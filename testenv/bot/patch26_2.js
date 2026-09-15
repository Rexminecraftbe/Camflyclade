// Macht mineflayer fuer Minecraft 26.2 brauchbar.
//
// minecraft-data hat Paketdaten nur bis 26.1 (Protokoll 775), der Testserver
// spricht 26.2 (776). Die Pakete selbst haben sich nicht geaendert, also
// leihen wir uns die Daten von 26.1 und tragen 26.2 ueberall dort nach, wo
// die Bibliotheken ihre Liste der unterstuetzten Versionen fuehren.
//
// Dieses Modul MUSS vor dem require von mineflayer laufen: loader.js liest
// latestSupportedVersion genau einmal beim Laden aus.

const FALLBACK = '26.1'
const TARGET = '26.2'
const PROTOCOL = 776

// 1. Die rohen Paketdaten von 26.1 auch unter 26.2 anbieten.
const data = require('minecraft-data/data.js')
if (!data.pc[TARGET]) {
  data.pc[TARGET] = data.pc[FALLBACK]
}

// 2. 26.2 in die Versionsliste von minecraft-data eintragen.
const md = require('minecraft-data')
if (!md.supportedVersions.pc.includes(TARGET)) {
  md.supportedVersions.pc.push(TARGET)
}

// 3. Der geliehene Datensatz traegt noch die Nummern von 26.1. Ohne die
//    Korrektur schickt der Bot beim Handshake Protokoll 775 und der Server
//    weist ihn ab.
const indexed = md(TARGET)
indexed.version.version = PROTOCOL
indexed.version.minecraftVersion = TARGET

// 4. minecraft-protocol fuehrt seine eigene Liste.
const protocolVersions = require('minecraft-protocol/src/version.js')
if (!protocolVersions.supportedVersions.includes(TARGET)) {
  protocolVersions.supportedVersions.push(TARGET)
}

// 5. mineflayer nimmt latestSupportedVersion, wenn beim Verbinden keine
//    Version angegeben ist - und liest den Wert beim Laden von loader.js
//    genau einmal aus.
const mineflayerVersion = require('mineflayer/lib/version.js')
mineflayerVersion.latestSupportedVersion = TARGET

module.exports = { version: TARGET, protocol: PROTOCOL }
