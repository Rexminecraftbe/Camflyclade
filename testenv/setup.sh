#!/usr/bin/env bash
# Baut die CamFly-Testumgebung von Null auf.
#
#   ./testenv/setup.sh                 alles: Werkzeuge, Bauen, beide
#                                      API-Pruefungen, Server hoch, Bot drin
#   ./testenv/setup.sh build apicheck  nur einzelne Abschnitte
#   ./testenv/setup.sh down            Server und Bots beenden
#   ./testenv/setup.sh clean           alles Heruntergeladene wegwerfen
#
# Alles Erzeugte liegt unter $CAMFLY_TEST_HOME (Standard ~/camfly-testenv),
# also ausserhalb des Repos. Am Repo selbst aendert das Skript nichts ausser
# target/, und das stellt es nach jedem Bauen wieder her.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# ---------------------------------------------------------------------------
# 1. Werkzeuge
# ---------------------------------------------------------------------------
# JDK 25 ist Pflicht. Installiert ist meist nur Java 21, das Projekt baut mit
# release 25 und die Klassen von paper-api haben Version 69.
phase_toolchain() {
  section "1. Werkzeuge"
  mkdir -p "$CACHE_DIR" "$LIB_DIR" "$SERVER_DIR" "$LOG_DIR" "$RUN_DIR" "$BOT_DIR"

  if [ -x "$JDK_DIR/bin/javac" ]; then
    info "JDK: $("$JDK_DIR/bin/java" -version 2>&1 | grep -v "Picked up" | head -1)"
  else
    fetch "https://api.adoptium.net/v3/binary/latest/$JDK_MAJOR/ga/linux/x64/jdk/hotspot/normal/eclipse" \
          "$CACHE_DIR/jdk$JDK_MAJOR.tar.gz" "JDK $JDK_MAJOR"
    rm -rf "$JDK_DIR" "$CACHE_DIR/jdk-unpack"
    mkdir -p "$CACHE_DIR/jdk-unpack"
    tar -xzf "$CACHE_DIR/jdk$JDK_MAJOR.tar.gz" -C "$CACHE_DIR/jdk-unpack"
    mv "$(find "$CACHE_DIR/jdk-unpack" -maxdepth 1 -mindepth 1 -type d | head -1)" "$JDK_DIR"
    rmdir "$CACHE_DIR/jdk-unpack" 2>/dev/null || true
    log "JDK $JDK_MAJOR ausgepackt: $("$JDK_DIR/bin/java" -version 2>&1 | grep -v "Picked up" | head -1)"
  fi
  "$JDK_DIR/bin/javac" -version 2>&1 | grep -q " $JDK_MAJOR" || die "Das ausgepackte JDK ist nicht Version $JDK_MAJOR"

  # paper-api und die Abhaengigkeiten fuer die Gegenprobe. spigot-api holt
  # Maven selbst, das braucht hier niemand von Hand zu laden.
  fetch "https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/$PAPER_API_VERSION/paper-api-$PAPER_API_VERSION.jar" \
        "$LIB_DIR/paper-api-$PAPER_API_VERSION.jar" "paper-api $PAPER_API_VERSION"
  local name url
  while read -r name url; do
    [ -n "$name" ] || continue
    fetch "$url" "$LIB_DIR/$name" "$name"
  done < <(paper_api_deps)

  # Das Server-Jar. Die alte api.papermc.io v2 ist abgeschaltet, die
  # Download-Adresse steht in der v3-Antwort von fill.papermc.io.
  if [ -s "$SERVER_DIR/paper.jar" ]; then
    info "paper.jar liegt schon da"
  else
    local meta jar_url
    meta="$(curl -sSL --fail "https://fill.papermc.io/v3/projects/paper/versions/$PAPER_MC_VERSION/builds/$PAPER_BUILD")" \
      || die "fill.papermc.io antwortet nicht"
    jar_url="$(printf '%s' "$meta" | jq -r '.downloads["server:default"].url')"
    [ -n "$jar_url" ] && [ "$jar_url" != "null" ] || die "Keine Download-Adresse in der Antwort von fill.papermc.io"
    fetch "$jar_url" "$SERVER_DIR/paper.jar" "Paper $PAPER_MC_VERSION build $PAPER_BUILD"
  fi

  # mineflayer
  if [ -d "$BOT_DIR/node_modules/mineflayer" ]; then
    info "mineflayer liegt schon da ($(node -e "process.stdout.write(require('$BOT_DIR/node_modules/mineflayer/package.json').version)"))"
  else
    command -v npm >/dev/null || die "npm fehlt"
    cat > "$BOT_DIR/package.json" <<'PKG'
{
  "name": "camfly-testbot",
  "version": "1.0.0",
  "private": true,
  "description": "Testbot fuer CamFly",
  "dependencies": { "mineflayer": "^4.34.0" }
}
PKG
    ( cd "$BOT_DIR" && npm install --no-audit --no-fund --loglevel=error >/dev/null )
    log "mineflayer installiert"
  fi
  cp "$TESTENV_DIR/bot/bot.js" "$TESTENV_DIR/bot/patch26_2.js" "$BOT_DIR/"
  ( cd "$BOT_DIR" && node -e "require('./patch26_2.js'); require('mineflayer'); \
      const v=require('mineflayer/lib/version.js'); \
      if (v.latestSupportedVersion !== '$PAPER_MC_VERSION') { console.error('Patch griff nicht'); process.exit(1) }" ) \
    2>/dev/null || die "Der 26.2-Patch fuer mineflayer greift nicht mehr - patch26_2.js pruefen"
  log "mineflayer spricht $PAPER_MC_VERSION (Protokoll $PROTOCOL_VERSION)"
}

# ---------------------------------------------------------------------------
# 2. Bauen
# ---------------------------------------------------------------------------
phase_build() {
  section "2. Plugin bauen"
  [ -x "$JDK_DIR/bin/javac" ] || die "JDK $JDK_MAJOR fehlt - Abschnitt toolchain zuerst"
  command -v mvn >/dev/null || die "mvn fehlt"

  # Alte Klassenkopien weg, sonst verdeckt eine davon die frisch gebaute.
  rm -rf "$OUT_DIR/plugin-classes" "$OUT_DIR/plugin.jar"
  mkdir -p "$OUT_DIR"

  ( cd "$REPO_DIR" && JAVA_HOME="$JDK_DIR" mvn -B clean package ) > "$LOG_DIR/maven.log" 2>&1 || {
    warn "mvn package ist gescheitert. Letzte Zeilen:"
    tail -n 40 "$LOG_DIR/maven.log" >&2
    die "Bauen fehlgeschlagen - alles in $LOG_DIR/maven.log"
  }

  local jar
  jar="$(find "$REPO_DIR/target" -maxdepth 1 -name '*.jar' ! -name 'original-*' | head -1)"
  [ -n "$jar" ] || die "Kein Jar in target/"
  cp "$jar" "$OUT_DIR/plugin.jar"
  cp -r "$REPO_DIR/target/classes" "$OUT_DIR/plugin-classes"
  log "gebaut: $(basename "$jar") ($(du -h "$OUT_DIR/plugin.jar" | cut -f1)), $(find "$OUT_DIR/plugin-classes" -name '*.class' | wc -l) Klassen"

  # target/ ist im Repo eingecheckt. Der Bau hat es veraendert, also sofort
  # zurueckholen - committet wird nur src/.
  if [ -d "$REPO_DIR/.git" ]; then
    ( cd "$REPO_DIR" && git restore --source=HEAD --worktree target/ && git clean -qfd target/ )
    log "target/ ist wieder wie im Repo"
  fi
}

# ---------------------------------------------------------------------------
# 3. Gegenprobe: derselbe Quelltext gegen paper-api
# ---------------------------------------------------------------------------
# Das pom baut gegen spigot-api, der Server laeuft auf Paper. Was nur in
# spigot-api steht, faellt sonst erst im Spiel als NoSuchMethodError auf.
phase_papercheck() {
  section "3. Quelltext gegen paper-api uebersetzen"
  [ -s "$LIB_DIR/paper-api-$PAPER_API_VERSION.jar" ] || die "paper-api fehlt - Abschnitt toolchain zuerst"
  rm -rf "$OUT_DIR/paper-classes"
  mkdir -p "$OUT_DIR/paper-classes"

  local sources
  sources="$(mktemp)"
  find "$REPO_DIR/src/main/java" -name '*.java' > "$sources"
  if "$JDK_DIR/bin/javac" --release "$JDK_MAJOR" -nowarn -proc:none \
        -cp "$(paper_classpath)" -d "$OUT_DIR/paper-classes" "@$sources" \
        > "$LOG_DIR/papercheck.log" 2>&1; then
    log "$(wc -l < "$sources" | tr -d ' ') Quelldateien uebersetzen auch gegen paper-api"
    rm -f "$sources"
  else
    warn "Der Quelltext laesst sich NICHT gegen paper-api uebersetzen:"
    grep -E '^(/|.*error:)' "$LOG_DIR/papercheck.log" | head -40 >&2
    rm -f "$sources"
    return 1
  fi
}

# ---------------------------------------------------------------------------
# 4. Pruefer ueber das fertige Jar
# ---------------------------------------------------------------------------
phase_apicheck() {
  section "4. Jar-Pruefer gegen paper-api"
  [ -d "$OUT_DIR/plugin-classes" ] || die "Keine gebauten Klassen - Abschnitt build zuerst"
  rm -rf "$OUT_DIR/apicheck"
  mkdir -p "$OUT_DIR/apicheck"
  "$JDK_DIR/bin/javac" -nowarn -d "$OUT_DIR/apicheck" "$TESTENV_DIR/apicheck/ApiCheck.java" \
    > "$LOG_DIR/apicheck-build.log" 2>&1 || {
      tail -n 20 "$LOG_DIR/apicheck-build.log" >&2
      die "ApiCheck liess sich nicht uebersetzen"
    }
  "$JDK_DIR/bin/java" -cp "$OUT_DIR/apicheck" ApiCheck \
      "$JDK_DIR/bin/javap" "$OUT_DIR/plugin-classes" "$(paper_classpath)" "$APICHECK_EXPECTED" \
      | tee "$LOG_DIR/apicheck.log"
  return "${PIPESTATUS[0]}"
}

# ---------------------------------------------------------------------------
# 5. Testserver einrichten und starten
# ---------------------------------------------------------------------------
phase_server() {
  section "5. Paper-Testserver"
  [ -s "$SERVER_DIR/paper.jar" ] || die "paper.jar fehlt - Abschnitt toolchain zuerst"
  [ -s "$OUT_DIR/plugin.jar" ] || die "Kein gebautes Plugin - Abschnitt build zuerst"

  echo "eula=true" > "$SERVER_DIR/eula.txt"

  # Klein und flach, damit der Server in Sekunden oben ist.
  cat > "$SERVER_DIR/server.properties" <<'PROPS'
online-mode=false
level-type=flat
view-distance=2
simulation-distance=2
spawn-protection=0
difficulty=peaceful
allow-flight=true
enforce-secure-profile=false
max-players=10
motd=CamFly Testserver
level-name=world
server-port=25565
sync-chunk-writes=false
PROPS

  mkdir -p "$SERVER_DIR/plugins/CamFly"
  cp "$OUT_DIR/plugin.jar" "$SERVER_DIR/plugins/CamFly.jar"

  # Die Konfiguration vor dem ersten Start hinlegen, mit abgeschalteten
  # Partikeln: Ein Bot in Sichtweite eines Cam-Spielers stirbt sonst am
  # Partikel-Paket, weil minecraft-data 26.1 die 26.2-IDs nicht kennt.
  local tmp; tmp="$(mktemp -d)"
  ( cd "$tmp" && unzip -oq "$OUT_DIR/plugin.jar" config.yml )
  awk '
    /^camera-particles:/ { inblock = 1 }
    /^[A-Za-z]/ && !/^camera-particles:/ { inblock = 0 }
    inblock && /^[[:space:]]*particles-per-tick:/ { sub(/:.*/, ": 0") }
    { print }
  ' "$tmp/config.yml" > "$SERVER_DIR/plugins/CamFly/config.yml"
  rm -rf "$tmp"
  awk '
    /^camera-particles:/ { inblock = 1 }
    /^[A-Za-z]/ && !/^camera-particles:/ { inblock = 0 }
    inblock && /^[[:space:]]*particles-per-tick:[[:space:]]*0[[:space:]]*$/ { found = 1 }
    END { exit found ? 0 : 1 }
  ' "$SERVER_DIR/plugins/CamFly/config.yml" \
    || die "particles-per-tick liess sich nicht auf 0 setzen - Aufbau der config.yml hat sich geaendert"
  log "config.yml abgelegt, camera-particles.particles-per-tick = 0"

  "$TESTENV_DIR/server.sh" start

  if grep -qE 'CamFly.*(Enabling|Enabled)' "$SERVER_LOG"; then
    log "CamFly ist aktiviert: $(grep -m1 -E 'Enabling CamFly' "$SERVER_LOG" | sed 's/^.*\]: //')"
  else
    warn "CamFly taucht im Start-Log nicht als aktiviert auf"
  fi
}

# ---------------------------------------------------------------------------
# 6. Bot ins Spiel
# ---------------------------------------------------------------------------
phase_bot() {
  section "6. Testbot"
  pid_alive "$SERVER_PID_FILE" || die "Server laeuft nicht - Abschnitt server zuerst"
  "$TESTENV_DIR/bot.sh" start "$BOT_MAIN"
  # /cam darf jeder, das ist Standardrecht. Fuer /data, /fillbiome und /tp
  # braucht der Bot op - ohne die sieht man nicht, wo er wirklich steht.
  "$TESTENV_DIR/server.sh" cmd "op $BOT_MAIN"
  sleep 1
}

# ---------------------------------------------------------------------------
phase_down() {
  section "Abbauen"
  "$TESTENV_DIR/bot.sh" stop all || true
  "$TESTENV_DIR/server.sh" stop || true
}

phase_clean() {
  phase_down || true
  section "Aufraeumen"
  rm -rf "$OUT_DIR" "$SERVER_DIR" "$LOG_DIR" "$RUN_DIR"
  info "Behalten werden $CACHE_DIR, $JDK_DIR, $LIB_DIR und $BOT_DIR/node_modules."
  info "Fuer wirklich alles: rm -rf $TEST_HOME"
}

phase_status() {
  section "Stand"
  info "Testordner: $TEST_HOME"
  [ -x "$JDK_DIR/bin/java" ] && info "JDK: $("$JDK_DIR/bin/java" -version 2>&1 | grep -v "Picked up" | head -1)" || info "JDK: fehlt"
  [ -s "$SERVER_DIR/paper.jar" ] && info "paper.jar: da" || info "paper.jar: fehlt"
  "$TESTENV_DIR/server.sh" status || true
  "$TESTENV_DIR/bot.sh" status || true
}

# ---------------------------------------------------------------------------
main() {
  local phases=("$@")
  if [ ${#phases[@]} -eq 0 ]; then
    phases=(toolchain build papercheck apicheck server bot)
  fi
  local failed=()
  local p
  for p in "${phases[@]}"; do
    case "$p" in
      toolchain|build|papercheck|apicheck|server|bot|down|clean|status)
        if ! "phase_$p"; then
          failed+=("$p")
          # Ein gescheiterter Bau macht alles Weitere sinnlos, eine
          # gescheiterte API-Pruefung nicht - die ist ein Befund, kein Abbruch.
          case "$p" in
            toolchain|build|server) die "Abschnitt $p ist gescheitert, hier geht es nicht weiter" ;;
            *) warn "Abschnitt $p meldet einen Befund, es geht weiter" ;;
          esac
        fi
        ;;
      *) die "Unbekannter Abschnitt: $p (erlaubt: toolchain build papercheck apicheck server bot down clean status)" ;;
    esac
  done

  section "Fertig"
  if [ ${#failed[@]} -gt 0 ]; then
    warn "Mit Befunden in: ${failed[*]}"
  else
    log "Alle Abschnitte ohne Befund"
  fi
  cat <<HINT

  Server-Konsole:  ./testenv/server.sh cmd <befehl>
  Server-Log:      ./testenv/server.sh log 50
  Bot steuern:     ./testenv/bot.sh cmd chat /cam
                   ./testenv/bot.sh cmd truth
                   ./testenv/bot.sh log 30
  Abbauen:         ./testenv/setup.sh down
HINT
  [ ${#failed[@]} -eq 0 ]
}

main "$@"
