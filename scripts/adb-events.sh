#!/usr/bin/env bash
# Event injection of spec section 7.3. Usage: scripts/adb-events.sh <event> [args]
#   webhook <path> [json]   (needs TOKEN and optional PORT, default 8787)   notification [title] [body]
#   battery <level>         battery-reset        wifi on|off         geo <lon> <lat>      sms <number> <text> (emulator)
set -euo pipefail
source "$(dirname "$0")/adb-common.sh"
need_device
ev="${1:-}"; shift || true
case "$ev" in
  webhook)
    : "${TOKEN:?set TOKEN to the app token (Settings, Webhook) or a Flow token}"
    PORT="${PORT:-8787}"; path="${1:?webhook needs a path}"; default_body='{"a":1}'; body="${2:-$default_body}"
    $ADB forward "tcp:$PORT" "tcp:$PORT" >/dev/null
    curl -i -sS -H "Authorization: Bearer $TOKEN" -d "$body" "http://127.0.0.1:$PORT/hook/$path" ;;
  notification) sh cmd notification post -S bigtext -t "${1:-Title}" "tag$(date +%s)" "${2:-Body text}" ;;
  battery) sh dumpsys battery unplug; sh dumpsys battery set level "${1:-15}" ;;
  battery-reset) sh dumpsys battery reset ;;
  wifi) sh svc wifi "$([[ "${1:-off}" == on ]] && echo enable || echo disable)" ;;
  geo) $ADB emu geo fix "${1:?lon}" "${2:?lat}" ;;
  sms) $ADB emu sms send "${1:?number}" "${2:?text}" ;;
  *) die "unknown event: $ev" ;;
esac
