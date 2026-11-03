#!/usr/bin/env bash
# Runs the release plugin (dist/) on every given Paper version: downloads the latest Paper build
# of each one that is missing (fill.papermc.io, sha256-checked), then run-all.sh with the API check
# and bot-test.js (OR_TESTS overrides). The summary is $OR_SCRATCH/test-results/compat.txt.
#
#   tools/test-paper/compat.sh 1.20.2 1.20.4 ... 26.2
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
scratch=${OR_SCRATCH:-$repo/runs/paper-test}
export OR_PLUGIN_JAR=${OR_PLUGIN_JAR:-$repo/dist/oreradar-2.0.0-paper.jar}
export OR_TESTS=${OR_TESTS:-bot-test}
mkdir -p "$scratch/paper-servers" "$scratch/test-results"
summary=$scratch/test-results/compat.txt
: > "$summary"
for v in "$@"; do
    have=$(ls "$scratch"/paper-servers/paper-"$v"-*.jar 2>/dev/null | head -n 1)
    if [[ -z $have ]]; then
        meta=$(curl -fsS --max-time 30 "https://fill.papermc.io/v3/projects/paper/versions/$v/builds/latest") || { echo "$v: no Paper build" | tee -a "$summary"; continue; }
        read -r name url sha < <(python3 -I -c 'import sys,json; d=json.load(sys.stdin)["downloads"]["server:default"]; print(d["name"], d["url"], d["checksums"]["sha256"])' <<< "$meta")
        curl -fsSL --retry 3 --max-time 600 -o "$scratch/paper-servers/$name.part" "$url" || { echo "$v: download failed" | tee -a "$summary"; continue; }
        if [[ $(sha256sum < "$scratch/paper-servers/$name.part" | cut -d' ' -f1) != "$sha" ]]; then
            rm -f "$scratch/paper-servers/$name.part"; echo "$v: checksum mismatch" | tee -a "$summary"; continue
        fi
        mv "$scratch/paper-servers/$name.part" "$scratch/paper-servers/$name"
        have=$scratch/paper-servers/$name
    fi
    if "$here/run-all.sh" "$v" > "$scratch/test-results/compat-$v.txt" 2>&1; then r=PASS; else r=FAIL; fi
    echo "$v ($(basename "$have" .jar)): $r — $(grep -E '^(api-check|bot-test|plugin errors)' "$scratch/test-results/compat-$v.txt" | tr '\n' ';')" | tee -a "$summary"
done
