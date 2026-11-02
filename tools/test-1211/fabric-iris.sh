#!/usr/bin/env bash
# fabric-iris.sh fetch|start|stop|status [HOST:PORT]
#
# The 1.21.1 Fabric dev client (devclient.sh fabric) with Sodium, Iris, ModMenu and the
# Complementary Reimagined shader pack, on the 1.21.1 display (:82), in a game directory of its own
# (runs/mc1211-fabric-iris/game) so the plain dev runs keep their empty mods/.
#   fetch                  downloads the pinned versions below from the Modrinth API, checks each
#                          file against the sha512 the API lists, and installs it into the game dir
#   start [HOST:PORT]      seeds the Iris settings (shaders on), then starts the client; with
#                          HOST:PORT it joins that server at once (quick play)
#   stop | status
# Fabric Loader remaps the intermediary jars in mods/ at start. Log: runs/mc1211-fabric/devclient.log.
set -euo pipefail
here=$(dirname "$0")
source "$here/env.sh"
BASE=$REPO/runs/mc1211-fabric-iris
GAME=$BASE/game
UA='oreradar-compat-test (github.com/lonaivdev-cell)'
# project slug, Modrinth version_number, destination folder in the game dir
PINS=(
    'sodium mc1.21.1-0.6.13-fabric mods'
    'iris 1.8.8+1.21.1-fabric mods'
    'modmenu 11.0.3 mods'
    'complementary-reimagined r5.9.3 shaderpacks'
)
SHADER_PACK=ComplementaryReimagined_r5.9.3.zip

fetch() {
    mkdir -p "$GAME/mods" "$GAME/shaderpacks"
    for pin in "${PINS[@]}"; do
        read -r slug version dest <<<"$pin"
        # Each download gets a new, empty directory; only a checked file is copied out of it.
        dl=$BASE/dl/$slug
        rm -rf "$dl"
        mkdir -p "$dl"
        curl -fsS --max-time 60 -A "$UA" "https://api.modrinth.com/v2/project/$slug/version" -o "$dl/versions.json"
        meta=$(python3 -I - "$dl/versions.json" "$version" <<'PY'
import json, sys
versions = json.load(open(sys.argv[1]))
match = [v for v in versions if v["version_number"] == sys.argv[2]]
if not match:
    sys.exit(f"no version {sys.argv[2]}")
files = match[0]["files"]
f = next((f for f in files if f["primary"]), files[0])
if "/" in f["filename"] or f["filename"].startswith("."):
    sys.exit(f"odd file name {f['filename']!r}")
print(f["url"], f["hashes"]["sha512"], f["filename"])
PY
        )
        read -r url sha512 name <<<"$meta"
        curl -fsSL --max-time 300 -A "$UA" "$url" -o "$dl/$name"
        got=$(sha512sum "$dl/$name" | cut -d' ' -f1)
        if [[ $got != "$sha512" ]]; then
            echo "sha512 mismatch for $name: got $got, Modrinth lists $sha512" >&2
            exit 1
        fi
        install -m 0644 "$dl/$name" "$GAME/$dest/$name"
        echo "ok $slug $version -> $dest/$name (sha512 ${sha512:0:16}...)"
    done
}

case ${1:-} in
fetch) fetch ;;
start)
    [[ -f $GAME/shaderpacks/$SHADER_PACK ]] || { echo "run '$0 fetch' first" >&2; exit 2; }
    mkdir -p "$GAME/config"
    printf '%s\n' "shaderPack=$SHADER_PACK" 'enableShaders=true' 'disableUpdateMessage=true' \
        'maxShadowRenderDistance=4' > "$GAME/config/iris.properties"
    GAME_DIR=$GAME "$here/devclient.sh" start fabric "${2:-}" ;;
stop) "$here/devclient.sh" stop fabric ;;
status) "$here/devclient.sh" status fabric ;;
*) sed -n '2,13p' "$0"; exit 2 ;;
esac
