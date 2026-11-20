-- Phase 1: devices, credentials, immutable raw events. All instants are timestamptz.

CREATE TABLE devices (
    id                 bigserial PRIMARY KEY,
    device_id          varchar(64)  NOT NULL UNIQUE,
    alias              varchar(100),
    zone               varchar(100),
    active             boolean      NOT NULL DEFAULT true,
    simulated          boolean      NOT NULL DEFAULT false,
    last_received_at   timestamptz,
    last_battery_level smallint CHECK (last_battery_level BETWEEN 0 AND 100),
    created_at         timestamptz  NOT NULL DEFAULT now()
);

-- Only a keyed digest (HMAC-SHA256 with server pepper) of the device key is stored, never the key itself.
-- Several rows per device may be active at once so keys can be rotated with an overlap window.
CREATE TABLE device_credentials (
    id          bigserial PRIMARY KEY,
    device_pk   bigint      NOT NULL REFERENCES devices (id),
    key_digest  char(64)    NOT NULL UNIQUE,
    key_prefix  varchar(12) NOT NULL,
    active      boolean     NOT NULL DEFAULT true,
    issued_at   timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz,
    revoked_at  timestamptz
);
CREATE INDEX idx_device_credentials_device ON device_credentials (device_pk);

CREATE TABLE device_events (
    id            bigserial PRIMARY KEY,
    device_pk     bigint        NOT NULL REFERENCES devices (id),
    event_id      varchar(100)  NOT NULL,
    event_type    varchar(32)   NOT NULL,
    event_time    timestamptz   NOT NULL,  -- device generated
    received_at   timestamptz   NOT NULL,  -- server generated on FIRST accepted arrival, never updated
    gps_lat       numeric(9, 6) CHECK (gps_lat BETWEEN -90 AND 90),
    gps_lon       numeric(9, 6) CHECK (gps_lon BETWEEN -180 AND 180),
    battery_level smallint      CHECK (battery_level BETWEEN 0 AND 100),
    raw_payload   jsonb         NOT NULL,
    CONSTRAINT uq_device_events_device_event UNIQUE (device_pk, event_id),
    CONSTRAINT ck_device_events_gps_pair CHECK ((gps_lat IS NULL) = (gps_lon IS NULL))
);
CREATE INDEX idx_device_events_device_time ON device_events (device_pk, event_time DESC);
CREATE INDEX idx_device_events_received ON device_events (received_at DESC);
CREATE INDEX idx_device_events_time ON device_events (event_time DESC);

-- Raw event data is immutable through normal operation.
-- UPDATE is always rejected. DELETE is rejected unless the (dev-only) demo reset sets
--   SET LOCAL safetypad.allow_demo_reset = 'on'
-- inside its own transaction.
CREATE FUNCTION forbid_event_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('safetypad.allow_demo_reset', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'device_events is immutable (% rejected)', TG_OP USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_device_events_immutable
    BEFORE UPDATE OR DELETE ON device_events
    FOR EACH ROW EXECUTE FUNCTION forbid_event_mutation();
