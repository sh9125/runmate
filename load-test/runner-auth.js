import execution from 'k6/execution';
import http from 'k6/http';

export const api = 'http://127.0.0.1:8081/api/v1';

function loginRequest(number) {
  return {
    method: 'POST',
    url: `${api}/auth/login`,
    body: JSON.stringify({
      email: `runner${number}@runmate.test`,
      password: 'RunMateLoad123!',
    }),
    params: { headers: { 'Content-Type': 'application/json' }, timeout: __ENV.AUTH_LOGIN_TIMEOUT || '10s' },
  };
}

function accessToken(response, number) {
  const token = response.status === 200 ? response.json('accessToken') : null;
  if (!token) {
    execution.test.abort(`runner${number} login failed (HTTP ${response.status})`);
  }
  return token;
}

export function loginRunner(number) {
  const { url, body, params } = loginRequest(number);
  return accessToken(http.post(url, body, params), number);
}

export function loginRunners(numbers) {
  const tokens = [];
  const batchSize = __ENV.AUTH_BATCH_SIZE ? Number(__ENV.AUTH_BATCH_SIZE) : 6;
  if (!Number.isInteger(batchSize) || batchSize < 1) {
    execution.test.abort('AUTH_BATCH_SIZE must be a positive integer');
  }
  for (let start = 0; start < numbers.length; start += batchSize) {
    const batch = numbers.slice(start, start + batchSize);
    const responses = http.batch(batch.map(loginRequest));
    responses.forEach((response, index) => tokens.push(accessToken(response, batch[index])));
  }
  return tokens;
}
