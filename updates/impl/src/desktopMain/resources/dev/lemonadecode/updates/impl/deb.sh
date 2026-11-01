#!/bin/sh
# Lemonade Code's updater for the .deb: waits for the app to quit, installs the new package
# (pkexec asks for the user's password; refusing keeps the old version) and opens the app.
#
# Usage: deb.sh <app pid> <.deb> <its folder, deleted afterwards> <command that opens the app>...
pid=$1 deb=$2 cleanup=$3
shift 3
trap '' HUP

while kill -0 "$pid" 2>/dev/null; do sleep 0.2; done

pkexec dpkg -i "$deb"
rm -rf "$cleanup"
rm -f "$0"
exec "$@"
