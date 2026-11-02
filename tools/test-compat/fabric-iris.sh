#!/usr/bin/env bash
# fabric-iris.sh fetch|start|stop|status [HOST:PORT]
#
# The 1.20.1 Fabric dev client (Gradle :fabric:runClient) with Sodium, Iris, ModMenu and the
# Complementary Reimagined shader pack, on the 1.20.1 display (:81), in a game directory of its own
# (runs/compat-fabric-iris/game) so the plain dev runs keep their empty mods/.
#   fetch                  downloads the pinned versions below from the Modrinth API, checks each
#                          file against the sha512 the API lists, and installs it into the game dir
#   start [HOST:PORT]      seeds options and Iris settings (shaders on), then starts the client;
#                          with HOST:PORT it joins that server at once (quick play)
#   stop | status
# Fabric Loader remaps the intermediary jars in mods/ at start (fabric.remapClasspathFile).
# Log: runs/compat-fabric-iris/client.log.
set -euo pipefail
source "$(dirname "$0")/../test/env.sh"
BASE=$REPO/runs/compat-fabric-iris
GAME=$BASE/game
LOG=$BASE/client.log
PIDS=$BASE/client.pids
UA='oreradar-compat-test (github.com/lonaivdev-cell)'
# project slug, Modrinth version_number, destination folder in the game dir
PINS=(
    'sodium mc1.20.1-0.5.13-fabric mods'
    'iris 1.7.6+1.20.1 mods'
    'modmenu 7.2.2 mods'
    'complementary-reimagined r5.9.3 shaderpacks'
)
SHADER_PACK=ComplementaryReimagined_r5.9.3.zip

running() { [[ -f $PIDS ]] && kill -0 "$(cat "$PIDS")" 2>/dev/null; }
scope_of() { sed -n 's#^0::.*/\([^/]*\.scope\)$#\1#p' "/proc/$1/cgroup" 2>/dev/null; }

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
    if running; then echo "already running (log $LOG)"; exit 0; fi
    [[ -f $GAME/shaderpacks/$SHADER_PACK ]] || { echo "run '$0 fetch' first" >&2; exit 2; }
    server=${2:-}
    args="--username Tester --width 1280 --height 720 --gameDir $GAME"
    if [[ -n $server ]]; then
        case ${server##*:} in 25565|25566|25575) echo "refusing $server: a live server port" >&2; exit 2 ;; esac
        case ${server%:*} in 127.0.0.1|localhost) ;; *) echo "refusing $server: use 127.0.0.1" >&2; exit 2 ;; esac
        args="$args --quickPlayMultiplayer $server"
    fi
    mkdir -p "$GAME/config"
    if [[ ! -f $GAME/options.txt ]]; then
        # As tools/test/devclient.sh seeds it; the version line keeps Minecraft from resetting it.
        cat > "$GAME/options.txt" <<'OPTIONS'
version:3465
pauseOnLostFocus:false
tutorialStep:none
skipMultiplayerWarning:true
joinedFirstServer:true
onboardAccessibility:false
narrator:0
renderDistance:6
simulationDistance:5
maxFps:30
enableVsync:false
guiScale:3
graphicsMode:1
key_key.oreradar.open:key.keyboard.r
OPTIONS
    fi
    printf '%s\n' "shaderPack=$SHADER_PACK" 'enableShaders=true' 'disableUpdateMessage=true' \
        'maxShadowRenderDistance=4' > "$GAME/config/iris.properties"
    "$TEST_DIR/xvfb.sh" start
    # Loom's dev runtime ships LWJGL 3.3.2-snapshot, not the launcher's 3.3.1; Sodium's pre-launch
    # check refuses any other version (its issue 2561), so the check is switched off for this rig.
    setsid nohup env JAVA_TOOL_OPTIONS=-Dsodium.checks.issue2561=false \
        DISPLAY="$DISPLAY_NAME" LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe LP_NUM_THREADS=4 \
        ALSOFT_DRIVERS=null "$REPO/tools/limited.sh" "$REPO/gradlew" -p "$REPO/mc1201" --console=plain \
        :fabric:runClient "-Poreradar_client_args=$args" </dev/null >"$LOG" 2>&1 &
    echo $! > "$PIDS"
    echo "started (log $LOG, game dir $GAME)" ;;
stop)
    if running; then
        pid=$(cat "$PIDS")
        scope=$(scope_of "$pid")
        if [[ -n $scope ]]; then
            systemctl --user kill --signal=SIGTERM "$scope" 2>/dev/null || true
            for _ in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
            systemctl --user stop "$scope" 2>/dev/null || true
        else
            kill -TERM "$pid" 2>/dev/null || true
        fi
    fi
    rm -f "$PIDS"
    echo stopped ;;
status) if running; then echo running; else echo stopped; exit 1; fi ;;
*) sed -n '2,13p' "$0"; exit 2 ;;
esac
