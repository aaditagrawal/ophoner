# Ophoner

An on-device Android AI agent. Chat with Claude or any OpenAI-compatible LLM and let it run tools on your phone: shell commands, file read/write scoped to a folder you pick, web fetch and search, and basic device control.

## Core features

- **Multi-provider chat** — Claude, OpenAI, OpenRouter, Gemini, custom OpenAI/Anthropic-compatible endpoints, plus ChatGPT/Codex device-code login; plan presets, remote model catalogs, and manual model slugs
- **Tools** — `shell_execute`, `file_read`, `file_write`, `file_list`, `file_delete`, `file_move`, `web_fetch`, `web_search`, `device_control`, `intent_launch`, `app_list`
- **YOLO mode** — settings toggle that auto-approves soft tool gates and raises the agent iteration cap (dangerous; use with care)
- **Share target** — share text into a new chat via Android `ACTION_SEND`
- **Folder chats** — SAF-scoped conversations; file tools enforce the folder root (not just the prompt)
- **Folder skills** — loads `SKILL.md` and `.ophoner/skills/*.md` from the active folder into the system prompt
- **Conversation history** — persisted locally, grouped by folder vs general, sorted by recency
- **Shizuku support** — ADB-level shell when Shizuku is installed and granted
- **Appearance** — Teenage Engineering–inspired morphic UI; orange accent + **DM Mono** / DM Sans

Built with Jetpack Compose, Material 3 Expressive, Hilt, Room.

## Build

Requires JDK 17.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## License

MIT
