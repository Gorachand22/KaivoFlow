#!/usr/bin/env bash
# Shared helpers for the device test plan (docs/specs/P2-always-on-engine.md section 7). Source this file, do not run it.
# PKG defaults to the debug build. Set PKG=com.kaivoflow.app for the release package (the DIAG receiver exists in debug only).
PKG="${PKG:-com.kaivoflow.app.debug}"
ADB="${ADB:-adb}"
[[ -n "${SERIAL:-}" ]] && ADB="$ADB -s $SERIAL"

die() { echo "error: $*" >&2; exit 1; }
need_device() { $ADB get-state >/dev/null 2>&1 || die "no device reachable through adb"; }
sh() { $ADB shell "$@"; }

# diag <ACTION> [am extras...]: sends a DiagReceiver broadcast and prints the KaivoDiag lines it logs.
diag() {
  local action="$1"; shift
  $ADB logcat -c
  sh am broadcast -n "$PKG/com.kaivoflow.app.debug.DiagReceiver" -a "com.kaivoflow.$action" "$@" >/dev/null
  sleep "${DIAG_WAIT:-3}"
  $ADB logcat -d -s KaivoDiag:I | grep DIAG || echo "no DIAG output (is this the debug build?)"
}

# pull_dump: runs DIAG_DUMP and copies reliability-dump.json to $1 (default ./reliability-dump.json).
pull_dump() {
  local out="${1:-./reliability-dump.json}"
  diag DIAG_DUMP >/dev/null
  $ADB exec-out run-as "$PKG" cat files/reliability-dump.json > "$out" || die "run-as failed (debug build only)"
  echo "wrote $out"
}

wait_boot() { $ADB wait-for-device; until [[ "$($ADB shell getprop sys.boot_completed | tr -d '\r')" == "1" ]]; do sleep 2; done; }
