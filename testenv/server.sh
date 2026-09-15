#!/usr/bin/env bash
# Steuert den Paper-Testserver: starten, Befehle schicken, Log lesen, stoppen.
#
#   ./testenv/server.sh start
#   ./testenv/server.sh cmd difficulty peaceful
#   ./testenv/server.sh log 50
#   ./testenv/server.sh wait "Done \(" 90
#   ./testenv/server.sh stop
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  cat <<USAGE
Aufruf: server.sh <befehl>
  start          startet den Server und wartet, bis er oben ist
  stop           schickt "stop" und wartet auf das Ende
  restart        stop, dann start
  status         laeuft er?
  cmd <text>     schickt eine Zeile in die Server-Konsole
  cleanup        raeumt Entities weg, ohne die Cam-Koerper zu toeten
  log [n]        die letzten n Zeilen (Standard 40)
  follow         Log mitlesen
  wait <muster> [sekunden]   wartet, bis das Muster im Log steht
USAGE
}

start() {
  if pid_alive "$SERVER_PID_FILE"; then
    log "Server laeuft schon (PID $(cat "$SERVER_PID_FILE"))"
    return 0
  fi
  [ -s "$SERVER_DIR/paper.jar" ] || die "paper.jar fehlt - erst ./testenv/setup.sh laufen lassen"
  [ -x "$JDK_DIR/bin/java" ] || die "JDK $JDK_MAJOR fehlt - erst ./testenv/setup.sh laufen lassen"
  mkdir -p "$RUN_DIR" "$LOG_DIR"

  # Eine FIFO als Konsole. Ohne sie gibt es keinen Weg, dem laufenden Server
  # noch "op" oder "difficulty peaceful" zu schicken.
  rm -f "$CONSOLE_FIFO"
  mkfifo "$CONSOLE_FIFO"

  # Ein Halter haelt die FIFO zum Schreiben offen. Sonst bekommt der Server
  # nach dem ersten echo ein EOF auf stdin und faehrt sich selbst herunter.
  # stdin und stderr des Halters muessen weg von hier: erbt er sie, haelt er
  # die Ausgabe-Pipe des Aufrufers offen und server.sh start kehrt nie zurueck.
  ( while true; do sleep 3600; done ) > "$CONSOLE_FIFO" 2>/dev/null < /dev/null &
  echo $! > "$HOLDER_PID_FILE"

  : > "$SERVER_LOG"
  (
    cd "$SERVER_DIR"
    exec "$JDK_DIR/bin/java" -Xms1G -Xmx2G -jar paper.jar --nogui
  ) < "$CONSOLE_FIFO" > "$SERVER_LOG" 2>&1 &
  echo $! > "$SERVER_PID_FILE"
  info "Server gestartet (PID $(cat "$SERVER_PID_FILE")), Log: $SERVER_LOG"

  if wait_for "$SERVER_LOG" 'Done \(' 120; then
    log "Server ist oben: $(grep -m1 -oE 'Done \([^)]*\)' "$SERVER_LOG")"
  else
    warn "Server war nach 120 s nicht oben. Letzte Zeilen:"
    tail -n 30 "$SERVER_LOG" >&2
    return 1
  fi

  # Ohne das erschlaegt ein Zombie den Bot, und danach verweigert das Plugin
  # /cam wegen der cam-safety-Sperre.
  send "difficulty peaceful"
  sleep 1
}

send() {
  pid_alive "$SERVER_PID_FILE" || die "Server laeuft nicht"
  [ -p "$CONSOLE_FIFO" ] || die "Konsolen-FIFO fehlt"
  printf '%s\n' "$*" > "$CONSOLE_FIFO"
  info "> $*"
}

stop() {
  if ! pid_alive "$SERVER_PID_FILE"; then
    info "Server laeuft nicht"
  else
    printf 'stop\n' > "$CONSOLE_FIFO" 2>/dev/null || true
    local pid i=0
    pid="$(cat "$SERVER_PID_FILE")"
    while kill -0 "$pid" 2>/dev/null && [ "$i" -lt 40 ]; do sleep 1; i=$((i + 1)); done
    # Kein pkill mit Muster: stuende das Muster in der eigenen Kommandozeile,
    # wuerde die eigene Shell mit abgeschossen.
    kill_pidfile "$SERVER_PID_FILE" "Paper-Server" 10
    log "Server beendet"
  fi
  kill_pidfile "$HOLDER_PID_FILE" "FIFO-Halter" 3
  rm -f "$CONSOLE_FIFO"
}

# Raeumt herumstehende Entities weg, laesst aber die Koerper des Kamera-Modus
# stehen. "kill @e[type=!minecraft:player]" ist hier toedlich: Der Koerper
# eines Spielers im Cam-Modus nimmt den Schaden fuer ihn, ein Kill auf den
# Koerper bringt also den Spieler um.
cleanup_entities() {
  send "kill @e[type=!minecraft:player,type=!minecraft:armor_stand,type=!minecraft:mannequin]"
}

case "${1:-}" in
  start)   start ;;
  stop)    stop ;;
  restart) stop; start ;;
  status)  if pid_alive "$SERVER_PID_FILE"; then log "laeuft (PID $(cat "$SERVER_PID_FILE"))"; else warn "laeuft nicht"; exit 1; fi ;;
  cmd)     shift; send "$@" ;;
  cleanup) cleanup_entities ;;
  log)     tail -n "${2:-40}" "$SERVER_LOG" ;;
  follow)  tail -f "$SERVER_LOG" ;;
  wait)    shift; wait_for "$SERVER_LOG" "$1" "${2:-30}" || die "Muster kam nicht: $1" ;;
  *)       usage; exit 1 ;;
esac
