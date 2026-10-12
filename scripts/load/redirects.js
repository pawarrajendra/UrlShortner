import http from 'k6/http';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Trend } from 'k6/metrics';

const codes = new SharedArray('seeded codes', () => JSON.parse(open(__ENV.DATASET || '/data/codes.json')));
const successful = new Counter('redirect_successes');
const unexpected = new Counter('unexpected_responses');
const clientLatency = new Trend('redirect_latency_ms', true);
const servedBy = new Counter('served_by');
const duration = Number(__ENV.DURATION_SECONDS || 60);
const rate = Number(__ENV.RATE || 100);
const degraded = __ENV.DEGRADED === 'true';
export const options = {
  scenarios: { redirects: { executor: 'constant-arrival-rate', rate, timeUnit: '1s',
    duration: `${duration}s`, preAllocatedVUs: 32, maxVUs: 128, gracefulStop: '10s' } },
  maxRedirects: 0,
  discardResponseBodies: true,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  thresholds: {
    'served_by{instance:app-1}': ['count>=0'],
    'served_by{instance:app-2}': ['count>=0'],
    'served_by{instance:unknown}': ['count==0'],
    http_req_failed: degraded ? ['rate<=1'] : ['rate<0.01'],
    dropped_iterations: ['count==0'],
    redirect_latency_ms: degraded ? ['p(95)>=0'] : ['p(95)<150'],
  },
};
http.setResponseCallback(http.expectedStatuses(302));
export default function () {
  const index = __ENV.HOT_KEY === 'true' ? 0 : exec.scenario.iterationInTest % codes.length;
  const response = http.get(`${__ENV.BASE_URL || 'http://nginx:8080'}/r/${codes[index]}`,
    { redirects: 0, timeout: '8s', tags: { name: 'GET /r/{code}' } });
  clientLatency.add(response.timings.duration);
  const instance = response.headers['X-App-Instance'];
  servedBy.add(1, { instance: ['app-1','app-2'].includes(instance) ? instance : 'unknown' });
  if (response.status === 302) successful.add(1);
  else unexpected.add(1, { status: String(response.status) });
}
export function handleSummary(data) {
  return { '/out/k6-summary.json': JSON.stringify(data, null, 2),
    stdout: JSON.stringify({ requests: data.metrics.http_reqs?.values.count,
      successes: data.metrics.redirect_successes?.values.count || 0,
      p95ClientMs: data.metrics.redirect_latency_ms?.values['p(95)'],
      errors: data.metrics.http_req_failed?.values.rate,
      droppedIterations: data.metrics.dropped_iterations?.values.count || 0 }) + '\n' };
}
