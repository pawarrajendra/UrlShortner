# Submission handoff

Start with the root [README](../../README.md) for prerequisites, local configuration, startup, annotated curl examples, full verification and retained-volume shutdown. Read [the engineering summary](../engineering-summary.md) for outcomes and trade-offs, then [the reviewer record](review.md) for evidence and outstanding acceptance.

## Reproduce the clean-source replay

Requires Java 21, Docker Engine with Compose, Python 3.12+ (safe archive extraction), Git, and first-run network access. Export `JAVA_HOME` for Java 21 as described in README. Run from the repository:

```bash
python3 scripts/phase7-smoke.py --output target/phase7-my-replay
```

Output directories must be new. `--revision` selects a committed source revision; default is HEAD. The runner exports only tracked source into a temporary directory, excludes `.env`/build output, runs Maven `clean verify`, and builds the full Compose image there. Maven may reuse the repository's ignored download cache; no compiled artifacts are copied. Git history is not needed for this current-source test. Saved-baseline upgrade tests in Phases 3/4 additionally require the recorded Git history.

The runner copies `.env.example` and replaces its three password placeholders with independent random secrets in a private temporary file. An isolated project uses a random localhost ingress port and distinct image/volume. It verifies fresh migration/readiness, create/key replay/metadata, GET/HEAD/no-store/Location, one eventual GET observation, disabling, and `down`/`up` persistence of the disabled mapping, original replay and event. It then stops apps and deliberately alters **only its disposable database's** V1 history checksum. Nonzero migration exit and blocked app startup are required. Restoring that fixture's original checksum demonstrates recovery; this test action is not a recovery procedure for a real database.

A finally block removes only its project containers/network/volume; it never reads the developer `.env` or resets development state. Abnormal termination such as SIGKILL can prevent cleanup: inspect the unique `urlshortener-p7-...` project before removing fixture resources. Ordinary developer shutdown remains `docker compose down`, retaining the named volume. Failed real migrations require investigation with storage intact, not edited SQL, automatic repair or volume deletion.

## Evidence and scope

[Recorded replay](phase7-replay/report.json) contains source revision, image identity, checks, pass/fail and cleanup. `verify.log`, `build.log` and `replay.log` preserve the tool results. The [first attempt](phase7-initial/report.json) is retained: the fixture incorrectly expected detailed checksum text instead of the intentionally redacted `Startup failed` message; this assertion was corrected and the full replay repeated. The exported code is identified independently of later evidence-only changes. The archive approach verifies a clean committed tree using this host's installed prerequisites and cached downloads; it does not establish an air-gapped install, a different operating system or hosted CI.

Current OpenAPI remains the P5 contract because P6/P7 add verification/handoff without changing public API/schema. The original staged contracts and baseline commits document feature evolution. [Scenario records](../engineering-summary.md#assignment-evidence), [AI log](../ai-execution-log.md), [validation history](../validation-results.md) and [load failures](../performance/phase6-final/results.md) remain part of the submission.

Final human review is pending. Record reviewer identity/date, inspected revision, dispositions and accepted limitations in [review.md](review.md) only after that review actually occurs.
