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
zählt zurzeit **444** (377 Methoden und 67 Feldzugriffe) - alle 444 gibt es auch
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
* **Der Heiltest läuft zweimal**, weil von selbst auf zwei Wegen Leben
  nachwächst und das Plugin beide abfangen muss: in `peaceful` jede Sekunde
  ein halbes Herz (Grund `REGEN`), in `easy` schnell aus der Sättigung
  (`SATIATED`) und danach langsam über den vollen Balken. Geprüft wird auf
  Gleichstand, ein einziges halbes Herz reicht also zum Durchfall - der
  Durchgang in `easy` braucht deshalb keinen großen Abstand, nur Sättigung
  und einen vollen Balken. Die holt er sich vorher mit dem
  Sättigungseffekt, der nur greift, solange der Balken nicht voll ist.
* **Erst verletzen, dann warten.** `/damage` setzt die `cam-safety`-Sperre in
  Gang, fünf Sekunden lang geht danach kein `/cam`. Der Heiltest legt seine
  Gegenprobe genau in diese Wartezeit. Wie viel Schaden, das rechnet er aus
  dem aus, was der Bot noch hat: ein fester Wert erschlägt ihn, sobald ein
  Durchgang auf den anderen folgt.
* **Zwei „partial packet"-Warnungen beim Login** sind harmlos.
* **Tränke setzt der Test mit `/summon` ab**, einen Block über dem Ziel: der
  Trank fällt, zerschellt und wirkt vier Blöcke weit. Die Entitätstypen heißen
  `minecraft:splash_potion` und `minecraft:lingering_potion`;
  `minecraft:potion` gibt es nicht mehr. Genommen wird Langsamkeit - sie tut
  niemandem weh und legt damit die `cam-safety`-Sperre nicht an, die jeder
  Schaden auslösen würde.
* **Die Wolke eines verweilenden Tranks braucht einen Moment** und fragt dann
  etwa jede Sekunde neu nach, wer in ihr steht. Der Test wartet deshalb nach
  jedem Wurf drei Sekunden - das deckt beim Splash die sofortige Wirkung und
  bei der Wolke gleich mehrere Runden ab.
* **Beim verweilenden Trank auf den Körper wird nur geprüft, dass der
  Cam-Modus endet.** Ob die Wirkung danach am Spieler hängt, sagt nichts
  mehr: Er steht nach dem Ende wieder bei seinem Körper und damit mitten in
  der Wolke, die ihn dann ganz regulär erwischt.
* **Der Rüstungsständer nimmt von Tränken nichts an**, das Mannequin in ihm
  sehr wohl. Über das läuft der Treffer auf den Körper, bei beiden
  Körpertypen.
* **Zum Trankstest fliegt der Bot zwölf Blöcke weg.** Steht er bei seinem
  Körper, benetzt ein Trank beide auf einmal, und die Probe sagt nicht mehr,
  wen von beiden er getroffen hat.
* **Den Treffer auf den Körper prüft der Test am Spielmodus**, nicht an der
  Meldung: `adventure` heißt im Cam-Modus, alles andere heißt beendet. Die
  Meldung `body-got-effect` wird zusätzlich geprüft, samt dem Effekt, den sie
  benennen soll.
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
ein verletzter Spieler heilt im Cam-Modus nicht nach, weder in `peaceful` noch
aus der Sättigung heraus · Gegenprobe: ohne Cam-Modus heilt er in beiden Fällen
sehr wohl · ein geworfener Trank geht im Cam-Modus am Spieler vorbei, der
Splash-Trank wie der verweilende · Gegenprobe: ohne Cam-Modus wirken beide auf
ihn · der Körper wird von beiden weiterhin getroffen und beendet damit den
Cam-Modus · die Meldung dazu nennt den Effekt, an dem es lag · Server-Log ohne
Fehler des Plugins.

Am Ende steht eine Zusammenfassung im Terminal, dazu `ergebnis.json` im
Arbeitsordner.
