#!/usr/bin/env bash
# xdo.sh <xdotool args...> -- drives the display (default :81) with the rig's unpacked xdotool.
source "$(dirname "$0")/env.sh"
exec env DISPLAY="$DISPLAY_NAME" LD_LIBRARY_PATH="$XROOT/usr/lib/x86_64-linux-gnu" "$XROOT/usr/bin/xdotool" "$@"
