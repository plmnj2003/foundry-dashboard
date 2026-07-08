// quick_scale.js — 현재 구성 한계 빠른 확인 (50 → 300명, 6분)
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const errorRate = new Rate('error_rate');
const p95trend  = new Trend('response_p95', true);

export const options = {
  stages: [
    { duration: '1m', target: 50  },
    { duration: '1m', target: 100 },
    { duration: '1m', target: 200 },
    { duration: '1m', target: 300 },
    { duration: '1m', target: 300 },
    { duration: '1m', target: 0   },
  ],
  thresholds: {
    'http_req_failed':   ['rate<0.05'],
    'http_req_duration': ['p(95)<3000'],
  },
};

const BASE = 'http://localhost:8080';
const EPS = [
  '/api/kpis',
  '/api/revenue-trend',
  '/api/revenue-by-customer',
  '/api/yield-by-product',
  '/api/defects-by-type',
  '/api/production-lots',
  '/api/orders',
];

export default function () {
  const url = BASE + EPS[Math.floor(Math.random() * EPS.length)];
  const res = http.get(url);
  const ok = check(res, {
    'status 200':    (r) => r.status === 200,
    'under 2000ms':  (r) => r.timings.duration < 2000,
  });
  errorRate.add(!ok);
  p95trend.add(res.timings.duration);
  sleep(Math.random() * 1.5 + 0.5);  // 0.5~2초
}

export function handleSummary(data) {
  const m = data.metrics;
  const d = m.http_req_duration?.values;
  const fail = m.http_req_failed?.values;

  const verdict = (fail?.rate < 0.01 && d?.['p(95)'] < 1000) ? '✅ 2000명 가능 (캐싱 추가 권장)' :
                  (fail?.rate < 0.05 && d?.['p(95)'] < 3000) ? '⚠️  튜닝 필요 (캐싱+인프라 보강)' :
                  '❌ 현 구성으로 2000명 불가 — 아키텍처 개선 필수';

  const report = `
══════════════════════════════════════════════════
  현재 구성 부하 테스트 결과 (최대 300명 동시)
══════════════════════════════════════════════════
  총 요청:   ${m.http_reqs?.values?.count}
  RPS:       ${m.http_reqs?.values?.rate?.toFixed(1)} req/s
  에러율:    ${((fail?.rate ?? 0)*100).toFixed(2)}%

  응답시간
    평균:  ${d?.avg?.toFixed(0)}ms
    p50:   ${d?.['p(50)']?.toFixed(0)}ms
    p95:   ${d?.['p(95)']?.toFixed(0)}ms
    p99:   ${d?.['p(99)']?.toFixed(0)}ms
    최대:  ${d?.max?.toFixed(0)}ms

  판정: ${verdict}
══════════════════════════════════════════════════
`;
  return {
    stdout: report,
    '/Users/deejayseo/foundry-dashboard/load-test/result_current.txt': report,
  };
}
