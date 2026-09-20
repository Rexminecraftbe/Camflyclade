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
zählt zurzeit **466** - alle 466 gibt es auch in paper-api. Der Hinweis auf die Abweichung steht also bei jedem Lauf da; ein
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
* **`start-with-effects` stellt der Test selbst um**, in der
  Konfigurationsdatei des Servers und mit `cam reload` von der Konsole; am
  Ende steht wieder `positive` da. Die Voreinstellung wird nicht gesetzt,
  sondern nachgesehen - so fällt auf, wenn in der ausgelieferten Datei etwas
  anderes steht.
* **Als schädlicher Effekt dient Langsamkeit, nicht Gift.** Gift macht
  Schaden, und dann stünde die `cam-safety`-Sperre vor der Ablehnung, um die
  es geht.
* **Die Wolke eines verweilenden Tranks räumt der Test weg**, bevor er `/cam`
  startet. Sie legt die Langsamkeit sonst sofort wieder auf, und mit
  `start-with-effects: positive` käme der Bot damit nicht mehr in den
  Cam-Modus.
* **Rechte des Bots:** `/cam` darf er ohne op, das ist Standardrecht. Für
  `/fillbiome` und `/data` wird er im Testlauf zum Operator gemacht - erst
  danach, damit das Standardrecht vorher wirklich geprüft wird.
* **Die Testkonfiguration kommt aus `src/main/resources/config.yml`**, nicht
  aus `target/classes`. Dort läge nach jedem Lauf wieder die eingecheckte alte
  Fassung - `restore_target` setzt den Ordner ja zurück -, und ein Lauf ohne
  den Schritt `build` prüfte das Plugin dann gegen eine Konfiguration, in der
  die neuen Schlüssel fehlen. Das sieht nach kaputtem Plugin aus und ist
  keines. Platzhalter ersetzt Maven ohnehin nur in `plugin.yml`.
* **Der Cam-Modus läuft voreingestellt im Abenteuermodus**, nicht in Kreativ -
  Kreativ steht nur einen einzigen Tick lang da. Der Portalvorgang dauert dort
  deshalb die vollen 80 Ticks; die Abkürzung auf einen Tick gilt nur für Unverwundbare,
  also Kreativ und Zuschauer. Der Portaltest wartet auf jede Reise sechs
  Sekunden. Umstellen lässt sich der Modus mit `camera-mode.gamemode`. Die
  Tests lassen die Voreinstellung stehen und erkennen den laufenden Cam-Modus
  an mehreren Stellen an `gameMode == "adventure"` - wer den Schlüssel im
  Test umstellt, muss diese Proben mit umstellen.
* **Nach jedem Anlauf am Portal liegt eine Portalsperre von 100 Ticks auf dem
  Spieler**, die das Plugin selbst setzt. Wer sie nicht abwartet, steht beim
  nächsten Anlauf in einem Portal, das gar nichts mehr tut - und solange er
  darin stehen bleibt, läuft sie nicht einmal ab, sie wird jeden Tick neu
  aufgezogen. Der Test verlässt deshalb nach jedem Anlauf den Cam-Modus, das
  setzt ihn an seinen Körper und damit aus dem Portal heraus, und wartet.
* **`cam reload` wirft jeden Kamera-Spieler aus dem Cam-Modus** -
  `reloadPlugin` ruft für jeden `exitCameraMode` auf. Nach jeder Umstellung
  der Konfiguration muss der Bot also erst wieder hinein, sonst geht er ganz
  regulär durch das Portal und die Probe sagt nichts über das Plugin aus.
  Jeder Anlauf des Portaltests stellt den Cam-Modus deshalb selbst sicher.
* **`cam reload` leert das Gemerkte über gesperrte Portale.** Zwischen dem
  Anlauf, der ein Portal sperrt, und dem, der die Sperre prüft, darf deshalb
  nichts an der Konfiguration gedreht werden: Jede Umstellung braucht danach
  erst wieder einen Anlauf, der die Sperre neu anlegt.
* **Wo ein Portal drüben herauskommt, steht erst nach der Reise fest.** Der
  Test geht deshalb einmal hinüber, ehe er drüben etwas umbaut - vorher weiß
  er gar nicht, wo er das Biom setzen müsste.
* **Die Testwelt bleibt zwischen zwei Läufen stehen.** Was ein Lauf drüben
  gesetzt hat, findet der nächste wieder vor - ein abgebrochener Lauf kann
  sogar den Bot im Nether zurücklassen. Der Portaltest setzt das Biom drüben
  deshalb vor der ersten Reise selbst, holt den Bot nötigenfalls heim und
  räumt am Ende wieder auf. Wer ganz von vorn anfangen will, löscht
  `~/camfly-testenv/server/world`; der Server legt sie neu an.
* **`allow-flight=true` steht in den Server-Einstellungen.** Sonst wirft der
  Anticheat den Bot mit „kicked for floating too long" hinaus, sobald er
  zwischen zwei Anläufen ein paar Sekunden ohne Cam-Modus in der Luft steht.
* **Wo eine Reise durch ein Portal herauskommt, sucht sich der Server aus:**
  das nächstgelegene Portal, und wo keines steht, baut er eines. Der Test
  verlässt sich deshalb nicht darauf, zweimal an derselben Ecke zu landen -
  landet der Bot in einem erlaubten Biom, nimmt er die Stelle in das verbotene
  hinein und versucht es noch einmal.
* **Die Chunks drüben hält der Test mit `/forceload` fest.** Ohne einen
  Spieler im Nether fallen sie weg, und `/fill` und `/fillbiome` brauchen sie
  geladen.
* **`nether` steht zweimal in der Konfiguration**, unter `portals` und unter
  `cam-area.dimensions`. `set_option` nimmt dafür einen Abschnitt entgegen,
  sonst träfe das Muster beide Zeilen auf einmal.
* **Das Testportal entsteht aus `/fill` und einem `/setblock ... fire`** im
  ausgehöhlten Rahmen. Ob daraus wirklich ein Portal geworden ist, sieht der
  Test mit `/execute if block ... run say` nach - so beantwortet der Server
  auch die Frage, in welcher Welt der Bot gerade steht.
* **Jede Interaktionsprobe steht zweimal da**, einmal ohne Cam-Modus und
  einmal darin. Ohne die Gegenprobe sagte der Abschnitt nur, dass sich nichts
  gerührt hat - und das sagt er auch dann, wenn der Klick des Bots gar nicht
  erst ankommt. Fällt eine Gegenprobe durch, ist die Probe daneben nichts
  wert, und genau das steht dann auch in der Zusammenfassung.
* **Der Bot wird per `/tp` neben sein Ziel gestellt**, nicht hingeflogen. Wo
  er steht, entscheidet darüber, ob sein Klick überhaupt in Reichweite ist,
  und ein Teleport landet zuverlässig an derselben Stelle.
* **Abgebaut wird eine Blume, kein Stein.** Der Bot ist außerhalb des
  Cam-Modus im Überlebensmodus und schlägt Stein von Hand minutenlang; die
  Blume geht mit einem Schlag.
* **`activateEntity` und `activateEntityAt` schreibt das Skript selbst.**
  Beide drehen in mineflayer den Kopf weich (`lookAt` ohne `force`) und warten
  dabei auf den Physik-Tick - und dieses Warten hat den Bot schon hängen
  lassen, mit `TimeoutError: Keine Antwort auf activate_entity`. Der Abschnitt
  sieht deshalb einmal hart hin und schreibt das `use_entity`-Paket danach
  direkt; sein Inhalt ist derselbe. Jeder Klick des Bots steht außerdem in
  einem `Promise.race` mit hartem Timeout, damit eine Frage immer eine Antwort
  bekommt.
* **Ein Rechtsklick geht zweimal hinaus.** Der echte Client schickt erst die
  „interact at"-Fassung mit dem Trefferpunkt und dann die schlichte, und
  welche von beiden wirkt, hängt an der Entität: Der Rüstungsständer hängt an
  der ersten, das Boot an der zweiten. `activate_entity` schickt deshalb
  immer beide. Mit nur einer davon blieben im ersten Lauf genau diese zwei
  Gegenproben hängen, während Item-Rahmen und Kistenlore längst gingen.
* **Den fremden Rüstungsständer prüft der Abschnitt nicht.** Nicht, weil das
  Plugin ihn nicht abwiese, sondern weil der Bot ihn gar nicht erst ausziehen
  kann - auch ohne Cam-Modus nicht. Vanilla wickelt das Abnehmen allein über
  `interactAt` ab, und der Trefferpunkt dieses Pakets übersteht die geflickten
  Paketdaten nicht. Nachgemessen am Server-Log: Der Ständer trug Stiefel und
  Stock vor dem Klick und danach immer noch, in beiden Durchgängen und mit
  beiden Klickfassungen. Eine Probe, deren Gegenprobe nie durchkommt, sagt
  über das Plugin nichts und stünde nur bei jedem Lauf rot da. Was sie gesagt
  hätte, sagen zwei andere mit: Der Item-Rahmen zeigt, dass ein Rechtsklick
  auf eine fremde Entität abgewiesen wird, und der Klick auf den eigenen
  Körper zeigt, dass ein Klick auf einen Rüstungsständer beim Plugin ankommt -
  der Körper ist selbst einer.
* **Ins Boot steigt der Bot über `/ride`, nicht über den Klick.** Der Klick
  kommt an, das Boot nimmt ihn nur nicht an - an dieser einen Stelle reichen
  die geflickten Paketdaten nicht. `/ride` geht im Server denselben Weg
  (`startRiding`, und damit `EntityMountEvent` und `VehicleEnterEvent`), nur
  ohne Client dazwischen, und genau die beiden fängt das Plugin ab. Gefragt
  wird danach mit `/execute on vehicle`.
* **Die Hand des Bots muss leer sein.** Mit etwas darin legt der Rechtsklick
  auf einen Rüstungsständer das Mitgebrachte an, statt etwas abzunehmen - und
  ist das Mitgebrachte keine Rüstung, passiert gar nichts. Der Abschnitt räumt
  dem Bot deshalb vorher die Taschen aus; aus den Tests davor bleibt sonst
  etwas darin liegen.
* **Die nächste Entität gewinnt.** Der Kamera-Körper des Bots ist selbst ein
  Rüstungsständer und kann dieselbe Art haben wie das Testobjekt. Der Bot
  stellt sich deshalb direkt neben sein Ziel, und der Suchradius bleibt
  klein genug, dass der eigene Körper nicht hineinfällt.
* **Was herumliegt, wird mit weggeräumt.** Der Abbau im Durchgang ohne
  Cam-Modus lässt eine Blume fallen, und die zählte beim Klick auf den
  eigenen Körper als nächste Entität mit.
* **Alles Gesetzte trägt die Marke `camflytest`** und wird am Ende wieder
  weggenommen, Blöcke mit `/fill ... air`. Die Testwelt bleibt zwischen zwei
  Läufen stehen; ohne die Marke fände der nächste Lauf die Entitäten eines
  abgebrochenen wieder vor und klickte auf die alten.
* **NBT-Fragen an den Server müssen ihre Klammern verdoppeln.** `server_says`
  schickt das Kommando durch `format()`, und `{ItemRotation:0b}` wäre dort
  ein Platzhalter. Dafür gibt es `nbt_frage`.
* **Der Klick auf den eigenen Körper wird am Spielmodus gemessen**, nicht an
  einer Meldung: `adventure` heißt im Cam-Modus, alles andere heißt beendet.
  Die Absage am fremden Körper gibt es nicht mehr, da wäre nichts zu hören.
* **Das leere Inventar prüft der Test mit einem `/give` davor.** Ein frisch
  gespawnter Bot hat ohnehin nichts in der Hand, und die Probe sagte ohne den
  Gegenstand gar nichts. Sie gehört zum Blockschutz: Ohne Gegenstand gibt es
  auch keinen mit `CanPlaceOn` oder `CanDestroy`, mit dem sich im
  Abenteuermodus doch bauen ließe.
* **Der Happy Ghast steht mit `NoAI` und `NoGravity` still.** Sonst zöge er
  davon, und die Stelle, an der der Bot aufgesetzt wird, wäre jedes Mal eine
  andere. Er ist vier Blöcke hoch, sein Rücken liegt also vier über seinen
  Füßen.
* **Die Gegenprobe am Happy Ghast wird nach beiden Seiten eingegrenzt.** Nur
  „nicht abgehoben" hieße sie auch dann gut, wenn der Bot glatt durch den
  Ghast hindurchgefallen wäre - und dann sagte die Probe darunter nichts mehr
  darüber, wer ihn angehoben hat.

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
Cam-Modus · die Meldung dazu nennt den Effekt, an dem es lag · `/cam` startet
mit einem positiven und einem neutralen Effekt, mit einem schädlichen nicht ·
auf `false` sperrt jeder Effekt, auf `true` keiner · die Ablehnung nennt jeden
schädlichen Effekt und nur die · ein offenes Portal trägt den Kamera-Spieler in
den Nether · ein verbotenes Biom dahinter holt ihn zurück · danach lässt
dasselbe Portal ihn gar nicht mehr durch · ein drüben neu gebautes Portal gibt
das gemerkte wieder frei · ein drüben abgebautes ebenso · mit
`forget-changed: false` bleibt der Eintrag stehen · auf `portals.nether: false`
trägt das Portal ihn gar nicht erst hinüber · im Cam-Modus lässt sich kein
Hebel umlegen, kein Block abbauen und keine Druckplatte auslösen · kein Bild
im Rahmen drehen, kein Fenster einer Kistenlore öffnen und kein Boot
besteigen · Gegenprobe: ohne Cam-Modus geht
jedes davon sehr wohl · das Inventar ist im Cam-Modus leer und danach wieder
da · der eigene Körper bleibt anklickbar und beendet damit den Cam-Modus ·
die Kamera wird von einem Happy Ghast abgehoben, ohne Cam-Modus bleibt der
Bot darauf stehen · Server-Log ohne Fehler des Plugins.

Am Ende steht eine Zusammenfassung im Terminal, dazu `ergebnis.json` im
Arbeitsordner.
