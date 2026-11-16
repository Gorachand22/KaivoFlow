#!/usr/bin/env bash
# Runs the reliability self-test suite (spec 6.5) from the host and prints the outcomes.
# Usage: scripts/adb-selftest.sh [ST1,ST2,...]   default ST1,ST2,ST4,ST5. ST1 takes 90 s, ST3 up to 16 minutes, ST6 needs the screen off.
set -euo pipefail
source "$(dirname "$0")/adb-common.sh"
need_device
ids="${1:-ST1,ST2,ST4,ST5}"
diag DIAG_SELFTEST --es ids "$ids"
echo "waiting for the long tests (ST1 needs 100 s) ..."
for _ in $(seq 1 "${ST_POLLS:-12}"); do
  sleep 10
  out="$(DIAG_WAIT=1 diag DIAG_EVALUATE)"
  echo "$out"
  grep -q " Pending " <<<"$out" || break
done
pull_dump "${DUMP_OUT:-./reliability-dump.json}"
