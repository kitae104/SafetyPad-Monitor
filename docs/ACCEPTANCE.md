# 검수 기준 대응표

PRD 검수 기준과 `IMPLEMENTATION_PLAN.md`의 Phase별 수용 기준이 **무엇으로 확인되었는지**를 정리합니다. “미확인”은 숨기지 않고 적었습니다.

## PRD 검수 기준
| 기준 | 결과 | 확인 방법 |
|---|---|---|
| 등록 장비만 POST 성공, device_id/키 불일치 거부 | ✔ | `authenticationFailures`, `keyOfAnotherDeviceIsForbidden`, 스모크 |
| 동일 이벤트 동시 재전송에도 1건, 최초 수신 시각 불변 | ✔ | `concurrentIdenticalPostsInsertExactlyOnce`(20스레드), `tenRetries…`, 1,000대 부하 |
| 시간대 Asia/Seoul 표시, DB는 timestamptz | ✔ | `dateFilterUsesSeoulCalendarDaysAndOutputIsKst`, `summaryCountsDaysInSeoulTime…`, UI는 서버 제공 `period` 사용 |
| 위치 없는 이벤트는 표에 남고 지도에서 제외 | ✔ | `mapEventsExcludeNullGpsButCountThem`, E2E(“지도에 표시하지 않습니다”) |
| 1개 장비 선택 시 타 장비 경로 숨김, 전체는 마커+클러스터 | ✔ | E2E(경로는 선택+토글 때만, 장비 전환 시 사라짐), 클러스터 불변식 단위 테스트 |
| 360px에서 수평 스크롤 강제 없음 | ✔ | 모든 페이지·지도 E2E의 `scrollWidth` 검사 |
| 시뮬레이터/실제 API 통합 테스트, Docker 재현, 저장소에 비밀 없음 | ✔ | Testcontainers 54건, `scripts/simulate_ingest.py`, 운영 스택 구동. 비밀은 `.env*`(gitignore) · 예시 파일은 `CHANGE_ME` 자리표시자 |

## Phase별 수용 기준
| Phase | 기준 | 결과 |
|---|---|---|
| 0 | 백엔드 health + 프런트 + Postgres 로컬 구동 | ✔ |
| 1 | 가짜 데이터 POST, 키·장비 불일치 실패, 재전송 duplicate·count=1 | ✔ (`IngestionIT` 14건, 에뮬레이터 E2E) |
| 2 | 비인가 차단, CSV 수식 무력화, 시간대, 필터 정확성 | ✔ (`AdminSecurityIT`, `AdminQueryIT`, `DemoFixtureIT`) |
| 3 | 1440/768/360 스크린샷 + Playwright 탐색 | ✔ (`docs/screenshots/`, E2E 22개 시나리오 × 3폭 = 66) |
| 4 | null GPS는 지도 제외·표 유지, 희소 위반으로 경로 미생성, 장비 선택으로 혼잡 방지, 운영 경로 플래그 OFF | ✔ (`MapRouteIT`, `RouteDisabledIT`, E2E) |
| 5 | 재현 가능한 배포, 로그/메트릭, 운영 시드 없음, 스모크, 운영 매뉴얼 | ✔ 로컬 / ⚠ **AWS 실배포는 승인 전이라 미수행** |

## 자동 테스트 현황
* 백엔드 54건: 수신 14, 관리자 보안 8, 조회·CSV·감사 10, 계정 관리 6, 지도·경로 6, 가상 데이터 1, 운영 프로파일 2, 레이트 리밋 1, 메트릭 2, 경로 비활성 1, 헬스 2, 설정 1.
* 프런트 단위 26건(포맷, API 헬퍼, UI 컴포넌트, 지도 기하·클러스터 불변식, 지도 요약), Playwright 63건(E2E는 키 없는 지도로 고정, 실제 지도는 `E2E_REAL_MAP=1`).
* 실행: `make check`(백엔드 + 프런트 정적/단위/빌드), `make e2e`(백엔드 기동 후).

## 아직 확인하지 못한 것 (정직한 목록)
* AWS 환경에서의 배포·백업·부하 — 계정/승인 없음.
* 실제 장비(펌웨어)와의 연동 — 장비 미도착. 에뮬레이터로만 검증.
* 접근성 자동 감사(axe), 스크린리더 실사용 검증.
* 백엔드 의존성 취약점 스캔, 외부 침투 테스트.
* 실제 지도 SDK(키 없는 대체 지도만 구현).
