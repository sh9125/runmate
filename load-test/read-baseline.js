import execution from 'k6/execution';
import http from 'k6/http';
import { Counter, Rate, Trend } from 'k6/metrics';
import { api, loginRunner } from './runner-auth.js';

const paths = {
  list: () => '/running-events?size=9&page=0',
  detail: () => '/running-events/1',
  'detail-spread': () => {
    const eventId = 3 + (execution.scenario.iterationInTest % 100);
    return `/running-events/${eventId}`;
  },
};
const endpoint = __ENV.READ || 'list';
const requestsPerSecond = Number(__ENV.RPS || 10);
const durationSeconds = Number(__ENV.SECONDS || 20);
const completed = new Counter('read_requests');
const failed = new Rate('read_failed');
const latency = new Trend('read_duration', true);

if (!Object.hasOwn(paths, endpoint)
  || !Number.isInteger(requestsPerSecond) || requestsPerSecond < 1
  || !Number.isInteger(durationSeconds) || durationSeconds < 1) {
  throw new Error('Use READ=list, detail, or detail-spread and positive integers for RPS and SECONDS');
}

export const options = {
  scenarios: {
    reads: {
      executor: 'constant-arrival-rate',
      rate: requestsPerSecond,
      timeUnit: '1s',
      duration: `${durationSeconds}s`,
      preAllocatedVUs: 40,
      maxVUs: 80,
    },
  },
  summaryTrendStats: ['avg', 'p(95)', 'p(99)'],
};

export function setup() {
  return loginRunner(2);
}

export default function (token) {
  const response = http.get(`${api}${paths[endpoint]()}`, {
    headers: { Authorization: `Bearer ${token}` },
    timeout: '5s',
  });
  completed.add(1);
  failed.add(response.status !== 200);
  if (response.status === 200) {
    latency.add(response.timings.duration);
  }
}

export function handleSummary(data) {
  const count = data.metrics.read_requests?.values.count ?? 0;
  const failedCount = data.metrics.read_failed?.values.passes ?? 0;
  const dropped = data.metrics.dropped_iterations?.values.count ?? 0;
  const round = (value) => Number(value.toFixed(2));
  const times = data.metrics.read_duration?.values;
  const summary = {
    endpoint,
    targetRps: requestsPerSecond,
    durationSeconds,
    completed: count,
    achievedRps: round(count / durationSeconds),
    failed: failedCount,
    failedPercent: count ? round(100 * failedCount / count) : null,
    dropped,
    avgMs: times ? round(times.avg) : null,
    p95Ms: times ? round(times['p(95)']) : null,
    p99Ms: times ? round(times['p(99)']) : null,
  };
  return { stdout: `${JSON.stringify(summary)}\n` };
}
