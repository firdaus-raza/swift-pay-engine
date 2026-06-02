import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

export const options = {
  scenarios: {
    constant_request_rate: {
      executor: 'constant-arrival-rate',
      rate: 250, // 250 requests per second
      timeUnit: '1s',
      duration: '4000s', // 4000 seconds = ~66.6 minutes -> total 1,000,000 requests
      preAllocatedVUs: 100, // how large the initial pool of VUs would be
      maxVUs: 500, // if the preAllocatedVUs are not enough, we can initialize more
    },
  },
};

// Two specific UUIDs you'll need to create in the database ahead of time for testing
// The `init.sql` script creates these two users.
const SENDER_ID = "11111111-1111-1111-1111-111111111111"; // Alice
const RECEIVER_ID = "22222222-2222-2222-2222-222222222222"; // Bob

export default function () {
  const url = 'http://localhost:8081/v1/payments';
  
  const payload = JSON.stringify({
    transactionId: uuidv4(),
    senderId: SENDER_ID,
    receiverId: RECEIVER_ID,
    amount: 0.01, // Small amount to allow for 1M transfers if Alice has $10k+
    currency: 'USD'
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
  };

  const res = http.post(url, payload, params);

  check(res, {
    'is status 202': (r) => r.status === 202,
  });
}
