import assert from 'node:assert/strict'

const base = (process.env.SMOKE_BASE_URL ?? 'http://localhost:3000').replace(/\/$/, '')
const get = path => fetch(base + path, { signal: AbortSignal.timeout(15000) })

const page = await get('/')
assert.equal(page.status, 200, 'Frontend must serve HTTP 200')
const html = await page.text()
assert.match(html, /Reservation Management System/)
const script = html.match(/src="([^"]+\.js)"/)?.[1]
assert.ok(script, 'Built frontend entry point must exist')
const asset = await get(script)
assert.equal(asset.status, 200, 'Built JavaScript must be served')
assert.match(asset.headers.get('content-type') ?? '', /javascript/)

const ready = await get('/api/v1/system/health/readiness')
assert.equal(ready.status, 200, 'Backend and PostgreSQL must be ready through the proxy')
assert.equal((await ready.json()).status, 'UP')

const info = await get('/api/v1/system/info')
assert.equal(info.status, 200)
assert.equal((await info.json()).phase, 2)

const docs = await get('/v3/api-docs')
assert.equal(docs.status, 200)
assert.ok((await docs.json()).paths['/api/v1/system/info'])
assert.equal((await get('/swagger-ui/index.html')).status, 200)
assert.equal((await get('/api/v1/users/me')).status, 401)
console.log('Smoke OK: frontend, assets, proxy, readiness + PostgreSQL, API, OpenAPI and Swagger.')
