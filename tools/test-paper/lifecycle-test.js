// Charging station and outline life cycle across a server restart, on a test server it restarts
// itself through tools/test-paper/server.sh:
//   - a station keeps its buffer in the chunk across a restart;
//   - breaking it drops a station item that keeps the buffer;
//   - an explosion drops it the same way;
//   - pistons cannot move it;
//   - a leftover outline display (one carrying the oreradar:outline marker) is gone after a start.
//
//   NODE_PATH=<dir with mineflayer> node tools/test-paper/lifecycle-test.js <version>
'use strict'
const fs = require('fs')
const path = require('path')
const { execFileSync } = require('child_process')
const { Vec3 } = require('vec3')
const { harness, itemData, itemText } = require(path.join(__dirname, 'harness.js'))

const version = process.argv[2]
const { consoleLog, sleep, check, finish, waitFor, consoleCmd, count, join: joinBot } = harness(version)
const serverSh = path.join(__dirname, 'server.sh')

function pdc (item) {
  return item ? itemData(item).pdc : {}
}
function paneName (item) {
  return itemText(item).split('\n')[0]
}
function join () {
  return joinBot('Keeper')
}
async function openStation (bot, block) {
  const opened = new Promise(resolve => bot.once('windowOpen', resolve))
  await bot.activateBlock(block)
  return Promise.race([opened, sleep(4000).then(() => null)])
}

async function main () {
  let bot = await join()
  await sleep(1000)
  const b = bot.entity.position.floored()
  // A height of its own: bot-test.js builds at 200 and items-test.js at 250 around the same spawn,
  // where each bot lands a few blocks apart.
  const bx = b.x, bz = b.z, y = 225
  const at = new Vec3(bx - 2, y, bz)
  for (const c of [
    'minecraft:gamemode survival Keeper',
    `minecraft:fill ${bx - 6} ${y - 1} ${bz - 4} ${bx + 4} ${y - 1} ${bz + 4} minecraft:glass`,
    `minecraft:tp Keeper ${bx + 0.5} ${y} ${bz + 0.5} 90 30`,
    'oreradar give Keeper station',
    'minecraft:give Keeper minecraft:redstone 4',
    // A leftover outline, as a crash could leave one: persistent, with the plugin's marker.
    `minecraft:summon minecraft:block_display ${bx} ${y + 3} ${bz} {BukkitValues:{"oreradar:outline":1b},block_state:{Name:"minecraft:stone"}}`
  ]) await consoleCmd(c, 400)
  await sleep(800)
  check('a marked leftover display exists before the restart', (await count('@e[type=minecraft:block_display]')) === 1)
  bot.setQuickBarSlot(bot.inventory.slots.findIndex(i => i && pdc(i)['oreradar:station'] === 1) - 36)
  await sleep(300)
  await bot.placeBlock(bot.blockAt(at.offset(0, -1, 0)), new Vec3(0, 1, 0)).catch(e => console.log(`  (placeBlock: ${e.message})`))
  await sleep(500)
  let win = await openStation(bot, bot.blockAt(at))
  check('station placed and opened', !!win)
  if (win) {
    const redstone = win.slots.findIndex((i, n) => n >= win.inventoryStart && i && i.name === 'redstone')
    await bot.clickWindow(redstone, 0, 0)
    await bot.clickWindow(7, 0, 0)
    const filled = await waitFor(() => /Buffer: 16,000/.test(paneName(win.slots[4])), 4000)
    check('four redstone burn into a 16,000 buffer', !!filled, paneName(win.slots[4]))
    bot.closeWindow(win)
  }
  // Pistons cannot push a station.
  await consoleCmd(`minecraft:setblock ${bx - 3} ${y} ${bz} minecraft:piston[facing=east]`, 300)
  await consoleCmd(`minecraft:setblock ${bx - 4} ${y} ${bz} minecraft:redstone_block`, 1000)
  check('a piston does not move the station', bot.blockAt(at).name === 'lodestone' && bot.blockAt(at.offset(1, 0, 0)).name === 'air')
  await consoleCmd(`minecraft:fill ${bx - 4} ${y} ${bz} ${bx - 3} ${y} ${bz} minecraft:air`, 300)
  bot.quit()
  await sleep(1000)

  console.log('  restarting the server')
  execFileSync(serverSh, ['stop', version], { stdio: 'inherit' })
  execFileSync(serverSh, ['start', version], { stdio: 'inherit' })
  check('the leftover display is removed at start', (await count('@e[type=minecraft:block_display]')) === 0,
    /Removed 1 leftover outline displays/.test(fs.readFileSync(consoleLog, 'utf8')) ? 'logged at enable' : 'removed as its chunk loaded')

  bot = await join()
  await sleep(1000)
  await consoleCmd(`minecraft:tp Keeper ${bx + 0.5} ${y} ${bz + 0.5} 90 30`, 1500)
  win = await openStation(bot, bot.blockAt(at))
  check('after the restart the station still holds its buffer', win && /Buffer: 16,000/.test(paneName(win.slots[4])), win && paneName(win.slots[4]))
  if (win) bot.closeWindow(win)

  // Break it: a station item that keeps the buffer.
  await consoleCmd('minecraft:give Keeper minecraft:diamond_pickaxe', 600)
  await bot.equip(bot.inventory.items().find(i => i.name === 'diamond_pickaxe'), 'hand')
  await bot.dig(bot.blockAt(at))
  await consoleCmd(`minecraft:tp Keeper ${at.x + 0.5} ${y} ${at.z + 0.5}`, 1500)
  const dropped = await waitFor(() => bot.inventory.items().find(i => i.name === 'lodestone'), 4000)
  check('breaking drops a station item that keeps the buffer', dropped && pdc(dropped)['oreradar:station'] === 1 && pdc(dropped)['oreradar:buffer'] === 16000,
    dropped && JSON.stringify(pdc(dropped)))

  // Place it again (the buffer comes back) and blow it up.
  await consoleCmd('minecraft:gamemode creative Keeper', 300)
  await consoleCmd(`minecraft:tp Keeper ${bx + 0.5} ${y} ${bz + 0.5} 90 30`, 800)
  await bot.equip(dropped, 'hand')
  await bot.placeBlock(bot.blockAt(at.offset(0, -1, 0)), new Vec3(0, 1, 0)).catch(e => console.log(`  (placeBlock: ${e.message})`))
  await sleep(500)
  await consoleCmd(`minecraft:tp Keeper ${bx + 30} ${y + 10} ${bz}`, 500)
  // The fuse tag is Fuse up to 1.20.2 and fuse after; the other is ignored.
  await consoleCmd(`minecraft:summon minecraft:tnt ${at.x + 0.5} ${y + 1} ${at.z + 0.5} {Fuse:1,fuse:1}`, 300)
  const item = version === '1.20.1'
    ? '@e[type=minecraft:item,nbt={Item:{id:"minecraft:lodestone",tag:{PublicBukkitValues:{"oreradar:buffer":16000}}}}]'
    : '@e[type=minecraft:item,nbt={Item:{id:"minecraft:lodestone",components:{"minecraft:custom_data":{PublicBukkitValues:{"oreradar:buffer":16000}}}}}]'
  check('an explosion drops it as a station item with its buffer', await waitFor(async () => (await count(item)) === 1, 8000, 500))
  bot.quit()
  await sleep(800)
  finish()
}

main().catch(e => {
  console.log(`FAIL ${e.stack || e}`)
  process.exit(1)
})
