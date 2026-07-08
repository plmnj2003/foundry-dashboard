#!/bin/zsh
# 부하 테스트 중 서버 상태를 모니터링하는 스크립트
# 별도 터미널에서 실행: ./load-test/monitor.sh

export PATH="/opt/homebrew/opt/postgresql@16/bin:$PATH"

echo "=========================================="
echo "  Foundry Dashboard — 실시간 모니터링"
echo "  Ctrl+C로 종료"
echo "=========================================="

while true; do
  echo ""
  echo "──────────────────────────── $(date '+%H:%M:%S') ────────────────────────────"

  # 1. Java 프로세스 CPU/메모리
  echo "[JVM 리소스]"
  ps aux | grep "dashboard-0.0.1" | grep -v grep | \
    awk '{printf "  CPU: %s%%  MEM: %s%%  VSZ: %.0fMB  RSS: %.0fMB\n", $3, $4, $5/1024, $6/1024}'

  # 2. PostgreSQL 커넥션 현황
  echo "[PostgreSQL 커넥션]"
  psql -U foundry_app -d foundry_db -t -c "
    SELECT '  Active: ' || count(*) || '  Idle: ' ||
           sum(CASE WHEN state='idle' THEN 1 ELSE 0 END) ||
           '  Total: ' || (SELECT count(*) FROM pg_stat_activity WHERE datname='foundry_db')
    FROM pg_stat_activity
    WHERE datname='foundry_db' AND state='active';" 2>/dev/null || echo "  DB 접속 불가"

  # 3. API 응답 시간 실시간 측정
  echo "[API 응답 시간]"
  for ep in kpis revenue-trend yield-by-product; do
    ms=$(curl -s -o /dev/null -w "%{time_total}" http://localhost:8080/api/$ep 2>/dev/null | \
         awk '{printf "%.0f", $1*1000}')
    bar=""
    n=$((ms / 50))
    [ $n -gt 20 ] && n=20
    for i in $(seq 1 $n); do bar="${bar}█"; done
    printf "  %-25s %4dms  %s\n" "/api/$ep" "$ms" "$bar"
  done

  # 4. 포트 소켓 연결 수
  echo "[소켓 연결 수]"
  tcp_8080=$(lsof -i :8080 2>/dev/null | grep -c ESTABLISHED || echo 0)
  tcp_5432=$(lsof -i :5432 2>/dev/null | grep -c ESTABLISHED || echo 0)
  echo "  :8080 (Spring Boot) ESTABLISHED: $tcp_8080"
  echo "  :5432 (PostgreSQL)  ESTABLISHED: $tcp_5432"

  # 5. 메모리 압박
  echo "[시스템 메모리]"
  vm_stat 2>/dev/null | awk '
    /Pages free/     {free=$3}
    /Pages active/   {active=$3}
    /Pages wired/    {wired=$3+0}
    END {
      page=4096
      printf "  Free: %.1fGB  Active: %.1fGB  Wired: %.1fGB\n",
             free*page/1073741824, active*page/1073741824, wired*page/1073741824
    }' || echo "  측정 불가"

  sleep 5
done
