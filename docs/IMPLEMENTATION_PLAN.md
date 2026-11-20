# Claude Code phased implementation plan

Every phase must produce working code, tests, and a commit-sized report. Never skip tests because a previous phase passed.

## Phase 0 — contract and scaffold (Day 1)
- Read PRD and OpenAPI. Generate Java21 Spring Boot4.1.x Gradle/Maven backend, React19 TS Vite Tailwind4, Compose PostgreSQL. Validate versions from official docs before pinning.
- `.env.example`, Makefile or scripts, container local setup, README developer quickstart, Flyway skeleton.
- Acceptance: backend health + frontend + postgres all run locally with documented commands.

## Phase 1 — ingestion (Days 2–4)
- Flyway devices/device_credentials/device_events; event REST POST with key auth, DTO validation, database idempotent constraint/upsert, original received_at preservation, test duplicate concurrent requests.
- GET /health; Swagger/OpenAPI; structured logs with secret redaction and request IDs.
- Acceptance: fake data can be posted, invalid key/device mismatches fail, retry returns duplicate status and count=1.

## Phase 2 — admin API and authentication (Days 5–7)
- Session login/logout, authorization, devices + events API filtering, pagination, CSV, summary aggregates. Dev-only demo fixture import and explicit reset.
- Acceptance: unauthorized access denied, CSV formula sanitization, timezones tested, filters accurate.

## Phase 3 — dashboard UI (Days 8–11)
- Implement light blue (#2563eb), white UI from design/UI_SPEC.md; sidebar, four KPI cards, charts, recent event list; event search/filter/table CSV; device list & detail.
- Acceptance: 1440/768/360 layout screenshots and Playwright navigation coverage.

## Phase 4 — map and location (Days 12–14)
- Map adapter with no-key preview fallback; marker clustering, event GPS points only, selected-device detail and latest known position, optional dev-only route from sample gps_locations.
- Production route feature flag OFF until hardware GPS ping contract confirmed.
- Acceptance: null GPS excluded map not table; route not inferred from sparse violations; device selection prevents map clutter.

## Phase 5 — deploy and validation (Days 15–20)
- AWS account ownership and permissions checklist; HTTPS DNS; backup/restore rehearsal; audit; performance tests at 50/1,000 device synthetic traffic; security audit and firmware vendor integration runbook.
- Acceptance: reproducible deployment, logs/metrics, no production demo seed, smoke tests and operator manual.

## Explicit out-of-scope until approved
- MQTT broker, mobile native apps, bidirectional control, continuous live tracking absent device GPS pings, SMS notifications, multi-tenancy, automatic GPS-based legal enforcement, unverified police evidence certification.
