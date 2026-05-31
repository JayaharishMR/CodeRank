#!/bin/bash
# judge-entrypoint.sh -- compile once, run against N test cases
#
# This entrypoint is used in "judge mode" to evaluate user-submitted
# code against multiple test cases in a single container invocation.
# The host overrides the default entrypoint via Docker API to use this
# script instead of entrypoint.sh (which is for playground/single-run).
#
# Input contract (bind-mounted at /workspace by host):
#   Main.java (or other .java files) -- user's source code
#   testcases/          -- directory with 0.in, 1.in, 2.in, ...
#   testcase_count.txt  -- single integer: number of test cases
#
# Environment variable:
#   CODERANK_TIME_LIMIT_SECONDS -- per-test-case timeout (default: 10)
#
# Output contract (to stdout):
#   Each test case result is preceded by a ===CODERANK_RESULT=== sentinel
#   followed by a JSON line with index, exitCode, stdout, stderr, timeMs.

# ── Helper: JSON string escaping ───────────────────────────────────
# Escape special characters so stdout/stderr can be embedded in valid
# JSON strings. Order matters: backslashes must be escaped first to
# avoid double-escaping characters we insert later.
escape_json() {
    local s="$1"
    s="${s//\\/\\\\}"     # backslash first
    s="${s//\"/\\\"}"     # double quotes
    s="${s//$'\n'/\\n}"   # newlines
    s="${s//$'\r'/\\r}"   # carriage returns
    s="${s//$'\t'/\\t}"   # tabs
    printf '%s' "$s"
}

# ── Step 1: Prepare output directory ───────────────────────────────
# /tmp is backed by a size-limited tmpfs so compiled .class files
# never touch real disk and are cleaned up when the container exits.
mkdir -p /tmp/out

# ── Step 2: Compile ────────────────────────────────────────────────
# Compile all Java files in /workspace. Stderr is captured so we can
# relay it as a structured COMPILE_ERROR response if compilation fails.
javac -d /tmp/out /workspace/*.java 2>/tmp/compile_error.txt
COMPILE_EXIT=$?

if [ $COMPILE_EXIT -ne 0 ]; then
    echo "COMPILE_ERROR"
    cat /tmp/compile_error.txt
    exit 1
fi

# ── Step 3: Detect main class ─────────────────────────────────────
# Grep source files for a main method signature so we know which
# class to pass to `java`. Only the first match is used -- multiple
# main classes are not supported.
MAIN_CLASS=$(grep -rl 'public static void main' /workspace/*.java | head -1 | xargs basename | sed 's/.java//')

if [ -z "$MAIN_CLASS" ]; then
    echo "COMPILE_ERROR"
    echo "No main method found"
    exit 1
fi

# ── Step 4: Read test case count ───────────────────────────────────
# The host writes the number of test cases to this file so the script
# knows how many input files to iterate over.
TEST_COUNT=$(cat /workspace/testcase_count.txt)

# ── Step 5: Configure time limit ──────────────────────────────────
# Default to 10 seconds if the host did not set the env variable.
# This is a per-test-case limit, not a total execution limit.
TIME_LIMIT=${CODERANK_TIME_LIMIT_SECONDS:-10}

# ── Step 6: Run each test case ─────────────────────────────────────
# Compile happened once above; now we execute the same class N times,
# each time with a different input file piped to stdin. Results are
# emitted as sentinel-delimited JSON lines so the host can parse them
# reliably even if user code writes arbitrary text to stdout/stderr.
for (( i=0; i<TEST_COUNT; i++ )); do
    # Record start time in nanoseconds for millisecond-precision timing
    START_NS=$(date +%s%N)

    # Run with per-test timeout; stdin comes from the numbered input file.
    # Exit code 124 from `timeout` signals the process exceeded the limit.
    timeout "$TIME_LIMIT" java -cp /tmp/out "$MAIN_CLASS" \
        < "/workspace/testcases/$i.in" \
        > "/tmp/stdout_$i.txt" \
        2> "/tmp/stderr_$i.txt"
    EXIT_CODE=$?

    # Record end time and compute elapsed milliseconds
    END_NS=$(date +%s%N)
    ELAPSED_MS=$(( (END_NS - START_NS) / 1000000 ))

    # Read captured output from temp files
    STDOUT_RAW=$(cat "/tmp/stdout_$i.txt")
    STDERR_RAW=$(cat "/tmp/stderr_$i.txt")

    # Escape for safe JSON embedding
    STDOUT_ESCAPED=$(escape_json "$STDOUT_RAW")
    STDERR_ESCAPED=$(escape_json "$STDERR_RAW")

    # Emit sentinel + structured JSON line
    echo "===CODERANK_RESULT==="
    printf '{"index":%d,"exitCode":%d,"stdout":"%s","stderr":"%s","timeMs":%d}\n' \
        "$i" "$EXIT_CODE" "$STDOUT_ESCAPED" "$STDERR_ESCAPED" "$ELAPSED_MS"
done

# Always exit 0 -- individual test failures are captured in the
# structured output above. A non-zero exit here would indicate a
# script-level failure (compilation error, missing main class, etc.).
exit 0
