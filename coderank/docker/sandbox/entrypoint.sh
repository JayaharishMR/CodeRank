#!/bin/bash
# entrypoint.sh -- compiles and runs Java code in sandbox
#
# Flow: compile -> detect main class -> execute with optional stdin.
# All output is written to stdout/stderr so the host process can
# capture it via `docker logs` or container attach.

# /tmp is backed by a size-limited tmpfs (set via Docker --tmpfs flag)
# so compiled .class files never touch real disk and are automatically
# cleaned up when the container exits.
mkdir -p /tmp/out

# ── Step 1: Compile ──────────────────────────────────────────────
# Compile all Java files in /workspace (mounted by the host).
# Stderr is captured to a file so we can relay it as a structured
# COMPILE_ERROR response if compilation fails.
javac -d /tmp/out /workspace/*.java 2>/tmp/compile_error.txt
COMPILE_EXIT=$?

if [ $COMPILE_EXIT -ne 0 ]; then
    echo "COMPILE_ERROR"
    cat /tmp/compile_error.txt
    exit 1
fi

# ── Step 2: Detect main class ───────────────────────────────────
# Grep source files for a main method signature so we know which
# class to pass to `java`. Only the first match is used -- multiple
# main classes are not supported.
MAIN_CLASS=$(grep -rl 'public static void main' /workspace/*.java | head -1 | xargs basename | sed 's/.java//')

if [ -z "$MAIN_CLASS" ]; then
    echo "COMPILE_ERROR"
    echo "No main method found"
    exit 1
fi

# ── Step 3: Run ─────────────────────────────────────────────────
# If the host placed a stdin.txt in /workspace, pipe it as standard
# input; otherwise run with no input (interactive read will EOF
# immediately, which is the desired behavior).
if [ -f /workspace/stdin.txt ]; then
    java -cp /tmp/out "$MAIN_CLASS" < /workspace/stdin.txt
else
    java -cp /tmp/out "$MAIN_CLASS"
fi
