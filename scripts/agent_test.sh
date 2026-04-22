#!/system/bin/sh
# Ophoner concept test — full agentic tool-calling loop on Android
#
# This was the original proof-of-concept that inspired the Android app.
# Kept for reference; the real implementation is the Kotlin/Compose app.
#
# Usage: set API_KEY via env, push to /data/local/tmp/, run under `adb shell`.
#   export API_KEY="fw_..." && adb push scripts/agent_test.sh /data/local/tmp/
#   adb shell "cd /data/local/tmp && sh agent_test.sh"

JQ="/data/local/tmp/jq"
API_KEY="${API_KEY:?Set API_KEY env var to your Fireworks/OpenAI-compatible key}"
BASE_URL="${BASE_URL:-https://api.fireworks.ai/inference/v1/chat/completions}"
MODEL="${MODEL:-accounts/fireworks/routers/kimi-k2p5-turbo}"
WORKDIR="/data/local/tmp/ophoner_workspace"
TMPDIR_AGENT="/data/local/tmp/ophoner_tmp"
MAX_ITERATIONS=10

mkdir -p "$WORKDIR" "$TMPDIR_AGENT"

# Create test files
echo "Hello from Ophoner!" > "$WORKDIR/hello.txt"
echo "Shopping list: milk, eggs, bread" > "$WORKDIR/notes.txt"
mkdir -p "$WORKDIR/src"
printf 'print("hello world")\n' > "$WORKDIR/src/main.py"

# Write tools definition using printf (no heredoc needed)
printf '%s' '[{"type":"function","function":{"name":"shell_execute","description":"Execute a shell command on the Android device. Returns stdout and stderr.","parameters":{"type":"object","properties":{"command":{"type":"string","description":"The shell command to run"}},"required":["command"]}}},{"type":"function","function":{"name":"file_read","description":"Read the full contents of a file","parameters":{"type":"object","properties":{"path":{"type":"string","description":"Absolute path to the file"}},"required":["path"]}}},{"type":"function","function":{"name":"file_write","description":"Write content to a file (creates or overwrites)","parameters":{"type":"object","properties":{"path":{"type":"string","description":"Absolute path to the file"},"content":{"type":"string","description":"Content to write"}},"required":["path","content"]}}},{"type":"function","function":{"name":"file_list","description":"List files and directories at a path","parameters":{"type":"object","properties":{"path":{"type":"string","description":"Directory path to list"},"recursive":{"type":"boolean","description":"If true, list recursively"}},"required":["path"]}}}]' > "$TMPDIR_AGENT/tools.json"

execute_tool() {
    local name="$1"
    local args_file="$2"
    case "$name" in
        shell_execute)
            local cmd=$($JQ -r '.command' < "$args_file")
            echo "[TOOL] shell_execute: $cmd" >&2
            eval "$cmd" 2>&1
            ;;
        file_read)
            local fpath=$($JQ -r '.path' < "$args_file")
            echo "[TOOL] file_read: $fpath" >&2
            cat "$fpath" 2>&1
            ;;
        file_write)
            local fpath=$($JQ -r '.path' < "$args_file")
            $JQ -r '.content' < "$args_file" > "$fpath" 2>&1
            echo "[TOOL] file_write: $fpath" >&2
            echo "Written: $fpath ($(wc -c < "$fpath") bytes)"
            ;;
        file_list)
            local fpath=$($JQ -r '.path' < "$args_file")
            local recursive=$($JQ -r '.recursive // false' < "$args_file")
            echo "[TOOL] file_list: $fpath (recursive=$recursive)" >&2
            if [ "$recursive" = "true" ]; then
                find "$fpath" 2>&1
            else
                ls -la "$fpath" 2>&1
            fi
            ;;
        *)
            echo "Error: unknown tool '$name'"
            ;;
    esac
}

echo "========================================="
echo "  OPHONER AGENT TEST"
echo "  Device: $(getprop ro.product.model)"
echo "  Android: $(getprop ro.build.version.release)"
echo "  Workspace: $WORKDIR"
echo "========================================="
echo ""

USER_MSG="List all files in $WORKDIR recursively, then read hello.txt and notes.txt. Summarize what you found."

echo "USER> $USER_MSG"
echo ""

# Initialize conversation
$JQ -n --arg msg "$USER_MSG" '[{"role":"user","content":$msg}]' > "$TMPDIR_AGENT/messages.json"

ITERATION=0
while [ $ITERATION -lt $MAX_ITERATIONS ]; do
    ITERATION=$((ITERATION + 1))
    echo "--- Iteration $ITERATION ---"

    # Build request body
    $JQ -n \
        --arg model "$MODEL" \
        --argjson messages "$(cat $TMPDIR_AGENT/messages.json)" \
        --argjson tools "$(cat $TMPDIR_AGENT/tools.json)" \
        '{model:$model, messages:$messages, tools:$tools, tool_choice:"auto"}' \
        > "$TMPDIR_AGENT/request.json"

    # Call LLM
    curl -s -X POST "$BASE_URL" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $API_KEY" \
        -d @"$TMPDIR_AGENT/request.json" \
        > "$TMPDIR_AGENT/response.json"

    # Check for errors
    ERROR=$($JQ -r '.error.message // empty' < "$TMPDIR_AGENT/response.json")
    if [ -n "$ERROR" ]; then
        echo "API ERROR: $ERROR"
        $JQ . < "$TMPDIR_AGENT/response.json"
        break
    fi

    FINISH_REASON=$($JQ -r '.choices[0].finish_reason' < "$TMPDIR_AGENT/response.json")

    # Extract assistant message, strip reasoning_content
    $JQ '.choices[0].message | del(.reasoning_content)' < "$TMPDIR_AGENT/response.json" > "$TMPDIR_AGENT/assistant_msg.json"

    CONTENT=$($JQ -r '.content // empty' < "$TMPDIR_AGENT/assistant_msg.json")
    if [ -n "$CONTENT" ] && [ "$CONTENT" != "null" ] && [ "$CONTENT" != "" ]; then
        echo "ASSISTANT> $CONTENT"
    fi

    # Append assistant message to conversation
    $JQ --argjson msg "$(cat $TMPDIR_AGENT/assistant_msg.json)" '. + [$msg]' < "$TMPDIR_AGENT/messages.json" > "$TMPDIR_AGENT/messages_new.json"
    mv "$TMPDIR_AGENT/messages_new.json" "$TMPDIR_AGENT/messages.json"

    if [ "$FINISH_REASON" != "tool_calls" ]; then
        echo ""
        echo "=== AGENT FINISHED (reason: $FINISH_REASON) ==="
        break
    fi

    # Process tool calls
    TOOL_COUNT=$($JQ '.tool_calls | length' < "$TMPDIR_AGENT/assistant_msg.json")
    echo "LLM requested $TOOL_COUNT tool call(s)"

    i=0
    while [ $i -lt "$TOOL_COUNT" ]; do
        TOOL_NAME=$($JQ -r ".tool_calls[$i].function.name" < "$TMPDIR_AGENT/assistant_msg.json")
        TOOL_ID=$($JQ -r ".tool_calls[$i].id" < "$TMPDIR_AGENT/assistant_msg.json")

        # Write parsed arguments to file
        $JQ -r ".tool_calls[$i].function.arguments" < "$TMPDIR_AGENT/assistant_msg.json" | $JQ '.' > "$TMPDIR_AGENT/tool_args.json"

        # Execute
        RESULT=$(execute_tool "$TOOL_NAME" "$TMPDIR_AGENT/tool_args.json")
        echo "  Result: $(echo "$RESULT" | head -5)"
        LINES=$(echo "$RESULT" | wc -l)
        if [ "$LINES" -gt 5 ]; then
            echo "  ... ($LINES total lines)"
        fi

        # Write result to file, then use jq to safely build the tool message
        printf '%s' "$RESULT" > "$TMPDIR_AGENT/tool_result.txt"
        $JQ --arg id "$TOOL_ID" --rawfile result "$TMPDIR_AGENT/tool_result.txt" \
            '. + [{"role":"tool","tool_call_id":$id,"content":$result}]' \
            < "$TMPDIR_AGENT/messages.json" > "$TMPDIR_AGENT/messages_new.json"
        mv "$TMPDIR_AGENT/messages_new.json" "$TMPDIR_AGENT/messages.json"

        i=$((i + 1))
    done
    echo ""
done

# Token usage
$JQ -r '"Tokens: prompt=\(.usage.prompt_tokens) completion=\(.usage.completion_tokens) total=\(.usage.total_tokens)"' < "$TMPDIR_AGENT/response.json" 2>/dev/null
echo ""

# Cleanup
rm -rf "$WORKDIR" "$TMPDIR_AGENT"
echo "Cleaned up."
