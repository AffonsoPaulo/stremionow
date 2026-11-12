#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Runs a command in a private D-Bus session with an unlocked GNOME Keyring: the Secret Service
# the desktop app's secret store uses on Linux. CI runners have no desktop session, so the
# store's round-trip tests would otherwise have nothing to talk to.
#
# Usage: with-secret-service.sh <command> [arguments...]
set -euo pipefail

if [ -z "${STREMIONOW_SECRET_SERVICE_READY:-}" ]; then
  # Re-run this script inside a new session bus, which ends with the command.
  exec dbus-run-session -- env STREMIONOW_SECRET_SERVICE_READY=1 "$0" "$@"
fi

# --unlock reads the password from stdin and creates (then unlocks) the login keyring, which
# becomes the default collection. The password only protects this throwaway keyring.
printf 'ci-keyring' | gnome-keyring-daemon --unlock --daemonize --components=secrets > /dev/null

# Wait until the daemon owns the Secret Service name on the bus.
registered=false
for _ in $(seq 1 50); do
  if dbus-send --session --print-reply --dest=org.freedesktop.DBus /org/freedesktop/DBus \
    org.freedesktop.DBus.NameHasOwner string:org.freedesktop.secrets 2> /dev/null | grep -q 'boolean true'; then
    registered=true
    break
  fi
  sleep 0.1
done
if [ "$registered" != true ]; then
  echo "gnome-keyring-daemon did not register org.freedesktop.secrets" >&2
  exit 1
fi

# Report what the keyring offers, so a failing test can be told apart from a keyring problem.
if command -v secret-tool > /dev/null; then
  if printf 'probe' | secret-tool store --label='CI probe' stremionow-ci probe; then
    echo "Secret Service: storing in the default collection works"
    secret-tool clear stremionow-ci probe || true
  else
    echo "Secret Service: storing in the default collection failed" >&2
  fi
fi
exec "$@"
