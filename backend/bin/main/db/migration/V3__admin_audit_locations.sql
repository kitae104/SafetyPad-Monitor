-- Phase 2: admin accounts (separate from device credentials), append-only audit log, optional GPS ping table.

CREATE TABLE admin_users (
    id            bigserial PRIMARY KEY,
    username      varchar(64)  NOT NULL UNIQUE,
    password_hash varchar(100) NOT NULL,
    role          varchar(20)  NOT NULL DEFAULT 'ADMIN' CHECK (role IN ('ADMIN')),
    enabled       boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    last_login_at timestamptz
);

CREATE TABLE audit_logs (
    id          bigserial PRIMARY KEY,
    occurred_at timestamptz  NOT NULL DEFAULT now(),
    actor       varchar(64)  NOT NULL,
    action      varchar(48)  NOT NULL,
    target      varchar(128),
    detail      jsonb,
    remote_addr varchar(64)
);
CREATE INDEX idx_audit_logs_time ON audit_logs (occurred_at DESC);

CREATE FUNCTION forbid_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-only (% rejected)', TG_OP USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_logs_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_mutation();

-- Independent periodic GPS pings (NOT violation events). Real-device ingestion stays disabled
-- until the firmware contract is approved; rows come from the dev demo fixture only for now.
CREATE TABLE device_locations (
    id          bigserial PRIMARY KEY,
    device_pk   bigint        NOT NULL REFERENCES devices (id),
    recorded_at timestamptz   NOT NULL,
    received_at timestamptz   NOT NULL,
    lat         numeric(9, 6) NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lon         numeric(9, 6) NOT NULL CHECK (lon BETWEEN -180 AND 180),
    source      varchar(32)   NOT NULL,
    CONSTRAINT uq_device_locations_sample UNIQUE (device_pk, recorded_at)
);
CREATE INDEX idx_device_locations_device_time ON device_locations (device_pk, recorded_at DESC);
