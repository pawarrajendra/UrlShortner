# Phase 6 — Measured Load Results

Generated from saved k6 summaries, private application snapshots, SQL counts and container samples.

Run: `urlshortener-p6-14a4d1ace8`; 100 GET requests/s for 60 seconds per case; 10,000 seeded links.

| Case | Requests / 302 | Client p95 ms | Server p95 max ms | Error % | Skipped arrivals | Mapping SELECTs | Cache hit %¹ | Persisted events | Loss/failure² | Healthy target |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| one-no-cache | 6000 / 5983 | 3.93 | 1.76 | 0.283 | 0 | 5983 | 0.00 | 5983 | 0 | pass |
| two-no-cache | 6000 / 6000 | 3.45 | 1.69 | 0.000 | 0 | 6000 | 0.00 | 6000 | 0 | pass |
| two-warm-cache | 6001 / 6001 | 4.72 | 2.61 | 0.000 | 0 | 86 | 98.57 | 6001 | 0 | pass |
| hot-key | 5990 / 5990 | 4.16 | 2.34 | 0.000 | 11 | 1 | 99.97 | 5990 | 0 | failed |
| cold-cache | 5994 / 5946 | 6.41 | 4.03 | 0.801 | 7 | 5946 | 0.00 | 5946 | 0 | failed |
| redis-outage | 5994 / 5966 | 5.77 | 3.00 | 0.467 | 7 | 5966 | 0.00 | 5966 | 0 | failed (fault variant) |
| slow-analytics | 6001 / 6001 | 4.36 | 2.47 | 0.000 | 0 | 121 | 97.98 | 70 | 5931 | failed (fault variant) |

| Case | Sampled queue peak³ | Oldest age peak s | Delivery lag p95 max ms | DB connection peak | App heap peak MiB⁴ | Samples / errors |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| one-no-cache | 0 | 0.001 | 2.47 | 7 | 55.4 | 14 / 0 |
| two-no-cache | 0 | 0.000 | 2.08 | 7 | 103.6 | 14 / 0 |
| two-warm-cache | 0 | 0.000 | 2.72 | 6 | 115.2 | 14 / 0 |
| hot-key | 0 | 0.000 | 2.59 | 7 | 110.0 | 14 / 0 |
| cold-cache | 0 | 0.000 | 2.61 | 9 | 115.6 | 14 / 0 |
| redis-outage | 0 | 0.000 | 2.74 | 12 | 119.7 | 14 / 0 |
| slow-analytics | 528 | 5.428 | 5100.27 | 8 | 119.1 | 14 / 0 |

¹ Reported cache hits divided by client GET attempts; raw Redis lookup hit/miss ratio is also retained in report.json.
² Sum of metered queue/admission/age/shutdown losses and write/unknown-commit/recorder failures. Unknown commits are separately retained; no exact persistence inference for those outcomes.
³ Sum across active replicas, sampled about every four seconds (Docker stats collection plus a two-second pause); short spikes can be missed.
⁴ Sum of used heap across active replicas; raw samples include per-container CPU, total memory, network and block I/O, plus database and JVM/pool gauges.

Healthy target: scheduled rate without dropped iterations, client and approximate server p95 below 150 ms, unexpected HTTP errors below 1%, zero measured analytics loss/failure, and every successful GET reconciled with committed/persisted events after bounded drain.

Degraded variants intentionally remove Redis or block event INSERTs; failing healthy analytics targets is reported as failed, rather than waived. HTTP continuity alone does not certify healthy throughput.

Server p95 is the maximum approximate two-minute-window per-replica all-status redirect GET timer percentile. It is a conservative per-replica comparison, not a mathematically merged cluster percentile. Client p95 includes proxy/network time.

## Environment and resource bounds

```json
{
  "host": {
    "platform": "macOS-15.6.1-arm64-arm-64bit-Mach-O",
    "architecture": "arm64"
  },
  "docker": {
    "NCPU": 8,
    "MemTotal": 4106604544,
    "OperatingSystem": "Docker Desktop",
    "Architecture": "aarch64",
    "ServerVersion": "29.8.2"
  },
  "settings": {
    "durationSeconds": 60,
    "rate": 100,
    "datasetLinks": 10000,
    "keepAwakeRequested": true,
    "k6Image": "grafana/k6:1.3.0",
    "appImage": "urlshortener-p6-14a4d1ace8:test",
    "appCpuEach": 1,
    "appMemoryEach": "512m",
    "heap": "128m..256m",
    "pgCpu": 1,
    "pgMemory": "512m",
    "analyticsWorkersEach": 2,
    "analyticsQueueEach": 1000,
    "analyticsMaxWait": "5s",
    "poolEach": 8,
    "fallbackEach": 4,
    "maintenanceDuringLoad": false,
    "workload": "GET, deterministic sequential cycling over 10000 codes; hot-key uses code zero",
    "warmup": "100 HEAD per replica; warm variants seed Redis from one primary SQL snapshot with original PXAT deadlines"
  },
  "appImageId": "sha256:754a9d452f4abb00660fa6f87d3e84e63ef7e013c23eec76f9afea378f3b0f10",
  "datasetChecksum": "9fa3b4bc74f4631e3410854dcf2448d1"
}
```

## Limits

- One shared host; does not establish linear scaling or HA.
- Client p95 includes NGINX/network; server p95 is the maximum approximate per-replica all-status redirect GET timer percentile.
- Two-minute percentile window; no pooling of percentiles across replicas.
- Warm Redis entries retain original 60-second deadlines and may refresh near the end of a run.
- Sampled gauges/resources can miss short spikes; container CPU/memory samples include the generator.
- Elapsed duration is checked against the requested window plus 12 seconds, with 0.1-second lower-bound rounding tolerance; long host/VM pauses invalidate that comparison and are not attributed to application capacity.
- All scenarios share one laptop/Docker VM; timings are local observations, not production sizing or HA evidence.
- Warm fixtures are bulk-populated from one primary SQL snapshot, preserving original absolute deadlines. They are not warmed by 10,000 timed public requests.
- Maintenance is disabled for comparable load runs; separate real-database tests cover cleanup-owner recovery.
- Healthy reconciliation includes a bounded post-load drain; persisted-per-second is normalized by the request window, not a separately sampled instantaneous commit rate.

Fixture cleanup succeeded: **True**. Report generated at 2026-10-12T00:33:34.634098+00:00.
