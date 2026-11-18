#!/usr/bin/env bash
# Sets up and runs the duck-clinic voice agent (step 1, typed input).
#   ./duck-voice.sh setup   checks Java, Maven, Ollama, the model and the MCP server; builds the client and the agent
#   ./duck-voice.sh run     setup, then the interactive agent
#   ./duck-voice.sh test    setup, then the four test cases, typed by a driver
#   ./duck-voice.sh voice   setup, then the Swift voice window (microphone, speech recognition, spoken replies; step 2)
#   ./duck-voice.sh start-clinic   starts duck-clinic on Payara Micro in the background (log: target/clinic.log); setup/run/test do this by themselves if it is not running
#   ./duck-voice.sh stop-clinic    stops the duck-clinic that start-clinic started
# Settings (environment variables):
#   CLINIC_DIR=<path>        the duck-clinic project (default ../duck-clinic); AUTO_START=0 stops setup/run/test from starting it
#   MODEL=<ollama model>     required for the default BACKEND=ollama; see "ollama list"
#   BACKEND=ollama|anthropic ollama (default) or the Anthropic API (then ANTHROPIC_API_KEY and ANTHROPIC_MODEL must be set)
#   CLIENT_DIR=<path>        the jakarta-mcp-client project (default ../jakarta-mcp-client-main)
#   MCP_URL=<url>            default http://localhost:8080/duck-clinic/mcp (use .../duck-clinic/api/mcp after the @ApplicationPath fix)
#   OLLAMA_URL, SLOW_SECONDS (wait before "yes" in test case 4, default 120), REBUILD=1
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CLIENT_DIR="${CLIENT_DIR:-$HERE/../jakarta-mcp-client-main}"
JAR="$HERE/target/duck-voice-agent.jar"
BACKEND="${BACKEND:-ollama}"
OLLAMA_URL="${OLLAMA_URL:-http://localhost:11434}"
MCP_URL_GIVEN="${MCP_URL:-}"
MCP_URL="${MCP_URL:-http://localhost:8080/duck-clinic/mcp}"
SLOW_SECONDS="${SLOW_SECONDS:-120}"
CLINIC_DIR="${CLINIC_DIR:-$HERE/../duck-clinic}"
AUTO_START="${AUTO_START:-1}"
CLINIC_LOG="$HERE/target/clinic.log"
CLINIC_PID="$HERE/target/clinic.pid"

say()  { printf '%s\n' "$*"; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

check_tools() {
    command -v java >/dev/null || fail "java not found"
    local major
    major="$(java -version 2>&1 | grep -m1 'version "' | sed -E 's/.*"([0-9]+).*/\1/')"
    [ "$major" -ge 21 ] 2>/dev/null || fail "Java 21 or newer is needed, found '$major'"
    command -v mvn  >/dev/null || fail "mvn not found"
    command -v curl >/dev/null || fail "curl not found"
    say "ok   Java $major, Maven, curl"
}

check_model() {
    if [ "$BACKEND" = "anthropic" ]; then
        [ -n "${ANTHROPIC_API_KEY:-}" ] && [ -n "${ANTHROPIC_MODEL:-}" ] || fail "BACKEND=anthropic needs ANTHROPIC_API_KEY and ANTHROPIC_MODEL"
        say "ok   Anthropic API, model $ANTHROPIC_MODEL"
        return
    fi
    local tags models
    tags="$(curl -sf -m 5 "$OLLAMA_URL/api/tags")" || fail "Ollama does not answer at $OLLAMA_URL (start it with: ollama serve)"
    models="$(printf '%s' "$tags" | grep -o '"name":"[^"]*"' | cut -d'"' -f4 || true)"
    local wanted="${MODEL:-${ANTHROPIC_MODEL:-}}"
    if [ -z "$wanted" ]; then
        say "Installed Ollama models:"; printf '%s\n' "$models" | sed 's/^/  /'
        fail "Set MODEL, for example: MODEL=<one of the above> $0 ${1:-run}"
    fi
    printf '%s\n' "$models" | grep -qx "$wanted" || printf '%s\n' "$models" | grep -qx "$wanted:latest" || fail "Model '$wanted' is not installed (ollama pull $wanted)"
    if curl -sf -m 10 -d "{\"model\":\"$wanted\"}" "$OLLAMA_URL/api/show" | grep -q '"tools"'; then
        say "ok   Ollama model $wanted (reports tool support)"
    else
        say "WARN Ollama model $wanted does not report tool support; it will probably never call the clinic tools"
    fi
    export ANTHROPIC_BASE_URL="$OLLAMA_URL"
    export ANTHROPIC_API_KEY="${ANTHROPIC_API_KEY:-ollama}"
    export ANTHROPIC_MODEL="$wanted"
    export ANTHROPIC_MAX_TOKENS="${ANTHROPIC_MAX_TOKENS:-4096}"
}

mcp_code() {
    curl -s -o /dev/null -m 5 -w '%{http_code}' -X POST -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
        -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' "$MCP_URL" || true
}

descendants() {
    local child
    for child in $(pgrep -P "$1" 2>/dev/null || true); do descendants "$child"; echo "$child"; done
}

start_clinic() {
    [ "$(mcp_code)" != "000" ] && { say "ok   duck-clinic already answers at $MCP_URL"; return; }
    [ -f "$CLINIC_DIR/pom.xml" ] || fail "duck-clinic project not found at $CLINIC_DIR (set CLINIC_DIR)"
    mkdir -p "$HERE/target"
    say "...  starting duck-clinic: mvn clean package payara-micro:start (log: $CLINIC_LOG)"
    # The duck-clinic pom sets maven.compiler.release=25, so Maven must run on JDK 25. With only JDK 21 installed, compile for release 21 instead.
    local clinic_java="${CLINIC_JAVA_HOME:-}" release_arg=""
    if [ -z "$clinic_java" ] && [ -x /usr/libexec/java_home ]; then clinic_java="$(/usr/libexec/java_home -v 25 2>/dev/null || true)"; fi
    if [ -n "$clinic_java" ]; then
        say "...  using JDK 25 from $clinic_java for duck-clinic"
    else
        release_arg="-Dmaven.compiler.release=21"
        say "WARN no JDK 25 found (set CLINIC_JAVA_HOME); compiling duck-clinic for release 21 instead"
    fi
    (
        if [ -n "$clinic_java" ]; then export JAVA_HOME="$clinic_java"; fi
        cd "$CLINIC_DIR"
        nohup mvn $release_arg clean package payara-micro:start > "$CLINIC_LOG" 2>&1 &
        echo $! > "$CLINIC_PID"
    )
    local waited=0
    while [ "$(mcp_code)" = "000" ]; do
        kill -0 "$(cat "$CLINIC_PID")" 2>/dev/null || { tail -30 "$CLINIC_LOG"; fail "duck-clinic stopped while starting; see the log above"; }
        [ "$waited" -lt 240 ] || fail "duck-clinic did not answer within 240 s; see $CLINIC_LOG"
        sleep 3; waited=$((waited + 3))
    done
    say "ok   duck-clinic is up after about $waited s"
}

stop_clinic() {
    [ -f "$CLINIC_PID" ] || fail "No $CLINIC_PID: start-clinic did not start a duck-clinic (stop one you started yourself with Ctrl-C)"
    local pid
    pid="$(cat "$CLINIC_PID")"
    for p in $(descendants "$pid") "$pid"; do kill "$p" 2>/dev/null || true; done
    rm -f "$CLINIC_PID"
    say "ok   stopped duck-clinic"
}

check_mcp() {
    local code
    code="$(mcp_code)"
    if [ "$code" = "000" ] && [ "$AUTO_START" != "0" ] && [ -f "$CLINIC_DIR/pom.xml" ]; then
        start_clinic
        code="$(mcp_code)"
    fi
    if [ "$code" = "404" ] && [ -z "$MCP_URL_GIVEN" ]; then
        local first="$MCP_URL" alt="http://localhost:8080/duck-clinic/api/mcp"
        MCP_URL="$alt"; code="$(mcp_code)"
        if [ "$code" = "404" ] || [ "$code" = "000" ]; then MCP_URL="$first"; code="404"; else say "note $first gives 404, using $alt"; fi
    fi
    case "$code" in
        000) fail "Nothing answers at $MCP_URL. Start duck-clinic (./duck-voice.sh start-clinic) or set CLINIC_DIR." ;;
        404) fail "404 at $MCP_URL (and at .../duck-clinic/api/mcp). A duck-clinic is running on port 8080 but not with the MCP endpoint there. Check: curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/duck-clinic/visits   and the deployed context root in the Payara log." ;;
        *)   say "ok   MCP server answers at $MCP_URL (HTTP $code)" ;;
    esac
    export MCP_URL
}

build() {
    if [ "${REBUILD:-}" = "1" ] || [ ! -f "$HOME/.m2/repository/eu/hansolo/mcp/mcp-jakarta-client/0.1.0-SNAPSHOT/mcp-jakarta-client-0.1.0-SNAPSHOT.jar" ]; then
        [ -f "$CLIENT_DIR/pom.xml" ] || fail "Client project not found at $CLIENT_DIR (set CLIENT_DIR)"
        say "...  installing the client (mvn install)"
        (cd "$CLIENT_DIR" && mvn -q -DskipTests install)
    fi
    say "ok   client installed"
    # Always run Maven: it only recompiles what changed. A timestamp check missed new sources unpacked from a zip, and the old jar then ran without --json.
        say "...  building the agent (mvn package)"
        (cd "$HERE" && mvn -q package)
    [ -f "$JAR" ] || fail "Build did not produce $JAR"
    say "ok   agent built"
}

cmd="${1:-}"
case "$cmd" in
    start-clinic) check_tools; start_clinic ;;
    stop-clinic)  stop_clinic ;;
    voice) check_tools; command -v swift >/dev/null || fail "swift not found (install Xcode or the Command Line Tools)"
           check_model voice; check_mcp; build
           say "...  building the Swift front end (swift build)"
           (cd "$HERE/swift" && swift build -c release)
           export DUCK_AGENT_JAR="$JAR"
           exec "$HERE/swift/.build/release/DuckVoice" ;;
    setup) check_tools; check_model setup; check_mcp; build; say "Ready. Next: MODEL=... $0 run   or   MODEL=... $0 test" ;;
    run)   check_tools; check_model run;   check_mcp; build; exec java -jar "$JAR" ;;
    test)  check_tools; check_model test;  check_mcp; build; exec java "$HERE/scripts/TestDriver.java" "$SLOW_SECONDS" java -jar "$JAR" ;;
    *)     sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
