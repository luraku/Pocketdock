#!/usr/bin/env python3
"""
Serveur factice de l'API de contrôle Pocketdock, pour développer et tester le tableau
de bord web sans appareil :

    python3 scripts/dashboard-mock/server.py [--port 7777]
    → http://127.0.0.1:7777/#token=demo

Sert app/src/main/assets/web et simule /v1/* avec des données réalistes
(pod Compose « forever », conteneurs isolés, images, volumes, machines).
"""
import argparse
import json
import math
import random
import struct
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

WEB = Path(__file__).resolve().parents[2] / "app/src/main/assets/web"
TOKEN = "demo"
T0 = time.time()


def ctr(cid, name, image, state, status, ports=(), project=None, service=None):
    labels = {}
    if project:
        labels = {"com.docker.compose.project": project, "com.docker.compose.service": service or name}
    return {"Id": cid * 8, "Names": [f"/{name}"], "Image": image, "ImageID": "sha256:" + cid * 8, "State": state,
            "Status": status, "Created": int(T0) - 3600,
            "Ports": [{"IP": "0.0.0.0", "PrivatePort": pr, "PublicPort": pu, "Type": "tcp"} for pu, pr in ports],
            "Labels": labels}


STATE = {
    "engine": "RUNNING",
    "containers": [
        ctr("a1b2c3d4", "forever-mysql-1", "mysql:8.0", "running", "Up 2 hours (healthy)", project="forever", service="mysql"),
        ctr("b2c3d4e5", "forever-worldserver-1", "forever-trinitycore:latest", "running", "Up 2 hours", [(8085, 8085)], "forever", "worldserver"),
        ctr("c3d4e5f6", "forever-bnetserver-1", "forever-trinitycore:latest", "running", "Up 2 hours", [(1119, 1119), (8081, 8081)], "forever", "bnetserver"),
        ctr("d4e5f6a7", "forever-web-1", "forever-web", "exited", "Exited (1) 5 minutes ago", [], "forever", "web"),
        ctr("e5f6a7b8", "web", "nginx:alpine", "running", "Up 14 minutes", [(8080, 80)]),
        ctr("f6a7b8c9", "cache", "redis:7-alpine", "exited", "Exited (0) 1 hour ago"),
    ],
    "machines": [{"name": "dev", "distro": "ubuntu", "label": "Ubuntu 24.04 LTS", "createdAt": int(T0 * 1000) - 86400000 * 2},
                 {"name": "alpine", "distro": "alpine", "label": "Alpine Linux 3.22", "createdAt": int(T0 * 1000) - 7200000}],
}
IMAGES = [
    {"Id": "sha256:" + "a1b2c3d4" * 8, "RepoTags": ["mysql:8.0"], "Size": 781_000_000, "Created": int(T0) - 86400 * 20},
    {"Id": "sha256:" + "b2c3d4e5" * 8, "RepoTags": ["forever-trinitycore:latest"], "Size": 1_430_000_000, "Created": int(T0) - 86400},
    {"Id": "sha256:" + "d4e5f6a7" * 8, "RepoTags": ["forever-web:latest"], "Size": 182_000_000, "Created": int(T0) - 86400},
    {"Id": "sha256:" + "e5f6a7b8" * 8, "RepoTags": ["nginx:alpine"], "Size": 48_300_000, "Created": int(T0) - 86400 * 9},
    {"Id": "sha256:" + "f6a7b8c9" * 8, "RepoTags": ["redis:7-alpine"], "Size": 41_200_000, "Created": int(T0) - 86400 * 30},
]
VOLUMES = [{"Name": "forever_mysql-data", "Driver": "local", "Labels": {"com.docker.compose.project": "forever"}},
           {"Name": "forever_logs", "Driver": "local", "Labels": {"com.docker.compose.project": "forever"}},
           {"Name": "redis-data", "Driver": "local", "Labels": {}}]


JOB = {"id": "job-3", "project": "forever", "action": "up", "state": "SUCCEEDED", "exitCode": 0,
       "startedAt": int((T0 - 5300) * 1000), "endedAt": int((T0 - 5050) * 1000),
       "output": "\n".join([
           " Network forever_forever  Creating", " Network forever_forever  Created",
           " Volume \"forever_mysql-data\"  Creating", " Volume \"forever_mysql-data\"  Created",
           "#1 [web internal] load build definition from Dockerfile", "#5 [web 2/5] RUN pip install --no-cache-dir -r requirements.txt",
           "#9 DONE 94.2s", " Container forever-mysql-1  Starting", " Container forever-mysql-1  Healthy",
           " Container forever-web-1  Started", ""])}


def frame(stream, text):
    b = text.encode()
    return struct.pack(">BxxxI", stream, len(b)) + b


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, code, body, ctype="application/json"):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}") if n else {}

    def handle_any(self, method):
        u = urlparse(self.path)
        path, q = u.path, parse_qs(u.query)
        if not path.startswith("/v1/"):
            rel = path.lstrip("/") or "index.html"
            f = WEB / rel
            if not f.is_file():
                return self.send(404, {"error": "introuvable"})
            ctype = {"html": "text/html; charset=utf-8", "js": "text/javascript", "css": "text/css", "svg": "image/svg+xml"}.get(f.suffix[1:], "application/octet-stream")
            return self.send(200, f.read_bytes(), ctype)
        if self.headers.get("Authorization") != f"Bearer {TOKEN}":
            return self.send(401, {"error": "jeton invalide ou absent"})
        s = path[4:].strip("/").split("/")
        t = time.time() - T0
        running = STATE["engine"] == "RUNNING"
        if method == "GET" and s == ["status"]:
            return self.send(200, {
                "version": "0.1.0", "abi": "arm64-v8a", "android": 35, "device": "Google Pixel 9 Pro", "nativeBundled": True,
                "engine": {"state": STATE["engine"], "message": "Docker 28.3.3 prêt" if running else "Arrêté",
                           "dockerVersion": "28.3.3" if running else None,
                           "forwardedPorts": ["127.0.0.1:1119/tcp", "127.0.0.1:8080/tcp", "127.0.0.1:8081/tcp", "127.0.0.1:8085/tcp"] if running else [],
                           "dockerHost": "tcp://127.0.0.1:2375", "diskUsageBytes": 6_420_000_000, "startedAt": int((T0 - 7400) * 1000),
                           "cpuPercent": 85 + 60 * math.sin(t / 7) + random.random() * 25 if running else None,
                           "rssBytes": int((1.55 + 0.1 * math.sin(t / 11)) * 1024 ** 3) if running else None,
                           "cpus": 4, "memoryMb": 4096, "diskGb": 64},
                "machines": {"items": STATE["machines"], "distros": [{"id": "alpine", "label": "Alpine Linux 3.22"},
                                                                     {"id": "ubuntu", "label": "Ubuntu 24.04 LTS"}, {"id": "custom", "label": "Rootfs personnalisé"}]},
            })
        if s[:2] == ["engine", "start"]:
            STATE["engine"] = "RUNNING"; return self.send(202, {"accepted": True})
        if s[:2] == ["engine", "stop"]:
            STATE["engine"] = "STOPPED"; return self.send(202, {"accepted": True})
        if s == ["engine", "console"]:
            return self.send(200, {"lines": ["[    0.000000] Booting Linux on physical CPU 0x0000000000 [0x000f0510]",
                                             "[    0.000000] Linux version 6.12.115-0-virt (buildozer@build-3-22-aarch64)",
                                             " * Starting docker ... [ ok ]", "Welcome to Alpine Linux 3.22"]})
        if s == ["settings"]:
            return self.send(200, {"cpus": 4, "memoryMb": 4096, "diskGb": 64, "exposeOnLan": False, "keepAwake": True, "autoStart": True, "controlApi": True})
        if s == ["exec"] or (s[0] == "machines" and s[-1] == "exec"):
            cmd = self.body().get("cmd", "")
            return self.send(200, {"exitCode": 0, "timedOut": False, "durationMs": 42, "stdout": f"(démo) {cmd}\nNAME=\"Ubuntu\"\nVERSION=\"24.04.3 LTS\"\n", "stderr": ""})
        if s == ["containers", "run"] or s == ["images", "pull"] or s == ["machines"] and method == "POST":
            return self.send(201, {"id": "demo"})
        if s == ["compose"]:
            return self.send(200, {"projects": [{"name": "forever", "uploadedAt": int((T0 - 5400) * 1000), "archiveBytes": 412_000}],
                                   "jobs": [JOB]})
        if s[0] == "compose" and method in ("POST", "DELETE", "PUT"):
            return self.send(202 if method != "PUT" else 201, JOB)
        if s[0] == "jobs":
            return self.send(200, JOB)
        if s[0] == "docker":
            if not running:
                return self.send(503, {"error": "moteur Docker non démarré"})
            d = s[1:]
            if d == ["containers", "json"]:
                return self.send(200, STATE["containers"])
            if d == ["images", "json"]:
                return self.send(200, IMAGES)
            if d == ["volumes"]:
                return self.send(200, {"Volumes": VOLUMES})
            if len(d) == 3 and d[0] == "containers" and d[2] == "logs":
                if "tail" in q:
                    lines = [frame(1, f"2026-11-06 21:{i:02d}:00 [INFO] World: tick {i} — {random.randint(80, 140)} ms\n") for i in range(30)]
                    lines.append(frame(2, "2026-11-06 21:30:12 [WARN] Map 571: grid not loaded (mmaps off)\n"))
                    return self.send(200, b"".join(lines), "application/octet-stream")
                return self.send(200, frame(1, time.strftime("%H:%M:%S") + " [INFO] heartbeat ok\n"), "application/octet-stream")
            if len(d) == 3 and d[0] == "containers" and d[2] == "stats":
                total = int((time.time() - T0) * 3e8)
                return self.send(200, {"cpu_stats": {"cpu_usage": {"total_usage": total}, "system_cpu_usage": int((time.time() - T0) * 4e9), "online_cpus": 4},
                                       "precpu_stats": {"cpu_usage": {"total_usage": total - int(3e8)}, "system_cpu_usage": int((time.time() - T0 - 1) * 4e9)},
                                       "memory_stats": {"usage": 612_000_000 + random.randint(0, 20_000_000), "limit": 4 * 1024 ** 3, "stats": {"inactive_file": 40_000_000}},
                                       "networks": {"eth0": {"rx_bytes": 182_000_000, "tx_bytes": 96_000_000}}, "pids_stats": {"current": 37}})
            if len(d) == 3 and d[0] == "containers" and d[2] == "json":
                return self.send(200, {"Created": "2026-11-06T19:12:03Z", "Config": {"Cmd": ["worldserver"], "Entrypoint": ["/usr/local/bin/entrypoint.sh"],
                                       "Env": ["DB_HOST=mysql", "DB_USER=trinity", "DB_PASSWORD=secret", "TZ=UTC"], "Labels": {"com.docker.compose.project": "forever"}},
                                       "HostConfig": {"RestartPolicy": {"Name": "unless-stopped"}},
                                       "NetworkSettings": {"Ports": {"8085/tcp": [{"HostIp": "0.0.0.0", "HostPort": "8085"}]}, "Networks": {"forever_forever": {"IPAddress": "172.18.0.3"}}},
                                       "Mounts": [{"Source": "/root/forever/data", "Destination": "/opt/trinitycore/data", "RW": False}, {"Name": "forever_logs", "Destination": "/opt/trinitycore/logs", "RW": True}]})
            if method in ("POST", "DELETE"):
                cid = d[1] if len(d) > 1 else ""
                for c in STATE["containers"]:
                    if c["Id"] == cid:
                        if d[-1] == "start" or d[-1] == "restart":
                            c["State"], c["Status"] = "running", "Up 1 second"
                        elif d[-1] == "stop":
                            c["State"], c["Status"] = "exited", "Exited (0) 1 second ago"
                return self.send(204, b"", "text/plain")
        return self.send(404, {"error": f"route inconnue : {method} {path}"})

    def do_GET(self): self.handle_any("GET")
    def do_POST(self): self.handle_any("POST")
    def do_PATCH(self): self.handle_any("PATCH")
    def do_PUT(self): self.handle_any("PUT")
    def do_DELETE(self): self.handle_any("DELETE")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=7777)
    a = ap.parse_args()
    print(f"Tableau de bord factice : http://127.0.0.1:{a.port}/#token={TOKEN}")
    ThreadingHTTPServer(("127.0.0.1", a.port), H).serve_forever()
