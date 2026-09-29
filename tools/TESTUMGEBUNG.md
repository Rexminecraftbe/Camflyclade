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
zählt zurzeit **523** - alle 523 gibt es auch in paper-api. Der Hinweis auf die Abweichung steht also bei jedem Lauf da; ein
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
* **Fliegen im Cam-Modus** geht über `bot.creative.startFlying()` und eine
  eigene Schleife in `fly`, im selben Schritt wie `bot.creative.flyTo()`: ein
  halber Block alle 50 ms. `flyTo` selbst lässt sich nicht abbrechen - hält
  das Plugin die Kamera an einer Grenze fest, kommt es nie an und zieht den
  Bot auch nach dem Timeout weiter zu seinem alten Ziel, gegen jeden späteren
  Flug und jedes `/tp`. Die eigene Schleife hört am Timeout auf.
* **Kein `pkill -f`.** Steht das Muster in der eigenen Kommandozeile, schießt es
  die eigene Shell ab. Das Skript merkt sich stattdessen die Prozessgruppe und
  beendet den Server erst über `stop` auf der Konsole, dann über die Gruppe.
* **`target/` ist nicht eingecheckt**, er steht in `.gitignore`. Was
  `mvn package` dort ablegt, landet also nie im Commit, und das Skript muss
  hinterher nichts zurücksetzen. Das gebaute Jar kopiert es nach
  `artifacts/`, damit auch ein Lauf ohne den Schritt `build` eines findet.
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
  aus `target/classes`. Dort läge die Fassung vom letzten Bauen, womöglich
  eine alte, und ein Lauf ohne den Schritt `build` prüfte das Plugin dann
  gegen eine Konfiguration, in der die neuen Schlüssel fehlen. Das sieht nach
  kaputtem Plugin aus und ist keines. Maven kopiert die Datei ohnehin nur,
  ersetzt wird darin nichts.
* **Der Cam-Modus läuft voreingestellt im Abenteuermodus**, nicht in Kreativ -
  Kreativ steht nur einen einzigen Tick lang da. Der Portalvorgang dauert dort
  deshalb die vollen 80 Ticks; die Abkürzung auf einen Tick gilt nur für Unverwundbare,
  also Kreativ und Zuschauer. Der Portaltest wartet auf jede Reise sechs
  Sekunden. Umstellen lässt sich der Modus mit `camera-mode.gamemode`.
  Die übrigen Abschnitte lassen die Voreinstellung stehen und erkennen den
  laufenden Cam-Modus an mehreren Stellen an `gameMode == "adventure"`. Nur
  `gamemode_checks` stellt den Schlüssel um und setzt ihn in seinem `finally`
  wieder auf `adventure` zurück - liefe ein anderer Abschnitt dazwischen,
  prüfte er gegen den falschen Modus.
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
  sonst träfe das Muster beide Zeilen auf einmal. Genauso bei `actionbar-on`
  und `actionbar-off`, die auch unter `messages` stehen, und bei `enabled`,
  das es in mehreren Abschnitten gibt: Die Schalter setzt der Test mit dem
  Abschnitt `message-settings`.
* **Die Schalter der Action-Bar prüft der Test am Spielmodus.** `camera-on`
  und `camera-off` sind in der ausgelieferten Datei aus; ohne Action-Bar sagt
  das Plugin zu `/cam` also gar nichts. Ob der Cam-Modus trotzdem läuft,
  fragt der Test mit `/execute if entity @s[gamemode=adventure]`.
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
* **Die Rüstung prüft ein eigener Abschnitt**, am Brustpanzer und mit
  `/execute if items entity @s armor.chest`. Das Plugin gibt sie über
  denselben Weg zurück wie das übrige Inventar - `getContents()` hält auch
  die Rüstungsslots -, und diese Probe hält fest, dass das so bleibt.
* **Der Happy Ghast steht mit `NoAI` und `NoGravity` still.** Sonst zöge er
  davon, und die Stelle, an der der Bot aufgesetzt wird, wäre jedes Mal eine
  andere. Er ist vier Blöcke hoch, sein Rücken liegt also vier über seinen
  Füßen.
* **Lava, Wasser und Pulverschnee baut der Test selbst auf**, neben dem
  Testplatz: zwei Becken aus Glas, drei Blöcke tief und oben offen, eines
  voll Wasser und eines voll Lava, dazu einen Würfel aus Pulverschnee und
  eine Decke daraus, eine Lage dick und fünf Blöcke über dem Boden. Die
  Voreinstellung `true` wird nachgesehen und nicht gesetzt, danach stellt
  der Test alle drei Schalter auf `false` und am Ende wieder zurück.
* **Die Decke prüft den Kopf.** Von unten kommt er als Erstes an, und eine
  einzige Lage ist so dünn, dass die Augen darüber herausschauen, noch ehe
  die Füße sie erreichen. Eine Sperre, die nur auf die Füße sieht, ließe die
  Kamera also hindurchschauen - die Gegenprobe mit `true` zeigt, dass die
  Augen dort wirklich darüber ankommen.
* **In der Lava steht der Bot nur mit Feuerschutz.** Ohne ihn verletzte sie
  ihn, und die `cam-safety`-Sperre läge auf allen weiteren Proben. Danach
  löscht ihn das Wasserbecken; erst dann geht der Feuerschutz wieder weg,
  denn er brennt noch eine Weile nach.
* **Die Gegenprobe zum Start in Lava misst am Chat**, nicht am Spielmodus:
  Der Körper steht mit in der Lava, nimmt dort sofort Schaden und beendet
  den Cam-Modus gleich wieder. Die Zeile der Action-Bar kommt vorher.
* **Pulverschnee friert.** Nach sieben Sekunden darin nimmt der Spieler
  Schaden, und der legte die `cam-safety`-Sperre auf die nächsten Proben. Die
  Startproben stellen den Bot deshalb nur kurz hinein und gleich danach
  wieder heraus.
* **Im Wasser sinkt der Bot**, auch fliegend: `startFlying` nimmt nur die
  normale Schwerkraft weg, nicht die im Wasser. Kleine Schritte meldet der
  Server dem Plugin aber erst ab 1/16 Block. Nach der Probe „nicht tiefer“
  steht er deshalb bis zu 1/16 Block unter der Stelle, an die er gesetzt
  wurde, und die Probe lässt dafür Luft.
* **Die Gegenprobe am Happy Ghast wird nach beiden Seiten eingegrenzt.** Nur
  „nicht abgehoben" hieße sie auch dann gut, wenn der Bot glatt durch den
  Ghast hindurchgefallen wäre - und dann sagte die Probe darunter nichts mehr
  darüber, wer ihn angehoben hat.

* **Den Spielmodus fragt `gamemode_checks` beim Server**, mit
  `/execute if entity @s[gamemode=...]`, und nicht bei mineflayer.
  `bot.game.gameMode` ist die Sicht des Clients und läuft hier auf
  geflickten Paketdaten - eine falsche Auskunft liesse genau die Probe
  durchgehen, um die es geht.
* **Die Zuschauer-Probe prüft zweierlei:** dass die Ablehnung im Chat steht
  und dass der Bot danach wirklich noch Zuschauer ist. Die Meldung allein
  sagte nur, dass etwas im Chat stand.
* **Der Mittelklick lässt sich vom Bot nicht schicken.** Das Paket dafür
  kennt er nicht, er fährt auf den Paketdaten von 26.1. Geprüft wird
  deshalb der Griff, der ihn unschädlich macht - der Sweep von
  `CamInventoryGuard` -, und zwar mit `/give`: Der legt dem Spieler etwas in
  dieselben Taschen, die der Mittelklick füllen würde. Geht der Sweep
  kaputt, fällt diese Probe, egal auf welchem Weg etwas hineingekommen
  wäre.
* **Das Blockplatzieren hat keine eigene Probe.** Der Sweep hält die Hände
  leer, also ist nichts da, was sich setzen liesse. Fällt der Sweep, fällt
  die Probe darüber.
* **Die Abbau-Gegenprobe läuft im selben Spielmodus wie die Probe daneben.**
  In Kreativ fängt den Abbau der abgebrochene Linksklick ab, in Überleben
  erst der `BlockBreakEvent`-Handler - in Überleben ist diese Probe also die
  einzige, die ihn überhaupt prüft.
* **Der Bot kennt den Sulfur Cube nicht.** `minecraft-data` 26.1 hat ihn noch
  nicht, und weil er in 26.2 mitten in die Liste der Entitätstypen gerutscht
  ist, heißt er beim Bot „tadpole". Gesucht wird er deshalb wie der eigene
  Körper nur über die Nähe, nie über den Namen.
* **Der Würfel trägt Erde**, gesetzt mit `equipment:{body:...}` im
  `/summon`. Nur mit einem Block darin rollt er, wenn man hineinläuft, und
  fliegt beim Schlag weg; ohne nimmt er den Schlag als Schaden, und den hält
  der Interaktionsschutz ohnehin ab. Ob der Block drin ist, fragt der Test
  mit `/execute if data entity ... equipment.body` nach.
* **Der Schlag hat sein eigenes Paket**, `attack` mit nur der Nummer der
  Entität. `attack_entity` schreibt es selbst, wie `activate_entity` die
  Klicks, und wartet nach dem Blick kurz: Der Blick geht erst mit dem nächsten
  Physik-Tick hinaus, und ohne ihn käme der Schlag mit der alten Richtung an -
  die bestimmt beim Sulfur Cube, wohin er fliegt.
* **Geschlagen wird aus zweieinhalb Blöcken.** Nah genug für den Schlag, zu
  weit, um den Würfel zu berühren. Aus der Nähe hielte ihn schon der Schutz
  gegen das Schieben fest, und die Probe sagte nichts mehr über den Schlag.
  Der Suchradius bleibt bei drei Blöcken, der Körper steht dreieinhalb
  dahinter.
* **Den Widerstand des Plugins liest der Test am Würfel ab**, als Modifier
  `camfly:cam_no_push` auf `minecraft:knockback_resistance`. Er wird mit dem
  Würfel gespeichert; die Probe zum Entladen setzt ihn deshalb mit
  `/attribute` von Hand, wie ihn ein Absturz zurückließe, entlädt den Chunk
  weit weg über `/forceload remove` und lädt ihn wieder.
* **Paper warnt vor Bukkits `EntityKnockbackByEntityEvent`.** Es ist dort zum
  Entfernen vorgemerkt, und jedes Plugin, das darauf hört, steht beim Start
  mit einer Warnung im Log. Das Plugin nimmt auf Paper deshalb Papers eigenes
  Event; landet die Warnung doch im Log, fällt sie in der Zusammenfassung
  auf.
* **Die Wand von `border-mode: barrier` steht nur im Client.** `fly` versetzt
  den Bot ohne Physik und liefe glatt durch sie hindurch, wie ein Client, der
  von der Wand nichts weiß. Der Grenztest läuft deshalb mit `walk`: Vorwärts
  mit der Physik von mineflayer, die an jedem Block anstößt, den der Server
  dem Bot geschickt hat. Zu Fuß schafft er gut vier Blöcke in der Sekunde.
* **barrier und push-back unterscheiden sich am Zurücksetzen.** Stehen bleibt
  die Kamera in beiden Fällen an der Grenze; push-back setzt sie dabei aber
  immer wieder auf die letzte Position zurück. `walk` zählt diese
  Zurücksetzungen über das Ereignis `forcedMove` von mineflayer - bei
  barrier müssen es null sein.
* **`max-distance` steht im Grenztest auf 6**, sonst müsste der Bot hundert
  Blöcke weit laufen. Die Wand steht an den Blöcken, von denen auch nur eine
  Ecke hinter der Grenze liegt; bei 6 ist das am Boden der sechste Block
  östlich des Körpers. Der Test sucht sie trotzdem selbst, mit `block_at` in
  der Sicht des Clients, und fragt am Server nach, dass dort weiter Luft ist.
* **Ob andere die Wand sehen, fragt ein zweiter Bot**, `CamFlyZuschauer`. Er
  kommt nur für diese eine Frage herein und wird von der Konsole aus
  hingestellt, op hat er nicht. Sein Name muss ein anderer sein als der des
  ersten Bots, sonst kickt der eine den anderen.
* **Das verbotene Biom im Grenztest fängt an einer 4er-Grenze an.** Das Spiel
  führt Biome in Würfeln von vier Blöcken; `/fillbiome` füllt ganze Würfel,
  und genau an deren Kante steht dann auch die Wand.
* **Die Proben für die Blöcke der Wand stehen an der Wand des Bioms**, nicht an
  `max-distance`. Die ist bei 6 Blöcken eine enge Kugel und schon zwei Blöcke
  seitlich des Bots weiter innen; die Wand des Bioms ist eben, jede Probe in
  der Ebene `x = biom_x` liegt vorn an ihr. Wasser und Lava liegen im Boden,
  rundum Gras, und weit genug auseinander, dass sie nicht zusammenlaufen; der
  Tropfstein steht auf dem Gras, sonst fiele er ab.
* **Auf die gesperrte Lava fällt der Bot mit `fall`**, der Schwerkraft von
  mineflayer - wie ein echter Client, der über der Lava aufhört zu fliegen.
  Mit `barrier` landet er auf Magma, das nur er hat, ohne zurückgesetzt zu
  werden; mit `push-back` fällt er in die Lava und wird immer wieder
  zurückgesetzt. Die übrigen Proben an Lava, Wasser und Pulverschnee fliegen
  mit `fly` und stoßen an nichts: Für sie hält wie bisher die Prüfung hinter
  der Wand, und deshalb bestehen sie in beiden Modi gleich.
* **Pulverschnee und Tropfstein kennt der Bot unter falschem Namen.** Er liest
  die Blöcke mit den Daten von 26.1, und dort tragen manche Blöcke von 26.2
  eine andere Nummer: Pulverschnee hält er für eine Kupfertruhe. Ob der
  Spieler nach dem Cam-Modus wieder die echten Blöcke sieht, prüft der Test
  deshalb am Vergleich mit dem, was derselbe Client vorher dort sah; was in
  der Welt steht, fragt er beim Server. Die Blöcke der Wand selbst - Barriere,
  Glas, Magma, Schnee, Stein - liest der Bot richtig.

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
schädlichen Effekt und nur die · `actionbar-on` und `actionbar-off` schalten
jeweils nur ihre eigene Zeile ab · ohne `actionbar-off` wird die Zeile zum
Start beim Ende geleert, voreingestellt nicht ·
`message-settings.enabled: false` nimmt die Action-Bar mit · die Startzeilen
stehen voreingestellt im Log · ein offenes Portal trägt den Kamera-Spieler in
den Nether · ein verbotenes Biom dahinter holt ihn zurück · danach lässt
dasselbe Portal ihn gar nicht mehr durch · ein drüben neu gebautes Portal gibt
das gemerkte wieder frei · ein drüben abgebautes ebenso · auf
`border-mode: false` holt ihn auch das verbotene Biom nicht zurück · mit
`forget-changed: false` bleibt der Eintrag stehen · auf `portals.nether: false`
trägt das Portal ihn gar nicht erst hinüber · im Cam-Modus lässt sich kein
Hebel umlegen, kein Block abbauen und keine Druckplatte auslösen · kein Bild
im Rahmen drehen, kein Fenster einer Kistenlore öffnen und kein Boot
besteigen · Gegenprobe: ohne Cam-Modus geht
jedes davon sehr wohl · das Inventar ist im Cam-Modus leer und danach wieder
da · die Rüstung ist im Cam-Modus abgelegt und danach wieder angezogen · der
eigene Körper bleibt anklickbar und beendet damit den Cam-Modus · einen
Sulfur Cube mit einem Block darin kann die Kamera weder wegschieben noch
wegschlagen · Gegenprobe: ohne Cam-Modus geht beides · er steht nur fest,
solange die Kamera ihn berühren könnte, vier Blöcke daneben und nach dem
Cam-Modus ist er wieder frei · ein Würfel, der mit dem Widerstand entladen
wurde, kommt ohne ihn wieder ·
die Kamera wird von einem Happy Ghast abgehoben, ohne Cam-Modus bleibt der
Bot darauf stehen · der Cam-Modus läuft voreingestellt im Abenteuermodus,
auf `survival` und `creative` im eingestellten und auf `keep` in dem, in dem
der Spieler gerade steht · beim Aussteigen kommt er in jedem Fall in seinen
Startmodus zurück · ein unbekannter Wert wird gemeldet und fällt auf
`adventure` zurück · aus dem Zuschauermodus heraus wird `/cam` bei jedem der
vier Werte abgelehnt, und der Spieler bleibt dabei Zuschauer · auch in
Überleben und Kreativ lässt sich im Cam-Modus kein Block abbauen, Gegenprobe:
ohne Cam-Modus geht es in beiden sehr wohl · was im Cam-Modus in die Taschen
kommt, ist im nächsten Tick wieder weg · auf `allow_lava_flight: false` bleibt
die Kamera über der Lava stehen, auf `allow_water_flight: false` über dem
Wasser, auf `allow_powder_snow_flight: false` auf dem Pulverschnee und mit dem
Kopf unter einer Decke daraus, der Cam-Modus läuft dabei weiter und die
Meldung kommt · wer schon im Wasser ist, kommt heraus, aber nicht tiefer hinein
· in Lava, Wasser und Pulverschnee startet `/cam` dann nicht, und die Ablehnung
sagt warum · Gegenprobe: voreingestellt geht all das, und die Lava bleibt auch
dicht vor der Kamera Lava · gesperrt und mit `border-mode: barrier` landet die
Kamera auf der Lava wie auf einem Block, ohne zurückgesetzt zu werden, und die
Wand zeigt Lava als Magma, Wasser als blaues Glas und Pulverschnee als Schnee
· mit `push-back` bleibt die Lava Lava, und wer hineinfällt, wird
zurückgesetzt · voreingestellt steht
`border-mode: barrier` mit `border-block: barrier`, `border-block-water:
blue_stained_glass`, `border-block-lava: magma_block`,
`border-block-powder-snow: snow_block` und `border-radius: 5` da · die Kamera
bleibt dann an `max-distance` und an einem verbotenen Biom stehen, ohne je
zurückgesetzt zu werden, und die Meldung kommt · die Wand steht nur im Client
des Kamera-Spielers: der Server hat dort Luft, ein zweiter Spieler ebenso, und
nach dem Cam-Modus ist sie auch bei ihm wieder weg · Wasser, Lava und
Pulverschnee zeigt sie als blaues Glas, Magma und Schnee, eine Stufe und einen
Tropfstein ersetzt sie, einen ganzen Steinblock lässt sie stehen - in der Welt
bleibt alles, wie es war, und nach dem Cam-Modus sieht der Spieler es auch
wieder · `border-block-water` nimmt einen anderen Block, und mit `water` steht
dort `border-block` · auf
`push-back` kommt die Kamera auch nicht weiter, wird dabei aber zurückgesetzt ·
auf `false`, mit `border-block: air` und mit `border-radius: 0` gibt es keine
Grenze · ein unbekannter Block und ein zu großer Radius werden gemeldet, und
die Wand steht mit dem Ersatz · Server-Log ohne Fehler des Plugins.

Am Ende steht eine Zusammenfassung im Terminal, dazu `ergebnis.json` im
Arbeitsordner.
