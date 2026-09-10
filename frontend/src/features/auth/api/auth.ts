export type Role = 'CLIENTE' | 'EMPLEADO' | 'ADMIN'
export type User = { id: string; name: string; email: string; role: Role; createdAt: string }
export type Session = { id: string; createdAt: string; expiresAt: string; current: boolean }
type Grant = { accessToken: string; tokenType: 'Bearer'; expiresIn: number; user: User }
type Snapshot = { user: User | null; ready: boolean }
type Csrf = { headerName: string; token: string }

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public fields?: Record<string, string>) {
    super(message)
  }
}

let accessToken: string | null = null
let expiresAt = 0
let snapshot: Snapshot = { user: null, ready: false }
let csrfValue: Csrf | null = null
let csrfPending: Promise<Csrf> | null = null
let refreshPending: Promise<void> | null = null
let generation = 0
const listeners = new Set<() => void>()
const channel = typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel('rms-session-events') : null

function publish(grant: Grant | null) {
  accessToken = grant?.accessToken ?? null
  expiresAt = grant ? Date.now() + grant.expiresIn * 1000 : 0
  snapshot = { user: grant?.user ?? null, ready: true }
  listeners.forEach(listener => listener())
}

channel?.addEventListener('message', event => {
  if (event.data === 'signed-out') { generation++; publish(null) }
})

export const getAuthSnapshot = () => snapshot
export function subscribeAuth(listener: () => void) {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

async function getCsrf(): Promise<Csrf> {
  if (csrfValue) return csrfValue
  if (!csrfPending) {
    csrfPending = fetch('/api/v1/auth/csrf', { credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.timeout(10000) })
      .then(async response => {
        if (!response.ok) throw new ApiError(response.status, 'CSRF_UNAVAILABLE', 'No se pudo conectar con el servidor.')
        const value = await response.json() as Csrf
        csrfValue = value
        return value
      })
      .finally(() => { csrfPending = null })
  }
  return csrfPending
}

async function raw<T>(path: string, method = 'GET', body?: unknown, authenticated = false, retryCsrf = true, idempotencyKey?: string): Promise<T> {
  const headers = new Headers({ Accept: 'application/json' })
  if (body !== undefined) headers.set('Content-Type', 'application/json')
  if (idempotencyKey) headers.set('Idempotency-Key', idempotencyKey)
  if (authenticated && accessToken) headers.set('Authorization', 'Bearer ' + accessToken)
  if (!['GET', 'HEAD'].includes(method)) {
    const csrf = await getCsrf()
    headers.set(csrf.headerName, csrf.token)
  }
  const response = await fetch('/api/v1' + path, {
    method, headers, credentials: 'same-origin', cache: 'no-store',
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(10000),
  })
  if (!response.ok) {
    const problem = await response.json().catch(() => ({})) as { code?: string; detail?: string; errors?: Record<string, string> }
    if (response.status === 403 && problem.code === 'CSRF_INVALID' && retryCsrf) {
      csrfValue = null
      return raw<T>(path, method, body, authenticated, false, idempotencyKey)
    }
    throw new ApiError(response.status, problem.code ?? 'REQUEST_FAILED',
      problem.detail ?? 'No se pudo completar la operación.', problem.errors)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export function refreshSession(): Promise<void> {
  if (!refreshPending) {
    const startedGeneration = generation
    const rotate = async () => {
      try {
        const grant = await raw<Grant>('/auth/refresh', 'POST')
        if (generation === startedGeneration) publish(grant)
      } catch (error) {
        if (generation === startedGeneration) publish(null)
        throw error
      }
    }
    // Serialize rotation across tabs sharing the HttpOnly cookie.
    refreshPending = (async () => {
      if (navigator.locks) await navigator.locks.request('rms-refresh-rotation', rotate)
      else await rotate()
    })().finally(() => { refreshPending = null })
  }
  return refreshPending
}

export function publicGet<T>(path: string): Promise<T> { return raw<T>(path) }

export async function api<T>(path: string, method = 'GET', body?: unknown, idempotencyKey?: string): Promise<T> {
  if (!accessToken || Date.now() >= expiresAt - 5000) await refreshSession()
  try {
    return await raw<T>(path, method, body, true, true, idempotencyKey)
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 401) throw error
    await refreshSession()
    return raw<T>(path, method, body, true, true, idempotencyKey)
  }
}

export async function register(input: { name: string; email: string; password: string }) {
  return raw<User>('/auth/register', 'POST', input)
}

export async function login(input: { email: string; password: string }) {
  const startedGeneration = ++generation
  const grant = await raw<Grant>('/auth/login', 'POST', input)
  if (generation === startedGeneration) publish(grant)
}

export async function logout() {
  try { await raw<void>('/auth/logout', 'POST', undefined, true) }
  catch (error) {
    if (!(error instanceof ApiError) || error.status !== 401) throw error
    // An expired/revoked bearer must not prevent revocation through the cookie.
    await raw<void>('/auth/logout', 'POST')
  }
  clearSession()
}

export function clearSession() {
  generation++
  publish(null)
  channel?.postMessage('signed-out')
}

export function errorMessage(error: unknown): string {
  return error instanceof ApiError ? error.message : 'No se pudo conectar. Comprueba tu conexión e inténtalo de nuevo.'
}
