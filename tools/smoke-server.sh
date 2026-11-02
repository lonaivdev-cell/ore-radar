#!/usr/bin/env bash
# Boots one loader's dedicated dev server through Gradle, waits for "Done", checks that Ore Radar
# initialised (the "Ore Radar initialising on <loader>" line OreRadar.init() logs), then types
# "stop" on the server console and waits for a clean exit.
#
#   tools/smoke-server.sh <build> <module> [timeout-seconds]
#   tools/smoke-server.sh mc1201 forge
#
# Exit 0 only if the server finished booting, the mod logged its init line, and Gradle exited 0
# after the stop. The full log is kept in runs/<build>-<module>/smoke-server.log.
set -euo pipefail

if [[ $# -lt 2 ]]; then
    sed -n '2,10s/^# \{0,1\}//p' "$0" >&2
    exit 2
fi
build=$1 module=$2 timeout=${3:-900}
repo=$(cd "$(dirname "$0")/.." && pwd)
[[ -z ${JAVA_HOME:-} && -x /opt/graalvm-21/bin/java ]] && export JAVA_HOME=/opt/graalvm-21

run_dir="$repo/runs/$build-$module"
log="$run_dir/smoke-server.log"
mkdir -p "$run_dir"
fifo=$(mktemp -u "${TMPDIR:-/tmp}/oreradar-console.XXXXXX")
mkfifo "$fifo"
# Held open read-write so the server never sees end-of-input before we send "stop".
exec 3<>"$fifo"
rm -f "$fifo"

start=$(date +%s)
"$repo/tools/limited.sh" "$repo/gradlew" -p "$repo/$build" --console=plain ":$module:runServer" \
    <&3 >"$log" 2>&1 &
gradle_pid=$!

finished() { ! kill -0 "$gradle_pid" 2>/dev/null; }

booted=no
while (( $(date +%s) - start < timeout )); do
    if grep -qE 'Done \([0-9.]+s\)!' "$log"; then
        booted=yes
        break
    fi
    finished && break
    sleep 2
done
boot_seconds=$(( $(date +%s) - start ))

if [[ $booted == yes ]]; then
    echo stop >&3
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
status=0
wait "$gradle_pid" || status=$?
exec 3>&-

echo "log: $log"
echo "boot: $booted after ${boot_seconds}s; gradle exit status $status"
grep -E 'Ore Radar initialising|Done \(|Stopping (the )?server|BUILD (SUCCESSFUL|FAILED)' "$log" || true
init_logged=no
if grep -q 'Ore Radar initialising on' "$log"; then
    init_logged=yes
fi
[[ $booted == yes && $init_logged == yes && $status == 0 ]]
