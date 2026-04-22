# Security model & known gaps

Ophoner is an LLM agent that runs tools on your device. This file documents what's protected, what isn't, and the threat model assumed.

## What's protected

### Credential storage
API keys (Claude, OpenAI, etc.) are held in `EncryptedSharedPreferences` backed by the Android Keystore master key (AES256-GCM for values, AES256-SIV for prefs keys). The rest of a `ProviderConfig` (display name, base URL, model id) lives in DataStore in plaintext — it's not secret.

Keys never enter:
- Local logs
- Cloud backup (`allowBackup=false` + `dataExtractionRules` exclude sharedpref/database/file/external)
- Crash reports (none are wired up)

### Network
- `network_security_config.xml` enforces `cleartextTrafficPermitted=false` globally
- All providers are HTTPS
- OkHttp timeouts: `connect 30s`, `readTimeout 120s` per-chunk, `writeTimeout 30s`, `callTimeout 0` (streams can be long)
- Provider stream errors propagate as typed `LlmResponseChunk.Error` instead of silent termination

### File tools
- All paths validated before handoff to `FileAccessManager`:
  - Null bytes rejected
  - Canonicalized paths must not escape the SAF-granted tree
  - Absolute paths under `/system`, `/proc`, `/sys`, `/vendor`, `/apex`, `/dev`, or other apps' `/data/data/<pkg>` trees are rejected
- The underlying `FileAccessManager` uses SAF `DocumentFile` APIs; the OS itself sandboxes writes to the URI tree the user picked

### Web tools
- `WebFetchTool` rejects non-http(s) schemes (blocks `file://`, `ftp://`, `gopher://`)
- SSRF guard: host resolved via `InetAddress.getAllByName`, each returned address checked against RFC 1918 + loopback + link-local + CGNAT (100.64/10) + IPv6 ULA (fc00::/7) + IPv6 link-local (fe80::/10)
- `localhost`, `*.local`, `*.internal` rejected by string match
- Max response body read into memory: 2 MB (hard cap before parsing)

### Shell tool
- **Deny list:** `rm -rf /`, `mkfs`, `dd if=…of=/dev/`, fork bombs, `chmod 777 /`, writes into `/system`, `tee /system`, `mount`/`umount`, `reboot`/`shutdown`/`halt`, `su`, writes to other apps' `/data/data`
- **Allow list (soft):** `ls`, `pwd`, `cat`, `grep`, `find`, `echo`, `which`, `ps`, `cd`, `head`, `tail`, `wc`, `stat`, `df`, `du`, `date`, `uname`, `env`, `printenv`, `whoami` — these get classified `ALLOW(allowlist)`. Commands outside both lists are `ALLOW(unscoped)` and logged
- **Audit log:** every execution (allowed or rejected) is appended to `getExternalFilesDir(null)/shell_audit.log` with timestamp + outcome + sanitized command
- When Shizuku is running, shell commands go through it; the policy gate runs **before** both the Shizuku and sandboxed paths

### Database
- Room schema versions managed by explicit `Migration` objects — no `fallbackToDestructiveMigration` in release
- Schema exported to `app/schemas/` for diffing

### Build / shipping
- `R8` minification + resource shrinking on release
- `isDebuggable=false` on release, explicit in the build script
- ProGuard rules cover Hilt, Room, OkHttp, Coroutines, Shizuku, Tink
- Strict full-mode R8 keep rules (default-constructor-keep no longer implicit)
- Signing config reads from `keystore.properties` at project root (never committed)

## Known gaps

### No per-tool user confirmation UI
The agent can call `shell_execute` or `file_write` without asking. The denylist/allowlist narrows the blast radius but does not close it. An adversarial prompt can still persuade the agent to do damage inside the SAF-granted folder or via `ALLOW(unscoped)` commands.

**Mitigation today:** review `shell_audit.log` after running. Don't grant folders you can't afford to lose. Don't enable Shizuku if you can't trust the LLM.

**Fix path:** add a Compose dialog that blocks the tool future until the user taps "Run" / "Deny", with an opt-in "always allow this exact command" toggle.

### SQLite DB is plaintext
Conversation contents (user prompts + model replies) live unencrypted in `ophoner.db`. On a rooted device, `adb pull` can read them.

**Fix path:** SQLCipher wired in via Room's `openHelperFactory`, with the passphrase stored in the Keystore.

### No automated tests
There are no unit or instrumentation tests. Security-critical validators (path traversal, SSRF, shell classifier) have no regression safety net.

**Fix path:** minimum bar before public contributions — JVM tests for `FileAccessManager.validatePath`, `WebFetchTool.validateUrlForSsrf`, `ShellExecuteTool` classifier, Room migrations.

### No crash reporting
Crashes don't leave the device. If you're debugging for someone else, you're debugging blind.

**Fix path:** Crashlytics or Sentry behind a consent toggle.

### Folder-mode boundary is prompt-level
When the user starts a "folder chat," a system-prompt instruction tells the agent to stay within the folder. Nothing at the tool layer enforces this — `shell_execute` can still touch anything outside the folder that the app has access to.

**Fix path:** track the folder URI in agent state and reject shell commands whose working directory or arguments escape it.

### `WRITE_SETTINGS` permission
Declared but only useful if the user grants it via `Settings.System.canWrite()`. It's a protected permission; Play Store reviewers will ask about it. If shipping publicly: write a clear rationale in the store listing.

### KSP opt-out flag
`android.disallowKotlinSourceSets=false` is set in `gradle.properties` because KSP 2.2.10-2.0.2 still registers generated sources via `kotlin.sourceSets` (AGP 9's new DSL blocks this). Remove when KSP 2.3.3+ ships.

## Reporting issues

Open a GitHub issue or email the maintainer. For anything that looks like a real exploit, please email rather than opening a public issue.
