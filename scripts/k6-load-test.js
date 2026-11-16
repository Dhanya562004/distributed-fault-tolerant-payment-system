import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';

// Custom Metrics
export const uniquePayments = new Counter('unique_payments');
export const duplicateReplays = new Counter('duplicate_replays');
export const cachedReplays = new Counter('cached_replays');

// Test Configuration: 20 concurrent VUs executing 1,000 requests over real HTTP against PostgreSQL
export const options = {
  scenarios: {
    payment_load: {
      executor: 'shared-iterations',
      vus: 20,
      iterations: 1000,
      maxDuration: '2m',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'], // < 1% errors
    http_req_duration: ['p(95)<3000'], // 95% under 3s
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || 'apexpay_admin_secret_key_2026';
const USERS = ['USR_ALICE', 'USR_BOB', 'USR_CHARLIE', 'USR_DAVID'];

// Per-VU buffer storing recent unique submissions (key + exact payload) to replay as ~5% duplicates
const recentSubmissions = [];
const MAX_BUFFER = 50;

export default function () {
  const isDuplicate = recentSubmissions.length > 5 && Math.random() < 0.05;

  let key;
  let payload;

  if (isDuplicate) {
    // Pick an existing submission and send the EXACT SAME payload for true idempotency replay
    const selected = recentSubmissions[Math.floor(Math.random() * recentSubmissions.length)];
    key = selected.key;
    payload = selected.payload;
    duplicateReplays.add(1);
  } else {
    // Generate fresh unique payment
    key = `IDEM_K6_${__VU}_${__ITER}_${Date.now()}_${Math.floor(Math.random() * 1000000)}`;
    const userId = USERS[Math.floor(Math.random() * USERS.length)];
    const amount = 50; // 50 cents (minor units) to preserve user balances
    payload = JSON.stringify({
      idempotencyKey: key,
      userId: userId,
      amount: amount,
      currency: 'USD',
      paymentMethod: 'CREDIT_CARD',
      description: 'k6 load test payment',
    });
    recentSubmissions.push({ key, payload });
    if (recentSubmissions.length > MAX_BUFFER) {
      recentSubmissions.shift();
    }
    uniquePayments.add(1);
  }

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-Api-Key': API_KEY,
      'X-Idempotency-Key': key,
    },
    tags: {
      type: isDuplicate ? 'duplicate_replay' : 'unique_payment',
    },
  };

  const res = http.post(`${BASE_URL}/api/v1/payments`, payload, params);

  const isReplayed = res.headers['Idempotent-Replayed'] === 'true' || res.headers['idempotent-replayed'] === 'true';
  if (isReplayed) {
    cachedReplays.add(1);
  }

  check(res, {
    'status is 201': (r) => r.status === 201,
    'payment status is SUCCESS': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.status === 'SUCCESS';
      } catch (e) {
        return false;
      }
    },
    'idempotent replay recognized': (r) => {
      if (isDuplicate) {
        return isReplayed;
      }
      return true;
    },
  });
}
