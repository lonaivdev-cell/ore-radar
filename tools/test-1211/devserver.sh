#!/usr/bin/env bash
# devserver.sh start|cmd|stop|status <neoforge|fabric|paper> [console command...]
#
# Runs a 1.21.1 dedicated dev server in the background with its console on a FIFO, so later calls
# can type commands into it. neoforge and fabric are Gradle's :<loader>:runServer (under
# tools/limited.sh) on 25621 / 25622; "paper" is a Paper 1.21.1 server with the plugin from
# paper/build/libs, on 25623 in runs/mc1211-paper/server, to test a modded client on a server that
# serves every player as a vanilla one. Set PAPER_SERVER_JAR to Paper's server jar for it.
#   devserver.sh start neoforge                     boots, waits for "Done", prints the port
#   devserver.sh cmd neoforge 'give Tester stone'   one console line
#   devserver.sh stop neoforge                      "stop", then waits for a clean exit
# The run directory's server.properties must use a 1.21.1 test port (25621-25629, DESIGN §12).
# Log: runs/mc1211-<module>/devserver.log.
set -euo pipefail
source "$(dirname "$0")/env.sh"
action=${1:?usage: devserver.sh start|cmd|stop|status <neoforge|fabric|paper> [command]}
module=${2:?module: neoforge, fabric or paper}
case $module in neoforge|fabric|paper) ;; *) echo "module must be neoforge, fabric or paper" >&2; exit 2 ;; esac
RUN=$REPO/runs/mc1211-$module
FIFO=$RUN/devserver.console
LOG=$RUN/devserver.log
PIDS=$RUN/devserver.pids

running() { [[ -f $PIDS ]] && read -r _ server < "$PIDS" && kill -0 "$server" 2>/dev/null; }

case $action in
start)
    if running; then echo "already running (log $LOG)"; exit 0; fi
    if [[ $module == paper ]]; then
        [[ -f ${PAPER_SERVER_JAR:-} ]] || { echo "set PAPER_SERVER_JAR to a Paper 1.21.1 server jar" >&2; exit 2; }
        mkdir -p "$RUN/server/plugins"
        echo 'eula=true' > "$RUN/server/eula.txt"
        if [[ ! -f $RUN/server/server.properties ]]; then
            printf '%s\n' 'server-ip=127.0.0.1' 'server-port=25623' 'online-mode=false' 'enable-rcon=false' \
                'enable-query=false' 'motd=Paper 1.21.1 with the Ore Radar plugin' 'view-distance=6' 'simulation-distance=4' \
                > "$RUN/server/server.properties"
        fi
        cp "$REPO/paper/build/libs/oreradar-2.0.0-paper.jar" "$RUN/server/plugins/oreradar-paper.jar"
    fi
    port=$(sed -n 's/^server-port=//p' "$RUN/server/server.properties" 2>/dev/null || true)
    if [[ -z $port ]] || (( port < 25621 || port > 25629 )); then
        echo "server-port in $RUN/server/server.properties is '${port}', not in 25621-25629" >&2
        exit 2
    fi
    rm -f "$FIFO"
    mkfifo "$FIFO"
    # A holder keeps the FIFO open for writing, so the server never reads end-of-input between commands.
    setsid bash -c 'exec 3<>"$1"; exec sleep infinity' _ "$FIFO" </dev/null >/dev/null 2>&1 &
    holder=$!
    if [[ $module == paper ]]; then
        (cd "$RUN/server" && exec setsid nohup "$REPO/tools/limited.sh" "$JAVA_HOME/bin/java" -Xmx2G -jar "$PAPER_SERVER_JAR" nogui \
            <"$FIFO" >"$LOG" 2>&1) &
    else
        setsid nohup "$REPO/tools/limited.sh" "$REPO/gradlew" -p "$REPO/mc1211" --console=plain ":$module:runServer" \
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
        read -r holder server < "$PIDS"
        echo stop > "$FIFO"
        for _ in $(seq 1 90); do kill -0 "$server" 2>/dev/null || break; sleep 1; done
        if kill -0 "$server" 2>/dev/null; then
            echo "still running after stop; terminating" >&2
            kill -TERM "$server" 2>/dev/null || true
        fi
        kill "$holder" 2>/dev/null || true
    fi
    rm -f "$FIFO" "$PIDS"
    grep -E 'Stopping (the )?server|BUILD (SUCCESSFUL|FAILED)' "$LOG" | tail -3 || true ;;
status)
    if running; then echo running; else echo stopped; exit 1; fi ;;
*)
    echo "usage: devserver.sh start|cmd|stop|status <neoforge|fabric|paper> [command]" >&2
    exit 2 ;;
esac
