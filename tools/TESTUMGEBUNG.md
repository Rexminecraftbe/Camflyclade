# CamFly-Testumgebung

Ein Aufruf baut alles auf, was zum Testen des Plugins gebraucht wird, und fährt
die Tests auf einem echten Paper-Server:

```bash
python3 tools/camfly_testenv.py
```

Beim ersten Mal dauert es ein paar Minuten, weil JDK, paper-api, der Server und
mineflayer geholt werden. Alles davon landet unter `~/camfly-testenv` und wird
danach wiederverwendet.

## Nützliche Aufrufe

```bash
python3 tools/camfly_testenv.py --steps build,crosscheck,apicheck  # nur bauen und prüfen
python3 tools/camfly_testenv.py --skip jdk,paperapi                # Schritte auslassen
python3 tools/camfly_testenv.py --keep-running                     # Server bleibt oben
python3 tools/camfly_testenv.py --stop                             # laufenden Server beenden
python3 tools/camfly_testenv.py --workdir /pfad/woanders           # anderer Arbeitsordner
```

Läuft der Server weiter, gehen Konsolenbefehle über die FIFO:

```bash
echo "say hallo" > ~/camfly-testenv/server/console.fifo
```

## Die acht Schritte

| Schritt      | Was passiert |
|--------------|--------------|
| `jdk`        | JDK 25 von Adoptium holen. Das System hat meist nur 21, `paper-api` hat aber Klassendateien der Version 69. |
| `build`      | `mvn -B clean package` gegen `spigot-api`. Maven holt die API selbst. |
| `paperapi`   | `paper-api` und die zwölf Abhängigkeiten, die zum Übersetzen nötig sind. |
| `crosscheck` | Denselben Quelltext noch einmal mit `javac` gegen `paper-api` übersetzen. |
| `apicheck`   | Jeden Aufruf auf `org/bukkit`, `net/md_5`, `io/papermc` aus `target/classes` gegen `paper-api` auflösen. |
| `server`     | Paper-Server holen, einrichten, starten, Konsole an eine FIFO hängen. |
| `bot`        | `mineflayer` holen und auf Protokoll 26.2 flicken. |
| `tests`      | Der Bot spielt die Testfälle im laufenden Server durch. |

## Warum gegen beide APIs geprüft wird

Das `pom` baut gegen `spigot-api`, der Server läuft auf Paper. Die beiden APIs
sind nicht deckungsgleich, und das hat schon dreimal einen `NoSuchMethodError`
im Spiel verursacht: `Biome.getKeyOrNull`, `ItemMeta.getDamageResistances`,
`Player.getPlayerProfile`.

Paper hängt `Biome` und `Structure` nur an `Keyed`, nicht an `RegistryAware`:
`getKey()` gibt es auf beiden, `getKeyOrNull()` und `getKeyOrThrow()` nur auf
Spigot.

Der `apicheck`-Schritt zerlegt dazu jede Klasse mit `javap -p -c`, zieht die
Aufrufe heraus und löst sie per Reflection gegen `paper-api` auf - samt
Oberklassen, allen Interfaces und, bei Interfaces, `java.lang.Object`.

Sollmarke im Skript sind die **348 Aufrufe** aus der Anleitung. Dieser Prüfer
zählt zurzeit **435** (370 Methoden und 65 Feldzugriffe) - alle 435 gibt es auch
in paper-api. Der Hinweis auf die Abweichung steht also bei jedem Lauf da; ein
Fehler ist er nicht, nur ein Zeichen, dass sich am Plugin etwas geändert hat.
Was zählt, ist die Zeile darunter: **fehlen: 0**.

## Fallen, die das Skript schon kennt

* **`difficulty peaceful`** wird gesetzt, sonst erschlägt ein Zombie den Bot und
  das Plugin verweigert danach `/cam` wegen der `cam-safety`-Sperre.
* **`camera-particles.particles-per-tick: 0`** wird in die Testkonfiguration
  geschrieben. Sonst stirbt jeder Bot in Sichtweite eines Cam-Spielers am
  Partikel-Paket: `minecraft-data` 26.1 kennt die 26.2-IDs nicht.
* **Der Bot-Name steht fest im Skript.** Über `BOT_NAME=... nohup ...` geht die
  Variable verloren, der zweite Bot joint als „TestBot" und kickt den ersten mit
  `duplicate_login`.
* **`/fillbiome`** verträgt höchstens 32768 Blöcke, und y muss in die Welt
  passen. Der Boden der Flachwelt liegt bei -64, `~-8` bei y=-60 geht schief;
  das Skript rechnet die Grenzen deshalb aus und deckelt sie.
* **`bot.entity.position` ist die Sicht des Clients** und läuft optimistisch
  voraus. Die Wahrheit holt das Skript mit `/data get entity @s Pos`.
* **Fliegen im Cam-Modus** geht über `bot.creative.startFlying()` und
  `bot.creative.flyTo()`, immer mit hartem Timeout drumherum, sonst hängt es an
  der Sperre fest.
* **Kein `pkill -f`.** Steht das Muster in der eigenen Kommandozeile, schießt es
  die eigene Shell ab. Das Skript merkt sich stattdessen die Prozessgruppe und
  beendet den Server erst über `stop` auf der Konsole, dann über die Gruppe.
* **`target/` ist im Repo eingecheckt.** Nach jedem `mvn package` setzt das
  Skript den Ordner mit `git restore --source=HEAD --worktree target/` und
  `git clean -fd target/` zurück, damit nur `src/` im Commit landet.
  (`--no-restore` lässt das bleiben.)
* **Ausgabeordner werden geleert**, bevor neu übersetzt wird - sonst verdeckt
  eine alte Klassenkopie die frisch gebaute.
* **Hunger braucht eine Schwierigkeit über `peaceful`.** Dort nimmt der
  Server vom Balken gar nichts weg, der Hungertest liefe ins Leere. Er stellt
  deshalb für sich auf `easy` und danach wieder zurück; Monster kommen dabei
  keine, das Spawnen ist ohnehin aus.
* **Die Sättigung liegt auf dem Testserver bei knapp 20**, nicht bei den 5
  eines frisch gespawnten Spielers - `peaceful` füllt sie die ganze Zeit mit
  auf. Ein Hungereffekt muss sie erst aufbrauchen, ehe der Balken selbst
  fällt: zwei Sekunden auf Stufe 255 reichten dafür nicht, fünf reichen.
* **Der Hunger wird serverseitig gelesen**, über `/data get entity @s
  foodLevel` und die beiden Nachbarwerte. Der Client kennt nur den Balken,
  Sättigung und Erschöpfung stehen allein auf dem Server.
* **Zwei „partial packet"-Warnungen beim Login** sind harmlos.
* **Rechte des Bots:** `/cam` darf er ohne op, das ist Standardrecht. Für
  `/fillbiome` und `/data` wird er im Testlauf zum Operator gemacht - erst
  danach, damit das Standardrecht vorher wirklich geprüft wird.

## Was die Tests abdecken

Plugin geladen · Bot verbindet sich · `/cam` ohne op · Körper wird gesetzt
(Rüstungsständer und Mannequin) · `/cam` beendet sich wieder · Körper wird
eingesammelt · serverseitige Position lesbar · Fliegen im Cam-Modus bewegt den
Spieler · nach dem Cam-Modus steht der Spieler wieder am Körper · `/cam reload`
von der Konsole · `/cam reload` vom Spieler · verbotenes Biom sperrt `/cam` ·
im erlaubten Biom geht `/cam` wieder · der Hungerbalken bleibt im Cam-Modus
stehen, samt Sättigung und Erschöpfung · nach dem Cam-Modus steht der Hunger
wieder wie vorher · Gegenprobe: ohne Cam-Modus zehrt derselbe Effekt sehr wohl ·
Server-Log ohne Fehler des Plugins.

Am Ende steht eine Zusammenfassung im Terminal, dazu `ergebnis.json` im
Arbeitsordner.
