// Testbot fuer CamFly.
//
// Der Bot joint, schreibt alles Beobachtete zeilenweise nach stdout und
// nimmt ueber eine FIFO Befehle entgegen. So laesst sich ein Testlauf aus
// der Shell steuern, ohne fuer jeden Versuch ein neues Skript zu schreiben.
//
// Aufruf: node bot.js <name> <host> <port> <befehls-fifo>
//
// Der Name kommt als Argument und hat absichtlich keinen Standardwert:
// Mit einem Standardwert joint ein zweiter Bot unter dem Namen des ersten
// und wirft ihn mit duplicate_login hinaus.

require('./patch26_2.js')

const fs = require('fs')
const mineflayer = require('mineflayer')

const [, , NAME, HOST, PORT, FIFO] = process.argv
if (!NAME || !HOST || !PORT || !FIFO) {
  console.error('Aufruf: node bot.js <name> <host> <port> <befehls-fifo>')
  process.exit(2)
}

function out (kind, text) {
  const ts = new Date().toISOString().slice(11, 23)
  process.stdout.write(`${ts} ${kind} ${text}\n`)
}

out('BOOT', `name=${NAME} host=${HOST} port=${PORT}`)

const bot = mineflayer.createBot({
  host: HOST,
  port: Number(PORT),
  username: NAME,
  auth: 'offline',
  version: '26.2',
  hideErrors: false
})

// --- Beobachtung -----------------------------------------------------------

bot.on('login', () => out('LOGIN', `als ${bot.username}`))

bot.once('spawn', () => {
  out('SPAWN', JSON.stringify(round(bot.entity.position)))
  out('READY', 'bereit fuer Befehle')
})

bot.on('respawn', () => out('RESPAWN', ''))
bot.on('death', () => out('DEATH', 'der Bot ist gestorben'))
bot.on('health', () => out('HEALTH', `hp=${bot.health} food=${bot.food}`))
bot.on('kicked', (reason) => out('KICKED', typeof reason === 'string' ? reason : JSON.stringify(reason)))
bot.on('end', (reason) => { out('END', String(reason)); process.exit(0) })
bot.on('error', (err) => out('ERROR', err && err.stack ? err.stack.split('\n')[0] : String(err)))

// position 1 ist die Actionbar, 0 und 2 sind Chat und Systemmeldung.
bot.on('message', (msg, position) => {
  const text = msg.toString()
  if (text.trim() === '') return
  out(position === 'game_info' || position === 1 ? 'ACTIONBAR' : 'CHAT', text)
})

// --- Befehle ---------------------------------------------------------------

function round (p) {
  return p ? { x: +p.x.toFixed(3), y: +p.y.toFixed(3), z: +p.z.toFixed(3) } : null
}

// Wartet auf die naechste Chatzeile, die zum Muster passt. Dient dazu, die
// Antwort des Servers auf einen Befehl einzusammeln.
function awaitMessage (pattern, ms) {
  return new Promise((resolve) => {
    const timer = setTimeout(() => { bot.removeListener('message', on); resolve(null) }, ms)
    function on (msg) {
      const text = msg.toString()
      if (pattern.test(text)) {
        clearTimeout(timer)
        bot.removeListener('message', on)
        resolve(text)
      }
    }
    bot.on('message', on)
  })
}

// bot.creative.flyTo laesst sich nicht abbrechen: Die Schleife schiebt
// bot.entity.position selbst weiter und setzt dabei jedes Mal die Schwerkraft
// auf 0, stopFlying() greift also mitten im Flug nicht. Ein blosser Timeout
// drumherum beendet nur das Warten - die Schleife fliegt weiter und zerrt den
// Bot spaeter quer durch die Welt. Darum dieselbe Mechanik noch einmal, aber
// mit Abbruch.
const FLY_STEP = 0.5    // Bloecke je Schritt, wie bei mineflayer
const FLY_TICK = 50     // ms je Schritt
let flight = null

function sleep (ms) { return new Promise((resolve) => setTimeout(resolve, ms)) }

async function flyTo (target, timeoutMs) {
  if (flight) {                       // ein alter Flug laeuft noch
    flight.abort = true
    await sleep(FLY_TICK * 3)
  }
  const { Vec3 } = require('vec3')
  const state = { abort: false }
  flight = state
  try {
    bot.creative.startFlying()
  } catch (err) {
    flight = null
    return `startFlying fehlgeschlagen: ${err.message}`
  }

  const started = Date.now()
  let sample = bot.entity.position.clone()
  let sampledAt = Date.now()

  while (true) {
    if (state.abort) { flight = null; return 'abgebrochen' }

    const vector = target.minus(bot.entity.position)
    const magnitude = Math.sqrt(vector.x * vector.x + vector.y * vector.y + vector.z * vector.z)
    if (magnitude <= FLY_STEP) {
      bot.entity.position = target.clone()
      flight = null
      return 'angekommen'
    }
    if (Date.now() - started > timeoutMs) { flight = null; return 'timeout' }

    bot.physics.gravity = 0
    bot.entity.velocity = new Vec3(0, 0, 0)
    bot.entity.position.add(vector.scaled(1 / magnitude).scaled(FLY_STEP))
    await sleep(FLY_TICK)

    // Haelt das Plugin ihn auf, holt der Server ihn jeden Schritt zurueck.
    // Netto steht er dann - und das ist eine Antwort, kein Fehler.
    if (Date.now() - sampledAt > 750) {
      if (bot.entity.position.distanceTo(sample) < 0.5) { flight = null; return 'blockiert' }
      sample = bot.entity.position.clone()
      sampledAt = Date.now()
    }
  }
}

const handlers = {
  // Schickt eine Zeile in den Chat. Mit fuehrendem / ist es ein Befehl.
  async chat (rest) {
    bot.chat(rest)
    out('SENT', rest)
  },

  // Die Sicht des Clients. Sie laeuft optimistisch voraus und ist nicht
  // immer das, was der Server glaubt.
  async pos () {
    out('POS', JSON.stringify(round(bot.entity && bot.entity.position)))
  },

  // Die Wahrheit vom Server. Braucht op.
  async truth () {
    const answer = awaitMessage(/Pos|has the following entity data|Keine Entity|No entity/i, 4000)
    bot.chat('/data get entity @s Pos')
    const text = await answer
    out('TRUTH', text === null ? 'keine Antwort (fehlt dem Bot op?)' : text)
  },

  // Fliegen im Cam-Modus.
  async fly (rest) {
    const parts = rest.split(/\s+/)
    const [x, y, z] = parts.map(Number)
    const limit = parts.length > 3 ? Number(parts[3]) * 1000 : 15000
    if ([x, y, z].some(Number.isNaN)) { out('FAIL', 'fly braucht x y z [sekunden]'); return }
    const { Vec3 } = require('vec3')
    const target = new Vec3(x, y, z)
    const before = round(bot.entity.position)
    const reason = await flyTo(target, limit)
    out('FLY', `${reason} ziel=${x},${y},${z} von=${JSON.stringify(before)} jetzt=${JSON.stringify(round(bot.entity.position))}`)
  },

  async stopfly () {
    if (flight) flight.abort = true
    try { bot.creative.stopFlying() } catch (err) { out('FAIL', `stopFlying: ${err.message}`) }
    out('STOPFLY', 'ok')
  },

  async look (rest) {
    const [yaw, pitch] = rest.split(/\s+/).map(Number)
    await bot.look(yaw * Math.PI / 180, pitch * Math.PI / 180, true)
    out('LOOK', `${yaw} ${pitch}`)
  },

  // Was der Bot gerade an Effekten und Sichtbarkeit hat.
  async state () {
    const effects = Object.values(bot.entity.effects || {}).map((e) => `${e.id}:${e.amplifier}`)
    out('STATE', JSON.stringify({
      hp: bot.health,
      food: bot.food,
      gamemode: bot.game && bot.game.gameMode,
      effects,
      pos: round(bot.entity.position)
    }))
  },

  // Alle Entities in der Naehe - so sehen wir den Koerper, den CamFly setzt.
  //
  // Die Typnamen kommen aus den geliehenen 26.1-Daten und stimmen ab einer
  // Stelle im Alphabet nicht mehr: 26.2 hat einen Entity-Typ dazubekommen,
  // alles danach ist um eins verschoben (ein Spieler meldet sich als
  // "fishing_bobber"). Darum steht die rohe Nummer mit dabei, und Spieler
  // werden ueber die UUID aus der Spielerliste erkannt - die kommt aus einem
  // anderen Paket und ist nicht betroffen.
  async near (rest) {
    const radius = Number(rest) || 8
    const me = bot.entity.position
    const byUuid = {}
    for (const p of Object.values(bot.players)) {
      if (p.uuid) byUuid[p.uuid] = p.username
    }
    const found = Object.values(bot.entities)
      .filter((e) => e !== bot.entity && e.position && e.position.distanceTo(me) <= radius)
      .map((e) => {
        const player = e.uuid && byUuid[e.uuid]
        return {
          typ: player ? 'player' : (e.name || e.type),
          nr: e.entityType,
          ...(player ? { spieler: player } : {}),
          d: +e.position.distanceTo(me).toFixed(2),
          pos: round(e.position)
        }
      })
    out('NEAR', JSON.stringify(found))
  },

  async ping () { out('PONG', 'da') },

  async quit () {
    if (flight) flight.abort = true
    out('QUIT', 'auf Wunsch')
    bot.quit('test vorbei')
    setTimeout(() => process.exit(0), 500)
  }
}

async function handle (line) {
  const trimmed = line.trim()
  if (trimmed === '') return
  const space = trimmed.indexOf(' ')
  const verb = (space < 0 ? trimmed : trimmed.slice(0, space)).toLowerCase()
  const rest = space < 0 ? '' : trimmed.slice(space + 1)
  const fn = handlers[verb]
  if (!fn) { out('FAIL', `unbekannter Befehl: ${verb}`); return }
  try {
    await fn(rest)
  } catch (err) {
    out('FAIL', `${verb}: ${err && err.message}`)
  }
}

// Eine FIFO liefert EOF, sobald der letzte Schreiber sie schliesst. Nach
// jedem echo muss also neu geoeffnet werden.
function listen () {
  let buffer = ''
  const stream = fs.createReadStream(FIFO, { encoding: 'utf8' })
  stream.on('data', (chunk) => {
    buffer += chunk
    let nl
    while ((nl = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, nl)
      buffer = buffer.slice(nl + 1)
      handle(line)
    }
  })
  stream.on('end', () => setTimeout(listen, 50))
  stream.on('error', (err) => { out('ERROR', `fifo: ${err.message}`); setTimeout(listen, 200) })
}
listen()
