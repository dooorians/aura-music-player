#!/usr/bin/env bash
# Attribution experiment on a build with the TEMPORARY PerfFlags: one element off at a time, journey(s) re-measured.
# Usage: exp_flags.sh PREFIX "journey ..." cfg1 cfg2 ...   (cfg "none" = everything on; "a+b" = several flags)
source "$(dirname "$0")/lib.sh"
P="$1"; shift; J="$1"; shift
for cfg in "$@"; do
  awake || exit 3
  adb shell am force-stop "$PKG"; sleep 1
  adb shell am start -n "$ACT" --es perfflags "'${cfg//+/ }'" >/dev/null; sleep 5
  CUR="$P/$cfg"; go_home; for i in 1 2; do sg 540 850 540 350 150 0.8; sg 540 350 540 850 150 0.8; done
  bash "$HERE/suite.sh" "${P}_$cfg" $J | tail -n +1
done
