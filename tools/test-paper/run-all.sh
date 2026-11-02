#!/usr/bin/env bash
# Proves the Paper plugin on each given Paper version (DESIGN.md §12): a fresh world, then
#   1. the binary API check (every Bukkit/Paper/Adventure member the jar uses exists on that server),
#   2. bot-test.js full (vanilla client: GUI, outlines, needle, action bar, beep, drain, station; a
#      client with the mod gets nothing from the plugin),
#   3. items-test.js (radars never stack nor craft, the station's radar slot, drain on a moving radar, GUI clicks),
#   4. lifecycle-test.js (station persistence across a restart, drops, explosion, pistons, leftovers),
# and stops the server. Logs go to $OR_SCRATCH/test-results/<version>/. Exit 1 if anything failed.
#
#   tools/test-paper/run-all.sh 1.20.1 1.21.1 26.3
#
# Needs the server jars and JDKs that server.sh names, and mineflayer under $OR_SCRATCH/bot/node_modules.
# 26.3: mineflayer speaks at most 26.1, so the bots join as 26.1 through ViaVersion and ViaBackwards
# (put both jars in $OR_SCRATCH/paper-runs/26.3/plugins/) and stand still (OR_STILL, see harness.js).
set -uo pipefail

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
scratch=${OR_SCRATCH:-$repo/runs/paper-test}
plugin_jar=${OR_PLUGIN_JAR:-$repo/paper/build/libs/oreradar-2.0.0-paper.jar}
export PATH=/opt/ci/node/bin:$PATH NODE_PATH=${NODE_PATH:-$scratch/bot/node_modules}

[[ $# -ge 1 ]] || { sed -n '2,15s/^# \{0,1\}//p' "$0" >&2; exit 2; }
failed=0
for version in "$@"; do
    out=$scratch/test-results/$version
    mkdir -p "$out"
    case $version in
        1.20.1) java=$HOME/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2/bin/java; client=() ;;
        1.21.1) java=/opt/graalvm-21/bin/java; client=() ;;
        26.3) java=$scratch/jdk25/bin/java; client=(OR_CLIENT_VERSION=26.1 OR_STILL=1) ;;
        *) echo "run-all.sh: unknown version $version" >&2; exit 2 ;;
    esac
    echo "== Paper $version"
    "$here/server.sh" start "$version" --fresh || { failed=1; continue; }
    run=$scratch/paper-runs/$version
    "$java" "$here/ApiCheck.java" "$plugin_jar" "$run/versions" "$run/libraries" > "$out/api-check.txt" 2>&1 || failed=1
    echo "api-check: $(tail -n 1 "$out/api-check.txt")"
    for test in bot-test items-test lifecycle-test; do
        env "${client[@]}" "$repo/tools/limited.sh" node "$here/$test.js" "$version" > "$out/$test.txt" 2>&1 || failed=1
        echo "$test: $(grep -c '^PASS' "$out/$test.txt") passed, $(grep -c '^FAIL' "$out/$test.txt") failed"
        grep '^FAIL' "$out/$test.txt"
    done
    grep -E 'ERROR|Exception' "$run/console.log" | grep -iE 'oreradar|net\.loserver' > "$out/plugin-errors.txt"
    echo "plugin errors in the server log: $(wc -l < "$out/plugin-errors.txt")"
    [[ -s $out/plugin-errors.txt ]] && failed=1
    cp "$run/console.log" "$out/console.log"
    "$here/server.sh" stop "$version"
done
exit $failed
