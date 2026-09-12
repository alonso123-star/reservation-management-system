import { sandbox } from './sandbox.mjs'
import { seed } from './demo-data.mjs'

const [kind, action] = process.argv.slice(2)
if (!['demo', 'e2e'].includes(kind) || !['up', 'seed', 'status', 'stop', 'down', 'reset'].includes(action)) {
  console.error('Usage: node scripts/demo.mjs <demo|e2e> <up|seed|status|stop|down|reset>'); process.exit(1)
}
try {
  const env = sandbox(kind, action === 'up')
  if (action === 'up') {
    env.compose(['up', '--build', '--wait', '--wait-timeout', '240'])
    console.log(`Isolated ${kind} ready: ${env.base}. Seed demo explicitly; Playwright prepares its own fixtures.`)
  } else if (action === 'seed') {
    if (kind !== 'demo') throw new Error('Playwright controls E2E data; manual seed is for demo only.')
    const data = await seed(env)
    console.log(`Demo ready: 3 accounts, ${data.types.length} types, ${data.rooms.length} rooms, ${data.stays.length} reservations. Credentials and date guidance: README.md.`)
  } else if (action === 'status') env.compose(['ps'])
  else if (action === 'stop') env.compose(['stop'])
  else if (action === 'down') env.compose(['down'])
  else env.compose(['down', '--volumes']) // Explicit reset: only rms-demo or rms-e2e project volumes.
} catch (error) {
  console.error(error instanceof Error && !('stderr' in error) ? error.message : 'Isolated Docker operation failed. Check Docker and environment health.'); process.exitCode = 1
}
