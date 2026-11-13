import execution from 'k6/execution';
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import { api, loginRunner } from './runner-auth.js';

const operation = __ENV.WRITE || 'apply';
const rate = Number(__ENV.RPS || 5);
const seconds = Number(__ENV.SECONDS || 8);
const actors = rate * seconds + 1;
const application = operation === 'apply';
const path = application ? '/running-events/1/participants' : '/running-events/2/participants/cancel';
const outcomes = {
  confirmed: new Counter('write_confirmed'),
  waitlisted: new Counter('write_waitlisted'),
  canceled: new Counter('write_canceled'),
  timeout: new Counter('write_timeout'),
  transport_error: new Counter('write_transport_error'),
  server_error: new Counter('write_server_error'),
  rejected: new Counter('write_rejected'),
  unexpected: new Counter('write_unexpected'),
};
const latency = new Trend('write_duration', true);

if (!['apply', 'cancel'].includes(operation)
  || !Number.isInteger(rate) || rate < 1
  || !Number.isInteger(seconds) || seconds < 1 || actors > 90) {
  throw new Error('Use WRITE=apply or cancel and positive RPS/SECONDS with RPS × SECONDS < 90');
}

export const options = {
  scenarios: {
    writes: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration: `${seconds}s`,
      preAllocatedVUs: 30,
      maxVUs: 60,
    },
  },
  setupTimeout: '2m',
  summaryTrendStats: ['avg', 'p(95)', 'p(99)'],
};

export function setup() {
  // 각 요청에 아직 신청하거나 취소하지 않은 회원을 한 명씩 배정한다.
  return Array.from({ length: actors }, (_, index) => loginRunner(index + 2));
}

function classify(response) {
  if (response.status === 0) {
    return response.error_code === 1050 ? 'timeout' : 'transport_error';
  }
  if (response.status >= 500) return 'server_error';
  if (response.status >= 400) return 'rejected';
  if (application && response.status === 201) {
    const status = response.json('status');
    if (status === 'PARTICIPATING') return 'confirmed';
    if (status === 'WAITING') return 'waitlisted';
  }
  if (!application && response.status === 200 && response.json('status') === 'CANCELED') {
    return 'canceled';
  }
  return 'unexpected';
}

export default function (tokens) {
  const token = tokens[execution.scenario.iterationInTest];
  if (!token) execution.test.abort('No unused test account is available');
  const response = http.post(`${api}${path}`, null, {
    headers: { Authorization: `Bearer ${token}` },
    timeout: '5s',
  });
  const outcome = classify(response);
  outcomes[outcome].add(1);
  if (['confirmed', 'waitlisted', 'canceled'].includes(outcome)) {
    latency.add(response.timings.duration);
  }
}

export function handleSummary(data) {
  const counts = Object.fromEntries(Object.keys(outcomes).map((name) => [
    name, data.metrics[`write_${name}`]?.values.count ?? 0,
  ]));
  const completed = Object.values(counts).reduce((sum, count) => sum + count, 0);
  const failed = completed - counts.confirmed - counts.waitlisted - counts.canceled;
  const times = data.metrics.write_duration?.values;
  const round = (value) => Number(value.toFixed(2));
  const summary = {
    operation, rate, seconds, completed,
    completedRps: round(completed / seconds),
    failed, failedPercent: completed ? round(100 * failed / completed) : null,
    dropped: data.metrics.dropped_iterations?.values.count ?? 0,
    ...counts,
    avgMs: times ? round(times.avg) : null,
    p95Ms: times ? round(times['p(95)']) : null,
    p99Ms: times ? round(times['p(99)']) : null,
  };
  return { stdout: `${JSON.stringify(summary)}\n` };
}
