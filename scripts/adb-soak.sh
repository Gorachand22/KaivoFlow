#!/usr/bin/env bash
# T-SOAK helper (spec 7.4, ST7): starts the 8 hour overnight soak, leaves the phone alone with the screen off and unplugged,
# and after the run prints the report. Usage: scripts/adb-soak.sh start | report
set -euo pipefail
source "$(dirname "$0")/adb-common.sh"
need_device
case "${1:-}" in
  start)
    diag DIAG_SELFTEST --es ids ST7
    "$(dirname "$0")/adb-conditions.sh" unplug
    "$(dirname "$0")/adb-conditions.sh" screen-off
    echo "Soak started at $(date). Do not touch the phone for 8 hours, then run: scripts/adb-soak.sh report" ;;
  report)
    "$(dirname "$0")/adb-conditions.sh" plug
    "$(dirname "$0")/adb-conditions.sh" screen-on
    DIAG_WAIT=2 diag DIAG_EVALUATE
    pull_dump "${DUMP_OUT:-./reliability-dump.json}"
    echo "Record device, OS version and the ST7 line above in docs/specs/P2-device-results.md" ;;
  *) die "usage: adb-soak.sh start|report" ;;
esac
