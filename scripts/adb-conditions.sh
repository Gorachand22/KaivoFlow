#!/usr/bin/env bash
# Condition commands of spec section 7.2. Usage: scripts/adb-conditions.sh <condition> [on|off]
#   screen-off | screen-on | unplug | plug | deep-doze | doze-off | bucket <name> | battery-exempt on|off |
#   bg-restricted on|off | exact-alarm on|off | crash | kill | force-stop | reboot | install <apk> | boot-broadcast |
#   tz-broadcast | status
set -euo pipefail
source "$(dirname "$0")/adb-common.sh"
need_device
cmd="${1:-status}"; arg="${2:-}"
case "$cmd" in
  screen-off) sh input keyevent KEYCODE_SLEEP ;;
  screen-on) sh input keyevent KEYCODE_WAKEUP; sh wm dismiss-keyguard ;;
  unplug) sh dumpsys battery unplug ;;
  plug) sh dumpsys battery reset ;;
  deep-doze) sh dumpsys battery unplug; sh dumpsys deviceidle force-idle; sh dumpsys deviceidle get deep ;;
  doze-off) sh dumpsys deviceidle unforce; sh dumpsys battery reset ;;
  bucket) [[ -n "$arg" ]] || die "bucket needs one of active working_set frequent rare restricted"; sh am set-standby-bucket "$PKG" "$arg"; sh am get-standby-bucket "$PKG" ;;
  battery-exempt) if [[ "$arg" == on ]]; then sh dumpsys deviceidle whitelist "+$PKG"; else sh dumpsys deviceidle whitelist "-$PKG"; fi; sh dumpsys deviceidle whitelist | grep -c "$PKG" || true ;;
  bg-restricted) sh cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND "$([[ "$arg" == on ]] && echo deny || echo allow)" ;;
  exact-alarm) sh cmd appops set "$PKG" SCHEDULE_EXACT_ALARM "$([[ "$arg" == off ]] && echo deny || echo allow)" ;;
  crash) sh am crash "$PKG" ;;
  kill) sh am kill "$PKG" ;;
  force-stop) sh am force-stop "$PKG" ;;
  reboot) $ADB reboot; wait_boot; echo "booted; unlock the phone, then run: scripts/adb-conditions.sh status" ;;
  install) [[ -f "$arg" ]] || die "apk not found: $arg"; $ADB install -r "$arg" ;;
  boot-broadcast) sh am broadcast -a android.intent.action.BOOT_COMPLETED -p "$PKG" ;;
  tz-broadcast) sh am broadcast -a android.intent.action.TIMEZONE_CHANGED -p "$PKG" ;;
  status)
    echo "--- standby bucket"; sh am get-standby-bucket "$PKG" || true
    echo "--- idle whitelist"; sh dumpsys deviceidle whitelist | grep "$PKG" || echo "not whitelisted"
    echo "--- appops"; sh cmd appops get "$PKG" RUN_ANY_IN_BACKGROUND SCHEDULE_EXACT_ALARM || true
    echo "--- exit info"; sh dumpsys activity exit-info "$PKG" | head -40 || true
    echo "--- foreground service"; sh dumpsys activity services "$PKG" | grep -i -m3 "isForeground\|foregroundServiceType" || echo "engine service not running" ;;
  *) die "unknown condition: $cmd" ;;
esac
