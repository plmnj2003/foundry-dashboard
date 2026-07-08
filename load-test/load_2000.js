// load_2000.js — 2000명 동시 사용자 부하 테스트
// 단계: 점진적 증가 → 2000명 유지 → 점진적 감소
import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

// ── 커스텀 메트릭 ─────────────────────────────────────────
const errorRate      = new Rate('error_rate');
const kpiP95         = new Trend('kpi_p95', true);
const revenueP95     = new Trend('revenue_p95', true);
const dbQueryFailed  = new Counter('db_query_failed');

// ── 부하 시나리오 ─────────────────────────────────────────
export const options = {
  stages: [
    { duration: '2m',  target: 100  },   // 웜업: 0 → 100명
    { duration: '3m',  target: 500  },   // 증가: 100 → 500명
    { duration: '3m',  target: 1000 },   // 증가: 500 → 1000명
    { duration: '3m',  target: 2000 },   // 증가: 1000 → 2000명
    { duration: '5m',  target: 2000 },   // 유지: 2000명 5분간
    { duration: '2m',  target: 0    },   // 감소
  ],

  // ── 합격 기준 (통과 못 하면 테스트 실패) ────────────────
  thresholds: {
    // 에러율: 1% 미만
    'http_req_failed': ['rate<0.01'],
    'error_rate':      ['rate<0.01'],

    // 응답 시간 (AI 채팅 제외)
    'http_req_duration{endpoint:kpi}':     ['p(95)<500',  'p(99)<1000'],
    'http_req_duration{endpoint:chart}':   ['p(95)<1000', 'p(99)<2000'],
    'http_req_duration{endpoint:table}':   ['p(95)<800',  'p(99)<1500'],

    // 전체 p95 2초 미만
    'http_req_duration': ['p(95)<2000'],
  },
};

const BASE = 'http://localhost:8080';

// 사용자 행동 패턴 (실제 사용 분포 반영)
// 대부분은 대시보드 조회, 일부만 AI 채팅
const USER_PROFILES = [
  { weight: 0.60, type: 'dashboard_viewer' },  // 60%: 대시보드만 조회
  { weight: 0.30, type: 'analyst' },            // 30%: 차트 + 테이블 조회
  { weight: 0.10, type: 'ai_user' },            // 10%: AI 채팅 사용
];

function pickProfile() {
  const r = Math.random();
  let acc = 0;
  for (const p of USER_PROFILES) {
    acc += p.weight;
    if (r < acc) return p.type;
  }
  return 'dashboard_viewer';
}

// ── 메인 시나리오 ─────────────────────────────────────────
export default function () {
  const profile = pickProfile();

  if (profile === 'dashboard_viewer') {
    // 페이지 진입 시 KPI + 차트 병렬 로딩 시뮬레이션
    group('Dashboard Load', () => {
      const responses = http.batch([
        { method: 'GET', url: `${BASE}/api/kpis`,
          params: { tags: { endpoint: 'kpi' } } },
        { method: 'GET', url: `${BASE}/api/revenue-trend`,
          params: { tags: { endpoint: 'chart' } } },
        { method: 'GET', url: `${BASE}/api/revenue-by-customer`,
          params: { tags: { endpoint: 'chart' } } },
      ]);

      responses.forEach((res, i) => {
        const ok = check(res, {
          [`batch[${i}] status 200`]: (r) => r.status === 200,
          [`batch[${i}] has body`]:   (r) => r.body && r.body.length > 2,
        });
        errorRate.add(!ok);
        if (i === 0) kpiP95.add(res.timings.duration);
      });
    });

    sleep(Math.random() * 3 + 2);  // 2~5초 페이지 열람

  } else if (profile === 'analyst') {
    // 분석가: 순차적으로 여러 차트 탐색
    group('Analyst Flow', () => {
      const endpoints = [
        { url: `${BASE}/api/yield-by-product`,    tag: 'chart' },
        { url: `${BASE}/api/defects-by-type`,     tag: 'chart' },
        { url: `${BASE}/api/production-lots`,     tag: 'table' },
        { url: `${BASE}/api/orders`,              tag: 'table' },
      ];

      // 2~3개 무작위 선택
      const count = Math.floor(Math.random() * 2) + 2;
      const selected = endpoints.sort(() => Math.random()-0.5).slice(0, count);

      selected.forEach(ep => {
        const res = http.get(ep.url, { tags: { endpoint: ep.tag } });
        const ok = check(res, {
          'status 200': (r) => r.status === 200,
          'has data':   (r) => r.body && r.body.length > 10,
        });
        errorRate.add(!ok);
        if (!ok) dbQueryFailed.add(1);
        revenueP95.add(res.timings.duration);
        sleep(Math.random() * 2 + 1);  // 1~3초 탐색
      });
    });

  } else {
    // AI 사용자: KPI 로드 후 AI 질문
    group('AI Chat Flow', () => {
      // 먼저 대시보드 KPI 로드
      const kpiRes = http.get(`${BASE}/api/kpis`,
        { tags: { endpoint: 'kpi' } });
      check(kpiRes, { 'kpi ok': (r) => r.status === 200 });

      sleep(Math.random() * 3 + 2);  // 2~5초 대시보드 열람 후 질문

      // AI 질문 (응답 시간 길어도 OK — 별도 threshold 없음)
      const questions = [
        '수익이 가장 높은 고객 Top 3는?',
        '이번 달 평균 수율은?',
        '최근 CRITICAL 불량이 발생한 Lot은?',
        '진행 중인 생산 Lot 현황은?',
      ];
      const q = questions[Math.floor(Math.random() * questions.length)];

      const chatRes = http.post(`${BASE}/api/chat`,
        JSON.stringify({ question: q }),
        {
          headers: { 'Content-Type': 'application/json' },
          timeout: '60s',
          tags: { endpoint: 'ai_chat' },
        }
      );
      check(chatRes, {
        'chat status 200': (r) => r.status === 200,
        'has answer':      (r) => {
          try { return JSON.parse(r.body).answer.length > 5; }
          catch { return false; }
        },
      });
    });

    sleep(Math.random() * 5 + 3);  // 3~8초 답변 읽기
  }
}

// ── 최종 요약 출력 ────────────────────────────────────────
export function handleSummary(data) {
  const m = data.metrics;
  const fmt = (v) => v !== undefined ? v.toFixed(2) : 'N/A';

  const report = `
========================================================
  부하 테스트 결과 요약  (2000명 동시 사용자)
========================================================

[요청 통계]
  총 요청 수:       ${m.http_reqs?.values?.count ?? 'N/A'}
  초당 요청 (RPS):  ${fmt(m.http_reqs?.values?.rate)} req/s
  에러율:           ${fmt((m.http_req_failed?.values?.rate ?? 0) * 100)} %

[응답 시간]
  평균:    ${fmt(m.http_req_duration?.values?.avg)} ms
  p50:     ${fmt(m.http_req_duration?.values?.['p(50)'])} ms
  p90:     ${fmt(m.http_req_duration?.values?.['p(90)'])} ms
  p95:     ${fmt(m.http_req_duration?.values?.['p(95)'])} ms
  p99:     ${fmt(m.http_req_duration?.values?.['p(99)'])} ms
  최대:    ${fmt(m.http_req_duration?.values?.max)} ms

[합격 기준]
  에러율 < 1%:    ${(m.http_req_failed?.values?.rate ?? 1) < 0.01 ? '✅ PASS' : '❌ FAIL'}
  p95 < 2000ms:   ${(m.http_req_duration?.values?.['p(95)'] ?? 9999) < 2000 ? '✅ PASS' : '❌ FAIL'}

[데이터 전송]
  송신: ${fmt((m.data_sent?.values?.count ?? 0) / 1024 / 1024)} MB
  수신: ${fmt((m.data_received?.values?.count ?? 0) / 1024 / 1024)} MB

========================================================
`;

  return {
    stdout: report,
    '/Users/deejayseo/foundry-dashboard/load-test/result_2000.txt': report,
  };
}
