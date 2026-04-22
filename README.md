# Ophoner

An on-device Android AI agent. Chat with Claude or any OpenAI-compatible LLM and let it run tools on your phone: shell commands, file read/write scoped to a folder you pick, web fetch and search, and basic device control.

## Core features

- **Multi-provider chat** — Claude (native streaming) and any OpenAI-compatible endpoint
- **Tools** — `shell_execute`, `file_read`, `file_write`, `file_list`, `web_fetch`, `web_search`, `device_control`
- **Folder chats** — scope a conversation to a folder via SAF; the agent stays inside it
- **Conversation history** — persisted locally, grouped by folder vs general, sorted by recency
- **Shizuku support** — ADB-level shell when Shizuku is installed and granted

Built with Jetpack Compose, Material 3, Hilt, Room.

## Build

Requires JDK 17.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## License

MIT
