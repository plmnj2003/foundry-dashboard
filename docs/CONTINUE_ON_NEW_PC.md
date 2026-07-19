# 새 PC에서 이어서 개발하기 — Claude Code용 인계 프롬프트

이 파일 전체를 새 PC의 Claude Code(또는 다른 AI 코딩 어시스턴트) 첫 메시지로 그대로 붙여넣으면,
지금까지 진행된 내용을 그대로 이해하고 이어서 작업할 수 있습니다.

실제 API 키/비밀번호는 이 문서에 넣지 않았습니다 — `.env` 파일은 git에 포함되지 않으므로
**직접 복사하거나 다시 입력**해야 합니다 (아래 "새 PC 준비물" 참고).

---

## 프롬프트 시작 (아래부터 그대로 복사해서 사용)

나는 `foundry-dashboard`라는 반도체 파운드리 운영 대시보드 프로젝트를 다른 PC에서 이어서 개발하려고 해.
저장소를 클론했고, 아래 내용을 기준으로 현재 상태를 파악한 다음 진행해줘.

### 프로젝트 개요

- **이름**: 반도체 파운드리 운영 대시보드 (Semiconductor Foundry Operations Dashboard)
- **깃허브**: `https://github.com/plmnj2003/foundry-dashboard` (브랜치: `main`)
- **배포**: Railway 프로젝트 `foundry-dashboard` (project id `68ee2e8a-0fdc-4268-b31a-568418867f39`),
  운영 URL `https://foundry-dashboard-production.up.railway.app`
- **데모 목표일**: 2026-07-30

### 전체 구조 (Maven 멀티모듈)

```
foundry-dashboard/
├── pom.xml              # 루트 aggregator (parent 아님 — backend/pom.xml은 독립적으로 그대로 유지)
├── backend/             # 기존 대시보드 + NL2SQL 챗봇 (Spring Boot 3.2, Java 21, JdbcTemplate, 포트 8080)
├── rag-service/         # 사내 문서 RAG 검색 전담 (신규 모듈, Spring Boot, 포트 8081/Railway는 PORT 주입)
├── frontend/             # Vue 3 + Vite + Chart.js (포트 5173)
├── db_setup.sql          # DDL + 시드데이터 (docker-compose용 Postgres 초기화 스크립트)
├── docker-compose.yml    # db(pgvector 이미지) + backend + rag-service + frontend
├── start.sh              # 로컬 실행 스크립트 (rag-service → backend → frontend 순서로 기동)
└── docs/
    ├── specification.md               # 시스템 사양서
    ├── specification_enterprise.md    # 엔터프라이즈 적용 사양서 (2.1~2.3, 3.10, 5.3, 6.4, 8.5에 RAG 반영)
    ├── postgresql_setup_guide.md       # PostgreSQL 세팅 가이드 (11장에 pgvector 설치법)
    ├── foundry-ai-proposal.pdf         # 제안요청서·기술제안서·아키텍처 설계서 통합본
    ├── make_pptx.py / presentation.pptx  # 임원 발표자료 (16슬라이드, 13번이 RAG 확장 슬라이드)
    └── CONTINUE_ON_NEW_PC.md           # 이 문서
```

### 지금까지 개발된 기능

**기존 (변경 없음)**: 매출/생산/품질 대시보드(KPI 카드, 차트, 표), Claude 기반 자연어→SQL 챗봇
(SELECT 전용 가드레일: DDL/DML/위험명령 차단, LIMIT 100 자동 적용).

**이번에 추가한 것 (RAG 문서 검색)**:
1. **rag-service 모듈**: 문서 업로드 → Apache Tika로 텍스트 추출 → 문단 단위 청킹(800자, 10% 중첩)
   → 임베딩 생성(OpenAI `text-embedding-3-small`, 키 없으면 해싱 기반 폴백으로 항상 동작) →
   PostgreSQL의 `pgvector` 확장으로 코사인 유사도 검색·저장·삭제.
2. **backend의 AiChatService 확장**: 질문이 들어오면 Claude에게 짧게 "SQL이냐 DOCUMENT냐" 분류를
   먼저 물어보고, SQL이면 기존 파이프라인 그대로, DOCUMENT면 rag-service에 검색을 요청한 뒤
   그 내용을 근거로 답변을 생성 (출처 파일명·유사도까지 함께 반환).
3. **새 API**: `POST /api/ai/chat`(구조화 응답 `{answer,type,sources[],confidence}`),
   `POST /api/documents/upload`, `GET /api/documents`, `DELETE /api/documents/{id}`
   — 전부 backend가 rag-service로 중계(프록시)하므로 프론트엔드는 backend `/api/**`만 호출.
4. **프론트엔드**: `useChatApi.js`(신규 composable), `AIChatbox.vue`에 출처·신뢰도 표시 + 📎 업로드
   버튼 추가, `DocumentsManager.vue`(신규 — 업로드 문서 목록/삭제, 헤더의 "📄 문서 관리" 버튼으로 토글,
   vue-router 없이 구현).
5. **DB 신규 테이블**: `document_meta`, `document_chunks`(embedding vector(1536), ivfflat 인덱스).
6. **데모 시드**: rag-service가 처음 기동할 때 `document_meta`가 비어있으면 사내 출장 규정/품질관리
   매뉴얼 샘플 문서 2건을 자동으로 넣어줌 (`DemoRagSeeder`).

### 로컬 환경에서 알아둬야 할 것 (중요)

- **pgvector 확장이 필요합니다.** Docker로 실행하면 `docker-compose.yml`의 db 이미지가
  이미 `pgvector/pgvector:pg16`이라 별도 설치 없이 됩니다 (권장 경로).
- 만약 Docker 없이 로컬 Postgres(Homebrew 등)를 직접 쓴다면, pgvector 확장이 미리 설치돼 있어야
  하고, **Homebrew의 pgvector 바틀은 최신 1~2개 PostgreSQL 버전만 지원**해서 오래된 버전(예: 16)에는
  안 깔릴 수 있습니다 — 그 경우 `postgresql_setup_guide.md` 11장의 소스 빌드 방법을 참고하세요.
- `.env`에 `ANTHROPIC_API_KEY`는 필수, `OPENAI_API_KEY`는 선택(없으면 해싱 폴백 임베딩으로 동작은
  하지만 검색 정확도가 낮음).
- 실행: `./start.sh` (brew postgresql 필요) 또는 `docker-compose up --build` (권장, 아무것도 안 깔려있어도 됨).

### Railway 배포 관련 알아둬야 할 것 (중요 — CLI로는 안 되는 것들)

- Railway 프로젝트에 서비스 3개: `foundry-dashboard`(backend+frontend, 기존), `rag-service`(신규),
  `Postgres`(이미지 `ghcr.io/railwayapp-templates/postgres-ssl:18`, pgvector 정상 동작 확인됨).
- **Railway CLI(`railway add`)로는 모노레포의 서비스별 "Root Directory"를 설정할 방법이 없습니다.**
  rag-service 서비스를 새로 만들면 기본적으로 레포 루트의 `Dockerfile`(backend용)을 잘못 빌드하므로,
  **Railway 대시보드 → 해당 서비스 → Settings → Build → Root Directory를 `rag-service`로 설정**해야
  합니다 (이미 설정 완료된 상태이지만, 서비스를 다시 만들 일이 있으면 잊지 말 것).
- Root Directory를 설정하면 그 폴더 안의 `rag-service/railway.json`을 자동으로 찾아서 Dockerfile
  빌드로 전환됩니다 (이미 파일 존재).
- **Railway가 런타임에 주입하는 `PORT` 환경변수는 `railway variable list`에 안 보입니다.** rag-service의
  Dockerfile은 `EXPOSE 8081`이지만 실제로는 Railway가 준 `PORT`(현재 값 8080)로 리스닝합니다.
  그래서 backend의 `RAG_SERVICE_URL` 변수는 `http://rag-service.railway.internal:8080`으로
  맞춰져 있습니다 — 포트가 다시 안 맞으면 이 부분부터 의심할 것.
- 새로운 `.railway/railway.ts` (Infrastructure-as-Code) 방식은 `railway config pull/apply`가
  "Railway TypeScript SDK 설치 필요" 오류를 내며 막혔었습니다(npm에 해당 패키지가 안 보임) — 그래서
  전부 imperative `railway` CLI 명령(`railway add`, `railway variable set` 등) + 대시보드 수동
  설정 한 번으로 처리했습니다.

### Git 상태

메인 브랜치에 전부 커밋·푸시되어 있습니다 (최근 커밋: "feat: 사내 문서 RAG 검색 기능 추가
(rag-service 멀티모듈)", "fix: rag-service가 Railway PORT 환경변수를 사용하도록 수정"). 작업 중이던
미완료 브랜치는 없습니다.

### 지금 해야 할 일 / 다음 단계

지금 시점에서 급하게 처리해야 할 미완료 작업은 없습니다. 로컬과 Railway 운영 환경 모두에서
대시보드, SQL 챗봇, 문서 업로드/검색/삭제까지 전부 정상 동작 확인했습니다. 이어서 할 만한 것:
- (선택) 실제 PDF/DOCX 규정 문서로 업로드 리허설 (지금은 샘플 txt 2건만 시드됨)
- (선택) `specification.md` 10장 로드맵에 있는 인증/로컬LLM/실시간연동 등 후속 단계

먼저 `docs/specification.md`, `docs/specification_enterprise.md`를 읽고 현재 코드 상태와
일치하는지 확인한 다음, 내가 다음에 뭘 하고 싶은지 알려주면 거기서부터 진행해줘.

## 프롬프트 끝

---

## 새 PC 준비물 (프롬프트 붙여넣기 전에 먼저 할 것)

1. **저장소 클론**: `git clone https://github.com/plmnj2003/foundry-dashboard.git`
2. **필수 설치**: JDK 21 (`brew install openjdk@21` 등), Maven, Node.js 22.x, 그리고
   Docker Desktop(권장) 또는 PostgreSQL 16 직접 설치
3. **`.env` 파일 직접 생성** (git에 없음 — 기존 PC의 `.env` 내용을 복사하거나 새로 발급):
   ```
   ANTHROPIC_API_KEY=sk-ant-...
   OPENAI_API_KEY=sk-...          # 선택
   ```
4. **Railway CLI 연결** (배포 쪽 작업을 이어서 하려면): `brew install railway`, `railway login`,
   저장소 루트에서 `railway link` 후 프로젝트 `foundry-dashboard` 선택
5. 준비되면 위 "프롬프트 시작~끝" 구간을 새 PC의 Claude Code에 그대로 붙여넣기
