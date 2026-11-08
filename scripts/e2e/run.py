#!/usr/bin/env python3
"""
Tests de bout en bout de Pocketdock, pilotés par adb + l'API de contrôle.

    python3 scripts/e2e/run.py --apk app/build/outputs/apk/debug [--out e2e-out]

Prérequis : un appareil ou émulateur visible par `adb devices`, APK **debug**
(le jeton de l'API est lu avec `run-as`).

Sorties dans --out :
  report.json   résultat structuré de chaque étape (ok / échec / ignoré, durée, détails)
  summary.md    résumé lisible (aussi ajouté à $GITHUB_STEP_SUMMARY en CI)
  *.png         captures d'écran de chaque onglet
  logcat.txt, console.txt, status.json   éléments de diagnostic
Code de sortie : 0 si toutes les étapes obligatoires passent, 1 sinon.
"""
import argparse
import glob
import io
import json
import shutil
import tarfile
import tempfile
import os
import subprocess
import sys
import time
import traceback
import urllib.error
import urllib.request

PKG = "dev.androdock"
PORT = 7777
API = f"http://127.0.0.1:{PORT}/v1"


class Fatal(Exception):
    """Échec définitif : wait() ne doit pas le traiter comme transitoire."""


class Ctx:
    def __init__(self, out, serial):
        self.out = out
        self.serial = serial
        self.token = None
        self.steps = []


def adb(ctx, *args, check=True, capture=True, timeout=300):
    cmd = ["adb"] + (["-s", ctx.serial] if ctx.serial else []) + list(args)
    r = subprocess.run(cmd, capture_output=capture, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)} -> {r.returncode}: {r.stderr.decode(errors='replace')[-500:]}")
    return r.stdout if capture else b""


def api(ctx, method, path, body=None, timeout=60, raw=False, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token or ctx.token}")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            payload = r.read()
            return r.status, (payload if raw else json.loads(payload or b"{}"))
    except urllib.error.HTTPError as e:
        payload = e.read()
        try:
            return e.code, json.loads(payload)
        except Exception:
            return e.code, {"raw": payload.decode(errors="replace")}


def wait(pred, timeout, interval=3, desc="condition"):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            ok, last = pred()
            if ok:
                return last
        except Fatal:
            raise
        except Exception as e:  # erreurs transitoires (app pas encore prête…)
            last = repr(e)
        time.sleep(interval)
    raise TimeoutError(f"délai dépassé ({timeout}s) en attendant {desc} ; dernier état : {str(last)[:800]}")


def step(ctx, name, required=True):
    """Décorateur : exécute une étape, mesure, consigne le résultat."""
    def deco(fn):
        t0 = time.time()
        entry = {"name": name, "required": required}
        print(f"\n▶ {name}", flush=True)
        try:
            detail = fn()
            entry.update(status="ok", detail=detail)
            print(f"  ✓ {detail if detail else ''}", flush=True)
        except Exception as e:
            entry.update(status="failed", detail=f"{type(e).__name__}: {e}", trace=traceback.format_exc()[-2000:])
            print(f"  ✗ {entry['detail']}", flush=True)
        entry["seconds"] = round(time.time() - t0, 1)
        ctx.steps.append(entry)
        return entry["status"] == "ok"
    return deco


def app_deaths(ctx):
    """Traces d'un plantage ou d'un arrêt du processus de l'application (diagnostic)."""
    out = adb(ctx, "logcat", "-d", "-b", "crash", check=False).decode(errors="replace").splitlines()
    log = adb(ctx, "logcat", "-d", "-v", "time", check=False).decode(errors="replace").splitlines()
    keys = ("AndroidRuntime", "FATAL", "lowmemorykiller", "lmkd", "has died", "Killing", "am_kill",
            "am_proc_died", "Force stopping", PKG + " ", "qemu", "DockerVm", "EngineService")
    return out + [l for l in log if any(k in l for k in keys)]


def screenshot(ctx, name, tab=None, dashboard=None):
    # --activity-clear-top : sans lui, si la tâche existe déjà, Android la ramène simplement au
    # premier plan (les extras ne comptent pas pour reconnaître la tâche) et l'intent n'arrive
    # pas : le tableau de bord resterait sur la vue précédente. Avec, MainActivity (singleTop)
    # repasse au sommet et reçoit l'intent dans onNewIntent.
    if tab:
        adb(ctx, "shell", "am", "start", "--activity-clear-top", "-n", f"{PKG}/.MainActivity", "--es", "tab", tab, check=False)
        time.sleep(2.5)
    if dashboard:
        adb(ctx, "shell", "am", "start", "--activity-clear-top", "-n", f"{PKG}/.MainActivity", "--es", "dashboard", dashboard, check=False)
        time.sleep(5)
    png = adb(ctx, "exec-out", "screencap", "-p")
    with open(os.path.join(ctx.out, f"{name}.png"), "wb") as f:
        f.write(png)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", default="e2e-out")
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    ap.add_argument("--engine-timeout", type=int, default=1800)
    ap.add_argument("--skip-engine", action="store_true", help="ne teste que l'app et les machines")
    ap.add_argument("--forever-dir", help="clone de luraku/forever_docker : scénario Compose niveau 1 (mysql + web)")
    ap.add_argument("--compose-timeout", type=int, default=5400)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    ctx = Ctx(args.out, args.serial)
    if os.path.isdir(args.apk):  # dossier : l'unique APK qu'il contient (pocketdock-debug.apk)
        apks = sorted(glob.glob(os.path.join(args.apk, "*.apk")))
        if len(apks) != 1:
            sys.exit(f"--apk {args.apk} : un seul .apk attendu, trouvé {apks}")
        args.apk = apks[0]

    abi = adb(ctx, "shell", "getprop", "ro.product.cpu.abi").decode().strip()
    sdk = adb(ctx, "shell", "getprop", "ro.build.version.sdk").decode().strip()
    print(f"Appareil : ABI {abi}, API {sdk}")

    @step(ctx, "Installation de l'APK")
    def _():
        adb(ctx, "install", "-r", "-g", args.apk, timeout=600)
        adb(ctx, "shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS", check=False)
        adb(ctx, "shell", "dumpsys", "deviceidle", "whitelist", f"+{PKG}", check=False)
        return f"ABI {abi}, API {sdk}"

    @step(ctx, "Lancement de l'application et API de contrôle")
    def _():
        adb(ctx, "shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity", "--ez", "noPermissionPrompt", "true")
        adb(ctx, "forward", f"tcp:{PORT}", f"tcp:{PORT}")
        def read_token():
            t = adb(ctx, "shell", "run-as", PKG, "cat", "files/control/token", check=False).decode().strip()
            return (len(t) >= 32 and " " not in t), t
        ctx.token = wait(read_token, 60, 2, "jeton de l'API (files/control/token)")
        code, st = wait(lambda: ((lambda r: (r[0] == 200, r))(api(ctx, "GET", "/status"))), 60, 2, "GET /v1/status")
        assert st["nativeBundled"], f"binaires natifs absents pour cet appareil : {st}"
        return f"v{st['version']}, abi={st['abi']}"

    @step(ctx, "API : authentification obligatoire")
    def _():
        tok, ctx.token = ctx.token, "mauvais"
        code, _ = api(ctx, "GET", "/status")
        ctx.token = tok
        assert code == 401, f"attendu 401, reçu {code}"
        return "401 sans jeton valide"

    @step(ctx, "API : réglages lisibles et modifiables")
    def _():
        mem = 3072 if args.forever_dir else 2048
        code, s = api(ctx, "PATCH", "/settings", {"cpus": 4, "memoryMb": mem, "diskGb": 16, "keepAwake": True})
        assert code == 200 and s["cpus"] == 4 and s["memoryMb"] == mem and s["diskGb"] == 16, s
        return s

    @step(ctx, "API : demande d'accès d'une application, droits, révocation")
    def _():
        code, r = api(ctx, "POST", "/pair", {"name": "e2e", "scopes": ["read", "run"]})
        assert code == 202 and r["state"] == "PENDING", (code, r)
        rid = r["id"]
        time.sleep(1)
        notif = "Demande d'accès" in adb(ctx, "shell", "dumpsys", "notification", "--noredact", check=False).decode(errors="replace")
        code, c = api(ctx, "GET", "/clients")
        assert code == 200 and any(p["id"] == rid for p in c["pending"]), c
        # l'utilisateur n'accorde que « read »
        code, c = api(ctx, "POST", f"/clients/pending/{rid}/approve", {"scopes": ["read"]})
        assert code == 200, c
        code, got = api(ctx, "GET", f"/pair/{rid}")
        assert code == 200 and got["state"] == "APPROVED" and got["token"].startswith("pdk_") and got["scopes"] == ["read"], got
        tok = got["token"]
        assert api(ctx, "GET", f"/pair/{rid}")[0] == 404, "jeton remis une seule fois"
        assert api(ctx, "GET", "/status", token=tok)[0] == 200
        code, e = api(ctx, "POST", "/containers/run", {"image": "hello-world"}, token=tok)
        assert code == 403 and e.get("required") == "run", (code, e)
        assert api(ctx, "GET", "/clients", token=tok)[0] == 403, "gestion des applications réservée au jeton principal"
        cid = next(x["id"] for x in api(ctx, "GET", "/clients")[1]["clients"] if x["name"] == "e2e")
        assert api(ctx, "DELETE", f"/clients/{cid}")[0] == 200
        assert api(ctx, "GET", "/status", token=tok)[0] == 401, "jeton révoqué"
        return f"accord, droit read seul, 403 sur run, révocation ; notification {'affichée' if notif else 'non vue'}"

    @step(ctx, "Tableau de bord web servi par l'API")
    def _():
        with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/", timeout=10) as r:
            html = r.read().decode()
        assert r.status == 200 and "<title>Pocketdock</title>" in html, html[:200]
        with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/app.js", timeout=10) as r:
            assert r.status == 200 and len(r.read()) > 10000
        return "index.html + app.js"

    screenshot(ctx, "01-containers-stopped", "containers")

    engine_ok = False
    if not args.skip_engine:
        @step(ctx, "Démarrage du moteur Docker (VM QEMU)")
        def _():
            code, r = api(ctx, "POST", "/engine/start")
            assert code == 202, r
            last = {}

            def ready():
                nonlocal last
                _, st = api(ctx, "GET", "/status")
                e = st["engine"]
                if e != last.get("engine"):
                    print(f"    · {e['state']} — {e['message']}", flush=True)
                last = st
                if e["state"] == "ERROR":
                    raise RuntimeError("moteur en erreur : " + e["message"])
                return e["state"] == "RUNNING", e
            e = wait(ready, args.engine_timeout, 5, "moteur RUNNING")
            # Accélération choisie (sur l'émulateur x86_64 de la CI : émulation logicielle attendue)
            assert e.get("accel") in ("kvm", "tcg"), e
            return f"Docker {e.get('dockerVersion')}, accélération {e.get('accel')} ({e.get('accelInfo')})"
        engine_ok = ctx.steps[-1]["status"] == "ok"

        if engine_ok:
            @step(ctx, "VM : disque de données monté sur /var/lib/docker, règle des ports locaux")
            def _():
                # --pid=host : /proc/1 est l'init de la VM (ses montages), --network=host : ses règles iptables.
                code, r = api(ctx, "POST", "/exec", {"cmd": "docker run --rm --privileged --pid=host --network=host alpine:3.22 sh -c "
                                                     "'grep -E \" /mnt/data | /var/lib/docker \" /proc/1/mounts; df -h /proc/1/root/var/lib/docker | tail -1; "
                                                     "chroot /proc/1/root iptables -t nat -S PREROUTING; "
                                                     "chroot /proc/1/root rc-status default 2>&1 | grep androdock; true'",
                                                     "timeoutSec": 600}, timeout=620)
                if "/dev/vda /mnt/data" not in r.get("stdout", ""):
                    _, c = api(ctx, "GET", "/engine/console?tail=2000")
                    boot = [l for l in c.get("lines", []) if "androdock" in l.lower() or "données" in l or "vda" in l]
                    print("    | console de la VM :\n    | " + "\n    | ".join(boot[-30:]), flush=True)
                assert code == 200 and r["exitCode"] == 0, r
                out = r["stdout"]
                print("    | " + out.replace("\n", "\n    | "), flush=True)
                assert "/dev/vda /mnt/data" in out, "disque de données non monté : Docker écrirait en RAM (tmpfs)"
                assert " /var/lib/docker " in out, "/var/lib/docker n'est pas un point de montage"
                assert "DNAT" in out and "127.0.0.1" in out, "règle DNAT des ports publiés sur 127.0.0.1 absente"
                return out.strip().splitlines()[2] if len(out.strip().splitlines()) > 2 else out.strip()

            @step(ctx, "Proxy API Docker : /v1/docker/version")
            def _():
                code, v = api(ctx, "GET", "/docker/version")
                assert code == 200, v
                return f"{v['Version']} / {v['Arch']} / noyau {v['KernelVersion']}"

            @step(ctx, "docker run hello-world (client docker embarqué)")
            def _():
                code, r = api(ctx, "POST", "/exec", {"cmd": "docker run --rm hello-world", "timeoutSec": 900}, timeout=960)
                assert code == 200 and r["exitCode"] == 0, r
                assert "Hello from Docker!" in r["stdout"], r["stdout"][-500:]
                return f"{r['durationMs'] / 1000:.0f} s"

            @step(ctx, "Téléchargement natif d'une image (TSK-030) comparé à docker pull")
            def _():
                ref = "redis:7-alpine"
                def image_id():
                    code, r = api(ctx, "GET", f"/docker/images/{ref}/json")
                    assert code == 200, r
                    return r["Id"]
                api(ctx, "DELETE", f"/docker/images/{ref}?force=1")
                code, n = api(ctx, "POST", "/images/pull", {"ref": ref, "native": True}, timeout=1800)
                assert code == 200 and n["method"] == "native", n
                native_id = image_id()
                api(ctx, "DELETE", f"/docker/images/{ref}?force=1")
                code, d = api(ctx, "POST", "/images/pull", {"ref": ref}, timeout=1800)
                assert code == 200 and d["method"] == "docker", d
                assert image_id() == native_id, "même identifiant d'image qu'avec docker pull"
                ratio = d["ms"] / max(n["ms"], 1)
                print(f"    | natif {n['ms'] / 1000:.1f} s ({n['detail']}) ; docker pull {d['ms'] / 1000:.1f} s ; ×{ratio:.1f}", flush=True)
                return f"natif {n['ms'] / 1000:.1f} s, docker pull {d['ms'] / 1000:.1f} s (×{ratio:.1f}), même ID"

            @step(ctx, "Conteneur nginx et redirection automatique du port 8080")
            def _():
                code, r = api(ctx, "POST", "/exec",
                              {"cmd": "docker rm -f e2e-web >/dev/null 2>&1; docker run -d --name e2e-web -p 8080:80 nginx:alpine",
                               "timeoutSec": 900}, timeout=960)
                assert code == 200 and r["exitCode"] == 0, r
                wait(lambda: (lambda st: ("127.0.0.1:8080/tcp" in st["engine"]["forwardedPorts"], st["engine"]["forwardedPorts"]))(
                    api(ctx, "GET", "/status")[1]), 120, 3, "redirection du port 8080")
                # Le port est redirigé sur la boucle locale de l'appareil ; on y accède depuis l'hôte via adb forward.
                adb(ctx, "forward", "tcp:18080", "tcp:8080")

                def fetch():
                    with urllib.request.urlopen("http://127.0.0.1:18080/", timeout=10) as r:
                        body = r.read().decode(errors="replace")
                    return "Welcome to nginx" in body, body[-300:]
                wait(fetch, 120, 3, "réponse HTTP de nginx sur 127.0.0.1:8080 de l'appareil")
                return "page nginx servie sur 127.0.0.1:8080 côté Android"

            if args.forever_dir:
                forever_level1(ctx, args)

            screenshot(ctx, "02-containers-running", "containers")
            screenshot(ctx, "03-images", "images")
            adb(ctx, "logcat", "-c", check=False)
            adb(ctx, "logcat", "-b", "crash", "-c", check=False)
            app_pid = adb(ctx, "shell", "pidof", PKG, check=False).decode().strip()
            screenshot(ctx, "10-dashboard-overview", dashboard="overview")
            screenshot(ctx, "11-dashboard-pods", dashboard="pods")
            screenshot(ctx, "12-dashboard-images", dashboard="images")

            @step(ctx, "Tableau de bord rendu dans la WebView avec les données du moteur")
            def _():
                def seen():
                    log = adb(ctx, "logcat", "-d", "-s", "AndrodockDashboard:I", check=False).decode(errors="replace")
                    lines = [l for l in log.splitlines() if "ready view=" in l]
                    pods_line = [l for l in lines if "view=pods" in l and "engine=RUNNING" in l and " containers=0" not in l]
                    return bool(pods_line), lines[-3:]
                lines = wait(seen, 150, 3, "signal ready du tableau de bord (vue pods, conteneurs > 0)")
                # Ouvrir et fermer le tableau de bord ne doit pas arrêter le moteur.
                pid = adb(ctx, "shell", "pidof", PKG, check=False).decode().strip()
                engine = api(ctx, "GET", "/status")[1]["engine"]
                if engine["state"] != "RUNNING":
                    print(f"    | moteur {engine['state']} ({engine['message']}) après le tableau de bord ; pid {app_pid} → {pid}", flush=True)
                    for l in app_deaths(ctx)[-40:]:
                        print("    | " + l, flush=True)
                    raise AssertionError(f"moteur {engine['state']} après navigation dans le tableau de bord (pid {app_pid} → {pid})")
                return lines[-1].split("ready ", 1)[-1] if lines else ""

    @step(ctx, "Création d'une machine Alpine (proot)")
    def _():
        api(ctx, "DELETE", "/machines/e2e-alpine")
        code, r = api(ctx, "POST", "/machines", {"name": "e2e-alpine", "distro": "alpine"})
        assert code == 202, r

        def created():
            _, m = api(ctx, "GET", "/machines")
            if m.get("lastError"):
                raise RuntimeError(m["lastError"])
            return any(i["name"] == "e2e-alpine" for i in m["items"]) and "installing" not in m, m.get("installing")
        wait(created, 600, 3, "machine créée")
        return "rootfs installé"

    machine_ok = ctx.steps[-1]["status"] == "ok"
    if machine_ok:
        @step(ctx, "Commande dans la machine : /etc/os-release")
        def _():
            code, r = api(ctx, "POST", "/machines/e2e-alpine/exec", {"cmd": "cat /etc/os-release; id -u; uname -m"})
            assert code == 200 and r["exitCode"] == 0, r
            assert "Alpine" in r["stdout"], r
            assert "\n0\n" in r["stdout"], "l'utilisateur devrait être root (proot -0)"
            return r["stdout"].strip().splitlines()[-1]

        if engine_ok:
            @step(ctx, "Docker depuis la machine (DOCKER_HOST préconfiguré)")
            def _():
                code, r = api(ctx, "POST", "/machines/e2e-alpine/exec", {"cmd": "docker ps --format '{{.Names}}'"})
                assert code == 200 and r["exitCode"] == 0, r
                assert "e2e-web" in r["stdout"], r
                return "docker ps voit e2e-web"

        screenshot(ctx, "04-machines", "machines")

    screenshot(ctx, "05-engine", "engine")

    if engine_ok:
        @step(ctx, "Arrêt propre du moteur", required=False)
        def _():
            api(ctx, "POST", "/engine/stop")
            wait(lambda: (lambda e: (e["state"] == "STOPPED", e))(api(ctx, "GET", "/status")[1]["engine"]), 120, 3, "moteur STOPPED")
            return "STOPPED"

    # ---- diagnostic
    try:
        _, st = api(ctx, "GET", "/status")
        json.dump(st, open(os.path.join(args.out, "status.json"), "w"), indent=2, ensure_ascii=False)
        _, c = api(ctx, "GET", "/engine/console?tail=2000")
        open(os.path.join(args.out, "console.txt"), "w").write("\n".join(c.get("lines", [])))
    except Exception as e:
        print("diagnostic API impossible :", e)
    open(os.path.join(args.out, "logcat.txt"), "wb").write(adb(ctx, "logcat", "-d", "-v", "time", check=False))

    report(ctx, abi, sdk)
    failed = [s for s in ctx.steps if s["status"] != "ok" and s["required"]]
    sys.exit(1 if failed else 0)


def forever_archive(src):
    """Archive .tar.gz du projet forever_docker prête pour un test : .env, certificat factice, sans le lanceur."""
    tmp = tempfile.mkdtemp(prefix="forever-")
    dst = os.path.join(tmp, "forever")
    top = os.path.abspath(src)
    # exclusions au premier niveau seulement (web/app est nécessaire au build de l'image web)
    shutil.copytree(src, dst, ignore=lambda d, names: [n for n in names if os.path.abspath(d) == top and n in (".git", "app", "docs")])
    env = open(os.path.join(dst, ".env.example")).read()
    for k, v in {"MYSQL_ROOT_PASSWORD": "e2e-root-pw", "DB_PASSWORD": "e2e-trinity-pw", "SOAP_PASSWORD": "e2e-soap-pw",
                 "MYSQL_BUFFER_POOL": "128M", "WOW_DIR": "/tmp"}.items():
        env = "\n".join(f"{k}={v}" if line.startswith(f"{k}=") else line for line in env.splitlines())
    open(os.path.join(dst, ".env"), "w").write(env + "\n")
    os.makedirs(os.path.join(dst, "certs", "ca"), exist_ok=True)
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2", "-subj", "/CN=androdock-e2e",
                    "-keyout", os.path.join(tmp, "ca.key"), "-out", os.path.join(dst, "certs", "ca", "forever_root_ca.crt")],
                   check=True, capture_output=True)
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tf:
        for name in sorted(os.listdir(dst)):
            tf.add(os.path.join(dst, name), arcname=name)
    shutil.rmtree(tmp, ignore_errors=True)
    return buf.getvalue()


def forever_level1(ctx, args):
    """Niveau 1 : vrai docker-compose.yml de forever_docker, services mysql + web (build local)."""
    state = {}

    @step(ctx, "Compose : import du projet forever dans la VM")
    def _():
        data = forever_archive(args.forever_dir)
        req = urllib.request.Request(API + "/compose/forever", data=data, method="PUT")
        req.add_header("Authorization", f"Bearer {ctx.token}")
        req.add_header("Content-Type", "application/gzip")
        with urllib.request.urlopen(req, timeout=300) as r:
            assert r.status == 201, r.status
        _, lst = api(ctx, "GET", "/compose")
        assert any(p["name"] == "forever" for p in lst["projects"]), lst
        return f"{len(data) // 1024} Ko envoyés"

    @step(ctx, "Compose : up --build mysql web (build local, healthcheck, depends_on)")
    def _():
        code, job = api(ctx, "POST", "/compose/forever/up", {"build": True, "services": ["mysql", "web"]})
        assert code == 202, job
        state["job"] = job["id"]
        last = [0]

        def done():
            _, j = api(ctx, "GET", f"/jobs/{job['id']}?tail=600")
            lines = (j.get("output") or "").splitlines()
            for line in lines[last[0]:][-15:]:
                print(f"    | {line[:160]}", flush=True)
            last[0] = len(lines)
            if j["state"] == "FAILED":
                raise Fatal(f"compose up en échec (code {j.get('exitCode')}, {j.get('error')}) : " + "\n".join(lines[-25:]))
            return j["state"] == "SUCCEEDED", j["state"]
        wait(done, args.compose_timeout, 10, "fin de docker compose up")
        _, j = api(ctx, "GET", f"/jobs/{job['id']}?tail=200000")
        open(os.path.join(ctx.out, "compose-up.log"), "w").write(j.get("output", ""))
        return "terminé"

    if ctx.steps[-1]["status"] != "ok":
        compose_diagnostics(ctx, ["forever-mysql-1", "forever-web-1"])

    up_ok = ctx.steps[-1]["status"] == "ok"

    @step(ctx, "Compose : mysql healthy, web démarré, réseau et volume du projet")
    def _():
        if not up_ok:
            raise Fatal("ignoré : compose up a échoué (voir le diagnostic ci-dessus)")
        def healthy():
            _, cs = api(ctx, "GET", "/docker/containers/json?all=1")
            mine = {c["Labels"].get("com.docker.compose.service"): c for c in cs
                    if c.get("Labels", {}).get("com.docker.compose.project") == "forever"}
            ok = "mysql" in mine and "(healthy)" in mine["mysql"]["Status"] and mine.get("web", {}).get("State") == "running"
            return ok, {k: v["Status"] for k, v in mine.items()}
        st = wait(healthy, 600, 5, "mysql healthy et web running")
        _, nets = api(ctx, "GET", "/docker/networks")
        assert any(n["Name"] == "forever_forever" for n in nets), [n["Name"] for n in nets]
        _, vols = api(ctx, "GET", "/docker/volumes")
        assert any(v["Name"] == "forever_mysql-data" for v in vols["Volumes"]), vols
        return st

    @step(ctx, "Compose : init SQL par bind mount (bases auth/characters/world/hotfixes)")
    def _():
        if not up_ok:
            raise Fatal("ignoré : compose up a échoué")
        code, r = api(ctx, "POST", "/exec", {"cmd": "docker exec forever-mysql-1 sh -c 'mysql -uroot -p\"$MYSQL_ROOT_PASSWORD\" -N -e \"SHOW DATABASES\"'",
                                             "timeoutSec": 120}, timeout=150)
        assert code == 200 and r["exitCode"] == 0, r
        dbs = set(r["stdout"].split())
        assert {"auth", "characters", "world", "hotfixes"} <= dbs, dbs
        return ", ".join(sorted(dbs & {"auth", "characters", "world", "hotfixes"}))

    @step(ctx, "Compose : site web sur 127.0.0.1:8088 (port publié en loopback dans la VM)")
    def _():
        if not up_ok:
            raise Fatal("ignoré : compose up a échoué")
        wait(lambda: (lambda st: ("127.0.0.1:8088/tcp" in st["engine"]["forwardedPorts"], st["engine"]["forwardedPorts"]))(
            api(ctx, "GET", "/status")[1]), 120, 3, "redirection du port 8088")
        adb(ctx, "forward", "tcp:18088", "tcp:8088")

        def health():
            with urllib.request.urlopen("http://127.0.0.1:18088/healthz", timeout=10) as r:
                return r.status == 200 and b"ok" in r.read(), r.status
        wait(health, 300, 5, "GET /healthz du site forever")
        return "/healthz → 200"

    screenshot(ctx, "13-dashboard-forever", dashboard="pods")

    @step(ctx, "Compose : down du projet", required=False)
    def _():
        code, job = api(ctx, "POST", "/compose/forever/down", {})
        assert code == 202, job
        wait(lambda: (lambda j: (j["state"] != "RUNNING", j["state"]))(api(ctx, "GET", f"/jobs/{job['id']}")[1]), 300, 5, "fin de down")
        _, j = api(ctx, "GET", f"/jobs/{job['id']}")
        assert j["state"] == "SUCCEEDED", j
        return "conteneurs et réseau supprimés"


def compose_diagnostics(ctx, containers):
    """Journaux et état des conteneurs d'un projet en échec : dans le rapport et dans la sortie de la CI."""
    out = []
    for name in containers:
        code, info = api(ctx, "GET", f"/docker/containers/{name}/json")
        if code != 200:
            out.append(f"### {name} : introuvable ({code})")
            continue
        st = info.get("State", {})
        health = (st.get("Health") or {}).get("Log", [])[-3:]
        out.append(f"### {name} : status={st.get('Status')} exit={st.get('ExitCode')} oom={st.get('OOMKilled')} "
                   f"restarts={info.get('RestartCount')} error={st.get('Error')!r}")
        for h in health:
            out.append(f"  health exit={h.get('ExitCode')} : {str(h.get('Output', '')).strip()[:300]}")
        code, r = api(ctx, "POST", "/exec", {"cmd": f"docker logs --tail 120 {name} 2>&1", "timeoutSec": 60}, timeout=90)
        out.append(r.get("stdout", "") if code == 200 else f"(journaux indisponibles : {r})")
    code, r = api(ctx, "POST", "/exec", {"cmd": "docker run --rm --privileged --pid=host alpine:3.22 sh -c "
                                                 "'free -m; grep -E \" /mnt/data | /var/lib/docker \" /proc/1/mounts; df -h /proc/1/root/var/lib/docker | tail -1; "
                                                 "chroot /proc/1/root rc-status default 2>&1 | grep androdock; dmesg | tail -25'", "timeoutSec": 90}, timeout=120)
    if code == 200:
        out.append("### VM : mémoire, disque, dmesg\n" + r.get("stdout", ""))
    text = "\n".join(out)
    open(os.path.join(ctx.out, "compose-diagnostics.txt"), "w").write(text)
    print("\n----- diagnostic Compose -----\n" + text[-12000:] + "\n------------------------------", flush=True)


def report(ctx, abi, sdk):
    json.dump({"abi": abi, "sdk": sdk, "steps": ctx.steps}, open(os.path.join(ctx.out, "report.json"), "w"),
              indent=2, ensure_ascii=False)
    lines = [f"## Pocketdock e2e — ABI {abi}, API {sdk}", "", "| | Étape | Durée | Détail |", "|---|---|---|---|"]
    for s in ctx.steps:
        icon = "✅" if s["status"] == "ok" else ("⚠️" if not s["required"] else "❌")
        detail = str(s.get("detail") or "").replace("|", "\\|").replace("\n", " ")[:200]
        lines.append(f"| {icon} | {s['name']} | {s['seconds']} s | {detail} |")
    md = "\n".join(lines) + "\n"
    open(os.path.join(ctx.out, "summary.md"), "w").write(md)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        open(os.environ["GITHUB_STEP_SUMMARY"], "a").write(md)
    print("\n" + md)


if __name__ == "__main__":
    main()
