#!/usr/bin/env bash
# Gemeinsame Einstellungen und Helfer der CamFly-Testumgebung.
# Wird von den anderen Skripten mit "source" geladen und nicht selbst gestartet.

# --- Verzeichnisse ---------------------------------------------------------
TESTENV_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$TESTENV_DIR/.." && pwd)"

# Alles Heruntergeladene und Erzeugte liegt ausserhalb des Repos, damit
# target/ und src/ sauber bleiben.
TEST_HOME="${CAMFLY_TEST_HOME:-$HOME/camfly-testenv}"

CACHE_DIR="$TEST_HOME/cache"     # heruntergeladene Archive, ueberleben Neubauten
JDK_DIR="$TEST_HOME/jdk25"       # ausgepacktes JDK 25
LIB_DIR="$TEST_HOME/libs"        # paper-api und seine Abhaengigkeiten
OUT_DIR="$TEST_HOME/out"         # Ausgabe der Pruefprogramme, wird jedes Mal geleert
SERVER_DIR="$TEST_HOME/server"   # der Paper-Testserver
BOT_DIR="$TEST_HOME/bot"         # node_modules und Bot-Zustand
LOG_DIR="$TEST_HOME/logs"
RUN_DIR="$TEST_HOME/run"         # PID-Dateien und FIFOs

CONSOLE_FIFO="$RUN_DIR/console.fifo"
SERVER_PID_FILE="$RUN_DIR/server.pid"
HOLDER_PID_FILE="$RUN_DIR/console-holder.pid"
SERVER_LOG="$LOG_DIR/server.log"

# --- Versionen -------------------------------------------------------------
# Eine Stelle zum Hochziehen, wenn Paper eine neue Version baut.
PAPER_MC_VERSION="26.2"
PAPER_BUILD="121"
PAPER_API_VERSION="26.2.build.121-stable"
PROTOCOL_VERSION="776"            # 26.2; minecraft-data kennt nur bis 26.1 (775)
JDK_MAJOR="25"

# Sollmarke des Jar-Pruefers: so viele Methodenaufrufe in die Server-API macht
# das Plugin. Konstruktoren und Feldzugriffe zaehlt der Pruefer getrennt dazu.
# Weicht die Zahl ab, ist das kein Fehler, sondern ein Hinweis, dass sich der
# Code veraendert hat.
APICHECK_EXPECTED="${APICHECK_EXPECTED:-346}"

# Bot-Namen stehen fest im Skript. Sie ueber eine Umgebungsvariable zu
# setzen ging schon schief: Die Variable ging bei nohup verloren, der zweite
# Bot jointe unter dem Namen des ersten und warf ihn mit duplicate_login raus.
BOT_MAIN="CamTester"
BOT_SECOND="CamWatcher"

# Abhaengigkeiten fuer die javac-Gegenprobe gegen paper-api.
# Format je Zeile: <Dateiname> <URL>
MAVEN_CENTRAL="https://repo1.maven.org/maven2"
paper_api_deps() {
  cat <<DEPS
guava-33.6.0-jre.jar $MAVEN_CENTRAL/com/google/guava/guava/33.6.0-jre/guava-33.6.0-jre.jar
annotations-26.0.2.jar $MAVEN_CENTRAL/org/jetbrains/annotations/26.0.2/annotations-26.0.2.jar
joml-1.10.8.jar $MAVEN_CENTRAL/org/joml/joml/1.10.8/joml-1.10.8.jar
snakeyaml-2.6.jar $MAVEN_CENTRAL/org/yaml/snakeyaml/2.6/snakeyaml-2.6.jar
bungeecord-chat-1.21-R0.4.jar $MAVEN_CENTRAL/net/md-5/bungeecord-chat/1.21-R0.4/bungeecord-chat-1.21-R0.4.jar
bungeecord-serializer-1.21-R0.4.jar $MAVEN_CENTRAL/net/md-5/bungeecord-serializer/1.21-R0.4/bungeecord-serializer-1.21-R0.4.jar
adventure-api-5.2.0.jar $MAVEN_CENTRAL/net/kyori/adventure-api/5.2.0/adventure-api-5.2.0.jar
adventure-key-5.2.0.jar $MAVEN_CENTRAL/net/kyori/adventure-key/5.2.0/adventure-key-5.2.0.jar
adventure-text-minimessage-5.2.0.jar $MAVEN_CENTRAL/net/kyori/adventure-text-minimessage/5.2.0/adventure-text-minimessage-5.2.0.jar
adventure-text-logger-slf4j-5.2.0.jar $MAVEN_CENTRAL/net/kyori/adventure-text-logger-slf4j/5.2.0/adventure-text-logger-slf4j-5.2.0.jar
examination-api-1.3.0.jar $MAVEN_CENTRAL/net/kyori/examination-api/1.3.0/examination-api-1.3.0.jar
slf4j-api-2.0.17.jar $MAVEN_CENTRAL/org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar
DEPS
}

# --- Ausgabe ---------------------------------------------------------------
if [ -t 1 ]; then C_R=$'\e[31m'; C_G=$'\e[32m'; C_Y=$'\e[33m'; C_B=$'\e[1m'; C_0=$'\e[0m'
else C_R=""; C_G=""; C_Y=""; C_B=""; C_0=""; fi

section() { printf '\n%s=== %s ===%s\n' "$C_B" "$*" "$C_0"; }
log()     { printf '%s[ok]%s %s\n'   "$C_G" "$C_0" "$*"; }
info()    { printf '     %s\n' "$*"; }
warn()    { printf '%s[!!]%s %s\n'   "$C_Y" "$C_0" "$*" >&2; }
die()     { printf '%s[xx]%s %s\n'   "$C_R" "$C_0" "$*" >&2; exit 1; }

# --- Helfer ----------------------------------------------------------------

# fetch <url> <ziel> [beschreibung]
# Laedt nur, wenn die Datei noch fehlt oder leer ist. Bricht bei Fehler ab.
fetch() {
  local url="$1" dest="$2" what="${3:-$(basename "$2")}"
  if [ -s "$dest" ]; then
    info "$what liegt schon da"
    return 0
  fi
  mkdir -p "$(dirname "$dest")"
  info "lade $what"
  local try
  for try in 1 2 3 4; do
    if curl -sSL --fail --retry 2 --connect-timeout 20 --max-time 900 -o "$dest.part" "$url"; then
      mv "$dest.part" "$dest"
      return 0
    fi
    rm -f "$dest.part"
    warn "Versuch $try fuer $what fehlgeschlagen, neuer Versuch"
    sleep $((try * 2))
  done
  die "$what liess sich nicht laden: $url"
}

# Der Klassenpfad aus paper-api und allen Abhaengigkeiten.
paper_classpath() {
  local cp="$LIB_DIR/paper-api-$PAPER_API_VERSION.jar"
  local name _url
  while read -r name _url; do
    [ -n "$name" ] || continue
    cp="$cp:$LIB_DIR/$name"
  done < <(paper_api_deps)
  printf '%s' "$cp"
}

# Laeuft der Prozess aus dieser PID-Datei noch?
pid_alive() {
  local file="$1"
  [ -f "$file" ] || return 1
  local pid; pid="$(cat "$file" 2>/dev/null || true)"
  [ -n "$pid" ] || return 1
  kill -0 "$pid" 2>/dev/null
}

# Prozess aus einer PID-Datei beenden. Nie ueber pkill -f mit einem Muster,
# das auch in der eigenen Kommandozeile steht - das killt die eigene Shell.
kill_pidfile() {
  local file="$1" name="$2" grace="${3:-10}"
  pid_alive "$file" || { rm -f "$file"; return 0; }
  local pid; pid="$(cat "$file")"
  kill "$pid" 2>/dev/null || true
  local i=0
  while kill -0 "$pid" 2>/dev/null && [ "$i" -lt "$grace" ]; do sleep 1; i=$((i + 1)); done
  if kill -0 "$pid" 2>/dev/null; then
    warn "$name ($pid) reagiert nicht, harter Abbruch"
    kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$file"
}

# Wartet, bis ein Muster in einer Datei auftaucht. wait_for <datei> <muster> <sekunden>
wait_for() {
  local file="$1" pattern="$2" secs="$3" i=0
  while [ "$i" -lt "$secs" ]; do
    [ -f "$file" ] && grep -qE "$pattern" "$file" && return 0
    sleep 1; i=$((i + 1))
  done
  return 1
}
