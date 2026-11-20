# Claude Code project rules — SafetyPad Monitor

## Role
Act as a senior IoT backend and frontend engineer building an auditable, testable, maintainable Korean responsive dashboard. Execute one phase at a time and validate acceptance criteria.

## Immutable decisions
1. Primary ingress: `POST /api/v1/device-events` via HTTPS, JSON, per-device `X-API-Key`. Do NOT implement MQTT for Phase 1.
2. `event_time` is device-generated; `received_at` must be set by server on FIRST successfully accepted arrival and never overwritten by retries. All instants are timezone-aware; store as PostgreSQL `timestamptz` and display in Asia/Seoul.
3. `event_id` is strongly recommended device-supplied stable unique ID; unique `(device_id, event_id)`. If device maker cannot supply it, mark that as a BLOCKER for guaranteed deduplication and record a fallback only upon explicit agreement.
4. Idempotency must be atomic at DB constraint/transaction level. A retry of a previously committed event returns a successful duplicate acknowledgment without altering original fields.
5. Only return success once transaction commits. 400 malformed; 401 missing/invalid key; 403 key mismatched device; 422 semantic invalid; 500/503 transient server failure.
6. `gpsData` nullable. GPS event point is NOT a full travel route. Route requires independent timestamped GPS pings; derive line only from actual samples with a visible quality/freshness disclaimer. Never claim 'real-time' absent periodic telemetry.
7. Device dashboard state = last received info; no definitive online/offline without agreed heartbeat. Use label `최근 수신`, not unsupported online count.
8. Protect secrets, rate-limit ingestion, validate payload size and latitude/longitude, redact credentials from logs; device keys hashed using keyed digest or secure lookup hash and support rotation.
9. Admin auth separate from device API-key auth. Prefer server sessions with secure httpOnly cookies and CSRF protection for browser mutations. No hardcoded default production admin password.
10. Raw event data immutable via normal CRUD; log exports/admin changes. Respect retention/privacy decisions; never invent a legal retention period.
11. Simulated data only in demo/dev profile; NEVER auto-seed production. All demo records visibly marked `가상 데이터`.
12. UI style: enterprise light white/blue, dark blue side navigation, concise cards, map prominent, dense event table desktop and stacked cards mobile. Use documented React components; keep styles accessible.
13. Modular monolith first: backend Spring Boot 4.1.x Java21, React19 TypeScript Vite Tailwind4, PostgreSQL with Flyway. Docker Compose for local; AWS deployment via explicit scripts and docs; no Kubernetes or Kafka initially.
14. Do not use mock API on production path. Provide real DB integration; E2E using emulator before hardware delivery.

## Development protocol
- Read README, docs/*, contracts/openapi.yaml, then produce a short plan.
- Implement ordered phases listed in docs/IMPLEMENTATION_PLAN.md.
- After each phase run tests/build/lint; report commands, results, files modified and blockers.
- Never call external resources or modify AWS without explicit operator approval and credentials.
- Generate `.env.example`, no real secrets checked in.
- Do not change agreed API fields without synchronized OpenAPI, tests, simulator and change log edits.
- Maintain `docs/DECISIONS.md` as assumptions become confirmed.
