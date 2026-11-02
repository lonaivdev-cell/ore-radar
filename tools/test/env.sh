# Sourced by the test helpers: where things are, and the 1.20.1 vertical's display (DESIGN §12).
# ORERADAR_DISPLAY may pick another display for another vertical (:82, :83); :77 belongs to the
# DeceasedCraft client rig and is refused.
REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
TEST_DIR=$REPO/tools/test
# Xvfb, xwd and xdotool come unpacked from the client rig's debs (read only; nothing is installed).
RIG=${ORERADAR_RIG:-$HOME/server-setup/deceasedcraft/addons/test/client-rig}
XROOT=$RIG/xroot
DISPLAY_NAME=${ORERADAR_DISPLAY:-:81}
if [[ $DISPLAY_NAME == :77 ]]; then
    echo "display :77 is the DeceasedCraft client rig's; use :81 (1.20.1), :82 or :83" >&2
    exit 2
fi
XVFB_UNIT=oreradar-xvfb-${DISPLAY_NAME#:}
[[ -z ${JAVA_HOME:-} && -x /opt/graalvm-21/bin/java ]] && export JAVA_HOME=/opt/graalvm-21
x_env() { env DISPLAY="$DISPLAY_NAME" LD_LIBRARY_PATH="$XROOT/usr/lib/x86_64-linux-gnu" "$@"; }
