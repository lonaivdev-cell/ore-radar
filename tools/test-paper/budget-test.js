// The scan budget is per tick, shared by every player (DESIGN §5 "Time budget"): N bots hover 40
// blocks apart at y=100 and track @ores/diamond, a heavy scan wherever they are (nearly every chunk
// has a diamond somewhere, so the palette check passes, and every terrain section in reach is
// read). All scans together must then stay within scanBudgetMicros per tick, as /oreradar info
// reports it ("Scan time per tick over the last 5 s"), and each scan is spread over more ticks.
//
//   NODE_PATH=<dir with mineflayer> node tools/test-paper/budget-test.js <version> [bots=4]
//
// Paper's /mspt cannot show this: it counts the "oversleep" in a tick, where the server thread
// runs queued tasks until the next tick is due, so added work shrinks that and the average hardly
// moves (the thread's CPU time neither). The server must be running (server.sh) with the default
// scanBudgetMicros (2000).
'use strict'
const path = require('path')
const { harness } = require(path.join(__dirname, 'harness.js'))

const version = process.argv[2]
const bots = Number(process.argv[3] || 4)
const { sleep, check, finish, consoleCmd, join } = harness(version)
const BUDGET_MICROS = 2000

/** The scan time per tick that /oreradar info reports: {average, max} in µs. */
async function scanLoad () {
  const out = await consoleCmd('oreradar info', 800)
  const m = out.match(/Scan time per tick over the last 5 s: ([\d,]+) µs on average, ([\d,]+) µs at most/)
  return m ? { average: Number(m[1].replace(/,/g, '')), max: Number(m[2].replace(/,/g, '')) } : null
}

async function main () {
  const gauges = []
  for (let i = 0; i < bots; i++) {
    gauges.push(await join('Gauge' + i))
    await sleep(300)
  }
  for (let i = 0; i < bots; i++) {
    await consoleCmd(`oreradar give Gauge${i} radar`, 200)
    await consoleCmd(`minecraft:gamemode spectator Gauge${i}`, 200) // hovers, and nothing can kill it
    await consoleCmd(`minecraft:tp Gauge${i} ${i * 40} 100 0`, 200)
  }
  for (const g of gauges) g.setQuickBarSlot(0)
  await sleep(15000) // the chunks around them load
  for (let i = 0; i < bots; i++) await consoleCmd(`oreradar target Gauge${i} @ores/diamond`, 200)
  await sleep(7000) // the 5 s window holds nothing but scanning
  const load = await scanLoad()
  const lines = []
  gauges[0].on('messagestr', (text, position) => { if (position !== 'game_info') lines.push(text) })
  gauges[0].chat('/oreradar info')
  await sleep(800)
  console.log(`  Gauge0: ${lines.find(l => l.startsWith('Last scan:')) || 'no scan line'}`)
  // At most: the last section of a tick may run past the deadline by what the reserve misjudged; a
  // GC pause inside a scan counts too, hence the margin. Without a shared budget, N players take N budgets.
  check(`${bots} players scanning share one ${BUDGET_MICROS} µs budget per tick`,
    !!load && load.average > 0 && load.average <= BUDGET_MICROS && load.max <= 1.5 * BUDGET_MICROS,
    load ? `${load.average} µs per tick on average, ${load.max} µs at most` : 'no scan time line in /oreradar info')
  for (const g of gauges) g.quit()
  await sleep(1000)
  finish()
}

main().catch(e => {
  console.log(`FAIL ${e.stack || e}`)
  process.exit(1)
})
