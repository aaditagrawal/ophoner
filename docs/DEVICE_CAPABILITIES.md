# Device capabilities without root

Open **Settings → Agent → Set up device access** (the recommended option above **Device capabilities**), then tap **Enable all & continue**. This enables all supported capability switches and walks through Android permissions, accessibility, notification access, shared files, system settings, and Shizuku. Existing grants are skipped. Returning from Settings advances to the next unfinished step; declined or skipped access appears in the final summary. You can leave and return, or review remaining access later.

**Device capabilities → Enable all** opens the same guided setup. Individual switches, **Set up access**, and **Test** remain available below it. YOLO does not enable capabilities or grant Android permissions.

The app never roots the phone or provisions it as a managed device.

## Available tools

| Tool | Actions | Access |
| --- | --- | --- |
| `device_capabilities` | Live status of every integration | None |
| `screen_control` | Read exposed UI nodes; click, long-click, type, scroll; Back, Home, Recents, notification shade; coordinate tap/swipe | Accessibility service + app-control switch |
| `screen_capture` | Current display image, delivered to the model during the run | Android 11+, accessibility + screen-image switches, vision-capable model |
| `advanced_device` | Shell identity, Wi-Fi/Bluetooth requests, get/set system settings, list packages, force-stop/enable/disable a package, foreground activity, navigation keys, selected diagnostics | Shizuku started and authorized + advanced-control switch |
| `notifications` | List active notifications, dismiss, invoke an exposed action, send a reply through an exposed reply action | Notification listener + notification switch |
| `contacts` | Search names/numbers, create contacts, update names/numbers | Contacts switch + read/write contacts permissions |
| `calendar` | List calendars and event instances; create, update, delete events | Calendar switch + read/write calendar permissions |
| `location` | Current location with accuracy and timestamp | Location switch + coarse/fine permission; keep app visible |
| `media` | Search images/video/audio by filename, open an item | Media switch + applicable media permissions |
| `shared_files` | List/read/write/mkdir/copy/move/delete in primary shared storage | Shared-files switch + all-files access |
| `phone_actions` | Alarm, timer, maps destination, dialer, SMS draft, calendar editor | Alarm permission is declared; recipient apps handle their own UI |

Existing `device_control` Wi-Fi/Bluetooth actions now use Shizuku when enabled and available. Volume control has the required audio-settings manifest permission. Flashlight and brightness have setup flows. Ordinary `shell_execute` reports whether it ran through Shizuku or the app sandbox.

## Shizuku setup

Open **Settings → Shell privileges → Set up Shizuku** for the standalone guide, or follow the Shizuku step in the full setup. The guide detects whether you need to install Shizuku, enable Developer options, enable wireless debugging, start Shizuku, or authorize Ophoner. On Android 11+, it explains pairing using the notification and links to wireless debugging settings. Older devices get a copyable ADB command. Once Shizuku starts, Ophoner requests authorization automatically and verifies the connection.

After connecting, **Device capabilities → Advanced device control → Test** checks the shell identity. An ADB-started service should report `uid=2000(shell)`.

The integration uses a Shizuku UserService with bounded output and command deadlines. Non-root Shizuku must be started again after reboot. Device/OEM restrictions can reject individual commands; a connected service does not mean every system action is supported. Ophoner does not silently retry a failed privileged command as an ordinary app command.

## Accessibility and screenshots

Enable **Ophoner device control** under Android Accessibility settings. Sideloaded apps may first need **Allow restricted settings** under Android's app-info menu. That is a user-controlled Android action.

`screen_control` returns a snapshot ID and node IDs. Node actions require both and reject missing/stale controls. Each action returns a fresh screen after a bounded event-driven settle, so the agent can verify and choose its next action without a separate read. `wait_for` waits locally for visible text or a resource ID. Coordinate gestures are available for interfaces with poor accessibility trees. Password-node text is excluded, and password entry is left to the user.

Screen capture uses AccessibilityService's screenshot API rather than continuous MediaProjection recording. It requires Android 11 or later and cannot capture protected windows. Screenshots are resized to a maximum dimension of 1280 pixels and passed to supported provider adapters as images. Only the most recent screenshot is retained in the live model context. Pixels are not saved in conversation history. Text returned by other tools is part of the normal chat history.

## Permissions, approvals, and stopping

New capabilities default to off. Android grants and Ophoner switches are separate: switching a capability off stops the corresponding tools from using it without revoking the OS grant.

Shell commands, file mutations, notification actions/replies, contacts/calendar writes, and advanced device mutations require a **Run once** approval. Requests expire after two minutes. YOLO skips these prompts; it does not bypass Android security. Enabling app control authorizes UI gestures during agent tasks, so it can perform actions in the visible app without a prompt for each tap.

Active runs have an **Open / Stop all** notification. Pending approvals update that notification. The foreground service runs only while tasks are active, uses the `specialUse` type for user-initiated cross-app agent tasks, and does not restart orphaned work after process death. Android can still stop the app. Task cancellation stops pending approvals and shell execution; an action already delivered to another app cannot be undone by cancellation.

## Boundaries

- Folder chats retain their SAF file boundary. Raw shell and the broad shared-file tool are unavailable there; start a general chat for device-wide file work.
- All-files access covers primary shared storage, not other apps' private databases. Android/data and Android/obb are excluded. Shared-file deletion is non-recursive; directories must be empty.
- Notification access exposes current notifications, not complete chat history. Replies are only available when the originating app supplies a reply action.
- Location is requested on demand while Ophoner is visible; there is no background location collection.
- Contacts updates change the first matching name/phone row. Calendar update/delete affects an event series when the ID belongs to a recurring event.
- Media organization can use shared-file copy/move/delete where all-files access permits it.
- Dial/SMS/calendar intents open the receiving app. Launch success does not mean a call was placed, a message sent, or an event saved.
- Screen, notification, and personal-data results may be sent to the selected AI provider. Do not enable access for data you do not want included in agent requests.

## Verification

JVM tests cover path traversal and symlink boundaries, folder scoping, argument quoting, process output limits/deadlines, approval cancellation and YOLO behavior, and image payloads for Chat Completions, Responses, and Anthropic Messages.

Use the capability-page tests on the target phone to verify Android grants, service binding, screenshot support, and OEM-specific Shizuku commands. These tests run locally; the screenshot test does not upload the captured image.

References: [Shizuku setup and UserService](https://github.com/RikkaApps/Shizuku-API), [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService), [shared storage](https://developer.android.com/training/data-storage/manage-all-files), [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [OpenAI image inputs](https://developers.openai.com/api/docs/guides/images-vision), [Anthropic image inputs](https://platform.claude.com/docs/en/build-with-claude/vision).

See [Agent interaction and phone-control performance](AGENT_INTERACTION.md) for the floating status pill, follow-ups, prompt branches, execution budgets, and local benchmark results.
