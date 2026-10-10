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
python3 tools/camfly_testenv.py --steps names                      # nach einem Update: fehlen Namen?
python3 tools/camfly_testenv.py --skip jdk,paperapi                # Schritte auslassen
python3 tools/camfly_testenv.py --keep-running                     # Server bleibt oben
python3 tools/camfly_testenv.py --stop                             # laufenden Server beenden
python3 tools/camfly_testenv.py --workdir /pfad/woanders           # anderer Arbeitsordner
```

Läuft der Server weiter, gehen Konsolenbefehle über die FIFO:

```bash
echo "say hallo" > ~/camfly-testenv/server/console.fifo
```

## Die neun Schritte

| Schritt      | Was passiert |
|--------------|--------------|
| `jdk`        | JDK 25 von Adoptium holen. Das System hat meist nur 21, `paper-api` hat aber Klassendateien der Version 69. |
| `build`      | `mvn -B clean package` gegen `spigot-api`. Maven holt die API selbst. |
| `paperapi`   | `paper-api` und die zwölf Abhängigkeiten, die zum Übersetzen nötig sind. |
| `crosscheck` | Denselben Quelltext noch einmal mit `javac` gegen `paper-api` übersetzen. |
| `apicheck`   | Jeden Aufruf auf `org/bukkit`, `net/md_5`, `io/papermc` aus `target/classes` gegen `paper-api` auflösen. |
| `names`      | Die Namenslisten der Sprachdatei gegen die Daten des Spiels prüfen, siehe unten. |
| `server`     | Paper-Server holen, einrichten, starten, Konsole an eine FIFO hängen. |
| `bot`        | `mineflayer` holen, auf Protokoll 26.2 flicken und seine Kollision wie im echten Client rechnen lassen. |
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
zählt zurzeit **664** - alle 664 gibt es auch in paper-api. Der Hinweis auf die Abweichung steht also bei jedem Lauf da; ein
Fehler ist er nicht, nur ein Zeichen, dass sich am Plugin etwas geändert hat.
Was zählt, ist die Zeile darunter: **fehlen: 0**.

## Die Namen nach einem Update

Was die Meldungen an Dingen des Spiels nennen, nennen sie mit den Namen aus
`lang/en.yml`: Schadensarten, Mobs, Effekte, Biome, Strukturen, Dimensionen
und Portale, in den Listen `damage-names`, `mob-names`, `effect-names`,
`biome-names`, `structure-names`, `dimension-names` und `portal-names`.
Bringt eine neue Version etwas dazu, sagt das Plugin selbst nichts - es nennt
etwas ohne Namen bei seinem Schlüssel und einen Mob so, wie das Spiel ihn
nennt. Der Schritt `names` gleicht die Listen deshalb mit den Daten des
Spiels ab, aus dem Jar des Servers: Schadensarten, Biome und Strukturen aus
`data/minecraft`, Effekte und die Mobs mit Spawn-Ei aus seiner `en_us.json`.
Dimensionen und Portale prüft er nicht, deren Schlüssel gibt das Plugin vor.
Er braucht keinen laufenden Server und ist in ein paar Sekunden durch:

```bash
python3 tools/camfly_testenv.py --steps names
```

Fällt er durch, steht dabei, worum es geht:

* **„es fehlen“**: eine Schadensart, ein Effekt, ein Biom oder eine Struktur
  ohne Namen. Sie gehören mit einem Namen in die Liste, die dabeisteht.
* **„neu“**: ein Mob mit Spawn-Ei, der in keiner Liste steht. Greift er an,
  gehört er mit einem Namen unter `mob-names`, sonst in `MOBS_OHNE_ANGRIFF`
  im Skript.
* **„gibt es nicht“**: ein Eintrag in einer der Listen oder in
  `MOBS_OHNE_ANGRIFF`, den es im Spiel nicht mehr gibt - umbenannt, entfernt
  oder vertippt.

Mobs ohne Spawn-Ei, etwa den Illusioner, sieht die Prüfung nicht. Eine eigene
Übersetzung wie `lang/de.yml` prüft sie auch nicht, sie liegt nicht im
Repository: Was ihr fehlt, kommt aus der englischen Datei.

## Fallen, die das Skript schon kennt

* **Die Daten des Spiels stecken im Download von Paper nur als Patch.**
  Lesbar sind sie erst im Jar, das Paperclip beim ersten Start daraus
  zusammensetzt, `server/versions/26.2/paper-26.2.jar`. Fehlt es, setzt der
  Schritt `names` es mit `-Dpaperclip.patchonly=true` zusammen, ohne einen
  Server zu starten; der Server findet es danach fertig vor.
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
* **Zum Trankstest stellt der Test den Bot zwölf Blöcke weg**, per `/tp` und
  nicht im Flug. Steht er bei seinem Körper, benetzt ein Trank beide auf
  einmal, und die Probe sagt nicht mehr, wen von beiden er getroffen hat. Ein
  Flug blieb dabei schon am Boden hängen: Der Bot stand auf einem
  Trampelpfad, 1/16 tiefer als das Gras daneben, stieß beim waagerechten Flug
  mit den Füßen an den ersten Grasblock, und der Server setzte ihn bei jedem
  Schritt zurück („moved wrongly"). Er blieb neben seinem Körper stehen, und
  sechs Proben fielen durch, ohne dass das Plugin etwas falsch gemacht hätte.
  Ob er weit genug weg steht, prüft der Test deshalb, bevor er wirft.
  Gestellt wird er auf die Höhe eines ganzen Blocks, nicht höher: Die Wolke
  eines verweilenden Tranks liegt nur einen halben Block hoch über dem Boden,
  und einen Block darüber geht sie auch ohne das Plugin an ihm vorbei.
* **Den Treffer auf den Körper prüft der Test am Spielmodus**, nicht an der
  Meldung: `adventure` heißt im Cam-Modus, alles andere heißt beendet. Die
  Meldung `body-got-effect` wird zusätzlich geprüft, samt dem Effekt, den sie
  benennen soll.
* **Was den Körper getroffen hat, prüft der Test an der Meldung.** Sie kommt
  erst, wenn der Cam-Modus schon vorbei ist, und sagt damit beides. Der Amboss
  fällt dabei wirklich auf den Körper, Kaktus und Golem kommen über `/damage`:
  Ob ein echter Kaktus den Körper piekst, hängt daran, wo er auf den Bruchteil
  eines Blocks genau steht. Das Plugin liest ohnehin nur die Schadensart und
  wer hinter dem Treffer steht, und beides setzt `/damage` genauso.
* **Der Amboss fällt mit `CancelDrop:1b`.** Sonst bliebe er als Block dort
  liegen, wo der Körper stand - genau dort, wohin der Bot zurückkommt, sobald
  der Cam-Modus endet.
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
  ersetzt wird darin nichts. Die Sprachdatei `lang/en.yml` kommt genauso aus
  `src/main/resources` und wird bei jedem Lauf neu hingelegt: Das Plugin legt
  sie selbst nur an, wenn sie fehlt, und was ein abgebrochener Lauf an ihren
  Schaltern gedreht hat, bliebe sonst stehen.
* **Der Cam-Modus läuft voreingestellt im Abenteuermodus**, nicht in Kreativ -
  Kreativ steht nur zwei Ticks lang da, eine Runde der Welt. Der Portalvorgang dauert dort
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
* **Die Grasschicht um den Startplatz legt der Test zu Beginn neu.** Ein Lauf
  fängt dort an, wo der letzte aufgehört hat, und findet vor, was dort im
  Boden steckt. Die Flachwelt erzeugt Dörfer, und deren Wege sind
  Trampelpfade (`minecraft:dirt_path`), genau in der Grasschicht bei y=-61
  und 1/16 niedriger als das Gras. Kein Abschnitt setzt welche - nachgesehen
  nach einem ganzen Lauf -, aber führt ein Dorfweg am Startplatz vorbei,
  steht der Bot auf ihm bei y=-60,0625, und ein waagerechter Flug von dort
  stößt an jeden Grasblock. Dazu kommt das Loch, das das Abräumen des
  Portalrahmens aus dem Portaltest in der Grasschicht hinterlässt.
  `boden_ebnen` füllt deshalb die Lage bei y=-61 von 16 Blöcken westlich und
  nördlich bis 32 östlich und südlich des Startplatzes mit Gras und stellt
  den Bot mitten auf einen Block. Weiter geht es nicht: Bei
  `view-distance=2` hält der Server nur zwei Chunks um den Bot sicher
  geladen, ohne Spieler gar keinen, und `/fill` braucht sie geladen.
* **Der Portaltest rundet die Startstelle ab, wie alle anderen Abschnitte.**
  Mit `int()` schnitt er unter null zur falschen Seite ab: Sein `heim` lag
  einen Block östlich oder südlich neben dem Bot, und dorthin stellt er ihn
  am Ende. Der nächste Lauf fing dann dort an, jeder einen Block weiter - so
  kann ein Lauf auf einem Dorfweg beginnen, neben dem der vorige noch auf
  Gras stand.
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
  und `actionbar-off`, die in der Sprachdatei unter `messages` und unter
  `message-settings` stehen: Die Schalter setzt der Test mit dem Abschnitt
  `message-settings`.
* **Texte und ihre Schalter stehen in der Sprachdatei `lang/en.yml`**, nicht
  in der `config.yml`. `set_options` schreibt alles mit dem Abschnitt
  `messages` oder `message-settings` dorthin und alles andere in die
  `config.yml`, beides in einer Runde mit nur einem `cam reload`. Ein Text wie
  `player.name-format` braucht deshalb immer den Abschnitt `messages`.
* **`cam_on` und `cam_off` richten sich nach der letzten Zeile im Chat.** Die
  Action-Bar wiederholt „Cam mode activated" alle zwei Sekunden. Kommt eine
  Wiederholung kurz vor dem Ausschalten an, steht sie neben „Cam mode ended"
  im selben Fenster. Zählte jede Zeile, hielte der Test das Ausschalten für
  missglückt, und ein zweites `/cam` schaltete den Cam-Modus wieder ein - die
  Probe danach fand ihn dann noch laufend vor. Auf die letzte Zeile ist
  Verlass: Nach der Antwort kommt keine Wiederholung der anderen mehr, das
  Plugin bricht die eine Zeile ab, sobald es die andere schickt. Den Server
  zu fragen geht hier nicht: Am Spielmodus ist der Cam-Modus nur bei
  `camera-mode.gamemode: adventure` zu erkennen, und der zweite Bot, den der
  Test des Spielernamens damit schaltet, hat kein op für `/execute`.
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
* **Was herumliegt, wird mit weggeräumt, und zwar nach dem `/fill`.** Der
  Abbau im Durchgang ohne Cam-Modus lässt eine Blume fallen, und die zählte
  beim Klick auf den eigenen Körper als nächste Entität mit. Das `/fill ...
  air` beim Aufräumen nimmt dem Hebel den Stein unter ihm weg, und der Hebel
  fällt dabei selbst als Item ab. Wurden die Items vorher weggeräumt, blieb
  er liegen, genau dort, wo der Sulfur-Cube-Test danach zuschlägt - und ein
  Schlag auf ein Item wirft den Bot vom Server („Attempting to attack an
  invalid entity"). Jeder Abschnitt danach fiel durch.
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
* **Den Rückstoß misst der Test am Landeplatz.** Jede Probe läuft
  zweimal: einmal trifft es den Bot selbst, einmal seinen Körper, während er
  zehn Blöcke darüber im Cam-Modus wartet. Verglichen wird, wo er liegen
  bleibt - darin stecken Richtung, Weite und Höhe des Stoßes. Die erste
  Geschwindigkeit steht zum Nachlesen dabei. Fällt eine Probe durch, steht
  auch da, wo der Flug anfing, wie hoch er ging und wie viele Pakete mit
  einer Geschwindigkeit kamen - ob er also woanders losging, oben anstieß
  oder noch einen Stoß hinterher bekam.
* **Tränke, Meldungen und Rückstoß laufen mit beiden Körpertypen.** Bei
  Typ 1 trifft es den Rüstungsständer oder das unsichtbare Mannequin darin,
  bei Typ 2 das Mannequin allein - und dort flogen Pfeil, Dreizack,
  Windkugel und Speerstich durch den Körper hindurch, während jede Probe mit
  Typ 1 bestand. `fuer_beide_koerpertypen` fährt diese Abschnitte deshalb
  einmal mit `type: 1` und einmal mit `type: 2`, und jede Probe darin trägt
  den Typ in Klammern hinter ihrem Namen. Am Ende steht wieder `type: 1` da.
* **Geschosse und Speer fragen den Server, ob sie ein Ziel treffen dürfen,
  und der fragt dasselbe Feld wie das Schieben.** `setCollidable(false)`
  setzt in Paper und Spigot `collides`, und `isPickable()` gibt genau das
  zurück; `canBeHitByProjectile()` und der Stich des Speers
  (`PiercingWeapon.canHitEntity`) fragen danach. Damit Spieler und Mobs den
  Körper trotzdem nicht schieben, steht sein Mannequin auf Stufe 0 und 1 im
  Team `cam_body` mit der Kollisionsregel `never`.
* **Den Schiebetest macht ein Schwein mit Tempo 0**, 0,3 Blöcke neben der
  Mitte des Körpers: Es schiebt, was in ihm steht, und läuft selbst nicht
  davon. Ohne KI schiebt ein Mob gar nichts, und genau in der Mitte fehlte
  dem Stoß die Richtung - beides nachgemessen an einem freien Mannequin, das
  sich dann nicht rührte. Auf Stufe 1 muss der Cam-Modus weiterlaufen, auf
  Stufe 2 muss er mit Typ 2 enden - das ist die Gegenprobe. Mit Typ 1 fällt
  Stufe 2 auf 1 zurück, der Körper bleibt stehen. Das Schwein kommt erst ein
  paar Sekunden nach dem Start: Eine Sekunde lang merkt sich die
  Bewegungsprüfung des Plugins noch keine Stelle, und was den Körper bis
  dahin verschiebt, fiele ihr nicht auf.
* **mineflayer rechnet die Geschwindigkeit von 26.x falsch um.** Das Paket
  trägt sie als lpVec3, schon in Blöcken je Tick; mineflayer teilt sie noch
  einmal durch 8000 wie im alten Format, und der Bot rührte sich nach einem
  Treffer kaum. `knock_start` setzt sie deshalb selbst - nur solange es
  mitschreibt, die übrigen Abschnitte laufen weiter wie bisher.
* **Das Explosionspaket von 26.2 liest der Bot nur bis zum Rückstoß.**
  Dahinter stehen Partikel, deren Nummern 26.1 anders vergibt; mineflayer
  warf sonst das ganze Paket weg, samt dem Stoß darin, und TNT schob den Bot
  ohne Cam-Modus keinen Millimeter.
* **Ein Pfeil stößt weniger weit als ein Schlag.** Ein Geschoss trifft,
  während der Server die Entitäten bewegt, und danach bewegt er den Spieler
  erst einen Tick lang selbst, ehe er ihm den Stoß schickt: Statt
  (-0,4 | 0,3608) kommt (-0,2184 | 0,2752) an. Der Schlag eines Spielers
  kommt zwischen zwei Ticks herein und geht ungebremst hinaus. Das Plugin
  macht beides genauso nach, deshalb steht jede Art Treffer für sich da.
* **Gleich nach einem Teleport hält der Client den Spieler noch in der
  Luft.** Der echte Client setzt beim Teleport nur Position und
  Geschwindigkeit, meldet dem Server „nicht am Boden" und setzt ihn erst mit
  seinem nächsten Tick ab. Ein Stoß, der davor ankommt, rutscht im ersten
  Tick ohne Bodenhaftung weiter - bei TNT einmal 4,25 statt 2,78 Blöcke. Der
  weitergegebene Treffer wartet deshalb zwei Ticks des Spielers ab.
* **Für TNT liegt eine Platte aus Obsidian unter dem Ziel**, 21 × 21 Blöcke.
  Sonst risse die Explosion die Grasschicht auf, und der Bot stünde bei der
  nächsten Probe in der Grube. Am Ende legt `boden_ebnen` wieder Gras.
* **Der Bot trägt im Rückstoßtest Resistenz 255.** Jeder Treffer landet und
  stößt, verletzt ihn aber nicht. `cam-safety` ist solange aus, sonst ginge
  nach jedem Treffer fünf Sekunden lang kein `/cam`.
* **In `peaceful` verletzt TNT keinen Spieler, stößt ihn aber.** Ohne
  Cam-Modus kommt der Stoß dann allein mit dem Explosionspaket. Im Cam-Modus
  lehnt der Server den weitergegebenen Schaden sofort ab, und das Plugin gibt
  den Stoß trotzdem weiter - wie die Explosion selbst.
* **Die Schläge führt ein zweiter Spieler**, `CamFlyZuschauer`, östlich des
  Ziels. Er schlägt, was dem Ziel am nächsten steht: ohne Cam-Modus den Bot,
  im Cam-Modus den Körper. Liegengebliebene Pfeile und Dreizacke räumt der
  Test vorher weg, sie tragen die Marke `camflytest`.
* **Die Waffe kommt mit `item replace` ins erste Fach, nicht mit `/give`.**
  Das Schwein für den Schwungschlag lässt beim `kill` Fleisch fallen, und
  hob der Schläger es zwischen `clear` und `/give` auf, lag es im ersten
  Fach: Er schlug mit dem Fleisch, ohne Schwung, und stach ohne Speer ins
  Leere. Das Schwein hat dazu eine leere Beutetabelle.
* **Den Sprint meldet der Test selbst.** mineflayer schickt für 26.x die
  Nummer der Aktion aus alten Versionen, und die heißt dort etwas anderes:
  Der Server hielt den Schläger nie für sprintend. `sprint` schreibt das
  Paket `entity_action` deshalb mit dem Namen der Aktion.
* **Ein voller Sprintschlag beendet den Sprint auf dem Server.** Für den halb
  ausgeholten Sprintschlag geht deshalb erst ein voller Schlag auf einen
  Rüstungsständer daneben, dann wird neu gesprintet und gleich hinterher
  geschlagen - mit dem Schwert ist der Schlag dann erst halb ausgeholt, und
  nur so prüft die Probe, dass der Sprint dann nichts dazugibt.
* **Ein Speer sticht nicht über das Paket für den Schlag.** Das nimmt der
  Server mit einem Speer in der Hand gar nicht an. Der Client meldet einen
  Stich als Aktion Nummer 7 (STAB) im Paket `block_dig`, und der Server sucht
  selbst entlang des Blicks, was er trifft. `stab` sieht deshalb erst hart
  zum Ziel und sticht einen Moment später. Der Speer sticht zudem nur voll
  ausgeholt, der Test wartet nach dem Wechsel der Waffe zwei Sekunden.
* **Ein Speer mit Rückstoß II stößt einen Spieler nicht weiter als ohne.**
  Der Server schickt den ersten Stoß des Stichs sofort und setzt die
  Geschwindigkeit danach zurück; den zweiten, den der Verzauberung, schickt
  ihm niemand. Die Probe hält fest, dass der Körper das genauso weitergibt.
* **Eine Windkugel fliegt langsam.** Mit der Geschwindigkeit, die ein Breeze
  ihr mitgibt, fliegt sie zwischen zwei Ticks durch einen Spieler hindurch,
  ohne ihn zu treffen. Die Probe schickt sie deshalb mit 0,3 Blöcken je Tick
  und ohne Beschleunigung los.
* **Die Windkugel trifft im Cam-Modus das Mannequin, bei beiden Typen.** Es
  ist so breit wie ein Spieler, die Kugel explodiert also genau dort, wo sie
  ihn ohne Cam-Modus träfe. Gemessen landet er auf ein Zehntausendstel am
  selben Fleck. Solange das Mannequin Geschosse durchließ, traf sie bei Typ 1
  den Rüstungsständer, 0,5 Blöcke breit statt 0,6: Ihr Stoß ging ein wenig
  steiler, der Spieler landete bis zu 0,09 Blöcke anders, und die Probe
  brauchte eine eigene Toleranz.
* **Der Streitkolben schlägt aus 3,4 Blöcken Höhe, 0,3 Sekunden nach dem
  Teleport.** Erst nach gut 1,5 Blöcken Fall ist es ein Schlag mit Wucht, der
  alles drumherum wegstößt. Früher fehlt der Fall, später ist der Schläger
  schon gelandet oder nicht mehr in Reichweite.
* **Die Spielregeln heißen seit 26.x anders.** `doMobSpawning` ist
  `spawn_mobs`, `doDaylightCycle` ist `advance_time`. Mit den alten Namen
  lehnte der Server beide ab - es spawnten Tiere, und auf `easy` griff ein
  Zombie den Körper an, ehe die Probe anfing.
* **Mobs reizt der Test mit `/damage`.** Den Kamera-Spieler sehen sie nicht,
  und den Körper nehmen sie nur mit `body.mob-target` - und auch dann nicht
  jeder: Ein Eisengolem denkt gar nicht an ihn. Ohne Cam-Modus geht der Reiz
  vom Bot aus, im Cam-Modus vom Körper. Den Wüstenzombie reizt Schaden ohne
  Stoß (`minecraft:generic`), damit er sofort zuschlägt, noch mit dem Körper
  nach Süden - einen Schritt später hätte er sich umgedreht.
* **Bei Mobs zählt die erste Geschwindigkeit.** Sie schlagen nach dem ersten
  Treffer weiter zu, der Landeplatz sagt dann nichts mehr. Beim Golem zählt
  nur die Höhe - dort steckt sein eigener Stoß. Zur Seite hängt sie davon ab,
  wo er beim Schlag steht und ob er den Bot vorher schon angerempelt hat; den
  Körper rempelt auf Bewegungsstufe 1 niemand an. Gemessen kam der Bot zur
  Seite mit 0,270 statt 0,218 davon, in der Höhe stimmten beide auf
  0,6672.
* **Die Ziege steht nicht in der Testumgebung.** Gemessen rammt sie den Körper
  genau wie den Bot (Landeplatz −6,967 ohne, −6,958 mit Cam-Modus), aber sie
  sucht sich ihr Ziel selbst und nahm den Körper nicht in jedem Lauf.
* **Der Wärter steht in einem Käfig aus Barrieren.** Acht Blöcke vom Ziel
  kommt er nicht heran, ihm bleibt nur der Schallstoß, und der geht durch
  Wände. Gereizt hält er ihn zehn Sekunden zurück und lädt dann 1,7 Sekunden
  auf, die Probe wartet 13,5. Zwei Fallen dabei: Einem Wärter, den `/summon`
  mit Daten setzt, fehlt `dig_cooldown` im Gedächtnis - er gräbt sich sofort
  ein, ist dabei unverwundbar, und das `/damage` zum Reizen prallt ab
  („Target is invulnerable to the given damage type“). Und seine Dunkelheit
  ist eine schädliche Wirkung: Mit `start-with-effects: positive` ließe sie
  `/cam` nicht starten, deshalb kommt er erst nach dem Start dazu.
* **Ob der Wärter angreift, entscheidet seine Wut, nicht sein Ziel.** Er geht
  auf den los, über den er am wütendsten ist, und kein Ereignis für ein Ziel
  kommt dabei vorbei - `setTarget` ändert bei ihm nichts. Der Test reizt ihn
  deshalb mit `/damage` und sieht nach, ob der Cam-Modus endet. Vom
  Kamera-Spieler aus gereizt brüllt er erst gut vier Sekunden, ehe er den
  Körper angreift; die Probe wartet zehn.
* **Hoglin und Piglin steuert ihr Gehirn.** `setTarget` erreicht sie nicht,
  ihr Ziel wechselt nur, wenn sie es selbst loslassen - beim Start im
  Kreativ-Tick, am Ende, wenn der Körper verschwindet. Ohne
  `IsImmuneToZombification:1b` würden beide in der Oberwelt nach 15 Sekunden
  zu Zombies. Ob die Übergabe saß, fragt die Probe eine Sekunde nach dem
  Start: Ein Hoglin, den sie verfehlt, gibt den Kamera-Spieler nach gut zehn
  Sekunden von selbst auf und ginge dann doch noch rechtzeitig auf den Körper
  los - mit dem alten Stand bestand er so jede spätere Frage.
* **Breeze und Knarz stehen nicht in der Probe.** Der Breeze greift nach den
  Regeln des Spiels nur Spieler und Eisengolems an und behält auf Paper den
  Körper keinen Tick lang. Spigot lässt ihn jedes Ziel angreifen, das sein
  Gehirn hat (SPIGOT-7957), auch einen Spieler im Kreativmodus - dort lässt
  er den Bot beim Start gar nicht los. Seine Windkugeln, schon vor dem Start
  auf den Bot abgefeuert, treffen ohnehin oft den Körper, der an dessen
  Stelle steht. Den Knarz weckt der Blick eines Spielers, und das Spiel setzt
  ihm dabei sein Ziel selbst, ohne ein Ereignis, das ein Plugin umlenken
  könnte.
* **Ob ein Mob ein Ziel hat, sagt `/execute as <Mob> on target run say`.**
  Hat er eines, sagt es die Marke. Ein Golem, der beim Start schon neben dem
  Bot steht, schlägt den Körper oft im selben Augenblick: Der Cam-Modus ist
  dann schon vorbei, ehe ein `cam_on` nachsehen kann, ob er läuft. Die Probe
  wartet deshalb nur auf die Bestätigung des Starts.
* **Ob ein Mob den Bot ansieht, sagt sein Kopf.** Der Blicktest stellt drei
  Kühe und einen fahrenden Händler um eine Stelle herum, die Kühe im Westen,
  Süden und Südwesten, den Händler im Nordosten, und lässt den Bot im
  Cam-Modus 1,2 Blöcke darüber schweben: Wer ihn ansieht, sieht steil nach
  oben. Gefragt wird mit
  `/execute as @e[...,x_rotation=-90..-30] run say`, und vor der Marke steht
  im Chat der Name des Mobs. Wer sich nur umsieht, sieht auf Augenhöhe, also
  mit 0. Steiler als -40 sieht keiner: Der Kopf hebt sich in jedem Tick neu
  von 0 aus, um höchstens 40 Grad.
* **Unsichtbar bemerkt ein Mob den Bot nur aus zwei Blöcken.** So weit lässt
  das Spiel jeden Mob einen unsichtbaren Spieler sehen, egal wie kurz seine
  Reichweite sonst wäre. Die Füße des Bots stehen deshalb höchstens 1,85
  Blöcke von denen der Mobs - weiter weg sähe ihn auch ohne das Plugin
  keiner an, und die Gegenprobe mit `mobs-look-at-player: true` fiele.
* **Die Kühe laufen nicht davon.** Ihr Bewegungstempo steht auf 0, gesetzt
  mit `/attribute`; ihre Ziele laufen sonst ganz normal, auch das Umsehen.
  Eine Kuh sucht sich jede zweite Runde ihrer Ziele mit 2 %
  Wahrscheinlichkeit jemanden zum Ansehen, im Schnitt also alle fünf
  Sekunden; deshalb drei Kühe und acht Sekunden je Probe.
* **Der Händler steht in einer Zelle aus Barrieren**, zwei Blöcke hoch, und
  behält sein Tempo. Er sieht über `INTERACT` jeden an, der drei Blöcke an
  ihn herankommt, und zwar sofort - an ihm hängt die Gegenprobe nicht am
  Zufall. `INTERACT` hält aber neben dem Blick auch die Bewegung, und mit
  Tempo 0 käme sein Spaziergang nie an und hielte sie ihm für immer weg: So
  stand er im ersten Lauf da und sah niemanden an, auch mit
  `mobs-look-at-player: true` nicht. Die Zelle steht über Eck, im Nordosten,
  damit ihre Wände seinen Blick nicht verdecken - er geht über die Kante
  zweier Wände hinweg, gut zwei Zehntel Blöcke über ihnen, zum Zuschauer
  gut ein Zehntel.
* **Der Zuschauer im Blicktest steht auf einer Barriere** mitten zwischen
  den Mobs, damit auch er höher steht als ihre Augen. Der Bot schwebt dabei
  im Cam-Modus fünf Blöcke daneben: nah genug, dass die Mobs das Ziel des
  Plugins tragen, zu weit, als dass sie ihn unsichtbar bemerkten. So prüft
  die Probe, dass das Ziel einen Spieler ohne Cam-Modus nicht verdeckt.
* **Der Name über dem Körper ist ein TextDisplay und wird am Server
  geprüft.** Was der Client daraus zeichnet, sieht der Bot nicht. Gefragt
  wird mit `/execute as <Körper> at @s positioned ~ ~2.25 ~ if entity
  @e[type=text_display,distance=..0.05]`, ob er genau über dem Körper steht,
  und mit `/execute if data`, was er trägt. Die Höhe ist die des Körpers plus
  0,275 - dort beginnt das Namensschild, das das Spiel selbst über ihn setzen
  würde: 2,25 über dem Rüstungsständer, 2,075 über dem Mannequin.
* **Gezählt wird über `say`**: Jede passende Entität sagt dieselbe Marke
  einmal, und der Test zählt, wie oft sie im Chat steht. So steht fest, dass
  der unsichtbare Körper ein einziges Mannequin ist und kein Rüstungsständer
  mehr daneben steht. Die Antwort von `/execute if entity` ohne `run` („Test
  passed, count: N“) kommt beim Bot ohne die Zahl an.
* **Ein Kommando darf höchstens 256 Zeichen lang sein.** Ein längeres nimmt
  der Server nicht an und wirft den Bot hinaus („Failed to decode packet
  'serverbound/minecraft:chat_command'“) - danach fällt jede weitere Probe.
  Der Namenstest fragt die Eigenschaften des TextDisplays deshalb einzeln ab.
* **Vor dem Namenstest räumt der Abschnitt Rüstungsständer, Mannequins und
  TextDisplays im Umkreis von acht Blöcken weg.** Er zählt sie um den Körper
  herum, und die Testwelt bleibt zwischen zwei Läufen stehen.
* **Dass der Name dem Körper folgt, prüft der Test am Rüstungsständer**: Er
  wird per `/tp` einen Block versetzt. Der Cam-Modus läuft dabei weiter, auf
  Bewegung prüft das Plugin das Mannequin, das in ihm steht.
* **Einen Zeilenumbruch im Namen schreibt der Test doppelt geschützt** in die
  Datei: `replace_option` reicht den Wert durch `re.subn`, das aus `\\`
  einen einzelnen Rückstrich macht. In der Datei steht danach `\n` in
  Anführungszeichen, und YAML macht daraus die neue Zeile.
* **Die Schalter des Namens gibt es zweimal**, unter `body.name` und unter
  `camera-mode.name`. Der Namenstest und der Test des Spielernamens setzen sie
  deshalb mit Abschnitt, wie `nether`. Der Namenstest schaltet den Namen über
  dem Kamera-Spieler für sich ab: Zu Beginn des Cam-Modus steht der Bot genau
  über seinem Körper, und `NAME` nimmt das nächste TextDisplay.
* **Den Namen über dem Kamera-Spieler sucht der Test an der Stelle**, nicht an
  der Art: im Modus 1 2,075 über den Füßen des Bots, die Höhe eines Spielers
  plus 0,275, im Modus 2 1,8 - dort sitzt ein Passagier, die Schrift ist um
  0,275 hinaufgeschoben. Am Server mit `/execute at`, in den Clients über die
  Position der Entitäten - der Bot liest die Entitätsarten mit den Daten von
  26.1, und das TextDisplay liegt hinter dem Sulfur Cube, also um eins
  verrutscht.
* **Im Modus 2 fragt der Test den Client nach dem Fahrzeug**, nicht nach der
  Stelle. Einem Passagier schickt der Server keine eigenen Positionen, der
  Client setzt ihn auf sein Fahrzeug - mineflayer nur nicht, bei ihm bleibt
  er stehen, wo er ihn zuerst sah. `entities` gibt deshalb zu jeder Entität
  `vehicle` mit, die Nummer der Entität, auf der sie sitzt.
* **Ob jemand den Spielernamen sieht, fragt der Test die Clients.** Der Bot
  selbst darf ihn nicht kennen, `CamFlyZuschauer` schon - ohne Cam-Modus und
  im Cam-Modus, denn das Namensschild der Kamera-Spieler ist abgeschaltet.
  Der Zuschauer bleibt den ganzen Abschnitt über dabei, `cam reload` wirft
  nur Kamera-Spieler hinaus.
* **Das abgeschaltete Namensschild liest der Test aus `scoreboard.dat`**,
  nach einem `save-all flush`: `NameTagVisibility` des Teams `cam_no_push`
  muss `never` sein, mit `camera-mode.name-visible: false` dagegen `always`.
  Den Client zu fragen geht nicht. mineflayer liest das Team-Paket von 26.2
  mit den Daten von 26.1 falsch - Optionen und Mitglieder kommen verdreht an,
  selbst die Kollisionsregel, die das Plugin seit jeher auf `never` setzt,
  steht bei ihm auf `always`. `nbt_lesen` liest die Datei ohne fremde
  Bibliothek.
* **Die Reise mit dem Spielernamen geht durch ein eigenes Portal**, sechs
  Blöcke neben dem Testplatz und einen über dem Boden, einmal in jedem Modus.
  Auf dem Boden stünde die unterste Reihe des Rahmens in der Grasschicht, und
  das Aufräumen mit `/fill ... air` ließe dort ein Loch - in der Testwelt, die
  stehen bleibt, fiel beim nächsten Lauf der Sulfur Cube hinein und ließ sich
  nicht mehr schieben. Dafür müssen beide
  `nether` auf `true`, unter `portals` und unter `cam-area.dimensions` - mit
  nur einem meldet das Plugin „cam mode is not allowed in the Nether“, und der Bot
  bleibt im Portal stehen. Hinterher räumt der Abschnitt beide Seiten wieder
  ab: Drüben bliebe sonst das Portal stehen, das der Server gebaut oder
  genommen hat, und der Portaltest hielte es für seines.
* **`/tp` in den Nether schickt der Test über das Netherdach**, auf y=130.
  Darunter stäke der Bot im Netherrack und nähme Schaden; die
  `cam-safety`-Sperre läge dann auf allen weiteren Proben. Zurück geht es mit
  `/cam`. Ohne Portal hat er drüben zwar keinen Anker, und CamFly holt ihn beim
  ersten Schritt zurück - aber nach einem Weltwechsel stimmt bei mineflayer die
  eigene Position nicht mehr, und der Schritt landete einmal mitten im Nether.
* **Ein Passagier verträgt sich nicht mit einem Teleport.** So wurde es
  ausprobiert, bevor es Modus 2 gab: Saß der Name als Passagier auf dem
  Spieler, lehnte Spigot 26.2 jeden `player.teleport()` ab - nach `/cam` blieb
  der Spieler in der Luft hängen statt an seinem Körper, und aus dem Nether
  kam er gar nicht mehr zurück. Paper nahm den Passagier in derselben Welt
  mit, warf ihn aber am Portal und bei `/tp` in eine andere Welt ab; der Name
  blieb am Portal in der Overworld stehen. Modus 2 nimmt ihn deshalb vor jedem
  Teleport von CamFly selbst und vor jedem Wechsel der Welt ab, und genau das
  prüft der Test: Beenden, `/tp` in derselben und in eine andere Welt, die
  Reise durch das Portal und dass am Portal nichts zurückbleibt.
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
* **Die Proben in der Luft enden im Wasser.** Für den Start im Fall lässt der
  Test den Bot 24 Blöcke über einem Becken los und setzt ihn nach jeder Probe
  per `/tp` hinein. Das Wasser nimmt ihm die Fallstrecke, und kein Aufprall
  legt die `cam-safety`-Sperre auf die nächsten Proben.
* **Die Gegenprobe knapp über dem Boden läuft mit Sanftem Fall** und wartet
  nach dem `/tp` nur 0,3 Sekunden. Gleich nach dem `/tp` steht der Bot für den
  Server noch auf dem Boden, bis sein Client die erste Bewegung meldet - dann
  sagte die Probe nichts. Ohne den Effekt wäre er aber nach einer halben
  Sekunde schon gelandet.
* **Luft und Frost liest der Test am Mannequin**, mit `/data get entity`, bei
  Körpertyp 1 also am unsichtbaren im Rüstungsständer. `TicksFrozen`
  speichert das Spiel nur, solange der Wert über null liegt.
* **Der Frost lässt wenig Zeit.** Nach 140 Ticks ist er voll, und von da an
  tut er weh, am Bot wie am Körper. Der Test liest deshalb gleich nach dem
  `/cam`, ohne die Pause von `cam_on` und `cam_off`, und holt den Bot sofort
  wieder aus dem Pulverschnee.
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
* **Die Physik von mineflayer lief an manchen Stellen durch Blöcke.**
  prismarine-physics setzt nach dem Anstoßen die Position aus der Kante der
  Box zusammen; an manchen Koordinaten ragt die Box danach um einen
  Rundungsfehler in den Block, und ihr Vergleich ohne Toleranz lässt den Bot
  im nächsten Tick hindurch - etwa an einer Wand bei x=-2. Der Grenztest fiel
  deshalb je nach Startplatz durch: vom Platz bei x=-8 aus lief der Bot durch
  die Wand und wurde zurückgesetzt, einen Block weiter westlich nicht. Der
  echte Client rechnet mit 1e-7 Toleranz (`VoxelShape.collideX/Y/Z`) und
  bleibt stehen; der Schritt `bot` flickt `prismarine-physics/lib/aabb.js`
  genauso.
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
Cam-Modus · die Meldung dazu nennt den Effekt, an dem es lag · die Meldung zu
einem Treffer auf den Körper nennt, was ihn getroffen hat: ein fallender Amboss
ist kein Angriff, sondern „a falling anvil“, ein Kaktus heißt „a cactus“ statt
CONTACT, ein Mob heißt wie in `mob-names` und einer mit eigenem Namen so, wie
er heißt · eine eigene Sprachdatei liefert auch die Namen, und was ihr fehlt,
kommt aus der englischen · ebenso den Namen eines Effekts, den die Ablehnung
beim Start nennt · `/cam` startet
mit einem positiven und einem neutralen Effekt, mit einem schädlichen nicht ·
auf `false` sperrt jeder Effekt, auf `true` keiner · die Ablehnung nennt jeden
schädlichen Effekt und nur die · `actionbar-on` und `actionbar-off` schalten
jeweils nur ihre eigene Zeile ab · ohne `actionbar-off` wird die Zeile zum
Start beim Ende geleert, voreingestellt nicht ·
`message-settings.enabled: false` nimmt die Action-Bar mit · die Startzeilen
stehen voreingestellt im Log · voreingestellt gilt `language: en` mit der
mitgelieferten `lang/en.yml` · eine eigene Sprachdatei liefert ihre Texte,
und was ihr fehlt, kommt aus der englischen · ist sie kaputt, meldet der
Reload das mit Datei und Zeile, meldet keinen Erfolg und lässt die bisherigen
Texte stehen - und auch die `config.yml` aus derselben Runde · eine Sprache
ohne Datei wird gemeldet und fällt auf Englisch zurück · Texte, die noch in
der `config.yml` stehen, werden als nicht mehr gelesen gemeldet · ein offenes
Portal trägt den Kamera-Spieler in den Nether · ein verbotenes Biom dahinter
holt ihn zurück · danach lässt
dasselbe Portal ihn gar nicht mehr durch · ein drüben neu gebautes Portal gibt
das gemerkte wieder frei · ein drüben abgebautes ebenso · auf
`border-mode: false` holt ihn auch das verbotene Biom nicht zurück · mit
`forget-changed: false` bleibt der Eintrag stehen · auf `portals.nether: false`
trägt das Portal ihn gar nicht erst hinüber · im Cam-Modus lässt sich kein
Hebel umlegen, kein Block abbauen und keine Druckplatte auslösen · kein Bild
im Rahmen drehen, kein Fenster einer Kistenlore öffnen und kein Boot
besteigen · Gegenprobe: ohne Cam-Modus geht
jedes davon sehr wohl · das Inventar ist im Cam-Modus leer und danach wieder
da · die Rüstung ist im Cam-Modus abgelegt und danach wieder angezogen · ein
Pfeil, ein Pfeil mit Schlag II, ein geworfener Dreizack, TNT und die
Schläge eines Spielers auf den Körper stoßen den Spieler genau dorthin, wo
derselbe Treffer ihn ohne Cam-Modus hinstößt - vom Schützen weg und nicht
zu ihm hin -: ein Schlag, ein Sprintschlag voll und halb ausgeholt, ein
Schwert mit Rückstoß II, ein Schwungschlag, der den Körper neben seinem Ziel
trifft, ein Speerstich und ein Speerstich mit Rückstoß II, ein Streitkolben,
der neben dem Körper aufschlägt, und zwei Windkugeln, die mit ihrem Treffer
explodieren · ein Wüstenzombie, der mit dem Körper anders steht als mit dem
Kopf, stößt entlang des Körpers, ein Eisengolem wirft hoch, und der
Schallstoß eines Wärters schleudert weit weg, alles wie ohne Cam-Modus ·
Pfeil, TNT, Windkugel, Sprintschlag und Speerstich auch mit
`damage-mode: false` · die Tränke, die Meldungen zu einem Treffer und jede
Probe zum Rückstoß mit Körpertyp 1 und mit Körpertyp 2 · auf
Bewegungsstufe 1 schiebt ein Mob den Körper nicht, auf Stufe 2 bei Typ 2
schon und beendet damit den Cam-Modus, bei Typ 1 nicht · ein Wärter greift
den Körper mit `mob-target: vanilla` an und mit `false` nicht, auch wenn ihn der
Kamera-Spieler reizt · ein Eisengolem, der beim Start hinter dem Spieler her
ist, geht mit `mob-target: vanilla` auf den Körper los, und er wie ein
Zombie verliert mit `false` sein Ziel · ebenso ein Hoglin und ein Piglin,
die ihr Gehirn steuert, und wen der Körper angezogen hat, der ist nach dem
Ende wieder hinter dem Spieler her · mit `mobs-look-at-player: false` sieht
weder eine Kuh noch ein fahrender Händler den Kamera-Spieler an, auch nicht
aus nächster Nähe, einen Spieler ohne Cam-Modus daneben aber sehr wohl ·
Gegenprobe: mit `true` sehen beide den Kamera-Spieler an · der
Name steht als TextDisplay genau dort über dem Körper, wo sonst sein
Namensschild hinge, trägt den Text aus `armorstand.name-format`, folgt dem
Körper und wird mit ihm eingesammelt · der Körper selbst trägt keinen Namen
mehr · unsichtbar ist der Körper bei beiden Typen ein einziges unsichtbares
Mannequin, ohne Rüstungsständer, mit dem Namen darüber · voreingestellt ist
der Name gelb, nicht durch Wände zu sehen, hat den Hintergrund eines
Namensschilds, keinen Schatten, Größe 1 und 64 Blöcke Sichtweite · jeder
Schalter unter `body.name` ändert genau das, und fest hell ist der Name
dabei immer · eine unbekannte Farbe wird gemeldet und fällt auf gelb zurück ·
`color` ersetzt einen Farbcode vorn in `name-format`, ein Farbcode weiter
hinten gilt weiter · `\n` beginnt eine neue Zeile · `name-visible: false`
setzt keinen Namen · über dem unsichtbaren Kamera-Spieler steht sein Name als
TextDisplay, wo sonst sein Namensschild hinge, trägt den Text aus
`player.name-format` und folgt ihm im Flug · im Modus 1 sitzt er nicht auf
ihm, im Modus 2 sitzt er als Passagier auf seinem Kopf, die Schrift auf der
Höhe des Namensschilds · sein Namensschild ist abgeschaltet, mit
`name-visible: false` wieder da · ein Spieler ohne Cam-Modus sieht den Namen,
ein anderer Kamera-Spieler auch, der Kamera-Spieler selbst nicht ·
voreingestellt ist er weiß, sonst wie der Name über dem Körper · jeder
Schalter unter `camera-mode.name` ändert genau das, ohne den Namen über dem
Körper anzufassen, und der Text ist frei, etwa „Cam von {player}“ · eine
unbekannte Farbe und ein unbekannter `name-mode` werden gemeldet und fallen
auf weiß und Modus 1 zurück · mit `allow_invisibility_potion: false` trägt er
den Namen ebenso · mit `player_visibility_mode: cam` sieht ihn nur, wer selbst
im Cam-Modus ist · mit `player_visibility_mode: false` und `name-visible:
false` gibt es keinen · in beiden Modi steht er nach der Reise durch das
Netherportal drüben wieder über ihm, am Portal bleibt nichts zurück, und das
Beenden bringt ihn aus dem Nether zurück zu seinem Körper · im Modus 2 nimmt
`/tp` in derselben Welt den Namen mit, nach `/tp` in den Nether sitzt er dort
wieder auf ihm, und `/cam` bringt ihn aus derselben wie aus der anderen Welt
zu seinem Körper, ohne dass ein Name zurückbleibt · der
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
zurückgesetzt · mitten im Fall startet `/cam` nicht, auch nicht mit Sanftem
Fall hoch in der Luft, und die Ablehnung sagt warum · Gegenprobe: so hoch wie
ein Sprung über dem Boden geht es · das Mannequin atmet und friert mit der
Luft und dem Frost weiter, die der Spieler beim Start hatte, und nach dem
Aussteigen hat der Spieler die des Mannequins · voreingestellt steht
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
