# 아키텍처

```text
[안전발판 + LTE/LTE-M, 다른 업체] -- HTTPS POST /api/v1/device-events --> [TLS proxy] --> [Spring Boot modular monolith]
                                                                           |-- Devices (API Key authentication)
                                                                           |-- Ingestion (validation, idempotent insert)
                                                                           |-- Location (optional independent GPS ping)
                                                                           |-- Events (filter/export)
                                                                           |-- Analytics (summary)
                                                                           |-- Admin (session auth)
                                                                           |-- Audit & Ops
                                                                                           |
                                                                                      [PostgreSQL]
[Browser: React responsive SPA] <-------------- HTTPS REST -----------------------------|
```

## DB design outline
- `devices`: id PK, device_id UNIQUE, alias, active, last_received_at, last_battery_level, created_at
- `device_credentials`: id PK, device_id FK, key_digest UNIQUE, key_prefix, active, issued_at, expires_at, revoked_at
- `device_events`: id PK, device_id FK, event_id NOT NULL, event_type, event_time TIMESTAMPTZ NOT NULL, received_at TIMESTAMPTZ NOT NULL, gps_lat NUMERIC(9,6) NULL, gps_lon NUMERIC(9,6) NULL, battery_level SMALLINT NULL, raw_payload JSONB, UNIQUE(device_id,event_id), indexes(device_id,event_time DESC), (received_at DESC)
- `device_locations` (optional ingestion phase): id PK, device_id FK, recorded_at TIMESTAMPTZ, received_at TIMESTAMPTZ, lat/lon; index(device_id,recorded_at DESC); idempotency key per sample needed when implemented
- `admin_users`, `audit_logs`: least privilege with immutable append-only audit entries; do not expose secret hashes

## Route policy
- Base map: only violation event markers (clustered); GPS nullable. Don't label as live location.
- Device lookup: most recent GPS known plus '마지막 수신 N시간 전'.
- Route only when there are >=2 independent GPS pings for the selected device/date; display straight segments between sampled points, NOT actual street navigation, and visually indicate large gaps.
- Reject lat outside [-90,90] / lon outside [-180,180], guard impossible timestamp ranges; avoid pretending perfect GPS accuracy.

## Security/operations
- Route only /api/v1/device-events for key auth; browser admin uses distinct session boundary.
- Use high entropy randomly generated per-device keys, keyed digest storage, constant-time comparison and rotation workflow. Never embed demonstration secrets.
- TLS proxy, DB in private network, secrets env or managed secret store, log redaction, rate limit, request size cap, input validation.
- First received_at must be recorded on initial accepted arrival and never changed on retries; measure per-message lag from timestamps but device RTC skew must be flagged.
- UTC instants and local KST rendering; CSV UTF-8 BOM if Korean Excel required, CSV formula injection sanitization.
- Single EC2 dev/pilot acceptable with encrypted backup to independent storage; document RPO/RTO and test restore. Production upgrade to RDS and multiple app instances as capacity grows.
