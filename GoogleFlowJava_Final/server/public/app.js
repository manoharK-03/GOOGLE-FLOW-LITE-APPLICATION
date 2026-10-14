// Google Flow front end - plain JS. Talks to GoogleFlowWebServer (/api/<cmd>), which
// forwards every call over a TCP socket to the Java backend.
const state = {
  token: localStorage.getItem("gf_token"), userName: localStorage.getItem("gf_user"),
  pollTimer: null, clusterTimer: null, playerTimer: null, extendOf: null, clips: {},
};
const $ = (id) => document.getElementById(id);

async function api(path, body) {
  const opts = { method: "POST", headers: { "Content-Type": "application/json" },
                 body: JSON.stringify({ token: state.token, ...(body || {}) }) };
  const res = await fetch("/api/" + path, opts);
  const j = await res.json();
  if (res.status === 401 && state.token) logout(true);
  return j;
}
function escapeHtml(s) { const d = document.createElement("div"); d.textContent = s ?? ""; return d.innerHTML; }

// ------------------------------------------------------------ auth --
document.querySelectorAll(".tab-btn").forEach((b) => b.addEventListener("click", () => {
  document.querySelectorAll(".tab-btn").forEach((x) => x.classList.remove("active"));
  b.classList.add("active");
  $("login-form").classList.toggle("hidden", b.dataset.tab !== "login");
  $("signup-form").classList.toggle("hidden", b.dataset.tab !== "signup");
  $("auth-message").textContent = "";
}));
function authMsg(ok, t) { $("auth-message").style.color = ok ? "#1ed760" : "#ff5c5c"; $("auth-message").textContent = t; }

$("signup-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const r = await api("register", { name: $("signup-name").value, email: $("signup-email").value, password: $("signup-password").value });
  if (r.status === "ok") { document.querySelector('.tab-btn[data-tab="login"]').click(); authMsg(true, "Registered! Now log in."); }
  else authMsg(false, r.message);
});
$("login-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const r = await api("login", { email: $("login-email").value, password: $("login-password").value });
  if (r.status !== "ok") return authMsg(false, r.message);
  state.token = r.token; state.userName = r.user.name;
  localStorage.setItem("gf_token", state.token); localStorage.setItem("gf_user", state.userName);
  enterApp();
});
async function logout(silent) {
  if (!silent) await api("logout");
  state.token = null; localStorage.removeItem("gf_token"); localStorage.removeItem("gf_user");
  $("auth-screen").classList.remove("hidden"); $("app-screen").classList.add("hidden");
}
$("logout-btn").addEventListener("click", () => logout(false));
function enterApp() {
  $("auth-screen").classList.add("hidden"); $("app-screen").classList.remove("hidden");
  $("welcome-name").textContent = "Signed in as " + state.userName;
  show("home");
}

// ------------------------------------------------------ navigation --
document.querySelectorAll(".nav-btn").forEach((b) => b.addEventListener("click", () => show(b.dataset.view)));
function show(view) {
  document.querySelectorAll(".nav-btn").forEach((b) => b.classList.toggle("active", b.dataset.view === view));
  document.querySelectorAll(".view").forEach((v) => v.classList.add("hidden"));
  $("view-" + view).classList.remove("hidden");
  clearInterval(state.clusterTimer);
  ({ home: loadHome, library: loadLibrary, playlists: loadPlaylists, history: loadHistory,
     account: loadQuota, cluster: loadCluster, scenes: loadScenes, assets: loadAssets, search: () => {}, create: () => {}, image: () => {}, i2v: () => {} })[view]();
}

// ------------------------------------------------------ clip cards --
const isVideoFile = (url) => /\.(mp4|webm)(\?|$)/i.test(url);
function mediaEl(url) {      // AI video clips are .mp4/.webm, local clips and images are .gif/.png
  return isVideoFile(url) ? `<video src="${url}" autoplay loop muted playsinline controls preload="metadata"></video>`
                          : `<img src="${url}" alt="clip" loading="lazy" />`;
}
function clipCard(a, own) {
  state.clips[a.asset_id] = a;
  const acts = own ? `
    <button data-act="like" data-id="${a.asset_id}">${a.liked ? "♥" : "♡"} Like</button>
    <button data-act="publish" data-id="${a.asset_id}">${a.public ? "🌐 Unpublish" : "🌐 Publish"}</button>
    <button data-act="playlist" data-id="${a.asset_id}">➕ Playlist</button>
    ${a.kind === "image" ? "" : `<button data-act="extend" data-id="${a.asset_id}">⏩ Extend</button>`}
    <a href="${a.media_url}" download>⬇ Save</a>
    <button data-act="delete" data-id="${a.asset_id}" class="danger">🗑</button>` : `<a href="${a.media_url}" download>⬇ Save</a>`;
  return `<div class="clip-card">${mediaEl(a.media_url)}
    <div class="meta"><div class="clip-title">${escapeHtml(a.prompt)}</div>
    <div class="duration">${a.kind === "image" ? "🖼 image" : "🎬 " + a.duration + "s"} · ${a.style} · by ${escapeHtml(a.author)}${a.public ? " · 🌐" : ""}</div></div>
    <div class="clip-actions">${acts}</div></div>`;
}
function fillGrid(id, items, own, emptyText) {
  $(id).innerHTML = items.length ? items.map((a) => clipCard(a, own)).join("") : `<p class="subtle">${emptyText}</p>`;
}
document.addEventListener("click", async (e) => {
  const b = e.target.closest("button[data-act]");
  if (!b) return;
  const id = b.dataset.id, act = b.dataset.act;
  if (act === "like" || act === "publish") { await api(act, { asset_id: id }); refreshCurrent(); }
  else if (act === "delete") { if (confirm("Delete this clip?")) { await api("delete", { asset_id: id }); refreshCurrent(); } }
  else if (act === "extend") { state.extendOf = id; $("extend-banner").textContent = "Extending: " + state.clips[id].prompt;
    $("extend-banner").classList.remove("hidden"); show("create"); }
  else if (act === "playlist") openModal(id);
});
function refreshCurrent() {
  const v = document.querySelector(".nav-btn.active").dataset.view; show(v);
}

// ------------------------------------------------------------ home --
async function loadHome() {
  const r = await api("home");
  if (r.status !== "ok") return;
  $("home-stats").textContent = `${r.stats.clips} clips · ${r.stats.playlists} playlists · ${r.stats.jobs} generations`;
  fillGrid("home-recent", r.recent, true, "Nothing yet — try Text → Video or Text → Image!");
  fillGrid("home-tv", r.tv, false, "Nothing published yet. Publish a clip from your Library.");
}

// ---------------------------------------------------------- search --
async function doSearch() {
  const r = await api("search", { q: $("search-input").value });
  fillGrid("search-grid", r.results || [], false, "No matches.");
}
$("search-btn").addEventListener("click", doSearch);
$("search-input").addEventListener("keydown", (e) => { if (e.key === "Enter") doSearch(); });

// ---------------------------------------------------------- create --
$("generate-btn").addEventListener("click", async () => {
  const prompt = $("prompt-input").value.trim();
  if (!prompt) return alert("Please type a prompt first.");
  $("generate-btn").disabled = true;
  const r = await api("submit", { prompt_text: prompt, style: $("style-select").value, camera: $("camera-select").value,
    aspect: $("aspect-select").value, model: $("model-select").value, extend_of: state.extendOf || "" });
  if (r.status !== "ok") { alert(r.message); $("generate-btn").disabled = false; return; }
  state.extendOf = null; $("extend-banner").classList.add("hidden");
  $("job-status-box").classList.remove("hidden");
  $("job-status-text").textContent = "Status: Waiting";
  $("job-status-sub").textContent = "Job " + r.job_id.slice(0, 8) + "…";
  poll(r.job_id, "video");
});

// ----------------------------------------------- text -> image (NEW) --
$("img-generate-btn").addEventListener("click", async () => {
  const prompt = $("img-prompt-input").value.trim();
  if (!prompt) return alert("Please type a prompt first.");
  $("img-generate-btn").disabled = true;
  $("img-result").classList.add("hidden");
  const r = await api("submit", { kind: "image", prompt_text: prompt, style: $("img-style-select").value,
    aspect: $("img-aspect-select").value, model: $("img-model-select").value });
  if (r.status !== "ok") { alert(r.message); $("img-generate-btn").disabled = false; return; }
  $("img-status-box").classList.remove("hidden");
  $("img-status-text").textContent = "Status: Waiting";
  $("img-status-sub").textContent = "Job " + r.job_id.slice(0, 8) + "…";
  poll(r.job_id, "image");
});
async function showImageResult(jobId) {          // find the finished picture in the library and show it big
  const lib = await api("library");
  const a = (lib.library || []).find((x) => x.job_id === jobId || x.media_url.includes(jobId));
  if (!a) return;
  $("img-result-img").src = a.media_url + "?t=" + Date.now();
  $("img-result").classList.remove("hidden");
}
// ------------------------------------------------ image -> video (NEW) --
let i2vB64 = null;
$("i2v-file").addEventListener("change", (e) => {          // shrink the picture in the browser (max 1024 px, JPEG) before it is sent over the socket
  const f = e.target.files[0]; i2vB64 = null;
  if (!f) return;
  const url = URL.createObjectURL(f), im = new Image();
  im.onload = () => {
    const k = Math.min(1, 1024 / Math.max(im.width, im.height));
    const c = document.createElement("canvas"); c.width = Math.round(im.width * k); c.height = Math.round(im.height * k);
    const g = c.getContext("2d"); g.fillStyle = "#fff"; g.fillRect(0, 0, c.width, c.height); g.drawImage(im, 0, 0, c.width, c.height);
    const d = c.toDataURL("image/jpeg", 0.9); i2vB64 = d.split(",")[1];
    $("i2v-preview-img").src = d; $("i2v-preview").classList.remove("hidden"); URL.revokeObjectURL(url);
  };
  im.onerror = () => alert("That file is not a readable image.");
  im.src = url;
});
$("i2v-generate-btn").addEventListener("click", async () => {
  if (!i2vB64) return alert("Please choose an image first.");
  $("i2v-generate-btn").disabled = true;
  $("i2v-result").classList.add("hidden");
  const r = await api("submit", { kind: "i2v", image_b64: i2vB64, prompt_text: $("i2v-prompt").value.trim(), seconds: $("i2v-seconds").value });
  if (r.status !== "ok") { alert(r.message); $("i2v-generate-btn").disabled = false; return; }
  $("i2v-status-box").classList.remove("hidden");
  $("i2v-status-text").textContent = "Status: Waiting";
  $("i2v-status-sub").textContent = "Job " + r.job_id.slice(0, 8) + "… (AI video can take 1–3 minutes)";
  poll(r.job_id, "i2v");
});
async function showI2vResult(jobId) {
  const lib = await api("library");
  const a = (lib.library || []).find((x) => x.media_url.includes(jobId));
  if (!a) return;
  $("i2v-result").innerHTML = mediaEl(a.media_url + "?t=" + Date.now());
  $("i2v-result").classList.remove("hidden");
}

function poll(jobId, kind) {
  clearTimeout(state.pollTimer);
  const img = kind === "image", i2v = kind === "i2v", scn = kind === "scene";
  const P = img ? "img-" : i2v ? "i2v-" : scn ? "scene-" : "job-", box = P + "status-box",
        btn = img ? "img-generate-btn" : i2v ? "i2v-generate-btn" : scn ? "scene-generate-btn" : "generate-btn";
  const spinner = document.querySelector("#" + box + " .spinner");
  spinner.className = "spinner";                     // reset to spinning for a new job
  const tick = async () => {
    const r = await api("status", { job: jobId });
    if (r.status !== "ok") return;
    const j = r.job;
    const done = j.status === "Completed", failed = j.status === "Failed";
    $(P + "status-text").textContent = done
      ? "Status: Done ✓"
      : "Status: " + j.status + (j.retries ? ` (retry ${j.retries})` : "");
    if (done || failed) {
      spinner.className = "spinner " + (done ? "done" : "failed");   // green tick or red cross
      $(btn).disabled = false;
      $(P + "status-sub").textContent = done
        ? `Done — rendered by ${j.node}${j.engine ? " using " + j.engine : ""}. See your Library.`
        : scn ? "Failed after retries — check the server log (a clip may have been deleted)." : "Failed after retries — quota refunded.";
      if (done && img) showImageResult(jobId);
      if (done && i2v) showI2vResult(jobId);
      if (done && scn) showSceneResult(jobId);
      return;
    }
    state.pollTimer = setTimeout(tick, 1000);
  };
  tick();
}

// --------------------------------------------------------- library --
async function loadLibrary() {
  const r = await api("library");
  const f = $("lib-filter").value;
  const items = (r.library || []).filter((a) => f === "all" || (a.kind || "video") === f);
  fillGrid("library-grid", items, true, "Nothing here yet — generate your first clip or image!");
}
$("lib-filter").addEventListener("change", loadLibrary);

// ------------------------------------------------------- playlists --
$("pl-create").addEventListener("click", async () => {
  const name = $("pl-name").value.trim(); if (!name) return;
  await api("pl_create", { name }); $("pl-name").value = ""; loadPlaylists();
});
async function loadPlaylists() {
  const r = await api("pl_list");
  const box = $("pl-list");
  if (!(r.playlists || []).length) { box.innerHTML = '<p class="subtle">No playlists yet.</p>'; return; }
  state.playlists = r.playlists;
  box.innerHTML = r.playlists.map((p, i) => `<div class="pl-box"><div class="pl-head"><h3>${escapeHtml(p.name)} <span class="subtle small">(${p.clips.length} clips)</span></h3>
    <div><button class="ghost-btn" data-pl-play="${i}">▶ Play all</button> <button class="ghost-btn" data-pl-del="${p.playlist_id}">Delete</button></div></div>
    <div class="card-grid">${p.clips.map((a) => `<div class="clip-card">${mediaEl(a.media_url)}<div class="meta"><div class="clip-title">${escapeHtml(a.prompt)}</div></div>
    <div class="clip-actions"><button data-pl-rm="${p.playlist_id}|${a.asset_id}" class="danger">Remove</button></div></div>`).join("") || '<p class="subtle">Empty — add clips from your Library.</p>'}</div></div>`).join("");
}
document.addEventListener("click", async (e) => {
  const t = e.target;
  if (t.dataset.plDel) { await api("pl_delete", { playlist_id: t.dataset.plDel }); loadPlaylists(); }
  if (t.dataset.plRm) { const [p, a] = t.dataset.plRm.split("|"); await api("pl_remove", { playlist_id: p, asset_id: a }); loadPlaylists(); }
  if (t.dataset.plPlay) playAll(state.playlists[+t.dataset.plPlay]);
});
function playAll(pl) {   // Scenebuilder-style: play the clips back to back
  clearTimeout(state.playerTimer);
  if (!pl.clips.length) return alert("This playlist is empty.");
  $("player").classList.remove("hidden");
  let i = 0;
  const next = () => {
    const a = pl.clips[i % pl.clips.length];
    const vid = isVideoFile(a.media_url);
    $("player-img").classList.toggle("hidden", vid); $("player-vid").classList.toggle("hidden", !vid);
    if (vid) { $("player-vid").src = a.media_url + "?t=" + Date.now(); $("player-vid").play().catch(() => {}); }
    else $("player-img").src = a.media_url + "?t=" + Date.now();
    $("player-cap").textContent = `${pl.name} — clip ${(i % pl.clips.length) + 1}/${pl.clips.length}: ${a.prompt}`;
    i++;
    if (i <= pl.clips.length) state.playerTimer = setTimeout(next, Math.max(2000, a.duration * 1000 * 1.5)); // play each ~1.5 loops
    else $("player").classList.add("hidden");
  };
  next();
}
$("player-stop").addEventListener("click", () => { clearTimeout(state.playerTimer); $("player").classList.add("hidden"); });

async function openModal(assetId) {
  const r = await api("pl_list");
  const l = r.playlists || [];
  $("modal-list").innerHTML = l.length ? l.map((p) => `<button class="ghost-btn block" data-pick="${p.playlist_id}">${escapeHtml(p.name)}</button>`).join("")
    : '<p class="subtle">Create a playlist first (Playlists tab).</p>';
  $("modal").classList.remove("hidden");
  $("modal-list").onclick = async (e) => {
    const id = e.target.dataset.pick; if (!id) return;
    await api("pl_add", { playlist_id: id, asset_id: assetId }); $("modal").classList.add("hidden");
  };
}
$("modal-close").addEventListener("click", () => $("modal").classList.add("hidden"));

// --------------------------------------------------------- history --
async function loadHistory() {
  const r = await api("history");
  $("history-body").innerHTML = (r.history || []).map((j) => `<tr><td>${j.kind === "image" ? "🖼 Image" : j.kind === "i2v" ? "📷 Image→Video" : j.kind === "scene" ? "✂️ Scene movie" : "🎬 Video"}</td><td>${escapeHtml(j.prompt_text)}</td>
    <td>${j.kind === "image" ? `${j.style} · ${j.aspect} · ${j.model}` : j.kind === "i2v" ? `${j.seconds}s · ${escapeHtml(j.engine || "…")}` : j.kind === "scene" ? `${j.scene_count} scene(s) · ${j.aspect} · ${escapeHtml(j.engine || "…")}` : `${j.style} · ${j.camera} · ${j.aspect} · ${j.model}`}</td>
    <td><span class="status-pill status-${j.status}">${j.status}</span></td><td>${j.node || "—"}</td><td>${j.retries}</td></tr>`).join("");
}

// --------------------------------------------------------- cluster --
async function loadCluster() {
  const draw = async () => {
    const r = await api("cluster");
    if (r.status !== "ok") return;
    $("cluster-summary").textContent = `Job queue length: ${r.queue_length} · dispatcher threads: ${r.dispatchers}`;
    $("cluster-body").innerHTML = r.nodes.map((n) => `<tr><td>${n.name}</td>
      <td><span class="status-pill ${n.up ? "status-Completed" : "status-Failed"}">${n.up ? "UP" : "DOWN"}</span></td>
      <td>${n.active}</td><td>${n.done}</td><td>${n.failed}</td>
      <td><button class="ghost-btn" data-node="${n.port}">${n.up ? "Crash" : "Revive"}</button></td></tr>`).join("");
  };
  await draw();
  state.clusterTimer = setInterval(draw, 2000);
}
document.addEventListener("click", async (e) => {
  if (e.target.dataset.node) { const r = await api("node_toggle", { port: e.target.dataset.node }); if (r.status !== "ok") alert(r.message); }
});

// ----------------------------------------------------------- quota --
async function loadQuota() {
  const r = await api("quota");
  if (r.status !== "ok") return;
  $("q-plan").textContent = r.plan_type; $("q-month").textContent = r.month;
  $("q-used").textContent = r.generations_used + " / " + r.generations_allowed;
  $("q-bar").style.width = Math.min(100, (r.generations_used / r.generations_allowed) * 100) + "%";
}

if (state.token) enterApp();


// ----------------------------------------- AI Prompt Assistant (NEW) --
// Adds "✨ Enhance with AI" under a prompt box: simple idea -> cinematic prompt (backend -> worker node -> LLM).
function attachAssistant(textareaId, target, styleSelectId) {
  const ta = $(textareaId);
  const box = document.createElement("div");
  box.className = "assistant";
  box.innerHTML = `
    <div class="row"><button type="button" class="ghost-btn assist-go">✨ Enhance with AI</button>
      <span class="subtle small assist-msg">Type a simple idea, then let AI turn it into a cinematic prompt.</span></div>
    <div class="assistant-out hidden">
      <div class="assistant-label subtle small"></div>
      <div class="assistant-text"></div>
      <div class="row"><button type="button" class="primary-btn assist-use">Use this prompt</button>
        <button type="button" class="ghost-btn assist-again">🔄 Another version</button>
        <button type="button" class="ghost-btn assist-close">Dismiss</button></div>
    </div>`;
  ta.insertAdjacentElement("afterend", box);
  const q = (c) => box.querySelector(c);
  let idea = "", seed = 0, suggestion = "";
  async function run(fresh) {
    if (fresh) { idea = ta.value.trim(); seed = Date.now(); } else seed += 1;
    if (!idea) { q(".assist-msg").textContent = "Type an idea in the box first."; return; }
    q(".assist-go").disabled = q(".assist-again").disabled = true;
    q(".assist-msg").textContent = "✨ Writing a cinematic prompt…";
    try {
      const r = await api("enhance", { idea, target, seed, style: styleSelectId ? $(styleSelectId).value : "" });
      if (r.status !== "ok") { q(".assist-msg").textContent = r.message || "Assistant failed"; return; }
      suggestion = r.prompt;
      q(".assistant-text").textContent = suggestion;
      q(".assistant-label").textContent = `Written by ${r.engine} · ${r.node}`;
      q(".assistant-out").classList.remove("hidden");
      q(".assist-msg").textContent = "";
    } catch (e) {
      q(".assist-msg").textContent = "Assistant unreachable - try again.";
    } finally {
      q(".assist-go").disabled = q(".assist-again").disabled = false;
    }
  }
  q(".assist-go").addEventListener("click", () => run(true));
  q(".assist-again").addEventListener("click", () => run(false));
  q(".assist-use").addEventListener("click", () => {
    ta.value = suggestion;
    q(".assistant-out").classList.add("hidden");
    q(".assist-msg").textContent = "✓ Prompt applied - you can still edit it.";
  });
  q(".assist-close").addEventListener("click", () => q(".assistant-out").classList.add("hidden"));
}
attachAssistant("prompt-input", "video", "style-select");
attachAssistant("img-prompt-input", "image", "img-style-select");
attachAssistant("i2v-prompt", "i2v", null);


// ---------------------------------------------- Scene Builder / Video Timeline (NEW) --
// Timeline = ordered list of {asset_id, start, end, hold}. "Preview" asks the backend for a quick low-res render of the
// exact sequence; "Generate movie" queues a real "scene" job that is load-balanced to a worker node like every other job.
const sb = { items: [], lib: [], caps: null, uid: 0, dragFrom: null, restored: false };
const SB_STEP = 0.08;                                         // one output frame = 0.08 s
const sbAsset = (id) => sb.lib.find((a) => a.asset_id === id);
const sbIsImg = (a) => a && a.kind === "image";
const sbDur = (a) => (a && !sbIsImg(a) ? a.duration || 0 : 0);
const sbFmt = (n) => (Math.round(n * 100) / 100).toFixed(2) + " s";
function sbThumb(a) {
  return isVideoFile(a.media_url) ? `<video src="${a.media_url}" muted loop autoplay playsinline preload="metadata"></video>`
                                   : `<img src="${a.media_url}" alt="clip" loading="lazy" />`;
}
function sbLen(it) {                                          // seconds this scene adds to the movie
  const a = sbAsset(it.asset_id);
  if (sbIsImg(a)) return it.hold;
  const d = sbDur(a), e = it.end > 0 ? Math.min(it.end, d || it.end) : d;
  return Math.max(0, e - it.start);
}
const sbKey = () => "gf_scene_" + (state.userName || "");
function sbSave() {
  try { localStorage.setItem(sbKey(), JSON.stringify({ title: $("sb-title").value, aspect: $("sb-aspect").value, format: $("sb-format").value,
    items: sb.items })); } catch (e) { /* storage full / disabled: timeline just isn't remembered */ }
}
async function loadScenes() {
  const [lib, pls] = await Promise.all([api("library"), api("pl_list")]);
  sb.lib = (lib.library || []);
  if (!sb.caps) {                                             // what can the server do? (MP4 output needs ffmpeg)
    const c = await api("scene_info");
    sb.caps = c.status === "ok" ? c : { mp4: false };
    $("sb-format").innerHTML = '<option value="gif">Output: GIF</option>' + (sb.caps.mp4 ? '<option value="mp4">Output: MP4</option>' : "");
  }
  if (!sb.restored) {                                         // bring back the timeline from last time
    sb.restored = true;
    try {
      const saved = JSON.parse(localStorage.getItem(sbKey()) || "null");
      if (saved) { sb.items = saved.items || []; $("sb-title").value = saved.title || ""; $("sb-aspect").value = saved.aspect || "auto";
        if (saved.format === "mp4" && sb.caps.mp4) $("sb-format").value = "mp4"; sb.uid = sb.items.reduce((m, i) => Math.max(m, i.uid || 0), 0); }
    } catch (e) { sb.items = []; }
  }
  sb.items = sb.items.filter((it) => sbAsset(it.asset_id));    // clips deleted from the Library disappear from the timeline
  state.playlists = pls.playlists || [];
  $("sb-playlist").innerHTML = '<option value="">➕ Add a whole playlist…</option>' +
    state.playlists.map((p, i) => `<option value="${i}">${escapeHtml(p.name)} (${p.clips.length})</option>`).join("");
  sbRenderPick(); sbRender();
}
function sbRenderPick() {
  const f = $("sb-filter").value;
  const items = sb.lib.filter((a) => f === "all" || (a.kind || "video") === f);
  $("sb-pick").innerHTML = items.length ? items.map((a) => `<div class="sb-pick-card">
      <div class="sb-pick-thumb">${sbThumb(a)}</div>
      <div class="sb-pick-meta"><div class="clip-title">${escapeHtml(a.prompt)}</div>
        <div class="subtle small">${sbIsImg(a) ? "🖼 image" : "🎬 " + a.duration + " s"}</div></div>
      <button class="ghost-btn" data-sb-add="${a.asset_id}">➕ Add</button></div>`).join("")
    : '<p class="subtle">Your Library is empty — generate a few clips or images first.</p>';
}
function sbAdd(a) {
  sb.items.push({ uid: ++sb.uid, asset_id: a.asset_id, start: 0, end: sbIsImg(a) ? 0 : sbDur(a), hold: 3 });
}
function sbRender() {
  const tl = $("sb-timeline");
  if (!sb.items.length) {
    tl.innerHTML = '<p class="subtle sb-empty">The timeline is empty. Press <b>➕ Add</b> on a clip in your Library.</p>';
  } else {
    tl.innerHTML = sb.items.map((it, i) => {
      const a = sbAsset(it.asset_id), d = sbDur(a) || 10;
      const trim = sbIsImg(a)
        ? `<label class="sb-trim">Show for <input type="range" data-sb="hold" min="0.5" max="10" step="0.5" value="${it.hold}" /><span data-sb-out="hold">${it.hold.toFixed(1)} s</span></label>`
        : `<label class="sb-trim">Start <input type="range" data-sb="start" min="0" max="${d}" step="${SB_STEP}" value="${it.start}" /><span data-sb-out="start">${sbFmt(it.start)}</span></label>
           <label class="sb-trim">End <input type="range" data-sb="end" min="0" max="${d}" step="${SB_STEP}" value="${it.end || d}" /><span data-sb-out="end">${sbFmt(it.end || d)}</span></label>`;
      return `<div class="sb-scene" draggable="true" data-i="${i}">
        <div class="sb-scene-head"><span class="sb-num" style="background:${sbColor(i)}">${i + 1}</span>
          <span class="sb-grip" title="Drag to rearrange">⠿</span>
          <span class="sb-len" data-sb-len>${sbFmt(sbLen(it))}</span></div>
        <div class="sb-scene-thumb">${sbThumb(a)}</div>
        <div class="clip-title">${escapeHtml(a.prompt)}</div>
        ${trim}
        <div class="sb-scene-actions">
          <button class="ghost-btn" data-sb-act="left" title="Move earlier">◀</button>
          <button class="ghost-btn" data-sb-act="right" title="Move later">▶</button>
          <button class="ghost-btn" data-sb-act="dup" title="Use this clip again (e.g. with a different trim)">⧉ Copy</button>
          <button class="ghost-btn danger" data-sb-act="del" title="Remove from timeline">✕</button></div></div>`;
    }).join("");
  }
  sbSummary(); sbSave();
}
function sbColor(i) { return `hsl(${(i * 67 + 140) % 360} 55% 38%)`; }
function sbSummary() {                                         // ruler + total length (cheap: no re-render of the scenes)
  const lens = sb.items.map(sbLen), total = lens.reduce((x, y) => x + y, 0);
  $("sb-ruler").innerHTML = sb.items.map((it, i) => `<div class="sb-seg" style="flex:${Math.max(lens[i], 0.1)};background:${sbColor(i)}" title="Scene ${i + 1}: ${sbFmt(lens[i])}">${i + 1}</div>`).join("");
  $("sb-total").textContent = sb.items.length ? `${sb.items.length} scene(s) · total ${sbFmt(total)}${total > (sb.caps?.max_seconds || 120) ? " — too long! max " + (sb.caps?.max_seconds || 120) + " s" : ""}` : "";
  document.querySelectorAll("#sb-timeline .sb-scene").forEach((el) => {
    const it = sb.items[+el.dataset.i]; if (it) el.querySelector("[data-sb-len]").textContent = sbFmt(sbLen(it));
  });
}
// add from the library picker / playlist
$("sb-filter").addEventListener("change", sbRenderPick);
$("sb-pick").addEventListener("click", (e) => {
  const id = e.target.dataset.sbAdd; if (!id) return;
  if (sb.items.length >= (sb.caps?.max_scenes || 20)) return alert("The timeline is full (max " + (sb.caps?.max_scenes || 20) + " scenes).");
  sbAdd(sbAsset(id)); sbRender();
});
$("sb-playlist").addEventListener("change", (e) => {
  const pl = state.playlists[+e.target.value]; e.target.value = "";
  if (!pl) return;
  if (!pl.clips.length) return alert("This playlist is empty.");
  pl.clips.forEach((c) => { const a = sbAsset(c.asset_id); if (a && sb.items.length < (sb.caps?.max_scenes || 20)) sbAdd(a); });
  sbRender();
});
// trim sliders (live, without re-rendering so dragging stays smooth)
$("sb-timeline").addEventListener("input", (e) => {
  const kind = e.target.dataset.sb; if (!kind) return;
  const el = e.target.closest(".sb-scene"), it = sb.items[+el.dataset.i], a = sbAsset(it.asset_id);
  let v = parseFloat(e.target.value);
  if (kind === "hold") it.hold = v;
  else {
    const d = sbDur(a) || 10, other = (k) => el.querySelector(`[data-sb="${k}"]`);
    if (kind === "start") { v = Math.min(v, (it.end || d) - SB_STEP); it.start = Math.max(0, v); e.target.value = it.start; }
    else { v = Math.max(v, it.start + SB_STEP); it.end = Math.min(d, v); e.target.value = it.end; }
  }
  const out = el.querySelector(`[data-sb-out="${kind}"]`);
  out.textContent = kind === "hold" ? it.hold.toFixed(1) + " s" : sbFmt(it[kind]);
  sbSummary(); sbSave();
});
// move / copy / remove
function sbMove(from, to) {
  if (to < 0 || to >= sb.items.length || from === to) return;
  sb.items.splice(to, 0, sb.items.splice(from, 1)[0]); sbRender();
}
$("sb-timeline").addEventListener("click", (e) => {
  const act = e.target.dataset.sbAct; if (!act) return;
  const i = +e.target.closest(".sb-scene").dataset.i;
  if (act === "left") sbMove(i, i - 1);
  else if (act === "right") sbMove(i, i + 1);
  else if (act === "dup") { if (sb.items.length < (sb.caps?.max_scenes || 20)) { sb.items.splice(i + 1, 0, { ...sb.items[i], uid: ++sb.uid }); sbRender(); } }
  else if (act === "del") { sb.items.splice(i, 1); sbRender(); }
});
// drag & drop rearranging
$("sb-timeline").addEventListener("dragstart", (e) => {
  const el = e.target.closest(".sb-scene"); if (!el) return;
  sb.dragFrom = +el.dataset.i; e.dataTransfer.effectAllowed = "move"; e.dataTransfer.setData("text/plain", String(sb.dragFrom));
  el.classList.add("dragging");
});
$("sb-timeline").addEventListener("dragover", (e) => {
  if (sb.dragFrom === null) return;
  e.preventDefault();
  document.querySelectorAll(".sb-scene.drop-here").forEach((x) => x.classList.remove("drop-here"));
  const el = e.target.closest(".sb-scene"); if (el) el.classList.add("drop-here");
});
$("sb-timeline").addEventListener("drop", (e) => {
  e.preventDefault();
  const el = e.target.closest(".sb-scene");
  if (el && sb.dragFrom !== null) sbMove(sb.dragFrom, +el.dataset.i);
  sb.dragFrom = null;
});
$("sb-timeline").addEventListener("dragend", () => {
  sb.dragFrom = null;
  document.querySelectorAll(".sb-scene").forEach((x) => x.classList.remove("dragging", "drop-here"));
});
$("sb-clear-btn").addEventListener("click", () => { if (!sb.items.length || confirm("Remove all scenes from the timeline?")) { sb.items = []; sbRender(); } });
["sb-title", "sb-aspect", "sb-format"].forEach((id) => $(id).addEventListener("change", sbSave));

function sbPayload() {
  return { title: $("sb-title").value.trim(), aspect: $("sb-aspect").value, format: $("sb-format").value,
    scenes: sb.items.map((it) => { const a = sbAsset(it.asset_id), d = sbDur(a);
      // an end slider left at the very end means "until the end of the file" (real length can differ slightly from the stored one)
      return { asset_id: it.asset_id, start: +it.start.toFixed(2), end: sbIsImg(a) || !it.end || it.end >= d - 0.005 ? 0 : +it.end.toFixed(2), hold: it.hold }; }) };
}
// Preview the sequence (quick, low-res, exactly the trims you set)
$("sb-preview-btn").addEventListener("click", async () => {
  if (!sb.items.length) return alert("Add at least one clip to the timeline first.");
  const btn = $("sb-preview-btn"); btn.disabled = true; btn.textContent = "⏳ Rendering preview…";
  try {
    const r = await api("scene_preview", sbPayload());
    if (r.status !== "ok") return alert(r.message);
    $("sb-preview-img").src = r.media_url + "?t=" + Date.now();
    $("sb-preview-cap").textContent = `Preview (low resolution, loops) — ${r.duration} s · rendered by ${r.node}. The final movie has the same scenes, order and trims in full size.`;
    $("sb-preview").classList.remove("hidden");
  } catch (e) { alert("Preview failed: " + e); }
  finally { btn.disabled = false; btn.textContent = "▶ Preview sequence"; }
});
// Generate the final movie (a real distributed job)
$("scene-generate-btn").addEventListener("click", async () => {
  if (!sb.items.length) return alert("Add at least one clip to the timeline first.");
  $("scene-generate-btn").disabled = true;
  $("scene-result").classList.add("hidden");
  const r = await api("scene_submit", sbPayload());
  if (r.status !== "ok") { alert(r.message); $("scene-generate-btn").disabled = false; return; }
  $("scene-status-box").classList.remove("hidden");
  $("scene-status-text").textContent = "Status: Waiting";
  $("scene-status-sub").textContent = "Job " + r.job_id.slice(0, 8) + "…";
  poll(r.job_id, "scene");
});
async function showSceneResult(jobId) {
  const lib = await api("library");
  sb.lib = lib.library || sb.lib;
  const a = sb.lib.find((x) => x.media_url.includes(jobId));
  if (!a) return;
  $("scene-result").innerHTML = mediaEl(a.media_url + "?t=" + Date.now()) +
    `<div class="subtle small" style="margin-top:6px">${a.duration} s · saved in your Library as a new clip — <a href="${a.media_url}" download>⬇ Save</a></div>`;
  $("scene-result").classList.remove("hidden");
  sbRenderPick();                                              // the new movie can itself be used as a scene
}


// ------------------------------------- My Assets / Character & Asset Consistency (NEW) --
// Uploaded pictures (person / object / location) are kept per user and can be picked as a "Reference" on the generation pages.
const refState = { list: [] };
const refById = (id) => refState.list.find((a) => a.ref_id === id);
const REF_ICON = { character: "🧍", object: "📦", location: "🏞" };
async function fetchRefs() { const r = await api("ref_list"); refState.list = r.assets || []; return refState.list; }

// shrink a chosen picture in the browser (max 1024 px, JPEG) before it is sent
function fileToJpegB64(f) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(f), im = new Image();
    im.onload = () => {
      const k = Math.min(1, 1024 / Math.max(im.width, im.height));
      const c = document.createElement("canvas"); c.width = Math.round(im.width * k); c.height = Math.round(im.height * k);
      const g = c.getContext("2d"); g.fillStyle = "#fff"; g.fillRect(0, 0, c.width, c.height); g.drawImage(im, 0, 0, c.width, c.height);
      URL.revokeObjectURL(url); resolve(c.toDataURL("image/jpeg", 0.9).split(",")[1]);
    };
    im.onerror = () => reject(new Error("That file is not a readable image."));
    im.src = url;
  });
}

// ---- gallery
async function loadAssets() {
  await fetchRefs();
  const g = $("asset-grid");
  g.innerHTML = refState.list.length ? refState.list.map((a) => `<div class="asset-card" data-ref="${a.ref_id}">
      <img src="${a.media_url}" alt="${escapeHtml(a.name)}" loading="lazy" />
      <div class="asset-info"><div class="asset-name">${REF_ICON[a.type] || ""} ${escapeHtml(a.name)}</div>
        <div class="subtle small asset-desc">${a.description ? escapeHtml(a.description) : "<i>no description</i>"}</div></div>
      <div class="asset-actions">
        <button class="ghost-btn" data-use="create" title="Use as reference for Text → Video">🎬 Video</button>
        <button class="ghost-btn" data-use="image" title="Use as reference for Text → Image">🖼 Image</button>
        <button class="ghost-btn" data-use="i2v" title="Animate this picture (Image → Video)">📷 Animate</button>
        <button class="ghost-btn" data-asset-act="edit">✏</button>
        <button class="ghost-btn danger" data-asset-act="del">🗑</button></div></div>`).join("")
    : '<p class="subtle">No assets yet — upload your first character, object or location above.</p>';
}
$("asset-upload-btn").addEventListener("click", async () => {
  const f = $("asset-file").files[0], msg = $("asset-msg");
  if (!f) return (msg.textContent = "Choose an image first.");
  if (!$("asset-name").value.trim()) return (msg.textContent = "Give the asset a name.");
  $("asset-upload-btn").disabled = true; msg.textContent = "Uploading…";
  try {
    const r = await api("ref_upload", { name: $("asset-name").value.trim(), type: $("asset-type").value,
      description: $("asset-desc").value.trim(), image_b64: await fileToJpegB64(f) });
    if (r.status !== "ok") { msg.textContent = r.message; return; }
    msg.textContent = "Saved ✓";
    $("asset-file").value = ""; $("asset-name").value = ""; $("asset-desc").value = "";
    await loadAssets();
  } catch (e) { msg.textContent = String(e.message || e); }
  finally { $("asset-upload-btn").disabled = false; }
});
$("asset-grid").addEventListener("click", async (e) => {
  const card = e.target.closest(".asset-card"); if (!card) return;
  const a = refById(card.dataset.ref); if (!a) return;
  if (e.target.dataset.use) return useAsset(a.ref_id, e.target.dataset.use);
  if (e.target.dataset.assetAct === "del") {
    if (!confirm(`Delete "${a.name}"?`)) return;
    await api("ref_delete", { ref_id: a.ref_id }); loadAssets();
  } else if (e.target.dataset.assetAct === "edit") {
    const name = prompt("Name:", a.name); if (name === null) return;
    const desc = prompt("Description (added to text prompts):", a.description || ""); if (desc === null) return;
    const r = await api("ref_update", { ref_id: a.ref_id, name, description: desc });
    if (r.status !== "ok") alert(r.message);
    loadAssets();
  }
});

// ---- the "Reference asset" picker, added to the three generation pages
const REF_PAGES = { create: "prompt-input", image: "img-prompt-input", i2v: null };
Object.keys(REF_PAGES).forEach((v) => {
  const row = document.createElement("div"); row.className = "ref-row";
  row.innerHTML = `<label>🧍 Reference asset</label><select id="ref-sel-${v}"><option value="">None</option></select><span class="subtle small" id="ref-hint-${v}"></span>`;
  const anchor = v === "i2v" ? $("i2v-file").parentElement : $(REF_PAGES[v]);
  anchor.insertAdjacentElement("beforebegin", row);
  $("ref-sel-" + v).addEventListener("change", () => refPicked(v));
});
async function refreshRefSelects() {
  await fetchRefs();
  Object.keys(REF_PAGES).forEach((v) => {
    const sel = $("ref-sel-" + v), keep = sel.value;
    sel.innerHTML = '<option value="">None</option>' + refState.list.map((a) => `<option value="${a.ref_id}">${REF_ICON[a.type] || ""} ${escapeHtml(a.name)}</option>`).join("");
    sel.value = refById(keep) ? keep : "";
    $("ref-hint-" + v).textContent = refState.list.length ? "" : "Upload pictures on the My Assets page first.";
  });
}
document.querySelectorAll(".nav-btn").forEach((b) => { if (REF_PAGES[b.dataset.view] !== undefined) b.addEventListener("click", refreshRefSelects); });

async function refPicked(v) {
  const a = refById($("ref-sel-" + v).value), hint = $("ref-hint-" + v);
  hint.textContent = "";
  if (!a) return;
  if (v === "i2v") {                                           // the asset's picture becomes the start picture (a true image reference)
    try {
      const blob = await (await fetch(a.media_url)).blob();
      i2vB64 = await new Promise((ok, bad) => { const fr = new FileReader(); fr.onload = () => ok(String(fr.result).split(",")[1]); fr.onerror = bad; fr.readAsDataURL(blob); });
      $("i2v-file").value = "";
      $("i2v-preview-img").src = a.media_url; $("i2v-preview").classList.remove("hidden");
      hint.textContent = "Using “" + a.name + "” as the start picture.";
    } catch (err) { hint.textContent = "Could not load that asset."; }
  } else {
    hint.textContent = a.description ? "“" + a.name + ": " + a.description + "” will be added to your prompt." : "This asset has no description, so nothing is added to the prompt — add one on My Assets (✏).";
  }
}
// choosing a file by hand replaces a picked asset
$("i2v-file").addEventListener("change", () => { $("ref-sel-i2v").value = ""; $("ref-hint-i2v").textContent = ""; });
async function useAsset(id, view) {
  await refreshRefSelects();
  show(view);
  $("ref-sel-" + view).value = id;
  refPicked(view);
}
// Text -> Video / Text -> Image: add the picked asset's description to the prompt that is sent (the existing buttons stay untouched)
const apiBeforeAssets = api;
api = async function (path, body) {
  if (path === "submit" && body && body.kind !== "i2v" && body.prompt_text) {
    const sel = $("ref-sel-" + (body.kind === "image" ? "image" : "create")), a = sel && refById(sel.value);
    if (a && a.description) body = { ...body, prompt_text: body.prompt_text + ", " + a.name + ": " + a.description };
  }
  return apiBeforeAssets(path, body);
};
