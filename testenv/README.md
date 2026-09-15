# Testumgebung fuer CamFly

Baut aus einem frisch geklonten Repo eine vollstaendige Testumgebung: JDK 25,
das gebaute Plugin, beide API-Pruefungen, einen laufenden Paper-Server und
einen Bot, der im Spiel steht.

```sh
./testenv/setup.sh
```

Das war es. Am Ende laeuft ein Paper-Server mit CamFly, und der Bot
`CamTester` steht darin und nimmt Befehle entgegen.

Am Plugin selbst aendert das Skript nichts. Alles Heruntergeladene und
Erzeugte liegt unter `~/camfly-testenv` (anders zu setzen ueber
`CAMFLY_TEST_HOME`), also ausserhalb des Repos. `target/` wird nach jedem
Bauen wieder auf den Stand des Repos zurueckgeholt.

## Was die Abschnitte tun

| Abschnitt   | Inhalt |
|-------------|--------|
| `toolchain` | JDK 25 von Adoptium, paper-api samt Abhaengigkeiten, das Paper-Server-Jar, mineflayer |
| `build`     | `JAVA_HOME=<jdk25> mvn -B clean package`, danach `target/` zuruecksetzen |
| `papercheck`| denselben Quelltext noch einmal mit javac gegen paper-api uebersetzen |
| `apicheck`  | jeden API-Aufruf im fertigen Jar gegen paper-api aufloesen |
| `server`    | Server einrichten, starten, `difficulty peaceful` setzen |
| `bot`       | `CamTester` joint und bekommt op |

Einzeln aufrufbar:

```sh
./testenv/setup.sh build apicheck   # nur neu bauen und pruefen
./testenv/setup.sh status           # was laeuft gerade
./testenv/setup.sh down             # Bots und Server beenden
./testenv/setup.sh clean            # Server, Logs und Ausgaben weg,
                                    # JDK/Jars/node_modules bleiben
```

Ein kompletter Durchlauf dauert beim ersten Mal ein paar Minuten (JDK 141 MB,
Server-Jar 62 MB), danach unter einer Minute.

## Server und Bot steuern

```sh
./testenv/server.sh cmd "op CamTester"    # Konsolenbefehl
./testenv/server.sh log 50                # Server-Log
./testenv/server.sh cleanup               # Entities weg, Cam-Koerper bleiben
./testenv/server.sh stop

./testenv/bot.sh start CamWatcher         # zweiter Bot
./testenv/bot.sh cmd chat /cam            # Cam-Modus an oder aus
./testenv/bot.sh cmd truth                # Position laut Server
./testenv/bot.sh cmd near 12              # Entities in der Naehe
./testenv/bot.sh cmd fly 5.5 -50 40.5     # fliegen, mit hartem Timeout
./testenv/bot.sh log 30                   # nur die Zeilen des Bots
./testenv/bot.sh rawlog 60                # mit den Meldungen der Bibliotheken
./testenv/bot.sh stop all
```

Botbefehle: `chat`, `pos`, `truth`, `fly x y z [sekunden]`, `stopfly`, `look`,
`state`, `near [radius]`, `ping`, `quit`.

## Warum gegen beide APIs geprueft wird

Das pom baut gegen **spigot-api**, der Server laeuft auf **Paper**. Die beiden
sind nicht deckungsgleich, und was nur in spigot-api steht, faellt sonst erst
im Spiel als `NoSuchMethodError` auf. Darum zweimal:

1. `papercheck` uebersetzt den Quelltext noch einmal gegen paper-api.
2. `apicheck` geht ueber das **fertige Jar**: `javap -p -c` zerlegt jede
   Klasse, jede Referenz auf `org/bukkit`, `net/md_5` und `io/papermc` wird
   herausgezogen und per Reflection gegen paper-api aufgeloest - mitsamt
   Oberklassen und Superinterfaces, bei Interfaces auch `java.lang.Object`.

Stand jetzt: 424 Referenzen, davon 346 Methodenaufrufe, 13 Konstruktoren und
65 Feldzugriffe. Alle in beiden APIs vorhanden. Die Sollmarke der
Methodenaufrufe steht in `lib.sh` unter `APICHECK_EXPECTED`; weicht die Zahl
ab, hat sich der Code veraendert - das ist ein Hinweis, kein Fehler.

Merke: Paper haengt `Biome` und `Structure` nur an `Keyed`, nicht an
`RegistryAware`. `getKey()` gibt es auf beiden, `getKeyOrNull()` und
`getKeyOrThrow()` nur auf Spigot.

Zwei Fallen beim Zerlegen, beide sind im Pruefer beruecksichtigt: javap
schreibt Konstruktoren als `."<init>":` mit Anfuehrungszeichen, und Klassen,
deren Signaturen auf fehlende Fremdklassen zeigen, duerfen nicht als "fehlt"
gemeldet werden - `getDeclaredMethods()` wirft dann alles oder nichts.

## Der Bot und Minecraft 26.2

minecraft-data hat Paketdaten nur bis 26.1 (Protokoll 775), der Server spricht
26.2 (776). `bot/patch26_2.js` leiht sich die Daten von 26.1 und traegt 26.2
in alle vier Versionslisten nach. Das Modul muss **vor** dem require von
mineflayer laufen, weil `loader.js` `latestSupportedVersion` genau einmal
beim Laden ausliest.

Beim Login kommen zwei `partial packet`-Warnungen, spaeter noch welche zu
`teams`. Sie sind harmlos. `bot.sh log` blendet sie aus, `bot.sh rawlog` zeigt
sie.

**Entity-Nummern sind verschoben.** 26.2 hat einen Entity-Typ dazubekommen,
alphabetisch zwischen `squid` und `tadpole`. Bis einschliesslich `squid`
(Nummer 127) stimmen die Namen aus den 26.1-Daten, ab `stray` ist alles um
eins verschoben - ein Spieler meldet sich als `fishing_bobber`. Die beiden
Koerper von CamFly liegen darunter (`armor_stand` 5, `mannequin` 83) und
werden richtig benannt. `bot.sh cmd near` gibt die rohe Nummer mit aus und
erkennt Spieler ueber die UUID aus der Spielerliste, die davon nicht
betroffen ist. Nachpruefen laesst sich die Grenze mit `summon` und `near`.

## Fallen, die schon Zeit gekostet haben

- **`difficulty peaceful` ist Pflicht.** Sonst erschlaegt ein Zombie den Bot,
  und danach verweigert das Plugin `/cam` wegen der cam-safety-Sperre.
  `setup.sh` setzt es in `server.properties` und schickt es zusaetzlich in
  die Konsole.
- **`camera-particles.particles-per-tick` muss 0 sein.** Sonst stirbt jeder
  Bot in Sichtweite eines Cam-Spielers am Partikel-Paket, weil
  minecraft-data 26.1 die Partikel-IDs von 26.2 nicht kennt. `setup.sh` legt
  die `config.yml` schon vor dem ersten Start mit 0 hin.
- **`kill @e[type=!minecraft:player]` toetet Spieler im Cam-Modus.** Der
  Koerper nimmt den Schaden fuer den Spieler, ein Kill auf den Koerper bringt
  also den Spieler um. `server.sh cleanup` nimmt die Koerper aus.
- **`bot.creative.flyTo` laesst sich nicht abbrechen.** Die Schleife schiebt
  `bot.entity.position` selbst weiter und setzt dabei jedes Mal die
  Schwerkraft auf 0, `stopFlying()` greift mitten im Flug nicht. Ein blosser
  Timeout drumherum beendet nur das Warten - die Schleife fliegt weiter und
  zerrt den Bot spaeter quer durch die Welt. `bot.js` bringt darum dieselbe
  Mechanik noch einmal mit, aber mit Abbruch, und meldet `blockiert`, wenn
  der Bot netto stehen bleibt, weil das Plugin ihn aufhaelt.
- **`bot.entity.position` ist die Sicht des Clients** und laeuft optimistisch
  voraus. Die Wahrheit holt `bot.sh cmd truth` ueber
  `/data get entity @s Pos`. Dafuer braucht der Bot op.
- **`/fillbiome`:** hoechstens 32768 Bloecke, sonst *Too many blocks in the
  specified volume*. Und y muss innerhalb der Weltgrenzen liegen - der Boden
  der Flachwelt ist -64, `~-8` bei y=-60 endet in *That position is out of
  this world!*.
- **Kein `pkill -f <muster>`,** wenn das Muster in der eigenen Kommandozeile
  steht - das killt die eigene Shell. Die Skripte arbeiten nur mit
  PID-Dateien.
- **Bot-Namen stehen fest** in `lib.sh` und werden als Argument uebergeben,
  nie ueber eine Umgebungsvariable: `BOT_NAME=... nohup ...` verliert die
  Variable, der zweite Bot joint unter dem Namen des ersten und wirft ihn mit
  `duplicate_login` hinaus.
- **`target/` ist im Repo eingecheckt.** `setup.sh build` holt es nach dem
  Bauen sofort zurueck (`git restore --source=HEAD --worktree target/` und
  `git clean -fd target/`). Committet wird nur `src/`.
- **Ausgabeordner sauber halten.** `setup.sh` loescht die Ordner der
  Pruefprogramme vor jedem Lauf, sonst verdeckt eine alte Klassenkopie die
  frisch gebaute.
- **Der Halter der Konsolen-FIFO braucht eigene Dateideskriptoren.** Erbt er
  stdout und stderr des Aufrufers, haelt er dessen Pipe offen und
  `server.sh start` kehrt nie zurueck.

## Versionen

Alle an einer Stelle, in `lib.sh`: Paper 26.2 build 121, paper-api
26.2.build.121-stable, Protokoll 776, JDK 25. Das Server-Jar kommt ueber
`https://fill.papermc.io/v3/...` - die alte `api.papermc.io` v2 ist
abgeschaltet.
