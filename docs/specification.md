# 반도체 파운드리 운영 대시보드 — 시스템 사양서

**문서 버전**: 1.1  
**작성일**: 2026-07-07 (2026-07-19 RAG 확장 반영)  
**프로젝트명**: Semiconductor Foundry Operations Dashboard  
**작성자**: 개발팀

> **2026-07-19 업데이트**: 사내 문서(규정·매뉴얼) 검색용 RAG(Retrieval-Augmented Generation) 파이프라인을
> `rag-service` 모듈로 추가했습니다. 기존 대시보드·NL2SQL 챗봇은 변경 없이 그대로 동작하며, 관련 내용은
> 본 문서 전반에 "(RAG 확장)"으로 표시했습니다.

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
| REST API 8개 엔드포인트 + RAG 확장 API 4종 | 페이지네이션 |
| AI 자연어 질의 (Claude API) — SQL/문서 자동 분류 | CI/CD 파이프라인 |
| SQL 보안 가드레일 | 실시간 스트리밍 |
| PostgreSQL DB 설계 및 시드 데이터 | 모바일 최적화 |
| 사내 문서 업로드·검색(RAG, pgvector) | 운영(Railway) RAG 배포 (후속 작업) |

---

## 2. 시스템 아키텍처

```
┌─────────────────────────────────────────────────────┐
│                   클라이언트 (브라우저)                │
│              Vue 3 SPA  — localhost:5173              │
│     대시보드 · AI 챗봇(출처 표시) · 문서 관리 화면      │
└───────────────────────┬─────────────────────────────┘
                        │ HTTP (Vite Proxy) — /api/** 만 호출
                        ▼
┌─────────────────────────────────────────────────────┐
│         Spring Boot 3.2 — backend  (:8080)            │
│                                                      │
│  DashboardCtrl   AiChatService        DocumentProxy  │
│  (8 endpoints)   질문 분류(SQL/문서)   Ctrl (RAG 중계) │
│       │            │        │              │         │
└───────┼────────────┼────────┼──────────────┼─────────┘
        │            │        │ (문서 질문)   │ (업로드/목록/삭제)
        ▼            ▼        ▼              ▼
┌──────────────┐ ┌──────┐  ┌───────────────────────────┐
│ PostgreSQL 16 │ │Claude│  │  rag-service (:8081) 신규   │
│ foundry_db    │ │ API  │  │  Tika 파싱 → 청킹 → 임베딩  │
│ + pgvector    │◄┼──────┼─▶│  → pgvector 저장/검색/삭제  │
│ (공용 DB)     │ └──────┘  └──────────┬────────────────┘
└──────────────┘                       │
                                        ▼
                          ┌─────────────────────────┐
                          │ OpenAI Embeddings (선택)  │
                          │ 키 없으면 해싱 폴백 사용    │
                          └─────────────────────────┘
```

> **(RAG 확장)** backend와 rag-service는 같은 PostgreSQL(pgvector 확장 포함)을 공유하는 별도의 Spring Boot
> 프로세스입니다. 프론트엔드는 backend의 `/api/**`만 호출하고, 문서 업로드·조회·삭제 요청은 backend가
> 내부적으로 rag-service(:8081)에 그대로 전달(프록시)합니다. 자세한 구조는
> [프로그램 아키텍처 설계서 통합 문서](foundry-ai-proposal.pdf)를 참고하세요.

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
| 빌드 도구 | Maven (멀티모듈) | 3.9.x | backend + rag-service 독립 빌드 유지 |
| JDK | OpenJDK | 21 LTS | 최신 LTS, 성능 향상 |
| **(RAG 확장)** 벡터 검색 | pgvector | 0.8.x | PostgreSQL 네이티브 벡터 유사도 검색 |
| **(RAG 확장)** 문서 파싱 | Apache Tika | 2.9.2 | PDF/DOCX/TXT 등 포맷 무관 텍스트 추출 |
| **(RAG 확장)** 임베딩 | OpenAI Embeddings | text-embedding-3-small | 1536차원, 키 없으면 해싱 폴백으로 대체 |

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

── (RAG 확장) rag-service 모듈 소유 테이블 — 같은 DB에 pgvector 확장으로 추가 ──

document_meta
├── id (PK)
├── filename
├── content_type
├── file_size
├── chunk_count
├── status [PROCESSING|COMPLETED|FAILED]
└── uploaded_at
       │
       │ 1:N (ON DELETE CASCADE)
       ▼
document_chunks
├── id (PK)
├── document_id (FK → document_meta)
├── chunk_index
├── content (TEXT)
├── embedding (vector(1536))          -- pgvector
└── created_at
    └── ivfflat 인덱스 (vector_cosine_ops, lists=10)
```

### 4.2 시드 데이터 규모
| 테이블 | 레코드 수 |
|---|---|
| customers | 8 |
| products | 8 |
| sales_orders | 20 |
| production_lots | 20 |
| defect_records | 15 |
| document_meta (RAG 확장) | 앱 최초 기동 시 샘플 2건 자동 시드 |
| document_chunks (RAG 확장) | 위 샘플 문서 청킹 결과 (문서당 1~수 개) |

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
| POST | `/chat` | AI 자연어 질의 (하위호환, 단순 응답) | `{answer: String}` |
| POST | `/ai/chat` **(RAG 확장)** | AI 자연어 질의 — SQL/문서 자동 분류 | `{answer, type, sources[], confidence}` |
| POST | `/documents/upload` **(RAG 확장)** | 문서 업로드 (rag-service로 중계) | `{documentId, filename, status}` |
| GET | `/documents` **(RAG 확장)** | 업로드 문서 목록 | Array |
| DELETE | `/documents/{id}` **(RAG 확장)** | 문서 및 청크 삭제 (cascade) | `204 No Content` |

### CORS 설정
- 허용 Origin: `http://localhost:5173`
- 허용 Method: GET, POST, PUT, DELETE, OPTIONS

---

## 6. AI 파이프라인 설계

### 6.1 처리 흐름 (Two-Pass, 기존 SQL 경로)

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

### 6.1-B (RAG 확장) 질문 자동 분류 및 문서 검색 경로

`/api/ai/chat`은 위 SQL 경로 앞에 분류 단계를 추가하고, 문서 질문이면 별도 경로로 보냅니다.

```
사용자 질문
    │
    ▼
[분류] Claude API — "SQL" 또는 "DOCUMENT" 한 단어 응답
    │
    ├── SQL 인 경우 → 위 6.1 기존 파이프라인 그대로 수행
    │
    └── DOCUMENT 인 경우
            │
            ▼
        backend → rag-service(:8081) POST /api/documents/search
            │  질문을 임베딩으로 변환 → pgvector 코사인 유사도(<=>) 검색
            ▼
        상위 유사 청크(파일명·청크번호·유사도) 반환
            │
            ▼
        Claude API — 청크 내용을 근거로 답변 생성 (근거 없으면 "모른다"고 답변)
            │
            ▼
        {answer, type:"document", sources:[{filename, chunkIndex, similarity}], confidence}
```

**임베딩 폴백**: `OPENAI_API_KEY`가 없으면 rag-service는 어휘 기반 해싱(bag-of-words feature hashing, 1536차원)으로
대체합니다 — 의미 기반 검색만큼 정확하지 않지만, 키가 없어도 데모/검색 기능 자체는 항상 동작합니다.

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
├── 헤더 — "📄 문서 관리" 토글 버튼 (RAG 확장)
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
├── AIChatbox.vue — AI 채팅 (출처·신뢰도 표시, 📎 업로드 버튼 추가)
│   └── useChatApi.js (RAG 확장) — sendChat / uploadDocument / listDocuments / deleteDocument
├── DocumentsManager.vue (RAG 확장) — 업로드 문서 목록 + 삭제
└── useDashboardAPI.js — 대시보드 API 호출 composable
```

### 7.2 상태 관리
- Composition API (`ref`, `computed`) 사용
- Vuex/Pinia 없이 composable 패턴으로 관리 (라우터 없이 `showDocs` ref로 화면 토글)
- `Promise.all`로 7개 대시보드 API 병렬 호출

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
# 1. 환경 변수 설정 (OPENAI_API_KEY는 선택 — 없으면 해싱 폴백으로 RAG 검색 동작)
echo "ANTHROPIC_API_KEY=sk-ant-..." > .env
echo "OPENAI_API_KEY=sk-...(선택)" >> .env

# 2. 전체 실행 (rag-service → backend → frontend 순으로 자동 기동)
./start.sh

# 3. 접속
# 대시보드:    http://localhost:5173
# Backend API: http://localhost:8080/api/kpis
# rag-service: http://localhost:8081/api/documents   (RAG 확장, 내부 전용)
```

> **(RAG 확장) 사전 준비**: 로컬 PostgreSQL에 `pgvector` 확장이 설치되어 있어야 합니다.
> Homebrew의 `postgresql@16`은 pgvector 바이너리 배포 대상에서 빠져 있어 소스 빌드가 필요할 수 있습니다
> (`postgresql_setup_guide.md` 11장 참고). Docker(`docker-compose up`)로 실행하면
> `pgvector/pgvector:pg16` 이미지를 사용하므로 이 과정이 필요 없습니다.

### 9.3 주요 디렉토리 구조 (2026-07-19, RAG 확장 후 — Maven 멀티모듈)
```
foundry-dashboard/
├── pom.xml                   # 루트 aggregator (backend + rag-service 모듈 나열만, parent 아님)
├── .env                      # API Key (git 제외)
├── .gitignore
├── start.sh                  # 통합 실행 스크립트 (rag-service → backend → frontend 순 기동)
├── db_setup.sql              # DDL + 시드 데이터 (pgvector 확장 + 문서 테이블 포함)
├── docker-compose.yml        # db(pgvector 이미지) + backend + rag-service + frontend
├── docs/
│   ├── specification.md              # 본 사양서
│   ├── specification_enterprise.md   # 엔터프라이즈 적용 사양서
│   ├── postgresql_setup_guide.md     # PostgreSQL 세팅 가이드
│   ├── foundry-ai-proposal.pdf       # 제안요청서·기술제안서·아키텍처 설계서 통합본
│   ├── make_pptx.py / presentation.pptx  # 임원 발표 자료
│   └── ...
├── backend/                  # 기존 모듈 (변경 없음, 포트 8080)
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/com/foundry/dashboard/
│       ├── FoundryDashboardApplication.java
│       ├── config/WebConfig.java
│       ├── controller/DashboardController.java
│       ├── controller/AiController.java          # (RAG 확장) /api/ai/chat
│       ├── controller/DocumentProxyController.java  # (RAG 확장) 문서 API 중계
│       ├── dto/ChatResponse.java                 # (RAG 확장)
│       └── service/AiChatService.java            # SQL/문서 자동 라우팅으로 확장
├── rag-service/               # (RAG 확장) 신규 모듈, 포트 8081
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/com/foundry/rag/
│       ├── RagServiceApplication.java
│       ├── controller/DocumentController.java
│       ├── service/DocumentIngestionService.java
│       ├── service/TextChunker.java
│       ├── service/EmbeddingService.java
│       ├── service/VectorSearchService.java
│       └── config/DemoRagSeeder.java
└── frontend/
    ├── package.json
    ├── vite.config.js
    ├── index.html
    └── src/
        ├── main.js
        ├── App.vue
        ├── components/AIChatbox.vue
        ├── components/DocumentsManager.vue   # (RAG 확장)
        └── composables/
            ├── useDashboardAPI.js
            └── useChatApi.js                 # (RAG 확장)
```

---

## 10. 향후 확장 계획

| 단계 | 내용 | 예상 기간 | 상태 |
|---|---|---|---|
| 0단계 | 사내 문서 RAG 검색 (rag-service, pgvector) | - | ✅ 완료 (2026-07-19) |
| 0-B단계 | rag-service 운영(Railway) 배포 반영 | 1주 | 미착수 |
| 1단계 | 사용자 인증 (JWT) + 권한 관리 | 2주 | 미착수 |
| 2단계 | 로컬 LLM 전환 (vLLM + Qwen2.5) | 4주 | 미착수 |
| 3단계 | 실시간 데이터 연동 (WebSocket) | 3주 | 미착수 |
| 4단계 | 알림 시스템 (수율 임계값 경보) | 2주 | 미착수 |
| 5단계 | 모바일 반응형 UI | 2주 | 미착수 |

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
