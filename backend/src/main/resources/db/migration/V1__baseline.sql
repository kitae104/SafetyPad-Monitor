-- Phase 0 baseline. Domain tables (devices, device_credentials, device_events) arrive in Phase 1.
-- All instants are timestamptz; display conversion to Asia/Seoul happens at the edge.
CREATE TABLE app_schema_info (
    id          smallint PRIMARY KEY CHECK (id = 1),
    created_at  timestamptz NOT NULL DEFAULT now()
);
INSERT INTO app_schema_info (id) VALUES (1);
