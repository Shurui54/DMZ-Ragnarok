#!/usr/bin/env bash
#
# One-command real-server smoke test for the ForgeEssentials production jar.
#
# It will:
#   1. build the production fat jar         (./gradlew clean build  ->  *-all.jar)
#   2. install a standalone Forge 1.20.1 server on first run (reused afterwards)
#   3. deploy the jar, boot the server, wait for startup
#   4. grep the log for pass/fail signals, then stop the server cleanly
#   5. print a PASS/FAIL summary
#
# Usage:
#   scripts/test-real-server.sh                 # core smoke test
#   WITH_PLAYERLOGGER=1 scripts/test-real-server.sh   # also exercise the bundled Hibernate/H2 DB
#
# Env overrides: SERVER_DIR, BOOT_WAIT (seconds), JAVA17
#
set -uo pipefail

# ----------------------------------------------------------------------------- config
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="${SERVER_DIR:-$PROJECT_DIR/run-prod-test}"
FORGE_VER="1.20.1-47.4.20"
ARGS_FILE="libraries/net/minecraftforge/forge/${FORGE_VER}/unix_args.txt"
BOOT_WAIT="${BOOT_WAIT:-240}"
WITH_PLAYERLOGGER="${WITH_PLAYERLOGGER:-0}"
# Use a dedicated port so the test never collides with a real server (or a not-yet-released
# socket from this script's own warm-up boot — that was a "FAILED TO BIND TO PORT" crash).
PORT="${PORT:-25599}"

# A Java 17 runtime is required (Forge 1.20.1). Prefer Gradle's auto-provisioned JDK, else PATH java.
JAVA17="${JAVA17:-$(find "$HOME/.gradle/jdks" -type f -name java -path '*17*' 2>/dev/null | head -1)}"
[ -z "$JAVA17" ] && JAVA17="$(command -v java || true)"

say()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
fail() { printf '\033[1;31mFAIL: %s\033[0m\n' "$*"; exit 1; }

[ -x "$JAVA17" ] || fail "no Java 17 found (set JAVA17=/path/to/jdk17/bin/java)"

# ----------------------------------------------------------------------------- 1. build
say "Building production jar (clean build is mandatory — incremental drops the mixin refmap)"
cd "$PROJECT_DIR"
./gradlew clean build -q || fail "gradle build failed"
JAR="$(ls -t "$PROJECT_DIR"/build/libs/*-all.jar 2>/dev/null | head -1)"
[ -n "$JAR" ] || fail "no *-all.jar produced in build/libs"
say "Jar: $JAR ($(du -h "$JAR" | cut -f1))"

# ----------------------------------------------------------------------------- 2. install Forge
mkdir -p "$SERVER_DIR"
cd "$SERVER_DIR"
if [ ! -f "$ARGS_FILE" ]; then
    say "Installing Forge $FORGE_VER server (one-time download)"
    curl -fSL -o forge-installer.jar \
        "https://maven.minecraftforge.net/net/minecraftforge/forge/${FORGE_VER}/forge-${FORGE_VER}-installer.jar" \
        || fail "could not download Forge installer"
    "$JAVA17" -jar forge-installer.jar --installServer || fail "Forge server install failed"
fi
echo "eula=true" > eula.txt
# Pin the test port (create or rewrite the property so reruns stay deterministic).
touch server.properties
if grep -q '^server-port=' server.properties; then
    sed -i "s/^server-port=.*/server-port=$PORT/" server.properties
else
    echo "server-port=$PORT" >> server.properties
fi

# ----------------------------------------------------------------------------- 3. deploy
say "Deploying jar to $SERVER_DIR/mods"
mkdir -p mods
rm -f mods/*.jar
cp "$JAR" mods/

# ----------------------------------------------------------------------------- boot helper
PIPE="$SERVER_DIR/.console.fifo"
CRASH_RE='FAILED TO BIND TO PORT|Failed to initialize server|Exception in thread "main"|Exception in server tick loop|Preparing crash report|This crash report has been saved|Crash report saved'

port_free() {  # 0 if nothing is listening on $PORT
    ss -ltn 2>/dev/null | grep -q ":$PORT[[:space:]]" && return 1 || return 0
}
wait_port_free() { local i; for ((i=0; i<20; i++)); do port_free && return 0; sleep 1; done; return 1; }

boot_and_wait() {   # sets $BOOT_STATUS = up | crash | timeout | exited
    rm -f serverlog.txt; rm -f crash-reports/*.txt 2>/dev/null
    # Make sure the chosen port is actually free before we boot (a prior boot's socket may linger).
    wait_port_free || { say "port $PORT still busy — waiting"; sleep 5; }
    rm -f "$PIPE"; mkfifo "$PIPE"
    sleep 100000 > "$PIPE" & local holder=$!
    "$JAVA17" @user_jvm_args.txt @"$ARGS_FILE" nogui < "$PIPE" > serverlog.txt 2>&1 & local srv=$!
    BOOT_STATUS=timeout
    local i
    for ((i=0; i<BOOT_WAIT; i+=3)); do
        if grep -q 'Done (' serverlog.txt 2>/dev/null; then BOOT_STATUS=up; break; fi
        if grep -qE "$CRASH_RE" serverlog.txt 2>/dev/null; then BOOT_STATUS=crash; break; fi
        kill -0 "$srv" 2>/dev/null || { BOOT_STATUS=exited; break; }
        sleep 3
    done
    # Graceful stop, then escalate; finally make sure the process is gone AND the port released
    # before returning so the next boot can bind.
    echo "stop" > "$PIPE" 2>/dev/null || true
    for ((i=0; i<30; i++)); do kill -0 "$srv" 2>/dev/null || break; sleep 1; done
    kill -9 "$srv" 2>/dev/null || true
    wait "$srv" 2>/dev/null || true
    kill "$holder" 2>/dev/null || true
    rm -f "$PIPE"
    wait_port_free || true
}

# If asked to test PlayerLogger, ensure its config flag is on (warm-up boot first run to generate it).
if [ "$WITH_PLAYERLOGGER" = "1" ]; then
    if [ ! -f ForgeEssentials/Modules.cfg ]; then
        say "Warm-up boot to generate FE config (PlayerLogger is off by default)"
        boot_and_wait
    fi
    if [ -f ForgeEssentials/Modules.cfg ]; then
        sed -i 's/^PlayerLogger=false/PlayerLogger=true/' ForgeEssentials/Modules.cfg
        rm -f ForgeEssentials/playerlogger.mv.db ForgeEssentials/playerlogger.trace.db 2>/dev/null
    fi
fi

# ----------------------------------------------------------------------------- 4. boot + validate
say "Booting server (waiting up to ${BOOT_WAIT}s for startup)"
boot_and_wait
L="$SERVER_DIR/serverlog.txt"

say "Results"
PASS=1
check() { # name, condition(0=ok)
    if [ "$2" -eq 0 ]; then printf '  \033[1;32m✓\033[0m %s\n' "$1"
    else printf '  \033[1;31m✗\033[0m %s\n' "$1"; PASS=0; fi
}

[ "$BOOT_STATUS" = "up" ]; check "server reached 'Done' (startup complete)" $?
grep -qE 'ForgeEssentials ServerStarted' "$L"; check "ForgeEssentials ServerStarted" $?
! grep -qE 'Mixin apply failed|InvalidInjectionException|could not be read' "$L"; check "mixins applied (refmap OK)" $?
! grep -qE "$CRASH_RE" "$L"; check "no fatal crash" $?
grep -qiE 'Registered [0-9]+ commands' "$L"; check "FE commands registered ($(grep -oiE 'Registered [0-9]+ commands' "$L" | head -1))" $?

if [ "$WITH_PLAYERLOGGER" = "1" ]; then
    grep -q 'PLAYERLOGGER created Database' "$L"; check "PlayerLogger: Hibernate/H2 DB created" $?
    [ "$(grep -c 'CommandAcceptanceException' "$L")" -eq 0 ]; check "PlayerLogger: 0 DDL errors" $?
fi

echo
echo "  modules loaded: $(grep -c 'Discovered FE module' "$L")"
echo "  full log: $L"
if [ "$PASS" = "1" ]; then printf '\n\033[1;32m==> PASS\033[0m  (deploy %s on any Forge %s server)\n' "$(basename "$JAR")" "$FORGE_VER"; exit 0
else printf '\n\033[1;31m==> FAIL\033[0m  (inspect %s)\n' "$L"; exit 1; fi
