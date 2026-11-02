#!/usr/bin/env bash
# Builds Ore Radar, runs core's unit tests, and collects the release jars in dist/ under their
# DESIGN.md §1 names, with dist/SHA256SUMS.
#
#   ./build.sh [target ...] [extra Gradle arguments, e.g. --offline]
#
# Targets: core (its unit tests), mc1201, mc1211, paper; naming none builds all four. Each target is
# its own Gradle build, so a broken one need not hold up the others: name the ones to build. Only
# the jars of the targets built are checked and replaced in dist/. When every jar is rebuilt, dist/
# is emptied first, so no jar from an older version stays behind.
#
# Gradle runs through tools/limited.sh (low priority, 9 GiB cap, no swap): a live game server
# shares this machine.
set -euo pipefail

repo=$(cd "$(dirname "$0")" && pwd)
cd "$repo"
# Gradle 9.5 and Loom 1.17 run on Java 21; the Java 17 toolchain is found or provisioned by Gradle.
[[ -z ${JAVA_HOME:-} && -x /opt/graalvm-21/bin/java ]] && export JAVA_HOME=/opt/graalvm-21

prop() { sed -n "s/^$2=//p" "$1" | tail -n 1; }
mod_id=$(prop gradle.properties mod_id)
version=$(prop gradle.properties mod_version)
mc1201=$(prop mc1201/gradle.properties minecraft_version)
mc1211=$(prop mc1211/gradle.properties minecraft_version)

# The release jars of each target that makes any.
declare -A jars=(
    [mc1201]="mc1201/forge/build/libs/$mod_id-$version+$mc1201-forge.jar mc1201/fabric/build/libs/$mod_id-$version+$mc1201-fabric.jar"
    [mc1211]="mc1211/neoforge/build/libs/$mod_id-$version+$mc1211-neoforge.jar mc1211/fabric/build/libs/$mod_id-$version+$mc1211-fabric.jar"
    [paper]="paper/build/libs/$mod_id-$version-paper.jar"
)

targets=()
gradle_args=()
declare -A named=()
for arg in "$@"; do
    case $arg in
        core | mc1201 | mc1211 | paper) [[ -v named[$arg] ]] || targets+=("$arg"); named[$arg]=1 ;;
        *) gradle_args+=("$arg") ;;
    esac
done
(( ${#targets[@]} )) || targets=(core mc1201 mc1211 paper)

gradle() { tools/limited.sh ./gradlew --console=plain "$@" "${gradle_args[@]}"; }

started=$(date +%s)
outputs=()
jar_targets=0
for target in "${targets[@]}"; do
    if [[ $target == core ]]; then
        echo "== core: unit tests"
        gradle -p core test
    else
        echo "== $target"
        gradle -p "$target" build
        read -ra built <<< "${jars[$target]}"
        outputs+=("${built[@]}")
        jar_targets=$((jar_targets + 1))
    fi
done

if (( ${#outputs[@]} )); then
    for jar in "${outputs[@]}"; do
        if [[ ! -f $jar ]]; then
            echo "build.sh: expected build output is missing: $jar" >&2
            exit 1
        fi
    done
    mkdir -p dist
    if (( jar_targets == ${#jars[@]} )); then
        rm -f dist/*.jar
    fi
    for jar in "${outputs[@]}"; do
        cp "$jar" dist/
    done
    (cd dist && sha256sum -- *.jar > SHA256SUMS)
fi

echo "== done: ${targets[*]} ($(( $(date +%s) - started ))s)"
if [[ -f dist/SHA256SUMS ]]; then
    cat dist/SHA256SUMS
fi
