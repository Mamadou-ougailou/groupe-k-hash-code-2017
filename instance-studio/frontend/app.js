const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

const NUMERIC = new Set([
  "V", "E", "C", "X", "R", "seed", "size_min", "size_max", "size_log_mu",
  "size_log_sigma", "size_small_frac", "size_large_min", "size_large_max",
  "topo_jitter", "degree", "degree_min", "degree_max", "degree_lambda",
  "dc_min", "dc_max", "latency_spread", "latency_noise", "zipf_alpha",
  "demand_alpha", "count_min", "count_max", "duplicate_rate",
]);

const STAT_LABELS = {
  score_ceiling: "Plafond de score",
  total_requests: "Requêtes totales",
  duplicate_lines: "Lignes dupliquées",
  duplicate_rate_actual: "Taux de duplication réel",
  videos_per_cache: "Vidéos par cache",
  capacity_pressure: "Pression de capacité",
  video_size_mean: "Taille moyenne",
  video_size_cv: "CV des tailles",
  degree_mean: "Degré moyen",
  degree_std: "Écart-type du degré",
  endpoints_without_cache: "Endpoints sans cache",
  cache_overlap_mean: "Recouvrement moyen",
  cache_overlap_p95: "Recouvrement p95",
  cache_overlap_max: "Recouvrement max",
  cache_pairs_disjoint: "Paires de caches disjointes",
  dc_latency_mean: "Latence datacenter moy.",
  cache_latency_mean: "Latence cache moy.",
  generation_seconds: "Génération (s)",
};

// ---- tabs ----------------------------------------------------------------

$$(".tab").forEach(tab => tab.addEventListener("click", () => {
  $$(".tab").forEach(t => t.classList.toggle("active", t === tab));
  $$(".panel").forEach(p => p.classList.toggle("active", p.id === tab.dataset.tab));
  if (tab.dataset.tab === "library") loadLibrary();
}));

// ---- conditional fields --------------------------------------------------

function syncConditionals() {
  $$("[data-when]").forEach(el => {
    const [field, values] = el.dataset.when.split("=");
    const current = $(`[name="${field}"]`).value;
    const hidden = !values.split(",").includes(current);
    el.classList.toggle("hidden", hidden);
    // Disabled controls are skipped by validation and by collectParams, so a
    // hidden field can never block submission or leak an irrelevant value.
    $$("input, select", el).forEach(input => { input.disabled = hidden; });
  });
}

$$("#gen-form select").forEach(sel => sel.addEventListener("change", syncConditionals));
syncConditionals();

// ---- generate ------------------------------------------------------------

function collectParams() {
  const payload = {};
  for (const el of $("#gen-form").elements) {
    if (!el.name || el.disabled) continue;
    if (NUMERIC.has(el.name)) {
      const n = Number(el.value);
      if (el.value !== "" && Number.isFinite(n)) payload[el.name] = n;
    } else if (el.value !== "") {
      payload[el.name] = el.value;
    }
  }
  return payload;
}

function statCards(stats) {
  return Object.entries(STAT_LABELS)
    .filter(([key]) => stats[key] !== undefined)
    .map(([key, label]) => `
      <div class="stat">
        <div class="k">${label}</div>
        <div class="v">${stats[key]}</div>
      </div>`)
    .join("");
}

$("#gen-form").addEventListener("submit", async event => {
  event.preventDefault();
  const button = $("#gen-btn");
  const status = $("#gen-status");
  button.disabled = true;
  status.className = "status";
  status.textContent = "génération en cours…";

  try {
    const response = await fetch("/api/generate", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(collectParams()),
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || `erreur ${response.status}`);

    const inst = data.instance;
    status.className = "status ok";
    status.textContent = `terminé en ${inst.stats.generation_seconds} s`;
    $("#gen-result").classList.remove("hidden");
    $("#gen-result").innerHTML = `
      <h3>${inst.name}</h3>
      <p class="meta">${describe(inst)}</p>
      <a class="dl" href="/api/instances/${inst.id}/download" download="${inst.name}.in">Télécharger</a>
      <div class="stats">${statCards(inst.stats)}</div>`;
  } catch (err) {
    status.className = "status err";
    status.textContent = err.message;
  } finally {
    button.disabled = false;
  }
});

// ---- library -------------------------------------------------------------

function describe(inst) {
  const p = inst.params;
  const mb = (inst.bytes / (1024 * 1024)).toFixed(2);
  return `V=${p.V} · E=${p.E} · C=${p.C} · X=${p.X} · R=${p.R} · graine ${p.seed} · ${p.topology} · ${mb} Mo`;
}

async function loadLibrary() {
  const status = $("#lib-status");
  status.className = "status";
  status.textContent = "chargement…";
  try {
    const response = await fetch("/api/instances");
    const data = await response.json();
    const list = data.instances || [];
    status.textContent = `${list.length} instance(s)`;
    $("#lib-list").innerHTML = list.length
      ? list.map(inst => `
          <div class="card">
            <div>
              <h3>${inst.name}</h3>
              <div class="meta">${describe(inst)}</div>
              <div class="meta">plafond ${inst.stats.score_ceiling} · degré moyen ${inst.stats.degree_mean} · recouvrement ${inst.stats.cache_overlap_mean}</div>
            </div>
            <a class="dl" href="/api/instances/${inst.id}/download" download="${inst.name}.in">Télécharger</a>
          </div>`).join("")
      : `<p class="hint">Aucune instance pour l'instant.</p>`;
  } catch (err) {
    status.className = "status err";
    status.textContent = err.message;
  }
}

$("#refresh-btn").addEventListener("click", loadLibrary);

// ---- validate ------------------------------------------------------------

$("#val-btn").addEventListener("click", async () => {
  const file = $("#val-file").files[0];
  const status = $("#val-status");
  const result = $("#val-result");

  if (!file) {
    status.className = "status err";
    status.textContent = "choisis un fichier";
    return;
  }

  status.className = "status";
  status.textContent = "lecture et envoi…";
  result.classList.add("hidden");

  try {
    const text = await file.text();
    const response = await fetch("/api/validate", {
      method: "POST",
      headers: { "Content-Type": "text/plain; charset=utf-8" },
      body: text,
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || `erreur ${response.status}`);

    status.className = data.valid ? "status ok" : "status err";
    status.textContent = data.valid ? "instance valide" : "instance invalide";
    result.classList.remove("hidden");
    result.innerHTML = `
      <h3>${data.valid ? "Valide" : "Invalide"} — ${file.name}</h3>
      ${data.errors.length ? `<ul class="errors">${data.errors.map(e => `<li>${escapeHtml(e)}</li>`).join("")}</ul>` : ""}
      <div class="stats">${summaryCards(data.summary)}</div>`;
  } catch (err) {
    status.className = "status err";
    status.textContent = err.message;
  }
});

function summaryCards(summary) {
  return Object.entries(summary).map(([k, v]) => `
    <div class="stat"><div class="k">${k}</div><div class="v">${v}</div></div>`).join("");
}

function escapeHtml(s) {
  return s.replace(/[&<>"']/g, c => (
    { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]
  ));
}
