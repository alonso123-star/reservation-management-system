import { beforeEach, describe, expect, it, vi } from 'vitest'

const user = { id: 'user-a', name: 'Ana', email: 'ana@example.test', role: 'CLIENTE', createdAt: '2026-09-08T12:00:00Z' }
const grant = (token = 'access-a') => ({ accessToken: token, expiresIn: 900, tokenType: 'Bearer', user })
const ok = (value: unknown) => new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } })
const csrf = () => ok({ headerName: 'X-XSRF-TOKEN', token: 'csrf-a' })

beforeEach(() => {
  vi.resetModules()
  vi.stubGlobal('BroadcastChannel', undefined)
  vi.stubGlobal('navigator', {})
})

describe('memory-only authentication client', () => {
  it('shares one rotation between simultaneous requests and never persists access tokens', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem')
    const calls: { path: string; bearer: string | null }[] = []
    vi.stubGlobal('fetch', vi.fn(async (path: string, init?: RequestInit) => {
      calls.push({ path, bearer: new Headers(init?.headers).get('Authorization') })
      if (path.endsWith('/csrf')) return csrf()
      if (path.endsWith('/refresh')) return ok(grant())
      return ok(user)
    }))
    const auth = await import('./auth')
    const results = await Promise.all([auth.api('/users/me'), auth.api('/users/me')])
    expect(results).toEqual([user, user])
    expect(calls.filter(call => call.path.endsWith('/refresh'))).toHaveLength(1)
    expect(calls.filter(call => call.path.endsWith('/me')).every(call => call.bearer === 'Bearer access-a')).toBe(true)
    expect(storage).not.toHaveBeenCalled()
  })

  it('renews after a 401 and retries the protected request once', async () => {
    let profiles = 0
    const tokens: (string | null)[] = []
    vi.stubGlobal('fetch', vi.fn(async (path: string, init?: RequestInit) => {
      if (path.endsWith('/csrf')) return csrf()
      if (path.endsWith('/login')) return ok(grant())
      if (path.endsWith('/refresh')) return ok(grant('access-b'))
      tokens.push(new Headers(init?.headers).get('Authorization'))
      return ++profiles === 1 ? new Response('{"code":"INVALID_SESSION"}', { status: 401 }) : ok(user)
    }))
    const auth = await import('./auth')
    await auth.login({ email: user.email, password: 'A test passphrase' })
    expect(await auth.api('/users/me')).toEqual(user)
    expect(tokens).toEqual(['Bearer access-a', 'Bearer access-b'])
  })

  it('clears the session when renewal is rejected', async () => {
    vi.stubGlobal('fetch', vi.fn(async (path: string) => path.endsWith('/csrf')
      ? csrf() : new Response('{"code":"SESSION_REVOKED","detail":"La sesión ha sido revocada."}', { status: 401 })))
    const auth = await import('./auth')
    await expect(auth.api('/users/me')).rejects.toMatchObject({ status: 401 })
    expect(auth.getAuthSnapshot()).toEqual({ user: null, ready: true })
  })

  it('obtains a fresh CSRF token after a rejected stale token', async () => {
    let attempts = 0
    let csrfRequests = 0
    vi.stubGlobal('fetch', vi.fn(async (path: string) => {
      if (path.endsWith('/csrf')) { csrfRequests++; return csrf() }
      return ++attempts === 1 ? new Response('{"code":"CSRF_INVALID"}', { status: 403 }) : ok(grant())
    }))
    const auth = await import('./auth')
    await auth.login({ email: user.email, password: 'A test passphrase' })
    expect(csrfRequests).toBe(2)
    expect(auth.getAuthSnapshot().user).toEqual(user)
  })

  it('does not restore a session from a late refresh response after logout', async () => {
    let finish: ((response: Response) => void) | undefined
    vi.stubGlobal('fetch', vi.fn(async (path: string) => {
      if (path.endsWith('/csrf')) return csrf()
      if (path.endsWith('/login')) return ok(grant())
      if (path.endsWith('/refresh')) return new Promise<Response>(resolve => { finish = resolve })
      return new Response(null, { status: 204 })
    }))
    const auth = await import('./auth')
    await auth.login({ email: user.email, password: 'A test passphrase' })
    const pending = auth.refreshSession()
    await vi.waitFor(() => expect(finish).toBeDefined())
    await auth.logout()
    finish!(ok(grant('late-access')))
    await pending
    expect(auth.getAuthSnapshot()).toEqual({ user: null, ready: true })
  })

  it('can log out through the refresh cookie when the bearer has expired', async () => {
    const logoutHeaders: (string | null)[] = []
    vi.stubGlobal('fetch', vi.fn(async (path: string, init?: RequestInit) => {
      if (path.endsWith('/csrf')) return csrf()
      if (path.endsWith('/login')) return ok(grant())
      logoutHeaders.push(new Headers(init?.headers).get('Authorization'))
      return logoutHeaders.length === 1 ? new Response('{}', { status: 401 }) : new Response(null, { status: 204 })
    }))
    const auth = await import('./auth')
    await auth.login({ email: user.email, password: 'A test passphrase' })
    await auth.logout()
    expect(logoutHeaders).toEqual(['Bearer access-a', null])
    expect(auth.getAuthSnapshot().user).toBeNull()
  })
})
