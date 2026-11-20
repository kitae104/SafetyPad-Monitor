# 운영 매뉴얼

대상: 서버 운영 담당자. 명령은 `deploy/docker-compose.prod.yml` 기반의 단일 호스트 구성을 기준으로 하며, `make` 대상은 저장소 루트에서 실행합니다.

## 1. 구성 요약
```
인터넷 ──443──> [web: Caddy (TLS, 정적 SPA, 프록시)] ──> [backend: Spring Boot :8080] ──> [postgres :5432]
                                       (내부 네트워크만)            (내부 네트워크만)
```
* 외부에 열리는 포트는 **80/443만**. DB·백엔드는 공개하지 않습니다.
* 공개 엣지가 막는 경로: `/actuator/prometheus`, `/api/v1/admin/dev/*`, `/swagger.html`, `/api-docs/*`.
* 시간은 DB·서버 모두 UTC 저장, 화면·CSV는 Asia/Seoul.

## 2. 일상 점검 (매일 5분)
| 확인 | 방법 | 정상 |
|---|---|---|
| 서비스 상태 | `curl https://<도메인>/actuator/health` (배포 직후에는 `make prod-smoke`) | `UP` / 스모크 전부 PASS |
| 컨테이너 | `docker compose -p safetypad-prod ps` | 3개 모두 `healthy`/`Up` |
| 수신 현황 | 대시보드 “최근 24시간 수신 장비”, 장비 목록의 `최근 수신` | 평소 대비 급감 없음 (※ 온라인 여부가 아님) |
| 오류율 | 메트릭 `http_server_requests_seconds_count{status=~"5.."}` | 0에 가까움 |
| 디스크 | `df -h`, `docker system df` | 사용률 80% 미만 |
| 백업 | `ls -l backups/` | 오늘 날짜의 파일 |

> `make prod-smoke`는 실행할 때마다 `SMOKE-<시각>`이라는 **비활성 테스트 장비 1대와 이벤트 1건**을 남깁니다(원시 이벤트는 삭제할 수 없는 설계). 매일 돌리지 말고 배포·변경 직후와 점검 시에만 실행하세요. 매일은 위의 health 확인으로 충분합니다.

## 3. 메트릭·로그
* **로그**: 운영 프로파일은 JSON 한 줄 로그(요청 ID 포함). `docker compose logs -f backend`. 기기 키·쿠키·본문은 로그에 남지 않도록 설계했고, 엣지 접근 로그도 헤더를 제거합니다. 장애 문의 시 응답의 `request_id`로 검색합니다.
* **메트릭**: `GET /actuator/prometheus` — **내부망에서만**, Basic 인증(`METRICS_USERNAME`/`METRICS_PASSWORD`, 관리자·기기 인증과 별개). 공개 엣지는 404.
  ```bash
  docker compose -p safetypad-prod exec -T web wget -qO- \
    --header "Authorization: Basic $(printf '%s:%s' "$METRICS_USERNAME" "$METRICS_PASSWORD" | base64 -w0)" \
    http://backend:8080/actuator/prometheus | grep safetypad_
  ```
* 핵심 지표: `safetypad_ingest_events_total{result="accepted|duplicate"}`, `http_server_requests_seconds*`(상태 코드별), JVM·Hikari 풀.
* 권장 알림(수신처·임계값은 운영 결정 → `OPEN_DECISIONS.md` C-13):
  1. `/actuator/health`가 DOWN 2분 이상
  2. 5xx 비율 1% 초과 5분 (특히 503 = DB 장애 신호)
  3. 수신 이벤트 0건이 평소 업무 시간대에 N시간 지속
  4. 401 급증(키 폐기 누락·노출 시도), 429 지속(속도 제한이 너무 낮거나 비정상 트래픽)
  5. 디스크 80%, 인증서 만료 14일 전(Caddy 자동 갱신 실패 시)

## 4. 장비·키 관리
* **장비 등록 → 키 발급**: 관리자 화면 “장비 관리”. 발급 시 키 원문은 한 번만 표시됩니다(서버에는 해시만 저장).
* **키 교체(무중단)**: 새 키 발급 → 기기 반영 확인 → 이전 키 폐기. 두 키는 폐기 전까지 모두 유효합니다.
* **키 노출 의심**: 즉시 해당 키 폐기 → 새 키 발급 → 감사 로그에서 해당 키 접두사(`key_prefix`)의 사용 이력 확인.
* **장비 비활성화**: 분실·철거 장비는 비활성화하면 전송이 403으로 거부됩니다. 기존 이벤트는 그대로 보존됩니다.
* **pepper(`DEVICE_KEY_PEPPER`)를 바꾸면 발급된 모든 키가 무효**가 됩니다. 의도된 전체 교체가 아니면 바꾸지 마세요. 별도 보관(§8).

## 5. 관리자 계정
* 첫 관리자는 `ADMIN_BOOTSTRAP_USERNAME/PASSWORD`로 **관리자가 한 명도 없을 때만** 생성됩니다. 생성 후에는 `.env`에서 비밀번호를 지우고 화면에서 비밀번호를 변경하세요.
* 운영 설정 → “관리자 계정”: 추가, 비밀번호 재설정(다른 관리자), 내 비밀번호 변경, 비활성화(열려 있는 세션도 즉시 종료).
* 비밀번호 분실 시: 다른 관리자가 재설정. **모든 관리자가 잠겼다면** DB에서 직접 처리합니다(§9-3).
* 로그인 실패는 IP+아이디당 분당 10회로 제한되며 감사 로그에 남습니다.

## 6. 데이터 내보내기·감사
* CSV 내보내기(위반 이벤트)는 최대 5만 행이고 **누가·언제·어떤 조건으로** 내보냈는지 `audit_logs`에 기록됩니다. 응답 헤더 `X-Export-Truncated: true`면 잘린 것입니다.
* 감사 로그 조회(읽기 전용):
  ```sql
  select occurred_at, actor, action, target, remote_addr from audit_logs order by id desc limit 50;
  ```
* `device_events`와 `audit_logs`는 DB 트리거로 수정·삭제가 막혀 있습니다(원시 데이터 불변).

## 7. 백업·복구
* **백업**: `make backup` → `backups/safetypad-<UTC>.dump.enc`(+`.sha256`). AES-256 암호화, 패스프레이즈는 `deploy/.backup-passphrase`(**백업과 다른 곳에 따로 보관**).
* **오프사이트 복사**는 승인된 도구로 직접 수행하세요(스크립트는 업로드·삭제를 하지 않습니다). 보존 기간은 운영 결정입니다.
* **복구 검증(정기, 권장 월 1회)**: `make restore-check FILE=backups/<파일>` — 별도 임시 DB로 복원하고 행 수·트리거·스키마 버전을 비교합니다. 운영 DB를 덮어쓰지 않습니다.
* **측정값(리허설)**: 이벤트 9,001건·장비 2,051대 DB(암호화 백업 664KB)에서 백업 약 1초, 복구 1초 미만. **이 숫자는 소규모 로컬 환경의 것이며 RTO/RPO 보증이 아닙니다.** 실제 환경에서 다시 측정하세요(`PERFORMANCE_REPORT.md`).
* **실제 복구 절차(DB 손상 시)**
  1. `docker compose -p safetypad-prod stop web backend`(기기는 5xx/타임아웃을 받고 **자체 큐로 재시도**)
  2. 최신 정상 백업으로 새 DB에 복원: `scripts/restore.sh <파일> safetypad_new`
  3. 검증 후 `DB_NAME=safetypad_new`로 `deploy/.env.prod` 수정 → `up -d`
  4. `make prod-smoke` 통과 확인. 백업 시점 이후의 이벤트는 기기 재전송으로 보충됩니다(기기 큐가 허용하는 범위 내).
* **RPO**는 백업 주기(최악의 경우 마지막 백업 이후 전부)입니다. 목표값은 `OPEN_DECISIONS.md` C-13.

## 8. 비밀 값 관리
| 값 | 용도 | 유출 시 | 보관 |
|---|---|---|---|
| `DB_PASSWORD` | DB 접속 | DB 노출 → 교체 후 재기동 | 비밀 저장소 |
| `DEVICE_KEY_PEPPER` | 키 해시 | 단독으로는 키를 못 만들지만, DB와 함께 유출되면 키 검증 위조 가능 → 전체 키 재발급 | 비밀 저장소, **분실 시 모든 키 재발급** |
| `ADMIN_BOOTSTRAP_*` | 첫 관리자 | 사용 후 삭제 | — |
| `METRICS_PASSWORD` | 메트릭 | 교체 | 비밀 저장소 |
| 백업 패스프레이즈 | 백업 복호화 | 백업 재암호화 | 백업과 분리 |
`deploy/.env.prod`는 커밋 금지(`.gitignore`로 제외). 권한 600.

## 9. 장애 대응
| 증상 | 확인 | 조치 |
|---|---|---|
| 기기가 5xx/타임아웃을 받음 | `docker compose ps`, backend 로그 | DB/백엔드 복구. 기기는 같은 `event_id`로 재시도하므로 유실·중복 없음 |
| 503 증가 | backend 로그의 `Database failure` | DB 컨테이너·디스크 확인, 연결 수 확인 |
| 401 급증 | 키 폐기/교체 이력(감사 로그) | 기기 키 반영 누락 여부 확인 |
| 429 | 메트릭의 429 수, 기기 수 | 정상 기기가 막히면 `INGEST_PER_KEY_PER_MINUTE`/`INGEST_PER_IP_PER_MINUTE` 상향. LTE 공용 NAT 때문에 IP 한도가 걸릴 수 있음 |
| 화면이 비어 있음 | 브라우저 콘솔, `/actuator/health` | 로그인 만료(401)면 재로그인. 데이터가 없으면 정상(“수신 없음” 안내) |
| 인증서 오류 | `docker compose logs web` | 80/443 개방·DNS 확인(ACME는 80/443 필요). Caddy 데이터 볼륨(`caddy_data`)을 지우지 말 것 |
| 모든 관리자 잠김/분실 | — | 서버는 “관리자가 0명일 때만” `ADMIN_BOOTSTRAP_*`로 첫 관리자를 만듭니다(이 코드 경로는 자동 테스트됨). ① 작업 내역을 기록하고 백업 ② `delete from admin_users;`(감사 로그는 별도 테이블이라 보존됨) ③ `deploy/.env.prod`에 `ADMIN_BOOTSTRAP_USERNAME/PASSWORD` 설정 후 backend 재시작 ④ 로그인 → 비밀번호 변경 → `.env`에서 비밀번호 삭제. *이 절차 자체의 리허설은 아직 하지 않았으니 스테이징에서 한 번 연습하세요.* |
| 디스크 부족 | `docker system df`, `du` | 오래된 백업을 승인된 보존 정책에 따라 이동. **`device_events`는 삭제 금지**(트리거가 막음) → 볼륨 확장 |

## 10. 업그레이드
1. 백업(`make backup`) → 2. 새 소스 반영 → 3. `docker compose ... up -d --build` (Flyway가 시작 시 마이그레이션 적용) → 4. `make prod-smoke`.
* 마이그레이션은 앞으로만 적용됩니다. 롤백은 **이전 이미지 + 백업 복원**으로 하세요(스키마를 되돌리는 마이그레이션은 제공하지 않음).
* 데모 데이터 기능은 운영 프로파일에 존재하지 않습니다. 운영 DB에 가상 데이터를 넣지 마세요.

## 11. 용량 감각 (측정 기반, 이벤트 발생 빈도는 가정)
* 이벤트 1건당 약 **616바이트**(테이블+인덱스, 9천 건 표본). 예: 1,000대 × 하루 10건 = 1만 건/일 ≈ 6MB/일 ≈ 2GB/년.
* 실제 발생 빈도를 알면 위 식으로 다시 계산하세요. 보존 기간이 확정되기 전에는 자동 삭제를 하지 않으므로 디스크가 계속 증가합니다.
