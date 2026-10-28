# Computer-use architecture research

Reviewed 2026-09-28. “TriCUA” is interpreted here as TryCua, the team behind `trycua/cua`.

## What transfers to Android

OpenAI describes computer use as a repeated observation, action and observation loop, and supports existing custom UI tools. Short sequences can run locally before returning a new observation. This makes our existing provider-independent tools a suitable integration point; adopting a particular model's native computer tool is optional. [OpenAI computer use](https://developers.openai.com/api/docs/guides/tools-computer-use)

Network/model round trips accumulate. Deterministic work belongs in local code, and independent work can run concurrently. Smaller prompts help context capacity and cost, but prompt-size reductions should not be reported as equivalent latency reductions. [OpenAI latency optimization](https://developers.openai.com/api/docs/guides/latency-optimization)

Cua separates the model, orchestration and OS driver. Its driver does not choose the model. Direct APIs and GUI interaction complement each other. Ophoner already has the analogous boundaries in its provider adapters, AgentLoop and Android device tools. Preserve them. [Cua concepts](https://cua.ai/docs/concepts/what-is-computer-use)

Cua normally combines accessibility and screenshots, favors semantic targets, and falls back to pixel interaction when semantics are inadequate. Ophoner currently favors compact accessibility observations with screenshots requested separately. This is our bandwidth tradeoff, not Cua's default. Canvas interfaces and misleading accessibility trees still need visual checks. [Capture and delivery](https://cua.ai/docs/concepts/capture-and-delivery-modalities)

An accepted input does not establish success. Cua distinguishes confirmed effects, suspected no-ops and unverifiable outcomes. Its verification guide recommends checking app-owned state and observing before retrying an uncertain action. [Action policy](https://cua.ai/docs/reference/cua-driver/action-selection-policy), [Verification](https://cua.ai/docs/how-to-guides/driver/verify-a-desktop-action)

Cua also binds semantic targets to their context and offers bounded observations. Its driver describes one-use capture identifiers for perception-derived coordinates. Our node snapshots are invalidated after node actions, and now check both package and Android window ID before dispatch. Full screenshot-to-coordinate capture binding remains future work. [Semantic snapshots](https://cua.ai/docs/reference/cua-driver/browser-semantic-snapshots), [Driver source](https://raw.githubusercontent.com/trycua/cua/main/libs/cua-driver/README.md)

Cua's listed platforms are macOS, Windows and Linux. These are architectural lessons for our native Android executor, not a claim that Cua's driver supports Android. [Platform support](https://cua.ai/docs/reference/cua-driver/platform-support)

## Implemented passes

1. Every successful screen action returns a fresh bounded accessibility observation. The next model turn can act on it without a separate read.
2. Keep two recent full screen trees in request history, retaining older action outcomes and preserving the stored transcript.
3. Wait for expected controls using accessibility events, with a local polling fallback for apps that omit events. All waits are bounded and cancellable.
4. An action can include `target_text` or `view_id`, plus optional `timeout_ms`, to await a destination in the same call. This supports navigation within and between apps without an extra model round trip for a separate wait. Results report `action_dispatched`, `target_found` when requested, and `verification`. `target_visible` only confirms the requested control is visible, not completion of the overall task. No destination condition produces `unverified`. A timeout preserves the action outcome and fresh screen; it does not automatically repeat the action.
5. Reject node actions if the foreground window changes, even within the same package. User takeover invalidates previously planned actions. Stop repeated identical successful results rather than imposing a 40-turn production limit.

Example: `screen_control` with `action: "back"`, `target_text: "Inbox"`, and `timeout_ms: "3000"` dispatches Back, waits locally for Inbox, and returns the new screen. If Inbox does not appear, the model receives the actual screen to decide how to recover.

Direct contacts, calendar, file and intent tools remain preferred when they satisfy the task with the granted capabilities. Dependent UI actions remain sequential. Blindly parallelizing taps or replaying navigation would trade apparent speed for incorrect actions.

## Measurements and next device checks

The scripted eight-action fixture uses 10 model requests with fused observations versus 18 with separate reads. Synthetic screen-history compaction reduces estimated tokens from 20,922 to 5,450. Virtual-clock tests cover immediate event wakeups, missing-event fallback, bounded settling and cancellation. These establish protocol and local-wait improvements, not real-device end-to-end speed.

Tomorrow's checks should record task completion, model requests, elapsed time, time waiting for Android, and recovery attempts for the same app workflows. Include back navigation, keyboard appearance, app switches, slow loading screens and user takeover. Test screenshots when accessibility is incomplete. Keep the phone untouched until that session.

Further candidates are combined tree/image observations with exact coordinate mapping, guarded multi-field form filling, and task-level outcome assertions. They are not implemented by this change. OpenAI's integration guide emphasizes correct screenshot coordinate mapping and evaluation of task success, latency and recovery. [Integration guidance](https://developers.openai.com/api/docs/guides/tools-computer-use-integration)

See [interaction behavior and test fixtures](AGENT_INTERACTION.md) for the floating pill, follow-ups and reproducible local measurements.
