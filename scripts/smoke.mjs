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
assert.equal((await info.json()).phase, 5)

const docs = await get('/v3/api-docs')
assert.equal(docs.status, 200)
const paths = (await docs.json()).paths
for (const path of ['/api/v1/system/info', '/api/v1/room-types', '/api/v1/rooms', '/api/v1/rooms/{id}/status', '/api/v1/staff/room-types']) {
  assert.ok(paths[path], 'OpenAPI must include ' + path)
}
for (const path of ['/api/v1/room-types', '/api/v1/rooms']) {
  assert.ok(paths[path].get.responses['200'].content['application/json'].schema)
  assert.ok(paths[path].post.responses['201'].content['application/json'].schema)
  assert.ok(paths[path].post.responses['409'].content['application/problem+json'].schema)
}
assert.equal((await get('/swagger-ui/index.html')).status, 200)
assert.equal((await get('/api/v1/users/me')).status, 401)
assert.equal((await get('/api/v1/staff/rooms')).status, 401)
for (const path of ['/api/v1/room-types', '/api/v1/rooms']) {
  const response = await get(path + '?page=0&size=2')
  assert.equal(response.status, 200)
  const catalog = await response.json()
  assert.ok(Array.isArray(catalog.items))
  assert.ok(catalog.items.length <= 2)
  assert.equal(catalog.page, 0)
  assert.equal(catalog.size, 2)
}
const availabilityPath = '/api/v1/rooms/availability'
const availabilityOperation = paths[availabilityPath]?.get
assert.ok(availabilityOperation, 'OpenAPI must describe availability')
assert.ok(availabilityOperation.responses['200'].content['application/json'].schema)
assert.ok(availabilityOperation.responses['400'].content['application/problem+json'].schema)
assert.deepEqual(availabilityOperation.parameters.filter(p => p.required).map(p => p.name).sort(), ['checkIn', 'checkOut', 'guests'])
assert.ok(!availabilityOperation.security?.length, 'Availability must be public')
const available = await get(availabilityPath + '?checkIn=2026-10-10&checkOut=2026-10-12&guests=2&page=0&size=2&sort=basePrice,asc')
assert.equal(available.status, 200)
const stay = await available.json()
assert.ok(Array.isArray(stay.items))
assert.equal(stay.page, 0)
assert.equal(stay.size, 2)
assert.ok(stay.items.length <= 2)
for (const room of stay.items) {
  assert.equal(room.nights, 2)
  assert.ok(room.roomType.capacity >= 2)
  assert.equal(typeof room.estimatedTotal, 'number')
  assert.equal(typeof room.roomType.currency, 'string')
}
for (const query of ['', '?checkIn=2026-10-10&checkOut=2026-10-10&guests=2', '?checkIn=2026-10-10&checkOut=2026-10-12&guests=0']) {
  const response = await get(availabilityPath + query)
  assert.equal(response.status, 400)
  assert.match(response.headers.get('content-type') ?? '', /application\/problem\+json/)
  const problem = await response.json()
  assert.ok(problem.code && problem.requestId)
}
assert.equal((await get('/availability')).status, 200, 'Public SPA search route must be served')
console.log('Smoke OK: frontend, assets, proxy, readiness + PostgreSQL, catalog, availability + validation, protected inventory, OpenAPI and Swagger.')
