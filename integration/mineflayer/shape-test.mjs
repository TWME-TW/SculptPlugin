import mineflayer from 'mineflayer'
const port = Number(process.env.SCULPT_E2E_PORT ?? 25599)
const sleep = ms => new Promise(r => setTimeout(r, ms))
const results = []
const check = (ok, label, detail = '') => {
  results.push({ ok })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  :: ' + detail : ''}`)
}
const bot = mineflayer.createBot({ host: '127.0.0.1', port, username: 'SculptE2E', version: '1.21.11', auth: 'offline' })
await new Promise((res, rej) => { const t = setTimeout(() => rej(new Error('spawn timeout')), 60000)
  bot.once('spawn', () => { clearTimeout(t); res() }); bot.once('error', rej); bot.once('kicked', r => rej(new Error('kicked: ' + r))) })
const chat = []
bot.on('messagestr', m => chat.push(m))
async function command(text, expect, tries = 40) {
  chat.length = 0; bot.chat(text)
  for (let i = 0; i < tries; i++) { await sleep(150); if (chat.some(expect)) return chat.find(expect) }
  return null
}
const field = (line, key) => {
  const m = line && line.match(new RegExp(key + '=([^;]*)'))
  return m ? m[1] : null
}

await command('/gamemode creative', m => /Creative/.test(m))
// Every run gets its own area: the world persists between runs, so reusing one
// would count the previous run's cells. The shape probe takes absolute
// coordinates, so the player does not need to stand there.
const stamp = Date.now()
const X = 20000 + (stamp % 400) * 8
const Z = 20000 + (stamp % 397) * 8
const floorY = 64
await command(`/fill ${X - 10} ${floorY} ${Z - 10} ${X + 10} ${floorY} ${Z + 16} minecraft:bedrock`,
  m => /Successfully filled/.test(m))
await command(`/fill ${X - 10} ${floorY + 1} ${Z - 10} ${X + 10} ${floorY + 10} ${Z + 16} minecraft:air`,
  m => /Successfully filled|No blocks/.test(m))
await sleep(900)
bot.chat('/sculpt edit on'); await sleep(2500)
await command('/sculpt resolution 2', m => /2|resolution/i.test(m))
await sleep(800)

// A single control line: three points, the middle one raised, so the curve
// through them bows upward.
const low = (floorY + 2).toFixed(2)
const high = (floorY + 5).toFixed(2)
const z = Z + 6
const placed = await command(
  `/sculpttest shape new ${X - 3}.00 ${low} ${z}.00 ${X}.00 ${high} ${z}.00 ${X + 3}.00 ${low} ${z}.00`,
  m => m.includes('SCULPT_TEST '))
check(/problem=null/.test(placed), 'one line is a complete surface on its own', placed)
check(/lines=1/.test(placed), 'the line is the only control line', placed)

const before = await command(`/sculpttest scan ${X - 8} ${floorY} ${Z - 4} ${X + 8} ${floorY + 10} ${Z + 14}`,
  m => m.includes('SCULPT_TEST '))
const built = await command('/sculpttest build', m => m.includes('build=ok'))
check(built != null, 'right-click builds the surface', built)
await sleep(2500)

const after = await command(`/sculpttest scan ${X - 8} ${floorY} ${Z - 4} ${X + 8} ${floorY + 10} ${Z + 14}`,
  m => m.includes('SCULPT_TEST '))
const activeBefore = Number(field(before, 'active'))
const activeAfter = Number(field(after, 'active'))
check(activeAfter > activeBefore,
  'the single line builds cells', `${activeBefore} -> ${activeAfter} sculpt blocks`)

// The cells must follow the curve, not a straight line: they reach up toward
// the raised middle point, above where the line's own ends sit.
const reach = Number(field(after, 'activeMaxY'))
check(reach > floorY + 2,
  'the built curve bows up toward the middle point',
  `highest cell y=${reach}, line ends at y=${floorY + 2}`)

// Shift + left-click in the air starts the next line. Looking at the sky makes
// sure the click really is in the air, which is what used to be ignored.
await bot.look(0, Math.PI / 2, true)   // mineflayer negates pitch on the wire, so +up
await sleep(400)
const air = await command('/sculpttest airclick', m => m.includes('SCULPT_TEST '))
const before2 = Number(field(air, 'before'))
const after2 = Number(field(air, 'after'))
check(after2 === before2 + 1, 'Shift + left-click in the air starts a new line', air)
check(/target=false/.test(air), 'the click really was in the air, not on a block', air)

// A line with a single point is still rejected.
const short = await command(`/sculpttest shape line ${X}.00 ${low} ${z}.00`, m => m.includes('SCULPT_TEST '))
check(/line_points/.test(short), 'a one-point line is still rejected', short)

console.log(`\n${results.filter(r => r.ok).length}/${results.length} checks passed`)
bot.quit('done')
process.exitCode = results.every(r => r.ok) ? 0 : 1
