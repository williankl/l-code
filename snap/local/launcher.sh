#!/bin/sh
# Starts the app inside the snap. The SNAP* variables snapd sets are dropped first: the app's
# terminals inherit its environment, and the user's shells shouldn't look like they run in a snap.
app="$SNAP/opt/lemonade-code/bin/Lemonade Code"
for name in $(env | sed -n 's/^\(SNAP[A-Z_]*\)=.*/\1/p'); do
  unset "$name"
done
exec "$app" "$@"
