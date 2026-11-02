# Performance work

## UI pass, September 28, 2026

Changes:

- Chat observes streaming text and tool arguments inside the timeline. The composer and floating controls observe a structurally compared state that excludes those fields.
- Argument streaming uses builders and the existing 50 ms publication window. Execution and persistence flush pending arguments. Accumulators and their timers share the main dispatcher; provider processing runs upstream on Default.
- History decoding and message serialization run on Default. Each history observer reuses unchanged decoded rows and replaces edited rows. Deleted rows leave the cache, and cancelling the observer releases it. The cache retains one entity/domain snapshot of that conversation.
- Markdown patterns compile once. Inline spans and message footer strings are remembered by their content and style.
- Tool-card summaries parse JSON on Default. Expanded cards bound formatting to 4,000 characters, consistent with their existing eight-line preview.

### Measurements

Desktop JVM microbenchmarks on JDK 21. These measure CPU work, not Android frame times, network latency, or database I/O. Results vary with JIT warmup and machine load.

| Workload | Before, median | After, median |
| --- | ---: | ---: |
| Inline markdown, 3,840 characters with mixed formatting | 5.88 ms | 2.62 ms |
| Decode 1,000 history rows, then 20 single-row appends | 93.50 ms | 5.57 ms |

The history fixture uses 6,500 characters per message. The old path decodes 21,210 rows across those snapshots; the new path decodes 1,020. Those first-pass measurements predate the paginated UI described below.

The streaming regression feeds 10,000 argument deltas, checks fewer than 20 published run states, and verifies exact arguments at execution and persistence. Other tests cover history edits, deletion/reinsertion, observer isolation, and every prefix of a mixed-markdown response.

Run correctness checks with `./gradlew :app:testDebugUnitTest`. Opt into timing output with:

```sh
OPHONER_PERF=1 ./gradlew :app:testDebugUnitTest --rerun-tasks \
  --tests 'dev.ophoner.ui.chat.MarkdownPerformanceTest' \
  --tests 'dev.ophoner.data.repository.ConversationRepositoryTest'
```

Timing output appears in `app/build/test-results/testDebugUnitTest/`. Timings are reports, not pass/fail thresholds.

The timeline isolation follows Compose's [derived-state guidance](https://developer.android.com/develop/ui/compose/side-effects#derivedstateof), with an explicit structural equality policy.

## Android history and rendering pass

- Opening a thread reads its latest 50 messages. Scrolling loads another 50 at a time. A restored older anchor opens a neighborhood of about 101 messages and pages in either direction, rather than decoding the entire tail. The model still receives full conversation context when sending.
- Schema 4 adds a `(conversationId, orderIndex)` index through a non-destructive migration. Message decoding remains off the main thread and unchanged decoded rows are reused within each active window.
- Thread reading positions are retained for the 32 most recently visited threads in memory, including pixel offset and whether the reader was following the latest reply. They do not survive process death. Streaming does not pull a reader away from older messages; a Latest button returns to the live tail.
- Project headers and expanded conversation rows are individually lazy. Expanding a large project no longer composes every child at once.
- Token updates reuse the history list unless messages or the checkpoint ID change. Model-history conversion and checkpoint JSON construction run on Default.
- Markdown commits complete blocks and reparses the unfinished tail. Prefix regression tests cover paragraphs, tables, code fences and display math. A single very long unfinished block can still require substantial parsing.
- History loading starts independently of provider refresh. Navigation clears previous chat destinations, and provider refresh avoids overlapping jobs.

### Device verification

Local validation passed 158 JVM tests and built both benchmark APKs. A desktop SQLite check compared schema 3 plus the migration with schema 4, verified latest/anchor page boundaries, and confirmed that the boundary query uses the composite index. This does not replace Android Room migration instrumentation.

The `benchmark` module exercises thread switching, old-history scrolling, a 300-thread project, and streaming with frosted versus solid controls. Its separate `dev.ophoner.benchmark` app seeds two 1,000-message threads and does not use the normal app's database. The benchmark build is non-debuggable but unminified; its timings are not equivalent to the optimized production release.

Build locally:

```sh
./gradlew :app:testDebugUnitTest :app:assembleBenchmark :benchmark:assembleBenchmark
```

On a dedicated, idle Android 10+ device, install and run:

```sh
adb install -r app/build/outputs/apk/benchmark/app-benchmark.apk
adb install -r benchmark/build/outputs/apk/benchmark/benchmark-benchmark.apk
adb shell am instrument -w -e class dev.ophoner.benchmark.NavigationChecks \
  dev.ophoner.benchmark.tests/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class dev.ophoner.benchmark.UiBenchmarks \
  dev.ophoner.benchmark.tests/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/media/dev.ophoner.benchmark.tests/ benchmark-results
```

An initial device run was interrupted by another session switching to the normal app. Its timings are not accepted as performance evidence. Further phone use was stopped at the user's request. Reliable frame-time comparison and the latest navigation/anchor checks remain pending; no Android speedup percentage is claimed. Keep frosted and solid appearance choices until a controlled comparison justifies changing the default.

### Baseline profiles

A collection journey covers startup, thread opening, scrolling, switching and project expansion:

```sh
adb shell am instrument -w -e class dev.ophoner.benchmark.BaselineProfiles \
  dev.ophoner.benchmark.tests/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/media/dev.ophoner.benchmark.tests/ benchmark-results
```

The existing `app/src/main/baseline-prof.txt` uses broad handwritten app rules. A device-generated replacement is **not yet collected or validated**. After collecting on a supported device, exclude benchmark-only `PerformanceActivity` rules, review the generated profile, replace the broad rules, and verify release packaging and startup performance. The benchmark uses an unminified target to retain production method names for this step.

## Tool paths

These replaced per-file document lookups, sequential pipe reads, full-page HTML scans, and sort-then-filter app listing.

1. **Files.** `DocumentTree` reads each directory with one child-cursor projection (document id, name, MIME type, size, flags). Path walks cost one query per segment. Pages are indexed in a map for that call and then dropped, so an external edit is visible on the next call. A provider error is not treated as an empty directory. A partial page (`EXTRA_LOADING`, after one bounded refresh) does not create or rename over a name that was not returned. Listings report that partial page as truncated.
2. **Folder skills.** `SkillLoader` uses the same child queries: one for the tree root, then one for `.ophoner` and one for `skills` when those directories are present.
3. **Shell.** `CommandRunner` drains stdout and stderr together, closes stdin, and uses `Process.waitFor` for the deadline. Output is capped while the rest of the pipe is still consumed, which is what keeps a 64KiB pipe from stalling the child. `PrivilegedService` delegates to the same runner, so the Shizuku path has the same deadline. Cancellation destroys the process only when the calling coroutine is cancelled.
4. **Moves.** A same-directory rename uses `DocumentsContract.renameDocument` when the source supports rename and the destination name is free. An existing file is overwritten in place, then the source is deleted. Anything else copies and deletes.
5. **Web search and fetch.** HTML becomes text in one pass, including script and style bodies, without a backtracking expression. Search results are scanned incrementally and the response body is closed once the requested count is complete. The byte caps remain.
6. **App listing.** Launcher rows are cached until a package broadcast or a locale change. A query filters that snapshot, then sorts only the matches.
7. **Tool scheduling.** Reads still run at most four at a time. That width is covered by an overlap test; it was not raised. Mutations stay in their own batches so they cannot pass each other. Cancelling a read batch does not start the following mutation. Recent per-tool latencies are kept in a fixed 64-entry trace.

Follow-up production measurements should use an optimized release build on a phone: long-history opening, scrolling during generation, a large streamed file-write preview, and rapid navigation. Record frame-time percentiles and allocations with the same fixtures before and after.
