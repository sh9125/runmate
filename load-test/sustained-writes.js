import execution from 'k6/execution';
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import { api, loginRunners } from './runner-auth.js';

const durationSeconds = 30;
const actorsPerOperation = 601;
const traceWrites = __ENV.TRACE_WRITES === '1';
const gateComparison = __ENV.GATE_COMPARISON === '1';
const promotionComparison = __ENV.PROMOTION_COMPARISON === '1';
const spreadComparison = __ENV.FINAL_CHECK === 'spread';
const midrange = __ENV.FINAL_CHECK === 'midrange';
const sustainedOnly = __ENV.FINAL_CHECK === 'sustained';
const finalCheck = __ENV.FINAL_CHECK === '1' || sustainedOnly;
const rates = promotionComparison ? [20] : spreadComparison ? [] : midrange ? [25, 30, 35] : sustainedOnly ? [10] : finalCheck ? [20, 10] : gateComparison ? [20] : traceWrites ? [15, 20] : [5, 10, 20];
const operations = promotionComparison ? ['cancel'] : ['apply', 'cancel'];
const outcomes = ['ok', 'timeout', 'rejected', 'server_error', 'unexpected', ...(gateComparison || promotionComparison ? ['busy'] : [])];
const stages = rates.flatMap((rate, index) => operations.map((operation) => ({
  name: `${operation}_${rate}`, operation, rate, eventId: index + 2, startsAfter: index * (midrange ? 60 : 45),
  duration: finalCheck && rate === 10 ? 60 : durationSeconds,
})));
if (!sustainedOnly && (gateComparison || finalCheck)) {
  stages.push({ name: 'detail_3', operation: 'detail', rate: 3, eventId: 4, startsAfter: 0, duration: durationSeconds });
}
if (finalCheck) {
  stages.push({ name: 'detail_3_sustained', operation: 'detail', rate: 3, eventId: 4, startsAfter: sustainedOnly ? 0 : 45, duration: 60 });
}
if (midrange) {
  rates.forEach((total, index) => stages.push({
    name: `detail_${total}`, operation: 'detail', rate: 3, eventId: 5, startsAfter: index * 60, duration: durationSeconds,
  }));
}
if (spreadComparison) {
  const writeTargets = [[2], [3, 4], [5, 6], [7]];
  writeTargets.forEach((eventIds, index) => {
    const startsAfter = index * 65;
    const stage = index + 1;
    for (const operation of operations) {
      stages.push({ name: `${operation}_${stage}`, operation, rate: 20, eventId: eventIds[0], eventIds, startsAfter, duration: durationSeconds });
    }
    stages.push({ name: `detail_${stage}`, operation: 'detail', rate: 3, eventId: 8, startsAfter, duration: durationSeconds });
  });
}
const stagesByName = Object.fromEntries(stages.map((stage) => [stage.name, stage]));
const counters = Object.fromEntries(stages.map(({ name }) => [
  name, Object.fromEntries(outcomes.map((outcome) => [outcome, new Counter(`${name}_${outcome}`)])),
]));
const latency = Object.fromEntries(stages.map(({ name }) => [name, new Trend(`${name}_duration`, true)]));
const droppedMetric = (name) => `dropped_iterations{scenario:${name}}`;

export const options = {
  setupTimeout: __ENV.AUTH_SETUP_TIMEOUT || '5m',
  summaryTrendStats: ['avg', 'p(95)', 'p(99)'],
  scenarios: Object.fromEntries(stages.map(({ name, operation, rate, startsAfter, duration }) => [
    name, {
      executor: 'constant-arrival-rate',
      exec: operation,
      rate,
      timeUnit: midrange && operation !== 'detail' ? '2s' : '1s',
      startTime: `${startsAfter}s`,
      duration: `${duration}s`,
      gracefulStop: '6s',
      preAllocatedVUs: rate * (midrange && operation !== 'detail' ? 3 : 6),
    },
  ])),
  // 태그별 시작 누락을 요약에 노출한다. 누락 자체는 측정 결과로 기록한다.
  thresholds: Object.fromEntries(stages.map(({ name }) => [droppedMetric(name), ['count>=0']])),
};

export function setup() {
  if (promotionComparison) {
    return { cancel: loginRunners(Array.from({ length: actorsPerOperation }, (_, index) => index + 2)) };
  }
  const accounts = Array.from({ length: actorsPerOperation * 2 }, (_, index) =>
    index < actorsPerOperation ? index + 2 : 1205 + index - actorsPerOperation);
  const tokens = loginRunners(accounts);
  return {
    cancel: tokens.slice(0, actorsPerOperation),
    apply: tokens.slice(actorsPerOperation),
  };
}

function run(operation, tokens, suffix, expectedCode, expectedStatus) {
  const { name, iterationInTest } = execution.scenario;
  if (iterationInTest === 0) console.log(`${name} starts at ${new Date().toISOString()}`);
  const token = operation === 'detail' ? tokens.apply[0] : tokens[operation][iterationInTest];
  if (!token) execution.test.abort(`No unused account for ${name}`);
  const headers = { Authorization: `Bearer ${token}` };
  const tags = {};
  if (traceWrites && operation !== 'detail') {
    const traceId = `${name}-${iterationInTest}`;
    headers['X-Runmate-Trace-Id'] = traceId;
    tags.trace_id = traceId;
  }
  const { eventId, eventIds } = stagesByName[name];
  const target = eventIds ? eventIds[iterationInTest % eventIds.length] : eventId;
  if (spreadComparison) tags.event_id = String(target);
  const url = `${api}/running-events/${target}${suffix}`;
  const params = { headers, tags, timeout: '5s' };
  const response = operation === 'detail' ? http.get(url, params) : http.post(url, null, params);
  let outcome = 'unexpected';
  if (response.status === expectedCode && (operation === 'detail' || response.json('status') === expectedStatus)) {
    outcome = 'ok';
    latency[name].add(response.timings.duration);
  } else if (response.error_code === 1050) {
    outcome = 'timeout';
  } else if ((gateComparison || promotionComparison) && response.status === 503 && response.json('code') === 'EVENT_WRITE_BUSY') {
    outcome = 'busy';
  } else if (response.status >= 500) {
    outcome = 'server_error';
  } else if (response.status >= 400) {
    outcome = 'rejected';
  }
  counters[name][outcome].add(1);
}

export function apply(tokens) {
  run('apply', tokens, '/participants', 201, 'WAITING');
}

export function cancel(tokens) {
  run('cancel', tokens, '/participants/cancel', 200, 'CANCELED');
}

export function detail(tokens) {
  run('detail', tokens, '', 200);
}

export function handleSummary(data) {
  const round = (value) => Number(value.toFixed(2));
  const results = stages.map(({ name, operation, rate, eventId, eventIds, duration }) => {
    const counts = Object.fromEntries(outcomes.map((outcome) => [
      outcome, data.metrics[`${name}_${outcome}`]?.values.count ?? 0,
    ]));
    const completed = Object.values(counts).reduce((sum, count) => sum + count, 0);
    const times = data.metrics[`${name}_duration`]?.values;
    return {
      name, operation, rate: midrange && operation !== 'detail' ? rate / 2 : rate, eventId, completed, ...counts,
      dropped: data.metrics[droppedMetric(name)]?.values.count ?? 0,
      ...(finalCheck ? { durationSeconds: duration } : {}),
      ...(eventIds ? { eventIds } : {}),
      completedRps: round(completed / duration),
      avgMs: times ? round(times.avg) : null,
      p95Ms: times ? round(times['p(95)']) : null,
      p99Ms: times ? round(times['p(99)']) : null,
    };
  });
  return { stdout: `${JSON.stringify(finalCheck ? { results } : { durationSeconds, results })}\n` };
}
