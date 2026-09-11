import assert from 'node:assert/strict'
import { randomBytes, randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'

// Local Compose only. Exact synthetic UUIDs; no existing user is promoted or deleted.
// Fixed-clock deterministic HTTP E2E lives in ReceptionIT; here dates follow the running hotel's day.
const base = 'http://localhost:3000'
const config = Object.fromEntries(readFileSync(new URL('../.env', import.meta.url), 'utf8').split(/\r?\n/)
  .filter(line => line && !line.startsWith('#')).map(line => { const at = line.indexOf('='); return [line.slice(0, at), line.slice(at + 1)] }))
const sql = statement => execFileSync('docker', ['compose', 'exec', '-T', 'db', 'psql', '-v', 'ON_ERROR_STOP=1', '-U', config.POSTGRES_USER, '-d', config.POSTGRES_DB, '-c', statement],
  { cwd: new URL('..', import.meta.url), encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 30000 })
const tag = randomUUID(), type = randomUUID(), room = randomUUID()
const password = randomBytes(24).toString('base64url') + 'aA1!'
const email = role => `reception-${role}-${tag}@example.test`
const shift = (date, days) => new Date(Date.parse(date + 'T12:00:00Z') + days * 86400000).toISOString().slice(0, 10)
function client() {
  const cookies = new Map(); let access, csrf
  return {
    async request(path, method = 'GET', body, key) {
      const headers = new Headers({ Accept: 'application/json', Origin: base })
      if (cookies.size) headers.set('Cookie', [...cookies].map(([k, v]) => `${k}=${v}`).join('; '))
      if (csrf) headers.set(csrf.headerName, csrf.token)
      if (access) headers.set('Authorization', 'Bearer ' + access)
      if (body !== undefined) headers.set('Content-Type', 'application/json')
      if (key) headers.set('Idempotency-Key', key)
      const response = await fetch(base + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(30000) })
      for (const cookie of response.headers.getSetCookie()) { const pair = cookie.split(';')[0], split = pair.indexOf('='); cookies.set(pair.slice(0, split), pair.slice(split + 1)) }
      return { status: response.status, value: response.status === 204 ? null : await response.json() }
    },
    async register(role) {
      csrf = (await this.request('/api/v1/auth/csrf')).value
      assert.equal((await this.request('/api/v1/auth/register', 'POST', { name: 'Synthetic reception ' + role, email: email(role), password })).status, 201)
    },
    async login(role) {
      const r = await this.request('/api/v1/auth/login', 'POST', { email: email(role), password }); assert.equal(r.status, 200); access = r.value.accessToken
    },
  }
}
const customer = client(), staff = client()
try {
  sql(`BEGIN; INSERT INTO room_types(id,name,description,capacity,base_price,created_at,updated_at) VALUES('${type}','RECEPTION-${tag}','Synthetic verification',2,125.50,now(),now());
    INSERT INTO rooms(id,room_type_id,code,floor,operational_status,created_at,updated_at) VALUES('${room}','${type}','RC-${tag.slice(0, 20).toUpperCase()}',1,'ACTIVE',now(),now()); COMMIT;`)
  await customer.register('client'); await customer.login('client'); await staff.register('staff')
  sql(`UPDATE users SET role_id=(SELECT id FROM roles WHERE name='EMPLEADO'),security_version=security_version+1 WHERE email='${email('staff')}';`)
  await staff.login('staff')
  const zone = config.HOTEL_TIME_ZONE || 'America/Lima'
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date()).map(p => [p.type, p.value]))
  const today = `${parts.year}-${parts.month}-${parts.day}`
  async function book(checkIn, checkOut) {
    const query = '/api/v1/rooms/availability?' + new URLSearchParams({ checkIn, checkOut, guests: '2', roomTypeId: type })
    const found = await customer.request(query); assert.equal(found.status, 200); assert.equal(found.value.totalElements, 1)
    const created = await customer.request('/api/v1/reservations', 'POST', { roomId: room, checkIn, checkOut, guests: 2 }, randomUUID())
    assert.equal(created.status, 201); return { value: created.value, query }
  }
  const booking = await book(today, shift(today, 5)), id = booking.value.id
  for (const expected of ['DECLINED', 'APPROVED']) {
    const payment = await customer.request(`/api/v1/reservations/${id}/payments`, 'POST', {}, randomUUID())
    assert.equal(payment.status, 201); assert.equal(payment.value.result, expected)
  }
  const detail = await staff.request('/api/v1/reservations/' + id)
  assert.equal(detail.value.reception.hotelDate, today); assert.equal(detail.value.reception.canCheckIn, true)
  assert.equal((await customer.request(`/api/v1/reservations/${id}/check-in`, 'POST', { version: 0 })).status, 403)
  const admitted = await staff.request(`/api/v1/reservations/${id}/check-in`, 'POST', { version: 0 })
  assert.equal(admitted.status, 200); assert.equal(admitted.value.status, 'CHECKED_IN'); assert.ok(admitted.value.checkedInAt)
  // Compare persisted values: PostgreSQL timestamps have microsecond precision, Java Instant can have nanoseconds.
  const persistedAdmission = await staff.request('/api/v1/reservations/' + id)
  assert.equal(persistedAdmission.status, 200); assert.equal(persistedAdmission.value.status, 'CHECKED_IN'); assert.ok(persistedAdmission.value.checkedInAt)
  const departed = await staff.request(`/api/v1/reservations/${id}/check-out`, 'POST', { version: admitted.value.version })
  assert.equal(departed.status, 200); assert.equal(departed.value.status, 'CHECKED_OUT'); assert.ok(departed.value.checkedOutAt)
  for (const field of ['checkIn', 'checkOut', 'agreedNightlyRate', 'totalAmount', 'currency']) assert.equal(departed.value[field], booking.value[field])
  assert.equal(departed.value.checkedInAt, persistedAdmission.value.checkedInAt)
  assert.equal((await customer.request(booking.query)).value.totalElements, 0)
  assert.equal((await staff.request(`/api/v1/reservations/${id}/check-out`, 'POST', { version: departed.value.version })).status, 409)
  // A separate room keeps the no-show stay independent of the intentionally blocking checkout.
  const noShowRoom = randomUUID()
  sql(`INSERT INTO rooms(id,room_type_id,code,floor,operational_status,created_at,updated_at) VALUES('${noShowRoom}','${type}','NS-${tag.slice(0, 20).toUpperCase()}',1,'ACTIVE',now(),now());`)
  const absent = await customer.request('/api/v1/reservations', 'POST', { roomId: noShowRoom, checkIn: shift(today, -1), checkOut: shift(today, 2), guests: 2 }, randomUUID())
  assert.equal(absent.status, 201)
  const absencePath = '/api/v1/reservations/' + absent.value.id
  assert.equal((await staff.request(absencePath)).value.reception.canNoShow, true)
  const noShow = await staff.request(absencePath + '/no-show', 'POST', { version: 0 })
  assert.equal(noShow.status, 200); assert.equal(noShow.value.status, 'NO_SHOW'); assert.equal(noShow.value.checkedOutAt, null)
  const available = await customer.request('/api/v1/rooms/availability?' + new URLSearchParams({ checkIn: today, checkOut: shift(today, 2), guests: '2', roomTypeId: type }))
  assert.equal(available.status, 200); assert.ok(available.value.items.some(r => r.id === noShowRoom))
  const payments = await staff.request(`/api/v1/reservations/${id}/payments`)
  assert.equal(payments.value.settlement, 'PAID'); assert.ok(payments.value.attempts.items.every(p => p.refund === null))
  const docs = await staff.request('/v3/api-docs'); assert.equal(docs.status, 200)
  for (const action of ['check-in', 'check-out', 'no-show']) assert.ok(docs.value.paths[`/api/v1/reservations/{id}/${action}`].post.responses['200'])
  assert.equal((await customer.request('/api/v1/auth/logout', 'POST')).status, 204)
  assert.equal((await staff.request('/api/v1/auth/logout', 'POST')).status, 204)
  console.log('Reception smoke OK: registration/login -> search -> booking -> full payment -> staff check-in -> early checkout, unchanged stay/price/blocking -> no-show -> availability; roles and OpenAPI verified.')
} finally {
  sql(`BEGIN;
    DELETE FROM idempotency_requests WHERE reservation_id IN (SELECT r.id FROM reservations r JOIN rooms rm ON rm.id=r.room_id WHERE rm.room_type_id='${type}') OR actor_id IN (SELECT id FROM users WHERE email IN ('${email('client')}','${email('staff')}'));
    DELETE FROM refunds WHERE payment_id IN (SELECT p.id FROM payments p JOIN reservations r ON r.id=p.reservation_id JOIN rooms rm ON rm.id=r.room_id WHERE rm.room_type_id='${type}');
    DELETE FROM payments WHERE reservation_id IN (SELECT r.id FROM reservations r JOIN rooms rm ON rm.id=r.room_id WHERE rm.room_type_id='${type}');
    DELETE FROM reservations WHERE room_id IN (SELECT id FROM rooms WHERE room_type_id='${type}');
    DELETE FROM audit_events WHERE actor_id IN (SELECT id FROM users WHERE email IN ('${email('client')}','${email('staff')}'));
    DELETE FROM refresh_tokens WHERE session_id IN (SELECT s.id FROM refresh_sessions s JOIN users u ON u.id=s.user_id WHERE u.email IN ('${email('client')}','${email('staff')}'));
    DELETE FROM refresh_sessions WHERE user_id IN (SELECT id FROM users WHERE email IN ('${email('client')}','${email('staff')}'));
    DELETE FROM users WHERE email IN ('${email('client')}','${email('staff')}');
    DELETE FROM rooms WHERE room_type_id='${type}'; DELETE FROM room_types WHERE id='${type}'; COMMIT;`)
  console.log('Exact synthetic reception fixtures removed; pre-existing records preserved.')
}
