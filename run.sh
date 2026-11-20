#!/usr/bin/env bash
# SafetyPad Monitor — 개발 서버 기동/종료 (Linux/macOS)
#
#   ./run.sh start   [all|db|backend|frontend]   기본: all (DB → 백엔드 → 프런트)
#   ./run.sh stop    [all|db|backend|frontend]   기본: all (프런트 → 백엔드 → DB). DB 데이터(볼륨)는 보존
#   ./run.sh restart [all|db|backend|frontend]
#   ./run.sh status
#   ./run.sh logs    [backend|frontend]          로그 따라가기 (Ctrl+C로 중단)
#
# 개발 프로파일 전용입니다(.env의 SPRING_PROFILES_ACTIVE=dev). 운영 스택은 make prod-up 을 쓰세요.
# 프로세스는 PID 파일이 아니라 "리슨 포트"로 찾습니다(백엔드 8080, 프런트 5173).
set -u
cd "$(dirname "$(readlink -f "$0")")"

RUN_DIR=.run
BACKEND_PORT=8080
FRONTEND_PORT=5173
mkdir -p "$RUN_DIR"

say()  { printf '%s\n' "$*"; }
fail() { printf '오류: %s\n' "$*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || fail "'$1' 명령을 찾을 수 없습니다."; }

ensure_env() {
  if [ ! -f .env ]; then
    say ".env 가 없어 새로 만듭니다(임의 비밀 값, 커밋 금지)."
    make env >/dev/null || fail ".env 생성 실패 (make 가 필요합니다)"
  fi
}

load_env() { set -a; . ./.env; set +a; }

# 해당 포트를 LISTEN 중인 프로세스의 PID (없으면 빈 문자열)
port_pid() {
  ss -ltnp 2>/dev/null | awk -v p=":$1" '$4 ~ p"$" { print }' \
    | sed -n 's/.*pid=\([0-9]*\).*/\1/p' | head -1
}

wait_http() { # url, 최대 초
  local i=0
  while [ "$i" -lt "$2" ]; do
    curl -sf -o /dev/null "$1" && return 0
    sleep 2; i=$((i + 2))
  done
  return 1
}

# ---------- DB ----------
db_running() { [ -n "$(docker compose ps --status running -q postgres 2>/dev/null)" ]; }

start_db() {
  need docker
  if db_running; then say "DB        이미 실행 중"; return; fi
  say "DB        기동 중..."
  load_env
  docker compose up -d --wait postgres >/dev/null 2>&1 || fail "DB 기동 실패 (docker compose logs postgres 확인)"
  say "DB        실행 중 (localhost:${DB_PORT:-5433})"
}

stop_db() {
  need docker
  if ! db_running; then say "DB        이미 중지됨"; return; fi
  docker compose stop postgres >/dev/null 2>&1 || fail "DB 중지 실패"
  say "DB        중지됨 (데이터는 보존)"
}

# ---------- 백엔드 / 프런트 ----------
start_backend() {
  need java
  if [ -n "$(port_pid $BACKEND_PORT)" ]; then say "백엔드    이미 실행 중 (:$BACKEND_PORT)"; return; fi
  db_running || fail "DB가 꺼져 있습니다. ./run.sh start db 를 먼저 실행하세요."
  say "백엔드    기동 중... (처음에는 1~2분 걸릴 수 있습니다)"
  ( load_env; cd backend && exec setsid nohup ./gradlew bootRun >"../$RUN_DIR/backend.log" 2>&1 < /dev/null ) &
  if wait_http "http://localhost:$BACKEND_PORT/actuator/health" 240; then
    say "백엔드    실행 중 http://localhost:$BACKEND_PORT"
  else
    fail "백엔드가 4분 안에 올라오지 않았습니다. 로그: $RUN_DIR/backend.log"
  fi
}

start_frontend() {
  need npm
  if [ -n "$(port_pid $FRONTEND_PORT)" ]; then say "프런트    이미 실행 중 (:$FRONTEND_PORT)"; return; fi
  [ -d frontend/node_modules ] || { say "프런트    의존성 설치 중 (npm ci)..."; ( cd frontend && npm ci >"../$RUN_DIR/npm-install.log" 2>&1 ) || fail "npm ci 실패: $RUN_DIR/npm-install.log"; }
  say "프런트    기동 중..."
  ( cd frontend && exec setsid nohup npm run dev >"../$RUN_DIR/frontend.log" 2>&1 < /dev/null ) &
  if wait_http "http://localhost:$FRONTEND_PORT/" 60; then
    say "프런트    실행 중 http://localhost:$FRONTEND_PORT"
  else
    fail "프런트가 올라오지 않았습니다. 로그: $RUN_DIR/frontend.log"
  fi
}

stop_port() { # 이름, 포트
  local pid i
  pid=$(port_pid "$2")
  if [ -z "$pid" ]; then say "$1 이미 중지됨"; return; fi
  kill "$pid" 2>/dev/null
  for i in $(seq 1 20); do
    [ -z "$(port_pid "$2")" ] && { say "$1 중지됨"; return; }
    sleep 1
  done
  kill -9 "$pid" 2>/dev/null
  sleep 1
  [ -z "$(port_pid "$2")" ] && say "$1 강제 종료됨" || fail "$1 를 종료하지 못했습니다 (PID $pid)"
}

# ---------- 명령 ----------
do_status() {
  local code
  db_running && say "DB        실행 중" || say "DB        중지됨"
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$BACKEND_PORT/actuator/health" 2>/dev/null)
  [ "$code" = "200" ] && say "백엔드    실행 중 http://localhost:$BACKEND_PORT" \
    || { [ -n "$(port_pid $BACKEND_PORT)" ] && say "백엔드    기동 중이거나 비정상 (HTTP $code)" || say "백엔드    중지됨"; }
  [ -n "$(port_pid $FRONTEND_PORT)" ] && say "프런트    실행 중 http://localhost:$FRONTEND_PORT" || say "프런트    중지됨"
}

do_start() {
  case "${1:-all}" in
    all)      start_db; start_backend; start_frontend
              say ""; say "접속: http://localhost:$FRONTEND_PORT  (아이디/비밀번호는 .env 의 ADMIN_BOOTSTRAP_*)" ;;
    db)       start_db ;;
    backend)  start_backend ;;
    frontend) start_frontend ;;
    *) fail "알 수 없는 대상: $1 (all|db|backend|frontend)" ;;
  esac
}

do_stop() {
  case "${1:-all}" in
    all)      stop_port "프런트   " $FRONTEND_PORT; stop_port "백엔드   " $BACKEND_PORT; stop_db ;;
    db)       stop_db ;;
    backend)  stop_port "백엔드   " $BACKEND_PORT ;;
    frontend) stop_port "프런트   " $FRONTEND_PORT ;;
    *) fail "알 수 없는 대상: $1 (all|db|backend|frontend)" ;;
  esac
}

cmd="${1:-}"
case "$cmd" in
  start)   ensure_env; do_start "${2:-all}" ;;
  stop)    do_stop "${2:-all}" ;;
  restart) ensure_env; do_stop "${2:-all}"; do_start "${2:-all}" ;;
  status)  do_status ;;
  logs)    f="$RUN_DIR/${2:-backend}.log"; [ -f "$f" ] || fail "로그 파일이 없습니다: $f"; tail -n 50 -f "$f" ;;
  *)       sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; [ -z "$cmd" ] || [ "$cmd" = "-h" ] || [ "$cmd" = "--help" ] || exit 1 ;;
esac
