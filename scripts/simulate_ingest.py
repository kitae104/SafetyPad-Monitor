#!/usr/bin/env python3
"""Device emulator: replays data/events.json (fictional) to POST /api/v1/device-events.

Keys come from .dev-secrets/<device_id>.key (written by the dev-only provisioner), never from the command line
or the repository. Each event is posted twice with --retry to demonstrate duplicate acknowledgements.
Only devices that have a key file are simulated.

  DEV_PROVISION_DEVICES=PAD-0001,PAD-0002 make backend-run     # once, creates key files
  python3 scripts/simulate_ingest.py --limit 10 --retry
"""
import argparse, json, sys, urllib.request, urllib.error
from datetime import datetime, timedelta, timezone
from pathlib import Path

root = Path(__file__).resolve().parents[1]


def post(url, key, body):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json", "X-API-Key": key})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8080/api/v1/device-events")
    ap.add_argument("--limit", type=int, default=10)
    ap.add_argument("--retry", action="store_true", help="re-post each event to show duplicate=true")
    ap.add_argument("--now", action="store_true", help="shift event_time to just before now (server rejects stale/future times)")
    a = ap.parse_args()

    events = json.loads((root / "data/events.json").read_text(encoding="utf-8"))
    keys = {p.stem: p.read_text().strip() for p in (root / ".dev-secrets").glob("*.key")}
    if not keys:
        sys.exit("no key files in .dev-secrets; run the backend once with DEV_PROVISION_DEVICES=PAD-0001,...")
    sent = 0
    for i, e in enumerate(e for e in events if e["device_id"] in keys):
        if sent >= a.limit:
            break
        body = {k: v for k, v in e.items() if k not in ("received_at", "simulated")}  # server sets received_at
        if a.now:
            body["event_time"] = (datetime.now(timezone.utc) - timedelta(minutes=1 + i)).isoformat()
        for attempt in range(2 if a.retry else 1):
            status, resp = post(a.url, keys[e["device_id"]], body)
            print(f"{e['event_id']} try{attempt + 1}: HTTP {status} duplicate={resp.get('duplicate')} {resp.get('error', '')}")
        sent += 1


if __name__ == "__main__":
    main()
