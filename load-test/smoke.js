// smoke.js — 기본 동작 확인 (5명, 30초)
// 목적: 부하 테스트 전 API가 정상 응답하는지 확인
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const errorRate   = new Rate('errors');
const kpiDuration = new Trend('kpi_duration', true);

export const options = {
  vus: 5,
  duration: '30s',
  thresholds: {
    http_req_failed:   ['rate<0.01'],      // 에러율 1% 미만
    http_req_duration: ['p(95)<500'],      // 95%ile 500ms 미만
    errors:            ['rate<0.01'],
  },
};

const BASE = 'http://localhost:8080';
const ENDPOINTS = [
  '/api/kpis',
  '/api/revenue-by-customer',
  '/api/revenue-trend',
  '/api/yield-by-product',
  '/api/defects-by-type',
  '/api/production-lots',
  '/api/orders',
];

export default function () {
  // 랜덤 엔드포인트 호출
  const url = BASE + ENDPOINTS[Math.floor(Math.random() * ENDPOINTS.length)];
  const res = http.get(url);

  const ok = check(res, {
    'status 200': (r) => r.status === 200,
    'body not empty': (r) => r.body.length > 2,
    'response < 500ms': (r) => r.timings.duration < 500,
  });

  errorRate.add(!ok);
  if (url.endsWith('/kpis')) kpiDuration.add(res.timings.duration);

  sleep(1);
}
