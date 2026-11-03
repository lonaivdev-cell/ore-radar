// Shared plumbing for the Paper test scripts in this folder: the console of a server started by
// server.sh, mineflayer bots, and PASS/FAIL bookkeeping.
//
// Environment:
//   OR_SCRATCH         where server.sh keeps paper-runs/<version> (default: runs/paper-test)
//   OR_CLIENT_VERSION  join with another protocol version, through ViaVersion/ViaBackwards on the server
//   OR_STILL=1         the bots send no movement, only answers to server teleports: mineflayer's movement
//                      packets, translated by ViaBackwards, are rejected by Paper 26.3
// run-all.sh sets both for 26.3.
'use strict'
const fs = require('fs')
const path = require('path')
const mineflayer = require('mineflayer')
const nbt = require('prismarine-nbt')

const PORTS = { '1.20.1': 25631, '1.21.1': 25632, '26.3': 25633 }
// Every other version (tools/test-paper/compat.sh) runs alone on 25634.
const portFor = (version) => PORTS[version] || 25634

function harness (version) {
  const scratch = process.env.OR_SCRATCH || path.join(__dirname, '..', '..', 'runs', 'paper-test')
  const run = path.join(scratch, 'paper-runs', version)
  const fifo = path.join(run, 'console.fifo')
  const consoleLog = path.join(run, 'console.log')
  const clientVersion = process.env.OR_CLIENT_VERSION || version
  const still = process.env.OR_STILL === '1'
  let failures = 0

  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

  function check (name, ok, detail) {
    if (!ok) failures++
    console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail !== undefined ? ' — ' + detail : ''}`)
  }

  /** Prints the summary and exits 1 if any check failed. */
  function finish () {
    console.log(failures === 0 ? 'ALL PASSED' : `${failures} FAILED`)
    process.exit(failures === 0 ? 0 : 1)
  }

  async function waitFor (fn, ms, step = 100) {
    const end = Date.now() + ms
    for (;;) {
      const v = await fn()
      if (v || Date.now() > end) return v
      await sleep(step)
    }
  }

  /** Types a console command and returns the log written within waitMs. */
  async function consoleCmd (cmd, waitMs = 1000) {
    const offset = fs.statSync(consoleLog).size
    fs.appendFileSync(fifo, cmd + '\n')
    await sleep(waitMs)
    return fs.readFileSync(consoleLog).subarray(offset).toString('utf8')
  }

  /**
   * Sets one key of the plugin's oreradar-server.toml (its first `key = ...` line) and runs
   * /oreradar reload; returns what the reload logged.
   */
  async function setConfig (key, tomlValue) {
    const file = path.join(run, 'plugins', 'OreRadar', 'oreradar-server.toml')
    const text = fs.readFileSync(file, 'utf8')
    const line = new RegExp(`^${key} = .*$`, 'm')
    if (!line.test(text)) throw new Error(`no ${key} in ${file}`)
    fs.writeFileSync(file, text.replace(line, `${key} = ${tomlValue}`))
    return consoleCmd('oreradar reload', 1500)
  }

  /** How many entities match a selector, as the server counts them (0 when none). */
  async function count (selector) {
    const out = await consoleCmd(`minecraft:execute if entity ${selector}`, 800)
    const m = out.match(/Test passed[,.] [Cc]ount: (\d+)/)
    return m ? Number(m[1]) : 0
  }

  /** A bot that has spawned; {@code setup(bot)}, if given, runs before it connects, e.g. to watch packets sent at join. */
  function join (username, setup) {
    return new Promise((resolve, reject) => {
      const bot = mineflayer.createBot({ host: '127.0.0.1', port: portFor(version), username, version: clientVersion, auth: 'offline' })
      // mineflayer sends sneaking only as player_input from 1.21.3 on, but servers before 1.21.6 still
      // take it from entity_action, as a real client of those versions also sends it.
      if (['1.21.3', '1.21.4', '1.21.5'].includes(clientVersion)) bot.once('login', () => {
        const setControlState = bot.setControlState.bind(bot)
        bot.setControlState = (control, state) => {
          const changed = control === 'sneak' && bot.getControlState('sneak') !== state
          setControlState(control, state)
          if (changed) bot._client.write('entity_action', { entityId: bot.entity.id, actionId: state ? 'start_sneaking' : 'stop_sneaking', jumpBoost: 0 })
        }
      })
      if (setup) setup(bot)
      if (still) {
        const write = bot._client.write.bind(bot._client)
        let teleported = false
        bot._client.on('position', () => { teleported = true })
        bot._client.write = (name, data) => {
          if (name === 'position' || name === 'flying' || name === 'look') return
          if (name === 'position_look') {
            if (!teleported) return
            teleported = false
          }
          write(name, data)
        }
      }
      bot.once('spawn', () => resolve(bot))
      bot.once('kicked', reason => reject(new Error(`${username} kicked: ${JSON.stringify(reason)}`)))
      bot.once('error', reject)
    })
  }

  return { version, run, consoleLog, still, sleep, check, finish, waitFor, consoleCmd, setConfig, count, join }
}

/**
 * An item as the client got it: Paper's PublicBukkitValues and CustomModelData, from NBT (1.20.1)
 * or components (1.20.5+). The lodestone is read server-side instead (bot-test.js serverNeedle):
 * mineflayer misparses some 1.21 item components.
 */
function itemData (item) {
  if (!item) return null
  const out = { name: item.name, pdc: {}, customModelData: null }
  if (item.nbt) {
    const tag = nbt.simplify(item.nbt)
    Object.assign(out.pdc, tag.PublicBukkitValues || {})
    if (tag.CustomModelData !== undefined) out.customModelData = tag.CustomModelData
  }
  for (const c of item.components || []) {
    if (c.type === 'custom_data' && c.data) Object.assign(out.pdc, nbt.simplify(c.data).PublicBukkitValues || {})
    else if (c.type === 'custom_model_data') out.customModelData = c.data
  }
  return out
}

/** A window title as text, whether the client got it as JSON (1.20) or NBT (1.20.3+). */
function title (window) {
  return typeof window.title === 'string' ? window.title : JSON.stringify(window.title)
}

/** An item's name and lore as text, for matching. */
function itemText (item) {
  if (!item) return ''
  const parts = []
  if (item.customName) parts.push(typeof item.customName === 'string' ? item.customName : JSON.stringify(item.customName))
  if (item.nbt) {
    const d = nbt.simplify(item.nbt).display || {}
    if (d.Name) parts.push(d.Name)
    for (const l of d.Lore || []) parts.push(l)
  }
  for (const c of item.components || []) {
    if (c.type === 'custom_name' || c.type === 'lore') parts.push(JSON.stringify(c.data))
  }
  return parts.join('\n')
}

module.exports = { harness, itemData, itemText, title }
