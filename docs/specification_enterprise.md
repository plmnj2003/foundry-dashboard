# 반도체 파운드리 운영 대시보드 — 엔터프라이즈 적용 사양서

**문서 버전**: 2.0  
**작성일**: 2026-07-07  
**적용 대상**: 기존 운영 인프라 보유 사업장  
**문서 목적**: 샘플 환경에서 검증된 대시보드를 실운영 시스템에 연동·배포하기 위한 상세 기술 사양

---

## 목차

1. [적용 전제 조건 및 체크리스트](#1-적용-전제-조건-및-체크리스트)
2. [아키텍처 상세 설계](#2-아키텍처-상세-설계)
3. [PostgreSQL 데이터베이스 상세 명세](#3-postgresql-데이터베이스-상세-명세)
4. [기존 시스템 데이터 매핑 가이드](#4-기존-시스템-데이터-매핑-가이드)
5. [REST API 상세 명세](#5-rest-api-상세-명세)
6. [AI 파이프라인 상세 명세](#6-ai-파이프라인-상세-명세)
7. [보안 가이드라인](#7-보안-가이드라인)
8. [환경별 설정 (Dev / Staging / Prod)](#8-환경별-설정)
9. [성능 최적화 가이드](#9-성능-최적화-가이드)
10. [배포 가이드](#10-배포-가이드)
11. [운영 가이드 (모니터링 / 장애 대응)](#11-운영-가이드)
12. [로컬 LLM 전환 상세 가이드](#12-로컬-llm-전환-상세-가이드)
13. [트러블슈팅](#13-트러블슈팅)
14. [용어 정의](#14-용어-정의)

---

## 1. 적용 전제 조건 및 체크리스트

### 1.1 필수 인프라 조건

| 항목 | 최소 사양 | 권장 사양 | 비고 |
|---|---|---|---|
| 서버 OS | Ubuntu 22.04 LTS | RHEL 9 / Ubuntu 24.04 | Windows Server 가능 (WSL2) |
| CPU | 4 Core | 8 Core 이상 | 백엔드 기준 |
| RAM | 8 GB | 16 GB 이상 | PostgreSQL + JVM 동시 |
| 디스크 | 50 GB | 200 GB SSD | 로그 포함 |
| JDK | OpenJDK 21 LTS | OpenJDK 21 LTS | 필수 |
| PostgreSQL | 14 이상 | 16 | 14 미만 일부 함수 미지원 |
| Node.js | 18.x LTS | 22.x LTS | 프론트엔드 빌드용 |
| 네트워크 | 내부망 접근 가능 | — | DB → 백엔드 → 프론트 순 |

### 1.2 외부 연결 조건

```
[인터넷 필요 여부]

현재 구성 (Claude API 사용):
  백엔드 서버 → api.anthropic.com:443 (HTTPS 아웃바운드 허용 필요)

로컬 LLM 전환 후:
  외부 인터넷 연결 불필요 → 완전 내부망 운영 가능
```

### 1.3 적용 전 체크리스트

```
[ ] PostgreSQL 서버 접근 가능 여부 확인
[ ] DB 계정 생성 권한 보유 (또는 DBA 협조)
[ ] 백엔드 서버 포트 8080 방화벽 오픈
[ ] 프론트엔드 서버 포트 80/443 방화벽 오픈
[ ] api.anthropic.com 아웃바운드 허용 (Claude API 사용 시)
[ ] ANTHROPIC_API_KEY 발급 완료
[ ] 기존 MES/ERP 시스템 DB 스키마 파악
[ ] 데이터 동기화 주기 결정 (실시간 / 배치)
[ ] 보안 정책 검토 (SQL 가드레일 규칙 사내 기준 적용)
```

---

## 2. 아키텍처 상세 설계

### 2.1 전체 시스템 구성도

```
┌─────────────────────────────────────────────────────────────────┐
│                         사용자 PC / 브라우저                       │
└──────────────────────────────┬──────────────────────────────────┘
                               │ HTTPS (443) or HTTP (80)
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Nginx 리버스 프록시                           │
│         /        → Vue 3 정적 파일 서빙                           │
│         /api/*   → Spring Boot :8080 프록시                       │
└──────────────────────────────┬──────────────────────────────────┘
                               │
              ┌────────────────┴────────────────┐
              │                                 │
              ▼                                 ▼
┌─────────────────────┐            ┌─────────────────────────────┐
│   Vue 3 Static      │            │   Spring Boot 3.2 API        │
│   (Nginx 서빙)       │            │   포트: 8080                  │
│                     │            │                              │
│   - KPI Cards       │            │   DashboardController        │
│   - Charts (4종)    │            │   AiChatService              │
│   - Lot 테이블       │            │   WebConfig (CORS)           │
│   - AI 채팅          │            │                              │
└─────────────────────┘            └──────────┬──────────────────┘
                                              │
                          ┌───────────────────┴──────────────────┐
                          │                                       │
                          ▼                                       ▼
             ┌────────────────────┐              ┌──────────────────────────┐
             │  PostgreSQL DB     │              │  Anthropic Claude API    │
             │  (foundry_db)      │              │  api.anthropic.com       │
             │                   │              │  (또는 내부 LLM 서버)      │
             │  ┌──────────────┐ │              │                          │
             │  │ 대시보드용 DB │ │              │  Text-to-SQL 생성         │
             │  │ (View 또는   │ │              │  결과 자연어 요약           │
             │  │  Read 전용)  │ │              └──────────────────────────┘
             │  └──────────────┘ │
             │                   │
             │  ┌──────────────┐ │
             │  │ 기존 운영 DB  │ │  ← MES / ERP / LIMS 연동
             │  │ (Read Only)  │ │
             │  └──────────────┘ │
             └────────────────────┘
```

### 2.2 데이터 흐름 상세

```
[Option A] 기존 DB 직접 연결 (Read-Only)
  기존 MES DB ─────────────────────────────▶ Spring Boot (JdbcTemplate)
  * 기존 테이블 구조에 맞게 SQL 수정 필요
  * View 생성으로 대시보드용 데이터 정규화 권장

[Option B] 별도 대시보드 DB 구성 (Replica/ETL)
  기존 MES DB ──[ETL 배치]──▶ foundry_db ──▶ Spring Boot
  * 스키마 변경 없이 독립 운영
  * 데이터 최신성: 배치 주기에 따름 (5분~1일)

[Option C] 실시간 CDC 연동 (고급)
  기존 MES DB ──[Debezium]──▶ Kafka ──▶ foundry_db (실시간)
  * 구축 비용 높음, 실시간 데이터 보장
```

**권장**: 초기 도입 시 **Option B (ETL 배치)** → 안정화 후 Option A 또는 C로 전환

---

## 3. PostgreSQL 데이터베이스 상세 명세

### 3.1 데이터베이스 생성 및 사용자 설정

```sql
-- 1. 데이터베이스 생성
CREATE DATABASE foundry_db
    WITH ENCODING = 'UTF8'
    LC_COLLATE = 'en_US.UTF-8'
    LC_CTYPE   = 'en_US.UTF-8'
    TEMPLATE   = template0;

-- 2. 전용 사용자 생성 (최소 권한 원칙)
CREATE USER foundry_app  WITH PASSWORD '변경필수_강력한패스워드';
CREATE USER foundry_ro   WITH PASSWORD '변경필수_읽기전용패스워드';  -- 읽기 전용
CREATE USER foundry_etl  WITH PASSWORD '변경필수_ETL패스워드';       -- ETL 전용

-- 3. 권한 부여
GRANT CONNECT ON DATABASE foundry_db TO foundry_app, foundry_ro, foundry_etl;
GRANT USAGE   ON SCHEMA public        TO foundry_app, foundry_ro, foundry_etl;

-- foundry_app: 대시보드 API용 (SELECT만 허용)
GRANT SELECT ON ALL TABLES IN SCHEMA public TO foundry_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO foundry_app;

-- foundry_ro: 분석/리포팅용 읽기 전용
GRANT SELECT ON ALL TABLES IN SCHEMA public TO foundry_ro;

-- foundry_etl: ETL 배치용 (INSERT/UPDATE 허용, DDL 불가)
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO foundry_etl;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO foundry_etl;
```

### 3.2 테이블 상세 명세 — customers

```sql
-- ============================================================
-- 테이블: customers (고객 마스터)
-- 설명: 파운드리 서비스를 이용하는 고객사 정보
-- 갱신 주기: 수동 (신규 고객사 추가 시)
-- ============================================================
CREATE TABLE customers (
    id          SERIAL          PRIMARY KEY,
    code        VARCHAR(20)     NOT NULL UNIQUE,        -- 내부 고객 코드 (예: CUST-001)
    name        VARCHAR(100)    NOT NULL,               -- 고객사 정식 명칭
    short_name  VARCHAR(50),                            -- 약칭 (차트 표시용)
    country     VARCHAR(50)     NOT NULL,               -- 국가명 (ISO 3166 국가명)
    country_code CHAR(2)        NOT NULL,               -- ISO 3166-1 alpha-2 (KR, TW, US...)
    tier        VARCHAR(20)     NOT NULL
                CHECK (tier IN ('PLATINUM','GOLD','SILVER','BRONZE')),
    credit_limit NUMERIC(15,2)  DEFAULT 0,             -- 신용 한도 (USD)
    is_active   BOOLEAN         NOT NULL DEFAULT TRUE,  -- 활성 고객 여부
    contract_start DATE,                                -- 최초 계약일
    account_manager VARCHAR(100),                       -- 담당 영업 담당자
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- 인덱스
CREATE INDEX idx_customers_tier     ON customers(tier);
CREATE INDEX idx_customers_country  ON customers(country_code);
CREATE INDEX idx_customers_active   ON customers(is_active);

-- 코멘트
COMMENT ON TABLE  customers               IS '고객 마스터 테이블';
COMMENT ON COLUMN customers.code          IS '내부 고객 식별 코드';
COMMENT ON COLUMN customers.tier          IS '고객 등급: PLATINUM>GOLD>SILVER>BRONZE';
COMMENT ON COLUMN customers.credit_limit  IS '최대 외상 한도 (USD 기준)';
```

### 3.3 테이블 상세 명세 — products

```sql
-- ============================================================
-- 테이블: products (제품/공정 마스터)
-- 설명: 파운드리가 제공하는 공정 종류 및 단가
-- 갱신 주기: 분기별 (단가 개정 시)
-- ============================================================
CREATE TABLE products (
    id               SERIAL         PRIMARY KEY,
    code             VARCHAR(30)    NOT NULL UNIQUE,       -- 제품 코드 (예: PROD-5NM-300)
    name             VARCHAR(100)   NOT NULL,              -- 제품명
    category         VARCHAR(50),                          -- 제품 분류 (Logic, Memory, Power, RF...)
    technology_node  VARCHAR(20)    NOT NULL,              -- 공정 노드 (5nm, 7nm, 12nm...)
    wafer_size       SMALLINT       NOT NULL
                     CHECK (wafer_size IN (150, 200, 300)), -- 웨이퍼 직경 (mm)
    unit_price       NUMERIC(12,2)  NOT NULL,              -- 웨이퍼당 단가 (USD)
    cycle_time_days  SMALLINT,                             -- 표준 TAT (일)
    design_rule      VARCHAR(30),                          -- 설계 규칙 버전
    max_layers       SMALLINT,                             -- 최대 금속 레이어 수
    is_active        BOOLEAN        NOT NULL DEFAULT TRUE,
    effective_from   DATE           NOT NULL DEFAULT CURRENT_DATE, -- 단가 적용 시작일
    effective_to     DATE,                                 -- 단가 만료일 (NULL=현재 유효)
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_products_node      ON products(technology_node);
CREATE INDEX idx_products_category  ON products(category);
CREATE INDEX idx_products_active    ON products(is_active);

COMMENT ON TABLE  products               IS '제품(공정) 마스터 테이블';
COMMENT ON COLUMN products.technology_node IS '반도체 공정 노드 (단위: nm)';
COMMENT ON COLUMN products.cycle_time_days IS '표준 생산 소요일 (TAT: Turn-Around Time)';
```

### 3.4 테이블 상세 명세 — sales_orders

```sql
-- ============================================================
-- 테이블: sales_orders (수주 테이블)
-- 설명: 고객으로부터 접수된 생산 주문
-- 갱신 주기: 실시간 (주문 접수/상태 변경 시)
-- ============================================================
CREATE TABLE sales_orders (
    id              SERIAL          PRIMARY KEY,
    order_number    VARCHAR(30)     NOT NULL UNIQUE,   -- 주문번호 (예: SO-2024-001234)
    customer_id     INTEGER         NOT NULL REFERENCES customers(id),
    product_id      INTEGER         NOT NULL REFERENCES products(id),
    quantity        INTEGER         NOT NULL CHECK (quantity > 0),  -- 웨이퍼 수량
    unit_price      NUMERIC(12,2)   NOT NULL CHECK (unit_price > 0),
    discount_rate   NUMERIC(5,4)    DEFAULT 0          -- 할인율 (0.05 = 5%)
                    CHECK (discount_rate >= 0 AND discount_rate < 1),
    total_amount    NUMERIC(14,2)   NOT NULL            -- quantity * unit_price * (1 - discount_rate)
                    GENERATED ALWAYS AS
                    (ROUND(quantity * unit_price * (1 - discount_rate), 2)) STORED,
    order_date      DATE            NOT NULL,
    required_date   DATE,                              -- 고객 요청 납기일
    confirmed_date  DATE,                              -- 확정 납기일
    shipped_date    DATE,                              -- 실제 출하일
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN (
                        'DRAFT',          -- 초안
                        'PENDING',        -- 접수 대기
                        'CONFIRMED',      -- 확정
                        'IN_PRODUCTION',  -- 생산 중
                        'QC_HOLD',        -- 품질 보류
                        'SHIPPED',        -- 출하 완료
                        'DELIVERED',      -- 납품 완료
                        'CANCELLED',      -- 취소
                        'RETURNED'        -- 반품
                    )),
    priority        SMALLINT        DEFAULT 3
                    CHECK (priority BETWEEN 1 AND 5),  -- 1=긴급, 5=일반
    po_number       VARCHAR(50),                       -- 고객 발주번호
    notes           TEXT,
    created_by      VARCHAR(50),                       -- 등록자
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- 인덱스 (대시보드 쿼리 최적화)
CREATE INDEX idx_orders_customer    ON sales_orders(customer_id);
CREATE INDEX idx_orders_product     ON sales_orders(product_id);
CREATE INDEX idx_orders_status      ON sales_orders(status);
CREATE INDEX idx_orders_date        ON sales_orders(order_date DESC);
CREATE INDEX idx_orders_status_date ON sales_orders(status, order_date DESC);
-- 월별 집계 쿼리 최적화용 함수 인덱스
CREATE INDEX idx_orders_month
    ON sales_orders(DATE_TRUNC('month', order_date));

COMMENT ON TABLE  sales_orders             IS '수주(Sales Order) 테이블';
COMMENT ON COLUMN sales_orders.total_amount IS '자동 계산 컬럼: quantity × unit_price × (1 - discount_rate)';
COMMENT ON COLUMN sales_orders.priority     IS '1=긴급(Urgent), 2=높음, 3=보통, 4=낮음, 5=일반';
```

### 3.5 테이블 상세 명세 — production_lots

```sql
-- ============================================================
-- 테이블: production_lots (생산 Lot)
-- 설명: 실제 웨이퍼 생산 배치 단위
-- 갱신 주기: 공정 단계별 실시간
-- ============================================================
CREATE TABLE production_lots (
    id              SERIAL          PRIMARY KEY,
    lot_number      VARCHAR(50)     NOT NULL UNIQUE,    -- Lot 번호 (예: LOT-5NM-2024-0001)
    order_id        INTEGER         REFERENCES sales_orders(id), -- NULL 가능 (자체 재고 생산)
    product_id      INTEGER         NOT NULL REFERENCES products(id),
    quantity        INTEGER         NOT NULL CHECK (quantity > 0),
    planned_qty     INTEGER,                            -- 계획 수량
    start_date      DATE            NOT NULL,
    planned_end_date DATE,                             -- 계획 완료일
    end_date        DATE,                              -- 실제 완료일
    status          VARCHAR(20)     NOT NULL DEFAULT 'QUEUED'
                    CHECK (status IN (
                        'QUEUED',         -- 대기
                        'IN_PROGRESS',    -- 진행 중
                        'ON_HOLD',        -- 보류
                        'COMPLETED',      -- 완료
                        'SCRAPPED',       -- 폐기
                        'REWORKED'        -- 재작업
                    )),
    yield_rate      NUMERIC(5,2)
                    CHECK (yield_rate IS NULL OR (yield_rate >= 0 AND yield_rate <= 100)),
    good_die_count  INTEGER,                           -- 양품 Die 수
    total_die_count INTEGER,                           -- 전체 Die 수
    -- 공정 단계별 통과 여부 (비트마스크 또는 별도 테이블로 확장 가능)
    current_step    VARCHAR(50),                       -- 현재 공정 단계
    fab_line        VARCHAR(20),                       -- 생산 라인 (Line-A, Line-B...)
    engineer_id     VARCHAR(50),                       -- 담당 엔지니어
    equipment_id    VARCHAR(50),                       -- 주 장비 ID
    scrap_reason    TEXT,                              -- 폐기 사유 (SCRAPPED 시)
    notes           TEXT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_lots_product    ON production_lots(product_id);
CREATE INDEX idx_lots_order      ON production_lots(order_id);
CREATE INDEX idx_lots_status     ON production_lots(status);
CREATE INDEX idx_lots_start_date ON production_lots(start_date DESC);
CREATE INDEX idx_lots_fab_line   ON production_lots(fab_line);
-- 수율 집계 최적화
CREATE INDEX idx_lots_yield_status
    ON production_lots(status, yield_rate)
    WHERE status = 'COMPLETED' AND yield_rate IS NOT NULL;

COMMENT ON TABLE  production_lots           IS '웨이퍼 생산 Lot 테이블';
COMMENT ON COLUMN production_lots.yield_rate IS '수율: good_die_count / total_die_count × 100';
COMMENT ON COLUMN production_lots.fab_line   IS '생산 라인 구분자';
```

### 3.6 테이블 상세 명세 — defect_records

```sql
-- ============================================================
-- 테이블: defect_records (불량 기록)
-- 설명: 각 Lot의 공정 단계별 불량 발생 기록
-- 갱신 주기: 검사 완료 시 즉시
-- ============================================================
CREATE TABLE defect_records (
    id              SERIAL          PRIMARY KEY,
    lot_id          INTEGER         NOT NULL REFERENCES production_lots(id),
    inspection_id   VARCHAR(50),                       -- 검사 레코드 ID (연동 시스템 키)
    defect_type     VARCHAR(100)    NOT NULL,           -- 불량 유형 (Particle, CD Variation...)
    defect_code     VARCHAR(20),                       -- 내부 불량 코드
    process_step    VARCHAR(100)    NOT NULL,           -- 발생 공정 단계
    equipment_id    VARCHAR(50),                       -- 발생 장비 ID
    count           INTEGER         NOT NULL DEFAULT 0 CHECK (count >= 0),
    density         NUMERIC(10,4),                     -- 불량 밀도 (개/cm²)
    severity        VARCHAR(20)     NOT NULL
                    CHECK (severity IN ('CRITICAL','MAJOR','MINOR','OBSERVATION')),
    inspection_method VARCHAR(50),                     -- 검사 방법 (AOI, SEM, Visual...)
    action_taken    TEXT,                              -- 조치 내용
    root_cause      TEXT,                              -- 근본 원인 분석
    is_recurring    BOOLEAN         DEFAULT FALSE,     -- 재발 불량 여부
    detected_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    reported_by     VARCHAR(50),                       -- 보고자
    closed_at       TIMESTAMPTZ,                       -- 불량 종결일
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_defects_lot         ON defect_records(lot_id);
CREATE INDEX idx_defects_type        ON defect_records(defect_type);
CREATE INDEX idx_defects_severity    ON defect_records(severity);
CREATE INDEX idx_defects_step        ON defect_records(process_step);
CREATE INDEX idx_defects_detected    ON defect_records(detected_at DESC);
CREATE INDEX idx_defects_equipment   ON defect_records(equipment_id)
    WHERE equipment_id IS NOT NULL;
-- 월별 불량 집계 최적화
CREATE INDEX idx_defects_month
    ON defect_records(DATE_TRUNC('month', detected_at), severity);

COMMENT ON TABLE  defect_records          IS '공정 불량 기록 테이블';
COMMENT ON COLUMN defect_records.density  IS '단위 면적당 불량 밀도 (개/cm²)';
COMMENT ON COLUMN defect_records.severity IS 'CRITICAL: 즉시 중단, MAJOR: 당일 조치, MINOR: 모니터링, OBSERVATION: 관찰';
```

### 3.7 대시보드용 View 정의

```sql
-- ============================================================
-- View: 기존 DB 직접 연동 시 데이터 정규화 레이어
-- 기존 테이블 구조가 다를 경우 이 View를 수정하여 대응
-- ============================================================

-- 월별 매출 집계 View
CREATE OR REPLACE VIEW v_revenue_monthly AS
SELECT
    DATE_TRUNC('month', order_date)::DATE AS month,
    TO_CHAR(order_date, 'YYYY-MM')        AS month_label,
    COUNT(*)                               AS order_count,
    SUM(total_amount)                      AS revenue,
    SUM(quantity)                          AS total_wafers
FROM sales_orders
WHERE status NOT IN ('CANCELLED', 'RETURNED', 'DRAFT')
GROUP BY DATE_TRUNC('month', order_date), TO_CHAR(order_date, 'YYYY-MM')
ORDER BY month;

-- 고객별 매출 집계 View
CREATE OR REPLACE VIEW v_revenue_by_customer AS
SELECT
    c.id,
    c.name,
    c.short_name,
    c.tier,
    c.country,
    c.country_code,
    COUNT(so.id)            AS order_count,
    SUM(so.quantity)        AS total_wafers,
    SUM(so.total_amount)    AS total_revenue,
    AVG(so.total_amount)    AS avg_order_value
FROM customers c
LEFT JOIN sales_orders so
    ON so.customer_id = c.id
    AND so.status NOT IN ('CANCELLED', 'RETURNED', 'DRAFT')
WHERE c.is_active = TRUE
GROUP BY c.id, c.name, c.short_name, c.tier, c.country, c.country_code
ORDER BY total_revenue DESC NULLS LAST;

-- 제품별 수율 집계 View
CREATE OR REPLACE VIEW v_yield_by_product AS
SELECT
    p.id,
    p.name,
    p.code,
    p.technology_node,
    p.wafer_size,
    p.category,
    COUNT(pl.id)             AS lot_count,
    AVG(pl.yield_rate)       AS avg_yield,
    MIN(pl.yield_rate)       AS min_yield,
    MAX(pl.yield_rate)       AS max_yield,
    STDDEV(pl.yield_rate)    AS std_yield,
    SUM(pl.quantity)         AS total_wafers
FROM products p
JOIN production_lots pl ON pl.product_id = p.id
WHERE pl.status = 'COMPLETED'
  AND pl.yield_rate IS NOT NULL
GROUP BY p.id, p.name, p.code, p.technology_node, p.wafer_size, p.category
ORDER BY avg_yield DESC;

-- 활성 Lot 현황 View
CREATE OR REPLACE VIEW v_active_lots AS
SELECT
    pl.id,
    pl.lot_number,
    p.name           AS product_name,
    p.technology_node,
    c.name           AS customer_name,
    pl.quantity,
    pl.status,
    pl.fab_line,
    pl.current_step,
    pl.start_date,
    pl.planned_end_date,
    CURRENT_DATE - pl.start_date AS elapsed_days,
    pl.planned_end_date - CURRENT_DATE AS remaining_days
FROM production_lots pl
JOIN products p       ON pl.product_id = p.id
LEFT JOIN sales_orders so ON pl.order_id = so.id
LEFT JOIN customers c     ON so.customer_id = c.id
WHERE pl.status IN ('QUEUED', 'IN_PROGRESS', 'ON_HOLD')
ORDER BY pl.start_date;

-- 불량 현황 요약 View
CREATE OR REPLACE VIEW v_defect_summary AS
SELECT
    dr.defect_type,
    dr.process_step,
    dr.severity,
    TO_CHAR(dr.detected_at, 'YYYY-MM') AS month,
    COUNT(*)                             AS occurrence_count,
    SUM(dr.count)                        AS total_defects,
    AVG(dr.density)                      AS avg_density
FROM defect_records dr
GROUP BY dr.defect_type, dr.process_step, dr.severity,
         TO_CHAR(dr.detected_at, 'YYYY-MM')
ORDER BY month DESC, total_defects DESC;

-- KPI 집계 View (API 최적화)
CREATE OR REPLACE VIEW v_kpi_summary AS
SELECT
    (SELECT COALESCE(SUM(total_amount), 0)
     FROM sales_orders
     WHERE status NOT IN ('CANCELLED','RETURNED','DRAFT'))       AS total_revenue,
    (SELECT COUNT(*)
     FROM sales_orders
     WHERE status IN ('PENDING','CONFIRMED','IN_PRODUCTION','QC_HOLD','SHIPPED'))
                                                                  AS active_orders,
    (SELECT COALESCE(ROUND(AVG(yield_rate)::NUMERIC, 2), 0)
     FROM production_lots
     WHERE status = 'COMPLETED'
       AND yield_rate IS NOT NULL)                                AS avg_yield_rate,
    (SELECT COUNT(*)
     FROM production_lots
     WHERE status IN ('QUEUED','IN_PROGRESS','ON_HOLD'))         AS active_lots,
    (SELECT COUNT(*)
     FROM defect_records
     WHERE severity = 'CRITICAL'
       AND detected_at >= CURRENT_DATE - INTERVAL '30 days')     AS critical_defects_30d;
```

### 3.8 자동 updated_at 트리거

```sql
-- updated_at 자동 갱신 함수
CREATE OR REPLACE FUNCTION trigger_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 각 테이블에 트리거 적용
CREATE TRIGGER trg_customers_updated_at
    BEFORE UPDATE ON customers
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

CREATE TRIGGER trg_products_updated_at
    BEFORE UPDATE ON products
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

CREATE TRIGGER trg_sales_orders_updated_at
    BEFORE UPDATE ON sales_orders
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

CREATE TRIGGER trg_production_lots_updated_at
    BEFORE UPDATE ON production_lots
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();
```

### 3.9 파티셔닝 전략 (데이터 증가 대비)

```sql
-- sales_orders, defect_records는 연간 수십만 건 예상 시 파티셔닝 권장
-- 적용 기준: 단일 테이블 1,000만 건 초과 또는 쿼리 응답 3초 초과 시

-- 예시: sales_orders 연도별 파티셔닝
CREATE TABLE sales_orders_partitioned (
    LIKE sales_orders INCLUDING ALL
) PARTITION BY RANGE (order_date);

CREATE TABLE sales_orders_2024
    PARTITION OF sales_orders_partitioned
    FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');

CREATE TABLE sales_orders_2025
    PARTITION OF sales_orders_partitioned
    FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
-- 매년 파티션 추가 필요 (cron 자동화 권장)
```

---

## 4. 기존 시스템 데이터 매핑 가이드

### 4.1 MES (Manufacturing Execution System) 매핑

기존 MES 시스템의 테이블 명칭과 필드가 다를 경우 아래 View를 활용하여 대시보드 스키마로 변환합니다.

```sql
-- 예시: 기존 MES의 WIP(Work In Progress) 테이블 → production_lots View
-- 기존 테이블: mes_wip (lot_id, proc_name, wfr_cnt, start_dt, end_dt, status_cd, yield_pct)

CREATE OR REPLACE VIEW production_lots AS  -- 기존 테이블 대신 View로 제공
SELECT
    mes.lot_id::INTEGER               AS id,
    mes.lot_num                        AS lot_number,
    NULL::INTEGER                      AS order_id,
    pm.product_id                      AS product_id,   -- 제품 코드 매핑 필요
    mes.wfr_cnt                        AS quantity,
    mes.wfr_cnt                        AS planned_qty,
    mes.start_dt::DATE                 AS start_date,
    mes.plan_end_dt::DATE              AS planned_end_date,
    mes.actual_end_dt::DATE            AS end_date,
    CASE mes.status_cd
        WHEN 'W' THEN 'QUEUED'
        WHEN 'R' THEN 'IN_PROGRESS'
        WHEN 'H' THEN 'ON_HOLD'
        WHEN 'C' THEN 'COMPLETED'
        WHEN 'S' THEN 'SCRAPPED'
        ELSE 'QUEUED'
    END                                AS status,
    mes.yield_pct                      AS yield_rate,
    mes.line_id                        AS fab_line,
    mes.cur_step                       AS current_step,
    NOW()                              AS created_at,
    NOW()                              AS updated_at
FROM mes_wip mes
LEFT JOIN product_mapping pm ON mes.prod_code = pm.mes_prod_code;
-- ※ product_mapping: MES 제품코드 ↔ foundry_db 제품ID 변환 테이블
```

### 4.2 ERP 매핑

```sql
-- 예시: SAP SD 모듈 수주 데이터 → sales_orders View
-- SAP 테이블: VBAK (주문 헤더), VBAP (주문 라인), KNA1 (고객 마스터)

CREATE OR REPLACE VIEW sales_orders AS
SELECT
    (vbak.vbeln || vbap.posnr)::BIGINT  AS id,
    vbak.vbeln                           AS order_number,
    cm.customer_id                       AS customer_id,  -- SAP 고객코드 매핑
    pm.product_id                        AS product_id,
    vbap.kwmeng::INTEGER                 AS quantity,
    vbap.netpr                           AS unit_price,
    0::NUMERIC                           AS discount_rate,
    vbap.kwmeng * vbap.netpr            AS total_amount,
    vbak.audat::DATE                     AS order_date,
    vbak.vdatu::DATE                     AS required_date,
    NULL::DATE                           AS confirmed_date,
    NULL::DATE                           AS shipped_date,
    CASE vbak.gbstk
        WHEN 'A' THEN 'PENDING'
        WHEN 'B' THEN 'IN_PRODUCTION'
        WHEN 'C' THEN 'DELIVERED'
        ELSE 'PENDING'
    END                                  AS status,
    3                                    AS priority,
    vbak.bstnk                          AS po_number,
    NULL::TEXT                           AS notes,
    vbak.ernam                          AS created_by,
    vbak.erdat::TIMESTAMPTZ             AS created_at,
    NOW()                               AS updated_at
FROM vbak
JOIN vbap ON vbak.vbeln = vbap.vbeln
LEFT JOIN customer_mapping cm ON vbak.kunnr = cm.sap_kunnr
LEFT JOIN product_mapping pm  ON vbap.matnr = pm.sap_matnr
WHERE vbak.auart IN ('ZOR', 'ZQT');  -- 수주/견적 유형 필터
```

### 4.3 ETL 배치 스크립트 구조 (Option B 선택 시)

```sql
-- ETL 실행 로그 테이블
CREATE TABLE etl_run_log (
    id           SERIAL PRIMARY KEY,
    table_name   VARCHAR(50)  NOT NULL,
    started_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    finished_at  TIMESTAMPTZ,
    rows_inserted INTEGER DEFAULT 0,
    rows_updated  INTEGER DEFAULT 0,
    status        VARCHAR(20)  DEFAULT 'RUNNING'
                  CHECK (status IN ('RUNNING','SUCCESS','FAILED')),
    error_msg    TEXT
);

-- ETL 프로시저 예시 (production_lots 증분 적재)
CREATE OR REPLACE PROCEDURE etl_sync_production_lots(p_since TIMESTAMPTZ)
LANGUAGE plpgsql AS $$
DECLARE
    v_inserted INTEGER := 0;
    v_updated  INTEGER := 0;
    v_log_id   INTEGER;
BEGIN
    INSERT INTO etl_run_log(table_name) VALUES('production_lots')
    RETURNING id INTO v_log_id;

    -- Upsert (기존 레코드 있으면 UPDATE, 없으면 INSERT)
    WITH src AS (
        SELECT * FROM dblink(
            'host=mes-db port=5432 dbname=mesdb user=etl_user',
            'SELECT lot_id, lot_num, prod_code, wfr_cnt, start_dt,
                    actual_end_dt, status_cd, yield_pct, updated_at
             FROM mes_wip WHERE updated_at >= ''' || p_since || ''''
        ) AS t(lot_id INT, lot_num VARCHAR, prod_code VARCHAR,
               wfr_cnt INT, start_dt DATE, actual_end_dt DATE,
               status_cd CHAR, yield_pct NUMERIC, updated_at TIMESTAMPTZ)
    )
    INSERT INTO production_lots
        (lot_number, product_id, quantity, start_date, end_date, status, yield_rate)
    SELECT
        src.lot_num,
        pm.product_id,
        src.wfr_cnt,
        src.start_dt,
        src.actual_end_dt,
        CASE src.status_cd WHEN 'C' THEN 'COMPLETED' ELSE 'IN_PROGRESS' END,
        src.yield_pct
    FROM src
    JOIN product_mapping pm ON src.prod_code = pm.mes_prod_code
    ON CONFLICT (lot_number) DO UPDATE SET
        status     = EXCLUDED.status,
        yield_rate = EXCLUDED.yield_rate,
        end_date   = EXCLUDED.end_date,
        updated_at = NOW();

    GET DIAGNOSTICS v_inserted = ROW_COUNT;

    UPDATE etl_run_log SET
        finished_at  = NOW(),
        rows_inserted = v_inserted,
        status = 'SUCCESS'
    WHERE id = v_log_id;

EXCEPTION WHEN OTHERS THEN
    UPDATE etl_run_log SET
        finished_at = NOW(),
        status = 'FAILED',
        error_msg = SQLERRM
    WHERE id = v_log_id;
    RAISE;
END;
$$;
```

---

## 5. REST API 상세 명세

### 5.1 공통 응답 형식 (권장 확장)

```json
// 현재 구현 (단순)
[{ "name": "TSMC", "total_revenue": 7360000 }]

// 운영 환경 권장 (래핑 구조 추가)
{
  "success": true,
  "data": [...],
  "meta": {
    "total": 8,
    "generated_at": "2026-07-07T14:30:00Z",
    "query_time_ms": 12
  }
}
```

### 5.2 엔드포인트 상세

#### GET /api/kpis

```
설명: 대시보드 핵심 KPI 4개 반환
캐시: 30초 권장 (운영 환경)
권한: 모든 인증된 사용자

응답 예시:
{
  "totalRevenue":  22403500.00,   // 취소/반품 제외 총 매출 (USD)
  "activeOrders":  11,            // PENDING~SHIPPED 상태 주문 수
  "avgYieldRate":  96.39,         // COMPLETED Lot 평균 수율 (%)
  "activeLots":    6,             // QUEUED~ON_HOLD 상태 Lot 수
  "criticalDefects30d": 2         // 최근 30일 CRITICAL 불량 수 (확장 시)
}

사용 View: v_kpi_summary
```

#### GET /api/revenue-by-customer

```
설명: 고객별 매출 합계 (취소/반품 제외)
정렬: total_revenue DESC
권한: 영업/경영진

응답 배열 항목:
{
  "name":          "TSMC",
  "tier":          "PLATINUM",
  "total_revenue": 7360000.00,
  "order_count":   3
}

-- 내부 SQL (현재 구현)
SELECT c.name, c.tier,
       SUM(so.total_amount) AS total_revenue,
       COUNT(so.id) AS order_count
FROM sales_orders so
JOIN customers c ON so.customer_id = c.id
WHERE so.status NOT IN ('CANCELLED','RETURNED','DRAFT')
GROUP BY c.id, c.name, c.tier
ORDER BY total_revenue DESC
```

#### GET /api/revenue-trend

```
설명: 월별 매출 추이
기간: 전체 데이터 (필요 시 ?months=12 파라미터 추가 권장)

응답 배열 항목:
{
  "month":   "2024-01",
  "revenue": 7950000.00
}
```

#### GET /api/yield-by-product

```
설명: 제품별 평균 수율 (COMPLETED Lot 기준)

응답 배열 항목:
{
  "name":            "Power IC 180nm",
  "technology_node": "180nm",
  "avg_yield":       99.10,
  "lot_count":       3
}
```

#### GET /api/defects-by-type

```
설명: 불량 유형 + 심각도별 총 발생 수

응답 배열 항목:
{
  "defect_type":  "Particle Contamination",
  "severity":     "MAJOR",
  "total_count":  12
}
```

#### GET /api/production-lots

```
설명: 최근 생산 Lot 20건 (최신 순)

응답 배열 항목:
{
  "lot_number": "LOT-5NM-001",
  "product":    "Logic 5nm",
  "quantity":   500,
  "status":     "COMPLETED",
  "start_date": "2024-01-06",
  "end_date":   "2024-01-26",
  "yield_rate": 94.20
}
```

#### GET /api/orders

```
설명: 최근 주문 20건 (주문일 최신 순)

응답 배열 항목:
{
  "id":           1,
  "customer":     "Samsung Semiconductor",
  "product":      "Logic 5nm",
  "quantity":     500,
  "total_amount": 2250000.00,
  "order_date":   "2024-01-05",
  "status":       "DELIVERED"
}
```

#### POST /api/chat

```
설명: AI 자연어 질의 → SQL 실행 → 한국어 요약 응답

요청:
{
  "question": "수익이 가장 높은 고객 Top 3는?"
}

응답:
{
  "answer": "TSMC가 $736만으로 1위..."
}

처리 시간: 평균 3~8초 (Claude API 응답 시간 포함)
오류 응답: { "answer": "이 쿼리는 실행할 수 없습니다: DDL 문은 허용되지 않습니다." }
```

---

## 6. AI 파이프라인 상세 명세

### 6.1 프롬프트 설계

```
[SQL 생성 프롬프트]
Database schema (semiconductor foundry):
- customers(id, name, country, tier)
- products(id, name, technology_node, wafer_size, unit_price)
- sales_orders(id, customer_id, product_id, quantity, unit_price, total_amount, order_date, status)
- production_lots(id, product_id, lot_number, quantity, start_date, end_date, status, yield_rate)
- defect_records(id, lot_id, defect_type, process_step, count, severity, detected_at)

※ 실제 운영 시: 기존 테이블 구조로 스키마 컨텍스트 교체 필요

[요약 프롬프트]
질문: {userQuestion}
SQL: {generatedSql}
결과 (최대 10건): {queryResults}
위 결과를 한국어로 간결하게 요약해주세요.
```

### 6.2 가드레일 규칙 상세

| 규칙 번호 | 규칙명 | 차단 패턴 | 오류 메시지 |
|---|---|---|---|
| GR-01 | DDL 차단 | `CREATE\|DROP\|ALTER\|TRUNCATE\|RENAME` | DDL 문은 허용되지 않습니다 |
| GR-02 | DML 차단 | `INSERT\|UPDATE\|DELETE\|MERGE\|UPSERT\|REPLACE` | DML 문은 허용되지 않습니다 |
| GR-03 | 권한 명령 차단 | `GRANT\|REVOKE\|EXECUTE\|EXEC\|CALL\|COPY\|LOAD` | 위험한 명령은 허용되지 않습니다 |
| GR-04 | SELECT 강제 | 문장이 SELECT로 시작하지 않으면 거부 | SELECT 쿼리만 허용됩니다 |
| GR-05 | LIMIT 자동 적용 | LIMIT 없으면 100 추가, LIMIT > 100이면 100으로 교체 | (자동 처리, 오류 없음) |
| GR-06 | 복수 문장 차단 | 세미콜론 이후 내용 존재 시 차단 | 복수 문장 쿼리는 허용되지 않습니다 |

### 6.3 AI 모델 선택 기준

| 모델 | 토큰 비용 | SQL 정확도 | 응답 속도 | 권장 용도 |
|---|---|---|---|---|
| claude-haiku-4-5 | 최저 | 보통 | 가장 빠름 | 단순 집계 질문 |
| claude-sonnet-4-6 | 중간 | 우수 | 보통 | **현재 적용 (권장)** |
| claude-opus-4-8 | 최고 | 최상 | 느림 | 복잡한 분석 질문 |

---

## 7. 보안 가이드라인

### 7.1 DB 계정 권한 분리

```
foundry_app  → SELECT만 허용 (대시보드 API)
foundry_ro   → SELECT만 허용 (분석/BI 툴)
foundry_etl  → SELECT/INSERT/UPDATE/DELETE (ETL 배치)
DBA 계정     → 별도 관리, 애플리케이션 미사용
```

### 7.2 네트워크 보안

```
[방화벽 규칙]
PostgreSQL (5432):
  - 허용: 백엔드 서버 IP만
  - 차단: 외부 인터넷, 개발자 PC 직접 접속

백엔드 API (8080):
  - 허용: Nginx 프록시 서버 IP만
  - 차단: 외부 직접 접근

아웃바운드:
  - api.anthropic.com:443 허용 (Claude API 사용 시)
  - 로컬 LLM 전환 후 차단 가능
```

### 7.3 API Key 관리

```bash
# 운영 환경: 환경변수로만 관리 (파일 저장 금지)
# systemd 서비스 파일에 주입
[Service]
Environment="ANTHROPIC_API_KEY=sk-ant-..."

# 또는 HashiCorp Vault / AWS Secrets Manager 사용 권장
# Vault 예시:
vault kv put secret/foundry-dashboard anthropic_api_key="sk-ant-..."

# 코드에서 읽기:
vault kv get -field=anthropic_api_key secret/foundry-dashboard
```

### 7.4 SQL 인젝션 방지

```java
// ✅ 올바른 방법: 파라미터화 쿼리 (현재 구현)
jdbc.queryForList("SELECT * FROM customers WHERE tier = ?", tier);

// ❌ 잘못된 방법: 문자열 직접 조합
jdbc.queryForList("SELECT * FROM customers WHERE tier = '" + tier + "'");
```

---

## 8. 환경별 설정

### 8.1 application-dev.yml

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/foundry_db
    username: foundry_app
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 5
      minimum-idle: 2

logging:
  level:
    com.foundry: DEBUG
    org.springframework.jdbc: DEBUG  # SQL 로깅 활성화
```

### 8.2 application-staging.yml

```yaml
spring:
  datasource:
    url: jdbc:postgresql://staging-db.internal:5432/foundry_db
    username: foundry_app
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 10
      minimum-idle: 3
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000

server:
  port: 8080

logging:
  level:
    com.foundry: INFO
```

### 8.3 application-prod.yml

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST}:${DB_PORT}/foundry_db
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
      validation-timeout: 5000

server:
  port: 8080
  compression:
    enabled: true

management:
  endpoints:
    web:
      exposure:
        include: health,metrics,info
  endpoint:
    health:
      show-details: when-authorized

anthropic:
  api-key: ${ANTHROPIC_API_KEY}

logging:
  level:
    root: WARN
    com.foundry: INFO
  file:
    name: /var/log/foundry-dashboard/app.log
  logback:
    rollingpolicy:
      max-file-size: 100MB
      max-history: 30
```

### 8.4 Nginx 설정 (운영 환경)

```nginx
# /etc/nginx/conf.d/foundry-dashboard.conf

upstream foundry_backend {
    server 127.0.0.1:8080;
    keepalive 32;
}

server {
    listen 80;
    server_name dashboard.foundry.internal;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name dashboard.foundry.internal;

    ssl_certificate     /etc/ssl/foundry/cert.pem;
    ssl_certificate_key /etc/ssl/foundry/key.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         HIGH:!aNULL:!MD5;

    # Vue 3 정적 파일
    root /var/www/foundry-dashboard/dist;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;  # SPA fallback
        expires 1h;
        add_header Cache-Control "public, no-transform";
    }

    # API 프록시
    location /api/ {
        proxy_pass         http://foundry_backend;
        proxy_http_version 1.1;
        proxy_set_header   Connection "";
        proxy_set_header   Host $host;
        proxy_set_header   X-Real-IP $remote_addr;
        proxy_read_timeout 60s;  # AI 응답 시간 고려
    }

    # AI 채팅은 타임아웃 더 길게
    location /api/chat {
        proxy_pass         http://foundry_backend;
        proxy_read_timeout 120s;
        proxy_set_header   Host $host;
    }
}
```

---

## 9. 성능 최적화 가이드

### 9.1 쿼리 성능 기준

| 엔드포인트 | 목표 응답 시간 | 최대 허용 |
|---|---|---|
| /api/kpis | < 100ms | 500ms |
| /api/revenue-* | < 200ms | 1,000ms |
| /api/production-lots | < 150ms | 500ms |
| /api/chat | < 10s | 30s |

### 9.2 인덱스 효과 검증

```sql
-- 쿼리 실행 계획 확인 (Index Scan 사용 여부)
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT c.name, SUM(so.total_amount)
FROM sales_orders so
JOIN customers c ON so.customer_id = c.id
WHERE so.status != 'CANCELLED'
GROUP BY c.id, c.name
ORDER BY 2 DESC;

-- 인덱스 사용 통계 확인
SELECT schemaname, tablename, indexname,
       idx_scan, idx_tup_read, idx_tup_fetch
FROM pg_stat_user_indexes
ORDER BY idx_scan DESC;
```

### 9.3 커넥션 풀 설정 계산식

```
최적 pool_size = (코어 수 × 2) + 유효 스핀들 수

예시 (4코어 서버):
  maximum-pool-size = (4 × 2) + 1 = 9  → 10으로 설정
  minimum-idle = maximum-pool-size / 2  = 5
```

### 9.4 PostgreSQL 서버 파라미터

```sql
-- postgresql.conf 권장 설정 (메모리 16GB 서버 기준)
shared_buffers       = 4GB           -- 전체 RAM의 25%
effective_cache_size = 12GB          -- 전체 RAM의 75%
work_mem             = 256MB         -- 정렬/해시 작업 메모리
maintenance_work_mem = 1GB           -- VACUUM, CREATE INDEX
max_connections      = 100
wal_compression      = on
autovacuum           = on
log_slow_statements  = on
log_min_duration_statement = 1000    -- 1초 초과 쿼리 로깅
```

---

## 10. 배포 가이드

### 10.1 백엔드 배포 (systemd)

```ini
# /etc/systemd/system/foundry-dashboard.service

[Unit]
Description=Foundry Dashboard Spring Boot API
After=network.target postgresql.service

[Service]
Type=simple
User=foundry
Group=foundry
WorkingDirectory=/opt/foundry-dashboard

Environment="JAVA_OPTS=-Xms512m -Xmx2g -XX:+UseG1GC"
Environment="SPRING_PROFILES_ACTIVE=prod"
Environment="DB_HOST=db.foundry.internal"
Environment="DB_PORT=5432"
Environment="DB_USERNAME=foundry_app"
Environment="DB_PASSWORD=변경필수"
Environment="ANTHROPIC_API_KEY=sk-ant-..."

ExecStart=/opt/homebrew/opt/openjdk@21/bin/java \
    $JAVA_OPTS \
    -jar /opt/foundry-dashboard/dashboard.jar

ExecStop=/bin/kill -SIGTERM $MAINPID
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal
SyslogIdentifier=foundry-dashboard

[Install]
WantedBy=multi-user.target
```

```bash
# 배포 명령
sudo systemctl daemon-reload
sudo systemctl enable foundry-dashboard
sudo systemctl start  foundry-dashboard
sudo systemctl status foundry-dashboard
```

### 10.2 프론트엔드 배포

```bash
# 1. 빌드
cd frontend
npm run build
# dist/ 폴더 생성됨

# 2. Nginx 디렉토리로 복사
sudo cp -r dist/* /var/www/foundry-dashboard/dist/

# 3. Nginx 재시작
sudo nginx -t && sudo systemctl reload nginx
```

### 10.3 DB 마이그레이션 절차

```bash
# 신규 컬럼/테이블 추가 시 순서
1. Staging DB에 먼저 적용 및 검증
2. 백엔드 신규 버전 Staging 배포 → 테스트
3. Production DB 마이그레이션 (트랜잭션 내)
4. Production 백엔드 배포
5. 롤백 DDL 준비 (ALTER TABLE DROP COLUMN 등)
```

---

## 11. 운영 가이드

### 11.1 헬스체크

```bash
# API 서버 상태
curl http://localhost:8080/actuator/health

# DB 연결 확인
psql -U foundry_app -d foundry_db -c "SELECT 1;"

# 대시보드 KPI API
curl http://localhost:8080/api/kpis

# 로그 실시간 확인
journalctl -u foundry-dashboard -f
tail -f /var/log/foundry-dashboard/app.log
```

### 11.2 모니터링 지표

```
[필수 모니터링 항목]

서버 리소스:
  - CPU 사용률 (임계값: 80% 초과 5분 지속 시 알림)
  - 메모리 사용률 (임계값: 85%)
  - 디스크 사용률 (임계값: 80%)

API 성능:
  - /api/kpis 응답 시간 (임계값: 500ms)
  - /api/chat 응답 시간 (임계값: 30s)
  - 5xx 에러율 (임계값: 1%)

DB 상태:
  - 커넥션 풀 사용률 (임계값: 90%)
  - 슬로우 쿼리 발생 수
  - Autovacuum 실행 여부

외부 API:
  - Anthropic API 응답 시간
  - API 할당량 사용률
```

### 11.3 장애 대응 매뉴얼

```
[시나리오 1] /api/chat 응답 없음
원인: Claude API 타임아웃 또는 API 키 문제
조치:
  1. curl -s https://api.anthropic.com/v1/models -H "x-api-key: $ANTHROPIC_API_KEY"
  2. API 키 유효성 및 크레딧 잔액 확인
  3. AiChatService의 maxTokens 값 축소 (512 → 256)
  4. 로컬 LLM 서버로 임시 전환

[시나리오 2] DB 커넥션 고갈
원인: HikariCP 풀 소진
조치:
  1. SELECT count(*), state FROM pg_stat_activity GROUP BY state;
  2. 장시간 idle 커넥션 강제 종료:
     SELECT pg_terminate_backend(pid) FROM pg_stat_activity
     WHERE state = 'idle' AND query_start < NOW() - INTERVAL '10 minutes';
  3. maximum-pool-size 증가 후 재시작

[시나리오 3] 특정 API 응답 지연
원인: 슬로우 쿼리
조치:
  1. SELECT query, mean_exec_time FROM pg_stat_statements ORDER BY mean_exec_time DESC LIMIT 10;
  2. 해당 쿼리 EXPLAIN ANALYZE 실행
  3. 누락 인덱스 추가: CREATE INDEX CONCURRENTLY ...
```

---

## 12. 로컬 LLM 전환 상세 가이드

### 12.1 전환 아키텍처

```
[현재]
AiChatService.java → Anthropic Java SDK → api.anthropic.com

[전환 후]
AiChatService.java → OpenAI 호환 HTTP → 내부 vLLM 서버
```

### 12.2 코드 변경 사항 (최소화 설계)

```yaml
# application.yml 추가
local-llm:
  enabled: true                              # false면 Claude API 사용
  base-url: http://gpu-server.internal:8000  # vLLM 서버 주소
  model: qwen2.5-coder-14b                  # 모델명
  api-key: EMPTY                             # vLLM은 불필요
```

```java
// AiChatService.java — 로컬 LLM 분기 추가 (변경 최소화)
@Value("${local-llm.enabled:false}")
private boolean localLlmEnabled;

@Value("${local-llm.base-url:}")
private String localLlmUrl;

private String callLlm(String prompt) {
    if (localLlmEnabled) {
        return callOpenAiCompatible(prompt);  // vLLM/Ollama
    } else {
        return callClaude(prompt);            // 현재 구현
    }
}

private String callOpenAiCompatible(String prompt) {
    // OpenAI 호환 API 호출 (spring-web RestClient)
    Map<String, Object> body = Map.of(
        "model", localLlmModel,
        "messages", List.of(Map.of("role","user","content", prompt)),
        "max_tokens", 512
    );
    Map response = RestClient.create()
        .post().uri(localLlmUrl + "/v1/chat/completions")
        .body(body).retrieve().body(Map.class);
    // 응답 파싱
    return ((Map)((Map)((List)response.get("choices")).get(0))
        .get("message")).get("content").toString().trim();
}
```

### 12.3 vLLM 서버 구성 (GPU 서버)

```bash
# GPU 서버에서 실행
pip install vllm

python -m vllm.entrypoints.openai.api_server \
    --model Qwen/Qwen2.5-Coder-14B-Instruct \
    --host 0.0.0.0 \
    --port 8000 \
    --max-model-len 4096 \
    --gpu-memory-utilization 0.85 \
    --dtype float16

# 헬스체크
curl http://gpu-server.internal:8000/v1/models
```

### 12.4 모델 성능 비교 (SQL 생성 정확도)

| 모델 | 크기 | GPU | SQL 정확도 | 응답속도 | 한국어 |
|---|---|---|---|---|---|
| Qwen2.5-Coder-7B | 7B | RTX 4090 | ★★★★☆ | 빠름 | 양호 |
| Qwen2.5-Coder-14B | 14B | A100 40G | ★★★★★ | 보통 | 우수 |
| Llama-3.1-8B | 8B | RTX 4090 | ★★★☆☆ | 빠름 | 보통 |
| Llama-3.3-70B | 70B | A100×2 | ★★★★★ | 느림 | 우수 |

---

## 13. 트러블슈팅

### 13.1 자주 발생하는 문제

```
[문제] Backend 시작 시 "Unable to acquire JDBC Connection"
원인: PostgreSQL 미실행 또는 접속 정보 오류
해결:
  pg_isready -h localhost -p 5432
  psql -U foundry_app -d foundry_db -c "SELECT 1;"
  application.yml의 datasource 설정 재확인

[문제] "ClassNotFoundException: org.postgresql.Driver"
원인: PostgreSQL JDBC 드라이버 미포함
해결: pom.xml에 postgresql dependency scope 확인 (runtime)

[문제] AI 채팅 "SELECT 쿼리만 허용됩니다" 반복
원인: Claude가 SQL을 ```sql ... ``` 마크다운으로 감쌈
해결: generateSql()에서 코드블록 제거 로직 확인
  raw.replaceAll("(?s)^```[a-zA-Z]*\\s*", "").replaceAll("(?s)```\\s*$", "")

[문제] CORS 에러 (브라우저에서 API 호출 실패)
원인: WebConfig.java의 allowedOrigins 불일치
해결:
  - 개발: "http://localhost:5173" 확인
  - 운영: 실제 도메인으로 변경
  - Nginx 프록시 사용 시 CORS 설정 불필요

[문제] 차트 데이터 없음 (빈 화면)
원인: API 응답 빈 배열 또는 필드명 불일치
해결:
  1. curl http://localhost:8080/api/revenue-trend 직접 확인
  2. Vue computed에서 필드명 매핑 확인 (month, revenue)
  3. DB 데이터 존재 여부 확인

[문제] vLLM 전환 후 한국어 응답 품질 저하
원인: 모델 한국어 지원 부족
해결:
  - Qwen2.5 시리즈로 교체 (한국어 강점)
  - 프롬프트에 "반드시 한국어로 답변하시오" 명시
  - 요약 프롬프트 강화
```

---

## 14. 용어 정의

| 용어 | 정의 |
|---|---|
| TAT (Turn-Around Time) | 주문 접수부터 출하까지 소요 기간 |
| Lot | 동일 조건에서 한 번에 생산하는 웨이퍼 묶음 |
| Yield Rate | 수율: 생산된 전체 Die 중 양품 비율 (%) |
| Technology Node | 반도체 공정 미세화 수준 (예: 5nm, 7nm) |
| Wafer | 실리콘 원판 (200mm, 300mm) |
| Die | 웨이퍼에서 잘라낸 개별 칩 |
| MES | Manufacturing Execution System — 생산 실행 시스템 |
| ERP | Enterprise Resource Planning — 전사 자원 관리 |
| ETL | Extract, Transform, Load — 데이터 추출·변환·적재 |
| CDC | Change Data Capture — 실시간 DB 변경 감지 |
| vLLM | GPU 기반 고성능 LLM 서빙 프레임워크 |
| Text-to-SQL | 자연어 질문을 SQL 쿼리로 변환하는 AI 기술 |
| KPI | Key Performance Indicator — 핵심 성과 지표 |
| LIMS | Laboratory Information Management System — 품질 분석 시스템 |
| AOI | Automated Optical Inspection — 자동 광학 검사 |
