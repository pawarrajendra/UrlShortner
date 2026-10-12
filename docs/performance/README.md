# Phase 6: repeatable reliability and load checks

The application APIs are public. The measurement fixture uses fresh credentials, its own Compose project/network/volume, 10,000 deterministic links and an unpublished NGINX port. It never reads the development `.env`, follows redirect destinations, stops development services or removes development volumes.

## Run the blocking correctness and dependency gates

Select Java 21 and keep Docker running:

```bash
./mvnw -B -ntp -Dmaven.repo.local=.m2-cache clean verify
./mvnw -B -ntp -Dmaven.repo.local=.m2-cache org.apache.maven.plugins:maven-dependency-plugin:3.10.0:tree \
  -DoutputType=json -DoutputFile=target/dependency-tree.json
python3 scripts/dependency-audit.py
ruby scripts/validate-contract.rb
```

The advisory gate checks the resolved Maven dependency graph, including test libraries, against the [OSV batch API](https://google.github.io/osv.dev/post-v1-querybatch/). Any indexed finding fails the gate; unavailable/partial responses fail distinctly. Curl uses verified system certificate trust. It does not scan native container packages or build-plugin dependencies, prove exploitability, or replace source review. The before/after audit evidence records the patched managed versions and actual findings.

CI checks Docker first, runs mandatory PostgreSQL/Redis tests, exports the dependency tree, runs the fail-closed advisory gate and checks all public API snapshots. Reports are retained even on failure. The workflow is authored; a hosted CI run is separate evidence.

## Run the complete measurement matrix

Requires Python 3, curl, Docker Compose and image-download access. The pinned k6 image is `grafana/k6:1.3.0`; no host k6 installation is required.

```bash
python3 scripts/phase6-load.py --keep-awake --output target/phase6-my-run
python3 scripts/phase6-report.py target/phase6-my-run
```

`--keep-awake` starts a macOS idle-sleep inhibitor tied to the runner process; on other platforms it has no effect. The runner records k6 elapsed time and rejects a comparison window longer than the configured duration plus 12 seconds. Keep the machine available throughout the measurement.

Choose a fresh output directory for each run; existing report files are not overwritten. Without `--image`, the harness builds the current source once and shares that artifact across cases. `--image` may reuse an already-built current artifact; its ID is recorded. A short setup check is useful before a full comparison, but does not satisfy the 60-second target:

```bash
python3 scripts/phase6-load.py --cases two-warm-cache --duration 5 \
  --output target/phase6-short-check
```

A completed harness process means evidence was saved; consult `validation.json` and `results.md` for actual target pass/fail. k6 exit codes and failed thresholds remain in every case.

The default rate/duration is 100 arrivals/second for 60 seconds. k6 uses a [constant-arrival-rate executor](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/) with 32 preallocated and at most 128 virtual users. It explicitly disables redirect following and treats only HTTP 302 as expected. Each iteration issues one GET. Uniform runs cycle deterministically through the same 10,000 codes; hot-key uses one code.

| Case | Replica/cache policy | Purpose |
| --- | --- | --- |
| one-no-cache | One, primary reads | Single-app baseline through the same NGINX proxy |
| two-no-cache | Two, primary reads | Routing and extra app capacity without cache |
| two-warm-cache | Two, shared warm Redis | Mapping SQL reduction and event-write throughput |
| hot-key | Two, shared warm Redis | Repeated resolution of one mapping |
| cold-cache | Two, Redis keys cleared after JVM warmup | Actual cache misses/fills against primary |
| redis-outage | Two, Redis stopped for the request window | Breaker/bounded primary fallback and event persistence |
| slow-analytics | Two, warm Redis; event table locked during the window | Fail-open redirects, writer timeouts, backlog/age/drop behavior |

Each app has one CPU, 512 MiB container memory, a 128–256 MiB heap, eight database connections, two analytics workers/1,000 queue slots/five-second age bound and four mapping fallback permits. PostgreSQL has one CPU/512 MiB; NGINX and Redis each have 0.5 CPU/128 MiB. The generator has one CPU/512 MiB. All share the recorded Docker VM/host. Retention maintenance is disabled during comparisons; deterministic tests exercise owner recovery separately.

Before each case, apps are restarted to reset counters and percentile windows, events are cleared only in the fixture, and each active app receives 100 HEAD warmup requests. Warm scenarios then seed Redis using one actual primary SQL snapshot and absolute `PXAT` deadlines: no sliding TTL or renewed old deadline. Keys can expire near the end of a 60-second window; actual misses/SQL are recorded. This is controlled warm-state preparation, not a timed 10,000-request public warmup benchmark.

## Private measurement profile

The harness activates `measure`, uses management port 9090, and exposes `/actuator/measurement` only on that unpublished port. Normal deployment retains health-only Actuator exposure and does not enable this endpoint. The endpoint exports a bounded set of analytics/cache/pool/JVM meters and two-minute-window approximate timer p95 values, including one all-status redirect GET timer per replica; it exports no datasource URL, password or configuration dump. Tests verify separate-port behavior and the default absence of the endpoint.

k6 summaries contain request/302 counts, client latency, error rate, skipped arrivals and replica distribution. Private snapshots provide mapping queries, cache hits/misses, accepted/committed events, loss reasons, known/unknown write outcomes, queue depth/age/wait, delivery lag, pool connections, heap and process CPU. Docker samples provide per-container CPU/memory/network/block I/O; SQL samples provide database connection/activity and cumulative database counters. Samples can miss short spikes.

After the request window, the slow-writer lock is released and queues/workers are polled to empty before actual event-row counts are read. Healthy reconciliation requires successful GETs = acknowledged commits = persisted rows, with zero metered loss/failure. A possible unknown commit is reported separately; it is never guessed to be lost or retried.

The client p95 includes NGINX and network time. The server figure is the maximum approximate per-replica all-status GET timer p95, a conservative comparison rather than a pooled cluster percentile. Low latency with lost events fails the healthy target. Fault runs intentionally can fail that target; their failures remain visible in the report.

## Reliability and review evidence

`ReliabilityIT` uses real PostgreSQL/Redis to hold all pool connections, hold a row lock, cancel `pg_sleep`, pause database networking, stop/restart cleanup ownership and inspect the private measurement port. Existing `AnalyticsTest`/`AnalyticsIT` cover deterministic queue saturation/age, slow/failed recording, actual HTTP-first graceful drain, hard-killed child loss, UTC/retention and persistence. Existing cache/core/foundation tests retain lifecycle, corruption, fallback, least-privilege, safe-error and URL/protocol coverage.

The source review and observed results distinguish assistant checks from pending human final acceptance. Neither this harness nor the passing gates establish production readiness, multi-host availability, container-native vulnerability freedom or backup/restore guarantees.


## Observed runs

The authoritative corrected measurement is [final results](phase6-final/results.md), with [derived gate evaluation](phase6-final/validation.json) and complete [run metadata](phase6-final/report.json). All three healthy topology comparisons passed. Warm Redis used 86 mapping SELECTs for 6,001 requests, versus 6,000 SELECTs in the two-app cache-disabled baseline (~98.6% reduction).

The final hot-key/cold-cache/Redis-outage runs did not meet the harness's strict zero-skipped-arrival gate (11/7/7 skipped arrivals). Their low error/latency and successful-event reconciliation do not justify declaring the scheduling gate passed. Slow analytics returned 6,001 redirects but persisted only 70 events: 5,423 queue-age losses and 508 known write failures; its healthy target remains failed. No unknown commits were observed.

[Initial run](phase6-run/results.md) and [intermediate rerun](phase6-rerun/results.md) remain available. Two initial windows lasted 459/618 seconds for a configured 60-second scenario and are invalid comparisons; a host/VM interruption is a possible explanation, not independently established. Early server figures used worst status-group percentiles and were superseded by the explicit all-status timer. The final report recomputes derived validity using a 0.1-second lower-bound rounding tolerance, retaining original runner flags and all raw measurements; sub-millisecond scenario-end rounding is not treated as a long interruption. No raw counts/latencies or failed thresholds are changed.

Raw sampled snapshots are retained losslessly as `samples.json.gz`; the report generator reads either plain or compressed samples. k6 summaries, final counters, metadata and gate evaluation remain directly readable JSON.

Raw CLI transcript paths have a scoped Git whitespace attribute so tool banners and HTTP status lines remain verbatim. Source code and documentation retain normal whitespace checks.
