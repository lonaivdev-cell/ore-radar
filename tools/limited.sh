#!/usr/bin/env bash
# Runs a command the way heavy work must run on this machine, which a live game server shares:
# low CPU weight, a 9 GiB memory cap and no swap, in a transient systemd scope that ends with the
# command. Falls back to plain nice where the user's systemd is not available.
#
#   tools/limited.sh <command> [args...]
if systemd-run --user --scope -q true >/dev/null 2>&1; then
    exec systemd-run --user --scope -q -p CPUWeight=20 -p MemoryMax=9G -p MemorySwapMax=0 \
        nice -n 19 "$@"
fi
exec nice -n 19 "$@"
