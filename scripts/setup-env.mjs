import { randomBytes } from 'node:crypto'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'

const path = new URL('../.env', import.meta.url)
const template = new URL('../.env.example', import.meta.url)
let contents = readFileSync(existsSync(path) ? path : template, 'utf8')
for (const [key, value] of [
  ['POSTGRES_PASSWORD', randomBytes(24).toString('hex')],
  ['JWT_SECRET_BASE64', randomBytes(32).toString('base64')],
]) {
  const pattern = new RegExp('^' + key + '=(.*)$', 'm')
  const match = contents.match(pattern)
  if (!match) contents += '\n' + key + '=' + value + '\n'
  else if (!match[1].trim() || match[1].trim() === 'change-this-local-password') {
    contents = contents.replace(pattern, key + '=' + value)
  }
}
writeFileSync(path, contents, { mode: 0o600 })
console.log('Local .env ready. Existing configured secrets were preserved; no values are displayed.')
