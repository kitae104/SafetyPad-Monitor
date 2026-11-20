# Decision log / unresolved questions

## Confirmed working assumptions
- External devices run firmware owned by another supplier.
- Server receives one-way JSON through HTTPS REST POST using per-device API Key.
- Fixed public IP may be assigned; use a DNS domain and valid TLS certificate to avoid hard-coding IP.
- Device event_time and server received_at separately stored.
- React responsive webapp not native mobile app; blue/white dashboard style 1.
- 50 devices near Inha Technical College **entirely simulated** for demo.

## Open / vendor sign-off required
1. Does firmware provide stable unique event_id? If not, reliable dedup contract cannot be assumed.
2. Batch POST vs single message; payload max; timeout/retry backoff.
3. GPS coordinate object fields, precision, null semantics.
4. Periodic GPS upload support and rates; optional endpoint separate from violations.
5. Battery presence/range; heartbeat and RTC drift policy.
6. Device key provisioning and rotation process.
7. Retention and location data legal basis; report format; admin roles.
8. AWS account, domain, target budget, RPO/RTO, deployment approval.
9. Original RFP listed MQTT alongside REST. **Superseded 2026-11-18**: MQTT was implemented as an optional ingress (see "MQTT 수신" below); REST remains the default.

## Phase 0 기술 결정 (2026-11-07 검증)
- Spring Boot **4.1.2**(Maven Central 기준 4.1.x 최신 GA), Java 21, Gradle 9.5.1 Kotlin DSL 래퍼 포함. 4.2.0은 RC이므로 미사용.
- Boot 4 모듈화 영향: 테스트에 `spring-boot-starter-restclient` 필요(`TestRestTemplate`), Testcontainers 2.x 아티팩트명 `testcontainers-*`.
- 프런트: React 19, Vite 8, Tailwind 4(@tailwindcss/vite), TypeScript 5.9(7.x는 도구 호환 확인 전까지 미사용), Vitest, ESLint.
- 로컬 DB 호스트 포트 5433(5432 충돌 회피). 운영 프로파일에서는 시뮬레이션 데이터 기본 비활성(`SIMULATED_DATA_ENABLED` 기본 false, dev 프로파일만 true).
- Phase 0 보안 기본값: `/actuator/health` 외 모든 경로 deny. 기기 API 키 필터(Phase 1)와 관리자 세션/CSRF(Phase 2)는 별도 체인으로 추가 예정.
- 폰트: Pretendard를 CDN(jsDelivr)으로 로드, 번들하지 않음. 오프라인 폴백은 Noto Sans KR/system-ui.

## Phase 1 구현 결정 (2026-11-07)
- **API 변경 로그 (OpenAPI 0.1.0 내 보강)**: 413(8192B 초과), 오류 본문 스키마 `Error{success,error,message,request_id}`, 429/503의 `Retry-After`, event_time 허용 구간(미래 10분 / 과거 366일, 설정 가능) 명시.
- 오류 코드 매핑: 401 키 없음/미등록/폐기/만료, 403 키↔device_id 불일치 또는 비활성 기기, 400 JSON 깨짐·필수 누락·미정의 필드, 422 범위/시간대/event_type/형식, 503 DB 오류(재시도 안전).
- `gpsData`: `null`, 생략, `""` 모두 "GPS 없음"으로 저장(이벤트는 보존). 객체는 `latitude`·`longitude` 숫자 필수, 범위 검사. (0,0) 거부 여부는 펌웨어 협의 필요(미결 #3).
- event_id 중복: 동일 `(device, event_id)` 재전송은 `duplicate:true`로 200 응답하고 최초 행·`received_at`을 변경하지 않음. 페이로드가 달라도 최초 값 유지(불일치 감지는 미구현).
- 키 저장: HMAC-SHA256(pepper) 다이제스트만 저장, 기기당 복수 활성 키(교체 중첩). pepper 교체는 전체 키 재발급을 의미하므로 별도 절차 필요. pepper 미설정/32자 미만이면 기동 거부(검증됨).
- 레이트 리밋은 단일 인스턴스용 인메모리 고정 윈도(IP 600/분, 키 120/분). 다중 인스턴스/프록시 뒤에서는 `X-Forwarded-For`를 신뢰하지 않으므로 프록시 구성 시 재검토.
- `device_events`: DB 트리거로 UPDATE 항상 거부, DELETE는 dev 데모 초기화 트랜잭션의 `safetypad.allow_demo_reset='on'`일 때만 허용.
- 로그: 요청 ID(서버 생성) + 접근 로그 한 줄. 헤더·본문 미기록. 로그에 키 접두사(8자)만 dev 프로비저너가 기록.
- 미검증: 503 경로(DB 장애 주입) 자동 테스트는 아직 없음. Swagger UI는 Phase 2에서 dev 한정 제공 예정(계약 파일은 `contracts/openapi.yaml`).

## Phase 2 구현 결정 (2026-11-07)
- **기본 프로파일은 운영 안전**: `spring.profiles.default: dev`를 제거. dev는 `SPRING_PROFILES_ACTIVE=dev`로 명시할 때만 켜지며, 데모 가져오기/초기화·키 프로비저너·Swagger UI·경로 플래그 기본 true는 dev 한정(운영 프로파일에서 빈 자체가 없음을 테스트로 검증).
- 관리자 인증: 서버 세션(`SPSESSION`, HttpOnly, SameSite=Lax, 운영 Secure) + CSRF 쿠키 토큰(`X-XSRF-TOKEN`). 기기 키는 관리자 API에 통하지 않음(테스트로 검증).
- 첫 관리자는 `ADMIN_BOOTSTRAP_USERNAME/PASSWORD`(12자 이상)로만 생성, 내장 기본 계정 없음. 로그인 쓰로틀은 **실패 횟수만** 계산(IP+아이디당 분당 10회).
- 역할: 현재 `ADMIN` 1종. 다중 역할은 확장 단계(미결 #9).
- 감사 로그: 로그인/실패·CSV 내보내기·장비 생성·키 발급/폐기·활성 변경·데모 가져오기/초기화. DB 트리거로 append-only.
- CSV: UTF-8 BOM, CRLF, 텍스트 셀 수식 무력화(`= + - @ TAB CR` 접두 시 `'` 추가), 최대 5만 행(초과 시 `X-Export-Truncated`), 시각 KST.
- 지연(lag)=최초 수신−기기 발생. 수신이 발생보다 60초 넘게 빠르면 `clock_skew_suspected`.
- 배터리 주의 임계값 20%(가정, 설정 `safetypad.low-battery-threshold`) — 미결 #5.
- **Phase 1 결함 수정**: 잘못된 Content-Type/405 등 프레임워크 4xx가 500으로 응답되던 문제를 415/405 등으로 수정(테스트 추가).

## Phase 4 구현 결정 (2026-11-07)
- 지도: 어댑터 경계(`ViolationMap`) 뒤에 **키 없는 SVG 대체 지도**만 구현. 실제 지도 SDK는 키·약관·과금 확인 후 추가(미결 #7). 대체 지도는 도로/건물이 없고 위치는 근사치임을 화면에 명시.
- 기본 지도: 위반 이벤트 마커(거리 기반 클러스터링). GPS 없는 이벤트는 표에 남고 지도에서는 제외하되 건수와 목록 링크를 표시.
- 경로: `device_locations`(독립 GPS 핑)만 사용, 위반 GPS로는 **절대** 만들지 않음. 핑 2개 미만이면 선을 그리지 않음. 간격 30분 초과 구간은 점선, "실제 도로 경로 아님" 문구 상시 표시. 장비 선택 + 토글 시에만 해당 장비 경로를 요청.
- 기능 플래그 `safetypad.route-enabled`: 기본 false(운영), dev 기본 true. 꺼져 있으면 API가 404(`ROUTE_DISABLED`), UI는 안내 문구 표시.
- 주기 GPS 수신 엔드포인트(`/api/v1/device-locations`)는 **구현하지 않음**(규격 미승인). 테이블과 데모 데이터만 존재.
- 수정한 UI 결함: 드래그용 포인터 캡처가 마커 클릭을 가로채던 문제, 격자 클러스터링이 단일 마커를 클러스터 아래에 가리던 문제(거리 기반으로 교체, 불변식 테스트), 360px 가로 오버플로.

## 지도 제공자: 카카오맵 (2026-11-08)
- `MAP_PROVIDER=kakao` + `MAP_API_KEY`(카카오 **JavaScript 키**, 도메인 허용 목록으로 보호되는 브라우저 공개용)일 때 `KakaoMap`을 사용한다. 그 외(키 없음, SDK/도메인 오류)는 `KeylessMap`으로 자동 대체한다.
- 키는 인증된 `GET /api/v1/admin/config`의 `map_js_key`로만 전달한다. REST/Admin 키는 절대 넣지 않는다.
- Caddy CSP에 `dapi.kakao.com`, `*.daumcdn.net`, `*.kakao.com` 허용을 추가했다.
- 경로선은 정기 GPS 핑(`route.sufficient`)으로만 그리며, 위반 지점끼리는 연결하지 않는다(불변 결정 6).

## 지도 가독성·사용성 개선 (2026-11-18)
- 문제: 지도 묶음의 숫자만으로는 안에 무엇이 있는지 알 수 없었다. 해결: ① 묶음 알약 "위반 N건" + 범례 상시 표시 ② 묶음 hover/focus 시 건수·장비 수·기간·장비별 상위 3대 카드 ③ 마커 클릭 시 상세 카드(발생/최초 수신/위치) 고정 ④ 위치·경로 화면에 **위반 목록**(장비별 순위 → 장비의 시각별 목록 → "지도에서 보기") ⑤ 기간 빠른 선택(서울 달력 기준) ⑥ 장비 선택 시 그 장비 지점에 맞춰 확대.
- 데이터 의미는 변하지 않는다: 새 API·필드 없음(`/map/events` 응답을 그대로 사용), 경로·실시간 표기 원칙(불변 6) 유지. 묶음 숫자는 "위반 건수"이며 장비 수가 아니다.
- E2E는 서버가 카카오 키로 설정돼 있어도 키 없는 지도를 검증하도록 `/admin/config` 응답을 테스트에서만 `mock`으로 바꾼다(`frontend/e2e/helpers.ts`).

## MQTT 수신 (2026-11-18)
- **승인**: 사용자가 MQTT 구현을 직접 요청함. CLAUDE.md 불변 결정 1번("Phase 1에서 MQTT 금지")은 이 요청으로 해제된 것으로 보고 구현했다. (CLAUDE.md 문구는 사용자가 직접 갱신하기 전까지 그대로 둔다.)
- **구조**: 백엔드 안에 내장 브로커(Moquette 0.17, MQTT 3.1.1). 모듈형 모놀리스 원칙 유지, 별도 컨테이너·브로커 인증 플러그인 없음. 외부 브로커(Mosquitto 등)는 다중 인스턴스 단계에서 재검토.
- **단일 경로**: REST와 MQTT는 `EventIntake`를 공유한다 → 검증(400/403/422), `(device_id,event_id)` 멱등, 최초 `received_at` 불변, 커밋 후에만 성공이 동일하게 적용된다. 저장 필드/REST 계약 변경 없음(OpenAPI 무변경).
- **불변 결정 5(커밋 후에만 성공)의 MQTT 해석**: PUBACK은 전송 확인일 뿐이므로, 커밋 후 `safetypad/devices/<id>/acks`로 결과를 발행하고 기기는 success ack 전까지 이벤트를 보관·재전송한다.
- **인증/ACL**: username=client id=device_id, password=기기 API 키(기존 해시 검증·교체·폐기 재사용). 장비는 자기 events 발행/acks 구독만 가능, 와일드카드·타 장비 토픽 거부, 메시지마다 키·장비 상태 재검증, 로그인 실패 제한, 익명 불가.
- **운영 안전장치**: 기본 꺼짐(`MQTT_ENABLED=false`). 운영 프로파일은 TLS 포트+키스토어가 없으면 기동 거부. 평문 1883은 `dev` 프로파일에서만. 브로커 텔레메트리/영속 저장 꺼짐. 운영 compose는 8883을 기본 127.0.0.1에만 게시.
- **미검증**: 공인 인증서(실서버)로 기기 접속(자체 서명 키스토어 TLS는 `MqttTlsIT`로 검증), 실제 펌웨어의 MQTT 클라이언트 호환, 대량(수백 대 동시 접속) 부하. 인증서 자동 갱신은 미구현.
