import assert from 'node:assert/strict'
import mineflayer from 'mineflayer'

const port = Number(process.env.SCULPT_E2E_PORT)
const timeoutMs = Number(process.env.SCULPT_E2E_TIMEOUT_MS ?? 60_000)

function waitForMessage(bot, predicate, description) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      bot.removeListener('messagestr', listener)
      reject(new Error(`Timed out waiting for ${description}`))
    }, timeoutMs)
    const listener = message => {
      if (!predicate(message)) return
      clearTimeout(timer)
      bot.removeListener('messagestr', listener)
      resolve(message)
    }
    bot.on('messagestr', listener)
  })
}

function parseResult(message) {
  const marker = 'SCULPT_TEST '
  const start = message.indexOf(marker)
  assert.notEqual(start, -1, `Missing test marker: ${message}`)
  return Object.fromEntries(message.slice(start + marker.length)
    .split(';')
    .map(part => {
      const equals = part.indexOf('=')
      return [part.slice(0, equals), part.slice(equals + 1)]
    }))
}

const bot = mineflayer.createBot({
  host: '127.0.0.1',
  port,
  username: 'SculptFoliaE2E',
  version: '1.21.11',
  auth: 'offline'
})

try {
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Bot spawn timed out')), timeoutMs)
    bot.once('spawn', () => {
      clearTimeout(timer)
      resolve()
    })
    bot.once('error', reject)
    bot.once('kicked', reason => reject(new Error(`Bot kicked: ${reason}`)))
  })

  const message = waitForMessage(
    bot,
    text => text.includes('SCULPT_TEST foliaBlueprint='),
    'Folia blueprint result')
  bot.chat('/sculpttest folia-blueprint')
  const result = parseResult(await message)

  assert.equal(result.foliaBlueprint, 'true')
  assert.equal(result.localOwned, 'true')
  assert.equal(result.remoteOwned, 'false')
  assert.equal(result.pasteError, 'command.sculpt.blueprint.folia_cross_region')
  assert.equal(result.saveError, 'command.sculpt.blueprint.folia_cross_region')
  assert.equal(result.source, 'STONE', 'rejected paste changed its source anchor')

  console.log(JSON.stringify(result))
  bot.quit('Sculpt Folia integration complete')
} catch (error) {
  console.error(error)
  bot.quit('Sculpt Folia integration failed')
  process.exitCode = 1
}
