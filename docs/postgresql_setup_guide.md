# PostgreSQL 상세 세팅 가이드

**대상 시스템**: 반도체 파운드리 운영 대시보드  
**작성일**: 2026-07-07 (2026-07-19 pgvector/RAG 확장 반영)  
**적용 버전**: PostgreSQL 16 (14 이상 호환)

---

## 목차

1. [설치 (OS별)](#1-설치)
2. [초기 서버 설정](#2-초기-서버-설정)
3. [보안 접속 설정 (pg_hba.conf)](#3-보안-접속-설정)
4. [사용자 및 데이터베이스 생성](#4-사용자-및-데이터베이스-생성)
5. [스키마 적용 (마이그레이션)](#5-스키마-적용)
6. [연결 풀 및 Spring Boot 연동](#6-spring-boot-연동)
7. [성능 튜닝 파라미터](#7-성능-튜닝)
8. [백업 및 복구](#8-백업-및-복구)
9. [모니터링 쿼리 모음](#9-모니터링-쿼리)
10. [기존 운영 DB 연동 패턴](#10-기존-운영-db-연동)
11. [pgvector 확장 설치 및 RAG 테이블 (RAG 확장, 신규)](#11-pgvector-확장-설치)

---

## 1. 설치

### macOS (Homebrew)

```bash
# 설치
brew install postgresql@16

# PATH 등록 (zsh 기준)
echo 'export PATH="/opt/homebrew/opt/postgresql@16/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc

# 서비스 시작 (부팅 시 자동 시작)
brew services start postgresql@16

# 서비스 수동 제어
brew services stop  postgresql@16
brew services restart postgresql@16

# 연결 확인
pg_isready
# 출력: /tmp:5432 - 접속을 받아드리는 중
```

### Ubuntu / Debian

```bash
# 공식 저장소 추가 (최신 버전 사용 권장)
sudo apt install -y curl ca-certificates
sudo install -d /usr/share/postgresql-common/pgdg
curl -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc \
    --fail https://www.postgresql.org/media/keys/ACCC4CF8.asc
sudo sh -c 'echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] \
    https://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" \
    > /etc/apt/sources.list.d/pgdg.list'

sudo apt update
sudo apt install -y postgresql-16 postgresql-contrib-16

# 서비스 시작
sudo systemctl enable --now postgresql

# postgres 계정으로 접속
sudo -u postgres psql
```

### RHEL / CentOS / Rocky Linux

```bash
# 저장소 추가
sudo dnf install -y https://download.postgresql.org/pub/repos/yum/reporpms/EL-9-x86_64/pgdg-redhat-repo-latest.noarch.rpm
sudo dnf -qy module disable postgresql
sudo dnf install -y postgresql16-server postgresql16-contrib

# DB 클러스터 초기화
sudo /usr/pgsql-16/bin/postgresql-16-setup initdb

# 서비스 시작
sudo systemctl enable --now postgresql-16

# 접속
sudo -u postgres psql
```

### Docker (간편 테스트용)

```bash
docker run -d \
  --name foundry-pg \
  -e POSTGRES_DB=foundry_db \
  -e POSTGRES_USER=foundry_app \
  -e POSTGRES_PASSWORD=강력한패스워드 \
  -p 5432:5432 \
  -v foundry_pgdata:/var/lib/postgresql/data \
  postgres:16-alpine

# 접속 확인
docker exec -it foundry-pg psql -U foundry_app -d foundry_db
```

---

## 2. 초기 서버 설정

### 2.1 설정 파일 위치 확인

```bash
# 설정 파일 위치 조회
psql -U postgres -c "SHOW config_file;"
psql -U postgres -c "SHOW hba_file;"
psql -U postgres -c "SHOW data_directory;"

# macOS Homebrew 기준
# config_file:    /opt/homebrew/var/postgresql@16/postgresql.conf
# hba_file:       /opt/homebrew/var/postgresql@16/pg_hba.conf
# data_directory: /opt/homebrew/var/postgresql@16

# Ubuntu 기준
# config_file:    /etc/postgresql/16/main/postgresql.conf
# hba_file:       /etc/postgresql/16/main/pg_hba.conf
# data_directory: /var/lib/postgresql/16/main
```

### 2.2 postgresql.conf 핵심 파라미터

```ini
# ── 연결 설정 ──────────────────────────────────────────────
listen_addresses = 'localhost'      # 운영 시 특정 IP만 허용
                                    # 예: '192.168.1.100,localhost'
port = 5432
max_connections = 100               # 동시 최대 접속 수
                                    # 계산식: pool_size × 앱서버수 + 5 (여유)

# ── 메모리 설정 (서버 RAM 기준 조정) ───────────────────────
# 8GB RAM 서버 기준:
shared_buffers = 2GB                # RAM의 25%
effective_cache_size = 6GB          # RAM의 75% (OS 캐시 포함 추정)
work_mem = 128MB                    # 정렬/해시 메모리 (세션당)
maintenance_work_mem = 512MB        # VACUUM, CREATE INDEX 작업용
wal_buffers = 64MB                  # WAL 버퍼

# 16GB RAM 서버 기준:
# shared_buffers = 4GB
# effective_cache_size = 12GB
# work_mem = 256MB
# maintenance_work_mem = 1GB

# ── 체크포인트 ──────────────────────────────────────────────
checkpoint_completion_target = 0.9  # 체크포인트 분산 처리
wal_level = replica                 # 복제 준비 (나중에 Replica 구성 시)

# ── 쿼리 플래너 ─────────────────────────────────────────────
random_page_cost = 1.1              # SSD 사용 시 (HDD는 4.0 기본)
effective_io_concurrency = 200      # SSD: 200, HDD: 2

# ── 로깅 ────────────────────────────────────────────────────
log_destination = 'stderr'
logging_collector = on
log_directory = 'log'
log_filename = 'postgresql-%Y-%m-%d.log'
log_rotation_age = 1d
log_rotation_size = 100MB

log_min_duration_statement = 1000   # 1초 초과 쿼리 로깅 (밀리초)
log_checkpoints = on
log_connections = off               # 운영 시 off (로그 과다 방지)
log_disconnections = off
log_lock_waits = on
log_statement = 'ddl'               # DDL 구문만 로깅 (운영)
                                    # 개발 시: 'all'

# ── Autovacuum ───────────────────────────────────────────────
autovacuum = on                     # 반드시 on 유지
autovacuum_vacuum_cost_delay = 2ms  # SSD 환경 최적화
autovacuum_max_workers = 3

# ── 기타 ────────────────────────────────────────────────────
timezone = 'Asia/Seoul'             # 또는 'UTC' (권장: UTC 통일)
lc_messages = 'en_US.UTF-8'        # 에러 메시지 영어 (검색 용이)
```

```bash
# 설정 변경 후 적용 (재시작 불필요 파라미터는 reload로)
sudo systemctl reload postgresql    # Ubuntu
brew services restart postgresql@16 # macOS

# 재시작 필요 파라미터 확인
SELECT name, context FROM pg_settings
WHERE context IN ('postmaster','superuser')
ORDER BY context, name;
-- context = 'postmaster': 서버 재시작 필요
-- context = 'sighup':     reload만으로 적용
```

---

## 3. 보안 접속 설정

### 3.1 pg_hba.conf 구조

```
# 형식: TYPE  DATABASE  USER  ADDRESS  METHOD
# TYPE:    local(소켓), host(TCP), hostssl(TLS), hostnossl
# METHOD:  trust, md5, scram-sha-256, reject, peer

# macOS/Ubuntu 기본 위치 수정
```

### 3.2 권장 설정 (보안 강화)

```conf
# pg_hba.conf — 파운드리 대시보드 권장 설정

# ── 로컬 소켓 접속 ─────────────────────────────────────────
# postgres 슈퍼유저: 로컬 소켓만 허용 (peer 인증)
local   all             postgres                                peer

# ── 애플리케이션 접속 (TCP) ─────────────────────────────────
# 대시보드 백엔드 서버 IP에서만 foundry_app 접속 허용
host    foundry_db      foundry_app     192.168.1.100/32        scram-sha-256
host    foundry_db      foundry_app     127.0.0.1/32            scram-sha-256

# ETL 서버에서 ETL 계정만 허용
host    foundry_db      foundry_etl     192.168.1.200/32        scram-sha-256

# ── 읽기 전용 계정 (BI 툴 접속) ────────────────────────────
host    foundry_db      foundry_ro      192.168.10.0/24         scram-sha-256

# ── 관리자 전용 (DBA PC만 허용) ───────────────────────────
host    all             postgres        192.168.1.50/32         scram-sha-256

# ── 나머지 모두 차단 ────────────────────────────────────────
host    all             all             0.0.0.0/0               reject
```

```bash
# 변경 후 적용
sudo systemctl reload postgresql   # Ubuntu
brew services restart postgresql@16 # macOS
```

### 3.3 비밀번호 암호화 설정 (scram-sha-256 강제)

```sql
-- postgresql.conf에 추가
password_encryption = scram-sha-256   -- md5보다 안전

-- 기존 md5 계정 재설정
ALTER USER foundry_app PASSWORD '새비밀번호';
-- 자동으로 scram-sha-256으로 저장됨
```

---

## 4. 사용자 및 데이터베이스 생성

### 4.1 전체 설정 스크립트 (순서대로 실행)

```sql
-- ============================================================
-- STEP 1: postgres 슈퍼유저로 접속하여 실행
-- psql -U postgres
-- ============================================================

-- 1-1. 애플리케이션 전용 역할(Role) 생성
CREATE ROLE foundry_app
    WITH LOGIN
    PASSWORD '변경필수_최소16자_특수문자포함'
    CONNECTION LIMIT 20          -- 최대 동시 접속 수 제한
    VALID UNTIL 'infinity';      -- 계정 만료일 (보안 정책에 따라 설정)

-- 1-2. ETL 배치 전용 계정
CREATE ROLE foundry_etl
    WITH LOGIN
    PASSWORD '변경필수_ETL전용패스워드'
    CONNECTION LIMIT 5;

-- 1-3. 읽기 전용 계정 (분석/리포팅)
CREATE ROLE foundry_ro
    WITH LOGIN
    PASSWORD '변경필수_읽기전용패스워드'
    CONNECTION LIMIT 10;

-- ============================================================
-- STEP 2: 데이터베이스 생성
-- ============================================================
CREATE DATABASE foundry_db
    WITH OWNER     = postgres
    ENCODING       = 'UTF8'
    LC_COLLATE     = 'en_US.UTF-8'
    LC_CTYPE       = 'en_US.UTF-8'
    TEMPLATE       = template0
    CONNECTION LIMIT = 50;       -- DB 수준 최대 접속 제한

-- DB 설명 추가
COMMENT ON DATABASE foundry_db IS '파운드리 운영 대시보드 데이터베이스';

-- ============================================================
-- STEP 3: foundry_db에 접속하여 권한 부여
-- \c foundry_db
-- ============================================================

-- 3-1. 스키마 접근 권한
GRANT CONNECT ON DATABASE foundry_db TO foundry_app, foundry_ro, foundry_etl;
GRANT USAGE   ON SCHEMA public        TO foundry_app, foundry_ro, foundry_etl;

-- 3-2. foundry_app: SELECT만 허용 (대시보드 API)
GRANT SELECT ON ALL TABLES    IN SCHEMA public TO foundry_app;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO foundry_app;
-- 향후 새 테이블 자동 권한 부여
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT ON TABLES    TO foundry_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT ON SEQUENCES TO foundry_app;

-- 3-3. foundry_ro: SELECT만 허용 (읽기 전용)
GRANT SELECT ON ALL TABLES    IN SCHEMA public TO foundry_ro;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT ON TABLES TO foundry_ro;

-- 3-4. foundry_etl: DML 허용 (ETL 배치)
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES    IN SCHEMA public TO foundry_etl;
GRANT USAGE, SELECT                  ON ALL SEQUENCES IN SCHEMA public TO foundry_etl;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES    TO foundry_etl;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT                  ON SEQUENCES TO foundry_etl;

-- ============================================================
-- STEP 4: 권한 확인
-- ============================================================
-- 계정 목록 확인
SELECT rolname, rolcanlogin, rolconnlimit, rolvaliduntil
FROM pg_roles
WHERE rolname LIKE 'foundry%';

-- DB 권한 확인
SELECT datname, datacl FROM pg_database WHERE datname = 'foundry_db';

-- 테이블 권한 확인 (스키마 적용 후)
SELECT grantee, table_name, privilege_type
FROM information_schema.role_table_grants
WHERE grantee LIKE 'foundry%'
ORDER BY grantee, table_name;
```

### 4.2 계정 관리 명령어 모음

```sql
-- 비밀번호 변경
ALTER USER foundry_app PASSWORD '새비밀번호';

-- 계정 잠금 (퇴직자 처리 등)
ALTER USER foundry_ro NOLOGIN;

-- 계정 만료일 설정
ALTER USER foundry_etl VALID UNTIL '2027-12-31';

-- 접속 제한 강화
ALTER USER foundry_app CONNECTION LIMIT 10;

-- 계정 삭제 (소유 객체 이전 후)
REASSIGN OWNED BY foundry_old TO postgres;
DROP OWNED BY foundry_old;
DROP USER foundry_old;
```

---

## 5. 스키마 적용

### 5.1 스키마 적용 순서

```bash
# 1. DB 접속
psql -U postgres -d foundry_db

# 2. 확장 모듈 설치 (필요 시)
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;  -- 슬로우 쿼리 분석용
CREATE EXTENSION IF NOT EXISTS dblink;               -- ETL 원격 DB 연결용
CREATE EXTENSION IF NOT EXISTS pgcrypto;             -- 암호화 함수 (필요 시)

# 3. 스키마 파일 실행
psql -U postgres -d foundry_db -f /path/to/db_setup.sql
psql -U postgres -d foundry_db -f /path/to/views.sql
psql -U postgres -d foundry_db -f /path/to/triggers.sql

# 4. 시드 데이터 (개발/스테이징만)
psql -U postgres -d foundry_db -f /path/to/seed_data.sql
```

### 5.2 마이그레이션 관리 (버전 관리)

```
마이그레이션 파일 네이밍 규칙:
V001__create_customers.sql
V002__create_products.sql
V003__create_sales_orders.sql
V004__create_production_lots.sql
V005__create_defect_records.sql
V006__create_views.sql
V007__create_triggers.sql
V008__create_indexes.sql
V009__add_lot_order_id.sql       ← 컬럼 추가 시 새 파일
V010__add_defect_density.sql
```

```sql
-- 마이그레이션 실행 이력 테이블 (Flyway/Liquibase 없이 수동 관리 시)
CREATE TABLE schema_version (
    version     VARCHAR(10)  PRIMARY KEY,
    description VARCHAR(200) NOT NULL,
    applied_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    applied_by  VARCHAR(50)  NOT NULL DEFAULT CURRENT_USER,
    checksum    INTEGER
);

-- 적용 후 기록
INSERT INTO schema_version (version, description)
VALUES ('V001', 'Create customers table');
```

### 5.3 컬럼 추가 시 주의사항 (무중단)

```sql
-- ✅ 안전: NOT NULL + DEFAULT 있는 컬럼 추가
ALTER TABLE production_lots
    ADD COLUMN fab_line VARCHAR(20) DEFAULT 'LINE-A';

-- ⚠️ 주의: NOT NULL + DEFAULT 없는 컬럼 추가 (기존 데이터 있을 때)
-- 방법: nullable로 추가 → 기존 데이터 업데이트 → NOT NULL 제약 추가
ALTER TABLE production_lots ADD COLUMN engineer_id VARCHAR(50);
UPDATE production_lots SET engineer_id = 'UNKNOWN' WHERE engineer_id IS NULL;
ALTER TABLE production_lots ALTER COLUMN engineer_id SET NOT NULL;

-- ✅ 안전: 인덱스 무중단 생성 (CONCURRENTLY)
CREATE INDEX CONCURRENTLY idx_lots_engineer ON production_lots(engineer_id);
-- ※ CONCURRENTLY는 트랜잭션 내에서 실행 불가
```

---

## 6. Spring Boot 연동

### 6.1 pom.xml 의존성

```xml
<!-- PostgreSQL JDBC Driver -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
    <!-- Spring Boot BOM이 버전 자동 관리 (42.7.x) -->
</dependency>

<!-- Spring JDBC (JdbcTemplate) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>

<!-- 커넥션 풀: HikariCP (Spring Boot 기본 포함) -->
<!-- 별도 추가 불필요 -->
```

### 6.2 application.yml 전체 설정

```yaml
spring:
  datasource:
    # 기본 연결
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:foundry_db}
    username: ${DB_USERNAME:foundry_app}
    password: ${DB_PASSWORD:}
    driver-class-name: org.postgresql.Driver

    # HikariCP 커넥션 풀 상세 설정
    hikari:
      # 풀 크기 (공식 계산식: CPU코어 × 2 + 1)
      maximum-pool-size: 10
      minimum-idle: 3

      # 타임아웃 설정 (밀리초)
      connection-timeout: 30000       # 풀에서 커넥션 획득 대기 최대 30초
      idle-timeout: 600000            # 유휴 커넥션 10분 후 제거
      max-lifetime: 1800000           # 커넥션 최대 수명 30분
      keepalive-time: 300000          # 5분마다 keepalive 쿼리

      # 커넥션 유효성 검사
      validation-timeout: 5000
      connection-test-query: SELECT 1  # PostgreSQL은 isValid() 지원하므로 생략 가능

      # 풀 이름 (모니터링 식별용)
      pool-name: FoundryDashboardPool

      # PostgreSQL 전용 설정
      data-source-properties:
        socketTimeout: 30             # 소켓 읽기 타임아웃 (초)
        connectTimeout: 10            # 접속 타임아웃 (초)
        ApplicationName: foundry-dashboard  # pg_stat_activity에서 식별
        reWriteBatchedInserts: true   # 배치 INSERT 최적화
        prepareThreshold: 5           # 서버 사이드 PreparedStatement 임계값

  # JdbcTemplate 설정
  jdbc:
    template:
      query-timeout: 30               # 쿼리 타임아웃 30초
      fetch-size: 1000                # 대용량 쿼리 페치 크기
```

### 6.3 JdbcTemplate 사용 패턴

```java
// ── 단일 값 조회 ───────────────────────────────────────────
Double revenue = jdbc.queryForObject(
    "SELECT COALESCE(SUM(total_amount), 0) FROM sales_orders WHERE status != ?",
    Double.class,
    "CANCELLED"
);

// ── 목록 조회 ──────────────────────────────────────────────
List<Map<String, Object>> rows = jdbc.queryForList(
    "SELECT c.name, SUM(so.total_amount) AS revenue " +
    "FROM sales_orders so JOIN customers c ON so.customer_id = c.id " +
    "WHERE so.status != ? " +
    "GROUP BY c.name ORDER BY revenue DESC",
    "CANCELLED"
);

// ── NamedParameter (가독성 향상) ───────────────────────────
// pom.xml에 추가 불필요 (spring-boot-starter-jdbc에 포함)
NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(jdbc);

Map<String, Object> params = Map.of(
    "status", "CANCELLED",
    "tier",   "PLATINUM"
);
List<Map<String, Object>> result = namedJdbc.queryForList(
    "SELECT * FROM customers WHERE tier = :tier " +
    "AND id IN (SELECT customer_id FROM sales_orders WHERE status != :status)",
    params
);

// ── 대용량 스트리밍 (메모리 효율) ─────────────────────────
jdbc.query(
    "SELECT * FROM defect_records WHERE detected_at > ?",
    rs -> {
        while (rs.next()) {
            // 한 행씩 처리 → OOM 방지
            processRow(rs.getLong("id"), rs.getString("defect_type"));
        }
    },
    LocalDate.now().minusMonths(3)
);
```

### 6.4 커넥션 풀 상태 확인 (Actuator)

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics
  endpoint:
    health:
      show-details: always
```

```bash
# 커넥션 풀 상태 확인
curl http://localhost:8080/actuator/health | python3 -m json.tool

# HikariCP 메트릭
curl http://localhost:8080/actuator/metrics/hikaricp.connections.active
curl http://localhost:8080/actuator/metrics/hikaricp.connections.idle
curl http://localhost:8080/actuator/metrics/hikaricp.connections.pending
```

---

## 7. 성능 튜닝

### 7.1 실행 계획 분석

```sql
-- 기본 실행 계획
EXPLAIN
SELECT c.name, SUM(so.total_amount)
FROM sales_orders so
JOIN customers c ON so.customer_id = c.id
GROUP BY c.name;

-- 실제 실행 통계 포함 (운영 환경에서는 주의)
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT c.name, SUM(so.total_amount)
FROM sales_orders so
JOIN customers c ON so.customer_id = c.id
WHERE so.status != 'CANCELLED'
GROUP BY c.id, c.name
ORDER BY 2 DESC;

-- 실행 계획 읽는 법
-- Seq Scan:   테이블 전체 스캔 (데이터 많으면 느림)
-- Index Scan: 인덱스 사용 (빠름)
-- Bitmap Scan: 조건 복합 시 (보통)
-- Hash Join:  해시 기반 조인 (대용량)
-- Nested Loop: 소량 데이터 조인 (빠름)
```

### 7.2 인덱스 설계 원칙

```sql
-- ── 인덱스 추가 기준 ───────────────────────────────────────
-- 1. WHERE 절에 자주 등장하는 컬럼
-- 2. JOIN ON 절의 FK 컬럼
-- 3. ORDER BY 절의 컬럼 (특히 LIMIT와 함께)
-- 4. GROUP BY 절의 컬럼 (카디널리티 높을 때)

-- ── 복합 인덱스 컬럼 순서 ─────────────────────────────────
-- 등호(=) 조건 컬럼 → 범위(<,>,BETWEEN) 컬럼 → ORDER BY 컬럼 순
CREATE INDEX idx_orders_status_date
    ON sales_orders(status, order_date DESC);
-- WHERE status = 'DELIVERED' ORDER BY order_date DESC → 최적

-- ── 부분 인덱스 (특정 조건만 인덱싱, 크기 절약) ───────────
CREATE INDEX idx_lots_active
    ON production_lots(start_date, product_id)
    WHERE status IN ('QUEUED', 'IN_PROGRESS');

-- ── 함수 인덱스 (TO_CHAR, DATE_TRUNC 사용 시) ─────────────
CREATE INDEX idx_orders_year_month
    ON sales_orders(DATE_TRUNC('month', order_date));
-- SELECT ... GROUP BY DATE_TRUNC('month', order_date) → 인덱스 활용

-- ── 인덱스 크기 및 사용률 확인 ───────────────────────────
SELECT
    indexname,
    pg_size_pretty(pg_relation_size(indexrelid)) AS size,
    idx_scan AS scan_count,
    idx_tup_read AS rows_read
FROM pg_stat_user_indexes
WHERE schemaname = 'public'
ORDER BY idx_scan DESC;

-- 사용하지 않는 인덱스 찾기 (idx_scan = 0)
SELECT indexname, tablename
FROM pg_stat_user_indexes
WHERE idx_scan = 0
  AND schemaname = 'public';
```

### 7.3 통계 정보 갱신

```sql
-- 통계 정보 갱신 (실행 계획 최적화)
ANALYZE customers;
ANALYZE sales_orders;
ANALYZE production_lots;
ANALYZE defect_records;

-- 전체 테이블 (운영 중단 없이 실행)
ANALYZE VERBOSE;

-- 테이블 팽창 확인 (VACUUM 필요 여부)
SELECT relname,
       n_live_tup,
       n_dead_tup,
       ROUND(n_dead_tup::NUMERIC / NULLIF(n_live_tup,0) * 100, 2) AS dead_ratio_pct,
       last_autovacuum,
       last_autoanalyze
FROM pg_stat_user_tables
ORDER BY dead_ratio_pct DESC NULLS LAST;

-- VACUUM (불필요한 행 정리)
VACUUM ANALYZE sales_orders;

-- VACUUM FULL (테이블 크기 축소, 잠금 발생 → 비운영 시간에)
VACUUM FULL defect_records;
```

### 7.4 슬로우 쿼리 분석

```sql
-- pg_stat_statements 활성화 확인
SELECT * FROM pg_extension WHERE extname = 'pg_stat_statements';
-- 없으면: CREATE EXTENSION pg_stat_statements;
-- postgresql.conf에 추가: shared_preload_libraries = 'pg_stat_statements'

-- Top 10 슬로우 쿼리
SELECT
    LEFT(query, 100)                        AS query_preview,
    calls,
    ROUND(total_exec_time::NUMERIC, 2)      AS total_ms,
    ROUND(mean_exec_time::NUMERIC, 2)       AS avg_ms,
    ROUND(stddev_exec_time::NUMERIC, 2)     AS stddev_ms,
    rows
FROM pg_stat_statements
ORDER BY mean_exec_time DESC
LIMIT 10;

-- 통계 초기화 (조정 후 재측정 시)
SELECT pg_stat_statements_reset();
```

---

## 8. 백업 및 복구

### 8.1 논리 백업 (pg_dump)

```bash
# 전체 DB 백업 (압축)
pg_dump -U postgres -d foundry_db \
    --format=custom \
    --compress=9 \
    --file=/backup/foundry_db_$(date +%Y%m%d_%H%M%S).dump

# 특정 테이블만 백업
pg_dump -U postgres -d foundry_db \
    --table=sales_orders \
    --table=production_lots \
    --format=custom \
    --file=/backup/orders_lots_$(date +%Y%m%d).dump

# 스키마만 백업 (데이터 제외)
pg_dump -U postgres -d foundry_db \
    --schema-only \
    --file=/backup/foundry_schema_$(date +%Y%m%d).sql

# 데이터만 백업 (스키마 제외)
pg_dump -U postgres -d foundry_db \
    --data-only \
    --file=/backup/foundry_data_$(date +%Y%m%d).sql
```

### 8.2 복구 (pg_restore)

```bash
# 전체 복구
pg_restore -U postgres -d foundry_db \
    --verbose \
    --clean \
    /backup/foundry_db_20260707.dump

# 특정 테이블만 복구
pg_restore -U postgres -d foundry_db \
    --table=sales_orders \
    /backup/foundry_db_20260707.dump

# 복구 전 DB 초기화 후 복구 (완전 복원)
dropdb -U postgres foundry_db
createdb -U postgres foundry_db
pg_restore -U postgres -d foundry_db /backup/foundry_db_20260707.dump
```

### 8.3 자동 백업 스크립트

```bash
#!/bin/bash
# /opt/scripts/backup_foundry_db.sh

BACKUP_DIR="/backup/postgresql"
DB_NAME="foundry_db"
DB_USER="postgres"
RETENTION_DAYS=30
DATE=$(date +%Y%m%d_%H%M%S)
FILENAME="${BACKUP_DIR}/${DB_NAME}_${DATE}.dump"

mkdir -p "$BACKUP_DIR"

# 백업 실행
pg_dump -U "$DB_USER" -d "$DB_NAME" \
    --format=custom --compress=9 \
    --file="$FILENAME"

if [ $? -eq 0 ]; then
    echo "[$(date)] 백업 성공: $FILENAME ($(du -sh $FILENAME | cut -f1))"
else
    echo "[$(date)] 백업 실패!" >&2
    exit 1
fi

# 오래된 백업 삭제
find "$BACKUP_DIR" -name "${DB_NAME}_*.dump" \
    -mtime +$RETENTION_DAYS -delete

echo "[$(date)] 보존 기간(${RETENTION_DAYS}일) 초과 파일 삭제 완료"
```

```bash
# crontab 등록 (매일 새벽 2시)
crontab -e
# 추가:
0 2 * * * /opt/scripts/backup_foundry_db.sh >> /var/log/pg_backup.log 2>&1
```

---

## 9. 모니터링 쿼리

### 9.1 서버 상태 대시보드 쿼리

```sql
-- 현재 접속 현황
SELECT state, count(*), application_name
FROM pg_stat_activity
WHERE datname = 'foundry_db'
GROUP BY state, application_name
ORDER BY count DESC;

-- 현재 실행 중인 쿼리 (5초 이상)
SELECT pid, usename, application_name,
       NOW() - query_start AS duration,
       LEFT(query, 100) AS query
FROM pg_stat_activity
WHERE state = 'active'
  AND query_start < NOW() - INTERVAL '5 seconds'
  AND datname = 'foundry_db'
ORDER BY duration DESC;

-- 잠금 대기 쿼리 확인
SELECT
    blocked.pid         AS blocked_pid,
    blocked.query       AS blocked_query,
    blocking.pid        AS blocking_pid,
    blocking.query      AS blocking_query
FROM pg_stat_activity blocked
JOIN pg_stat_activity blocking
    ON blocking.pid = ANY(pg_blocking_pids(blocked.pid))
WHERE cardinality(pg_blocking_pids(blocked.pid)) > 0;

-- 테이블 크기 순위
SELECT relname AS table_name,
       pg_size_pretty(pg_total_relation_size(relid)) AS total_size,
       pg_size_pretty(pg_relation_size(relid))        AS table_size,
       pg_size_pretty(pg_total_relation_size(relid)
           - pg_relation_size(relid))                  AS index_size
FROM pg_stat_user_tables
ORDER BY pg_total_relation_size(relid) DESC;

-- DB 전체 크기
SELECT pg_size_pretty(pg_database_size('foundry_db')) AS db_size;

-- 캐시 히트율 (권장: 99% 이상)
SELECT
    SUM(heap_blks_hit) AS cache_hits,
    SUM(heap_blks_read) AS disk_reads,
    ROUND(SUM(heap_blks_hit)::NUMERIC /
          NULLIF(SUM(heap_blks_hit) + SUM(heap_blks_read), 0) * 100, 2) AS cache_hit_rate
FROM pg_statio_user_tables;

-- 트랜잭션 통계
SELECT datname,
       xact_commit   AS commits,
       xact_rollback AS rollbacks,
       blks_read     AS disk_reads,
       blks_hit      AS cache_hits,
       tup_inserted, tup_updated, tup_deleted, tup_returned
FROM pg_stat_database
WHERE datname = 'foundry_db';
```

---

## 10. 기존 운영 DB 연동

### 10.1 Read-Only 계정으로 기존 DB 직접 연결

```yaml
# application.yml — 기존 MES DB 직접 연결 (Read-Only)
spring:
  datasource:
    primary:                                 # 기존 MES DB
      url: jdbc:postgresql://mes-db.internal:5432/mes_production
      username: dashboard_readonly
      password: ${MES_DB_PASSWORD}
      hikari:
        pool-name: MesDBPool
        maximum-pool-size: 5
        read-only: true                      # 읽기 전용 모드 강제
```

```java
// DataSource 설정 (다중 DB 연결 시)
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.primary.hikari")
    public HikariDataSource mesDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setReadOnly(true);  // 쓰기 시도 시 예외 발생
        return ds;
    }
}
```

### 10.2 기존 DB 테이블 구조 파악 쿼리

```sql
-- 기존 DB의 테이블 목록과 컬럼 파악
SELECT
    table_name,
    column_name,
    data_type,
    is_nullable,
    column_default
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name IN ('wip', 'lot_master', 'defect', 'order_header')
ORDER BY table_name, ordinal_position;

-- 기존 테이블 레코드 수
SELECT relname AS table_name,
       n_live_tup AS row_count
FROM pg_stat_user_tables
ORDER BY n_live_tup DESC;

-- FK 관계 파악
SELECT
    tc.table_name,
    kcu.column_name,
    ccu.table_name  AS foreign_table,
    ccu.column_name AS foreign_column
FROM information_schema.table_constraints tc
JOIN information_schema.key_column_usage kcu
    ON tc.constraint_name = kcu.constraint_name
JOIN information_schema.constraint_column_usage ccu
    ON ccu.constraint_name = tc.constraint_name
WHERE tc.constraint_type = 'FOREIGN KEY'
  AND tc.table_schema = 'public';
```

### 10.3 dblink로 원격 DB 쿼리 (ETL용)

```sql
-- dblink 확장 설치
CREATE EXTENSION IF NOT EXISTS dblink;

-- 원격 DB 연결 문자열 등록 (재사용)
SELECT dblink_connect(
    'mes_conn',
    'host=mes-db.internal port=5432 dbname=mesdb user=etl_ro password=패스워드'
);

-- 원격 쿼리 실행
SELECT *
FROM dblink('mes_conn',
    'SELECT lot_id, lot_num, wfr_cnt, status_cd, yield_pct
     FROM mes_wip WHERE updated_at > NOW() - INTERVAL ''1 hour'''
) AS t(lot_id INT, lot_num VARCHAR(50), wfr_cnt INT,
       status_cd CHAR(1), yield_pct NUMERIC);

-- 연결 종료
SELECT dblink_disconnect('mes_conn');
```

### 10.4 postgres_fdw로 외부 테이블 매핑 (고급)

```sql
-- postgres_fdw: 원격 DB 테이블을 로컬처럼 사용
CREATE EXTENSION IF NOT EXISTS postgres_fdw;

-- 외부 서버 등록
CREATE SERVER mes_server
    FOREIGN DATA WRAPPER postgres_fdw
    OPTIONS (host 'mes-db.internal', port '5432', dbname 'mesdb');

-- 사용자 매핑
CREATE USER MAPPING FOR foundry_etl
    SERVER mes_server
    OPTIONS (user 'etl_ro', password '패스워드');

-- 외부 테이블 매핑
CREATE FOREIGN TABLE mes_wip_remote (
    lot_id      INTEGER,
    lot_num     VARCHAR(50),
    wfr_cnt     INTEGER,
    status_cd   CHAR(1),
    yield_pct   NUMERIC(5,2),
    updated_at  TIMESTAMP
) SERVER mes_server OPTIONS (schema_name 'public', table_name 'mes_wip');

-- 이후 로컬 테이블처럼 조회 가능
SELECT * FROM mes_wip_remote WHERE status_cd = 'C' LIMIT 10;
```

---

## 11. pgvector 확장 설치

**(RAG 확장, 2026-07-19 추가)** 사내 문서 검색(RAG)용 `rag-service` 모듈이 사용하는 벡터 유사도 검색 확장입니다.
기존 5개 테이블과는 무관하며, 같은 `foundry_db`에 테이블 2개(`document_meta`, `document_chunks`)만 추가됩니다.

### 11.1 설치 (OS별)

```bash
# ── Docker (권장, 가장 간단) ─────────────────────────────────
# docker-compose.yml의 db 이미지를 pgvector가 포함된 이미지로 지정하면 끝
# image: pgvector/pgvector:pg16

# ── macOS (Homebrew) ─────────────────────────────────────────
brew install pgvector
# ⚠️ 주의: Homebrew의 pgvector 바틀은 최신 1~2개 PostgreSQL 버전(예: @17, @18)만
#          사전 빌드해서 배포합니다. postgresql@16처럼 조금 이전 버전을 쓰고 있다면
#          brew install만으로는 "extension \"vector\" is not available" 오류가 납니다.
brew list --versions pgvector   # 어떤 PG 버전용으로 설치됐는지 확인
find $(brew --prefix pgvector)/share -name "vector.control"  # postgresql@16 폴더가 없다면 아래로

# postgresql@16용으로 소스에서 직접 빌드 (Xcode Command Line Tools 필요)
git clone --branch v0.8.0 --depth 1 https://github.com/pgvector/pgvector.git
cd pgvector
make PG_CONFIG=$(brew --prefix postgresql@16)/bin/pg_config
make PG_CONFIG=$(brew --prefix postgresql@16)/bin/pg_config install

# ── Ubuntu / Debian ──────────────────────────────────────────
sudo apt install postgresql-16-pgvector

# ── RHEL / Rocky Linux ───────────────────────────────────────
sudo dnf install pgvector_16
```

### 11.2 설치 확인 및 확장 활성화

```sql
-- foundry_db에 접속한 상태로
CREATE EXTENSION IF NOT EXISTS vector;

-- 버전 확인
SELECT extversion FROM pg_extension WHERE extname = 'vector';
```

### 11.3 문서 테이블 DDL

```sql
CREATE TABLE document_meta (
    id           SERIAL       PRIMARY KEY,
    filename     VARCHAR(255) NOT NULL,
    content_type VARCHAR(100),
    file_size    BIGINT,
    chunk_count  INTEGER      DEFAULT 0,
    status       VARCHAR(20)  CHECK (status IN ('PROCESSING','COMPLETED','FAILED'))
                              DEFAULT 'PROCESSING',
    uploaded_at  TIMESTAMP    DEFAULT NOW()
);

CREATE TABLE document_chunks (
    id          SERIAL       PRIMARY KEY,
    document_id INTEGER      REFERENCES document_meta(id) ON DELETE CASCADE,
    chunk_index INTEGER      NOT NULL,
    content     TEXT         NOT NULL,
    embedding   vector(1536),
    created_at  TIMESTAMP    DEFAULT NOW()
);

-- 데모 규모(lists=10)용 인덱스. 데이터가 많아지면 lists ≈ sqrt(행 수)로 재계산 후 REINDEX 권장
CREATE INDEX idx_document_chunks_embedding
    ON document_chunks USING ivfflat (embedding vector_cosine_ops) WITH (lists = 10);

CREATE INDEX idx_document_chunks_document_id ON document_chunks(document_id);
```

이 DDL은 `rag-service/src/main/resources/schema.sql`에 있으며, rag-service가 기동할 때마다
`CREATE ... IF NOT EXISTS` 방식으로 자동 실행됩니다 — 수동 적용은 선택 사항입니다.

### 11.4 유사도 검색 쿼리 예시

```sql
-- ? 자리에는 애플리케이션이 생성한 1536차원 벡터를 '[0.01,-0.02,...]' 형태 문자열로 바인딩
SELECT dc.document_id, dm.filename, dc.chunk_index, dc.content,
       1 - (dc.embedding <=> ?::vector) AS similarity   -- 코사인 유사도 (1에 가까울수록 유사)
FROM document_chunks dc
JOIN document_meta dm ON dc.document_id = dm.id
WHERE dm.status = 'COMPLETED'
ORDER BY dc.embedding <=> ?::vector   -- <=> : 코사인 거리, ivfflat 인덱스와 동일 연산자 사용
LIMIT 5;
```

### 11.5 rag-service 전용 계정 (운영 반영 시 권장)

```sql
-- foundry_app(기존, SELECT 전용)과 분리해 문서 업로드/삭제 권한만 별도 부여
CREATE ROLE foundry_rag WITH LOGIN PASSWORD '변경필수' CONNECTION LIMIT 5;
GRANT CONNECT ON DATABASE foundry_db TO foundry_rag;
GRANT USAGE ON SCHEMA public TO foundry_rag;
GRANT SELECT, INSERT, UPDATE, DELETE ON document_meta, document_chunks TO foundry_rag;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO foundry_rag;
-- 기존 5개 업무 테이블에는 권한을 주지 않아, rag-service가 매출/생산 데이터에 접근할 수 없도록 분리
```

---

## 부록: 자주 쓰는 psql 명령어

```bash
# 접속
psql -U foundry_app -d foundry_db -h localhost

# psql 내부 명령
\l          # DB 목록
\c foundry_db  # DB 전환
\dt         # 테이블 목록
\dv         # View 목록
\d+ sales_orders  # 테이블 상세 (컬럼, 인덱스, 제약조건)
\di         # 인덱스 목록
\du         # 사용자(Role) 목록
\dp         # 객체 권한 목록
\x          # 확장 출력 모드 토글 (가로→세로)
\timing     # 쿼리 실행 시간 표시
\e          # 외부 에디터로 쿼리 편집
\i file.sql # SQL 파일 실행
\o out.txt  # 결과를 파일로 저장
\q          # 종료
```
