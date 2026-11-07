#!/usr/bin/env python3
"""
Récupère les binaires natifs arm64 utilisés par Pocketdock et les place dans
app/src/main/jniLibs/arm64-v8a sous forme de fichiers lib*.so.

Pourquoi des lib*.so ? Depuis Android 10 (targetSdk >= 29), une application ne
peut plus exécuter un binaire écrit dans son répertoire de données (W^X).
Les fichiers de nativeLibraryDir, eux, sont exécutables : on y loge donc les
exécutables (proot, qemu, docker) et leurs bibliothèques, puis l'application
recrée au démarrage les noms attendus (sonames) via des liens symboliques.

Sources :
  * paquets Termux (proot, qemu-system-aarch64-headless et dépendances)
  * binaire statique officiel du client Docker

Le script écrit aussi app/src/main/assets/runtime-manifest.json qui décrit la
correspondance  nom attendu -> fichier lib*.so.
"""
import argparse
import hashlib
import io
import json
import lzma
import os
import re
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.error
import urllib.request
from pathlib import Path

TERMUX_REPO = "https://packages-cf.termux.dev/apt/termux-main"
TERMUX_PREFIX = "data/data/com.termux/files/usr"
DOCKER_VERSION = "27.5.1"
# arch Termux / Docker -> ABI Android
ARCHES = {"aarch64": "arm64-v8a", "x86_64": "x86_64"}

ROOT_PACKAGES = ["proot", "qemu-system-aarch64-headless"]
# Exécutables à embarquer : nom logique -> chemin dans le préfixe Termux
EXECUTABLES = {
    "proot": "bin/proot",
    "proot-loader": "libexec/proot/loader",
    "qemu-system-aarch64": "bin/qemu-system-aarch64",
}
# Bibliothèques fournies par Android lui-même (jamais embarquées)
SYSTEM_LIBS = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so",
    "libz.so", "libEGL.so", "libGLESv2.so", "libGLESv3.so", "libvulkan.so",
    "libmediandk.so", "libOpenSLES.so", "libjnigraphics.so", "libstdc++.so",
}
# Paquets inutiles pour un QEMU sans affichage (évite de gonfler l'APK)
SKIP_PACKAGES = {"termux-keyring", "termux-tools", "dash", "bash", "coreutils"}

ROOT = Path(__file__).resolve().parent.parent
ASSETS_DIR = ROOT / "app/src/main/assets"
CACHE = ROOT / ".native-cache"


def fetch(url: str, dest: Path, attempts: int = 5) -> Path:
    if dest.exists() and dest.stat().st_size > 0:
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_name(dest.name + ".part")
    for i in range(1, attempts + 1):
        print(f"  ↓ {url}" + (f" (essai {i})" if i > 1 else ""), flush=True)
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "pocketdock-build"})
            with urllib.request.urlopen(req, timeout=120) as r, open(part, "wb") as f:
                shutil.copyfileobj(r, f)
            part.rename(dest)
            return dest
        except (OSError, urllib.error.URLError) as e:
            print(f"  ! échec : {e}", flush=True)
            part.unlink(missing_ok=True)
            if i == attempts:
                raise
            time.sleep(2 ** i)
    return dest


def parse_index(text: str) -> dict:
    pkgs = {}
    for block in text.split("\n\n"):
        fields = {}
        key = None
        for line in block.splitlines():
            if line.startswith(" ") and key:
                fields[key] += "\n" + line.strip()
                continue
            if ":" in line:
                key, _, val = line.partition(":")
                fields[key.strip()] = val.strip()
        if "Package" in fields:
            pkgs[fields["Package"]] = fields
            for prov in fields.get("Provides", "").split(","):
                prov = re.sub(r"\(.*?\)", "", prov).strip()
                if prov and prov not in pkgs:
                    pkgs.setdefault("__provides__" + prov, fields)
    return pkgs


def deps_of(fields: dict) -> list:
    out = []
    for dep in (fields.get("Depends", "") + "," + fields.get("Pre-Depends", "")).split(","):
        dep = dep.split("|")[0]
        dep = re.sub(r"\(.*?\)", "", dep).strip()
        if dep:
            out.append(dep)
    return out


def resolve(pkgs: dict, roots: list) -> list:
    seen, order, stack = set(), [], list(roots)
    while stack:
        name = stack.pop()
        if name in seen or name in SKIP_PACKAGES:
            continue
        seen.add(name)
        fields = pkgs.get(name) or pkgs.get("__provides__" + name)
        if not fields:
            print(f"  ! dépendance introuvable : {name} (ignorée)")
            continue
        order.append(fields)
        stack.extend(deps_of(fields))
    return order


def extract_deb(deb: Path, dest: Path):
    """Extrait data.tar.* d'un .deb (format ar) dans dest."""
    data = deb.read_bytes()
    assert data[:8] == b"!<arch>\n", f"{deb} n'est pas une archive ar"
    pos = 8
    while pos < len(data):
        header = data[pos:pos + 60]
        name = header[:16].decode().strip().rstrip("/")
        size = int(header[48:58].decode().strip())
        body = data[pos + 60:pos + 60 + size]
        pos += 60 + size + (size & 1)
        if name.startswith("data.tar"):
            if name.endswith(".xz"):
                body = lzma.decompress(body)
                mode = "r:"
            elif name.endswith(".gz"):
                mode = "r:gz"
            elif name.endswith(".zst"):
                body = subprocess.run(["zstd", "-dc"], input=body, capture_output=True, check=True).stdout
                mode = "r:"
            else:
                mode = "r:"
            with tarfile.open(fileobj=io.BytesIO(body), mode=mode) as tf:
                names = tf.getnames()
                if sys.version_info >= (3, 12):
                    tf.extractall(dest, filter="fully_trusted")
                else:
                    tf.extractall(dest)
            return names
    raise RuntimeError(f"data.tar introuvable dans {deb}")


LICENSE_HINTS = [  # (motif, identifiant SPDX) : simple indication, le texte complet fait foi
    (r"GNU LESSER GENERAL PUBLIC LICENSE\s+Version 2\.1", "LGPL-2.1"),
    (r"GNU LESSER GENERAL PUBLIC LICENSE\s+Version 3", "LGPL-3.0"),
    (r"GNU LIBRARY GENERAL PUBLIC LICENSE", "LGPL-2.0"),
    (r"GNU GENERAL PUBLIC LICENSE\s+Version 3", "GPL-3.0"),
    (r"GNU GENERAL PUBLIC LICENSE\s+Version 2", "GPL-2.0"),
    (r"Apache License\s+Version 2\.0", "Apache-2.0"),
    (r"Permission is hereby granted, free of charge", "MIT"),
    (r"Redistribution and use in source and binary forms", "BSD"),
    (r"zlib License|This software is provided 'as-is'", "Zlib"),
    (r"OpenSSL License|Apache License", "Apache-2.0"),
]


def guess_licenses(texts: list) -> list:
    found = []
    for t in texts:
        for pat, spdx in LICENSE_HINTS:
            if re.search(pat, t, re.I) and spdx not in found:
                found.append(spdx)
                break
    return found or ["voir le texte"]


def write_notices(abi: str, components: list):
    """Avis de licence des composants natifs embarqués : JSON dans l'APK, Markdown pour la CI."""
    lic_dir = ASSETS_DIR / "licenses"
    lic_dir.mkdir(parents=True, exist_ok=True)
    (lic_dir / f"native-{abi}.json").write_text(json.dumps({"abi": abi, "components": components}, indent=1, ensure_ascii=False))
    out = ROOT / "build" / f"THIRD_PARTY_NATIVE-{abi}.md"
    out.parent.mkdir(parents=True, exist_ok=True)
    lines = [f"# Composants natifs embarqués ({abi})", "",
             "Générés par scripts/fetch-native.py. Sources : voir les liens de chaque composant.", ""]
    for c in components:
        lines += [f"## {c['name']} {c['version']}", "",
                  f"- Licence : {', '.join(c['licenses'])}",
                  f"- Site : {c.get('homepage') or '—'}",
                  f"- Sources : {c['source']}",
                  f"- Paquet : `{c.get('package_file', '—')}` (SHA-256 `{c.get('sha256', '—')}`)",
                  f"- Fichiers dans l'APK : {', '.join(c['files'])}", ""]
        for lt in c["license_texts"]:
            lines += [f"<details><summary>{lt['file']}</summary>", "", "```", lt["text"].strip(), "```", "", "</details>", ""]
    out.write_text("\n".join(lines))
    print(f"  avis de licence : {lic_dir / f'native-{abi}.json'} ({len(components)} composants), {out}")


def needed_libs(path: Path) -> list:
    out = subprocess.run(["readelf", "-d", str(path)], capture_output=True, text=True).stdout
    return re.findall(r"\(NEEDED\)\s+Shared library: \[([^\]]+)\]", out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--clean", action="store_true")
    ap.add_argument("--arch", action="append", choices=sorted(ARCHES),
                    help="architecture(s) à récupérer (défaut : toutes)")
    args = ap.parse_args()
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    for arch in args.arch or sorted(ARCHES):
        fetch_arch(arch, args.clean)


def fetch_arch(arch: str, clean: bool):
    abi = ARCHES[arch]
    JNI_DIR = ROOT / "app/src/main/jniLibs" / abi
    print(f"=== {arch} ({abi}) ===")
    if clean and JNI_DIR.exists():
        shutil.rmtree(JNI_DIR)
    JNI_DIR.mkdir(parents=True, exist_ok=True)
    stage = CACHE / f"stage-{arch}"
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir(parents=True)

    print("• Index Termux")
    index = fetch(f"{TERMUX_REPO}/dists/stable/main/binary-{arch}/Packages", CACHE / f"Packages-{arch}").read_text()
    pkgs = parse_index(index)

    print("• Résolution des dépendances")
    resolved = resolve(pkgs, ROOT_PACKAGES)
    print("  " + ", ".join(p["Package"] for p in resolved))

    print("• Téléchargement / extraction")
    owner = {}  # fichier extrait (chemin réel) -> paquet
    for p in resolved:
        deb = fetch(f"{TERMUX_REPO}/{p['Filename']}", CACHE / "debs" / Path(p["Filename"]).name)
        for name in extract_deb(deb, stage):
            path = stage / name
            if path.is_file() and not path.is_symlink():
                owner[path.resolve()] = p["Package"]
    usr = stage / TERMUX_PREFIX
    # Table des bibliothèques disponibles : soname -> fichier réel
    libs = {}
    for f in (usr / "lib").iterdir():
        if re.search(r"\.so(\.\d+)*$", f.name) and (f.is_file() or f.is_symlink()):
            real = f.resolve()
            if real.is_file():
                libs[f.name] = real

    manifest = {"bins": {}, "libs": {}}
    stored = {}  # chemin réel -> nom lib*.so

    def store(real: Path, preferred: str) -> str:
        if real in stored:
            return stored[real]
        name = preferred
        shutil.copyfile(real, JNI_DIR / name)
        os.chmod(JNI_DIR / name, 0o755)
        stored[real] = name
        return name

    queue = []
    for logical, rel in EXECUTABLES.items():
        src = usr / rel
        if not src.exists():
            sys.exit(f"Exécutable manquant : {rel}")
        manifest["bins"][logical] = store(src.resolve(), "lib" + logical.replace("-", "_") + ".so")
        queue.append(src.resolve())

    # Parcours des dépendances ELF (NEEDED) pour n'embarquer que l'utile
    done = set()
    missing = set()
    while queue:
        elf = queue.pop()
        if elf in done:
            continue
        done.add(elf)
        for soname in needed_libs(elf):
            if soname in manifest["libs"]:
                continue
            if soname in libs:
                real = libs[soname]
                digest = hashlib.sha1(soname.encode()).hexdigest()[:8]
                manifest["libs"][soname] = store(real, f"libdep_{digest}.so")
                queue.append(real)
            elif soname not in SYSTEM_LIBS:
                missing.add(soname)
    if missing:
        print("  ! bibliothèques non trouvées (supposées fournies par Android) : " + ", ".join(sorted(missing)))

    print("• Client Docker statique")
    url = f"https://download.docker.com/linux/static/stable/{arch}/docker-{DOCKER_VERSION}.tgz"
    tgz = fetch(url, CACHE / f"docker-{DOCKER_VERSION}-{arch}.tgz")
    with tarfile.open(tgz) as tf:
        member = tf.getmember("docker/docker")
        with tf.extractfile(member) as src, open(JNI_DIR / "libdocker.so", "wb") as dst:
            shutil.copyfileobj(src, dst)
    os.chmod(JNI_DIR / "libdocker.so", 0o755)
    manifest["bins"]["docker"] = "libdocker.so"

    (ASSETS_DIR / f"runtime-manifest-{abi}.json").write_text(json.dumps(manifest, indent=2, sort_keys=True))

    print("• Avis de licence")
    shipped = {}  # paquet -> fichiers lib*.so de l'APK
    for real, stored_name in stored.items():
        pkg = owner.get(real)
        if pkg:
            shipped.setdefault(pkg, []).append(stored_name)
        else:
            print(f"  ! paquet d'origine inconnu pour {real.name}")
    by_name = {p["Package"]: p for p in resolved}
    components = []
    for pkg in sorted(shipped):
        f = by_name[pkg]
        doc = usr / "share" / "doc" / pkg
        texts = []
        if doc.is_dir():
            for lf in sorted(doc.iterdir()):
                if lf.is_file() and lf.stat().st_size < 200_000:
                    texts.append({"file": f"share/doc/{pkg}/{lf.name}", "text": lf.read_text(errors="replace")})
        if not texts:
            print(f"  ! aucun texte de licence dans le paquet {pkg}")
        components.append({
            "name": pkg, "version": f.get("Version", ""), "homepage": f.get("Homepage", ""),
            "licenses": guess_licenses([t["text"] for t in texts]),
            "source": f"https://github.com/termux/termux-packages (recette du paquet {pkg}, sources amont : {f.get('Homepage', '—')})",
            "package_file": Path(f["Filename"]).name, "sha256": f.get("SHA256", ""),
            "files": sorted(shipped[pkg]), "license_texts": texts,
        })
    components.append({
        "name": "docker (client)", "version": DOCKER_VERSION, "homepage": "https://github.com/docker/cli",
        "licenses": ["Apache-2.0"], "source": f"https://github.com/docker/cli/tree/v{DOCKER_VERSION}",
        "package_file": Path(url).name, "sha256": hashlib.sha256(tgz.read_bytes()).hexdigest(),
        "files": ["libdocker.so"],
        "license_texts": [{"file": "docker/cli LICENSE", "text": "Apache License 2.0 — https://github.com/docker/cli/blob/master/LICENSE"}],
    })
    write_notices(abi, components)
    total = sum(f.stat().st_size for f in JNI_DIR.iterdir())
    print(f"✓ {len(list(JNI_DIR.iterdir()))} fichiers, {total / 1e6:.1f} Mo dans {JNI_DIR.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
