# Ophoner

An on-device Android AI agent. Chat with Claude or any OpenAI-compatible LLM and let it run tools on your phone: shell commands, file read/write scoped to a folder you pick, web fetch and search, and basic device control.

## Core features

- **Multi-provider chat** — Claude, OpenAI, OpenRouter, Gemini, custom OpenAI/Anthropic-compatible endpoints, plus ChatGPT/Codex device-code login; plan presets, remote model catalogs, and manual model slugs
- **Tools** — `shell_execute`, `file_read`, `file_write`, `file_list`, `file_delete`, `file_move`, `web_fetch`, `web_search`, `device_control`, `intent_launch`, `app_list`
- **YOLO mode** — settings toggle that auto-approves soft tool gates (dangerous; use with care)
- **Share target** — share text into a new chat via Android `ACTION_SEND`
- **Folder chats** — SAF-scoped conversations; file tools enforce the folder root (not just the prompt)
- **Folder skills** — loads `SKILL.md` and `.ophoner/skills/*.md` from the active folder into the system prompt
- **Conversation history** — persisted locally, grouped by folder vs general, sorted by recency
- **Shizuku support** — ADB-level shell when Shizuku is installed and granted
- **Appearance** — AndBrowser-inspired: frosted-glass controls floating over content (real backdrop blur on Android 12+ via [Haze](https://github.com/chrisbanes/haze)), neutral surfaces, **DM Sans** with DM Mono for code, [Lucide](https://lucide.dev) icons (ISC), and a restrained blue accent

Built with Jetpack Compose, Material 3 Expressive, Haze, Hilt, Room.

## Device capabilities

Open **Settings → Agent → Device capabilities** to enable non-root app automation, Shizuku device actions, notification actions/replies, screenshots, contacts, calendar, location, media, and shared-file tools. Each integration has setup and local test controls. See [setup, tool reference, and limits](docs/DEVICE_CAPABILITIES.md).

## Log in with ChatGPT

1. Open **Settings** and tap **Log in with ChatGPT**.
2. Tap **Copy & open ChatGPT** to copy the one-time code and open an in-app browser tab. Sign in and paste the code. An external-browser option is also available.
3. Return to Ophoner. Once approved, the app saves the connection and selects ChatGPT as the active provider. Settings shows **Signed in**, your email when provided by ChatGPT, and the current model. **Manage** lets you view or copy saved auth tokens and sign out. Select providers and models from the dropdown beside Send in the prompt box. Tokens stay masked until you reveal them.

For ChatGPT connections, the **Fast** on/off toggle in the top-right corner controls processing speed. Off uses Standard, the default. Fast requests the faster service tier using the same model and subscription; availability depends on your account and model. GPT-6 Fast mode uses 2.5× the standard credits. The choice is saved per connection and applies to the next run. See [OpenAI's speed documentation](https://learn.chatgpt.com/docs/agent-configuration/speed).

Enable device code login in ChatGPT's security settings if prompted. Workspace accounts may require an administrator to enable it. See [OpenAI's device login instructions](https://learn.chatgpt.com/docs/auth#login-on-headless-devices).

The dialog shows the code's remaining lifetime, normally 15 minutes or the expiry returned by OpenAI. Temporary DNS, connection, and server failures retry automatically without discarding the code. Before a code is issued, connection retries continue for up to two minutes. Use **Try again** for a new code after expiry, or **Cancel** to stop. Credentials are encrypted on the device, refresh automatically, and are excluded from provider exports. **Sign out of ChatGPT** removes the connection and credentials while keeping conversations.

This uses Ophoner's existing unofficial Codex subscription integration, not a general-purpose ChatGPT identity provider. Access depends on your account and workspace; OpenAI may change the underlying endpoints. API-key providers remain available through **Add provider**.

## Model catalogs

OpenAI API, Anthropic, Gemini, OpenRouter, and OpenCode Go model suggestions come from
[models.dev](https://models.dev/api.json). Opening a provider group in the composer dropdown shows saved
models immediately and checks for updates, using ETags to avoid downloading an
unchanged catalog. **Refresh models** checks again. No API key is sent to models.dev.

Suggestions include models with text input/output and tool calling, ordered by
release date with deprecated entries excluded. The selected model and manually
added IDs stay available. The last successful catalog is saved for offline use;
if a refresh fails, the dropdown labels the saved list. On a first launch without
a cache, the app can fall back to the provider's own API when a key is configured.

ChatGPT defaults to **Automatic newest**. Login and opening chat check
the account's model list and rank it by models.dev release dates, using the account's
preferred order for models released on the same day. The previous `gpt-5.4` default
migrates automatically. Selecting a specific model turns automatic selection off.
Offline, the app retains the last selected model. Custom endpoints use their own
model discovery; public catalog entries do not guarantee access for an account.

OpenCode Go connects with an API key from [OpenCode](https://opencode.ai/auth).
The catalog selects the correct Chat Completions, Responses, or Anthropic Messages
protocol for each model. Use **Add model slug…** in the composer dropdown for a
model missing from the catalog; model selection itself is never a text field.

## Build

Requires JDK 17.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## License

MIT
