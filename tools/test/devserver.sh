#!/usr/bin/env bash
# devserver.sh start|cmd|stop|status <forge|fabric|vanilla> [console command...]
#
# Runs the 1.20.1 dedicated dev server (Gradle :<loader>:runServer under tools/limited.sh) in the
# background with its console on a FIFO, so later calls can type commands into it. "vanilla" runs
# Mojang's server jar from Loom's cache instead, without Ore Radar, on 25613 in
# runs/mc1201-vanilla/server (seeded like the dev servers), to test a client on a server without it.
#   devserver.sh start forge                     boots, waits for "Done", prints the port
#   devserver.sh cmd forge 'give Tester stone'   one console line
#   devserver.sh stop forge                      "stop", then waits for a clean exit
# The run directory's server.properties must use a 1.20.1 test port (25611-25619, DESIGN §12).
# Log: runs/mc1201-<loader>/devserver.log.
set -euo pipefail
source "$(dirname "$0")/env.sh"
action=${1:?usage: devserver.sh start|cmd|stop|status <forge|fabric> [command]}
loader=${2:?loader: forge or fabric}
case $loader in forge|fabric|vanilla) ;; *) echo "loader must be forge, fabric or vanilla" >&2; exit 2 ;; esac
VANILLA_JAR=$HOME/.gradle/caches/fabric-loom/1.20.1/minecraft-server.jar
RUN=$REPO/runs/mc1201-$loader
FIFO=$RUN/devserver.console
LOG=$RUN/devserver.log
PIDS=$RUN/devserver.pids

running() { [[ -f $PIDS ]] && read -r _ gradle < "$PIDS" && kill -0 "$gradle" 2>/dev/null; }

case $action in
start)
    if running; then echo "already running (log $LOG)"; exit 0; fi
    if [[ $loader == vanilla && ! -f $RUN/server/server.properties ]]; then
        mkdir -p "$RUN/server"
        echo 'eula=true' > "$RUN/server/eula.txt"
        printf '%s\n' 'server-ip=127.0.0.1' 'server-port=25613' 'online-mode=false' 'enable-rcon=false' \
            'enable-query=false' 'motd=Vanilla 1.20.1 (no Ore Radar)' 'view-distance=6' 'simulation-distance=6' \
            > "$RUN/server/server.properties"
    fi
    port=$(sed -n 's/^server-port=//p' "$RUN/server/server.properties" 2>/dev/null || true)
    if [[ -z $port ]] || (( port < 25611 || port > 25619 )); then
        echo "server-port in $RUN/server/server.properties is '${port}', not in 25611-25619" >&2
        exit 2
    fi
    rm -f "$FIFO"
    mkfifo "$FIFO"
    # A holder keeps the FIFO open for writing, so the server never reads end-of-input between commands.
    setsid bash -c 'exec 3<>"$1"; exec sleep infinity' _ "$FIFO" </dev/null >/dev/null 2>&1 &
    holder=$!
    if [[ $loader == vanilla ]]; then
        (cd "$RUN/server" && exec setsid nohup "$REPO/tools/limited.sh" "$JAVA_HOME/bin/java" -Xmx2G -jar "$VANILLA_JAR" nogui \
            <"$FIFO" >"$LOG" 2>&1) &
    else
        setsid nohup "$REPO/tools/limited.sh" "$REPO/gradlew" -p "$REPO/mc1201" --console=plain ":$loader:runServer" \
            <"$FIFO" >"$LOG" 2>&1 &
    fi
    echo "$holder $!" > "$PIDS"
    for _ in $(seq 1 300); do
        if grep -qsE 'Done \([0-9.]+s\)!' "$LOG"; then
            echo "up on 127.0.0.1:$port (log $LOG)"
            exit 0
        fi
        running || { echo "server exited; see $LOG" >&2; tail -20 "$LOG" >&2; exit 1; }
        sleep 2
    done
    echo "no Done after 600 s; see $LOG" >&2
    exit 1 ;;
cmd)
    running || { echo "not running" >&2; exit 1; }
    shift 2
    printf '%s\n' "$*" > "$FIFO" ;;
stop)
    if running; then
        read -r holder gradle < "$PIDS"
        echo stop > "$FIFO"
        for _ in $(seq 1 90); do kill -0 "$gradle" 2>/dev/null || break; sleep 1; done
        if kill -0 "$gradle" 2>/dev/null; then
            echo "still running after stop; terminating" >&2
            kill -TERM "$gradle" 2>/dev/null || true
        fi
        kill "$holder" 2>/dev/null || true
    fi
    rm -f "$FIFO" "$PIDS"
    grep -E 'Stopping (the )?server|BUILD (SUCCESSFUL|FAILED)' "$LOG" | tail -3 || true ;;
status)
    if running; then echo running; else echo stopped; exit 1; fi ;;
*)
    echo "usage: devserver.sh start|cmd|stop|status <forge|fabric> [command]" >&2
    exit 2 ;;
esac
