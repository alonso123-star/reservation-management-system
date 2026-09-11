import { readFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'

// Explicit local operator action, not an HTTP endpoint or automatic demo account.
const email = (process.argv[2] || '').trim().toLowerCase()
if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 254) {
  throw new Error('Usage: node scripts/bootstrap-admin.mjs <existing-active-user-email>')
}
const config = Object.fromEntries(readFileSync(new URL('../.env', import.meta.url), 'utf8').split(/\r?\n/)
  .filter(line => line && !line.startsWith('#')).map(line => { const at = line.indexOf('='); return [line.slice(0, at), line.slice(at + 1)] }))
const escapedEmail = email.replaceAll("'", "''")
// Compatible with AdminUserService: role gate -> target user -> sessions.
const statement = `BEGIN;
SELECT set_config('rms.bootstrap.email', '${escapedEmail}', true);
SELECT id FROM roles WHERE name='ADMIN' FOR NO KEY UPDATE;
DO $bootstrap$
DECLARE target_id uuid; previous_role text;
BEGIN
  IF EXISTS(SELECT 1 FROM users u JOIN roles r ON r.id=u.role_id WHERE u.active AND r.name='ADMIN') THEN
    RAISE EXCEPTION 'An active administrator already exists; use the administrative API';
  END IF;
  SELECT u.id,r.name INTO target_id,previous_role FROM users u JOIN roles r ON r.id=u.role_id
    WHERE u.email=current_setting('rms.bootstrap.email') AND u.active FOR UPDATE OF u;
  IF target_id IS NULL THEN RAISE EXCEPTION 'Active registered user required'; END IF;
  UPDATE users SET role_id=(SELECT id FROM roles WHERE name='ADMIN'),security_version=security_version+1,
    version=version+1,updated_at=now() WHERE id=target_id;
  UPDATE refresh_sessions SET revoked_at=now() WHERE user_id=target_id AND revoked_at IS NULL;
  INSERT INTO audit_events(id,actor_id,action,resource_type,resource_id,changes,occurred_at,request_id)
    VALUES(gen_random_uuid(),NULL,'USER_ROLE_CHANGED','USER',target_id,
      jsonb_build_object('oldRole',previous_role,'newRole','ADMIN'),now(),gen_random_uuid());
END $bootstrap$;
COMMIT;`
try {
  execFileSync('docker', ['compose', 'exec', '-T', 'db', 'psql', '-v', 'ON_ERROR_STOP=1', '-U', config.POSTGRES_USER, '-d', config.POSTGRES_DB],
    { cwd: new URL('..', import.meta.url), input: statement, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'], timeout: 30000 })
  console.log('First administrator provisioned. Existing sessions revoked; sign in again. No password was generated or changed.')
} catch {
  throw new Error('Bootstrap rejected. Requires local Compose on V6, an existing active user and zero active administrators. No partial change was committed.')
}
