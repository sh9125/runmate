import execution from 'k6/execution';
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import { api, loginRunner } from './runner-auth.js';

const readTarget = __ENV.READ_TARGET || 'same';
const seconds = 8;
const writeRate = 5;
const readRate = 20;
const actorsPerWrite = writeRate * seconds + 1;
const eventUrl = `${api}/running-events/2`;
const operations = ['apply', 'cancel', 'detail'];
const outcomes = ['ok', 'timeout', 'http_4xx', 'http_5xx', 'unexpected'];
const counters = Object.fromEntries(operations.map((operation) => [
  operation,
  Object.fromEntries(outcomes.map((outcome) => [
    outcome, new Counter(`${operation}_${outcome}`),
  ])),
]));
const durations = Object.fromEntries(operations.map((operation) => [
  operation, new Trend(`${operation}_duration`, true),
]));

if (!['none', 'same', 'separate'].includes(readTarget)) {
  throw new Error('Use READ_TARGET=none, same, or separate');
}

function scenario(exec, rate, vus) {
  return {
    exec,
    executor: 'constant-arrival-rate',
    rate,
    timeUnit: '1s',
    duration: `${seconds}s`,
    preAllocatedVUs: vus,
    maxVUs: vus,
  };
}

export const options = {
  scenarios: {
    apply: scenario('apply', writeRate, 30),
    cancel: scenario('cancel', writeRate, 30),
    ...(readTarget === 'none' ? {} : { detail: scenario('detail', readRate, 100) }),
  },
  setupTimeout: '2m',
  summaryTrendStats: ['avg', 'p(95)', 'p(99)'],
};

export function setup() {
  const tokens = {
    applicants: Array.from({ length: actorsPerWrite }, (_, index) => loginRunner(182 + index)),
    participants: Array.from({ length: actorsPerWrite }, (_, index) => loginRunner(2 + index)),
    reader: readTarget === 'none' ? null : loginRunner(1),
  };
  console.log(`Measurement begins after login setup at ${new Date().toISOString()}`);
  return tokens;
}

function authorized(token) {
  if (!token) execution.test.abort('No unused test account is available');
  return { headers: { Authorization: `Bearer ${token}` }, timeout: '5s' };
}

function record(operation, response, expectedCode, expectedStatus) {
  let outcome = 'unexpected';
  if (response.status === expectedCode
    && (!expectedStatus || response.json('status') === expectedStatus)) {
    outcome = 'ok';
    durations[operation].add(response.timings.duration);
  } else if (response.error_code === 1050) {
    outcome = 'timeout';
  } else if (response.status >= 500) {
    outcome = 'http_5xx';
  } else if (response.status >= 400) {
    outcome = 'http_4xx';
  }
  counters[operation][outcome].add(1);
}

export function apply({ applicants }) {
  const token = applicants[execution.scenario.iterationInTest];
  const response = http.post(`${eventUrl}/participants`, null, authorized(token));
  record('apply', response, 201, 'WAITING');
}

export function cancel({ participants }) {
  const token = participants[execution.scenario.iterationInTest];
  const response = http.post(`${eventUrl}/participants/cancel`, null, authorized(token));
  record('cancel', response, 200, 'CANCELED');
}

export function detail({ reader }) {
  const eventId = readTarget === 'same' ? 2 : 3;
  const response = http.get(`${api}/running-events/${eventId}`, authorized(reader));
  record('detail', response, 200);
}

export function handleSummary(data) {
  const round = (value) => Number(value.toFixed(2));
  const measured = Object.fromEntries(operations.map((operation) => {
    const counts = Object.fromEntries(outcomes.map((outcome) => [
      outcome, data.metrics[`${operation}_${outcome}`]?.values.count ?? 0,
    ]));
    const times = data.metrics[`${operation}_duration`]?.values;
    const completed = Object.values(counts).reduce((sum, count) => sum + count, 0);
    return [operation, {
      completed,
      completedRps: round(completed / seconds),
      failedPercent: completed ? round(100 * (completed - counts.ok) / completed) : null,
      ...counts,
      avgMs: times ? round(times.avg) : null,
      p95Ms: times ? round(times['p(95)']) : null,
      p99Ms: times ? round(times['p(99)']) : null,
    }];
  }));
  const dropped = data.metrics.dropped_iterations?.values.count ?? 0;
  return { stdout: `${JSON.stringify({ readTarget, seconds, dropped, ...measured })}\n` };
}
