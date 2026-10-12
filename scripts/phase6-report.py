"""Summarize a recorded P6 load matrix without rerunning or changing measurements."""
import argparse
from datetime import datetime, timezone
import json
import gzip
from pathlib import Path


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory',type=Path)
    args=parser.parse_args(); report=json.loads((args.directory/'report.json').read_text())
    aggregate_timer=any(r['name']=='redirect.server.duration' for folder in args.directory.iterdir() if folder.is_dir() and (folder/'metrics-after.json').exists() for rows in json.loads((folder/'metrics-after.json').read_text()).values() for r in rows)
    lines=['# Phase 6 — Measured Load Results','',
        'Generated from saved k6 summaries, private application snapshots, SQL counts and container samples.','',
        f"Run: `{report['project']}`; {report['settings']['rate']} GET requests/s for {report['settings']['durationSeconds']} seconds per case; {report['settings']['datasetLinks']:,} seeded links.",
        '', '| Case | Requests / 302 | Client p95 ms | Server p95 max ms | Error % | Skipped arrivals | Mapping SELECTs | Cache hit %¹ | Persisted events | Loss/failure² | Healthy target |',
        '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |']
    validations=[]
    resource_lines=['','| Case | Sampled queue peak³ | Oldest age peak s | Delivery lag p95 max ms | DB connection peak | App heap peak MiB⁴ | Samples / errors |',
        '| --- | ---: | ---: | ---: | ---: | ---: | --- |']
    for case in report['cases']:
        folder=args.directory/case['name']
        sample_file=folder/'samples.json'
        samples=json.loads(sample_file.read_text()) if sample_file.exists() else json.loads(gzip.open(str(sample_file)+'.gz','rt').read())
        after=json.loads((folder/'metrics-after.json').read_text())
        def sample_gauge(sample,name,tags=None):
            return sum((r.get('value') or 0) for rows in sample['metrics'].values() for r in rows if r['name']==name and (not tags or all(r['tags'].get(k)==v for k,v in tags.items())))
        queue=max((sample_gauge(s,'analytics.queue.depth') for s in samples),default=0)
        age=max(((r.get('value') or 0) for s in samples for rows in s['metrics'].values() for r in rows if r['name']=='analytics.queue.oldest.age.seconds'),default=0)
        lag=max(((r.get('p95Seconds') or 0)*1000 for rows in after.values() for r in rows if r['name']=='analytics.delivery.lag'),default=0)
        connections=max((s['database']['connections'] for s in samples),default=0)
        heap=max((sample_gauge(s,'jvm.memory.used',{'area':'heap'})/(1024**2) for s in samples),default=0)
        loss=sum(case['losses'].values())+sum(case['failureOutcomes'].values())
        hit=100*case['cacheHits']/case['requests'] if case['requests'] else 0
        server='unavailable' if case['serverP95MaxMs'] is None else f"{case['serverP95MaxMs']:.2f}"
        actual=json.loads((folder/'k6-summary.json').read_text())['state']['testRunDurationMs']/1000
        valid=case['durationSeconds']-.1<=actual<=case['durationSeconds']+12
        gate=valid and case['k6ExitCode']==0 and case['serverP95MaxMs'] is not None and case['serverP95MaxMs']<150 and case['reconciledWithoutLoss'] and case['requests']>=report['settings']['rate']*case['durationSeconds']*.99
        case.setdefault('rawRunnerGateEvaluation',{'measurementWindowValid':case.get('measurementWindowValid'),'healthyTargetPassed':case['measuredHealthyTargetPassed']})
        case['measurementWindowValid']=valid
        case['measuredHealthyTargetPassed']=gate
        validations.append({'name':case['name'],'elapsedSeconds':actual,'measurementWindowValid':valid,'healthyTargetPassed':gate,'degradedVariant':case['degradedVariant']})
        target=('pass' if gate else 'failed') if valid else 'invalid window; rerun'
        if case['degradedVariant']: target+=' (fault variant)'
        lines.append(f"| {case['name']} | {case['requests']:.0f} / {case['successes']:.0f} | {case['clientP95Ms']:.2f} | {server} | {case['unexpectedErrorRate']*100:.3f} | {case['droppedIterations']:.0f} | {case['mappingQueries']:.0f} | {hit:.2f} | {case['persisted']} | {loss:.0f} | {target} |")
        resource_lines.append(f"| {case['name']} | {queue:.0f} | {age:.3f} | {lag:.2f} | {connections} | {heap:.1f} | {case['samples']} / {len(case['sampleFailures'])} |")
    if not aggregate_timer:
        lines.insert(4,'**Earlier instrumentation:** server values are worst status-group p95 bounds, not the all-status timer used by the final matrix. Consult phase6-final/results.md for the corrected comparison.')
    lines+=resource_lines+['','¹ Reported cache hits divided by client GET attempts; raw Redis lookup hit/miss ratio is also retained in report.json.',
        '² Sum of metered queue/admission/age/shutdown losses and write/unknown-commit/recorder failures. Unknown commits are separately retained; no exact persistence inference for those outcomes.',
        '³ Sum across active replicas, sampled about every four seconds (Docker stats collection plus a two-second pause); short spikes can be missed.',
        '⁴ Sum of used heap across active replicas; raw samples include per-container CPU, total memory, network and block I/O, plus database and JVM/pool gauges.',
        '', 'Healthy target: scheduled rate without dropped iterations, client and approximate server p95 below 150 ms, unexpected HTTP errors below 1%, zero measured analytics loss/failure, and every successful GET reconciled with committed/persisted events after bounded drain.',
        '', 'Degraded variants intentionally remove Redis or block event INSERTs; failing healthy analytics targets is reported as failed, rather than waived. HTTP continuity alone does not certify healthy throughput.',
        '', 'Server p95 is the maximum approximate two-minute-window per-replica all-status redirect GET timer percentile. It is a conservative per-replica comparison, not a mathematically merged cluster percentile. Client p95 includes proxy/network time.',
        '', '## Environment and resource bounds','', '```json',json.dumps({'host':report['host'],'docker':report['docker'],'settings':report['settings'],'appImageId':report['appImageId'],'datasetChecksum':report['datasetChecksum']},indent=2),'```',
        '', '## Limits','', *('- '+s for s in report['limitations']),
        '- Elapsed duration is checked against the requested window plus 12 seconds, with 0.1-second lower-bound rounding tolerance; long host/VM pauses invalidate that comparison and are not attributed to application capacity.',
        '- All scenarios share one laptop/Docker VM; timings are local observations, not production sizing or HA evidence.',
        '- Warm fixtures are bulk-populated from one primary SQL snapshot, preserving original absolute deadlines. They are not warmed by 10,000 timed public requests.',
        '- Maintenance is disabled for comparable load runs; separate real-database tests cover cleanup-owner recovery.',
        '- Healthy reconciliation includes a bounded post-load drain; persisted-per-second is normalized by the request window, not a separately sampled instantaneous commit rate.',
        '', f"Fixture cleanup succeeded: **{report.get('cleanupSucceeded')}**. Report generated at {datetime.now(timezone.utc).isoformat()}."]
    report['gateEvaluationNote']='Derived gates recomputed from saved measurements with 0.1s lower-bound rounding tolerance; original runner flags retained per case. Raw measurements unchanged.'
    (args.directory/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    (args.directory/'validation.json').write_text(json.dumps(validations,indent=2)+'\n')
    (args.directory/'results.md').write_text('\n'.join(lines)+'\n')
    print('Wrote',args.directory/'results.md')


if __name__=='__main__': main()
