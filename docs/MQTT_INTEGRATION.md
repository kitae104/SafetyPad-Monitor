# MQTT 수신 규격 (기기 ↔ 서버)

기기가 REST(`POST /api/v1/device-events`) 대신 **MQTT**로 위반 이벤트를 보내는 방법입니다.
서버 안에 **내장 브로커(Moquette, MQTT 3.1.1)** 가 있고, 저장 규칙은 REST와 **완전히 동일**합니다(같은 코드 경로).
기본값은 **꺼짐**이며, 켜지 않으면 서버는 REST만 받습니다.

> 두 방식은 병행할 수 있습니다. 같은 `(device_id, event_id)`는 어느 경로로 오든 1건만 저장됩니다.

## 1. 한눈에 보기

| 항목 | 값 |
|---|---|
| 프로토콜 | MQTT 3.1.1 (MQTT 5 미지원) |
| 접속 | 운영: **TLS 8883** 만 허용. 개발: 127.0.0.1:1883 평문 허용(개발 전용) |
| Client ID | **`device_id` 와 동일** (다르면 접속 거부) |
| Username | `device_id` |
| Password | 기기 **API 키** (REST의 `X-API-Key`와 같은 키) |
| 발행 토픽 | `safetypad/devices/<device_id>/events` (QoS 1) |
| 구독 토픽 | `safetypad/devices/<device_id>/acks` (QoS 1) |
| 페이로드 | REST 본문과 **동일한 JSON** (UTF-8, 최대 8 KiB) |
| Clean session | true 권장 (서버는 브로커에 메시지를 저장하지 않음) |

## 2. 가장 중요한 규칙: "저장 확인(ack)"을 받기 전에는 지우지 마세요

MQTT의 PUBACK은 **"브로커가 바이트를 받았다"** 는 뜻일 뿐, 저장 완료가 아닙니다.
서버는 이벤트를 DB에 **커밋한 뒤에만** `…/acks` 토픽으로 결과를 보냅니다(REST의 200 응답과 같은 의미).

```
기기 ──PUBLISH events (event_id=E1)──▶ 서버(검증·DB 커밋)
기기 ◀──PUBLISH acks {"success":true,"event_id":"E1","duplicate":false}──
```

- `success:true` 를 받으면 그 이벤트를 지워도 됩니다.
- ack가 없거나 `status`가 429/5xx이면 **같은 `event_id`로 다시 발행**하세요. 재전송은 안전합니다(`duplicate:true`로 확인만 되고 최초 `received_at`은 바뀌지 않음).
- 그러므로 **`event_id`는 기기가 만든 안정적인 고유 ID**여야 합니다(REST와 동일한 요구, `docs/DECISIONS.md` 블로커 참고).

## 3. 페이로드 (REST와 동일)

```json
{
  "event_id": "PAD-0001-20261118-000123",
  "device_id": "PAD-0001",
  "event_type": "MULTI_RIDE",
  "event_time": "2026-11-18T22:46:35+09:00",
  "gpsData": { "latitude": 37.451448, "longitude": 126.651542 },
  "battery_level": 72
}
```
`gpsData`는 `null`/생략 가능. `event_time`은 타임존이 포함된 ISO-8601. 필드 의미·검증 범위는 `contracts/openapi.yaml`과 같습니다.

## 4. ack 메시지

성공:
```json
{ "success": true, "event_id": "PAD-0001-20261118-000123", "duplicate": false }
```
실패(HTTP 상태와 같은 의미의 `status`):
```json
{ "success": false, "event_id": "…", "status": 422, "error": "UNPROCESSABLE", "message": "…" }
```

| status | 뜻 | 기기 동작 |
|---|---|---|
| 400 | JSON이 아니거나 필수 필드 누락 | 재시도해도 소용없음. 페이로드 수정 |
| 401 | 키가 폐기/만료됨(연결도 곧 끊김) | 새 키로 재접속 |
| 403 | 페이로드 `device_id`가 로그인한 장비와 다름 / 장비 비활성 | 설정 확인, 운영자에게 문의 |
| 413 | 페이로드 8 KiB 초과 | 줄여서 전송 |
| 422 | 값 오류(위경도 범위, 시각 범위 등) | 페이로드 수정 |
| 429 | 속도 제한(키당 분당 120건, REST와 공유) | 잠시 후 **같은 event_id로** 재전송 |
| 503 / 500 | 서버 일시 장애 | 잠시 후 **같은 event_id로** 재전송 |

`event_id`가 JSON 오류 등으로 읽히지 않으면 ack에서 `event_id`가 빠질 수 있습니다.

## 5. 접속·권한 규칙 (보안)

- 로그인은 REST와 같은 키 저장소(해시)로 검증합니다. **키 교체, 폐기, 장비 비활성화가 그대로 적용**되며, 연결 중에도 **메시지마다 다시 확인**합니다(폐기된 키는 다음 메시지에서 401 후 연결 종료).
- 장비는 **자기 토픽만** 사용할 수 있습니다. 다른 장비의 토픽 발행/구독, 와일드카드(`#`, `+`) 구독은 거부됩니다.
- 익명 접속 불가. 같은 사용자명으로 로그인 실패가 분당 20회를 넘으면 그 사용자명은 잠시 거부됩니다(무차별 대입 방지).
- 운영에서는 TLS(8883)만 열립니다. 평문 포트는 개발 환경(`dev` 프로파일)에서만 열 수 있습니다.
- 페이로드·키는 로그에 남기지 않습니다. 브로커의 외부 텔레메트리·영속 저장은 꺼져 있습니다.

## 6. 서버 켜기

### 6.1 로컬 개발
`.env`에 `MQTT_ENABLED=true` 를 넣고 `./run.sh restart backend`.
운영 설정 화면의 "MQTT 수신"에 `평문 1883 (개발 전용)`이 표시되면 열린 것입니다.

에뮬레이터로 시험:
```bash
# 키 파일: .dev-secrets/PAD-0001.key (REST 에뮬레이터와 같은 파일)
python3 scripts/simulate_mqtt.py --device PAD-0001 --count 3 --retry
#  mqtt-…  try1: success=True duplicate=False
#  mqtt-…  try2: success=True duplicate=True
```
(MQTTX, mosquitto_pub 등 일반 MQTT 클라이언트로도 같은 방식으로 시험할 수 있습니다.)

### 6.2 운영 (TLS)
1. 브로커용 인증서를 **PKCS12 키스토어**로 준비합니다. 기기가 접속할 호스트 이름(예: `mqtt.example.com`)이 인증서에 포함되어야 합니다.
   ```bash
   openssl pkcs12 -export -in fullchain.pem -inkey privkey.pem -out keystore.p12 -name safetypad
   chmod 600 keystore.p12
   ```
2. 서버의 `deploy/mqtt-keystore/keystore.p12`에 두고(저장소에 커밋 금지), `deploy/.env.prod`에 설정:
   ```
   MQTT_ENABLED=true
   MQTT_KEYSTORE_PASSWORD=<키스토어 비밀번호>
   MQTT_PUBLISH_BIND=0.0.0.0      # 외부 접속 허용. 방화벽/보안그룹에서 8883 허용 필요
   ```
3. `make prod-up` 후 운영 설정 화면에서 `TLS 8883`을 확인합니다.
4. 인증서 갱신(예: Let's Encrypt 90일) 후에는 키스토어를 다시 만들어 교체하고 백엔드를 재시작하세요. **자동 갱신은 구현되어 있지 않습니다.**

TLS 없이 `MQTT_ENABLED=true` 로 운영 프로파일을 시작하면 서버는 **기동을 거부**합니다(평문 노출 방지).

## 7. 운영 시 알아둘 점 / 한계

- **서버 안의 브로커**입니다. 서버 재시작 중에는 기기가 접속하지 못하며, 이때 발행한 메시지는 ack가 오지 않으므로 기기가 재전송해야 합니다(위 2절).
- 브로커 세션은 메모리에만 있습니다(서버 재시작 시 구독 정보 초기화 → 기기는 재접속 후 다시 구독).
- 서버 인스턴스를 여러 대로 늘리면 각자 브로커를 갖습니다. 그 단계에서는 외부 브로커(Mosquitto/EMQX 등) 전환을 검토하세요(`EventIntake`는 전송 방식과 무관하게 재사용 가능).
- 속도 제한은 인메모리입니다(REST와 동일한 한계).
- MQTT 5, 보존 메시지(retain), 유언(LWT) 기반 온라인 표시는 지원하지 않습니다. 기기 상태는 계속 **"최근 수신"** 으로만 표시합니다.
- 메트릭: `safetypad_mqtt_messages_total{result=accepted|duplicate|rejected}`, `safetypad_mqtt_logins_total{result=…}` (Prometheus, Basic 인증 뒤).

## 8. 펌웨어 업체 체크리스트

- [ ] MQTT 3.1.1, TLS 1.2 이상, 서버 인증서 검증 지원
- [ ] Client ID·Username = `device_id`, Password = API 키
- [ ] `…/acks` 구독 후 발행(QoS 1), **success ack 수신 전 이벤트 보관**
- [ ] 같은 이벤트 재전송 시 `event_id` 불변
- [ ] 429/5xx 시 지수 백오프 재전송
- [ ] 8 KiB 이하 페이로드
