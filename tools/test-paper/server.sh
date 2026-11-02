#!/usr/bin/env bash
# Runs a Paper test server for the Ore Radar plugin (DESIGN.md §12): one run directory per Minecraft
# version, a console fed through a FIFO, everything under tools/limited.sh.
#
#   tools/test-paper/server.sh start <version> [--fresh]   boot (and wait for "Done"); --fresh deletes the world first
#   tools/test-paper/server.sh cmd <version> <command...>   type a console command, print the log lines it caused
#   tools/test-paper/server.sh stop <version>               "stop", wait for the JVM to exit
#   tools/test-paper/server.sh status <version>
#
# Versions and their ports / JDKs (DESIGN.md §12 gives Paper 25631-25639):
#   1.20.1 -> 25631, Java 17     1.21.1 -> 25632, Java 21     26.3 -> 25633, Java 25
# Environment: OR_SCRATCH (default: runs/paper-test, git-ignored; see BUILDING.md) holds paper-servers/<jar>,
# paper-runs/<version>/ and jdk25/. OR_PLUGIN_JAR overrides the plugin jar (default: paper/build/libs).
set -euo pipefail

repo=$(cd "$(dirname "$0")/../.." && pwd)
scratch=${OR_SCRATCH:-$repo/runs/paper-test}
plugin_jar=${OR_PLUGIN_JAR:-$repo/paper/build/libs/oreradar-2.0.0-paper.jar}

usage() { sed -n '2,15s/^# \{0,1\}//p' "$0" >&2; exit 2; }
[[ $# -ge 2 ]] || usage
action=$1 version=$2
shift 2

case $version in
    1.20.1) port=25631 java=$HOME/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2/bin/java ;;
    1.21.1) port=25632 java=/opt/graalvm-21/bin/java ;;
    26.3) port=25633 java=$scratch/jdk25/bin/java ;;
    *) echo "server.sh: unknown version $version" >&2; exit 2 ;;
esac
server_jar=$(ls "$scratch"/paper-servers/paper-"$version"-*.jar 2>/dev/null | head -n 1)
run=$scratch/paper-runs/$version
log=$run/console.log
fifo=$run/console.fifo

running() { [[ -f $run/server.pid ]] && kill -0 "$(cat "$run/server.pid")" 2>/dev/null; }

# Waits until the log grows a line matching $1 after byte offset $2, or $3 seconds pass.
wait_for() {
    local pattern=$1 offset=$2 timeout=$3 start
    start=$(date +%s)
    while (( $(date +%s) - start < timeout )); do
        if tail -c +"$((offset + 1))" "$log" | grep -qE "$pattern"; then return 0; fi
        running || return 1
        sleep 0.5
    done
    return 1
}

start() {
    [[ -n $server_jar ]] || { echo "server.sh: no Paper $version jar in $scratch/paper-servers" >&2; exit 1; }
    [[ -x $java ]] || { echo "server.sh: no JDK at $java" >&2; exit 1; }
    if running; then echo "server.sh: $version already running" >&2; exit 1; fi
    if [[ ${1:-} == --fresh ]]; then rm -rf "$run"/world "$run"/world_nether "$run"/world_the_end "$run"/plugins/OreRadar; fi
    mkdir -p "$run/plugins"
    echo "eula=true" > "$run/eula.txt"
    if [[ ! -f $run/server.properties ]]; then
        cat > "$run/server.properties" <<EOF
server-ip=127.0.0.1
server-port=$port
online-mode=false
enable-rcon=false
enable-query=false
level-seed=oreradar
spawn-protection=0
view-distance=6
simulation-distance=4
max-players=4
white-list=false
allow-flight=true
motd=Ore Radar test $version
EOF
    fi
    cp "$plugin_jar" "$run/plugins/oreradar-paper.jar"
    rm -f "$fifo"
    mkfifo "$fifo"
    : > "$log"
    # A writer that never closes, so the server never reads end-of-input from its console.
    nohup sleep infinity > "$fifo" 2>/dev/null &
    echo $! > "$run/holder.pid"
    (cd "$run" && exec "$repo/tools/limited.sh" "$java" -Xms512M -Xmx2G -Dterminal.jline=false -Dterminal.ansi=false \
        -jar "$server_jar" --nogui < "$fifo" >> "$log" 2>&1) &
    echo $! > "$run/server.pid"
    if wait_for 'Done \([0-9.,]+s\)!' 0 600; then
        echo "server.sh: Paper $version up on 127.0.0.1:$port"
    else
        echo "server.sh: Paper $version did not finish booting; see $log" >&2
        exit 1
    fi
}

cmd() {
    running || { echo "server.sh: $version is not running" >&2; exit 1; }
    local offset
    offset=$(stat -c %s "$log")
    printf '%s\n' "$*" > "$fifo"
    sleep "${OR_CMD_WAIT:-2}"
    tail -c +"$((offset + 1))" "$log"
}

stop() {
    if running; then
        printf 'stop\n' > "$fifo"
        local start
        start=$(date +%s)
        while running && (( $(date +%s) - start < 120 )); do sleep 0.5; done
        if running; then
            echo "server.sh: $version did not stop in 120 s; killing it" >&2
            pkill -TERM -P "$(cat "$run/server.pid")" || true
            kill "$(cat "$run/server.pid")" 2>/dev/null || true
        fi
    fi
    [[ -f $run/holder.pid ]] && kill "$(cat "$run/holder.pid")" 2>/dev/null || true
    rm -f "$run/server.pid" "$run/holder.pid" "$fifo"
    echo "server.sh: $version stopped"
}

case $action in
    start) start "$@" ;;
    cmd) cmd "$@" ;;
    stop) stop ;;
    status) if running; then echo "running"; else echo "stopped"; fi ;;
    *) usage ;;
esac
