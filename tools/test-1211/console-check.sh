#!/usr/bin/env bash
# Boots one 1.21.1 loader's dedicated dev server through Gradle, as tools/smoke-server.sh does, then
# types Ore Radar's console commands and checks their answers: "oreradar info", "oreradar reload"
# and a datapack "reload" (which rebuilds the rules), then "stop".
#
#   tools/test-1211/console-check.sh <neoforge|fabric> [timeout-seconds]
#
# Exit 0 only if the server booted, wrote config/oreradar-server.toml with its comments, answered
# both commands, rebuilt the rules after "reload", and Gradle exited 0 after the stop. The log is
# kept in runs/mc1211-<module>/console-check.log.
set -euo pipefail

if [[ $# -lt 1 ]]; then
    sed -n '2,11s/^# \{0,1\}//p' "$0" >&2
    exit 2
fi
module=$1 timeout=${2:-900}
case $module in neoforge|fabric) ;; *) echo "module must be neoforge or fabric" >&2; exit 2 ;; esac
repo=$(cd "$(dirname "$0")/../.." && pwd)
[[ -z ${JAVA_HOME:-} && -x /opt/graalvm-21/bin/java ]] && export JAVA_HOME=/opt/graalvm-21

run_dir="$repo/runs/mc1211-$module"
log="$run_dir/console-check.log"
config="$run_dir/server/config/oreradar-server.toml"
mkdir -p "$run_dir"
fifo=$(mktemp -u "${TMPDIR:-/tmp}/oreradar-console.XXXXXX")
mkfifo "$fifo"
# Held open read-write so the server never sees end-of-input before we send "stop".
exec 3<>"$fifo"
rm -f "$fifo"

"$repo/tools/limited.sh" "$repo/gradlew" -p "$repo/mc1211" --offline --console=plain ":$module:runServer" \
    <&3 >"$log" 2>&1 &
gradle_pid=$!
finished() { ! kill -0 "$gradle_pid" 2>/dev/null; }

# Waits until the log matches a pattern; fails after the timeout or when the server is gone.
wait_for() {
    local pattern=$1 start
    start=$(date +%s)
    while (( $(date +%s) - start < timeout )); do
        grep -qE "$pattern" "$log" && return 0
        finished && return 1
        sleep 1
    done
    return 1
}

status=0 checks=ok
if wait_for 'Done \([0-9.]+s\)!'; then
    echo "oreradar info" >&3
    wait_for 'Players with Ore Radar' || checks=failed
    echo "oreradar reload" >&3
    wait_for 'Reloaded oreradar-server.toml' || checks=failed
    rules_before=$(grep -c 'Ore Radar rules:' "$log" || true)
    echo "reload" >&3
    for _ in $(seq 60); do
        (( $(grep -c 'Ore Radar rules:' "$log" || true) > rules_before )) && break
        sleep 1
    done
    (( $(grep -c 'Ore Radar rules:' "$log" || true) > rules_before )) || checks=failed
    echo stop >&3
else
    checks=failed
fi
# Give the server time to save and stop; kill only what is left after that.
for _ in $(seq 120); do
    finished && break
    sleep 1
done
if ! finished; then
    echo "server still running after stop; terminating" >&2
    pkill -TERM -P "$gradle_pid" 2>/dev/null || true
    kill -TERM "$gradle_pid" 2>/dev/null || true
fi
wait "$gradle_pid" || status=$?
exec 3>&-

echo "log: $log"
grep -E 'Ore Radar initialising|Ore Radar rules:|Starting Minecraft server on|Done \(|Ore Radar on |Targets: |Scans: |Players with Ore Radar|Reloaded|Stopping (the )?server|BUILD (SUCCESSFUL|FAILED)' "$log" || true
commented=no
if [[ -f $config ]] && grep -q '^# Hard cap on any scan' "$config"; then
    commented=yes
fi
echo "config written with comments: $commented; command checks: $checks; gradle exit status $status"
[[ $checks == ok && $commented == yes && $status == 0 ]]
