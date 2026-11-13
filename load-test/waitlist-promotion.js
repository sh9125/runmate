import { check } from 'k6';
import http from 'k6/http';
import { api, loginRunner } from './runner-auth.js';

const eventUrl = `${api}/running-events/2`;

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    checks: ['rate==1'],
  },
};

export function setup() {
  return {
    participant: loginRunner(2),
    firstInLine: loginRunner(3),
    secondInLine: loginRunner(4),
  };
}

function authorized(token) {
  return { headers: { Authorization: `Bearer ${token}` }, timeout: '5s' };
}

export default function (tokens) {
  const cancellation = http.post(
    `${eventUrl}/participants/cancel`, null, authorized(tokens.participant),
  );
  check(cancellation, {
    'runner2 was canceled': (response) =>
      response.status === 200 && response.json('status') === 'CANCELED',
  });

  const promoted = http.get(eventUrl, authorized(tokens.firstInLine));
  check(promoted, {
    'runner3 was promoted': (response) =>
      response.status === 200 && response.json('applicationStatus') === 'PARTICIPATING',
    'the event still has exactly two participants': (response) =>
      response.status === 200 && response.json('currentParticipantCount') === 2,
  });

  const next = http.get(`${eventUrl}/waitlist/me`, authorized(tokens.secondInLine));
  check(next, {
    'runner4 is now first in line': (response) =>
      response.status === 200 && response.json('position') === 1,
  });
}
