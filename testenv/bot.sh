#!/usr/bin/env bash
# Steuert die mineflayer-Testbots.
#
#   ./testenv/bot.sh start                 startet CamTester
#   ./testenv/bot.sh start CamWatcher      startet den zweiten Bot
#   ./testenv/bot.sh cmd chat /cam         schickt /cam als CamTester
#   ./testenv/bot.sh cmd CamWatcher near 8
#   ./testenv/bot.sh log 30
#   ./testenv/bot.sh stop all
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# Ist das erste Argument einer der bekannten Bot-Namen, gilt es als Name,
# sonst gehoert es schon zum Befehl. Namen stehen fest - sie ueber eine
# Umgebungsvariable zu setzen ging schon schief.
pick_name() {
  case "${1:-}" in
    "$BOT_MAIN"|"$BOT_SECOND") printf '%s' "$1" ;;
    *) printf '%s' "$BOT_MAIN" ;;
  esac
}
is_name() { case "${1:-}" in "$BOT_MAIN"|"$BOT_SECOND") return 0 ;; *) return 1 ;; esac; }

bot_pid_file() { printf '%s/bot-%s.pid' "$RUN_DIR" "$1"; }
bot_fifo()     { printf '%s/bot-%s.fifo' "$RUN_DIR" "$1"; }
bot_log()      { printf '%s/bot-%s.log' "$LOG_DIR" "$1"; }

# Nur die eigenen Zeilen des Bots. Alles andere im Log sind Meldungen der
# Bibliotheken - vor allem die "partial packet"-Warnungen, die daher kommen,
# dass wir minecraft-data von 26.1 fuer 26.2 benutzen. Sie sind harmlos, aber
# sie verdecken alles. Mit rawlog gibt es das ganze Log.
own_lines() { grep -E '^[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3} ' "$1" || true; }

start() {
  local name="$1"
  if pid_alive "$(bot_pid_file "$name")"; then
    log "$name laeuft schon (PID $(cat "$(bot_pid_file "$name")"))"
    return 0
  fi
  [ -d "$BOT_DIR/node_modules/mineflayer" ] || die "mineflayer fehlt - erst ./testenv/setup.sh laufen lassen"
  mkdir -p "$RUN_DIR" "$LOG_DIR"

  # Die Skripte liegen im Repo, laufen muessen sie neben node_modules.
  cp "$TESTENV_DIR/bot/bot.js" "$TESTENV_DIR/bot/patch26_2.js" "$BOT_DIR/"

  local fifo; fifo="$(bot_fifo "$name")"
  rm -f "$fifo"; mkfifo "$fifo"
  : > "$(bot_log "$name")"

  # Der Name steht als Argument in der Kommandozeile, nicht in einer
  # Umgebungsvariablen: BOT_NAME=... nohup ... verliert die Variable, der
  # zweite Bot joint dann unter dem Namen des ersten und wirft ihn mit
  # duplicate_login hinaus.
  (
    cd "$BOT_DIR"
    exec node bot.js "$name" 127.0.0.1 25565 "$fifo"
  ) > "$(bot_log "$name")" 2>&1 &
  echo $! > "$(bot_pid_file "$name")"

  if wait_for "$(bot_log "$name")" '^\S+ READY ' 45; then
    log "$name ist im Spiel"
    grep -E '^\S+ (LOGIN|SPAWN) ' "$(bot_log "$name")" | sed 's/^/     /'
  else
    warn "$name kam nicht ins Spiel. Log:"
    tail -n 25 "$(bot_log "$name")" >&2
    return 1
  fi
}

send() {
  local name="$1"; shift
  local fifo; fifo="$(bot_fifo "$name")"
  pid_alive "$(bot_pid_file "$name")" || die "$name laeuft nicht"
  [ -p "$fifo" ] || die "FIFO von $name fehlt"
  printf '%s\n' "$*" > "$fifo"
}

stop_one() {
  local name="$1"
  if pid_alive "$(bot_pid_file "$name")"; then
    printf 'quit\n' > "$(bot_fifo "$name")" 2>/dev/null || true
    sleep 1
  fi
  kill_pidfile "$(bot_pid_file "$name")" "Bot $name" 5
  rm -f "$(bot_fifo "$name")"
  info "$name beendet"
}

case "${1:-}" in
  start)
    shift; start "$(pick_name "${1:-}")" ;;
  cmd)
    shift
    name="$BOT_MAIN"
    if is_name "${1:-}"; then name="$1"; shift; fi
    send "$name" "$@" ;;
  log)
    shift
    name="$BOT_MAIN"
    if is_name "${1:-}"; then name="$1"; shift; fi
    own_lines "$(bot_log "$name")" | tail -n "${1:-40}" ;;
  rawlog)
    shift
    name="$BOT_MAIN"
    if is_name "${1:-}"; then name="$1"; shift; fi
    tail -n "${1:-40}" "$(bot_log "$name")" ;;
  follow)
    shift; tail -f "$(bot_log "$(pick_name "${1:-}")")" ;;
  wait)
    shift
    name="$BOT_MAIN"
    if is_name "${1:-}"; then name="$1"; shift; fi
    wait_for "$(bot_log "$name")" "$1" "${2:-15}" || die "Muster kam nicht: $1" ;;
  status)
    for n in "$BOT_MAIN" "$BOT_SECOND"; do
      if pid_alive "$(bot_pid_file "$n")"; then log "$n laeuft (PID $(cat "$(bot_pid_file "$n")"))"; else info "$n laeuft nicht"; fi
    done ;;
  stop)
    shift
    if [ "${1:-}" = "all" ] || [ -z "${1:-}" ]; then
      stop_one "$BOT_MAIN"; stop_one "$BOT_SECOND"
    else
      stop_one "$(pick_name "$1")"
    fi ;;
  *)
    cat <<USAGE
Aufruf: bot.sh <befehl> [botname] [...]
  start [name]              Bot starten ($BOT_MAIN oder $BOT_SECOND)
  cmd [name] <befehl>       Befehl an den Bot: chat, pos, truth, fly, stopfly,
                            look, state, near, ping, quit
  log [name] [n]            die letzten n Zeilen des Bots
  rawlog [name] [n]         dasselbe mit den Meldungen der Bibliotheken
  follow [name]             Log mitlesen
  wait [name] <muster> [s]  warten, bis das Muster im Log steht
  status                    wer laeuft
  stop [name|all]           beenden
USAGE
    exit 1 ;;
esac
