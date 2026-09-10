import assert from 'node:assert/strict'
import { randomUUID, randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'

// Local Compose only. Creates synthetic fixtures and removes only their exact IDs in finally.
// Does not print passwords, cookies, access tokens or local configuration.
const base = 'http://localhost:3000'
const config = Object.fromEntries(readFileSync(new URL('../.env', import.meta.url), 'utf8').split(/\r?\n/)
  .filter(line => line && !line.startsWith('#')).map(line => { const at = line.indexOf('='); return [line.slice(0, at), line.slice(at + 1)] }))
const fixture = { room: randomUUID(), type: randomUUID(), tag: randomUUID(), email: `smoke-${randomUUID()}@example.test`, password: randomBytes(24).toString('base64url') + 'aA1!' }
const sql = statement => execFileSync('docker', ['compose', 'exec', '-T', 'db', 'psql', '-v', 'ON_ERROR_STOP=1', '-U', config.POSTGRES_USER, '-d', config.POSTGRES_DB, '-c', statement],
  { cwd: new URL('..', import.meta.url), encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 30000 })
const cookies = new Map()
let csrf, access
async function request(path, method = 'GET', body, key) {
  const headers = new Headers({ Accept: 'application/json', Origin: base })
  if (cookies.size) headers.set('Cookie', [...cookies].map(([name, value]) => `${name}=${value}`).join('; '))
  if (csrf) headers.set(csrf.headerName, csrf.token)
  if (access) headers.set('Authorization', 'Bearer ' + access)
  if (key) headers.set('Idempotency-Key', key)
  if (body !== undefined) headers.set('Content-Type', 'application/json')
  const response = await fetch(base + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(30000) })
  for (const cookie of response.headers.getSetCookie()) {
    const pair = cookie.split(';')[0], split = pair.indexOf('=')
    cookies.set(pair.slice(0, split), pair.slice(split + 1))
  }
  return { status: response.status, value: response.status === 204 ? null : await response.json() }
}
try {
  sql(`BEGIN;
    INSERT INTO room_types(id,name,description,capacity,base_price,created_at,updated_at) VALUES('${fixture.type}','SMOKE-${fixture.tag}','Synthetic verification',2,125.50,now(),now());
    INSERT INTO rooms(id,room_type_id,code,floor,operational_status,created_at,updated_at) VALUES('${fixture.room}','${fixture.type}','SMOKE-${fixture.tag.slice(0, 20).toUpperCase()}',1,'ACTIVE',now(),now());
    COMMIT;`)
  const checkIn = new Date(Date.now() + 30 * 86400000).toISOString().slice(0, 10)
  const checkOut = new Date(Date.now() + 32 * 86400000).toISOString().slice(0, 10)
  const query = '/api/v1/rooms/availability?' + new URLSearchParams({ checkIn, checkOut, guests: '2', roomTypeId: fixture.type })
  const availability = async () => { const response = await request(query); assert.equal(response.status, 200); return response.value.totalElements }
  assert.equal((await request('/api/v1/room-types/' + fixture.type)).status, 200)
  assert.equal(await availability(), 1)
  csrf = (await request('/api/v1/auth/csrf')).value
  assert.equal((await request('/api/v1/auth/register', 'POST', { name: 'Synthetic smoke client', email: fixture.email, password: fixture.password })).status, 201)
  const grant = await request('/api/v1/auth/login', 'POST', { email: fixture.email, password: fixture.password })
  assert.equal(grant.status, 200); access = grant.value.accessToken
  const body = { roomId: fixture.room, checkIn, checkOut, guests: 2 }, key = randomUUID()
  const created = await request('/api/v1/reservations', 'POST', body, key)
  assert.equal(created.status, 201); assert.equal(created.value.status, 'CONFIRMED'); assert.equal(created.value.totalAmount, 251)
  const replay = await request('/api/v1/reservations', 'POST', body, key)
  assert.equal(replay.status, 201); assert.deepEqual(replay.value, created.value)
  assert.equal(await availability(), 0)
  const history = await request('/api/v1/reservations'); assert.equal(history.status, 200); assert.equal(history.value.totalElements, 1)
  assert.equal((await request('/api/v1/reservations/' + created.value.id)).status, 200)
  const cancelled = await request('/api/v1/reservations/' + created.value.id + '/cancel', 'POST', { version: created.value.version, reason: 'Synthetic smoke completed' })
  assert.equal(cancelled.status, 200); assert.equal(cancelled.value.status, 'CANCELLED')
  assert.equal(await availability(), 1)
  const docs = await request('/v3/api-docs'); assert.equal(docs.status, 200)
  assert.ok(docs.value.paths['/api/v1/reservations'].post.responses['201'])
  assert.ok(docs.value.paths['/api/v1/reservations/{id}/cancel'])
  assert.equal((await request('/api/v1/auth/logout', 'POST')).status, 204)
  console.log('Reservation smoke OK: catalog -> availability -> client login -> creation/replay -> blocked dates -> history/detail -> cancellation -> available dates -> OpenAPI.')
} finally {
  sql(`BEGIN;
    DELETE FROM idempotency_requests WHERE reservation_id IN (SELECT id FROM reservations WHERE room_id='${fixture.room}') OR actor_id IN (SELECT id FROM users WHERE email='${fixture.email}');
    DELETE FROM reservations WHERE room_id='${fixture.room}';
    DELETE FROM audit_events WHERE actor_id IN (SELECT id FROM users WHERE email='${fixture.email}');
    DELETE FROM refresh_tokens WHERE session_id IN (SELECT id FROM refresh_sessions WHERE user_id IN (SELECT id FROM users WHERE email='${fixture.email}'));
    DELETE FROM refresh_sessions WHERE user_id IN (SELECT id FROM users WHERE email='${fixture.email}');
    DELETE FROM users WHERE email='${fixture.email}';
    DELETE FROM rooms WHERE id='${fixture.room}';
    DELETE FROM room_types WHERE id='${fixture.type}';
    COMMIT;`)
  console.log('Synthetic business fixtures removed; existing catalog and accounts preserved.')
}
