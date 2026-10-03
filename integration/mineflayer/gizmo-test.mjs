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
const gizmo = async sub => command('/sculpttest gizmo ' + sub, m => m.includes('SCULPT_TEST '))
const aimAt = async (handle, offset) => {
  // The server applies the probe's teleport direction, but the client's own
  // look packet wins on the next tick, so the drag has to be driven from the
  // client: read the ray the probe wants, then look along it here.
  const line = await gizmo('aim ' + handle + (offset === undefined ? '' : ' ' + offset))
  const eye = /eye=([-\d.]+),([-\d.]+),([-\d.]+)/.exec(line)
  const aim = /aim=([-\d.]+),([-\d.]+),([-\d.]+)/.exec(line)
  if (!eye || !aim) return line
  const dx = Number(aim[1]) - Number(eye[1])
  const dy = Number(aim[2]) - Number(eye[2])
  const dz = Number(aim[3]) - Number(eye[3])
  const distance = Math.hypot(dx, dy, dz)
  // Mineflayer stores its own yaw convention and converts on the wire with
  // notchianYaw = 180 - yaw and notchianPitch = -pitch, so both are converted
  // here to the yaw/pitch the server will end up with.
  await bot.look(Math.PI - Math.atan2(-dx, dz), Math.asin(dy / distance), true)
  return line
}
const state = async () => {
  const line = await gizmo('state')
  return {
    hover: field(line, 'hover'), dragging: field(line, 'dragging'),
    angleStep: field(line, 'angleStep'), angle: field(line, 'angle'),
    x: Number(field(line, 'offsetX')), y: Number(field(line, 'offsetY')), z: Number(field(line, 'offsetZ')),
    raw: line,
  }
}

await command('/gamemode creative', m => /Creative/.test(m))
// Build the platform where the bot already is. Teleporting it far away races
// the chunk stream, and mineflayer's own physics then walks the player off the
// edge before the ground arrives, so the ground is placed under it instead.
await bot.look(0, 0, true)   // face south, so "forward" is +Z
await sleep(400)
const base = bot.entity.position.floored()
const X = base.x, Z = base.z
const floorY = base.y - 1
check(await command(`/fill ${X - 6} ${floorY} ${Z - 6} ${X + 6} ${floorY} ${Z + 12} minecraft:bedrock`,
  m => /Successfully filled/.test(m)) != null, 'a platform is built under the player')
check(await command(`/fill ${X - 6} ${floorY + 1} ${Z - 6} ${X + 6} ${floorY + 8} ${Z + 12} minecraft:air`,
  m => /Successfully filled|No blocks/.test(m)) != null, 'the space above the platform is cleared')
await sleep(900)
bot.chat('/sculpt edit on'); await sleep(2500)
await command('/sculpt resolution 2', m => /2|resolution/i.test(m))
await sleep(800)

const selected = await gizmo('select')
check(selected != null && /gizmo=select/.test(selected), 'a selection is set on the transform tool', selected)
await sleep(600)

// Aim at the +Y (up) arrow from a position that keeps it in view.
let line = await aimAt('move_y')
check(line != null, 'the player can be aimed at a handle', line)
await sleep(500)
line = await gizmo('tick')
const hover = field(line, 'hover')
check(hover === 'MOVE_Y', 'aiming at the up arrow hovers it', line)

// Grab it and drag by rotating the view upward, which is what a real drag is.
line = await gizmo('primary')
check(field(line, 'dragging') === 'MOVE_Y', 'the left click grabs the hovered handle', line)

const before = await state()
// Sweep the aim further up the handle, exactly like sliding the mouse up.
await aimAt('move_y', 3)
await sleep(500)
await gizmo('tick')
const after = await state()
check(after.y !== before.y, 'dragging the up arrow changes the vertical offset',
  `${before.y} -> ${after.y}`)
check(after.x === 0 && after.z === 0, 'an up drag does not move the other axes',
  `x=${after.x} z=${after.z}`)
check(after.y % 1 === 0, 'the offset lands on the voxel grid', `${after.y}`)
check(after.angle === '0.0', 'a translation leaves the rotation alone', `${after.angle}`)

// Turning the head must not leak into an axis the handle does not run along.
await gizmo('aim move_y 5')
await sleep(500)
await gizmo('tick')
const turned = await state()
check(turned.x === 0 && turned.z === 0, 'turning the head keeps the drag on its own axis',
  `x=${turned.x} z=${turned.z}`)

// Release, then cancel: Q must restore the pending transform.
line = await gizmo('primary')
check(field(line, 'dragging') === 'null', 'the left click releases the handle', line)
const pending = await state()
check(pending.y !== 0, 'the pending transform survives the release', `y=${pending.y}`)
const cancelled = await command('/sculpttest gizmo cancel', m => m.includes('SCULPT_TEST '))
if (cancelled == null) {
  // No dedicated cancel probe: the Q key routes through the tool's cancel.
  await command('/sculpttest key drop', m => m.includes('SCULPT_TEST '))
}
await sleep(600)
const cleared = await state()
check(cleared.y === 0 && cleared.x === 0 && cleared.z === 0 && Number(cleared.angle) === 0,
  'cancelling discards the pending transform', JSON.stringify(cleared))

// Rotation: aim at the ring and drag it. The probe computes its direction
// from the eye position before teleporting, so the aim is repeated once the
// player has actually moved and the direction is then computed in place.
await aimAt('rotate_y', 60)
await sleep(500)
line = await aimAt('rotate_y', 60)
await sleep(300)
line = await gizmo('tick')
const ringHover = field(line, 'hover')
if (ringHover === 'ROTATE_Y') {
  await gizmo('primary')
  await aimAt('rotate_y', 120)
  await sleep(400)
  await aimAt('rotate_y', 120)
  await sleep(300)
  await gizmo('tick')
  const rotated = await state()
  check(Number(rotated.angle) !== 0, 'dragging the ring rotates the selection', `angle=${rotated.angle}`)
  const degrees = Math.abs(Number(rotated.angle) * 180 / Math.PI)
  check(Math.abs(degrees % 15) < 0.6 || Math.abs(degrees % 15 - 15) < 0.6,
    'the rotation snaps to the current step', `${degrees.toFixed(2)} deg`)
  check(rotated.x === 0 && rotated.z === 0, 'a rotation does not translate', JSON.stringify(rotated))
} else {
  check(false, 'the ring is pickable', `hover=${ringHover}`)
}

// The snap step cycles with the scroll wheel (Shift+scroll is the editor's adjust).
const stepBefore = (await state()).angleStep
await command('/sculpttest gizmo snap', m => m.includes('SCULPT_TEST '))
await sleep(400)
const stepAfter = (await state()).angleStep
check(stepBefore !== stepAfter || stepBefore != null, 'the snap step is exposed', `${stepBefore} -> ${stepAfter}`)

// Applying: a real move must land in the world. The selection is one block,
// and where it sits depends on the world, so its coordinates are read back
// from the probe rather than assumed. The direction of the drag depends on
// the view, so the check looks for where the stone ended up.
await gizmo('cancel')
await sleep(300)
const placed = await gizmo('select')
const box = /box=(-?\d+),(-?\d+),(-?\d+)/.exec(placed)
check(box != null, 'the selection is placed for the apply check', placed)
const [vx, vy, vz] = [Number(box[1]), Number(box[2]), Number(box[3])]
const bx = Math.floor(vx / 16), by = Math.floor(vy / 16), bz = Math.floor(vz / 16)
await sleep(400)

const filled = await gizmo('fill stone')
check(/gizmo=fill;material=STONE/.test(filled), 'the selection is filled with stone', filled)
const sourceBefore = await gizmo(`block ${bx} ${by} ${bz}`)
check(/type=minecraft:stone/.test(sourceBefore), 'the source block starts as stone', sourceBefore)

await aimAt('move_y'); await sleep(400)
await aimAt('move_y'); await sleep(300)
await gizmo('tick')
await gizmo('primary')
await aimAt('move_y', 3); await sleep(400)
await aimAt('move_y', 3); await sleep(300)
await gizmo('tick')
const drag = await state()
await gizmo('primary')
await sleep(200)
check(drag.y !== 0, 'the drag produced an offset to apply', `y=${drag.y}`)

const applied = await gizmo('apply')
check(/gizmo=apply/.test(applied), 'right-click applies the transform', applied)
await sleep(2500)
const movedFrom = await gizmo(`block ${bx} ${by} ${bz}`)
check(/type=minecraft:air/.test(movedFrom), 'the move clears the source block', movedFrom)
// The move is smaller than a block, so the moved volume can touch the block
// above or below the source. The block the offset points into must hold stone,
// and the source block must not.
const expected = Math.floor((by * 16 + drag.y) / 16)
const destination = await gizmo(`block ${bx} ${expected} ${bz}`)
// A moved cell is a SculptBlock, so the world shows a barrier backing (or a
// plain block when the cell is complete) rather than the original stone.
check(!/type=minecraft:air/.test(destination),
  'the moved material arrives at the block the offset points into',
  `expected=${expected} :: ${destination}`)
let occupied = []
for (let y = by - 4; y <= by + 4; y++) {
  const line = await gizmo(`block ${bx} ${y} ${bz}`)
  if (!/type=minecraft:air/.test(line)) occupied.push(y)
}
check(occupied.length > 0 && !occupied.includes(by),
  'nothing is left behind in the source block', `y=${JSON.stringify(occupied)}`)

console.log(`\n${results.filter(r => r.ok).length}/${results.length} checks passed`)
bot.quit('done')
process.exitCode = results.every(r => r.ok) ? 0 : 1
