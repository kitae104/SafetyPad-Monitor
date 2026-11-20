# SafetyPad Monitor — Claude Code 개발 시작 패키지

이 패키지는 **개발 설계·API 계약 초안·50대 완전 가상 데이터·디자인 가이드·Claude Code 지시문**입니다. 완성된 서비스 소스코드는 아닙니다. 실제 API, DB, React, 배포는 Claude Code가 단계별로 구현하도록 준비했습니다.

## 빠른 시작
1. 이 ZIP을 원하는 작업 폴더에 압축 해제합니다.
2. 터미널에서 해당 폴더로 이동 후 `claude`를 실행합니다.
3. `START_PROMPT.md`의 전체 내용을 Claude Code에 입력합니다.
4. Claude Code는 `CLAUDE.md`, `docs/PRD.md`, `contracts/openapi.yaml`, `docs/IMPLEMENTATION_PLAN.md`를 읽고 Phase 0부터 실행해야 합니다.
5. 가상 데이터 변경 시 `python scripts/generate_demo.py`를 실행합니다.

## 결정 사항
- 기기 → 서버: **HTTPS REST POST** (기본) 또는 **MQTT over TLS** (선택, 기본 꺼짐 — [`docs/MQTT_INTEGRATION.md`](docs/MQTT_INTEGRATION.md))
- 기기별 API Key + device_id 대조
- 이벤트 발생시각(event_time) / 최초 수신시각(received_at) 구분
- 위반 이벤트 GPS와 GPS 주기 위치 기록은 별도 데이터 모델
- 지도 기본 보기: **위반 위치의 마커/클러스터**, 한 기기 선택 시에만 경로
- 지도 경로: `gps_locations` 같은 위치 핑이 존재할 때만 노출. 위반 GPS만 있을 때는 이벤트 점만 보여줌
- 블루/화이트 기업형 UI 1번 우선. PC와 모바일 하나의 반응형 React 웹앱
- 파일럿 약 50대, 확장 가능하되 과설계 금지
- 서울이 아니라 인천 미추홀구 인하공업전문대학 주변을 가상의 무대로 사용

## 주의
- 시드 데이터는 실제 위치/운행 기록이 아니라 **임의 생성한 예시**입니다. GPS 선분은 실제 도로를 따르지 않으며 네비게이션·위법 증거로 쓰지 않습니다.
- GPS 주기 전송/하트비트 기능은 원 제안서 확정 사항이 아닙니다. 화면은 데모 데이터를 사용하지만 실제 배포에서는 기능 플래그로 제어합니다.
- 지도 SDK의 API 키/약관/과금은 구현 전 확인. 개발 기본값으로 외부 키가 필요 없는 지도 대체 컴포넌트를 제공하고, 실제 지도는 어댑터로 교체합니다.
- API Key 원문/비밀번호는 패키지에 포함하지 않습니다. 공개된 예시 키도 실제 장비에 사용하지 마세요.

## 구현 현황
Phase 0~4 구현·검증 완료, Phase 5는 **로컬 운영 스택까지 검증**(AWS 실배포는 승인 전이라 미수행). 상세: [`docs/ACCEPTANCE.md`](docs/ACCEPTANCE.md).
**결정이 필요한 항목은 [`docs/OPEN_DECISIONS.md`](docs/OPEN_DECISIONS.md)에 모아 두었습니다.**

| 문서 | 내용 |
|---|---|
| [`docs/ADMIN_USER_GUIDE.md`](docs/ADMIN_USER_GUIDE.md) | **관리자 사용 매뉴얼** — 화면별 사용법, 지도 읽는 법, 업무별 빠른 시작, 문제 해결 |
| [`docs/MQTT_INTEGRATION.md`](docs/MQTT_INTEGRATION.md) | MQTT 수신 규격(토픽·인증·ack·TLS 운영 절차) |
| [`docs/ACCEPTANCE.md`](docs/ACCEPTANCE.md) | 검수 기준 대응표, 테스트 현황, 미확인 목록 |
| [`docs/OPEN_DECISIONS.md`](docs/OPEN_DECISIONS.md) | 사용자·업체 결정 사항(기본값 포함) |
| [`docs/FIRMWARE_INTEGRATION_RUNBOOK.md`](docs/FIRMWARE_INTEGRATION_RUNBOOK.md) | 장비 업체 전달용 연동 규격·시험 체크리스트 |
| [`docs/OPERATIONS_MANUAL.md`](docs/OPERATIONS_MANUAL.md) | 운영·백업/복구·장애 대응 |
| [`docs/DEPLOYMENT_AWS.md`](docs/DEPLOYMENT_AWS.md) | AWS 배포 가이드(미실행 초안) |
| [`docs/SECURITY_AUDIT.md`](docs/SECURITY_AUDIT.md), [`docs/PERFORMANCE_REPORT.md`](docs/PERFORMANCE_REPORT.md) | 보안 점검, 부하·복원력 시험 |
| [`contracts/openapi.yaml`](contracts/openapi.yaml), [`contracts/admin-openapi.yaml`](contracts/admin-openapi.yaml) | 기기 수신 / 관리자 API 계약 |

## 개발자 퀵스타트
요구 사항: Java 21, Node 22+, Docker(Compose v2), (E2E) Google Chrome.

한 번에 기동/종료(개발용): `./run.sh start|stop|restart|status|logs` (Linux/macOS), `.\run.ps1 start|stop|...` (PowerShell 7). 로그는 `.run/`에 쌓입니다.

```bash
make db-up          # .env 자동 생성(임의 비밀 값) + PostgreSQL 16 (호스트 포트 5433)
make backend-run    # http://localhost:8080  (개발 프로파일: .env의 SPRING_PROFILES_ACTIVE=dev)
make frontend-dev   # http://localhost:5173  (/api, /actuator 는 8080으로 프록시)
```
1. 브라우저에서 `http://localhost:5173` → 로그인. 아이디/비밀번호는 `.env`의 `ADMIN_BOOTSTRAP_USERNAME` / `ADMIN_BOOTSTRAP_PASSWORD` (내장 기본 계정은 없습니다).
2. 운영 설정 화면의 **“가상 데이터 불러오기”**로 `data/`의 가상 데이터(50대·178건·GPS 2,250점)를 넣습니다(개발 프로파일 한정, 화면 전체에 `가상 데이터` 표시).
3. 장비 에뮬레이터: `DEV_PROVISION_DEVICES=PAD-0001,PAD-0002`로 키 파일(`.dev-secrets/`)이 만들어지면 `python3 scripts/simulate_ingest.py --limit 10 --retry --now`.
4. API 문서(개발 한정): `http://localhost:8080/swagger.html`.

```bash
make check          # 백엔드 69 테스트(Testcontainers·실 Postgres) + 프런트 lint/단위/빌드
make e2e            # Playwright 66건 (22 시나리오 × 3 폭, 백엔드 기동 상태, 1440/768/360 + 스크린샷 → docs/screenshots)
```

### 운영 스택 리허설 (로컬)
```bash
make prod-env                       # deploy/.env.prod 생성(임의 비밀 값). 로컬이면 DOMAIN=localhost, HTTP_PORT=8088, HTTPS_PORT=8443 로 수정
make prod-up                        # Caddy(TLS) + 백엔드 + Postgres. 운영 프로파일: 데모 기능 없음
HTTPS_PORT=8443 make prod-smoke     # 29개 점검
make backup && make restore-check FILE=backups/<파일>
```
- 개발 DB 포트는 5433입니다(호스트의 5432와 충돌 방지, `.env`의 `DB_PORT`).
- `.env`, `deploy/.env.prod`, `.dev-secrets/`, `backups/`는 **커밋 금지**(`.gitignore`). 실제 비밀 값은 저장소에 없습니다.
- 기본 프로파일은 **운영 안전**(데모·dev 도구 꺼짐)입니다. 개발에서만 `SPRING_PROFILES_ACTIVE=dev`를 명시합니다.
