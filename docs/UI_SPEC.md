# 화면 및 디자인 지침 — 1번 시안

Design system: corporate blue/white, white canvas #F8FAFC, brand #2563EB, dark sidebar #163B72, slate gray text #1E293B, cyan blue highlights, subtle rounded-xl cards, icon library Lucide. Clear typographic hierarchy, no glass/neon/dark default. Font Pretendard or Noto Sans KR via installed/common CDN fallback; do not bundle licensed fonts.

## Navigation
대시보드 / 위반 이벤트 / 장비 관리 / 위치·경로 / 통계 리포트 / 운영 설정. Desktop fixed/collapsible left sidebar, mobile top bar + drawer/bottom primary navigation.

## Dashboard content layout
- Header: title, selected period, search device ID, CSV action, signed-in admin.
- KPI 4 cards: 등록 장비 수, 오늘 위반 건수, 최근 24시간 수신 장비, 배터리 주의 장비. NEVER claim online without heartbeat.
- Main row (2/3 + 1/3): large map with clustered *violation* points; selected device information including last location and timestamp.
- Secondary row: 7-day event line chart and device-wise top violations bars or delayed reception summary.
- Bottom: recent violations table `device_id / event_time / received_at / GPS / 배터리`, detail drilldown.
- All map data labeled `가상 데이터` in demo; dark map never default.

## Map UX (important)
- Default ALL: violation markers and clustering only. Do not draw trajectories of 50 devices.
- Device selected: selected device violations become highlighted. Optional '이동 경로 보기' only if real GPS pings available; draw only selected-device route.
- Show point type icon `위반` vs `정기 위치`, freshness badge `마지막 수신 시각`; never show stale point as current.
- Date range + selected device + route on/off; missing GPS rows listed, not pinned.
- Demo map centered on Inha Technical College around 37.451448,126.651542. Synthetic points are in a nearby envelope, not street-aligned.

## Mobile responsive
At 360px two-across KPI then map then selected device card then event cards, horizontal lists not forced; table transforms to cards. 768px 2-col, 1440px 4-col KPIs and full table. Touch controls 44px; accessible focus, contrast, keyboard support.

## Components to implement
AppShell, SidebarNav, TopBar, DateRangeFilter, DeviceIdSearch, SummaryMetricCard, ViolationMap, MarkerClusterLayer, DeviceDetailPanel, BatteryGauge, ViolationTrendChart, EventTable, EventMobileCard, CSVExportButton, LastSeenIndicator, RouteToggle, EmptyState, LoadingSkeleton, Toast, ConfirmDialog, AuthGate.

## Static draft available
`design/dashboard-preview.html` is a **visual mockup only** for first-pass visual direction; implement real React components and do not copy demo values into production code.
