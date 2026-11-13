import { check } from 'k6';
import execution from 'k6/execution';
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { api, loginRunner } from './runner-auth.js';

const runnerNumbers = [2, 3, 4, 5, 6];
const confirmed = new Counter('confirmed_participants');
const waiting = new Counter('waitlist_registrations');

export const options = {
  scenarios: {
    last_spot: {
      executor: 'per-vu-iterations',
      vus: runnerNumbers.length,
      iterations: 1,
      maxDuration: '15s',
    },
  },
  thresholds: {
    checks: ['rate==1'],
    confirmed_participants: ['count==1'],
    waitlist_registrations: ['count==4'],
  },
};

// 로그인은 참가 시나리오 전에 한 번씩 실행한다.
export function setup() {
  return runnerNumbers.map(loginRunner);
}

export default function (tokens) {
  const token = tokens[execution.vu.idInTest - 1];
  const response = http.post(`${api}/running-events/1/participants`, null, {
    headers: { Authorization: `Bearer ${token}` },
    timeout: '5s',
    tags: { name: 'last_spot_application' },
  });
  const status = response.status === 201 ? response.json('status') : null;

  check(status, {
    'application was confirmed or waitlisted': (value) =>
      value === 'PARTICIPATING' || value === 'WAITING',
  });
  confirmed.add(status === 'PARTICIPATING' ? 1 : 0);
  waiting.add(status === 'WAITING' ? 1 : 0);
}
