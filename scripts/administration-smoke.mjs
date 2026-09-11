import assert from 'node:assert/strict'
import { randomUUID, randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'

// Local Compose only; exact synthetic fixture identifiers, no changes to pre-existing accounts.
const base = 'http://localhost:3000'
const config = Object.fromEntries(readFileSync(new URL('../.env', import.meta.url), 'utf8').split(/\r?\n/).filter(l => l && !l.startsWith('#'))
  .map(l => { const i = l.indexOf('='); return [l.slice(0, i), l.slice(i + 1)] }))
const sql = statement => execFileSync('docker', ['compose', 'exec', '-T', 'db', 'psql', '-v', 'ON_ERROR_STOP=1', '-At', '-U', config.POSTGRES_USER, '-d', config.POSTGRES_DB, '-c', statement], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 30000 })
const activeAdmins = Number(sql("SELECT count(*) FROM users u JOIN roles r ON r.id=u.role_id WHERE u.active AND r.name='ADMIN'"))
assert.equal(activeAdmins, 0, 'Run this isolated local smoke before provisioning real administrators; existing users will not be altered.')
const tag = randomUUID(), email = `admin-smoke-${tag}@example.test`, customerEmail = `managed-${tag}@example.test`
const password = randomBytes(24).toString('base64url') + 'aA1!'
const type = randomUUID(), room = randomUUID(), booking = randomUUID(), approved = randomUUID()
let adminId, customerId
function client() {
  let token, csrf; const cookies = new Map()
  return {
    async request(path, method = 'GET', body) {
      const headers = new Headers({ Accept: 'application/json', Origin: base })
      if (token) headers.set('Authorization', 'Bearer ' + token)
      if (csrf) headers.set(csrf.headerName, csrf.token)
      if (cookies.size) headers.set('Cookie', [...cookies].map(([k, v]) => k + '=' + v).join('; '))
      if (body !== undefined) headers.set('Content-Type', 'application/json')
      const response = await fetch(base + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(30000) })
      for (const cookie of response.headers.getSetCookie()) { const pair = cookie.split(';')[0], at = pair.indexOf('='); cookies.set(pair.slice(0, at), pair.slice(at + 1)) }
      return { status: response.status, value: response.status === 204 ? null : await response.json() }
    },
    async prepare() { csrf = (await this.request('/api/v1/auth/csrf')).value },
    async login(address) { token = undefined; const r = await this.request('/api/v1/auth/login', 'POST', { email: address, password }); assert.equal(r.status, 200); token = r.value.accessToken; return r.value.user },
  }
}
const admin = client(), customer = client()
try {
  await admin.prepare()
  const registered = await admin.request('/api/v1/auth/register', 'POST', { name: 'Synthetic bootstrap admin', email, password }); assert.equal(registered.status, 201); adminId = registered.value.id
  execFileSync(process.execPath, ['scripts/bootstrap-admin.mjs', email], { stdio: ['ignore', 'pipe', 'pipe'], timeout: 30000 })
  await admin.login(email)
  assert.throws(() => execFileSync(process.execPath, ['scripts/bootstrap-admin.mjs', email], { stdio: ['ignore', 'pipe', 'pipe'], timeout: 30000 }))
  const created = await admin.request('/api/v1/users', 'POST', { name: 'Synthetic managed user', email: customerEmail, password, role: 'CLIENTE' }); assert.equal(created.status, 201); customerId = created.value.id
  assert.equal((await admin.request('/api/v1/users/' + customerId)).value.email, customerEmail)
  const listed = await admin.request('/api/v1/users?q=' + encodeURIComponent(customerEmail) + '&role=CLIENTE&active=true&page=0&size=1&sort=email,asc')
  assert.equal(listed.status, 200); assert.equal(listed.value.totalElements, 1); assert.equal(listed.value.items[0].id, customerId)
  await customer.prepare(); await customer.login(customerEmail)
  const promoted = await admin.request(`/api/v1/users/${customerId}/role`, 'PATCH', { role: 'EMPLEADO', version: 0 }); assert.equal(promoted.status, 200)
  const stale = await admin.request(`/api/v1/users/${customerId}/role`, 'PATCH', { role: 'ADMIN', version: 0 }); assert.equal(stale.status, 409); assert.equal(stale.value.code, 'USER_VERSION_CONFLICT')
  assert.equal((await customer.request('/api/v1/users/me')).status, 401)
  assert.equal((await customer.login(customerEmail)).role, 'EMPLEADO')
  assert.equal((await customer.request('/api/v1/users')).status, 403)
  assert.equal((await admin.request(`/api/v1/users/${customerId}/status`, 'PATCH', { active: false, version: 1 })).status, 200)
  assert.equal((await customer.request('/api/v1/users/me')).status, 401)
  assert.equal((await admin.request(`/api/v1/users/${customerId}/status`, 'PATCH', { active: true, version: 2 })).status, 200)
  assert.equal((await admin.request(`/api/v1/users/${customerId}/role`, 'PATCH', { role: 'CLIENTE', version: 3 })).status, 200)
  const adminView = (await admin.request('/api/v1/users/' + adminId)).value
  for (const [action, body] of [['role', { role: 'CLIENTE', version: adminView.version }], ['status', { active: false, version: adminView.version }]]) {
    const r = await admin.request(`/api/v1/users/${adminId}/${action}`, 'PATCH', body); assert.equal(r.status, 409); assert.equal(r.value.code, 'LAST_ACTIVE_ADMIN')
  }
  // Fixed historical period. Values are isolated from current-time auth/audit activity.
  assert.equal(Number(sql("SELECT count(*) FROM reservations WHERE created_at>='2040-10-10T05:00:00Z' AND created_at<'2040-10-13T05:00:00Z'")), 0)
  sql(`BEGIN;
    INSERT INTO room_types(id,name,description,capacity,base_price,created_at,updated_at) VALUES('${type}','ADMIN-${tag}','Synthetic',2,100.10,now(),now());
    INSERT INTO rooms(id,room_type_id,code,floor,operational_status,created_at,updated_at) VALUES('${room}','${type}','AD-${tag.slice(0, 20).toUpperCase()}',1,'ACTIVE',now(),now());
    INSERT INTO reservations(id,code,customer_id,created_by,room_id,check_in,check_out,guests,status,agreed_nightly_rate,total_amount,currency,created_at,updated_at)
      VALUES('${booking}','R-${tag}','${customerId}','${adminId}','${room}','2040-10-10','2040-10-12',2,'CONFIRMED',100.10,200.20,'PEN','2040-10-10T05:00:00Z',now());
    INSERT INTO payments(id,reservation_id,amount,currency,result,simulated_reference,actor_id,created_at)
      VALUES('${approved}','${booking}',200.20,'PEN','APPROVED','SIM-${approved}','${adminId}','2040-10-10T05:00:00Z'),
      ('${randomUUID()}','${booking}',200.20,'PEN','DECLINED','SIM-${randomUUID()}','${adminId}','2040-10-10T04:59:59Z'); COMMIT;`)
  let dashboard = await admin.request('/api/v1/admin/dashboard?from=2040-10-10&to=2040-10-13'); assert.equal(dashboard.status, 200)
  assert.equal(dashboard.value.reservationsCreated, 1); assert.equal(dashboard.value.arrivals, 1); assert.equal(dashboard.value.departures, 1); assert.equal(dashboard.value.roomNightsOccupied, 2); assert.equal(dashboard.value.netRevenue, 200.20)
  const eligible = Number(sql("SELECT count(*) FROM rooms r JOIN room_types t ON t.id=r.room_type_id WHERE r.active AND t.active AND r.operational_status='ACTIVE'"))
  assert.equal(dashboard.value.eligibleRooms, eligible); assert.equal(dashboard.value.roomNightsAvailable, eligible * 3)
  assert.equal(dashboard.value.occupancyPercent, Number((200 / (eligible * 3)).toFixed(2)))
  assert.equal(dashboard.value.hotelTimeZone, 'America/Lima'); assert.equal(dashboard.value.currency, 'PEN'); assert.equal(dashboard.value.approvedPayments, 200.20)
  sql(`INSERT INTO refunds(id,payment_id,amount,currency,reason,actor_id,created_at) VALUES('${randomUUID()}','${approved}',200.20,'PEN','Synthetic refund','${adminId}','2040-10-11T05:00:00Z');`)
  dashboard = await admin.request('/api/v1/admin/dashboard?from=2040-10-10&to=2040-10-13'); assert.equal(dashboard.value.netRevenue, 0); assert.equal(dashboard.value.refunds, 200.20)
  const events = await admin.request(`/api/v1/admin/audit-events?actorId=${adminId}&resource=USER&resourceId=${customerId}&action=USER_ROLE_CHANGED`)
  assert.equal(events.status, 200); assert.equal(events.value.totalElements, 2)
  assert.ok(events.value.items.every(e => e.changes.oldRole && e.changes.newRole))
  const correlated = await admin.request('/api/v1/admin/audit-events?requestId=' + events.value.items[0].requestId + '&size=1')
  assert.equal(correlated.status, 200); assert.equal(correlated.value.totalElements, 1); assert.equal(correlated.value.items[0].id, events.value.items[0].id)
  assert.ok(!JSON.stringify(events.value).includes(password)); assert.ok(!/password|token|cookie|securityVersion/i.test(JSON.stringify(events.value)))
  const docs = await admin.request('/v3/api-docs'); assert.equal(docs.status, 200)
  for (const path of ['/users', '/users/{id}', '/users/{id}/role', '/users/{id}/status', '/admin/dashboard', '/admin/audit-events']) assert.ok(docs.value.paths['/api/v1' + path])
  const schemaFor = path => { const ref = docs.value.paths[path].post.requestBody.content['application/json'].schema.$ref; return docs.value.components.schemas[ref.split('/').at(-1)].properties }
  assert.ok(schemaFor('/api/v1/users').role); assert.equal(schemaFor('/api/v1/users').password.writeOnly, true)
  assert.ok(schemaFor('/api/v1/reservations').roomId); assert.equal(schemaFor('/api/v1/reservations').password, undefined)
  for (const path of ['/admin/users', '/admin/users/new', '/admin/dashboard', '/admin/audit-events']) assert.equal((await fetch(base + path)).status, 200)
  assert.equal((await admin.request('/api/v1/auth/logout', 'POST')).status, 204)
  console.log('Administration smoke OK: bootstrap guard, login, create/read, role/status/session revocation, last ADMIN, exact dashboard payments/refunds, safe audit and OpenAPI.')
} finally {
  sql(`BEGIN;
    DELETE FROM refunds WHERE payment_id IN (SELECT id FROM payments WHERE reservation_id='${booking}');
    DELETE FROM payments WHERE reservation_id='${booking}'; DELETE FROM reservations WHERE id='${booking}';
    DELETE FROM rooms WHERE id='${room}'; DELETE FROM room_types WHERE id='${type}';
    DELETE FROM audit_events WHERE actor_id IN (SELECT id FROM users WHERE email IN ('${email}','${customerEmail}')) OR (resource_type='USER' AND resource_id IN (SELECT id FROM users WHERE email IN ('${email}','${customerEmail}')));
    DELETE FROM refresh_tokens WHERE session_id IN (SELECT s.id FROM refresh_sessions s JOIN users u ON u.id=s.user_id WHERE u.email IN ('${email}','${customerEmail}'));
    DELETE FROM refresh_sessions WHERE user_id IN (SELECT id FROM users WHERE email IN ('${email}','${customerEmail}'));
    DELETE FROM users WHERE email IN ('${email}','${customerEmail}'); COMMIT;`)
  console.log('Only synthetic administrative fixtures removed; pre-existing users preserved.')
}
