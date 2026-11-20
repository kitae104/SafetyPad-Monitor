# AWS 배포 가이드 (초안 — 미실행)

> **이 문서의 어떤 명령도 아직 실행하지 않았습니다.** AWS 계정·자격 증명·비용 승인이 없으므로 AWS에는 접근하지 않았습니다.
> 아래 절차는 로컬에서 검증한 운영 스택(`deploy/docker-compose.prod.yml`)을 그대로 단일 EC2에 올리는 것을 기준으로 합니다.
> 값이 `<…>`인 곳은 운영자가 결정해야 하며, 결정이 필요한 항목은 `OPEN_DECISIONS.md` C절에 모아 두었습니다.

## 1. 시작 전 체크리스트 (소유권·권한)
- [ ] AWS 계정 **소유자(청구 주체)**와 운영 담당자 확정, 루트 계정 MFA 활성화, 루트 키 미사용
- [ ] 배포용 IAM 사용자/역할(최소 권한): EC2·EBS·VPC·Route 53(또는 외부 DNS 관리 권한)·S3(백업 버킷)·CloudWatch·SSM
- [ ] 리전(예: 서울 `ap-northeast-2`) 결정, 월 예산·알림(AWS Budgets)
- [ ] 도메인과 DNS 관리 주체 확정 (예: `monitor.<회사 도메인>`)
- [ ] 목표 **RPO/RTO**, 백업 보존 기간, 알림 수신처
- [ ] 장비 업체에 알릴 정보: 도메인, TLS 요구, 연동 런북
- [ ] 개인정보·위치정보 처리 방침 확정 여부(미확정이면 자동 삭제 없이 시작함을 문서화)

## 2. 권장 구성 (파일럿: 약 50대, 관리자 5명)
```
Route 53 / 외부 DNS ── A 레코드 ─> Elastic IP ─> EC2 (Ubuntu LTS, Docker)
                                                   └ docker compose: caddy(80/443) → backend → postgres
EBS(암호화) ─ pgdata / caddy_data
S3(암호화·버전 관리·퍼블릭 차단) ← 암호화된 백업 업로드
CloudWatch Logs/Alarms ← 컨테이너 로그, 호스트 지표
```
* 인스턴스 크기는 **`PERFORMANCE_REPORT.md` §4처럼 목표 인스턴스에서 부하 시험을 한 뒤** 결정합니다(소형으로 시작해 측정 후 조정).
* 규모가 커지면: DB를 RDS(PostgreSQL 16)로 분리하고 백엔드를 여러 대로 늘립니다. 이때 속도 제한(인메모리)과 프록시 구성을 재검토해야 합니다(`SECURITY_AUDIT.md` §5). Kubernetes·Kafka는 도입하지 않습니다.
* ALB를 앞에 두는 경우: ALB가 TLS를 종단하면 Caddyfile의 `servers { trusted_proxies … }`에 ALB 대역을 넣고, 백엔드의 `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES`를 점검하세요(클라이언트 IP가 속도 제한에 쓰임).

## 3. 네트워크·보안
* 보안 그룹 인바운드: **80, 443만 전체 허용**. SSH(22)는 관리자 고정 IP만(가능하면 SSM Session Manager로 SSH 폐쇄). DB(5432)·백엔드(8080)는 공개하지 않음(Compose가 이미 내부 네트워크만 사용).
* EBS 볼륨 암호화, IMDSv2 필수, 호스트 자동 보안 패치(`unattended-upgrades`).
* 비밀 값(`DB_PASSWORD`, `DEVICE_KEY_PEPPER`, `ADMIN_BOOTSTRAP_*`, `METRICS_PASSWORD`, 백업 패스프레이즈)은 SSM Parameter Store(SecureString) 또는 Secrets Manager에 두고, 배포 시 `deploy/.env.prod`로 내려받아 권한 600으로 저장. **저장소·AMI·이미지에 넣지 않음.**

## 4. 배포 절차
1. EC2 생성 → Elastic IP 연결 → DNS A 레코드 설정 → 전파 확인(`dig <도메인>`).
2. Docker 설치, 소스 반영(승인된 방식), `make prod-env`로 `deploy/.env.prod` 생성 후 값 입력:
   `DOMAIN=<도메인>`, `HTTP_PORT=80`, `HTTPS_PORT=443`, 비밀 값, `ADMIN_BOOTSTRAP_USERNAME/PASSWORD`.
3. `make prod-up` — Let's Encrypt 인증서는 80/443이 열려 있으면 자동 발급됩니다. `docker compose -p safetypad-prod logs web`에서 `certificate obtained` 확인.
4. `BASE_URL=https://<도메인> CURL_OPTS= make prod-smoke` → **전부 PASS** 확인(공인 인증서이므로 `-k` 없음).
5. 첫 로그인 → **비밀번호 변경** → `deploy/.env.prod`에서 `ADMIN_BOOTSTRAP_PASSWORD` 삭제 → 관리자 추가.
6. 장비 등록 → 키 발급 → 업체에 안전 전달 → 연동 런북 §7 시험.
7. 백업 크론 등록(아래 §5), 알림 설정(§6), 복구 리허설 1회 수행·시간 기록.
* **운영에는 `ROUTE_ENABLED=false`, `SIMULATED_DATA_ENABLED=false`가 고정**입니다(Compose에 하드코딩). 운영에 가상 데이터를 넣지 않습니다.

## 5. 백업 (자동화 예시 — 승인 후)
```bash
# 매일 03:10 KST 백업 (호스트 크론). 패스프레이즈 파일은 백업 대상과 다른 곳에 보관할 것.
10 3 * * *  cd /opt/safetypad && make backup >> /var/log/safetypad-backup.log 2>&1
# 오프사이트 복사 예시 (버킷은 SSE-KMS, 버전 관리, 퍼블릭 차단. 인스턴스 역할에 PutObject만 허용)
aws s3 cp backups/ s3://<백업 버킷>/safetypad/ --recursive --exclude "*" --include "*.dump.enc*"
```
* 보존·삭제 정책은 **운영 결정**입니다. 스크립트는 파일을 지우지 않습니다. S3 수명 주기 규칙으로 처리하세요.
* 월 1회 `make restore-check FILE=...`로 복구를 검증하고 소요 시간을 `OPERATIONS_MANUAL.md`에 기록합니다.

## 6. 모니터링·알림 (권장)
* 컨테이너 로그를 CloudWatch Logs로 수집(JSON 로그, `request_id`로 검색). 키·쿠키는 로그에 없도록 설계됨.
* CloudWatch 알림: EC2 상태 검사, 디스크 80%, 메모리, CPU.
* 애플리케이션 지표는 내부 Prometheus 엔드포인트(Basic 인증)로 수집. 알림 조건은 `OPERATIONS_MANUAL.md` §3.
* 외부 감시: `https://<도메인>/actuator/health`를 1분 간격으로 확인(Route 53 Health Check 또는 업타임 서비스).

## 7. 롤백
* 앱 이미지 롤백: 이전 소스로 `up -d --build`. 스키마가 앞으로 진행된 경우 **이전 버전 앱이 새 스키마와 호환되는지** 먼저 확인하고, 불가하면 업그레이드 직전 백업으로 복구(`OPERATIONS_MANUAL.md` §7, §10).
* 인스턴스 장애: 새 EC2에 동일 절차로 올리고 최신 백업을 복구, Elastic IP 재연결. 기기는 재시도로 유실을 줄입니다.

## 8. 수용 기준 (Phase 5)
| 기준 | 현재 상태 |
|---|---|
| 재현 가능한 배포 | ✔ 로컬 운영 스택으로 검증(`make prod-up`→스모크 29/29). **AWS에서는 미검증** |
| 로그·메트릭 | ✔ JSON 로그, Prometheus 지표(내부) / 수집·알림 연결은 AWS에서 설정 필요 |
| 운영 시드 없음 | ✔ 운영 프로파일에 데모 빈 없음, 스모크 확인 |
| 스모크 테스트 | ✔ `scripts/smoke.sh` |
| 운영 매뉴얼 | ✔ `OPERATIONS_MANUAL.md` |
| 백업/복구 리허설 | ✔ 로컬 수행 / **AWS 환경에서 재수행 필요** |
| 50/1,000대 부하 | ✔ 로컬 수행 / **목표 인스턴스에서 재측정 필요** |
