# Agent runtime

Ophoner runs its own Kotlin agent loop on Android. Provider adapters handle model protocols; tools execute device operations. The chat UI observes the application-scoped run manager.

## Execution and recovery

- A response must have an explicit successful provider terminal event. Every tool call must finish and contain valid JSON. The runtime never repairs partial arguments or executes tools from an interrupted response.
- Tool arguments are checked against the built-in JSON Schema subset before approval and execution. Remote MCP servers also validate their own schemas.
- The run manager checkpoints a stable transcript row before each tool starts and after each result. The loop waits for each database acknowledgment. Completed results survive cancellation or process death. Interrupted calls have an explicit unknown-outcome result; they are not automatically replayed.
- Recovery is transcript-based. Reopening a conversation and continuing supplies those recorded outcomes to the model. Android process death does not automatically restart an active run.
- Up to four explicitly parallel-safe reads execute together. Results publish as each read completes; their model context order remains the original call order.
- Screen, app-launch, device-control, notification and shell workflows acquire a shared phone UI lease. The lease remains held across model turns through run completion or cancellation, protecting observation/action/verification from other Ophoner conversations.
- Individual tool timeouts come from tool policy. Runs have a 20-minute deadline and the existing 25-turn limit, or 40 in YOLO mode. Three identical all-failing tool batches stop as stalled.
- Transient model failures receive at most two retries with jitter and `Retry-After` support. Failed partial responses are discarded before retrying. Tool side effects are never retried by this policy.
- Stop reasons distinguish completion, cancellation, error, context limit, stalled execution and exhausted budget.

## Context and caching

Stored conversation history remains intact. The request copy has a separate context budget, including schemas, image allowance and reserved output capacity. Token counts used for budgeting are conservative estimates, not tokenizer measurements.

Older large tool results are shortened first. Oversized recent results retain their beginning and end with an omission notice. If necessary, completed older exchanges are summarized, including within a long single task. User instructions and recent call/result pairs remain intact. If mandatory context or the summary request cannot fit, the run stops with an actionable error instead of silently dropping the task.

Provider configuration supports these fields in imported/exported provider JSON:

```json
{
  "contextWindowTokens": 32768,
  "maxOutputTokens": 4096,
  "promptCaching": true
}
```

Use the selected model's actual context capacity when overriding the conservative default. Output limits are sent to supported chat and Claude endpoints. The ChatGPT subscription adapter does not invent unsupported request fields.

Tool ordering is deterministic and unchanged request prefixes remain stable. OpenAI receives a stable conversation cache-routing key; Claude receives cache breakpoints for tools, instructions and conversation history. Custom gateways do not receive provider-specific cache hints. Disabling `promptCaching` removes Ophoner's explicit hints; it does not disable a provider's automatic caching.

Reported input/output and cache-read/cache-creation token counts are saved with each completed iteration. Missing usage remains unknown. Cache hits depend on provider/model support, prefix matching, minimum size and expiry; Ophoner does not promise a hit or measured latency improvement.

## Folder skills

The root `SKILL.md` remains project instructions. Files in `.ophoner/skills/*.md` appear initially as names, paths and short descriptions. The agent calls `skill_load` with an exact catalog path when it needs the full instructions. Reads respect the conversation folder or configured working directory. Large skills have an explicit continuation notice for `file_read`.

## MCP

The `mcp_manage` tool lets the agent register and manage HTTP or local stdio servers requested by the user:

```json
{"action":"add","name":"example","url":"https://your-server.example/mcp"}
```

Actions:

- `list`: show registered servers and active tool counts.
- `add`: negotiate the protocol, discover tools, then persist the validated registration. An HTTP 401 saves the server as requiring OAuth, ready for `authenticate`.
- `remove`: remove a registration and its active tools, erase its local credentials/environment, and stop its stdio process.
- `authenticate`: open browser sign-in for a registered HTTP server, then refresh and activate its tools.
- `logout`: erase locally stored OAuth credentials and close the cached connection. This does not revoke consent at the authorization server.
- `refresh`: explicitly refresh one server or all server catalogs.
- `tools`: search cached tool names and descriptions, optionally using `query`.
- `select`: replace the active schema set using a `names` array of the exact MCP tool names. Include previously selected tools you want to keep. An empty array disables all remote schemas.

Registration, authentication, logout, removal and refresh use the normal approval flow. Discovery/search and selection do not execute remote tools. Each remote tool call still requires approval unless YOLO mode is enabled. Server-provided read-only annotations are not treated as authority to bypass approval.

Server catalogs and active selections persist locally. Startup loads the cache without network discovery. Registration initially selects up to eight tools if there is room. A maximum of 16 active remote tools and a schema-size budget prevent large MCP catalogs from overwhelming the model context. Tools become available on the next model turn. Each turn uses a fixed catalog for both advertised schemas and dispatch.

This implementation supports MCP 2025-03-26 Streamable HTTP, JSON or SSE POST responses, session negotiation and paginated tool discovery. HTTPS is required except for explicit loopback HTTP endpoints. Redirects and automatic tool-call replay are disabled. Legacy SSE endpoints, resources, prompts and server-initiated sampling are not implemented. Remote text and structured results are supported; remote binary content is omitted with a notice.

### OAuth sign-in

After adding a protected HTTP server, use:

```json
{"action":"authenticate","name":"example"}
```

Ophoner opens the system browser and waits up to five minutes for a callback on an ephemeral `127.0.0.1` listener. State and PKCE S256 bind the callback to the pending request. Cancellation closes the listener; a killed app requires a fresh sign-in. Authorization codes and tokens never become tool results or model context.

Discovery follows protected-resource metadata, including `resource_metadata` from the HTTP 401 challenge, then authorization-server metadata with an exact issuer check. OAuth endpoints require HTTPS. Requests include the MCP resource identifier in authorization, code exchange and refresh. Redirects and automatic POST retries remain disabled. A rejected tool call is not replayed after sign-in.

Public clients use dynamic client registration when available. A server without it needs a pre-registered public client ID:

```json
{"action":"add","name":"example","url":"https://your-server.example/mcp","client_id":"your-public-client-id","scope":"tools:read"}
```

The authorization server must accept native-app loopback redirect URIs with an ephemeral port. Confidential client secrets, client-credentials grants and client-ID metadata documents are not supported. Ophoner stores tokens in Android encrypted preferences, excluded from backup, and commits rotated refresh tokens before using them. Tokens are bound to the endpoint and registration identity; reusing a removed server name does not reuse its credentials.

### Local stdio servers

```json
{"action":"add","name":"local","transport":"stdio","command":["/absolute/path/to/runtime","/absolute/path/to/server"],"cwd":"/absolute/working/directory","env":{"LOG_LEVEL":"warn"}}
```

`command` is an argument array, not a shell command. An absolute executable path is required. `cwd` and `env` are optional. Environment overrides are stored separately from the catalog in encrypted storage. Tool arguments are still part of the conversation, so do not supply secrets through chat/tool arguments.

Processes run under Ophoner's Android app identity, with a small inherited environment and explicit overrides. This transport does not install runtimes, start Shizuku/root processes, or gain access to Termux's private directories. The executable must already be compatible with Android and accessible to the app. Prefer launching the server directly so its lifetime matches the managed process.

Each process uses newline-delimited JSON-RPC on stdin/stdout. Stderr is drained independently and discarded. Sessions are reused, serialized per server, and retired after 60 seconds idle. Requests have a 30-second deadline and bounded message sizes. Cancellation, a protocol error, removal, or replacement terminates the managed process. A later explicit operation starts a new session; an interrupted tool call is never replayed automatically.

Implementation references: [MCP authorization](https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization), [MCP transports](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports).

## Lessons adapted from Pi

[Pi](https://pi.dev/) keeps its core small and makes context preparation and tool execution explicit. Ophoner adopts that separation through:

- A model-context projection separate from durable chat history.
- On-demand skills and bounded, explicitly selected MCP schemas.
- Stable instructions and tool ordering for prompt-cache reuse.
- Explicit tool lifecycle events and persistence barriers.
- Deterministic fake-provider tests for protocol, scheduling, cancellation and recovery.

Pi's steering/follow-up queues and session branching are useful future extensions. They are not part of this change. Ophoner keeps Android-specific permission checks, foreground-service behavior and screen ownership in its own runtime.

## Validation

The unit suite uses scripted providers, fake tools and local HTTP fixtures. It covers truncated streams, schema errors, slow consumers, cancellation, checkpoint failures, bounded concurrency, shared-screen ownership, context compaction, caching hints, skill scope, MCP discovery and registry snapshots, OAuth PKCE/state/resource binding, refresh-token rotation, loopback callback cleanup, and stdio lifecycle/cancellation. No live provider credentials or external MCP servers are required.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```
