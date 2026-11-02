# Sourced by the 1.21.1 test helpers: the 1.21.1 vertical's display (:82, DESIGN §12) and the shared
# rig of tools/test/ (Xvfb, screenshots, xdotool), which these scripts reuse on that display.
ORERADAR_DISPLAY=${ORERADAR_DISPLAY:-:82}
source "$(dirname "${BASH_SOURCE[0]}")/../test/env.sh"
export ORERADAR_DISPLAY
TEST1211_DIR=$REPO/tools/test-1211
