import { execFileSync } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('..', import.meta.url))
// These environments deliberately cannot target the normal local Compose project.
const environments = {
  demo: { POSTGRES_DB: 'rms_demo', POSTGRES_USER: 'rms_demo', POSTGRES_PORT: '5433', BACKEND_PORT: '8081', FRONTEND_PORT: '3001' },
  e2e: { POSTGRES_DB: 'rms_e2e', POSTGRES_USER: 'rms_e2e', POSTGRES_PORT: '5434', BACKEND_PORT: '8082', FRONTEND_PORT: '3002' },
}
export function sandbox(kind, initialize = false) {
  if (!Object.hasOwn(environments, kind)) throw new Error('Only isolated demo or e2e environments are allowed.')
  const expected = { ...environments[kind], HOTEL_TIME_ZONE: 'America/Lima', HOTEL_ARRIVAL_DEADLINE: '22:00' }
  const path = new URL(`../.env.${kind}`, import.meta.url)
  if (!existsSync(path) && initialize) {
    const values = { ...expected, POSTGRES_PASSWORD: randomBytes(24).toString('hex'), JWT_SECRET_BASE64: randomBytes(32).toString('base64') }
    writeFileSync(path, Object.entries(values).map(([key, value]) => `${key}=${value}`).join('\n') + '\n', { mode: 0o600, flag: 'wx' })
  }
  const values = Object.fromEntries(readFileSync(path, 'utf8').trim().split(/\r?\n/).map(line => {
    const at = line.indexOf('='); return [line.slice(0, at), line.slice(at + 1)]
  }))
  for (const [key, value] of Object.entries(expected)) if (values[key] !== value) throw new Error(`Unsafe ${kind} configuration: ${key}`)
  if (!/^[a-f0-9]{48}$/.test(values.POSTGRES_PASSWORD) || Buffer.from(values.JWT_SECRET_BASE64 ?? '', 'base64').length !== 32) throw new Error('Invalid isolated credentials; no Docker action performed.')
  const args = ['compose', '--project-name', `rms-${kind}`, '--env-file', fileURLToPath(path), '--file', 'compose.yaml']
  const compose = (commands, capture = false) => execFileSync('docker', [...args, ...commands], {
    cwd: root, env: { ...process.env, ...values }, encoding: 'utf8',
    stdio: capture ? ['ignore', 'pipe', 'pipe'] : 'inherit', timeout: 900000,
  })
  const sql = statement => compose(['exec', '-T', 'db', 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-At', '-U', values.POSTGRES_USER, '-d', values.POSTGRES_DB, '-c', statement], true).trim()
  const assertDatabase = () => {
    if (sql('SELECT current_database()') !== expected.POSTGRES_DB) throw new Error('Unexpected database; stopped.')
  }
  return { kind, compose, sql, assertDatabase, base: `http://localhost:${values.FRONTEND_PORT}` }
}

// Destructive reset is available exclusively to the disposable E2E database.
export function resetE2E() {
  const env = sandbox('e2e'); env.assertDatabase()
  env.sql('TRUNCATE audit_events, auth_rate_limits, idempotency_requests, refunds, payments, reservations, rooms, room_types, refresh_tokens, refresh_sessions, users;')
  return env
}
