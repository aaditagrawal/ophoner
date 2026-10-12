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

## Tool backlog, paused by request

1. **File reads, writes, lists, deletes, and moves:** replace `DocumentFile.findFile` and repeated metadata getters with a child cursor projecting document ID, name, MIME type, and size. A flat listing currently requires about four metadata queries per file in addition to enumeration. Target one query per directory and one per path segment. Test provider errors, truncation, external edits, and scope enforcement without retaining stale URI caches.
2. **Folder skill loading:** reuse the same bulk metadata queries before each request.
3. **Shell execution:** the original implementation read stdout before stderr and wrapped blocking work in a coroutine timeout. Verify concurrent bounded draining, cancellation, and actual deadlines through both sandbox and Shizuku paths. Another session is replacing this code; reassess that result before changing it.
4. **File moves:** use provider-supported rename for eligible same-directory moves, preserving overwrite behavior and falling back to copy/delete when necessary.
5. **Web search/fetch:** reuse compiled HTML patterns and stop parsing search matches after the requested result count. Retain response-size caps and test malformed HTML.
6. **App listing:** filter before sorting. Any label/package cache needs invalidation for package changes and locale changes.
7. **Tool scheduling:** measure bounded concurrent read batches and per-tool latency before changing concurrency. Preserve mutation order and cancellation.

Follow-up production measurements should use an optimized release build on a phone: long-history opening, scrolling during generation, a large streamed file-write preview, and rapid navigation. Record frame-time percentiles and allocations with the same fixtures before and after.
