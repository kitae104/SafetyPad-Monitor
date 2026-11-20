# Claude Code 마스터 시작 프롬프트 (전체 복사해서 입력)

당신은 20년 경력의 IoT 관제·Java 백엔드·React 프런트엔드·클라우드 운영 엔지니어입니다. 지금 폴더는 SafetyPad Monitor 프로젝트입니다. 이 프로젝트를 실제 실행 가능한 형태로 단계적으로 완성하세요.

먼저 `README.md`, `CLAUDE.md`, `docs/PRD.md`, `docs/ARCHITECTURE.md`, `docs/UI_SPEC.md`, `docs/IMPLEMENTATION_PLAN.md`, `docs/DECISIONS.md`, `contracts/openapi.yaml` 및 `data/` 샘플을 확인하고 기술적 충돌·미확정 사항을 식별하십시오. 명백한 미확정은 `docs/DECISIONS.md`에 가정/의존성으로 기록하고, 확인되지 않은 펌웨어 동작을 사실로 구현하지 마십시오.

**지금부터 Phase 0을 실제로 수행**하세요. 백엔드는 Java 21 + Spring Boot 4.1.x + Spring Security + PostgreSQL + Flyway, 프런트는 React 19 + TypeScript + Vite + Tailwind 4, 로컬 환경은 Docker Compose로 구성합니다. 역할에 맞는 모듈을 분리하고 OpenAPI 규격을 먼저 기준으로 삼으세요. 추후 수천 대 확장이 가능하도록 설계하되 Kafka/Kubernetes 등은 도입하지 마세요.

통신은 오직 `HTTPS REST POST`입니다. MQTT 구현을 시작하지 마세요. 기기별 API Key와 device_id 대조, event_id 멱등 처리, 서버 수신 시각의 최초 값 보존, GPS nullable 처리, 시간대 정확성, 관리자 별도 인증, 원시 데이터 삭제/수정 금지, CSV 안전 처리, 백업과 장애 대응을 반드시 고려하세요. 최초 요청 성공은 DB 커밋 후에만 반환하세요.

대시보드는 이전에 선택한 **1번 블루·화이트 SaaS 기업형 시안**이 기준입니다. 좌측 네비게이션, KPI, 이벤트 조회/검색/CSV, 큰 지도와 선택 장비 상세, 차트, 모바일 카드형 이벤트 목록으로 구성합니다. 기본 지도에는 위반 이벤트 마커만 보이고, 특정 장비를 선택할 때만 그 장비의 이동 경로가 나타나야 합니다. 이동 경로는 실제 주기 GPS 핑 데이터가 있을 때만 표시하며, 위반 GPS만으로 경로를 지어내지 마세요. 주기 GPS 데이터는 **데모 및 확장 규격**이고, 실제 기기에서는 별도 승인 전 기능을 비활성화하세요.

`data/devices.json`, `data/events.json`, `data/gps_locations.json`는 실제 인하공전 주변 장비 데이터가 아니라 테스트용 가상 기록입니다. 개발 환경에서만 시드하고 전체 데모 화면에 `가상 데이터` 표시를 넣으세요. 실제 지도 SDK API 키가 없으면 지도 어댑터 + 키 없는 대체 UI를 구현하세요. API/DB를 연결하기 전 임시 mock 화면을 만드는 것은 허용하되 최종 결과에서는 실제 서버 API에 연결하세요.

Phase 0이 끝나면 실행 명령어와 테스트 결과를 보여주고 Phase 1을 순서대로 진행하세요. 이후 Phase 2~5까지 문서의 검수 기준을 만족시키세요. 각 단계마다 빌드/테스트/린트·스크린샷 검증을 수행하고, 문제는 스스로 수정하세요. 실제 AWS 접근, 외부 서비스 결제, 비밀 키 생성·공개 등은 사용자의 명시적 승인 전에는 수행하지 마세요. 파일을 실제로 생성/수정해야 하며 설명만 하고 끝내지 마세요.
