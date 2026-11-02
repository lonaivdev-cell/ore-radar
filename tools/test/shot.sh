#!/usr/bin/env bash
# shot.sh OUT.png -- screenshots the whole display (default :81) to a PNG.
set -euo pipefail
source "$(dirname "$0")/env.sh"
OUT=${1:?usage: shot.sh OUT.png}
mkdir -p "$(dirname "$OUT")"
TMP=$(mktemp "${TMPDIR:-/tmp}/oreradar-xwd.XXXXXX")
trap 'rm -f "$TMP"' EXIT
x_env "$XROOT/usr/bin/xwd" -root -silent -out "$TMP"
python3 -I "$TEST_DIR/bin/xwd2png.py" "$TMP" "$OUT"
