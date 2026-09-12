import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'

// Public synthetic credential, ONLY for the isolated demo/e2e databases.
export const demoPassword = 'Demo-Only-Reservation-2026!'
export const addresses = { admin: 'admin@example.test', employee: 'employee@example.test', client: 'client@example.test' }
export function hotelToday() { return new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Lima', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date()) }
export function shift(date, days) { return new Date(Date.parse(date + 'T12:00:00Z') + days * 86400000).toISOString().slice(0, 10) }

function client(base) {
  const cookies = new Map(); let csrf, access
  return {
    async request(path, method = 'GET', body, key, expected = 200) {
      const headers = new Headers({ Accept: 'application/json', Origin: base })
      if (cookies.size) headers.set('Cookie', [...cookies].map(([k, v]) => `${k}=${v}`).join('; '))
      if (csrf) headers.set(csrf.headerName, csrf.token)
      if (access) headers.set('Authorization', 'Bearer ' + access)
      if (key) headers.set('Idempotency-Key', key)
      if (body !== undefined) headers.set('Content-Type', 'application/json')
      const response = await fetch(base + '/api/v1' + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(30000) })
      for (const cookie of response.headers.getSetCookie()) { const pair = cookie.split(';')[0], at = pair.indexOf('='); cookies.set(pair.slice(0, at), pair.slice(at + 1)) }
      // Never include a response body or credential in assertion/error output.
      assert.equal(response.status, expected, `${method} ${path}: unexpected HTTP status`)
      return response.status === 204 ? null : response.json()
    },
    async prepare() { csrf = await this.request('/auth/csrf') },
    async login(email) { access = (await this.request('/auth/login', 'POST', { email, password: demoPassword })).accessToken },
  }
}

export async function seed(env, withStays = true) {
  env.assertDatabase()
  if (Number(env.sql('SELECT count(*) FROM users')) !== 0) throw new Error('Seeding requires an empty isolated database. Existing demo data preserved; use the documented reset explicitly.')
  const admin = client(env.base); await admin.prepare()
  const first = await admin.request('/auth/register', 'POST', { name: 'Demo Administrador', email: addresses.admin, password: demoPassword }, undefined, 201)
  // One-time bootstrap, before login; only the newly registered synthetic UUID.
  assert.match(first.id, /^[a-f0-9-]{36}$/)
  env.sql(`BEGIN; UPDATE users SET role_id=(SELECT id FROM roles WHERE name='ADMIN'), security_version=security_version+1, version=version+1 WHERE id='${first.id}';
    INSERT INTO audit_events(id,actor_id,action,resource_type,resource_id,occurred_at,request_id,changes) VALUES('${randomUUID()}',NULL,'DEMO_ADMIN_BOOTSTRAPPED','USER','${first.id}',now(),'${randomUUID()}','{"oldRole":"CLIENTE","newRole":"ADMIN"}'); COMMIT;`)
  await admin.login(addresses.admin)
  const employee = await admin.request('/users', 'POST', { name: 'Demo Recepcionista', email: addresses.employee, password: demoPassword, role: 'EMPLEADO' }, undefined, 201)
  const customer = await admin.request('/users', 'POST', { name: 'Demo Huésped', email: addresses.client, password: demoPassword, role: 'CLIENTE' }, undefined, 201)
  const types = []
  for (const [name, capacity, basePrice] of [['Standard', 2, 120], ['Deluxe', 3, 220], ['Suite', 4, 350]]) {
    types.push(await admin.request('/room-types', 'POST', { name, capacity, basePrice, active: true, description: `${name}: habitación de demostración con datos sintéticos.` }, undefined, 201))
  }
  const rooms = []
  for (const [code, type, floor, operationalStatus] of [['101', 0, 1, 'ACTIVE'], ['102', 0, 1, 'ACTIVE'], ['103', 0, 1, 'MAINTENANCE'], ['201', 1, 2, 'ACTIVE'], ['202', 1, 2, 'ACTIVE'], ['203', 1, 2, 'OUT_OF_SERVICE'], ['301', 2, 3, 'ACTIVE'], ['302', 2, 3, 'ACTIVE']]) {
    rooms.push(await admin.request('/rooms', 'POST', { code, roomTypeId: types[type].id, floor, operationalStatus, active: true }, undefined, 201))
  }
  const today = hotelToday(), stays = []
  if (withStays) {
    const customerApi = client(env.base); await customerApi.prepare(); await customerApi.login(addresses.client)
    const staff = client(env.base); await staff.prepare(); await staff.login(addresses.employee)
    for (const [index, start, finish, action] of [[0, 7, 9, 'pending'], [1, 0, 2, 'check-in'], [3, 0, 2, 'check-out'], [4, 10, 12, 'cancel'], [6, -1, 2, 'no-show']]) {
      let reservation = await customerApi.request('/reservations', 'POST', { roomId: rooms[index].id, checkIn: shift(today, start), checkOut: shift(today, finish), guests: 2 }, randomUUID(), 201)
      if (!['pending', 'no-show'].includes(action)) for (const result of ['DECLINED', 'APPROVED']) {
        const payment = await customerApi.request(`/reservations/${reservation.id}/payments`, 'POST', {}, randomUUID(), 201); assert.equal(payment.result, result)
      }
      if (['check-in', 'check-out'].includes(action)) reservation = await staff.request(`/reservations/${reservation.id}/check-in`, 'POST', { version: reservation.version })
      if (action === 'check-out') reservation = await staff.request(`/reservations/${reservation.id}/check-out`, 'POST', { version: reservation.version })
      if (action === 'cancel') reservation = await customerApi.request(`/reservations/${reservation.id}/cancel`, 'POST', { version: reservation.version, reason: 'Cancelación de demostración con reembolso simulado.' })
      if (action === 'no-show') reservation = await staff.request(`/reservations/${reservation.id}/no-show`, 'POST', { version: reservation.version })
      stays.push(reservation)
    }
    await customerApi.request('/auth/logout', 'POST', undefined, undefined, 204)
    await staff.request('/auth/logout', 'POST', undefined, undefined, 204)
  }
  await admin.request('/auth/logout', 'POST', undefined, undefined, 204)
  return { admin: first, employee, customer, types, rooms, today, stays }
}
