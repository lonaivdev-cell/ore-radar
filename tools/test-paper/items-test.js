// Radar items in a player's hands and in a station (DESIGN §3, §8), on a running test server
// (tools/test-paper/server.sh start <version>):
//   - radars never stack: each has its own oreradar:id (and from 1.20.5 max_stack_size 1), crafted
//     ones too, two at a time with a shift-click;
//   - a station's radar slot takes a single radar, never a stack; shift-click moves one off a stack;
//   - the drain of the radar in use is kept back and written only when the lore's 5% step changes
//     (from 1.21.11 the bar follows it live, as damage, which the client does not re-bob for) or
//     the radar stops being in use; none of it is lost or counted twice across hand swaps, putting
//     it away, a drop, a shift-click into a chest, a quit, a server restart and a death (a radar
//     that left the player pays once it is in use again);
//   - a radar is not a compass ingredient: the radar recipe with a radar for its compass makes nothing;
//   - with keepInventory, dying while the radar shows FOUND breaks nothing;
//   - the chest GUI acts once per click, not again on a double-click's DOUBLE_CLICK;
//   - 26.3: a bundle cannot put an item into a station slot (PLACE_FROM_BUNDLE).
//
//   NODE_PATH=<dir with mineflayer> node tools/test-paper/items-test.js <version>
//
// OR_CLIENT_VERSION and OR_STILL as in harness.js. Prints PASS/FAIL lines; exit 1 if any check failed.
'use strict'
const fs = require('fs')
const path = require('path')
const { execFileSync } = require('child_process')
const { Vec3 } = require('vec3')
const { harness, itemData, itemText } = require(path.join(__dirname, 'harness.js'))

const version = process.argv[2]
const { consoleLog, sleep, check, finish, waitFor, consoleCmd, join } = harness(version)
/** From 1.20.5 items carry components rather than NBT tags. */
const components = version !== '1.20.1'
/** From 1.21.11 the client ignores damage when it decides whether to replay the equip animation. */
const damageIgnored = version === '26.3'
const NAME = 'Smith'
const CAPACITY = 36000
const KEEP_INVENTORY = version === '26.3' ? 'minecraft:keep_inventory' : 'keepInventory' // renamed in 26.x
const serverSh = path.join(__dirname, 'server.sh')

function radars (bot) {
  return bot.inventory.slots.map((item, slot) => ({ item, slot })).filter(r => r.item && itemData(r.item).pdc['oreradar:radar'] === 1)
}
function idOf (item) {
  return JSON.stringify(itemData(item).pdc['oreradar:id'])
}
async function openWindow (bot, action, ms = 4000) {
  const opened = new Promise(resolve => bot.once('windowOpen', resolve))
  await action()
  return Promise.race([opened, sleep(ms).then(() => null)])
}
/** The selected item as the server stores it, one line of /data output. */
async function serverHeld () {
  const out = await consoleCmd(`minecraft:data get entity ${NAME} SelectedItem`, 800)
  return out.split('\n').find(l => l.includes('has the following entity data')) || ''
}

/** The game time in ticks, as the server counts them (it persists across a restart). */
async function gametime () {
  const out = await consoleCmd('minecraft:time query gametime', 400)
  const m = out.match(/The (?:game )?time is (\d+)/) // 26.x says "game time"
  if (!m) throw new Error(`no game time in: ${out.trim()}`)
  return Number(m[1])
}
function clientCharge (item) {
  const c = itemData(item).pdc['oreradar:charge']
  return c === undefined ? CAPACITY : c
}
/**
 * What the client compares to decide whether to replay the equip animation, and the bar apart:
 * from 1.21.11 a change of damage alone does not count.
 */
function appearance (item) {
  const comps = item.components || []
  const bar = comps.find(c => c.type === 'damage')
  const look = comps.filter(c => !(damageIgnored && c.type === 'damage'))
  return { look: JSON.stringify({ name: item.name, count: item.count, nbt: item.nbt || null, look }), bar: bar ? JSON.stringify(bar.data) : null }
}
/** Counts the changes the client sees on one radar (by id), wherever it sits; reset() starts over. */
function watch (bot, id) {
  const w = { changes: 0, barMoves: 0 }
  let last = null
  let lastBar = null
  const timer = setInterval(() => {
    const r = radars(bot).find(x => idOf(x.item) === id)
    if (!r) return
    const a = appearance(r.item)
    if (last !== null && a.look !== last) w.changes++
    else if (lastBar !== null && a.bar !== lastBar) w.barMoves++
    last = a.look
    lastBar = a.bar
  }, 50)
  w.reset = () => { w.changes = 0; w.barMoves = 0 }
  w.stop = () => clearInterval(timer)
  return w
}

/**
 * The radar's drain across everything that moves it (DESIGN §8 "Charge writes"). Each stretch in
 * use is bracketed by the game time just outside and just inside it, and ends with something that
 * stops the radar (put away, dropped, into a chest, quit, server stop, death), so it cannot drain
 * between stretches. The drain then lies between half a unit per inner and per outer tick
 * (drainPerSecond 10); the totals read off the item must stay inside the sum. Ancient debris is
 * the target: never near an overworld scene, so no needle write mixes in. Returns the bot it ends
 * with (a restart and a rejoin replace it).
 */
async function drainCadence (bot, { bx, y, bz }) {
  const owed = { lo: 0, hi: 0 }
  let outerStart, innerStart
  const begin = async (action) => {
    outerStart = await gametime()
    await action()
    innerStart = await gametime()
  }
  const end = async (action, after) => {
    const innerEnd = await gametime()
    await action()
    const outerEnd = after ? await after() : await gametime()
    owed.lo += Math.floor((innerEnd - innerStart) / 2) - 1
    owed.hi += Math.ceil((outerEnd - outerStart) / 2) + 1
  }
  const inRange = drained => drained >= owed.lo && drained <= owed.hi
  const range = drained => `${drained} drained, expected ${owed.lo}–${owed.hi}`
  const radar = () => radars(bot).find(r => idOf(r.item) === id)
  const hold = async () => {
    const r = await waitFor(radar, 4000)
    if (r && r.slot >= 36) bot.setQuickBarSlot(r.slot - 36)
    await sleep(300)
    return r
  }
  /** Selects a hotbar slot without the radar: it is no longer in use. */
  const putAway = async () => {
    const r = radar()
    bot.setQuickBarSlot([...Array(9).keys()].find(i => !bot.inventory.slots[36 + i] && (!r || r.slot !== 36 + i)))
    await sleep(500)
  }
  const drainedNow = () => CAPACITY - clientCharge(radar().item)
  const swap = () => bot._client.write('block_dig', { status: 6, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 })
  const drop = () => bot._client.write('block_dig', { status: 4, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 })
  const fetchDropped = () => consoleCmd(`minecraft:execute positioned ${bx} ${y} ${bz} run tp @e[type=minecraft:item,limit=1,sort=nearest,distance=..8] ${NAME}`, 2500)

  await consoleCmd(`minecraft:tp ${NAME} ${bx + 0.5} ${y} ${bz + 0.5} -90 0`, 300)
  await consoleCmd(`minecraft:clear ${NAME}`, 300)
  await consoleCmd(`oreradar give ${NAME} radar`, 400)
  await waitFor(() => radars(bot)[0], 4000)
  const id = idOf(radars(bot)[0].item)
  await hold()
  let w = watch(bot, id)

  // 1. Held for 14 s with the hands swapped every 3 s: the client sees no change; putting it away writes it all.
  await begin(() => consoleCmd(`oreradar target ${NAME} minecraft:ancient_debris`, 1000))
  w.reset()
  for (let i = 0; i < 4; i++) {
    await sleep(3000)
    swap()
  }
  await sleep(2000)
  const quiet = { ...w }
  const held = components ? await serverHeld() : ''
  await end(putAway)
  let drained = drainedNow()
  check('a radar in use is not rewritten for its drain, hand swaps included', quiet.changes === 0, `${quiet.changes} changes the client would re-bob for in 14 s, 4 swaps`)
  if (damageIgnored) check('from 1.21.11 its bar follows the drain live, as damage alone', quiet.barMoves >= 2, `${quiet.barMoves} bar moves`)
  if (components) check('from 1.20.5 a radar shows a charge bar (max_damage = capacity)', /max_damage": 36000/.test(held), held.slice(0, 200))
  check('putting the radar away writes the drain kept back, all of it once', inRange(drained), range(drained))

  // 2. Dropped: what it owes waits under its id and is paid once it is in use again.
  await begin(hold)
  await sleep(6000)
  await end(async () => { drop(); await sleep(400) })
  await begin(async () => { await fetchDropped(); await hold() })
  await sleep(3000)
  await end(putAway)
  drained = drainedNow()
  check('a dropped radar pays its drain once it is in use again', inRange(drained), range(drained))

  // 3. Into a chest by shift-click, after 4 s with the chest open; the same.
  await begin(hold)
  const chest = bot.blockAt(new Vec3(bx + 2, y, bz - 2))
  const win = await openWindow(bot, () => bot.activateBlock(chest))
  check('the chest opens', !!win)
  if (win) {
    await sleep(4000)
    const from = win.slots.findIndex((i, n) => n >= win.inventoryStart && i && idOf(i) === id)
    await end(async () => { await bot.clickWindow(from, 0, 1); await sleep(500) })
    await begin(async () => {
      await bot.clickWindow(win.slots.findIndex((i, n) => n < win.inventoryStart && i && idOf(i) === id), 0, 1)
      await sleep(400)
      bot.closeWindow(win)
      await hold()
    })
    await sleep(3000)
    await end(putAway)
    drained = drainedNow()
    check('a radar put in a chest pays its drain once it is in use again', inRange(drained), range(drained))
  }

  // 4. Quit and rejoin; the radar is in use again from the join.
  await begin(hold)
  await sleep(5000)
  w.stop()
  await end(async () => { bot.quit(); await sleep(1500) })
  await begin(async () => {
    bot = await join(NAME)
    await sleep(1500)
    drained = drainedNow()
    await hold()
  })
  check('quitting writes the drain', inRange(drained), range(drained))

  // 5. The server stops with the player online: the plugin is disabled before the player is saved.
  await sleep(5000)
  bot.on('error', () => {})
  bot.on('kicked', () => {})
  await end(() => { console.log('  restarting the server'); execFileSync(serverSh, ['stop', version], { stdio: 'inherit' }) },
    async () => { execFileSync(serverSh, ['start', version], { stdio: 'inherit' }); return gametime() })
  await begin(async () => {
    bot = await join(NAME)
    await sleep(1500)
    drained = drainedNow()
    await consoleCmd(`minecraft:tp ${NAME} ${bx + 0.5} ${y} ${bz + 0.5} -90 0`, 300)
    await hold()
  })
  check('a server restart keeps the drain, none of it twice', inRange(drained), range(drained))

  // 6. Death drops the radar; what it owed is paid once it is in use again.
  await sleep(5000)
  await consoleCmd(`minecraft:gamerule ${KEEP_INVENTORY} false`, 300)
  await end(() => consoleCmd(`minecraft:kill ${NAME}`, 600))
  await begin(async () => {
    await sleep(1500) // the bot respawns
    await consoleCmd(`minecraft:tp ${NAME} ${bx + 0.5} ${y} ${bz + 0.5} -90 0`, 500)
    await fetchDropped()
    await hold()
  })
  await sleep(3000)
  await end(putAway)
  drained = drainedNow()
  check('a radar dropped at death pays its drain once it is in use again', inRange(drained), range(drained))
  await sleep(1500)

  // 7. Crossing a 5% step writes the radar once, with the new step in the lore: 1,810 shows 10%, 1,760 5%.
  await consoleCmd('minecraft:kill @e[type=minecraft:item]', 300)
  await consoleCmd(`minecraft:tp ${NAME} ${bx + 0.5} ${y} ${bz + 0.5} -90 0`, 300)
  await consoleCmd(`minecraft:clear ${NAME}`, 300)
  await consoleCmd(`oreradar give ${NAME} radar`, 400)
  const fresh = await waitFor(() => radars(bot)[0], 4000)
  bot.setQuickBarSlot(fresh.slot - 36)
  await sleep(300)
  const second = idOf(fresh.item)
  await consoleCmd(`oreradar target ${NAME} minecraft:ancient_debris`, 300)
  await consoleCmd(`oreradar charge ${NAME} 1810`, 1000)
  w = watch(bot, second)
  await sleep(11000)
  w.stop()
  const r = radars(bot)[0]
  const charge = r ? clientCharge(r.item) : null
  const lore = r ? itemText(r.item) : ''
  check('crossing a 5% step writes the radar exactly once', w.changes === 1 && charge < 1810 && charge >= 1700 && /5%/.test(lore) && !/10%/.test(lore),
    `${w.changes} writes, charge ${charge}, lore ${(lore.match(/[0-9]+%[^"\\]*/) || [''])[0]}`)
  return bot
}

async function main () {
  let bot = await join(NAME)
  await sleep(1000)
  const b = bot.entity.position.floored()
  const bx = b.x, bz = b.z, y = 250
  for (const c of [
    `minecraft:gamemode survival ${NAME}`,
    `minecraft:fill ${bx - 6} ${y} ${bz - 6} ${bx + 6} ${y + 4} ${bz + 6} minecraft:air`,
    `minecraft:fill ${bx - 6} ${y - 1} ${bz - 6} ${bx + 6} ${y - 1} ${bz + 6} minecraft:glass`,
    `minecraft:tp ${NAME} ${bx + 0.5} ${y} ${bz + 0.5} -90 0`,
    `minecraft:setblock ${bx + 5} ${y} ${bz} minecraft:diamond_ore`,
    `minecraft:setblock ${bx + 2} ${y} ${bz + 2} minecraft:crafting_table`,
    `minecraft:setblock ${bx + 2} ${y} ${bz - 2} minecraft:chest`,
    `minecraft:clear ${NAME}`
  ]) await consoleCmd(c, 400)
  await sleep(1000)

  // ---- radars never stack ----
  await consoleCmd(`oreradar give ${NAME} radar`, 400)
  await consoleCmd(`oreradar give ${NAME} radar`, 400)
  await sleep(500)
  let rs = radars(bot)
  check('two given radars stay apart', rs.length === 2 && rs.every(r => r.item.count === 1) && idOf(rs[0].item) !== idOf(rs[1].item),
    rs.map(r => `slot ${r.slot} x${r.item.count} id ${idOf(r.item)}`).join('; '))
  if (components) {
    bot.setQuickBarSlot(rs[0].slot - 36)
    await sleep(300)
    const held = await serverHeld()
    check('from 1.20.5 a radar says max_stack_size 1', /max_stack_size": 1/.test(held), held.slice(0, 160))
  }

  // Two crafted at once, with a shift-click on the result.
  await consoleCmd(`minecraft:clear ${NAME}`, 300)
  for (const [item, n] of [['lightning_rod', 2], ['iron_ingot', 4], ['glass_pane', 2], ['redstone', 4], ['compass', 2]]) {
    await consoleCmd(`minecraft:give ${NAME} minecraft:${item} ${n}`, 250)
  }
  await sleep(500)
  let win = await openWindow(bot, () => bot.activateBlock(bot.blockAt(new Vec3(bx + 2, y, bz + 2))))
  check('the crafting table opens', !!win, win && win.type)
  if (win) {
    // " L " / "IGI" / "RCR" in grid slots 1-9, two of each, so one shift-click makes two radars.
    const cells = { lightning_rod: [2], iron_ingot: [4, 6], glass_pane: [5], redstone: [7, 9], compass: [8] }
    for (const [name, at] of Object.entries(cells)) {
      const from = win.slots.findIndex((i, n) => n >= win.inventoryStart && i && i.name === name)
      await bot.clickWindow(from, 0, 0)
      for (const cell of at) {
        await bot.clickWindow(cell, 1, 0)
        await bot.clickWindow(cell, 1, 0)
      }
    }
    await sleep(600)
    const result = win.slots[0]
    check('the recipe makes a radar with an id of its own', !!result && itemData(result).pdc['oreradar:radar'] === 1 && itemData(result).pdc['oreradar:id'] !== undefined,
      result && JSON.stringify(itemData(result).pdc))
    await bot.clickWindow(0, 0, 1)
    await sleep(800)
    bot.closeWindow(win)
    await sleep(500)
    rs = radars(bot)
    check('one shift-click crafts two radars that do not stack', rs.length === 2 && rs.every(r => r.item.count === 1) && idOf(rs[0].item) !== idOf(rs[1].item),
      rs.map(r => `slot ${r.slot} x${r.item.count} id ${idOf(r.item)}`).join('; '))
  }

  // A radar is not a compass ingredient: the same recipe with a radar where the compass goes makes nothing.
  for (const [item, n] of [['lightning_rod', 1], ['iron_ingot', 2], ['glass_pane', 1], ['redstone', 2]]) {
    await consoleCmd(`minecraft:give ${NAME} minecraft:${item} ${n}`, 250)
  }
  await sleep(500)
  win = await openWindow(bot, () => bot.activateBlock(bot.blockAt(new Vec3(bx + 2, y, bz + 2))))
  if (win) {
    const cells = { lightning_rod: [2], iron_ingot: [4, 6], glass_pane: [5], redstone: [7, 9] }
    for (const [name, at] of Object.entries(cells)) {
      await bot.clickWindow(win.slots.findIndex((i, n) => n >= win.inventoryStart && i && i.name === name), 0, 0)
      for (const cell of at) await bot.clickWindow(cell, 1, 0)
    }
    await bot.clickWindow(win.slots.findIndex((i, n) => n >= win.inventoryStart && i && itemData(i).pdc['oreradar:radar'] === 1), 0, 0)
    await bot.clickWindow(8, 0, 0)
    await sleep(800)
    check('a radar is not a compass ingredient: in the radar recipe it makes nothing', !win.slots[0] && win.slots[8] && win.slots[8].name === 'compass',
      `result ${win.slots[0] ? win.slots[0].name : 'empty'}, centre ${win.slots[8] ? win.slots[8].name : 'empty'}`)
    bot.closeWindow(win)
    await sleep(500)
  } else {
    check('the crafting table reopens', false)
  }

  // ---- a station's radar slot takes one radar ----
  // A stack can only come from item data copied outside the plugin, such as a /give.
  await consoleCmd(`minecraft:clear ${NAME}`, 300)
  const stack = components ? 'minecraft:compass[custom_data={PublicBukkitValues:{"oreradar:radar":1b}}] 2'
    : 'minecraft:compass{PublicBukkitValues:{"oreradar:radar":1b}} 2'
  await consoleCmd(`minecraft:give ${NAME} ${stack}`, 400)
  await consoleCmd(`oreradar give ${NAME} station`, 400)
  await sleep(600)
  const stationSlot = bot.inventory.slots.findIndex(i => i && itemData(i).pdc['oreradar:station'] === 1)
  bot.setQuickBarSlot(stationSlot - 36)
  await bot.look(Math.PI, Math.PI / 4)
  await sleep(300)
  await bot.placeBlock(bot.blockAt(new Vec3(bx - 2, y - 1, bz)), new Vec3(0, 1, 0)).catch(e => console.log(`  (placeBlock: ${e.message})`))
  await sleep(600)
  const station = bot.blockAt(new Vec3(bx - 2, y, bz))
  win = await openWindow(bot, () => bot.activateBlock(station))
  check('station placed and opened', !!win && station.name === 'lodestone')
  if (win) {
    const from = win.slots.findIndex((i, n) => n >= win.inventoryStart && i && i.count === 2 && i.name === 'compass')
    await bot.clickWindow(from, 0, 0) // the stack on the cursor
    await bot.clickWindow(1, 0, 0) // PLACE_ALL: both
    await sleep(700)
    check('a station refuses a stack of radars', !win.slots[1], win.slots[1] && `radar slot x${win.slots[1].count}`)
    await bot.clickWindow(1, 1, 0) // PLACE_ONE
    await sleep(700)
    check('but takes one radar off it', !!win.slots[1] && win.slots[1].count === 1, win.slots[1] ? `x${win.slots[1].count}` : 'empty')
    await bot.clickWindow(from, 0, 0) // the other one back
    await bot.clickWindow(1, 0, 0) // the station's one onto the cursor
    await bot.clickWindow(from, 0, 0) // and onto the other: a stack of two again
    await sleep(500)
    await bot.clickWindow(from, 0, 1) // shift-click the stack
    await sleep(700)
    check('shift-click moves one radar off a stack', !!win.slots[1] && win.slots[1].count === 1 && !!win.slots[from] && win.slots[from].count === 1,
      `station x${win.slots[1] ? win.slots[1].count : 0}, inventory x${win.slots[from] ? win.slots[from].count : 0}`)
    bot.closeWindow(win)
  }

  // ---- the drain of the radar in use: written rarely, never lost or counted twice ----
  bot = await drainCadence(bot, { bx, y, bz })

  // ---- keepInventory: dying while the radar shows FOUND ----
  await consoleCmd(`minecraft:gamerule ${KEEP_INVENTORY} true`, 300)
  const offset = fs.statSync(consoleLog).size
  const chat = []
  bot.on('messagestr', (text, position) => { if (position !== 'game_info') chat.push(text) })
  for (let i = 0; i < 3; i++) {
    await consoleCmd(`minecraft:tp ${NAME} ${bx + 0.5} ${y} ${bz + 0.5} -90 0`, 300)
    await consoleCmd(`oreradar target ${NAME} @ores/diamond`, 2000)
    await consoleCmd(`minecraft:kill ${NAME}`, 50)
    bot.chat('/oreradar info')
    await sleep(2500)
  }
  await consoleCmd(`minecraft:gamerule ${KEEP_INVENTORY} false`, 300)
  const errors = fs.readFileSync(consoleLog).subarray(offset).toString('utf8').split('\n')
    .filter(l => /NullPointerException|Radar update failed|Unhandled exception/.test(l))
  check('dying with keepInventory while a radar shows FOUND breaks nothing',
    errors.length === 0 && chat.some(l => l.startsWith('Radar in slot')) && radars(bot).length === 1,
    errors.slice(0, 2).join(' | ') || chat.filter(l => l.startsWith('Radar in slot')).pop())

  // ---- the chest GUI acts once per click ----
  await consoleCmd(`minecraft:tp ${NAME} ${bx + 0.5} ${y} ${bz + 0.5} -90 0`, 500)
  bot.setQuickBarSlot(radars(bot)[0].slot - 36)
  await sleep(800)
  win = await openWindow(bot, () => bot.activateItem())
  check('use opens the chest GUI', !!win)
  if (win) {
    // A vanilla double-click sends PICKUP, PICKUP, then DOUBLE_CLICK (PICKUP_ALL); the last must not act.
    // prismarine-windows cannot predict a double-click; the server decides what it does anyway.
    const accept = win.acceptClick.bind(win)
    win.acceptClick = click => click.mode === 6 ? [] : accept(click)
    await bot.clickWindow(47, 0, 0)
    await bot.clickWindow(47, 0, 6)
    await sleep(1000)
    const off = itemData(bot.inventory.slots[radars(bot)[0].slot]).pdc['oreradar:off']
    check('a click and the double-click that follows it toggle the power once', off === 1, `oreradar:off ${off}`)
    bot.closeWindow(win)
  }

  // ---- 26.3: bundles (1.21.2+) cannot put anything into a station slot ----
  if (version === '26.3') {
    await consoleCmd(`minecraft:give ${NAME} minecraft:bundle[bundle_contents=[{id:"minecraft:dirt",count:4}]]`, 500)
    await sleep(500)
    win = await openWindow(bot, () => bot.activateBlock(bot.blockAt(new Vec3(bx - 2, y, bz))))
    if (win) {
      const bundle = win.slots.findIndex((i, n) => n >= win.inventoryStart && i && i.name === 'bundle')
      await bot.clickWindow(bundle, 0, 0)
      await bot.clickWindow(7, 1, 0) // right-click the empty fuel slot: PLACE_FROM_BUNDLE
      await sleep(800)
      check('a bundle cannot place an item into the station\'s fuel slot', !win.slots[7], win.slots[7] && `${win.slots[7].name} x${win.slots[7].count}`)
      await bot.clickWindow(bundle, 0, 0)
      bot.closeWindow(win)
    } else {
      check('station reopens for the bundle check', false)
    }
  }

  bot.quit()
  await sleep(800)
  finish()
}

main().catch(e => {
  console.log(`FAIL ${e.stack || e}`)
  process.exit(1)
})
