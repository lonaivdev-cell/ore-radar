// Drives the Ore Radar Paper plugin with headless mineflayer bots on a running test server
// (tools/test-paper/server.sh start <version>), and checks what a vanilla client receives.
//
//   NODE_PATH=<dir with mineflayer> node tools/test-paper/bot-test.js <version> [full|basic]
//
// OR_CLIENT_VERSION and OR_STILL as in harness.js; with OR_STILL=1 the scene is built where the bots
// spawned instead of in the sky.
//
// basic: join, /oreradar give from the console, the radar item's data.
// full:  basic plus target, outlines (and that a second player does not see them), compass needle
//        (the radar's own lodestone in the overworld and the Nether; the player's compass target
//        never moves), action bar, beep, drain, chest GUI (track, search), sneak-use, power toggle,
//        charging station (redstone), outline cleanup when the radar goes idle and on quit, and a
//        client that talks like the mod getting nothing from the plugin (no channels, no packets).
// Prints one PASS/FAIL line per check and exits 1 if any failed.
'use strict'
const path = require('path')
const { Vec3 } = require('vec3')
const { harness, itemData, itemText, title } = require(path.join(__dirname, 'harness.js'))

const version = process.argv[2]
const mode = process.argv[3] || 'full'
const { still, sleep, check, finish, waitFor, consoleCmd, count, join: joinBot } = harness(version)

/** Counts block displays in the world, as the server sees them. */
function serverDisplayCount () {
  return count('@e[type=minecraft:block_display]')
}

/**
 * The held item's lodestone as the server stores it: {pos, tracked} or null when the item has no
 * lodestone data at all (NBT on 1.20.1, the lodestone_tracker component from 1.20.5).
 */
async function serverNeedle (player) {
  const out = await consoleCmd(`minecraft:data get entity ${player} SelectedItem`, 800)
  const line = out.split('\n').find(l => l.includes('has the following entity data')) || ''
  // 1.20.1 prints the compound's keys in hash order (Z, X, Y).
  const legacyTag = line.match(/LodestonePos: \{([^}]*)\}/)
  const legacy = legacyTag && ['X', 'Y', 'Z'].map(k => Number(legacyTag[1].match(new RegExp(`${k}: (-?\\d+)`))[1]))
  const modern = line.match(/lodestone_tracker": \{([^}]*target: \{pos: \[I; (-?\d+), (-?\d+), (-?\d+)\])?/)
  const anyData = /Lodestone|lodestone_tracker/.test(line)
  if (!anyData) return null
  const tracked = /LodestoneTracked: 1b|tracked: 1b/.test(line)
  const pos = legacy || (modern && modern[2] ? modern.slice(2, 5).map(Number) : null)
  return { pos, tracked, raw: line.slice(0, 200) }
}

/** The first {x, y, z} in a packet, wherever its version nests the position. */
function positionIn (packet) {
  if (!packet || typeof packet !== 'object') return null
  if (typeof packet.x === 'number' && typeof packet.y === 'number' && typeof packet.z === 'number') return [packet.x, packet.y, packet.z]
  for (const v of Object.values(packet)) {
    const pos = positionIn(v)
    if (pos) return pos
  }
  return null
}

function slotWithRadar (bot) {
  return bot.inventory.slots.findIndex(i => i && itemData(i).pdc['oreradar:radar'] === 1)
}

async function join (username) {
  const targets = []
  const categories = []
  const bot = await joinBot(username, b => {
    b._client.on('spawn_position', packet => targets.push(positionIn(packet)))
    b._client.on('sound_effect', packet => categories.push(packet.soundCategory))
  })
  bot.actionBars = []
  bot.sounds = []
  bot.soundCategories = categories
  bot.chatLines = []
  // Where a plain compass points: the spawn position the server sends (the player's compass target).
  bot.compassTargets = targets
  // Paper sends the action bar as its own packet, which mineflayer does not turn into an event.
  const ChatMessage = require('prismarine-chat')(bot.registry)
  bot.on('actionBar', msg => bot.actionBars.push({ at: Date.now(), text: msg.toString() }))
  bot._client.on('action_bar', packet => bot.actionBars.push({ at: Date.now(), text: ChatMessage.fromNotch(packet.text).toString() }))
  bot.on('soundEffectHeard', (name, pos, volume, pitch) => bot.sounds.push({ at: Date.now(), name, volume, pitch }))
  bot.on('messagestr', (text, position) => { if (position !== 'game_info') bot.chatLines.push(text) })
  return bot
}

function displays (bot) {
  return Object.values(bot.entities).filter(e => e.name === 'block_display')
}

async function openWindow (bot, action, ms = 4000) {
  const opened = new Promise(resolve => bot.once('windowOpen', resolve))
  await action()
  return Promise.race([opened, sleep(ms).then(() => null)])
}

async function main () {
  const p = await join('Prospector')
  check('bot joins', true, `${p.username} on ${version} (${p.version})`)
  await sleep(1500)

  let out = await consoleCmd('oreradar give Prospector radar')
  check('/oreradar give from the console', /Gave Prospector an Ore Radar/.test(out), out.trim().split('\n').pop())
  const radarSlot = await waitFor(() => { const s = slotWithRadar(p); return s >= 0 ? s : null }, 5000)
  const radar = radarSlot === null ? null : itemData(p.inventory.slots[radarSlot])
  check('radar item: a compass marked oreradar:radar=1', radar && radar.name === 'compass' && radar.pdc['oreradar:radar'] === 1,
    radar && JSON.stringify(radar.pdc))
  check('radar item: CustomModelData 7701', radar && JSON.stringify(radar.customModelData).includes('7701'), radar && JSON.stringify(radar.customModelData))
  check('radar item: fresh radar has no charge key (= full)', radar && radar.pdc['oreradar:charge'] === undefined)
  out = await consoleCmd('oreradar info')
  check('/oreradar info from the console', /trackable blocks/.test(out) && /Now: 1 player,/.test(out))

  if (mode === 'full') await full(p, radarSlot)

  p.quit()
  await sleep(1500)
  if (mode === 'full') {
    const left = await serverDisplayCount()
    check('outlines removed when their player quits', left === 0, `${left} block displays left in the world`)
  }
  finish()
}

async function full (p, radarSlot) {
  const w = await join('Watcher')
  await sleep(1000)
  // The scene: a glass floor with air above and three diamond ores. Normally in the sky, far above
  // any natural diamond (more than maxRadius = 96 blocks); a still bot gets it where it stands,
  // where natural diamonds lie farther below than the outline radius (5 + 2 chunks).
  const b = p.entity.position.floored()
  const bx = b.x, bz = b.z, y = still ? b.y : 200
  const scene = [
    `minecraft:gamemode survival Prospector`,
    `minecraft:fill ${bx - 4} ${y} ${bz - 4} ${bx + 21} ${y + 6} ${bz + 4} minecraft:air`,
    `minecraft:fill ${bx - 4} ${y - 1} ${bz - 4} ${bx + 4} ${y - 1} ${bz + 4} minecraft:glass`
  ]
  if (!still) scene.push(`minecraft:tp Prospector ${bx + 0.5} ${y} ${bz + 0.5} -90 0`, `minecraft:tp Watcher ${bx + 2.5} ${y} ${bz + 3.5}`)
  scene.push(`minecraft:setblock ${bx + 5} ${y} ${bz} minecraft:diamond_ore`,
    `minecraft:setblock ${bx + 6} ${y} ${bz} minecraft:deepslate_diamond_ore`,
    `minecraft:setblock ${bx + 20} ${y + 5} ${bz} minecraft:diamond_ore`)
  for (const c of scene) await consoleCmd(c, 400)
  await sleep(1500)
  p.setQuickBarSlot(radarSlot - 36)
  await sleep(500)

  // Target from the console, validated like a player's choice.
  let out = await consoleCmd('oreradar target Prospector @ores/nothing')
  check('/oreradar target refuses an unknown group', /names no block, tag or group/.test(out))
  out = await consoleCmd('oreradar target Prospector @ores/diamond', 3000)
  check('/oreradar target sets the held radar', /now tracks/.test(out), out.trim().split('\n').pop())
  const ores = [[bx + 5, y, bz], [bx + 6, y, bz], [bx + 20, y + 5, bz]]

  const shown = await waitFor(() => displays(p).length === 3 ? displays(p) : null, 6000)
  check('three glowing block displays reach the radar\'s owner', !!shown, `${displays(p).length} seen`)
  if (shown) {
    const at = shown.map(e => [Math.floor(e.position.x), Math.floor(e.position.y), Math.floor(e.position.z)].join(',')).sort()
    check('displays sit on the matched blocks', JSON.stringify(at) === JSON.stringify(ores.map(o => o.join(',')).sort()), at.join(' '))
  }
  check('the other player sees no outline', displays(w).length === 0, `${displays(w).length} seen by Watcher`)
  check('server holds exactly three outline entities', (await serverDisplayCount()) === 3)

  // The needle is the radar's own lodestone; the player's compass target (what plain compasses follow) stays the spawn.
  const spawn = p.compassTargets[0]
  const needle = await waitFor(async () => { const n = await serverNeedle('Prospector'); return n && n.pos && n.pos.join() === ores[0].join() ? n : null }, 5000, 500)
  check('compass needle points at the nearest match: the radar\'s lodestone, untracked', needle && !needle.tracked,
    needle ? `lodestone ${needle.pos}` : `needle ${JSON.stringify(await serverNeedle('Prospector'))}`)

  const bar = await waitFor(() => p.actionBars.find(a => a.text.includes('⛏') && / m/.test(a.text)), 4000)
  check('action bar shows target, distance and charge', !!bar, bar && bar.text)
  const beeps = await waitFor(() => { const n = p.sounds.filter(s => s.name.includes('note_block.pling')); return n.length >= 3 ? n : null }, 5000)
  check('beeps while a match is near', !!beeps, beeps ? `${beeps.length} plings, pitch ${beeps[0].pitch.toFixed(2)}` : `sounds: ${p.sounds.map(s => s.name).slice(0, 5)}`)
  // PLAYERS is 7 in the protocol's category list; MASTER (0) would be loud for whoever cannot mute it alone.
  const categories = [...new Set(p.soundCategories.map(String))]
  check('the beep plays in the Players sound category', categories.length > 0 && categories.every(c => /^(7|players?)$/i.test(c)), categories.join(','))

  // Charge: the drain (10 a second) is kept back while the radar is held (items-test.js covers when it is written).
  await sleep(5500)
  const kept = itemData(p.inventory.slots[radarSlot]).pdc['oreradar:charge']
  check('the drain is not written while the radar is held', kept === undefined, `oreradar:charge ${kept}`)
  p.chatLines.length = 0
  p.chat('/oreradar info')
  const live = await waitFor(() => p.chatLines.find(l => l.startsWith('Radar in slot')), 3000)
  await sleep(500)
  const after = itemData(p.inventory.slots[radarSlot]).pdc['oreradar:charge']
  check('/oreradar info shows the charge without writing the radar', live && /⚡ \d+%/.test(live) && after === undefined, `${live}; oreradar:charge ${after}`)

  // Chest GUI: open with use, pick @ores/iron.
  let win = await openWindow(p, () => p.activateItem())
  check('use opens the chest GUI', win && win.slots.length >= 54, win && `${win.type} ${title(win)}`)
  if (win) {
    const tabs = win.slots.slice(0, 9).filter(Boolean).map(i => itemText(i).split('\n')[0])
    // The default rules have one tab, Ores, listing every entry, so no "All" repeats it.
    check('top row holds the lone Ores tab, no "All"', tabs.length === 1 && /ores/i.test(tabs[0]), tabs.join(' | '))
    // Ancient debris: never near an overworld scene, wherever the bot stands.
    const debris = win.slots.slice(9, 45).findIndex(i => i && itemText(i).includes('minecraft:ancient_debris'))
    check('catalog lists ancient debris and the ore groups', debris >= 0 && win.slots.slice(9, 45).some(i => i && itemText(i).includes('@ores/iron')))
    const bottom = [45, 47, 48, 49, 50, 53].map(s => win.slots[s] && win.slots[s].name)
    check('bottom row: power, current, clear, search', bottom[1] === 'lime_dye' && bottom[3] === 'barrier' && bottom[4] === 'oak_sign', bottom.join(','))
    const closed = new Promise(resolve => p.once('windowClose', resolve))
    await p.clickWindow(9 + debris, 0, 0)
    await Promise.race([closed, sleep(3000)])
    await sleep(800)
    const d = itemData(p.inventory.slots[radarSlot])
    check('clicking an entry tracks it and closes the GUI', d.pdc['oreradar:target'] === 'minecraft:ancient_debris', d.pdc['oreradar:target'])
  }
  await waitFor(() => displays(p).length === 0, 4000)
  check('outlines go when the new target is nowhere near', displays(p).length === 0, `${displays(p).length} left`)

  // Search through the chat prompt.
  await sleep(700)
  win = await openWindow(p, () => p.activateItem())
  if (win) {
    p.chatLines.length = 0
    await p.clickWindow(50, 0, 0)
    const prompt = await waitFor(() => p.chatLines.find(l => l.includes('Ore Radar search')), 3000)
    check('search asks in chat', !!prompt, prompt)
    const reopened = await openWindow(p, () => p.chat('diamond'))
    const entries = reopened ? reopened.slots.slice(9, 45).filter(Boolean).map(i => itemText(i)) : []
    check('search reopens the GUI filtered', reopened && entries.length === 1 && entries[0].includes('@ores/diamond'),
      reopened && `${title(reopened)}, ${entries.length} entries`)
    check('the search line is not broadcast', !(await waitFor(() => w.chatLines.find(l => l.includes('diamond')), 1000)))
    if (reopened) p.closeWindow(reopened)
  } else {
    check('chest GUI reopens for search', false)
  }

  // Sneak + use on a block tracks it (its group); sneak + use in the air switches the radar off.
  await sleep(700)
  p.setControlState('sneak', true)
  await sleep(800)
  await p.lookAt(new Vec3(bx + 5.5, y + 0.5, bz + 0.5))
  await p.activateBlock(p.blockAt(new Vec3(bx + 5, y, bz)))
  const tracking = await waitFor(() => p.actionBars.slice(-3).find(a => a.text.startsWith('Tracking')), 2000)
  check('sneak + use on diamond ore tracks @ores/diamond', itemData(p.inventory.slots[radarSlot]).pdc['oreradar:target'] === '@ores/diamond',
    tracking ? tracking.text : `last action bar: ${(p.actionBars[p.actionBars.length - 1] || {}).text}`)
  await waitFor(() => displays(p).length === 3, 4000)
  check('outlines come back', displays(p).length === 3, `${displays(p).length}`)
  await p.look(0, Math.PI / 2) // straight up, at the sky
  await sleep(700)
  p.activateItem()
  await sleep(1000)
  check('sneak + use in the air switches it off', itemData(p.inventory.slots[radarSlot]).pdc['oreradar:off'] === 1)
  await waitFor(() => displays(p).length === 0, 4000)
  check('an idle radar keeps no outlines', displays(p).length === 0 && (await serverDisplayCount()) === 0)
  const offBar = await waitFor(() => p.actionBars.slice(-3).find(a => / off/.test(a.text)), 3000)
  check('action bar says it is off', !!offBar, offBar && offBar.text)
  // The action bar is shared with the server and other plugins: a radar that is not searching says why once.
  const barsBefore = p.actionBars.length
  await sleep(4000)
  const offLines = p.actionBars.slice(barsBefore)
  check('and does not keep rewriting the action bar', offLines.length <= 1, `${offLines.length} lines in 4 s: ${offLines.map(a => a.text).join(' | ')}`)
  const idleNeedle = await serverNeedle('Prospector')
  check('an idle radar is a plain compass (no lodestone data)', idleNeedle === null, idleNeedle && idleNeedle.raw)
  check('the player\'s compass target never moved', p.compassTargets.length > 0 && p.compassTargets.every(t => t.join() === spawn.join()),
    `spawn positions sent: ${p.compassTargets.map(t => t.join(' ')).join('; ')}`)
  await sleep(700)
  p.activateItem()
  await sleep(1000)
  p.setControlState('sneak', false)
  check('and back on', itemData(p.inventory.slots[radarSlot]).pdc['oreradar:off'] === undefined)
  await waitFor(() => displays(p).length === 3, 4000)

  // A plain lodestone: the radar opens its menu there and never links to it.
  await consoleCmd(`minecraft:setblock ${bx + 2} ${y} ${bz - 2} minecraft:lodestone`, 600)
  await sleep(700)
  win = await openWindow(p, () => p.activateBlock(p.blockAt(new Vec3(bx + 2, y, bz - 2))))
  check('use on a plain lodestone opens the radar menu', win && /Ore Radar/.test(title(win)), win && title(win))
  if (win) p.closeWindow(win)
  await sleep(1500)
  const afterLodestone = await serverNeedle('Prospector')
  check('and does not link the radar to it', !afterLodestone || (!afterLodestone.tracked && JSON.stringify(afterLodestone.pos) !== JSON.stringify([bx + 2, y, bz - 2])),
    afterLodestone ? `needle ${afterLodestone.pos}, tracked=${afterLodestone.tracked}` : 'no lodestone data')

  // Charging station: place it, feed it redstone, charge a nearly empty radar.
  out = await consoleCmd('oreradar charge Prospector 1000')
  check('/oreradar charge sets the held radar', /1,000 \/ 36,000/.test(out))
  await consoleCmd('oreradar give Prospector station', 600)
  await consoleCmd('minecraft:give Prospector minecraft:redstone 16', 600)
  await sleep(800)
  const stationSlot = p.inventory.slots.findIndex(i => i && itemData(i).pdc['oreradar:station'] === 1)
  check('station item: a lodestone marked oreradar:station=1', stationSlot >= 0 && p.inventory.slots[stationSlot].name === 'lodestone')
  await p.look(Math.PI, Math.PI / 4)
  p.setQuickBarSlot(stationSlot - 36)
  await sleep(500)
  const floor = p.blockAt(new Vec3(bx - 2, y - 1, bz))
  try {
    await p.placeBlock(floor, new Vec3(0, 1, 0))
  } catch (e) {
    console.log(`  (placeBlock: ${e.message})`)
  }
  await sleep(800)
  const stationBlock = p.blockAt(new Vec3(bx - 2, y, bz))
  check('station placed', stationBlock && stationBlock.name === 'lodestone', stationBlock && stationBlock.name)
  p.setQuickBarSlot(radarSlot - 36)
  await sleep(300)
  win = await openWindow(p, () => p.activateBlock(stationBlock))
  check('right-click opens the 9-slot station GUI', win && win.slots.length >= 9 && /Charging Station/.test(title(win)),
    win && `${win.type} ${title(win)}`)
  if (win) {
    const linked = await serverNeedle('Prospector')
    check('no lodestone linking on a station', !linked || (!linked.tracked && JSON.stringify(linked.pos) !== JSON.stringify([bx - 2, y, bz])),
      linked ? `needle ${linked.pos}, tracked=${linked.tracked}` : 'no lodestone data')
    const winRadar = win.inventoryStart + 27 + (radarSlot - 36)
    const winRedstone = win.slots.findIndex((i, n) => n >= win.inventoryStart && i && i.name === 'redstone')
    await p.clickWindow(4, 0, 0) // a pane: nothing happens
    await p.clickWindow(winRadar, 0, 0)
    await p.clickWindow(1, 0, 0)
    await p.clickWindow(winRedstone, 0, 0)
    await p.clickWindow(7, 0, 0)
    await sleep(600)
    check('panes cannot be taken', !p.inventory.slots.some(i => i && i.name.endsWith('stained_glass_pane')))
    check('radar and redstone sit in their slots', win.slots[1] && win.slots[1].name === 'compass' && win.slots[7] && win.slots[7].name === 'redstone',
      `${win.slots[1] && win.slots[1].name}, ${win.slots[7] && win.slots[7].name} x${win.slots[7] && win.slots[7].count}`)
    const full = await waitFor(() => win.slots[1] && itemData(win.slots[1]).pdc['oreradar:charge'] === 36000, 15000, 250)
    const charge = win.slots[1] ? itemData(win.slots[1]).pdc['oreradar:charge'] : null
    check('the station charges the radar from redstone', !!full, `radar charge ${charge}, redstone left ${win.slots[7] ? win.slots[7].count : 0}`)
    check('panes show the buffer', /Buffer: [\d,]+ \/ 200,000/.test(itemText(win.slots[4])), itemText(win.slots[4]).split('\n')[0])
    await p.clickWindow(1, 0, 1) // shift-click the radar back
    await sleep(500)
    p.closeWindow(win)
  }
  await sleep(500)
  out = await consoleCmd('oreradar info')
  check('/oreradar info counts the station', /1 station loaded/.test(out))
  const back = slotWithRadar(p)
  check('radar back in the inventory', back >= 0)
  if (back >= 36) p.setQuickBarSlot(back - 36)
  await waitFor(() => displays(p).length === 3, 4000)
  check('outlines again once the radar is back in hand', displays(p).length === 3, `${displays(p).length}`)
  w.quit()
  await nether(p, bx, bz, y)
  await moddedClient()
}

/**
 * A client with the mod is served as a vanilla client (DESIGN §8): the plugin registers no
 * oreradar: channels, answers no hello, and its radar still opens the chest GUI.
 */
async function moddedClient () {
  const registered = []
  const payloads = []
  const m = await joinBot('Modder', bot => bot._client.on('custom_payload', packet => {
    if (/register$/.test(packet.channel)) registered.push(...packet.data.toString('utf8').split('\0'))
    else if (packet.channel.startsWith('oreradar:')) payloads.push(packet.channel)
  }))
  await sleep(1000)
  const send = (channel, bytes) => m._client.write('custom_payload', { channel, data: Buffer.from(bytes) })
  // The mod registers the channels it listens on, then says hello: protocol 1, hands, probe 0, outlines, arrow and beep.
  send('minecraft:register', Buffer.from(['oreradar:config', 'oreradar:scan'].join('\0')))
  await sleep(300)
  send('oreradar:hello', [1, 0, 0, 7])
  await sleep(2500)
  check('a client with the mod: the server registers no oreradar: channel', !registered.some(c => c.startsWith('oreradar:')), registered.join(' ') || 'none registered')
  check('and sends it nothing on them, hello or not', payloads.length === 0, payloads.join(' '))
  await consoleCmd('oreradar give Modder radar', 600)
  m.setQuickBarSlot(0)
  await sleep(500)
  const win = await openWindow(m, () => m.activateItem())
  check('its radar opens the chest GUI like any vanilla client\'s', win && /Ore Radar/.test(title(win)), win && title(win))
  if (win) m.closeWindow(win)
  m.quit()
  await sleep(500)
}

/**
 * In the Nether a plain compass spins on 1.20.x and 1.21.1 clients, so the needle is the radar's own
 * lodestone. It stays on its block while the new nearest lies within one needle frame (11.25°) of it,
 * and moves once that block is mined.
 */
async function nether (p, bx, bz, y) {
  const ny = 100
  const inNether = c => consoleCmd(`minecraft:execute in minecraft:the_nether run ${c}`, 400)
  // Nothing loads the Nether's chunks before the player gets there, and a block command on an
  // unloaded chunk only says so: load them first, and fail if the scene still was not built.
  const area = `${bx - 3} ${bz - 3} ${bx + 21} ${bz + 3}`
  await inNether(`forceload add ${area}`)
  let unbuilt = true
  for (let tries = 0; tries < 10; tries++) {
    await sleep(500)
    const out = await inNether(`fill ${bx - 3} ${ny - 1} ${bz - 3} ${bx + 21} ${ny + 4} ${bz + 3} minecraft:air`)
    unbuilt = /not loaded/.test(out)
    if (!unbuilt) break
  }
  const outs = [await inNether(`fill ${bx - 3} ${ny - 1} ${bz - 3} ${bx + 3} ${ny - 1} ${bz + 3} minecraft:glass`),
    await inNether(`setblock ${bx + 20} ${ny} ${bz} minecraft:ancient_debris`)]
  check('the Nether scene is built', !unbuilt && !outs.some(o => /not loaded/.test(o)), outs.map(o => o.trim().split('\n').pop()).join(' | '))
  await inNether(`tp Prospector ${bx + 0.5} ${ny} ${bz + 0.5} -90 0`)
  await inNether(`forceload remove ${area}`)
  await sleep(1500)
  await consoleCmd('oreradar target Prospector minecraft:ancient_debris', 2500)
  const needleAt = async want => waitFor(async () => { const n = await serverNeedle('Prospector'); return n && n.pos && n.pos.join() === want.join() ? n : null }, 5000, 500)
  const far = [bx + 20, ny, bz], near = [bx + 10, ny, bz]
  let n = await needleAt(far)
  check('in the Nether the needle is the radar\'s lodestone, untracked', n && !n.tracked, n ? `lodestone ${n.pos}` : `needle ${JSON.stringify(await serverNeedle('Prospector'))}`)
  await inNether(`setblock ${near.join(' ')} minecraft:ancient_debris`)
  await sleep(2500)
  n = await serverNeedle('Prospector')
  check('a nearer match in the same direction does not move it (no rewrite)', n && n.pos && n.pos.join() === far.join(), n && `lodestone ${n.pos}`)
  await inNether(`setblock ${far.join(' ')} minecraft:air`)
  n = await needleAt(near)
  check('once its block is mined it moves to the nearest', !!n, `needle ${JSON.stringify(await serverNeedle('Prospector'))}`)
  await consoleCmd(`minecraft:execute in minecraft:overworld run tp Prospector ${bx + 0.5} ${y} ${bz + 0.5} -90 0`, 1500)
  n = await serverNeedle('Prospector')
  check('back in the overworld the radar is a plain compass again', n === null, n && n.raw)
}

main().catch(e => {
  console.log(`FAIL ${e.stack || e}`)
  process.exit(1)
})
