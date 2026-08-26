import assert from 'node:assert/strict'
import mineflayer from 'mineflayer'
import { Vec3 } from 'vec3'

const port = Number(process.env.SCULPT_E2E_PORT)
const editor = process.env.SCULPT_E2E_EDITOR ?? 'worldedit'
const timeoutMs = Number(process.env.SCULPT_E2E_TIMEOUT_MS ?? 60_000)

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

function waitForMessage(bot, predicate, description, timeout = timeoutMs) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      bot.removeListener('messagestr', listener)
      reject(new Error(`Timed out waiting for ${description}`))
    }, timeout)
    const listener = message => {
      if (!predicate(message)) return
      clearTimeout(timer)
      bot.removeListener('messagestr', listener)
      resolve(message)
    }
    bot.on('messagestr', listener)
  })
}

async function command(bot, text, predicate, description = text) {
  const message = waitForMessage(bot, predicate, description)
  bot.chat(text)
  return message
}

function parseInspection(message) {
  const marker = 'SCULPT_TEST '
  const start = message.indexOf(marker)
  assert.notEqual(start, -1, `Missing inspection marker: ${message}`)
  return Object.fromEntries(message.slice(start + marker.length)
    .split(';')
    .map(part => {
      const equals = part.indexOf('=')
      return [part.slice(0, equals), part.slice(equals + 1)]
    }))
}

async function inspect(bot, x, y, z, predicate = state => state.active === 'true') {
  const deadline = Date.now() + timeoutMs
  let last
  while (Date.now() < deadline) {
    const response = await command(
      bot,
      `/sculpttest inspect ${x} ${y} ${z}`,
      message => message.includes('SCULPT_TEST '),
      `inspection at ${x},${y},${z}`
    )
    last = parseInspection(response)
    if (predicate(last)) return last
    await sleep(500)
  }
  throw new Error(`Inspection did not converge at ${x},${y},${z}: ${JSON.stringify(last)}`)
}

async function shulkersAt(bot, x, z, minimum = 1) {
  const deadline = Date.now() + timeoutMs
  let matches = []
  while (Date.now() < deadline) {
    matches = Object.values(bot.entities).filter(entity =>
      entity.name === 'shulker' &&
      Math.floor(entity.position.x) === x &&
      Math.floor(entity.position.z) === z)
    if (matches.length >= minimum) return matches
    await sleep(100)
  }
  const visibleShulkers = Object.values(bot.entities)
    .filter(entity => entity.name === 'shulker')
    .map(entity => `${entity.id}@${entity.position}`)
  throw new Error(`Expected ${minimum} Shulker(s) at x=${x},z=${z}; found ${matches.length}; visible=${visibleShulkers}`)
}

async function frontShulkerTarget(bot, x) {
  const shulkers = await shulkersAt(bot, x, 0)
  const front = shulkers.toSorted((a, b) =>
    b.position.z - a.position.z ||
    a.position.y - b.position.y ||
    a.position.x - b.position.x)[0]
  const serverY = front.position.y >= 69 ? front.position.y - 5 : front.position.y
  const aimedCell = new Vec3(front.position.x, serverY + 0.25, front.position.z)
  return { front, aimedCell }
}

async function exerciseDirectShulkerLeftClick(bot, x, initialState) {
  const { front, aimedCell } = await frontShulkerTarget(bot, x)
  bot.chat(`/tp ${bot.username} ${front.position.x} 64 3.5`)
  await sleep(350)
  await bot.lookAt(aimedCell, true)
  bot.attack(front)
  return inspect(bot, x, 64, 0,
    state => Number(state.removed) > Number(initialState.removed))
}

async function exerciseDirectShulkerRightClick(bot, x) {
  bot.chat('/item replace entity @s weapon.mainhand with minecraft:stone')
  await sleep(300)
  const click = parseInspection(await command(
    bot,
    `/sculpttest click ${x} 64 0 shulker-sneak-right`,
    message => message.includes('SCULPT_TEST click=true'),
    'sneaking direct Shulker right click'))
  assert.equal(click.route, 'shulker')
  assert.equal(click.cancelled, 'true')
  assert.equal(click.sneaking, 'true')
  return inspect(bot, x, 64, 1,
    state => state.active === 'true')
}

async function exerciseSneakingBarrierRightClick(bot, x, initialState) {
  bot.chat('/item replace entity @s weapon.mainhand with minecraft:stone')
  await sleep(300)
  const click = parseInspection(await command(
    bot,
    `/sculpttest click ${x} 64 0 sneak-right`,
    message => message.includes('SCULPT_TEST click=true'),
    'sneaking BARRIER right click'))
  assert.equal(click.route, 'block')
  assert.equal(click.cancelled, 'true')
  assert.equal(click.sneaking, 'true')
  const restored = await inspect(bot, x, 64, 0,
    state => state.active === 'true' &&
      Number(state.removed) < Number(initialState.removed))
  assert.equal(restored.block, 'BARRIER',
    'sneaking right click reverted the SculptBlock to its original block')
  return restored
}

async function exerciseWandShulkerClicks(bot, x) {
  const before = await inspect(bot, x, 64, 0)
  bot.chat('/item replace entity @s weapon.mainhand with minecraft:air')
  await sleep(250)
  await command(bot, '/sculpt tool selector',
    message => message.toLowerCase().includes('selection wand'), 'give selector tool')
  await sleep(250)
  assert.equal(bot.heldItem?.name, 'bone', 'selector tool is not in the main hand')

  const left = parseInspection(await command(
    bot,
    `/sculpttest click ${x} 64 0 shulker-left`,
    message => message.includes('SCULPT_TEST click=true'),
    'selector Shulker left click'))
  assert.equal(left.route, 'shulker')
  assert.equal(left.cancelled, 'true')

  const right = parseInspection(await command(
    bot,
    `/sculpttest click ${x} 64 0 shulker-right`,
    message => message.includes('SCULPT_TEST click=true'),
    'selector Shulker right click'))
  assert.equal(right.route, 'shulker')
  assert.equal(right.cancelled, 'true')

  const after = await inspect(bot, x, 64, 0)
  assert.equal(after.signature, before.signature,
    'selector clicks unexpectedly edited the SculptBlock')
  assert.equal(after.removed, before.removed,
    'selector clicks unexpectedly changed removed cells')
  assert.equal(after.occupied, before.occupied,
    'selector clicks unexpectedly changed occupied cells')

  bot.chat('/item replace entity @s weapon.mainhand with minecraft:stone')
  await sleep(300)
  return { left, right, before, after }
}

async function exerciseDownwardDiagonalPlacement(bot, x) {
  bot.chat('/item replace entity @s weapon.mainhand with minecraft:stone')
  bot.chat(`/setblock ${x} 64 0 minecraft:stone`)
  await command(bot, '/sculpt mode shulker',
    message => message.toLowerCase().includes('shulker'), 'mode shulker')
  await command(bot, '/sculpt resolution 2',
    message => message.includes('2'), 'resolution 2')
  bot.chat(`/tp ${bot.username} ${x + 0.5} 67 0.5`)
  await sleep(500)

  await command(bot, `/sculpttest click ${x} 64 0 top-right-nw`,
    message => message.includes('SCULPT_TEST click=true'),
    'top-face grid=2 placement')

  const first = await inspect(bot, x, 65, 0,
    state => state.active === 'true' && Number(state.occupied) === 1)
  assert.equal(first.rootPassengers, '1', 'single-cell root passenger count')
  assert.equal(first.rootDisplays, '1', 'single-cell root display count')
  assert.equal(first.leafDisplays, '1', 'single-cell leaf display count')
  assert.equal(first.orphanLeaves, '0', 'single-cell placement left an orphan leaf')

  const orphanCleanup = parseInspection(await command(
    bot,
    `/sculpttest orphan ${x} 65 0`,
    message => message.includes('SCULPT_TEST orphan=true'),
    'orphan leaf reconciliation'))
  assert.equal(orphanCleanup.before, '1', 'orphan fault injection failed')
  assert.equal(orphanCleanup.after, '0', 'chunk reconciliation retained an orphan leaf')

  await sleep(60)
  const secondClick = parseInspection(await command(
    bot,
    `/sculpttest click ${x} 65 0 top-right-se`,
    message => message.includes('SCULPT_TEST click=true'),
    'opposite top-face grid=2 placement'))
  assert.equal(secondClick.route, 'entity')
  assert.equal(secondClick.cancelled, 'true')

  const second = await inspect(bot, x, 65, 0,
    state => state.active === 'true' && Number(state.occupied) === 2)
  assert.equal(second.orphanLeaves, '0', 'second placement left an orphan leaf')
  assert.equal(second.rootPassengers, second.occupied,
    'root passenger count diverged from occupied leaves')
  return { first, orphanCleanup, secondClick, second }
}

async function exerciseTintedEdgePlacement(bot, x) {
  bot.chat('/item replace entity @s weapon.mainhand with minecraft:grass_block')
  bot.chat(`/setblock ${x} 64 0 minecraft:stone`)
  await command(bot, '/sculpt mode barrier',
    message => message.toLowerCase().includes('barrier'), 'mode barrier')
  await command(bot, '/sculpt resolution 2',
    message => message.includes('2'), 'resolution 2')
  bot.chat(`/tp ${bot.username} ${x + 0.5} 67 0.5`)
  await sleep(500)

  const expected = parseInspection(await command(
    bot,
    `/sculpttest tint ${x} 65 0 grass_block`,
    message => message.includes('SCULPT_TEST tint=true'),
    'grass tint at the AIR placement position'))
  assert.notEqual(expected.tintArgb, '00000000',
    'AIR placement position did not resolve a grass biome tint')

  const click = parseInspection(await command(
    bot,
    `/sculpttest click ${x} 64 0 top-right-nw`,
    message => message.includes('SCULPT_TEST click=true'),
    'place tinted cell into an AIR position'))
  assert.equal(click.route, 'block')
  assert.equal(click.cancelled, 'true')

  const placed = await inspect(bot, x, 65, 0)
  assert.notEqual(placed.tintArgb, '00000000',
    'new grass SculptBlock did not resolve a biome tint')
  assert.equal(placed.tintArgb, expected.tintArgb,
    'new grass SculptBlock tint differs from its placement position')

  bot.chat('/item replace entity @s weapon.mainhand with minecraft:stone')
  await command(bot, '/sculpt mode shulker',
    message => message.toLowerCase().includes('shulker'), 'restore shulker mode')
  return { expected, click, placed }
}

async function exerciseNonBakeableEdgePlacement(bot, x) {
  bot.chat('/item replace entity @s weapon.mainhand with minecraft:red_mushroom_block')
  bot.chat(`/setblock ${x} 64 0 minecraft:stone`)
  bot.chat(`/setblock ${x} 65 0 minecraft:air`)
  await command(bot, '/sculpt mode barrier',
    message => message.toLowerCase().includes('barrier'), 'mode barrier')
  await command(bot, '/sculpt resolution 2',
    message => message.includes('2'), 'resolution 2')
  bot.chat(`/tp ${bot.username} ${x + 0.5} 67 0.5`)
  await sleep(500)

  const edgeClick = parseInspection(await command(
    bot,
    `/sculpttest click ${x} 64 0 top-right-nw`,
    message => message.includes('SCULPT_TEST click=true'),
    'reject a non-bakeable edge placement'))
  assert.equal(edgeClick.route, 'block')
  assert.equal(edgeClick.cancelled, 'true')

  const target = await inspect(bot, x, 65, 0,
    state => state.active === 'false' && state.block === 'AIR')
  assert.equal(target.active, 'false',
    'non-bakeable held block created a SculptBlock')

  const existing = await createSculptBlock(bot, x, 'barrier')
  bot.chat('/item replace entity @s weapon.mainhand with minecraft:red_mushroom_block')
  await sleep(300)
  const restoreClick = parseInspection(await command(
    bot,
    `/sculpttest click ${x} 64 0 right`,
    message => message.includes('SCULPT_TEST click=true'),
    'reject a non-bakeable existing-cell placement'))
  assert.equal(restoreClick.route, 'block')
  assert.equal(restoreClick.cancelled, 'true')

  const unchanged = await inspect(bot, x, 64, 0)
  assert.equal(unchanged.signature, existing.signature,
    'non-bakeable held block changed an existing SculptBlock')
  assert.equal(unchanged.removed, existing.removed,
    'non-bakeable held block restored an existing cell')

  bot.chat('/item replace entity @s weapon.mainhand with minecraft:stone')
  await command(bot, '/sculpt mode shulker',
    message => message.toLowerCase().includes('shulker'), 'restore shulker mode')
  return { edgeClick, target, existing, restoreClick, unchanged }
}

async function createSculptBlock(bot, x, mode) {
  bot.chat(`/setblock ${x} 64 0 minecraft:stone`)
  await sleep(250)
  await command(bot, `/sculpt mode ${mode}`,
    message => message.toLowerCase().includes(mode), `mode ${mode}`)
  await command(bot, '/sculpt resolution 2',
    message => message.includes('2'), 'resolution 2')
  bot.chat(`/tp ${bot.username} ${x + 0.5} 64 3.5`)
  await sleep(500)
  await bot.lookAt(new Vec3(x + 0.5, 64.5, 0.5), true)
  const block = bot.blockAt(new Vec3(x, 64, 0))
  assert.equal(block?.name, 'stone', `Expected source stone at ${x},64,0`)

  await command(bot, `/sculpttest click ${x} 64 0`,
    message => message.includes('SCULPT_TEST click=true'), 'Sculpt left click')
  return inspect(bot, x, 64, 0,
    state => state.active === 'true' && Number(state.removed) > 0)
}

async function copyPaste(bot, sourceX, targetX) {
  bot.chat(`/tp ${bot.username} ${sourceX + 0.5} 64 3.5`)
  await sleep(350)
  bot.chat(`//pos1 ${sourceX},64,0`)
  await sleep(200)
  bot.chat(`//pos2 ${sourceX},64,0`)
  await sleep(200)
  await command(bot, '//copy -e',
    message => /copied|affected|clipboard/i.test(message), 'WorldEdit copy')

  bot.chat(`/tp ${bot.username} ${targetX + 0.5} 64 3.5`)
  await sleep(350)
  await command(bot, '//paste -e',
    message => /pasted|affected|operation completed/i.test(message), 'WorldEdit paste')
  return inspect(bot, targetX, 64, 0)
}

function assertEquivalent(source, pasted, label) {
  assert.equal(pasted.active, 'true', `${label}: pasted block is inactive`)
  assert.equal(pasted.state, 'SCULPTED', `${label}: pasted state`)
  assert.equal(pasted.shulker, source.shulker, `${label}: collision mode`)
  assert.equal(pasted.mixed, source.mixed, `${label}: mixed-material flag`)
  assert.equal(pasted.leaves, source.leaves, `${label}: leaf count`)
  assert.equal(pasted.removed, source.removed, `${label}: removed count`)
  assert.equal(pasted.signature, source.signature, `${label}: octree/material signature`)
  assert.equal(pasted.block, source.block, `${label}: backing block`)
  assert.equal(pasted.fullCollision, source.fullCollision, `${label}: full collision`)
  assert.equal(pasted.entityCollision, source.entityCollision, `${label}: entity collision`)
  assert.equal(pasted.rootValid, 'true', `${label}: root entity`)
  assert.equal(pasted.clickProxy, source.clickProxy, `${label}: click proxy`)
  assert.equal(pasted.collisionEntities, source.collisionEntities,
    `${label}: collision entity count`)
}

const bot = mineflayer.createBot({
  host: '127.0.0.1',
  port,
  username: 'SculptE2E',
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

  bot.chat('/gamemode creative')
  await sleep(300)
  bot.chat('/time set day')
  bot.chat('/weather clear')
  bot.chat('/fill -5 63 -5 25 63 5 minecraft:bedrock')
  await sleep(500)

  await command(bot, '/sculpt mode barrier',
    message => message.toLowerCase().includes('barrier'), 'mode barrier')
  bot.chat('/setblock 4 64 0 minecraft:stone')
  await command(bot, '/sculpt resolution 1',
    message => message.includes('1'), 'resolution 1')
  const normalLeft = parseInspection(await command(bot, '/sculpttest click 4 64 0 left',
    message => message.includes('SCULPT_TEST click=true'), 'normal block left click'))
  assert.equal(normalLeft.mode, 'true',
    'normal-block pass-through case did not have sculpt mode enabled')
  assert.equal(normalLeft.cancelled, 'false',
    'resolution 1 intercepted a normal-block left click')
  const normalRight = parseInspection(await command(bot, '/sculpttest click 4 64 0 right',
    message => message.includes('SCULPT_TEST click=true'), 'normal block right click'))
  assert.equal(normalRight.cancelled, 'false',
    'resolution 1 intercepted a normal-block right click')
  const wholeNormal = await inspect(bot, 4, 64, 0,
    state => state.active === 'false' && state.block === 'STONE')

  const barrierFirstCut = await createSculptBlock(bot, 0, 'barrier')
  await command(bot, '/sculpttest remove-cell 0 64 0',
    message => message.includes('SCULPT_TEST removeCell=true'),
    'prepare a second empty BARRIER cell')
  const barrierSource = await inspect(bot, 0, 64, 0,
    state => Number(state.removed) > Number(barrierFirstCut.removed))
  assert.equal(barrierSource.shulker, 'false')
  assert.equal(barrierSource.block, 'BARRIER')
  const barrierPaste = await copyPaste(bot, 0, 8)
  const sneakingBarrierRight = await exerciseSneakingBarrierRightClick(
    bot, 0, barrierSource)
  assertEquivalent(barrierSource, barrierPaste, 'barrier')

  const partialSource = await createSculptBlock(bot, 2, 'shulker')
  assert.equal(partialSource.shulker, 'true')
  assert.equal(partialSource.entityCollision, 'true')
  assert.equal(partialSource.block, 'AIR')
  assert.equal(Number(partialSource.clickProxyY), 64,
    'partial adaptive click proxy does not begin at the block bottom')
  const directShulkerLeft = await exerciseDirectShulkerLeftClick(bot, 2, partialSource)
  const partialAfterClicks = await inspect(bot, 2, 64, 0)

  const rightClickSource = await createSculptBlock(bot, 6, 'shulker')
  const directShulkerRight = await exerciseDirectShulkerRightClick(bot, 6)
  const wandShulkerClicks = await exerciseWandShulkerClicks(bot, 6)
  const downwardDiagonal = await exerciseDownwardDiagonalPlacement(bot, 22)
  const tintedEdgePlacement = await exerciseTintedEdgePlacement(bot, 20)
  const nonBakeableEdgePlacement = await exerciseNonBakeableEdgePlacement(bot, 24)
  const partialPaste = await copyPaste(bot, 2, 10)
  assertEquivalent(partialAfterClicks, partialPaste, 'partial adaptive')

  await command(bot, '/sculpttest fill 2 64 0',
    message => message.includes('SCULPT_TEST fill=true'), 'fill adaptive source')
  const fullSource = await inspect(bot, 2, 64, 0,
    state => state.fullCollision === 'true' && state.block === 'BARRIER')
  assert.equal(fullSource.removed, '0')
  assert.equal(fullSource.collisionEntities, '0')
  const fullPaste = await copyPaste(bot, 2, 18)
  assertEquivalent(fullSource, fullPaste, 'full adaptive')

  bot.chat(`/tp ${bot.username} 8.5 64 3.5`)
  await sleep(350)
  await bot.lookAt(new Vec3(8.5, 64.2, 0.2), true)
  await command(bot, '/sculpttest click 8 64 0',
    message => message.includes('SCULPT_TEST click=true'), 'edit pasted SculptBlock')
  const editedPaste = await inspect(bot, 8, 64, 0,
    state => Number(state.removed) > Number(barrierPaste.removed))
  const unchangedSource = await inspect(bot, 0, 64, 0)
  assert.equal(unchangedSource.signature, sneakingBarrierRight.signature,
    'editing pasted block mutated the source')

  await command(bot, '/sculpt resolution 1',
    message => message.includes('1'), 'resolution 1')
  await command(bot, '/sculpt mode barrier',
    message => message.toLowerCase().includes('barrier'), 'mode barrier')

  const entityRestoreClick = parseInspection(await command(bot, '/sculpttest click 10 64 0 right',
    message => message.includes('SCULPT_TEST click=true'), 'restore entity SculptBlock'))
  assert.equal(entityRestoreClick.route, 'entity')
  assert.equal(entityRestoreClick.cancelled, 'true',
    'resolution 1 did not intercept an entity SculptBlock right click')
  const entityRestore = await inspect(bot, 10, 64, 0,
    state => state.active === 'false' && state.block === 'STONE')

  const entityRemoveClick = parseInspection(await command(bot, '/sculpttest click 8 64 0 left',
    message => message.includes('SCULPT_TEST click=true'), 'remove entity SculptBlock'))
  assert.equal(entityRemoveClick.route, 'entity')
  assert.equal(entityRemoveClick.cancelled, 'true',
    'resolution 1 did not intercept an entity SculptBlock left click')
  const entityRemove = await inspect(bot, 8, 64, 0,
    state => state.active === 'false' && state.block === 'AIR')

  const wholeRestoreClick = parseInspection(await command(bot, '/sculpttest click 2 64 0 right',
    message => message.includes('SCULPT_TEST click=true'), 'restore whole SculptBlock'))
  assert.equal(wholeRestoreClick.cancelled, 'true',
    'resolution 1 did not intercept a SculptBlock right click')
  const wholeRestore = await inspect(bot, 2, 64, 0,
    state => state.active === 'false' && state.block === 'STONE')

  const wholeRemoveClick = parseInspection(await command(bot, '/sculpttest click 0 64 0 left',
    message => message.includes('SCULPT_TEST click=true'), 'remove whole SculptBlock'))
  assert.equal(wholeRemoveClick.cancelled, 'true',
    'resolution 1 did not intercept a SculptBlock left click')
  const wholeSculpt = await inspect(bot, 0, 64, 0,
    state => state.active === 'false' && state.block === 'AIR')

  console.log(JSON.stringify({
    editor,
    wholeBlock: {
      normal: { state: wholeNormal, left: normalLeft, right: normalRight },
      removed: wholeSculpt,
      restored: wholeRestore,
      entityRemoved: entityRemove,
      entityRestored: entityRestore
    },
    barrier: {
      source: barrierSource,
      pasted: barrierPaste,
      edited: editedPaste,
      sneakingRight: sneakingBarrierRight
    },
    partialAdaptive: {
      source: partialSource,
      directShulkerLeft,
      afterClicks: partialAfterClicks,
      pasted: partialPaste,
      rightClick: { source: rightClickSource, placed: directShulkerRight },
      downwardDiagonal,
      tint: tintedEdgePlacement,
      nonBakeable: nonBakeableEdgePlacement,
      selector: wandShulkerClicks
    },
    fullAdaptive: { source: fullSource, pasted: fullPaste }
  }))
  bot.quit('Sculpt integration complete')
} catch (error) {
  console.error(error)
  bot.quit('Sculpt integration failed')
  process.exitCode = 1
}
