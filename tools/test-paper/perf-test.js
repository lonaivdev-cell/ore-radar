// Measures the server-side scan (DESIGN §5) in natural terrain: a bot stands at the world spawn,
// tracks one target after another with /oreradar target, and reads its own "Last scan" line from
// /oreradar info (CPU time, ticks, chunks visited and skipped by the palette check, sections read).
//
//   NODE_PATH=<dir with mineflayer> node tools/test-paper/perf-test.js <version> [spec...]
//
// OR_PERF_Y=<y> measures from that height (in spectator mode, so stone around is no problem);
// by default the player stands on the surface where it is.
//
// The targets must be trackable under the server's rules: stone needs its [categories.stone] table
// uncommented, or allowAll = true.
'use strict'
const path = require('path')
const { harness } = require(path.join(__dirname, 'harness.js'))

const version = process.argv[2]
const specs = process.argv.length > 3 ? process.argv.slice(3) : ['@ores/iron', '@ores/coal', '@ores/diamond', 'minecraft:ancient_debris']
const { sleep, consoleCmd, join } = harness(version)
const SAMPLES = 5

async function main () {
  const bot = await join('Gauge')
  const lines = []
  bot.on('messagestr', (text, position) => { if (position !== 'game_info') lines.push(text) })
  await consoleCmd('oreradar give Gauge radar', 300)
  if (process.env.OR_PERF_Y) {
    await consoleCmd('minecraft:gamemode spectator Gauge', 300)
    await consoleCmd(`minecraft:tp Gauge 0 ${process.env.OR_PERF_Y} 0`, 300)
  } else {
    await consoleCmd('minecraft:gamemode survival Gauge', 300)
    await consoleCmd('minecraft:execute as Gauge at @s positioned over world_surface run tp @s ~ ~ ~', 300)
  }
  await sleep(10000) // chunks around the player finish loading
  bot.setQuickBarSlot(0)
  const pos = bot.entity.position
  console.log(`Paper ${version}, player at ${pos.x.toFixed(0)} ${pos.y.toFixed(0)} ${pos.z.toFixed(0)}, ${SAMPLES} scans per target`)
  for (const spec of specs) {
    await consoleCmd(`oreradar target Gauge ${spec}`, 2500)
    const samples = []
    for (let i = 0; i < SAMPLES; i++) {
      await sleep(1100) // one scan interval (20 ticks) and a bit
      lines.length = 0
      bot.chat('/oreradar info')
      await sleep(600)
      const scan = lines.find(l => l.startsWith('Last scan:'))
      const status = lines.find(l => l.startsWith('Radar in slot'))
      if (scan) samples.push({ scan, status })
    }
    const micros = samples.map(s => Number(s.scan.match(/Last scan: ([\d,]+) µs/)[1].replace(/,/g, ''))).sort((a, b) => a - b)
    const median = samples.find(s => Number(s.scan.match(/Last scan: ([\d,]+) µs/)[1].replace(/,/g, '')) === micros[Math.floor(micros.length / 2)])
    console.log(`${spec}: median ${micros[Math.floor(micros.length / 2)]} µs (min ${micros[0]}, max ${micros[micros.length - 1]})`)
    if (median) console.log(`  ${median.scan}\n  ${median.status}`)
  }
  bot.quit()
  await sleep(500)
  process.exit(0)
}

main().catch(e => {
  console.log(`FAIL ${e.stack || e}`)
  process.exit(1)
})
