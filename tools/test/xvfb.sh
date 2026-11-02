#!/usr/bin/env bash
# xvfb.sh start|stop|status -- a rootless Xvfb on the vertical's display (default :81, 1280x720x24),
# as the transient user unit oreradar-xvfb-<n> inside bubblewrap, adapted from the DeceasedCraft
# client rig. Clients reach it through the abstract socket @/tmp/.X11-unix/X<n>: no TCP and no
# file socket on the host. xkbcomp is overlaid onto /usr/bin because Xvfb runs it from a
# compiled-in path.
set -euo pipefail
source "$(dirname "$0")/env.sh"
GEOM=${ORERADAR_GEOM:-1280x720x24}
LOG=$REPO/runs/$XVFB_UNIT.log
# The rig's debs may lack xdpyinfo; the host's works too (it only talks to the abstract socket).
up() { x_env "$XROOT/usr/bin/xdpyinfo" >/dev/null 2>&1 || DISPLAY=$DISPLAY_NAME xdpyinfo >/dev/null 2>&1; }
case ${1:-start} in
start)
    if up; then echo "Xvfb already up on $DISPLAY_NAME"; exit 0; fi
    [[ -x $XROOT/usr/bin/Xvfb ]] || { echo "no Xvfb under $XROOT" >&2; exit 3; }
    systemctl --user stop "$XVFB_UNIT" 2>/dev/null || true
    systemctl --user reset-failed "$XVFB_UNIT" 2>/dev/null || true
    mkdir -p "$(dirname "$LOG")"
    systemd-run --user --unit="$XVFB_UNIT" --quiet -p CPUWeight=10 -p MemoryMax=2G -p Nice=19 \
        -p StandardOutput=truncate:"$LOG" -p StandardError=file:"$LOG" \
        /usr/bin/bwrap --ro-bind / / --dev /dev --proc /proc --tmpfs /tmp --tmpfs /run --tmpfs /home --ro-bind "$RIG" "$RIG" \
            --overlay-src /usr/bin --overlay-src "$RIG/xbin" --ro-overlay /usr/bin \
            --setenv LD_LIBRARY_PATH "$XROOT/usr/lib/x86_64-linux-gnu" \
            --unshare-pid --unshare-ipc --unshare-uts --die-with-parent \
            "$XROOT/usr/bin/Xvfb" "$DISPLAY_NAME" -screen 0 "$GEOM" -nolisten tcp -nolisten unix -noreset
    for _ in $(seq 1 50); do up && { echo "Xvfb up on $DISPLAY_NAME ($GEOM)"; exit 0; }; sleep 0.2; done
    echo "Xvfb failed to start; see $LOG" >&2
    cat "$LOG" >&2
    exit 1 ;;
stop)
    systemctl --user stop "$XVFB_UNIT" 2>/dev/null || true
    systemctl --user reset-failed "$XVFB_UNIT" 2>/dev/null || true
    echo stopped ;;
status)
    if up; then echo "up on $DISPLAY_NAME"; else echo down; exit 1; fi ;;
*)
    echo "usage: $0 start|stop|status" >&2
    exit 2 ;;
esac
