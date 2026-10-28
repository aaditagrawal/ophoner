# Agent interaction and phone-control performance

## Follow a task in other apps

With Ophoner accessibility enabled, an active run shows a movable status pill outside Ophoner. Tap it to expand the latest assistant output, Stop, Resume, Open chat, Hide pill, and a follow-up field. Expanding asks the run to pause before its next action. An action already dispatched may finish. Sending a follow-up saves it and resumes the run; it enters model context at the next complete turn. The pill is hidden on the lock screen and during model screenshots.

The ongoing notification mirrors the current action and latest response excerpt. Approval requests open the existing in-app review dialog. The pill does not grant access or auto-approve actions.

## Prompts and branches

User messages have Copy, Edit in branch, and Fork here actions. Editing opens a new thread with the history before that prompt and an editable draft. Forking copies history through the selected prompt. The original thread remains intact, including any active run. Branches preserve provider and folder scope. Branching does not reverse actions already performed on the device.

The main composer accepts follow-ups during a run and has a separate Stop control. Focusing it requests a pause; sending resumes. Follow-ups are stored before acceptance is reported. A follow-up racing with completion stays as a draft if the run has already closed. Pausing invalidates previously planned actions so the agent must reassess before continuing.

## Execution budget

Production runs no longer stop at 25 or 40 model turns. The existing 20-minute elapsed-time limit remains, along with context limits, tool deadlines, Stop, and repeated-failure detection. Six consecutive identical successful action results also stop a stalled run. Random screen snapshot identifiers do not count as progress. Explicit turn limits remain available to deterministic tests.

## Optimization passes and local results

1. **Return screen observations with actions.** `screen_control` waits for Android content events to settle and returns a fresh screen with every action. A controlled eight-action workflow required 10 model requests versus 18 for the separate action/read protocol, a 44% reduction. This measures request count with a scripted provider, not real-provider latency.
2. **Reduce screen context.** Screen trees omit empty layout containers, null/default fields and duplicated labels. Request copies retain the two latest full screen observations and omit older node lists while retaining action outcomes. Stored transcripts remain intact. Ten synthetic 100-control observations decreased from 20,922 to 5,450 estimated tokens, a 74% reduction. These are estimates for the fixture, not provider billing counts.
3. **Wait locally for controls.** `screen_control.wait_for` accepts `target_text`, `view_id`, and a bounded `timeout_ms`. A virtual-clock test detects a control at 75 ms when its accessibility event arrives at 75 ms, with no further model request. Apps that omit events get a 250 ms local polling fallback. Screen settling uses an 80 ms quiet period, up to 400 ms waiting for the first event and 800 ms for continuous changes. Cancellation interrupts these waits.
4. **Combine navigation and destination waits.** Any screen action accepts an expected `target_text` or `view_id` and waits locally before returning its screen. Results distinguish dispatched actions, visible targets and unverified outcomes. Missing targets do not cause automatic replay. Node actions also reject a changed Android window within the same app. See [the architecture research](COMPUTER_USE_ARCHITECTURE.md) for the OpenAI and TryCua sources behind these choices.

The focused JVM suite also covers follow-ups at completion, failed persistence, pause/resume, stale-action rejection, retrying a partially streamed response, branch scope/prefix preservation, and tasks exceeding 40 successful steps.

## Local validation

The final isolated build passed 69 focused JVM tests across agent, repository and device packages, assembled debug and benchmark APKs, and passed Android debug lint. These checks do not exercise the real Android window manager or Samsung keyboard. Debug output uses the local development signing key; updating the previously installed app requires its matching signing key.

## Device verification still required

The user deferred device testing until the next session. Check pill placement, drag/tap behavior, text entry with the Samsung keyboard, pause/resume, transferring drafts to the chat, copy/edit/fork controls, screenshot exclusion, lock-screen hiding, and action/wait timing in real apps.

The benchmark-only `dev.ophoner.AgentInteractionActivity` starts an offline streaming fixture against an ephemeral loopback server. It uses the separate `dev.ophoner.benchmark` app, has no real provider credentials, and performs no external device tasks. Its server expires after three minutes. This fixture is not included in production or debug APKs.
