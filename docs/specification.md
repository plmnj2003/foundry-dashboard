# 반도체 파운드리 운영 대시보드 — 시스템 사양서

**문서 버전**: 1.0  
**작성일**: 2026-07-07  
**프로젝트명**: Semiconductor Foundry Operations Dashboard  
**작성자**: 개발팀

---

## 1. 프로젝트 개요

### 1.1 배경
반도체 파운드리 운영 현장에서 생산, 품질, 매출 데이터가 각각 분산된 시스템에 저장되어 있어 통합적인 의사결정이 어려운 상황. 실시간 KPI 모니터링과 자연어 기반 데이터 분석 기능을 통해 운영 효율성을 높이는 대시보드 시스템을 구축.

### 1.2 목표
- 생산/품질/매출 데이터를 단일 대시보드에서 통합 조회
- AI 기반 자연어 질의(Text-to-SQL)로 비개발자도 데이터 분석 가능
- SQL 가드레일 적용으로 DB 보안 유지
- 확장 가능한 아키텍처 (향후 로컬 LLM 전환 대응)

### 1.3 범위
| 포함 | 미포함 |
|---|---|
| 대시보드 UI (KPI, 차트, 테이블) | 사용자 인증/권한 |
| REST API 8개 엔드포인트 | 페이지네이션 |
| AI 자연어 질의 (Claude API) | CI/CD 파이프라인 |
| SQL 보안 가드레일 | 실시간 스트리밍 |
| PostgreSQL DB 설계 및 시드 데이터 | 모바일 최적화 |

---

## 2. 시스템 아키텍처

```
┌─────────────────────────────────────────────────────┐
│                   클라이언트 (브라우저)                │
│              Vue 3 SPA  — localhost:5173              │
└───────────────────────┬─────────────────────────────┘
                        │ HTTP (Vite Proxy)
                        ▼
┌─────────────────────────────────────────────────────┐
│              Spring Boot 3.2 REST API                │
│                  localhost:8080                       │
│                                                      │
│   ┌──────────────┐    ┌─────────────────────────┐   │
│   │DashboardCtrl │    │    AiChatService         │   │
│   │  (8 endpoints│    │  SQL 생성 → 검증 → 실행  │   │
│   └──────┬───────┘    └────────┬────────────────┘   │
│          │                     │                     │
└──────────┼─────────────────────┼─────────────────────┘
           │                     │
           ▼                     ▼
┌─────────────────┐    ┌─────────────────────────────┐
│   PostgreSQL 16  │    │     Anthropic Claude API     │
│   foundry_db     │    │   claude-sonnet-4-6          │
│   localhost:5432 │    │   (Text-to-SQL + 요약)        │
└─────────────────┘    └─────────────────────────────┘
```

---

## 3. 기술 스택

| 영역 | 기술 | 버전 | 선택 이유 |
|---|---|---|---|
| Frontend Framework | Vue 3 | 3.4.21 | Composition API, 경량, 생산성 |
| Frontend Build | Vite | 5.2.8 | 빠른 HMR, 간단한 설정 |
| 차트 라이브러리 | Chart.js + vue-chartjs | 4.4.2 / 5.3.0 | 가장 넓은 생태계 |
| HTTP 클라이언트 | Axios | 1.6.8 | 브라우저 호환성, 인터셉터 |
| Backend Framework | Spring Boot | 3.2.5 | 엔터프라이즈 표준, JVM |
| DB 접근 | Spring JdbcTemplate | - | 간결한 SQL 매핑, 오버헤드 없음 |
| DB | PostgreSQL | 16 | RDBMS 표준, JSON 지원 |
| AI SDK | Anthropic Java SDK | 2.34.0 | 공식 SDK, 타입 안전 |
| AI 모델 | claude-sonnet-4-6 | - | 비용 효율, SQL 생성 정확도 우수 |
| 빌드 도구 | Maven | 3.9.x | 표준 Java 빌드 |
| JDK | OpenJDK | 21 LTS | 최신 LTS, 성능 향상 |

---

## 4. 데이터베이스 설계

### 4.1 ERD 구조

```
customers
├── id (PK)
├── name
├── country
└── tier [PLATINUM|GOLD|SILVER|BRONZE]
       │
       │ 1:N
       ▼
sales_orders
├── id (PK)
├── customer_id (FK → customers)
├── product_id (FK → products)
├── quantity
├── unit_price
├── total_amount
├── order_date
└── status [PENDING|IN_PRODUCTION|SHIPPED|DELIVERED|CANCELLED]

products
├── id (PK)
├── name
├── technology_node (예: 5nm, 7nm, 12nm)
├── wafer_size (200 / 300 mm)
└── unit_price
       │
       │ 1:N
       ▼
production_lots
├── id (PK)
├── product_id (FK → products)
├── lot_number (UNIQUE)
├── quantity
├── start_date / end_date
├── status [QUEUED|IN_PROGRESS|COMPLETED|SCRAPPED]
└── yield_rate (%)
       │
       │ 1:N
       ▼
defect_records
├── id (PK)
├── lot_id (FK → production_lots)
├── defect_type
├── process_step
├── count
├── severity [CRITICAL|MAJOR|MINOR]
└── detected_at
```

### 4.2 시드 데이터 규모
| 테이블 | 레코드 수 |
|---|---|
| customers | 8 |
| products | 8 |
| sales_orders | 20 |
| production_lots | 20 |
| defect_records | 15 |

---

## 5. API 명세

### Base URL: `http://localhost:8080/api`

| Method | Endpoint | 설명 | 응답 |
|---|---|---|---|
| GET | `/kpis` | 핵심 KPI 4개 | `{totalRevenue, activeOrders, avgYieldRate, activeLots}` |
| GET | `/revenue-by-customer` | 고객별 매출 합계 | Array |
| GET | `/revenue-trend` | 월별 매출 추이 | Array (YYYY-MM, revenue) |
| GET | `/yield-by-product` | 제품별 평균 수율 | Array |
| GET | `/defects-by-type` | 불량 유형/심각도별 합계 | Array |
| GET | `/production-lots` | 최근 생산 Lot 20건 | Array |
| GET | `/orders` | 최근 주문 20건 | Array |
| POST | `/chat` | AI 자연어 질의 | `{answer: String}` |

### CORS 설정
- 허용 Origin: `http://localhost:5173`
- 허용 Method: GET, POST, PUT, DELETE, OPTIONS

---

## 6. AI 파이프라인 설계

### 6.1 처리 흐름 (Two-Pass)

```
사용자 질문 (자연어)
        │
        ▼
[Pass 1] Claude API — SQL 생성
        │  prompt: 스키마 + 질문 → SELECT 쿼리만 반환
        ▼
SQL 보안 가드레일 검증
  ① DDL 차단: CREATE, DROP, ALTER, TRUNCATE, RENAME
  ② DML 차단: INSERT, UPDATE, DELETE, MERGE
  ③ 위험 명령 차단: GRANT, REVOKE, EXECUTE, COPY
  ④ SELECT 시작 강제
  ⑤ LIMIT 100 자동 적용
  ⑥ 복수 문장(;) 차단
        │
        ▼
PostgreSQL 실행
        │
        ▼
[Pass 2] Claude API — 결과 요약
        │  결과 데이터 → 한국어 자연어 요약
        ▼
사용자에게 응답
```

### 6.2 가드레일 구현 (Java Regex)

```java
Pattern DDL       = Pattern.compile("(?i)\\b(CREATE|DROP|ALTER|TRUNCATE|RENAME)\\b");
Pattern DML       = Pattern.compile("(?i)\\b(INSERT|UPDATE|DELETE|MERGE|UPSERT|REPLACE)\\b");
Pattern DANGEROUS = Pattern.compile("(?i)\\b(GRANT|REVOKE|EXECUTE|EXEC|CALL|COPY|LOAD)\\b");
Pattern SELECT_ONLY = Pattern.compile("(?i)^\\s*SELECT\\b");
Pattern LIMIT_RE  = Pattern.compile("(?i)\\bLIMIT\\s+(\\d+)\\b");
```

---

## 7. 프론트엔드 구성

### 7.1 컴포넌트 구조

```
App.vue
├── KPI Cards (4개 지표)
├── Charts Row 1
│   ├── Line Chart — 월별 매출 추이
│   └── Bar Chart (수평) — 고객별 매출
├── Charts Row 2
│   ├── Bar Chart — 제품별 수율
│   └── Doughnut Chart — 불량 유형 분포
├── Tables Row
│   ├── 생산 Lot 테이블
│   └── 주문 테이블
└── AIChatbox.vue — AI 채팅 컴포넌트
    └── useDashboardAPI.js — API 호출 composable
```

### 7.2 상태 관리
- Composition API (`ref`, `computed`) 사용
- Vuex/Pinia 없이 composable 패턴으로 관리
- `Promise.all`로 7개 API 병렬 호출

---

## 8. 보안 설계

| 항목 | 적용 내용 |
|---|---|
| SQL Injection | 파라미터화 쿼리 + 가드레일 차단 |
| DB 권한 | SELECT 전용 사용자 권한 원칙 (권장) |
| API Key 관리 | `.env` 파일 (`.gitignore` 적용) |
| CORS | 허용 Origin 명시적 지정 |
| DDL/DML 차단 | 정규식 + SELECT 강제 |
| 데이터 노출 | LIMIT 100 자동 적용 |

---

## 9. 실행 환경

### 9.1 로컬 개발 환경
| 항목 | 사양 |
|---|---|
| OS | macOS (darwin 25.5.0) |
| JDK | OpenJDK 21 (Homebrew) |
| Maven | 3.9.16 |
| Node.js | v22.22.3 |
| npm | 10.9.8 |
| PostgreSQL | 16 (Homebrew) |

### 9.2 실행 방법
```bash
# 1. 환경 변수 설정
echo "ANTHROPIC_API_KEY=sk-ant-..." > .env

# 2. 전체 실행
./start.sh

# 3. 접속
# 대시보드: http://localhost:5173
# API:       http://localhost:8080/api/kpis
```

### 9.3 주요 디렉토리 구조
```
foundry-dashboard/
├── .env                      # API Key (git 제외)
├── .gitignore
├── start.sh                  # 통합 실행 스크립트
├── db_setup.sql              # DDL + 시드 데이터
├── docker-compose.yml        # Docker 구성 (선택)
├── docs/
│   ├── specification.md      # 본 사양서
│   └── presentation.pptx     # 임원 발표 자료
├── backend/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/com/foundry/dashboard/
│       ├── FoundryDashboardApplication.java
│       ├── config/WebConfig.java
│       ├── controller/DashboardController.java
│       └── service/AiChatService.java
└── frontend/
    ├── package.json
    ├── vite.config.js
    ├── index.html
    └── src/
        ├── main.js
        ├── App.vue
        ├── components/AIChatbox.vue
        └── composables/useDashboardAPI.js
```

---

## 10. 향후 확장 계획

| 단계 | 내용 | 예상 기간 |
|---|---|---|
| 1단계 | 사용자 인증 (JWT) + 권한 관리 | 2주 |
| 2단계 | 로컬 LLM 전환 (vLLM + Qwen2.5) | 4주 |
| 3단계 | 실시간 데이터 연동 (WebSocket) | 3주 |
| 4단계 | 알림 시스템 (수율 임계값 경보) | 2주 |
| 5단계 | 모바일 반응형 UI | 2주 |

---

## 11. 성과 지표 (데모 데이터 기준)

| KPI | 값 |
|---|---|
| 총 매출 | $22,403,500 |
| 활성 주문 | 11건 |
| 평균 수율 | 96.39% |
| 진행 중 Lot | 6개 |
| 최고 수율 제품 | Power IC 180nm (99.1%) |
| 최대 매출 고객 | TSMC ($7,360,000) |
