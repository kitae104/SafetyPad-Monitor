# Synthetic sample data

Generated using `python scripts/generate_demo.py`, deterministic seed 20261107.

- `devices.json`: 50 fictional PAD-0001...PAD-0050 devices
- `events.json`: 178 synthetic multi-rider events with timestamps, 8% missing GPS, occasional delayed receipt
- `events.csv`: Excel-friendly CSV (UTF-8 BOM)
- `gps_locations.json`: 2,250 *fictional periodic* GPS pings to demonstrate selected-device routes; this is an optional future device capability, **not guaranteed by original RFP**

Anchor for visual map test: Inha Technical College approximate vicinity, 37.451448 N, 126.651542 E; the samples are randomly perturbed coordinates and don't follow surveyed roads. No real devices, vehicles, violations, users, identifiable persons or historical telemetry are represented.

`received_at` in the seed is for history/UI demonstration only. When handling live REST events, the server must set it, never trust client request.
