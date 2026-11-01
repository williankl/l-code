#!/bin/sh
# Lemonade Code's updater for a macOS bundle or a Linux app folder: waits for the app to quit,
# swaps the new version in for the old one and opens it. If the swap fails, the old one stays.
#
# Usage: swap.sh <app pid> <installed folder> <new folder> <folder to delete afterwards, or ""> <command that opens the app>...
pid=$1 target=$2 staged=$3 cleanup=$4
shift 4
trap '' HUP

while kill -0 "$pid" 2>/dev/null; do sleep 0.2; done

old="$target.old.$$"
if mv "$target" "$old"; then
  if mv "$staged" "$target"; then rm -rf "$old"; else mv "$old" "$target"; fi
fi
rm -rf "$staged"
if [ -n "$cleanup" ]; then rm -rf "$cleanup"; fi
rm -f "$0"
exec "$@"
