"""模拟浏览器终端全链路测试：ticket → WS(WSS) → terminal.open → 等待 terminal.opened。

纯标准库实现，无第三方依赖。用法:
  python verify-terminal-ws.py <ws_url_prefix>
    例: python verify-terminal-ws.py https://genhaosan.online   (公网走 nginx/WSS)
        python verify-terminal-ws.py http://127.0.0.1:18080     (服务器本机直连后端)
"""
import base64
import hashlib
import json
import os
import socket
import ssl
import struct
import sys
import time
import urllib.request
from urllib.parse import urlparse

BASE = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("SUSUMONITOR_VALIDATION_BASE_URL", "http://localhost:18080")
ADMIN_USERNAME = os.environ.get("SUSUMONITOR_VALIDATION_ADMIN_USERNAME", "")
ADMIN_PASSWORD = os.environ.get("SUSUMONITOR_VALIDATION_ADMIN_PASSWORD", "")
SERVER_ID = 4


def http_json(method, url, token=None, body=None, timeout=10):
    req = urllib.request.Request(url, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) ws-terminal-test")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    with urllib.request.urlopen(req, data=data, timeout=timeout) as resp:
        return json.loads(resp.read().decode())


def new_uuid():
    # 标准 UUID v4 格式(带连字符),后端 UUID.fromString 要求 8-4-4-4-12
    r = __import__("random")
    h = "".join(r.choices("0123456789abcdef", k=32))
    return f"{h[0:8]}-{h[8:12]}-4{h[13:16]}-{r.choice('89ab')}{h[17:20]}-{h[20:32]}"


def connect_ws(url, timeout=10):
    p = urlparse(url)
    host, port = p.hostname, p.port or (443 if p.scheme == "wss" else 80)
    path = p.path + ("?" + p.query if p.query else "")
    key = base64.b64encode(os.urandom(16)).decode()
    raw = socket.create_connection((host, port), timeout=timeout)
    if p.scheme == "wss":
        ctx = ssl.create_default_context()
        sock = ctx.wrap_socket(raw, server_hostname=host)
    else:
        sock = raw
    req = (
        f"GET {path} HTTP/1.1\r\n"
        f"Host: {host}\r\n"
        f"Upgrade: websocket\r\n"
        f"Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        f"Sec-WebSocket-Version: 13\r\n"
        f"Origin: {BASE}\r\n"
        f"\r\n"
    )
    sock.sendall(req.encode())
    resp = b""
    while b"\r\n\r\n" not in resp:
        chunk = sock.recv(4096)
        if not chunk:
            break
        resp += chunk
    status_line = resp.split(b"\r\n", 1)[0].decode()
    print(f"[handshake] {status_line}")
    if b" 101 " not in status_line.encode():
        print("[handshake] FAILED, body:", resp.decode(errors="replace"))
        raise SystemExit(1)
    return sock


def ws_send(sock, text):
    b = text.encode()
    mask = os.urandom(4)
    header = bytearray([0x81])
    ln = len(b)
    if ln < 126:
        header.append(0x80 | ln)
    elif ln < 65536:
        header.append(0x80 | 126)
        header += struct.pack(">H", ln)
    else:
        header.append(0x80 | 127)
        header += struct.pack(">Q", ln)
    masked = bytes(x ^ mask[i % 4] for i, x in enumerate(b))
    sock.sendall(bytes(header) + mask + masked)


def ws_recv(sock, timeout_sec):
    sock.settimeout(timeout_sec)
    try:
        head = sock.recv(2)
    except (socket.timeout, ssl.SSLWantReadError, ConnectionError):
        return None
    if len(head) < 2:
        return None
    b0, b1 = head
    ln = b1 & 0x7F
    if ln == 126:
        ln = struct.unpack(">H", sock.recv(2))[0]
    elif ln == 127:
        ln = struct.unpack(">Q", sock.recv(8))[0]
    mask = sock.recv(4) if b1 & 0x80 else b""
    data = b""
    while len(data) < ln:
        chunk = sock.recv(ln - len(data))
        if not chunk:
            break
        data += chunk
    if mask:
        data = bytes(x ^ mask[i % 4] for i, x in enumerate(data))
    return data


def main():
    print(f"=== 终端全链路测试 @ {BASE} (server_id={SERVER_ID}) ===")
    # 1. 登录
    login = http_json("POST", BASE + "/api/auth/login",
                      body={"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD})
    token = login["data"]["token"]
    print("[1] login ok")
    # 2. 取 ticket
    t = http_json("POST", BASE + "/api/ws/monitor-ticket", token=token)
    ticket = t["data"]["ticket"]
    print(f"[2] ticket ok: {ticket[:16]}...")
    # 3. 连接 WS
    scheme = "wss" if BASE.startswith("https") else "ws"
    host = urlparse(BASE).netloc
    ws_url = f"{scheme}://{host}/ws/monitor?ticket={ticket}"
    sock = connect_ws(ws_url)
    print("[3] ws connected")
    # 4. 先订阅 metrics（浏览器行为），再发 terminal.open
    ws_send(sock, json.dumps({
        "type": "metrics.subscribe", "message_id": new_uuid(),
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime()),
        "payload": {"server_id": SERVER_ID},
    }))
    ws_send(sock, json.dumps({
        "type": "terminal.open", "message_id": new_uuid(),
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime()),
        "payload": {"server_id": SERVER_ID, "cols": 80, "rows": 24},
    }))
    print("[4] sent metrics.subscribe + terminal.open, waiting for terminal.opened (10s)...")
    deadline = time.time() + 10
    while time.time() < deadline:
        data = ws_recv(sock, deadline - time.time())
        if data is None:
            continue
        try:
            frame = json.loads(data.decode())
        except Exception:
            print("[recv] <non-json>", data[:120])
            continue
        print("[recv]", json.dumps(frame, ensure_ascii=False)[:300])
        if frame.get("type") == "terminal.opened":
            print("\n*** terminal.opened 收到! 建链成功 ***")
            return
        if frame.get("type") == "error":
            print("\n*** 收到 error 帧 ***")
            return
    print("\n*** 10 秒内未收到 terminal.opened ***")


if __name__ == "__main__":
    main()
