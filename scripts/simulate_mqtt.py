#!/usr/bin/env python3
"""Device emulator over MQTT (stdlib only, MQTT 3.1.1, QoS 1). Counterpart of simulate_ingest.py (REST).

What a real device must do, in this order (see docs/MQTT_INTEGRATION.md):
  1. CONNECT with client id = device_id, username = device_id, password = the device API key
  2. SUBSCRIBE to safetypad/devices/<device_id>/acks
  3. PUBLISH (QoS 1) the event JSON to safetypad/devices/<device_id>/events
  4. KEEP the event until an ack with success=true for its event_id arrives; otherwise re-publish the SAME event_id

Keys come from .dev-secrets/<device_id>.key, never from the command line or the repository.

  python3 scripts/simulate_mqtt.py --device PAD-0001 --count 3 --retry
  python3 scripts/simulate_mqtt.py --host broker.example.com --port 8883 --tls --device PAD-0001   # production (TLS)
"""
import argparse, json, socket, ssl, struct, sys, time, uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

root = Path(__file__).resolve().parents[1]


def utf(s: str) -> bytes:
    b = s.encode()
    return struct.pack("!H", len(b)) + b


def packet(first: int, body: bytes) -> bytes:
    n, rem = len(body), b""
    while True:
        d, n = n % 128, n // 128
        rem += bytes([d | (128 if n else 0)])
        if not n:
            break
    return bytes([first]) + rem + body


def read_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("connection closed by broker")
        buf += chunk
    return buf


def read_packet(sock):
    first = read_exact(sock, 1)[0]
    mult, length = 1, 0
    while True:
        b = read_exact(sock, 1)[0]
        length += (b & 127) * mult
        mult *= 128
        if not b & 128:
            break
    return first, read_exact(sock, length)


class Device:
    def __init__(self, host, port, device_id, key, tls=False, timeout=10):
        raw = socket.create_connection((host, port), timeout=timeout)
        self.sock = ssl.create_default_context().wrap_socket(raw, server_hostname=host) if tls else raw
        self.id, self.pid = device_id, 0
        flags = 0xC2  # username + password + clean session
        self.sock.sendall(packet(0x10, utf("MQTT") + bytes([4, flags]) + struct.pack("!H", 60) + utf(device_id) + utf(device_id) + utf(key)))
        first, body = read_packet(self.sock)
        if first != 0x20 or body[1] != 0:
            raise SystemExit(f"CONNECT refused (return code {body[1] if len(body) > 1 else '?'}): wrong key, disabled device, or client id != device id")
        topic = f"safetypad/devices/{device_id}/acks"
        pid = self._next()
        self.sock.sendall(packet(0x82, struct.pack("!H", pid) + utf(topic) + bytes([1])))
        while True:
            first, body = read_packet(self.sock)
            if first == 0x90:
                if body[2] == 0x80:
                    raise SystemExit("SUBSCRIBE refused by the broker")
                break

    def _next(self):
        self.pid = self.pid % 65535 + 1
        return self.pid

    def publish_and_wait_ack(self, event, wait=10):
        """Returns the commit ack (dict). PUBACK alone only means the broker got the bytes."""
        topic = f"safetypad/devices/{self.id}/events"
        self.sock.sendall(packet(0x32, utf(topic) + struct.pack("!H", self._next()) + json.dumps(event).encode()))
        deadline = time.time() + wait
        self.sock.settimeout(wait)
        while time.time() < deadline:
            first, body = read_packet(self.sock)
            if first >> 4 == 3:  # PUBLISH from broker: an ack
                tlen = struct.unpack("!H", body[:2])[0]
                off = 2 + tlen
                if (first >> 1) & 3:  # QoS 1: acknowledge it
                    self.sock.sendall(packet(0x40, body[off:off + 2]))
                    off += 2
                ack = json.loads(body[off:])
                if ack.get("event_id") in (event.get("event_id"), None):
                    return ack
        raise TimeoutError("no ack: keep the event and re-publish it with the same event_id")

    def close(self):
        try:
            self.sock.sendall(packet(0xE0, b""))
        finally:
            self.sock.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=1883)
    ap.add_argument("--tls", action="store_true", help="MQTT over TLS (production, usually port 8883)")
    ap.add_argument("--device", required=True, help="device_id; its key is read from .dev-secrets/<device_id>.key")
    ap.add_argument("--count", type=int, default=3)
    ap.add_argument("--retry", action="store_true", help="publish each event twice to show duplicate=true")
    a = ap.parse_args()

    key_file = root / ".dev-secrets" / f"{a.device}.key"
    if not key_file.exists():
        sys.exit(f"{key_file} not found (create a key via the admin UI/dev provisioner and save it there)")
    dev = Device(a.host, a.port, a.device, key_file.read_text().strip(), tls=a.tls)
    try:
        for i in range(a.count):
            event = {
                "event_id": f"mqtt-{uuid.uuid4().hex[:12]}", "device_id": a.device, "event_type": "MULTI_RIDE",
                "event_time": (datetime.now(timezone.utc) - timedelta(minutes=1 + i)).isoformat(),
                "gpsData": {"latitude": 37.4514 + i * 0.0003, "longitude": 126.6515 + i * 0.0003}, "battery_level": 80,
            }
            for attempt in range(2 if a.retry else 1):
                ack = dev.publish_and_wait_ack(event)
                print(f"{event['event_id']} try{attempt + 1}: success={ack.get('success')} duplicate={ack.get('duplicate')} {ack.get('error', '')}")
    finally:
        dev.close()


if __name__ == "__main__":
    main()
