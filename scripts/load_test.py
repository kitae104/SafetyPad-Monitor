#!/usr/bin/env python3
"""Synthetic ingestion load test (stdlib only).

Provisions N throw-away devices through the REAL admin API (login + CSRF), then posts E unique events per device
over persistent connections with a fraction of immediate retries. Finally it checks, via the admin API, that the
database holds exactly N*E events (idempotency under load) and prints latency percentiles and status counts.

  ADMIN_USER=... ADMIN_PASSWORD=... python3 scripts/load_test.py --base-url https://localhost:8443 --insecure \
      --devices 1000 --events-per-device 5 --concurrency 50 --retry-rate 0.1

Use ONLY against a stack you own (rehearsal/staging). It creates devices named LT-<run>-NNNN; raw events are
immutable by design, so test data stays in that database. The per-IP rate limit must be raised for the test
(INGEST_PER_IP_PER_MINUTE), because every synthetic device shares one source address.
"""
import argparse, http.client, http.cookiejar, json, os, random, ssl, statistics, sys, threading, time, urllib.parse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone


def make_ctx(insecure):
    ctx = ssl.create_default_context()
    if insecure:
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
    return ctx


class Conn:
    """One persistent connection per thread."""
    def __init__(self, base, ctx, keepalive=True):
        self.keepalive = keepalive
        u = urllib.parse.urlparse(base)
        self.https = u.scheme == "https"
        self.host, self.port = u.hostname, u.port or (443 if self.https else 80)
        self.ctx = ctx
        self.local = threading.local()

    def _c(self):
        c = getattr(self.local, "c", None)
        if c is None:
            c = http.client.HTTPSConnection(self.host, self.port, context=self.ctx, timeout=30) if self.https \
                else http.client.HTTPConnection(self.host, self.port, timeout=30)
            self.local.c = c
        return c

    def request(self, method, path, body=None, headers=None):
        h = {"Content-Type": "application/json", **(headers or {})}
        if not self.keepalive:
            h["Connection"] = "close"   # new TCP+TLS handshake per request, like an LTE device that sleeps between events
        data = json.dumps(body).encode() if body is not None else None
        for attempt in (0, 1):
            try:
                c = self._c()
                c.request(method, path, body=data, headers=h)
                r = c.getresponse()
                raw = r.read()
                if not self.keepalive:
                    c.close()
                    self.local.c = None
                return r.status, raw, r
            except (http.client.HTTPException, OSError):
                self.local.c = None  # reconnect once
                if attempt:
                    raise


class Admin:
    def __init__(self, conn, user, password):
        self.conn, self.cookies = conn, {}
        self._call("GET", "/api/v1/admin/csrf")
        st, raw, _ = self._call("POST", "/api/v1/admin/login", {"username": user, "password": password})
        if st != 200:
            sys.exit(f"admin login failed: HTTP {st} {raw[:120]!r}")

    def _call(self, method, path, body=None):
        h = {"Cookie": "; ".join(f"{k}={v}" for k, v in self.cookies.items())}
        if method != "GET" and "XSRF-TOKEN" in self.cookies:
            h["X-XSRF-TOKEN"] = self.cookies["XSRF-TOKEN"]
        st, raw, r = self.conn.request(method, path, body, h)
        for sc in r.getheader("Set-Cookie", "").split(", ") if r.getheader("Set-Cookie") else []:
            nv = sc.split(";", 1)[0]
            if "=" in nv:
                k, v = nv.split("=", 1)
                self.cookies[k] = v
        return st, raw, r

    def json(self, method, path, body=None):
        st, raw, _ = self._call(method, path, body)
        return st, (json.loads(raw) if raw else {})


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(round(p / 100 * (len(xs) - 1))))]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", required=True)
    ap.add_argument("--insecure", action="store_true", help="accept self-signed/internal-CA certificates (rehearsal only)")
    ap.add_argument("--devices", type=int, default=50)
    ap.add_argument("--events-per-device", type=int, default=5)
    ap.add_argument("--concurrency", type=int, default=20)
    ap.add_argument("--retry-rate", type=float, default=0.1, help="fraction of events immediately re-sent (duplicates)")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--no-keepalive", action="store_true", help="open a new TLS connection for every request (realistic for devices)")
    a = ap.parse_args()
    user, pw = os.environ.get("ADMIN_USER"), os.environ.get("ADMIN_PASSWORD")
    if not user or not pw:
        sys.exit("set ADMIN_USER and ADMIN_PASSWORD")
    rnd = random.Random(a.seed)
    admin = Admin(Conn(a.base_url, make_ctx(a.insecure)), user, pw)
    conn = Conn(a.base_url, make_ctx(a.insecure), keepalive=not a.no_keepalive)
    run = datetime.now(timezone.utc).strftime("%H%M%S")
    prefix = f"LT-{run}-"

    # ---- provisioning (real admin API) ----
    t0 = time.time()
    ids = [f"{prefix}{i:04d}" for i in range(1, a.devices + 1)]
    keys = {}
    lock = threading.Lock()

    def provision(did):
        # one admin session per worker thread would be cleaner, but the API is session-bound; serialise via lock-free copy
        st, _ = admin.json("POST", "/api/v1/admin/devices", {"device_id": did, "alias": "load test", "zone": "synthetic"})
        if st != 201:
            return f"create {did}: HTTP {st}"
        st, body = admin.json("POST", f"/api/v1/admin/devices/{did}/credentials", {"confirm": True})
        if st != 201:
            return f"issue {did}: HTTP {st}"
        with lock:
            keys[did] = body["api_key"]
        return None

    errs = [e for e in (provision(d) for d in ids) if e]
    if errs:
        sys.exit(f"provisioning failed: {errs[:3]}")
    print(f"provisioned {len(keys)} devices in {time.time() - t0:.1f}s")

    # ---- load ----
    base_time = datetime.now(timezone.utc) - timedelta(minutes=2)
    jobs = []
    for did in ids:
        for n in range(1, a.events_per_device + 1):
            jobs.append((did, n))
    rnd.shuffle(jobs)
    lat, status, dup_ack, fresh_ack = [], {}, 0, 0
    mlock = threading.Lock()

    def send(job):
        nonlocal dup_ack, fresh_ack
        did, n = job
        body = {
            "event_id": f"{did}-EVT-{n:05d}", "device_id": did, "event_type": "MULTI_RIDE",
            "event_time": (base_time + timedelta(seconds=n)).isoformat(),
            "gpsData": {"latitude": 37.451448 + rnd.uniform(-.005, .005), "longitude": 126.651542 + rnd.uniform(-.005, .005)},
            "battery_level": 50,
        }
        sends = 2 if rnd.random() < a.retry_rate else 1
        for _ in range(sends):
            s = time.perf_counter()
            try:
                st, raw, _ = conn.request("POST", "/api/v1/device-events", body, {"X-API-Key": keys[did]})
            except Exception as e:  # network-level failure
                st, raw = 0, str(e).encode()
            dt = (time.perf_counter() - s) * 1000
            with mlock:
                lat.append(dt)
                status[st] = status.get(st, 0) + 1
                if st == 200:
                    if b'"duplicate":true' in raw:
                        dup_ack += 1
                    else:
                        fresh_ack += 1

    t1 = time.time()
    with ThreadPoolExecutor(max_workers=a.concurrency) as ex:
        list(ex.map(send, jobs))
    wall = time.time() - t1

    # ---- verify through the admin API ----
    st, res = admin.json("GET", f"/api/v1/admin/events?device_id={urllib.parse.quote(prefix)}&size=1")
    stored = res.get("total", -1) if st == 200 else -1
    expected = a.devices * a.events_per_device
    ok_ids = st == 200 and stored == expected

    print(f"\n== Result: {a.devices} devices x {a.events_per_device} events, concurrency {a.concurrency}, retry rate {a.retry_rate}, {'new TLS connection per request' if a.no_keepalive else 'keep-alive'}")
    print(f"requests sent      : {len(lat)}  in {wall:.2f}s  => {len(lat) / wall:.0f} req/s")
    print(f"latency ms         : p50 {pct(lat, 50):.1f}  p95 {pct(lat, 95):.1f}  p99 {pct(lat, 99):.1f}  max {max(lat):.1f}  mean {statistics.mean(lat):.1f}")
    print(f"status counts      : {dict(sorted(status.items()))}")
    print(f"acks               : fresh {fresh_ack}, duplicate {dup_ack}")
    print(f"stored in database : {stored} (expected {expected}) -> {'OK exactly-once' if ok_ids else 'MISMATCH'}")
    failures = sum(v for k, v in status.items() if k != 200)
    print(f"non-200 responses  : {failures}")
    sys.exit(0 if ok_ids and failures == 0 else 1)


if __name__ == "__main__":
    main()
