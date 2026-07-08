// stress.js — 한계점 탐색 (2000 → 5000명까지 밀어붙이기)
// 목적: 시스템이 언제 무너지기 시작하는지 확인
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const errorRate = new Rate('error_rate');

export const options = {
  stages: [
    { duration: '2m', target: 500  },
    { duration: '2m', target: 1000 },
    { duration: '2m', target: 2000 },
    { duration: '2m', target: 3000 },
    { duration: '2m', target: 4000 },
    { duration: '2m', target: 5000 },  // 한계점 탐색
    { duration: '2m', target: 0    },
  ],
  thresholds: {
    'http_req_failed': ['rate<0.10'],  // 스트레스: 10% 이하면 통과
  },
};

const BASE = 'http://localhost:8080';

export default function () {
  // KPI만 반복 호출 (가장 가벼운 엔드포인트로 한계 탐색)
  const res = http.get(`${BASE}/api/kpis`);
  const ok = check(res, { 'status 200': (r) => r.status === 200 });
  errorRate.add(!ok);
  sleep(0.5);
}
