/* Pocketdock — tableau de bord web (servi par l'API de contrôle, 127.0.0.1:7777). Aucune dépendance. */
(() => {
  "use strict";

  // ------------------------------------------------------------------ outils
  const $ = (sel, el = document) => el.querySelector(sel);
  const esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const icon = (id, cls = "") => `<svg class="${cls}"><use href="#i-${id}"/></svg>`;
  const bytes = (n) => {
    if (n == null || isNaN(n)) return "—";
    const u = ["o", "Ko", "Mo", "Go", "To"]; let i = 0; n = Number(n);
    while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
    return `${n.toFixed(n >= 100 || i === 0 ? 0 : 1)} ${u[i]}`;
  };
  const ago = (sec) => {
    const d = Date.now() / 1000 - sec;
    if (d < 60) return "à l'instant";
    if (d < 3600) return `il y a ${Math.floor(d / 60)} min`;
    if (d < 86400) return `il y a ${Math.floor(d / 3600)} h`;
    return `il y a ${Math.floor(d / 86400)} j`;
  };
  const duration = (ms) => {
    const s = Math.floor(ms / 1000), h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
    return h ? `${h} h ${m} min` : m ? `${m} min` : `${s} s`;
  };
  const bridge = window.AndrodockBridge; // présent dans la WebView de l'application

  // ------------------------------------------------------------------ authentification
  const hashParams = new URLSearchParams(location.hash.slice(1));
  const hashToken = hashParams.get("token");
  if (hashToken) {
    try { localStorage.setItem("androdock.token", hashToken); } catch (_) {}
    history.replaceState(null, "", location.pathname + "#/" + (hashParams.get("view") || "overview"));
  }
  let token = hashToken || (() => { try { return localStorage.getItem("androdock.token"); } catch (_) { return null; } })();

  class ApiError extends Error { constructor(status, msg) { super(msg); this.status = status; } }

  async function api(method, path, body, { raw = false, timeout = 60000 } = {}) {
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), timeout);
    try {
      const r = await fetch(`/v1${path}`, {
        method, signal: ctrl.signal,
        headers: { Authorization: `Bearer ${token}`, ...(body !== undefined ? { "Content-Type": "application/json" } : {}) },
        body: body !== undefined ? JSON.stringify(body) : undefined,
      });
      if (r.status === 401) { showLogin("Jeton invalide ou expiré."); throw new ApiError(401, "non autorisé"); }
      if (raw) { if (!r.ok) throw new ApiError(r.status, await r.text()); return r; }
      const text = await r.text();
      const data = text ? (() => { try { return JSON.parse(text); } catch (_) { return { raw: text }; } })() : {};
      if (!r.ok) throw new ApiError(r.status, data.error || data.message || data.raw || `HTTP ${r.status}`);
      return data;
    } finally { clearTimeout(t); }
  }
  const docker = (method, path, body, opts) => api(method, `/docker${path}`, body, opts);

  // ------------------------------------------------------------------ état
  const S = {
    status: null, containers: [], images: [], volumes: [], machines: { items: [], distros: [] }, compose: { projects: [], jobs: [] },
    clients: { clients: [], pending: [], scopes: [] },
    cpuHist: [], memHist: [], openPods: new Set((() => { try { return JSON.parse(localStorage.getItem("androdock.openPods") || "[]"); } catch (_) { return []; } })()),
    busy: new Set(),
  };
  const running = () => S.status?.engine?.state === "RUNNING";

  // ------------------------------------------------------------------ notifications
  function toast(msg, kind = "") {
    const el = document.createElement("div");
    el.className = `toast ${kind}`;
    el.innerHTML = `<div style="flex:1">${esc(msg)}</div>`;
    $("#toasts").append(el);
    setTimeout(() => el.remove(), kind === "bad" ? 7000 : 3500);
  }
  async function act(key, label, fn, okMsg) {
    if (S.busy.has(key)) return;
    S.busy.add(key); render();
    try { await fn(); if (okMsg) toast(okMsg, "ok"); }
    catch (e) { if (e.status !== 401) toast(`${label} : ${e.message}`, "bad"); }
    finally { S.busy.delete(key); await refreshAll(); }
  }

  // ------------------------------------------------------------------ navigation
  const VIEWS = [
    { id: "overview", label: "Vue d'ensemble", short: "Accueil", icon: "home" },
    { id: "stacks", label: "Stacks Compose", short: "Stacks", icon: "stack" },
    { id: "pods", label: "Conteneurs", short: "Conteneurs", icon: "pods" },
    { id: "images", label: "Images", short: "Images", icon: "layers" },
    { id: "volumes", label: "Volumes", short: "Volumes", icon: "disk" },
    { id: "machines", label: "Machines Linux", short: "Machines", icon: "machine" },
    { id: "engine", label: "Moteur", short: "Moteur", icon: "gear" },
  ];
  const current = () => (location.hash.match(/^#\/(\w+)/) || [])[1] || "overview";
  window.addEventListener("hashchange", () => { render(); refreshAll(); });

  function renderNav() {
    const stacksN = new Set([...(S.compose.projects || []).map((p) => p.name), ...pods().filter((p) => p.compose).map((p) => p.name)]).size;
    const counts = { stacks: stacksN || "", pods: pods().filter((p) => !p.compose).length || "", images: S.images.length || "", volumes: S.volumes.length || "", machines: S.machines.items.length || "" };
    const cur = current();
    $("#nav").innerHTML = VIEWS.map((v) =>
      `<a href="#/${v.id}" class="${cur === v.id ? "active" : ""}">${icon(v.icon)}<span>${v.label}</span><span class="count">${counts[v.id] ?? ""}</span></a>`).join("");
    $("#bottomnav").innerHTML = VIEWS.filter((v) => v.id !== "volumes").map((v) =>
      `<a href="#/${v.id}" class="${cur === v.id ? "active" : ""}">${icon(v.icon)}<span>${v.short}</span></a>`).join("");
    const e = S.status?.engine;
    $("#engine-mini").innerHTML = e ? `<div class="row"><span class="dot ${dotClass(e.state)}"></span>Moteur ${stateLabel(e.state)}</div><small>${esc(e.message)}</small>` : "";
  }

  const stateLabel = (s) => ({ RUNNING: "actif", STOPPED: "arrêté", ERROR: "en erreur", PREPARING: "préparation", BOOTING: "démarrage", STOPPING: "arrêt" }[s] || s);
  const dotClass = (s) => (s === "RUNNING" ? "ok" : s === "ERROR" ? "bad" : s === "STOPPED" ? "" : "warn");
  const pillFor = (state) => {
    const map = { running: ["ok", "actif"], exited: ["idle", "arrêté"], created: ["idle", "créé"], paused: ["warn", "en pause"], restarting: ["warn busy", "redémarre"], dead: ["bad", "mort"], removing: ["warn busy", "suppression"] };
    const [c, l] = map[state] || ["idle", state];
    return `<span class="pill ${c}">${l}</span>`;
  };

  // ------------------------------------------------------------------ données
  async function refreshStatus() {
    try {
      S.status = await api("GET", "/status");
      const e = S.status.engine;
      if (e.cpuPercent != null) { S.cpuHist.push(e.cpuPercent); S.memHist.push(e.rssBytes); }
      if (S.cpuHist.length > 60) { S.cpuHist.shift(); S.memHist.shift(); }
      S.machines = S.status.machines;
    } catch (_) {}
  }
  async function refreshDocker(all = false) {
    if (all || current() === "engine") { try { S.clients = await api("GET", "/clients"); } catch (_) {} }
    if (!running()) { S.containers = []; return; }
    try { S.containers = await docker("GET", "/containers/json?all=1"); } catch (_) {}
    if (all || ["images", "overview"].includes(current())) { try { S.images = await docker("GET", "/images/json"); } catch (_) {} }
    if (all || ["volumes", "overview"].includes(current())) { try { S.volumes = (await docker("GET", "/volumes")).Volumes || []; } catch (_) {} }
    if (all || ["stacks", "pods", "overview"].includes(current())) { try { S.compose = await api("GET", "/compose"); } catch (_) {} }
  }
  async function refreshAll() { await refreshStatus(); await refreshDocker(); render(); }

  let tick = 0;
  async function loop() {
    if (!token) return;
    await refreshStatus();
    if (tick++ % 2 === 0) await refreshDocker();
    if (!document.querySelector(".input:focus")) render();
    setTimeout(loop, document.hidden ? 8000 : 2000);
  }

  // ------------------------------------------------------------------ pods
  /** Un « pod » = un projet Compose ; un conteneur isolé forme son propre pod. */
  function pods() {
    const groups = new Map();
    for (const c of S.containers) {
      const project = c.Labels?.["com.docker.compose.project"];
      const key = project ? `compose:${project}` : `single:${c.Id}`;
      if (!groups.has(key)) groups.set(key, { key, name: project || cname(c), compose: !!project, containers: [] });
      groups.get(key).containers.push(c);
    }
    return [...groups.values()].sort((a, b) => (b.compose - a.compose) || a.name.localeCompare(b.name));
  }
  const cname = (c) => (c.Names?.[0] || c.Id.slice(0, 12)).replace(/^\//, "");
  const service = (c) => c.Labels?.["com.docker.compose.service"] || cname(c);
  const publicPorts = (cs) => [...new Set(cs.flatMap((c) => (c.Ports || []).filter((p) => p.PublicPort).map((p) => p.PublicPort)))].sort((a, b) => a - b);

  // ------------------------------------------------------------------ rendu
  function render() {
    if (!token) return;
    renderNav();
    const v = VIEWS.find((x) => x.id === current()) || VIEWS[0];
    $("#view-title").textContent = v.label;
    $("#topbar-actions").innerHTML = topActions(v.id);
    const html = ({ overview, stacks: stacksView, pods: podsView, images, volumes, machines, engine })[v.id]();
    const view = $("#view");
    // évite de détruire un formulaire modifié mais pas encore enregistré
    if (view.dataset.view === v.id && view.querySelector("[data-keep][data-dirty]")) return;
    view.dataset.view = v.id;
    const scroll = window.scrollY;
    view.innerHTML = html;
    window.scrollTo(0, scroll);
    // Signal à l'app (tests e2e) : la vue est rendue avec des données réelles.
    if (bridge?.ready && S.status && signaled !== v.id + ":" + S.containers.length) {
      signaled = v.id + ":" + S.containers.length;
      try { bridge.ready(v.id, S.status.engine?.state || "", pods().length, S.containers.length); } catch (_) {}
    }
  }
  let signaled = "";

  function topActions(id) {
    const e = S.status?.engine?.state;
    const engineBtn = e === "STOPPED" || e === "ERROR"
      ? `<button class="btn primary" data-action="engine-start">${icon("play")}<span class="btn-label">Démarrer</span></button>`
      : e === "RUNNING" ? `<button class="btn" data-action="engine-stop">${icon("stop")}<span class="btn-label">Arrêter</span></button>` : "";
    const extra = {
      stacks: running() ? `<button class="btn primary" data-action="compose-import">${icon("down")}<span class="btn-label">Importer</span></button>` : "",
      pods: running() ? `<button class="btn primary" data-action="open-run">${icon("plus")}<span class="btn-label">Lancer</span></button>` : "",
      images: running() ? `<button class="btn primary" data-action="open-pull">${icon("down")}<span class="btn-label">Télécharger</span></button>` : "",
      machines: `<button class="btn primary" data-action="open-machine">${icon("plus")}<span class="btn-label">Nouvelle</span></button>`,
    }[id] || "";
    // Vue d'ensemble, moteur et pods (moteur arrêté) affichent déjà le bouton dans leur bandeau.
    return extra + (["images", "volumes"].includes(id) && !running() ? engineBtn : "");
  }

  function engineHero() {
    const e = S.status?.engine || {};
    const st = e.state || "STOPPED";
    const busy = ["PREPARING", "BOOTING", "STOPPING"].includes(st);
    const progress = busy ? (e.progress >= 0 ? `<div class="progress"><i style="width:${Math.round(e.progress * 100)}%"></i></div>` : `<div class="progress indeterminate"><i></i></div>`) : "";
    const uptime = st === "RUNNING" && e.startedAt ? ` · actif depuis ${duration(Date.now() - e.startedAt)}` : "";
    return `<div class="card hero">
      <span class="dot ${dotClass(st)}" style="width:12px;height:12px"></span>
      <div style="flex:1;min-width:220px">
        <div class="title">Moteur Docker ${stateLabel(st)}</div>
        <div class="sub">${esc(e.message || "")}${uptime}</div>${progress}
      </div>
      ${st === "STOPPED" || st === "ERROR" ? `<button class="btn primary" data-action="engine-start">${icon("play")}Démarrer</button>` : ""}
      ${st === "RUNNING" ? `<button class="btn" data-action="engine-stop">${icon("stop")}Arrêter</button>` : ""}
    </div>`;
  }

  /** Courbe : échelle sur le maximum observé (+25 %), plafonnée à [max] si fourni. */
  function spark(values, max) {
    if (values.length < 2) return `<svg class="spark" viewBox="0 0 100 36" preserveAspectRatio="none"></svg>`;
    const peak = Math.max(...values, 1) * 1.25;
    const m = max ? Math.min(max, peak) : peak;
    const pts = values.map((v, i) => [(i / (values.length - 1)) * 100, 34 - (v / m) * 30]);
    const line = pts.map((p, i) => `${i ? "L" : "M"}${p[0].toFixed(1)},${p[1].toFixed(1)}`).join("");
    return `<svg class="spark" viewBox="0 0 100 36" preserveAspectRatio="none"><path class="area" d="${line}L100,36L0,36Z"/><path class="line" d="${line}"/></svg>`;
  }

  function overview() {
    const e = S.status?.engine || {};
    const runningCount = S.containers.filter((c) => c.State === "running").length;
    const cpuMax = (e.cpus || 1) * 100;
    const memMax = (e.memoryMb || 2048) * 1024 * 1024;
    const diskMax = (e.diskGb || 32) * 1024 ** 3;
    const tiles = `<div class="grid grid-tiles">
      <div class="card tile"><span class="label">Processeur de la VM</span>
        <span class="value">${e.cpuPercent != null ? Math.round(e.cpuPercent) : "—"}<small>% · ${e.cpus || "?"} vCPU</small></span>${spark(S.cpuHist, cpuMax)}</div>
      <div class="card tile"><span class="label">Mémoire de la VM</span>
        <span class="value">${e.rssBytes ? bytes(e.rssBytes) : "—"}<small>/ ${bytes(memMax)}</small></span>${spark(S.memHist, memMax)}</div>
      <div class="card tile"><span class="label">Disque Docker</span>
        <span class="value">${bytes(e.diskUsageBytes)}<small>/ ${bytes(diskMax)}</small></span>
        <div class="meter ${e.diskUsageBytes / diskMax > .8 ? "warn" : ""}"><i style="width:${Math.min(100, (e.diskUsageBytes / diskMax) * 100 || 0)}%"></i></div></div>
      <div class="card tile"><span class="label">Conteneurs</span>
        <span class="value">${runningCount}<small>actifs / ${S.containers.length}</small></span>
        <div class="meter ok"><i style="width:${S.containers.length ? (runningCount / S.containers.length) * 100 : 0}%"></i></div></div>
    </div>`;
    const ports = (e.forwardedPorts || []);
    const portsCard = `<div class="card card-pad"><h2>Ports redirigés vers Android</h2>${ports.length
      ? `<div class="ports">${ports.map((p) => { const port = p.split(":")[1].split("/")[0]; return `<button class="chip" data-action="open-port" data-port="${port}">${esc(p)}${icon("ext")}</button>`; }).join("")}</div>`
      : `<div class="muted">Aucun port publié. Un conteneur lancé avec <span class="mono">-p 8080:80</span> apparaîtra ici automatiquement.</div>`}</div>`;
    const pl = pods().slice(0, 6);
    const podsCard = `<div class="card"><div class="row card-pad" style="padding-bottom:6px"><h2 style="margin:0">Pods</h2><span class="spacer"></span><a href="#/pods" class="muted">Tout voir ${icon("chev")}</a></div>
      <div class="list">${pl.length ? pl.map(podRowCompact).join("") : `<div class="list-item muted">${running() ? "Aucun conteneur pour l'instant." : "Démarrez le moteur pour voir vos conteneurs."}</div>`}</div></div>`;
    const info = `<div class="card card-pad"><h2>Système</h2><dl class="kv">
      <dt>Docker</dt><dd>${esc(e.dockerVersion || "—")}</dd>
      <dt>Hôte Docker</dt><dd>${esc(e.dockerHost || "")}</dd>
      <dt>Appareil</dt><dd>${esc(S.status?.device || "")} · Android API ${esc(S.status?.android || "")}</dd>
      <dt>ABI</dt><dd>${esc(S.status?.abi || "")}</dd>
      <dt>Images</dt><dd>${S.images.length} · ${bytes(S.images.reduce((a, i) => a + (i.Size || 0), 0))}</dd>
      <dt>Volumes</dt><dd>${S.volumes.length}</dd>
      <dt>Machines</dt><dd>${S.machines.items.length}</dd>
      <dt>Version</dt><dd>Pocketdock ${esc(S.status?.version || "")}</dd></dl></div>`;
    return engineHero() + tiles + `<div class="grid grid-2">${podsCard}<div class="grid">${portsCard}${info}</div></div>`;
  }

  function podState(p) {
    const n = p.containers.length, up = p.containers.filter((c) => c.State === "running").length;
    if (up === n) return `<span class="pill ok">${n > 1 ? `${up}/${n} actifs` : "actif"}</span>`;
    if (up === 0) return `<span class="pill idle">arrêté</span>`;
    return `<span class="pill warn">${up}/${n} actifs</span>`;
  }
  function podRowCompact(p) {
    const ports = publicPorts(p.containers);
    return `<div class="list-item clickable" data-action="goto-pod" data-key="${esc(p.key)}">
      <div class="pod-icon">${icon(p.compose ? "pods" : "layers")}</div>
      <div class="main-col"><span class="name ellipsis">${esc(p.name)}</span>
        <span class="meta ellipsis">${p.compose ? `${p.containers.length} service(s) · Compose` : esc(p.containers[0].Image)}${ports.length ? ` · ${ports.map((x) => `:${x}`).join(" ")}` : ""}</span></div>
      ${podState(p)}</div>`;
  }

  function projectsCard() {
    const projects = S.compose.projects || [];
    const jobs = S.compose.jobs || [];
    const rows = projects.map((p) => {
      const pod = pods().find((x) => x.compose && x.name === p.name);
      const job = jobs.find((j) => j.project === p.name);
      const busy = job?.state === "RUNNING";
      const state = busy ? `<span class="pill warn busy">${esc(job.action)}…</span>` : pod ? podState(pod) : `<span class="pill idle">non démarré</span>`;
      return `<div class="list-item project-row">
        <div class="pod-icon">${icon("pods")}</div>
        <div class="main-col"><div class="row" style="gap:8px"><span class="name">${esc(p.name)}</span>${state}</div>
          <span class="meta ellipsis">${bytes(p.archiveBytes)} · importé ${ago(p.uploadedAt / 1000)}${job ? ` · dernière opération : ${esc(job.action)} ${job.state === "SUCCEEDED" ? "✓" : job.state === "FAILED" ? "✗" : ""}` : ""}</span></div>
        <div class="actions project-actions">
          <button class="btn primary small" title="docker compose up -d" data-action="compose" data-op="up" data-name="${esc(p.name)}" ${busy ? "disabled" : ""}>${icon("play")}Démarrer</button>
          <button class="btn small" title="docker compose up -d --build" data-action="compose" data-op="build" data-name="${esc(p.name)}" ${busy ? "disabled" : ""}>${icon("restart")}Reconstruire</button>
          <button class="btn small" title="docker compose down" data-action="compose" data-op="down" data-name="${esc(p.name)}" ${busy ? "disabled" : ""}>${icon("stop")}Arrêter</button>
          ${job ? `<button class="icon-btn" title="Sortie de la dernière opération" data-action="open-job" data-id="${esc(job.id)}">${icon("term")}</button>` : ""}
          <button class="icon-btn danger" title="Supprimer le projet" data-action="compose-rm" data-name="${esc(p.name)}" ${busy ? "disabled" : ""}>${icon("trash")}</button>
        </div></div>`;
    }).join("");
    return `<div class="card"><div class="row card-pad" style="padding-bottom:6px"><h2 style="margin:0">Projets Compose</h2><span class="spacer"></span>
      <button class="btn" data-action="compose-import">${icon("down")}Importer un .tar.gz</button></div>
      <div class="list">${rows || `<div class="list-item muted">Importez l'archive d'un dossier contenant un <span class="mono">docker-compose.yml</span>
        (ex. <span class="mono">tar czf projet.tar.gz -C mon-projet .</span>). Il est exécuté dans la VM : les bind mounts relatifs, <span class="mono">build:</span> et <span class="mono">.env</span> fonctionnent.</div>`}</div></div>`;
  }

  function podsView() {
    if (!running()) return engineHero() + emptyState("pods", "Moteur arrêté", "Démarrez le moteur Docker pour gérer vos conteneurs.");
    const list = pods().filter((p) => !p.compose);
    const hint = pods().some((p) => p.compose) ? `<p class="muted" style="margin:0">Les conteneurs des projets Compose sont dans <a href="#/stacks">Stacks</a>.</p>` : "";
    if (!list.length) return emptyState("pods", "Aucun conteneur isolé", `Lancez un conteneur avec le bouton <b>Lancer</b>, ou depuis un terminal : <code>docker run -d -p 8080:80 nginx</code>`,
      `<button class="btn primary" data-action="open-run">${icon("plus")}Lancer un conteneur</button>`) + hint;
    return list.map(podCard).join("") + hint;
  }

  function stacksView() {
    if (!running()) return engineHero() + emptyState("stack", "Moteur arrêté", "Démarrez le moteur Docker pour importer et lancer vos stacks Compose.");
    return projectsCard() + pods().filter((p) => p.compose).map(podCard).join("");
  }

  function podCard(p) {
      const open = S.openPods.has(p.key) || !p.compose;
      const anyUp = p.containers.some((c) => c.State === "running");
      const ids = p.containers.map((c) => c.Id).join(",");
      const busy = S.busy.has(`pod:${p.key}`);
      const ports = publicPorts(p.containers);
      return `<div class="card pod ${open ? "open" : ""}">
        <div class="pod-head" data-action="toggle-pod" data-key="${esc(p.key)}">
          ${p.compose ? icon("chev", "chev") : ""}
          <div class="pod-icon">${icon(p.compose ? "pods" : "layers")}</div>
          <div class="main-col" style="flex:1;min-width:0">
            <div class="row" style="gap:8px"><span class="pod-title ellipsis">${esc(p.name)}</span>${podState(p)}</div>
            <div class="meta muted ellipsis">${p.compose ? `Projet Compose · ${p.containers.length} service(s)` : esc(p.containers[0].Image) + " · " + esc(p.containers[0].Status)}</div>
          </div>
          <div class="ports" data-stop>${ports.map((x) => `<button class="chip" data-action="open-port" data-port="${x}">:${x}${icon("ext")}</button>`).join("")}</div>
          <div class="actions" data-stop>
            ${anyUp
              ? `<button class="icon-btn" title="Arrêter" data-action="pod-stop" data-ids="${ids}" data-key="${esc(p.key)}" ${busy ? "disabled" : ""}>${icon("stop")}</button>`
              : `<button class="icon-btn" title="Démarrer" data-action="pod-start" data-ids="${ids}" data-key="${esc(p.key)}" ${busy ? "disabled" : ""}>${icon("play")}</button>`}
            <button class="icon-btn" title="Redémarrer" data-action="pod-restart" data-ids="${ids}" data-key="${esc(p.key)}" ${busy ? "disabled" : ""}>${icon("restart")}</button>
            <button class="icon-btn danger" title="Supprimer" data-action="pod-rm" data-ids="${ids}" data-key="${esc(p.key)}" data-name="${esc(p.name)}" ${busy ? "disabled" : ""}>${icon("trash")}</button>
          </div>
        </div>
        ${p.compose ? `<div class="pod-body list">${p.containers.map((c) => containerRow(c)).join("")}</div>` : ""}
      </div>`;
  }

  function containerRow(c) {
    const ports = (c.Ports || []).filter((p) => p.PublicPort);
    const up = c.State === "running";
    const busy = S.busy.has(`c:${c.Id}`);
    return `<div class="list-item clickable" data-action="open-container" data-id="${c.Id}">
      <span class="dot ${up ? "ok" : ""}"></span>
      <div class="main-col"><span class="name ellipsis">${esc(service(c))}</span>
        <span class="meta ellipsis">${esc(c.Image)} · ${esc(c.Status)}${ports.length ? " · " + ports.map((p) => `${p.PublicPort}→${p.PrivatePort}`).join(", ") : ""}</span></div>
      <div class="actions" data-stop>
        ${up ? `<button class="icon-btn" title="Arrêter" data-action="c-stop" data-id="${c.Id}" ${busy ? "disabled" : ""}>${icon("stop")}</button>`
             : `<button class="icon-btn" title="Démarrer" data-action="c-start" data-id="${c.Id}" ${busy ? "disabled" : ""}>${icon("play")}</button>`}
        <button class="icon-btn" title="Redémarrer" data-action="c-restart" data-id="${c.Id}" ${busy ? "disabled" : ""}>${icon("restart")}</button>
      </div></div>`;
  }

  function images() {
    if (!running()) return emptyState("layers", "Moteur arrêté", "Démarrez le moteur pour gérer les images.");
    if (!S.images.length) return emptyState("layers", "Aucune image", "Téléchargez une image arm64 depuis Docker Hub.",
      `<button class="btn primary" data-action="open-pull">${icon("down")}Télécharger une image</button>`);
    const total = S.images.reduce((a, i) => a + (i.Size || 0), 0);
    return `<div class="card"><div class="row card-pad" style="padding-bottom:4px"><h2 style="margin:0">${S.images.length} image(s) · ${bytes(total)}</h2><span class="spacer"></span>
      <button class="btn ghost" data-action="prune">Nettoyer</button></div><div class="list">${S.images.map((i) => {
        const tag = (i.RepoTags || []).find((t) => t !== "<none>:<none>") || i.Id.replace("sha256:", "").slice(0, 12);
        const used = S.containers.filter((c) => c.ImageID === i.Id).length;
        return `<div class="list-item">
          <div class="pod-icon">${icon("layers")}</div>
          <div class="main-col"><span class="name ellipsis mono" style="font-size:13px">${esc(tag)}</span>
            <span class="meta">${bytes(i.Size)} · ${ago(i.Created)}${used ? ` · utilisée par ${used} conteneur(s)` : ""}</span></div>
          <div class="actions">
            <button class="icon-btn" title="Lancer" data-action="run-image" data-image="${esc(tag)}">${icon("play")}</button>
            <button class="icon-btn danger" title="Supprimer" data-action="rm-image" data-id="${esc(i.Id)}" data-name="${esc(tag)}">${icon("trash")}</button>
          </div></div>`;
      }).join("")}</div></div>`;
  }

  function volumes() {
    if (!running()) return emptyState("disk", "Moteur arrêté", "Démarrez le moteur pour gérer les volumes.");
    if (!S.volumes.length) return emptyState("disk", "Aucun volume", "Les volumes nommés créés par vos conteneurs ou projets Compose apparaîtront ici.");
    return `<div class="card list">${S.volumes.map((v) => `<div class="list-item">
      <div class="pod-icon">${icon("disk")}</div>
      <div class="main-col"><span class="name ellipsis">${esc(v.Name)}</span>
        <span class="meta ellipsis">${esc(v.Labels?.["com.docker.compose.project"] ? "Compose · " + v.Labels["com.docker.compose.project"] : v.Driver)}</span></div>
      <button class="icon-btn danger" title="Supprimer" data-action="rm-volume" data-name="${esc(v.Name)}">${icon("trash")}</button></div>`).join("")}</div>`;
  }

  function machines() {
    const m = S.machines || {};
    const inst = m.installing;
    const instCard = inst ? `<div class="card card-pad"><div class="row"><span class="dot warn"></span><b>${esc(inst.name)}</b><span class="muted">${esc(inst.message)}</span></div>
      <div class="progress ${inst.progress >= 0 ? "" : "indeterminate"}"><i style="width:${inst.progress >= 0 ? Math.round(inst.progress * 100) : 35}%"></i></div></div>` : "";
    const err = m.lastError ? `<div class="card card-pad" style="border-color:var(--bad)"><b style="color:var(--bad)">Échec</b> <span class="muted">${esc(m.lastError)}</span></div>` : "";
    if (!m.items?.length && !inst) return err + emptyState("machine", "Aucune machine Linux", "Une machine Alpine ou Ubuntu démarre instantanément et peut piloter Docker.",
      `<button class="btn primary" data-action="open-machine">${icon("plus")}Nouvelle machine</button>`);
    return instCard + err + `<div class="card list">${(m.items || []).map((x) => `<div class="list-item clickable" data-action="open-machine-detail" data-name="${esc(x.name)}">
      <div class="pod-icon">${icon("machine")}</div>
      <div class="main-col"><span class="name">${esc(x.name)}</span><span class="meta">${esc(x.label)} · créée ${ago(x.createdAt / 1000)}</span></div>
      <div class="actions" data-stop>
        ${bridge ? `<button class="icon-btn" title="Terminal" data-action="machine-term" data-name="${esc(x.name)}">${icon("term")}</button>` : ""}
        <button class="icon-btn danger" title="Supprimer" data-action="rm-machine" data-name="${esc(x.name)}">${icon("trash")}</button>
      </div></div>`).join("")}</div>`;
  }

  let settingsCache = null;
  function engine() {
    if (!settingsCache) { api("GET", "/settings").then((s) => { settingsCache = s; render(); }).catch(() => {}); }
    const s = settingsCache;
    const form = s ? `<form class="card card-pad form" data-keep data-form="settings">
      <h2>Ressources de la VM <span class="faint" style="font-weight:400">— appliquées au prochain démarrage</span></h2>
      ${rangeField("cpus", "Processeurs", s.cpus, 1, 8, 1, "vCPU")}
      ${rangeField("memoryMb", "Mémoire", s.memoryMb, 512, 8192, 512, "Mo")}
      ${rangeField("diskGb", "Disque (maximum)", s.diskGb, 8, 256, 8, "Go")}
      ${toggleField("autoStart", "Démarrer le moteur avec l'application", s.autoStart)}
      ${toggleField("keepAwake", "Empêcher la mise en veille du processeur", s.keepAwake)}
      ${toggleField("exposeOnLan", "Exposer les ports sur le réseau local", s.exposeOnLan)}
      <div class="row"><span class="spacer"></span><button class="btn primary" type="submit">Enregistrer</button></div>
    </form>` : `<div class="card card-pad muted">Chargement des réglages…</div>`;
    return engineHero() + `<div class="grid grid-2">${form}
      <div class="card card-pad"><div class="row" style="margin-bottom:10px"><h2 style="margin:0">Console de la VM</h2><span class="spacer"></span>
        <button class="btn ghost" data-action="load-console">${icon("restart")}Actualiser</button></div>
        <pre class="term" id="console">${esc(consoleCache || "Cliquez sur Actualiser.")}</pre>
        <div class="row" style="margin-top:14px"><span class="spacer"></span>
        <button class="btn danger" data-action="reset-engine">${icon("trash")}Réinitialiser les données Docker</button></div></div></div>` + apiCard();
  }

  /** Demandes d'accès en attente et applications autorisées (jeton et droits propres). */
  function clientsBlock() {
    const label = (id) => (S.clients.scopes || []).find((x) => x.id === id)?.label || id;
    const chips = (ids) => ids.map((id) => `<span class="pill idle" title="${esc(label(id))}">${esc(id)}</span>`).join(" ");
    const pend = (S.clients.pending || []).map((r) => `<div class="list-item project-row">
        <div class="main-col"><span class="name">« ${esc(r.name)} » demande l'accès</span>
          <span class="meta">${chips(r.scopes)} · nom déclaré, non vérifié</span></div>
        <div class="actions project-actions" style="padding-left:0">
          <button class="btn primary small" data-action="client-approve" data-id="${esc(r.id)}">Autoriser</button>
          <button class="btn small" data-action="client-deny" data-id="${esc(r.id)}">Refuser</button></div></div>`).join("");
    const apps = (S.clients.clients || []).map((c) => `<div class="list-item">
        <div class="main-col"><span class="name">${esc(c.name)}</span>
          <span class="meta">${chips(c.scopes)} · ${c.lastUsedAt ? "utilisée " + ago(c.lastUsedAt / 1000) : "jamais utilisée"}</span></div>
        <div class="actions"><button class="btn small" data-action="client-revoke" data-id="${esc(c.id)}" data-name="${esc(c.name)}">Révoquer</button></div></div>`).join("");
    return `<h3 style="margin:18px 0 6px">Applications autorisées</h3>
      <div class="list" style="border:1px solid var(--line);border-radius:12px">${pend}${apps ||
        (pend ? "" : `<div class="list-item muted">Aucune. Une application demande l'accès avec <span class="mono">POST /v1/pair</span> ; vous l'acceptez ici ou depuis la notification.</div>`)}</div>`;
  }

  /** Jeton de l'API de contrôle, pour piloter Pocketdock depuis une autre application du téléphone. */
  let showToken = false;
  function apiCard() {
    const shown = showToken ? token : `${(token || "").slice(0, 6)}${"•".repeat(18)}`;
    return `<div class="card card-pad"><h2>API de contrôle</h2>
      <h3 style="margin:0 0 6px">Jeton principal</h3>
      <p class="muted" style="margin-top:0">Une application du téléphone peut piloter Pocketdock sur
        <span class="mono">http://127.0.0.1:${location.port || 7777}/v1/</span> avec ce jeton
        (en-tête <span class="mono">Authorization: Bearer …</span>). Le jeton principal donne tous les droits ; pour une
        application, préférez une <b>demande d'accès</b> (<span class="mono">POST /v1/pair</span>) : jeton propre, droits limités, révocable.</p>
      <div class="row" style="gap:8px;flex-wrap:wrap"><input class="input mono" readonly value="${esc(shown)}" style="flex:1 1 220px;min-width:0" aria-label="Jeton">
        <button class="btn" data-action="token-toggle">${showToken ? "Masquer" : "Afficher"}</button>
        <button class="btn primary" data-action="token-copy">Copier</button></div>
      ${clientsBlock()}
      <p class="muted" style="margin:14px 0 6px">Exemple : lancer un conteneur</p>
      <pre class="term">POST /v1/containers/run
Authorization: Bearer &lt;jeton&gt;
Content-Type: application/json

{"image": "nginx:alpine", "ports": ["8080:80"]}</pre>
      <p class="muted" style="margin-bottom:0">Toutes les routes : <a href="#" data-action="open-url" data-url="https://github.com/luraku/Pocketdock/blob/ccr-e93cecaf-5a3oeg/docs/control-api.md">docs/control-api.md</a></p></div>`;
  }
  let consoleCache = "";

  const rangeField = (k, label, v, min, max, step, unit) => `<div class="field"><div class="row"><label for="f-${k}">${label}</label><span class="spacer"></span>
    <span class="range-val" id="v-${k}">${v} ${unit}</span></div>
    <input id="f-${k}" name="${k}" type="range" min="${min}" max="${max}" step="${step}" value="${v}" oninput="document.getElementById('v-${k}').textContent=this.value+' ${unit}'"></div>`;
  const toggleField = (k, label, v) => `<label class="row" style="justify-content:space-between;cursor:pointer"><span>${label}</span>
    <span class="switch"><input type="checkbox" name="${k}" ${v ? "checked" : ""}><span></span></span></label>`;

  const emptyState = (ic, title, body, action = "") => `<div class="card empty">${icon(ic)}<h3>${title}</h3><p>${body}</p>${action}</div>`;

  // ------------------------------------------------------------------ tiroir (détails)
  let drawerCleanup = null;
  function openDrawer(title, body, onOpen) {
    closeDrawer();
    $("#drawer-title").innerHTML = title;
    $("#drawer-body").innerHTML = body;
    $("#drawer").hidden = false;
    drawerCleanup = onOpen?.($("#drawer-body")) || null;
  }
  function closeDrawer() {
    if (drawerCleanup) { drawerCleanup(); drawerCleanup = null; }
    $("#drawer").hidden = true;
  }

  /** Démultiplexe un flux de journaux Docker (en-têtes de 8 octets) ; renvoie [{stream, text}]. */
  function demux(buf) {
    const u = new Uint8Array(buf);
    const dec = new TextDecoder();
    const isMux = u.length >= 8 && u[0] <= 2 && u[1] === 0 && u[2] === 0 && u[3] === 0;
    if (!isMux) return [{ stream: 1, text: dec.decode(u) }];
    const out = [];
    for (let i = 0; i + 8 <= u.length;) {
      const len = (u[i + 4] << 24) | (u[i + 5] << 16) | (u[i + 6] << 8) | u[i + 7];
      out.push({ stream: u[i], text: dec.decode(u.subarray(i + 8, i + 8 + len)) });
      i += 8 + len;
    }
    return out;
  }

  function openContainer(id) {
    const c = S.containers.find((x) => x.Id === id);
    if (!c) return;
    const title = `<div class="row"><span class="dot ${c.State === "running" ? "ok" : ""}"></span><div class="ellipsis"><b>${esc(service(c))}</b>
      <div class="muted mono ellipsis" style="font-size:12px">${esc(c.Image)} · ${c.Id.slice(0, 12)}</div></div></div>`;
    const body = `<div class="row" style="margin-bottom:14px;flex-wrap:wrap">
        ${c.State === "running" ? `<button class="btn" data-action="c-stop" data-id="${id}">${icon("stop")}Arrêter</button>` : `<button class="btn" data-action="c-start" data-id="${id}">${icon("play")}Démarrer</button>`}
        <button class="btn" data-action="c-restart" data-id="${id}">${icon("restart")}Redémarrer</button>
        ${bridge && c.State === "running" ? `<button class="btn" data-action="c-term" data-id="${id}">${icon("term")}Terminal</button>` : ""}
        <span class="spacer"></span>
        <button class="btn danger" data-action="c-rm" data-id="${id}" data-name="${esc(cname(c))}">${icon("trash")}Supprimer</button></div>
      <div class="tabs" id="ctabs">
        <button data-tab="logs" class="active">Journaux</button><button data-tab="stats">Ressources</button>
        <button data-tab="info">Configuration</button><button data-tab="exec">Commande</button></div>
      <div id="ctab"></div>`;
    openDrawer(title, body, (root) => {
      let timer = null, alive = true;
      const tab = (name) => {
        clearInterval(timer); timer = null;
        root.querySelectorAll("#ctabs button").forEach((b) => b.classList.toggle("active", b.dataset.tab === name));
        const box = $("#ctab", root);
        if (name === "logs") {
          box.innerHTML = `<div class="row" style="margin-bottom:8px"><span class="muted">Suivi en direct · 500 dernières lignes</span><span class="spacer"></span>
            <label class="row muted" style="gap:6px;cursor:pointer"><span class="switch"><input type="checkbox" id="follow" checked><span></span></span>Défilement auto</label></div>
            <pre class="term" id="logs"><span class="dim">Chargement…</span></pre>`;
          let since = 0, first = true;
          const pull = async () => {
            try {
              const q = first ? "tail=500" : `since=${since}`;
              const r = await docker("GET", `/containers/${id}/logs?stdout=1&stderr=1&timestamps=0&${q}`, undefined, { raw: true });
              const parts = demux(await r.arrayBuffer());
              since = Math.floor(Date.now() / 1000);
              const pre = $("#logs", root); if (!pre || !alive) return;
              if (first) pre.innerHTML = parts.length ? "" : `<span class="dim">(aucune sortie)</span>`;
              first = false;
              for (const p of parts) pre.insertAdjacentHTML("beforeend", p.stream === 2 ? `<span class="err">${esc(p.text)}</span>` : esc(p.text));
              if ($("#follow", root)?.checked) pre.scrollTop = pre.scrollHeight;
            } catch (e) { const pre = $("#logs", root); if (pre && first) pre.textContent = e.message; }
          };
          pull(); timer = setInterval(pull, 2000);
        } else if (name === "stats") {
          const cpu = [], mem = [];
          box.innerHTML = `<div class="grid grid-tiles" id="stats"><div class="card tile muted">Mesure en cours…</div></div>`;
          let prev = null;
          const pull = async () => {
            try {
              const s = await docker("GET", `/containers/${id}/stats?stream=false`, undefined, { timeout: 15000 });
              const cpuDelta = s.cpu_stats.cpu_usage.total_usage - (prev?.cpu_stats.cpu_usage.total_usage ?? s.precpu_stats.cpu_usage.total_usage);
              const sysDelta = s.cpu_stats.system_cpu_usage - (prev?.cpu_stats.system_cpu_usage ?? s.precpu_stats.system_cpu_usage ?? 0);
              const ncpu = s.cpu_stats.online_cpus || 1;
              const pct = sysDelta > 0 ? (cpuDelta / sysDelta) * ncpu * 100 : 0;
              prev = s;
              const used = (s.memory_stats.usage || 0) - (s.memory_stats.stats?.inactive_file || 0);
              cpu.push(pct); mem.push(used); if (cpu.length > 40) { cpu.shift(); mem.shift(); }
              const net = Object.values(s.networks || {}).reduce((a, n) => [a[0] + n.rx_bytes, a[1] + n.tx_bytes], [0, 0]);
              const el = $("#stats", root); if (!el || !alive) return;
              el.innerHTML = `<div class="card tile"><span class="label">CPU</span><span class="value">${pct.toFixed(1)}<small>%</small></span>${spark(cpu, Math.max(100, ...cpu))}</div>
                <div class="card tile"><span class="label">Mémoire</span><span class="value">${bytes(used)}<small>/ ${bytes(s.memory_stats.limit)}</small></span>${spark(mem)}</div>
                <div class="card tile"><span class="label">Réseau</span><span class="value" style="font-size:18px">↓ ${bytes(net[0])}<br>↑ ${bytes(net[1])}</span></div>
                <div class="card tile"><span class="label">Processus</span><span class="value">${s.pids_stats?.current ?? "—"}</span></div>`;
            } catch (e) { const el = $("#stats", root); if (el) el.innerHTML = `<div class="card tile muted">${esc(e.message)}</div>`; }
          };
          pull(); timer = setInterval(pull, 3000);
        } else if (name === "info") {
          box.innerHTML = `<div class="muted">Chargement…</div>`;
          docker("GET", `/containers/${id}/json`).then((j) => {
            const mounts = (j.Mounts || []).map((m) => `${m.Name || m.Source} → ${m.Destination}${m.RW ? "" : " (lecture seule)"}`);
            const ports = Object.entries(j.NetworkSettings?.Ports || {}).map(([k, v]) => `${k} ← ${(v || []).map((b) => `${b.HostIp || "0.0.0.0"}:${b.HostPort}`).join(", ") || "non publié"}`);
            const nets = Object.entries(j.NetworkSettings?.Networks || {}).map(([k, v]) => `${k} · ${v.IPAddress || "—"}`);
            box.innerHTML = `<dl class="kv">
              <dt>Créé</dt><dd>${esc(new Date(j.Created).toLocaleString("fr-FR"))}</dd>
              <dt>Commande</dt><dd>${esc([...(j.Config.Entrypoint || []), ...(j.Config.Cmd || [])].join(" "))}</dd>
              <dt>Redémarrage</dt><dd>${esc(j.HostConfig.RestartPolicy?.Name || "no")}</dd>
              <dt>Ports</dt><dd>${ports.map(esc).join("<br>") || "—"}</dd>
              <dt>Réseaux</dt><dd>${nets.map(esc).join("<br>") || "—"}</dd>
              <dt>Montages</dt><dd>${mounts.map(esc).join("<br>") || "—"}</dd>
              <dt>Variables</dt><dd>${(j.Config.Env || []).map((e) => esc(e.replace(/(PASSWORD|SECRET|TOKEN|KEY)([^=]*)=.*/i, "$1$2=••••••"))).join("<br>")}</dd>
              <dt>Labels</dt><dd>${Object.entries(j.Config.Labels || {}).map(([k, v]) => esc(`${k}=${v}`)).join("<br>") || "—"}</dd></dl>`;
          }).catch((e) => { box.textContent = e.message; });
        } else if (name === "exec") {
          box.innerHTML = `<form class="form" data-form="exec" data-id="${id}">
            <div class="field"><label>Commande exécutée dans le conteneur (sh -c)</label>
            <div class="row"><input class="input mono" name="cmd" placeholder="ls -la /" autocomplete="off" autocapitalize="off" spellcheck="false">
            <button class="btn primary" type="submit">${icon("term")}Exécuter</button></div></div></form>
            <pre class="term" id="exec-out" style="margin-top:12px"><span class="dim">La sortie s'affichera ici.</span></pre>`;
        }
      };
      root.addEventListener("click", (ev) => { const b = ev.target.closest("#ctabs button"); if (b) tab(b.dataset.tab); });
      tab("logs");
      return () => { alive = false; clearInterval(timer); };
    });
  }

  function openJob(id) {
    openDrawer(`<b>Opération Compose</b><div class="muted mono" style="font-size:12px">${esc(id)}</div>`,
      `<div class="row" id="job-head" style="margin-bottom:12px"><span class="pill busy warn">en cours</span></div><pre class="term" id="job-out" style="max-height:70vh"><span class="dim">…</span></pre>`,
      (root) => {
        let alive = true, timer = null;
        const pull = async () => {
          try {
            const j = await api("GET", `/jobs/${encodeURIComponent(id)}`);
            if (!alive) return;
            const pill = j.state === "RUNNING" ? `<span class="pill warn busy">en cours</span>` : j.state === "SUCCEEDED" ? `<span class="pill ok">réussi</span>` : `<span class="pill bad">échec${j.exitCode != null ? ` (code ${j.exitCode})` : ""}</span>`;
            $("#job-head", root).innerHTML = `${pill}<span class="muted">${esc(j.project)} · ${esc(j.action)} · ${duration((j.endedAt || Date.now()) - j.startedAt)}</span>`;
            const pre = $("#job-out", root);
            const atBottom = pre.scrollTop + pre.clientHeight >= pre.scrollHeight - 30;
            pre.innerHTML = esc(j.output || "") + (j.error ? `<span class="err">\n${esc(j.error)}</span>` : "") || `<span class="dim">(pas encore de sortie)</span>`;
            if (atBottom) pre.scrollTop = pre.scrollHeight;
            if (j.state !== "RUNNING") { clearInterval(timer); refreshAll(); }
          } catch (e) { $("#job-out", root).textContent = e.message; }
        };
        pull(); timer = setInterval(pull, 1500);
        return () => { alive = false; clearInterval(timer); };
      });
  }

  async function composeOp(name, op) {
    const body = op === "build" ? { build: true } : {};
    const path = op === "build" ? "up" : op;
    try {
      const j = await api("POST", `/compose/${encodeURIComponent(name)}/${path}`, body);
      openJob(j.id);
    } catch (e) { if (e.status !== 401) toast(e.message, "bad"); }
    refreshAll();
  }

  function uploadProject(file) {
    const guess = file.name.replace(/\.(tar\.gz|tgz|gz)$/i, "").toLowerCase().replace(/[^a-z0-9_-]+/g, "-").replace(/^-+|-+$/g, "") || "projet";
    openDrawer(`<b>Importer un projet Compose</b><div class="muted" style="font-size:12px">${esc(file.name)} · ${bytes(file.size)}</div>`,
      `<form class="form" data-keep>
        <div class="field"><label>Nom du projet</label><input class="input mono" name="name" value="${esc(guess)}" required autocapitalize="off" spellcheck="false" pattern="[a-z0-9][a-z0-9_-]*">
          <span class="hint">Minuscules, chiffres, - et _. Il préfixe les conteneurs, réseaux et volumes du projet.</span></div>
        <div class="row" style="gap:8px;flex-wrap:wrap">
          <button class="btn primary" type="submit" data-start="1">${icon("play")}Importer et démarrer</button>
          <button class="btn" type="submit" data-start="0">Importer seulement</button></div>
      </form>`,
      (body) => {
        const form = body.querySelector("form");
        const onSubmit = async (e) => {
          e.preventDefault();
          const name = form.name.value.trim();
          const start = e.submitter?.dataset.start === "1";
          form.querySelectorAll("button").forEach((b) => (b.disabled = true));
          toast(`Envoi de ${file.name} dans la VM…`);
          try {
            const r = await fetch(`/v1/compose/${encodeURIComponent(name)}`, { method: "PUT", body: file, headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/gzip" } });
            if (!r.ok) throw new Error((await r.json().catch(() => ({}))).error || `HTTP ${r.status}`);
            toast(`Projet ${name} importé`, "ok");
            closeDrawer();
            if (start) await composeOp(name, "up"); else refreshAll();
          } catch (err) {
            toast(`Import : ${err.message}`, "bad");
            form.querySelectorAll("button").forEach((b) => (b.disabled = false));
          }
        };
        form.addEventListener("submit", onSubmit);
        return () => form.removeEventListener("submit", onSubmit);
      });
  }

  function openMachineDetail(name) {
    openDrawer(`<b>${esc(name)}</b><div class="muted" style="font-size:12px">Machine Linux (proot)</div>`,
      `<form class="form" data-form="machine-exec" data-name="${esc(name)}">
        <div class="field"><label>Commande</label><div class="row">
          <input class="input mono" name="cmd" value="cat /etc/os-release" autocomplete="off" autocapitalize="off" spellcheck="false">
          <button class="btn primary" type="submit">${icon("term")}Exécuter</button></div>
          <span class="hint">Exécutée en root dans la machine, avec <span class="mono">docker</span> et <span class="mono">DOCKER_HOST</span> préconfigurés.</span></div></form>
        ${bridge ? `<button class="btn" data-action="machine-term" data-name="${esc(name)}" style="margin-top:12px">${icon("term")}Ouvrir un terminal interactif</button>` : ""}
        <pre class="term" id="mexec-out" style="margin-top:14px"><span class="dim">La sortie s'affichera ici.</span></pre>`);
  }

  function openRunForm(image = "") {
    openDrawer(`<b>Lancer un conteneur</b>`, `<form class="form" data-form="run">
      <div class="field"><label>Image</label><input class="input mono" name="image" value="${esc(image || "nginx:alpine")}" required autocapitalize="off" spellcheck="false">
        <span class="hint">Téléchargée automatiquement si absente (linux/arm64).</span></div>
      <div class="form-row">
        <div class="field"><label>Nom</label><input class="input" name="name" placeholder="facultatif" autocapitalize="off"></div>
        <div class="field"><label>Ports (hôte:conteneur)</label><input class="input mono" name="ports" placeholder="8080:80, 5353:53/udp"></div></div>
      <div class="field"><label>Variables d'environnement</label><textarea class="input" name="env" placeholder="CLE=valeur (une par ligne)"></textarea></div>
      <div class="field"><label>Volumes</label><input class="input mono" name="volumes" placeholder="donnees:/var/lib/app"></div>
      <div class="field"><label>Commande</label><input class="input mono" name="command" placeholder="facultatif (sh -c)"></div>
      ${toggleField("restartAlways", "Redémarrer automatiquement (unless-stopped)", false)}
      <div class="row"><span class="spacer"></span><button class="btn primary" type="submit">${icon("play")}Lancer</button></div></form>`);
  }

  function openPullForm() {
    openDrawer(`<b>Télécharger une image</b>`, `<form class="form" data-form="pull">
      <div class="field"><label>Référence</label><input class="input mono" name="ref" placeholder="postgres:16-alpine" required autocapitalize="off" spellcheck="false">
      <span class="hint">L'image doit exister pour linux/arm64.</span></div>
      <div class="row" style="flex-wrap:wrap;gap:6px">${["nginx:alpine", "redis:7-alpine", "postgres:16-alpine", "mysql:8.0", "traefik:v3", "python:3.12-slim"]
        .map((x) => `<button type="button" class="chip" data-action="fill" data-target="ref" data-value="${x}">${x}</button>`).join("")}</div>
      <div class="row"><span class="spacer"></span><button class="btn primary" type="submit">${icon("down")}Télécharger</button></div></form>`);
  }

  function openMachineForm() {
    const distros = (S.machines.distros || []);
    openDrawer(`<b>Nouvelle machine Linux</b>`, `<form class="form" data-form="machine">
      <div class="field"><label>Distribution</label><select class="input" name="distro">${distros.map((d) => `<option value="${esc(d.id)}">${esc(d.label)}</option>`).join("")}</select></div>
      <div class="field"><label>Nom</label><input class="input" name="name" value="dev" pattern="[a-z0-9][a-z0-9-]{0,30}" required autocapitalize="off">
        <span class="hint">Minuscules, chiffres et tirets.</span></div>
      <div class="field"><label>URL du rootfs (distribution personnalisée)</label><input class="input mono" name="url" placeholder="https://…/rootfs.tar.xz"></div>
      <div class="row"><span class="spacer"></span><button class="btn primary" type="submit">${icon("plus")}Créer</button></div></form>`);
  }

  // ------------------------------------------------------------------ événements
  document.addEventListener("click", async (ev) => {
    if (ev.target.closest("[data-close]")) return closeDrawer();
    const el = ev.target.closest("[data-action]");
    if (!el) return;
    const stop = ev.target.closest("[data-stop]");
    const a = el.dataset.action;
    if (stop && (a === "toggle-pod" || a === "open-container" || a === "open-machine-detail")) return;
    ev.preventDefault();
    const ids = (el.dataset.ids || "").split(",").filter(Boolean);
    const each = (fn) => Promise.all(ids.map(fn));
    switch (a) {
      case "engine-start": return act("engine", "Démarrage", () => api("POST", "/engine/start"), "Démarrage du moteur demandé");
      case "engine-stop": return act("engine", "Arrêt", () => api("POST", "/engine/stop"), "Arrêt du moteur demandé");
      case "toggle-pod": {
        const k = el.dataset.key;
        S.openPods.has(k) ? S.openPods.delete(k) : S.openPods.add(k);
        try { localStorage.setItem("androdock.openPods", JSON.stringify([...S.openPods])); } catch (_) {}
        return render();
      }
      case "goto-pod": S.openPods.add(el.dataset.key); location.hash = el.dataset.key.startsWith("compose:") ? "#/stacks" : "#/pods"; return;
      case "compose-import": $("#compose-file").click(); return;
      case "token-toggle": showToken = !showToken; render(); return;
      case "client-approve": case "client-deny":
        try {
          S.clients = await api("POST", `/clients/pending/${encodeURIComponent(el.dataset.id)}/${a === "client-approve" ? "approve" : "deny"}`, {});
          toast(a === "client-approve" ? "Accès accordé" : "Demande refusée", "ok");
        } catch (e) { toast(e.message, "bad"); }
        render(); return;
      case "client-revoke":
        if (!confirm(`Retirer l'accès de « ${el.dataset.name} » ? Son jeton cessera de fonctionner.`)) return;
        try { S.clients = await api("DELETE", `/clients/${encodeURIComponent(el.dataset.id)}`); toast("Accès retiré", "ok"); } catch (e) { toast(e.message, "bad"); }
        render(); return;
      case "token-copy":
        try {
          if (bridge?.copyText) bridge.copyText("Jeton Pocketdock", token);
          else await navigator.clipboard.writeText(token);
          toast("Jeton copié", "ok");
        } catch (_) { showToken = true; render(); toast("Copie impossible : sélectionnez le jeton affiché", "bad"); }
        return;
      case "pod-start": return act(`pod:${el.dataset.key}`, "Démarrage", () => each((id) => docker("POST", `/containers/${id}/start`)));
      case "pod-stop": return act(`pod:${el.dataset.key}`, "Arrêt", () => each((id) => docker("POST", `/containers/${id}/stop?t=10`)));
      case "pod-restart": return act(`pod:${el.dataset.key}`, "Redémarrage", () => each((id) => docker("POST", `/containers/${id}/restart?t=10`)));
      case "pod-rm":
        if (!confirm(`Supprimer ${el.dataset.name} (${ids.length} conteneur(s)) ?`)) return;
        return act(`pod:${el.dataset.key}`, "Suppression", () => each((id) => docker("DELETE", `/containers/${id}?force=1`)), `${el.dataset.name} supprimé`);
      case "open-container": return openContainer(el.dataset.id);
      case "c-start": return act(`c:${el.dataset.id}`, "Démarrage", () => docker("POST", `/containers/${el.dataset.id}/start`)).then(() => reopen(el.dataset.id));
      case "c-stop": return act(`c:${el.dataset.id}`, "Arrêt", () => docker("POST", `/containers/${el.dataset.id}/stop?t=10`)).then(() => reopen(el.dataset.id));
      case "c-restart": return act(`c:${el.dataset.id}`, "Redémarrage", () => docker("POST", `/containers/${el.dataset.id}/restart?t=10`), "Redémarré");
      case "c-rm":
        if (!confirm(`Supprimer ${el.dataset.name} ?`)) return;
        closeDrawer();
        return act(`c:${el.dataset.id}`, "Suppression", () => docker("DELETE", `/containers/${el.dataset.id}?force=1&v=1`), `${el.dataset.name} supprimé`);
      case "c-term": return bridge?.openTerminal("exec", el.dataset.id);
      case "machine-term": return bridge?.openTerminal("machine", el.dataset.name);
      case "open-port": {
        const url = `http://127.0.0.1:${el.dataset.port}/`;
        return bridge ? bridge.openUrl(url) : window.open(url, "_blank");
      }
      case "open-url": ev.preventDefault(); return bridge ? bridge.openUrl(el.dataset.url) : window.open(el.dataset.url, "_blank");
      case "open-run": return openRunForm();
      case "run-image": return openRunForm(el.dataset.image);
      case "open-pull": return openPullForm();
      case "fill": { const f = el.closest("form"); f.elements[el.dataset.target].value = el.dataset.value; return; }
      case "rm-image":
        if (!confirm(`Supprimer l'image ${el.dataset.name} ?`)) return;
        return act(`img:${el.dataset.id}`, "Suppression", () => docker("DELETE", `/images/${encodeURIComponent(el.dataset.id)}?force=1`), "Image supprimée");
      case "rm-volume":
        if (!confirm(`Supprimer le volume ${el.dataset.name} et ses données ?`)) return;
        return act(`vol:${el.dataset.name}`, "Suppression", () => docker("DELETE", `/volumes/${encodeURIComponent(el.dataset.name)}?force=1`), "Volume supprimé");
      case "prune": return act("prune", "Nettoyage", async () => {
        await docker("POST", "/containers/prune"); await docker("POST", `/images/prune?filters=${encodeURIComponent('{"dangling":["true"]}')}`);
      }, "Nettoyage terminé");
      case "compose": return composeOp(el.dataset.name, el.dataset.op);
      case "compose-rm": {
        const vols = confirm(`Supprimer le projet ${el.dataset.name} ?\n\nOK : supprimer aussi ses volumes (données).\nAnnuler : garder les volumes.`);
        try { const j = await api("DELETE", `/compose/${encodeURIComponent(el.dataset.name)}?volumes=${vols ? 1 : 0}`); openJob(j.id); } catch (e) { toast(e.message, "bad"); }
        return refreshAll();
      }
      case "open-job": return openJob(el.dataset.id);
      case "open-machine": return openMachineForm();
      case "open-machine-detail": return openMachineDetail(el.dataset.name);
      case "rm-machine":
        if (!confirm(`Supprimer la machine ${el.dataset.name} et tout son système de fichiers ?`)) return;
        return act(`m:${el.dataset.name}`, "Suppression", () => api("DELETE", `/machines/${encodeURIComponent(el.dataset.name)}`), "Machine supprimée");
      case "load-console": {
        try { consoleCache = (await api("GET", "/engine/console?tail=400")).lines.join("\n"); } catch (e) { consoleCache = e.message; }
        render(); const pre = $("#console"); if (pre) pre.scrollTop = pre.scrollHeight; return;
      }
      case "reset-engine":
        if (!confirm("Supprimer TOUS les conteneurs, images et volumes Docker ?")) return;
        return act("reset", "Réinitialisation", () => api("POST", "/engine/reset"), "Réinitialisation demandée");
    }
  });
  const reopen = (id) => { if (!$("#drawer").hidden) openContainer(id); };

  document.addEventListener("submit", async (ev) => {
    const f = ev.target.closest("form[data-form]");
    if (!f) return;
    ev.preventDefault();
    const d = Object.fromEntries(new FormData(f));
    const split = (s) => (s || "").split(/[,\n]/).map((x) => x.trim()).filter(Boolean);
    const btn = f.querySelector("[type=submit]"); if (btn) btn.disabled = true;
    try {
      switch (f.dataset.form) {
        case "settings": {
          const body = {}; for (const k of ["cpus", "memoryMb", "diskGb"]) body[k] = Number(d[k]);
          for (const k of ["autoStart", "keepAwake", "exposeOnLan"]) body[k] = f.elements[k].checked;
          settingsCache = await api("PATCH", "/settings", body); f.removeAttribute("data-dirty"); toast("Réglages enregistrés", "ok"); break;
        }
        case "run":
          toast(`Lancement de ${d.image}… (téléchargement si nécessaire)`);
          await api("POST", "/containers/run", { image: d.image, name: d.name || null, ports: split(d.ports), env: split(d.env), volumes: split(d.volumes), command: d.command || null, restartAlways: f.elements.restartAlways.checked }, { timeout: 15 * 60000 });
          closeDrawer(); toast(`${d.image} lancé`, "ok"); location.hash = "#/pods"; break;
        case "pull":
          closeDrawer(); toast(`Téléchargement de ${d.ref}…`);
          await api("POST", "/images/pull", { ref: d.ref }, { timeout: 30 * 60000 }); toast(`${d.ref} téléchargée`, "ok"); break;
        case "machine":
          await api("POST", "/machines", { name: d.name, distro: d.distro, url: d.url || undefined });
          closeDrawer(); toast(`Création de ${d.name}…`); break;
        case "exec": case "machine-exec": {
          const out = $(f.dataset.form === "exec" ? "#exec-out" : "#mexec-out");
          out.innerHTML = `<span class="dim">$ ${esc(d.cmd)}\n…</span>`;
          const r = f.dataset.form === "exec"
            ? await api("POST", "/exec", { cmd: `docker exec ${f.dataset.id} sh -c ${shq(d.cmd)}`, timeoutSec: 120 }, { timeout: 130000 })
            : await api("POST", `/machines/${encodeURIComponent(f.dataset.name)}/exec`, { cmd: d.cmd, timeoutSec: 300 }, { timeout: 310000 });
          out.innerHTML = `<span class="dim">$ ${esc(d.cmd)}</span>\n${esc(r.stdout)}${r.stderr ? `<span class="err">${esc(r.stderr)}</span>` : ""}<span class="dim">\n— code ${r.exitCode}${r.timedOut ? " (délai dépassé)" : ""} · ${r.durationMs} ms</span>`;
          break;
        }
      }
    } catch (e) { if (e.status !== 401) toast(e.message, "bad"); }
    finally { if (btn) btn.disabled = false; refreshAll(); }
  });
  const shq = (s) => `'${String(s).replace(/'/g, `'\\''`)}'`;
  document.addEventListener("change", (e) => {
    if (e.target.matches("[data-upload]") && e.target.files[0]) { uploadProject(e.target.files[0]); e.target.value = ""; }
  });
  document.addEventListener("input", (e) => { e.target.closest("[data-keep]")?.setAttribute("data-dirty", "1"); });
  document.addEventListener("keydown", (e) => { if (e.key === "Escape") closeDrawer(); });

  // ------------------------------------------------------------------ connexion
  function showLogin(msg = "") {
    token = null;
    try { localStorage.removeItem("androdock.token"); } catch (_) {}
    document.body.innerHTML = `<div class="login"><form class="card form" id="login">
      <div class="brand" style="padding:0 0 8px"><svg viewBox="0 0 32 32" class="brand-logo"><rect x="5" y="9" width="6" height="6" rx="1.2" fill="currentColor"/><rect x="12.5" y="9" width="6" height="6" rx="1.2" fill="currentColor"/><rect x="20" y="9" width="6" height="6" rx="1.2" fill="currentColor"/><path d="M2 17h26c1.6 0 2.4-2.4 4-2.4 0 3.2-2.4 4.8-4 4.8-2.4 5.6-8 8.8-15.2 8.8C6.4 28.2 2.8 24 2 17z" fill="currentColor" opacity=".55"/></svg>Pocketdock</div>
      <div class="muted">Collez le jeton de l'API de contrôle (onglet <b>Moteur</b> de l'application → Copier).</div>
      ${msg ? `<div style="color:var(--bad)">${esc(msg)}</div>` : ""}
      <input class="input mono" name="token" placeholder="jeton" autocomplete="off" required>
      <button class="btn primary" type="submit" style="justify-content:center">Se connecter</button></form></div>`;
    document.getElementById("login").addEventListener("submit", (e) => {
      e.preventDefault();
      const t = new FormData(e.target).get("token").trim();
      try { localStorage.setItem("androdock.token", t); } catch (_) {}
      location.hash = "#/overview"; location.reload();
    });
  }

  if (!token) showLogin();
  else { if (!location.hash.startsWith("#/")) location.hash = "#/overview"; render(); refreshDocker(true).then(loop); }
})();
