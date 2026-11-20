export interface Page<T> {
  items: T[]
  page: number
  size: number
  total: number
}

export interface EventRow {
  id: number
  event_id: string
  device_id: string
  alias: string | null
  event_type: string
  event_time: string
  received_at: string
  lag_seconds: number
  clock_skew_suspected: boolean
  latitude: number | null
  longitude: number | null
  battery_level: number | null
  simulated: boolean
}

export interface LastKnownGps {
  latitude: number
  longitude: number
  event_time: string
  source: 'VIOLATION_EVENT'
}

export interface DeviceRow {
  device_id: string
  alias: string | null
  zone: string | null
  active: boolean
  simulated: boolean
  last_received_at: string | null
  last_battery_level: number | null
  event_count: number
  last_known_gps: LastKnownGps | null
}

export interface CredentialMeta {
  credential_id: number
  key_prefix: string
  active: boolean
  issued_at: string
  expires_at: string | null
  revoked_at: string | null
}

export interface DeviceDetail {
  device: DeviceRow
  credentials: CredentialMeta[]
}

export interface Summary {
  generated_at: string
  registered_devices: number
  violations_today: number
  devices_received_last_24h: number
  low_battery_devices: number
  low_battery_threshold: number
  simulated_data_present: boolean
  daily_trend: { date: string; count: number }[]
  top_devices: { device_id: string; alias: string | null; count: number }[]
  lag_distribution: Record<string, number>
  period: { from: string; to: string; timezone: string }
}

export interface AppConfig {
  demo_tools: boolean
  route_enabled: boolean
  location_ping_ingest_enabled: boolean
  map_provider: string
  map_js_key?: string | null
  mqtt_enabled?: boolean
  mqtt_tls_port?: number | null
  mqtt_plaintext_port?: number | null
  timezone: string
}

export interface LiveStatus {
  running: boolean
  generated: number
  lease_seconds_left: number
  last_event_at: string | null
}

export interface MeResponse {
  username: string
  role: string
}

export interface EventFilters {
  from?: string
  to?: string
  device_id?: string
  min_lag_seconds?: string
  has_gps?: string
  sort?: string
  dir?: string
  page?: number
  size?: number
}

export interface MapEventPoint {
  id: number
  event_id: string
  device_id: string
  alias: string | null
  point_type: 'VIOLATION'
  event_time: string
  received_at: string
  latitude: number
  longitude: number
  simulated: boolean
}

export interface MapEventsResponse {
  points: MapEventPoint[]
  truncated: boolean
  missing_gps_count: number
  note: string
}

export interface RoutePoint {
  point_type: 'PERIODIC'
  recorded_at: string
  latitude: number
  longitude: number
  source: string
  gap_before: boolean
}

export interface RouteResponse {
  device_id: string
  sufficient: boolean
  points: RoutePoint[]
  gaps: number
  max_gap_minutes: number
  range: { from: string; to: string; timezone: string } | null
  days_available: string[]
  last_ping_at: string | null
  disclaimer: string
}
