const state = { addons: [], q: "", type: "", origin: "", tags: [] };

// R20: type and origin must be distinguishable without relying on colour.
// Each badge already carries its word, so these are a second, non-colour cue
// rather than the only one, and they are aria-hidden because a screen reader
// reads that word already. Filled shapes are types, stars are origins, and a
// filled star is first-party.
const TYPE_GLYPH = {
  plugin: "◆", template: "▲", snippet: "●", "code-action": "■",
};
const ORIGIN_GLYPH = { appdevforall: "★", community: "☆" };

const cards = document.getElementById("cards");
const status = document.getElementById("status");
const template = document.getElementById("card");

function size(bytes) {
  const mb = bytes / 1048576;
  return mb >= 1 ? mb.toFixed(1) + " MB" : Math.round(bytes / 1024) + " KB";
}

// Only http(s) and same-origin relative URLs may reach an href or src.
// Every catalog URL is derived by the generator today, but the page renders
// data fetched over the network, and "javascript:" in an href would run.
function safeUrl(value) {
  if (typeof value !== "string") return "";
  try {
    const url = new URL(value, location.origin);
    return url.protocol === "http:" || url.protocol === "https:" ? url.href : "";
  } catch {
    return "";
  }
}

function matches(addon) {
  if (state.type && addon.type !== state.type) return false;
  if (state.origin && addon.origin !== state.origin) return false;
  // every selected tag must be present, so tags narrow rather than widen
  if (!state.tags.every((t) => addon.tags.includes(t))) return false;
  if (!state.q) return true;
  const text = [addon.name, addon.summary, addon.description, ...addon.tags]
    .join(" ").toLowerCase();
  return state.q.toLowerCase().split(/\s+/).every((word) => text.includes(word));
}

function toggleTag(tag, where) {
  const i = state.tags.indexOf(tag);
  if (i === -1) state.tags.push(tag);
  else state.tags.splice(i, 1);
  render();
  // render() replaced the button that was clicked, so focus would fall to
  // <body> and a keyboard user would restart from the top of the page
  const again = document.querySelector(
    `${where === "bar" ? "#active" : "#cards"} .tag[data-tag="${CSS.escape(tag)}"]`
  ) || document.querySelector(`.tag[data-tag="${CSS.escape(tag)}"]`);
  if (again) again.focus();
}

function tagButton(tag, where) {
  const b = document.createElement("button");
  b.type = "button";
  b.className = "tag";
  b.textContent = "#" + tag;
  b.dataset.tag = tag;
  if (where) b.dataset.where = where;
  if (state.tags.includes(tag)) {
    b.classList.add("on");
    b.setAttribute("aria-pressed", "true");
  } else {
    b.setAttribute("aria-pressed", "false");
  }
  b.addEventListener("click", () => toggleTag(tag, b.dataset.where));
  return b;
}

function renderActive() {
  const bar = document.getElementById("active");
  bar.replaceChildren();
  bar.hidden = state.tags.length === 0;
  if (bar.hidden) return;
  bar.append("Filtering by ");
  for (const tag of state.tags) bar.append(tagButton(tag, "bar"));
  const clear = document.createElement("button");
  clear.type = "button";
  clear.className = "clear";
  clear.textContent = "Clear tags";
  clear.addEventListener("click", () => { state.tags = []; render(); });
  bar.append(clear);
}

// Safari throws SecurityError after ~100 replaceState calls in 30 seconds and
// render() runs on every keystroke, so coalesce the writes. A failure here
// must never stop the list from rendering.
let urlTimer = null;
function syncUrl() {
  clearTimeout(urlTimer);
  urlTimer = setTimeout(() => {
    try {
      const url = new URL(location.href);
      url.search = new URLSearchParams(
        Object.entries({ q: state.q, type: state.type, origin: state.origin,
                         tags: state.tags.join(",") })
          .filter(([, v]) => v)
      ).toString();
      history.replaceState(null, "", url);
    } catch (e) {
      /* the list is rendered; a stale address bar is not worth breaking it */
    }
  }, 250);
}

function render() {
  const shown = state.addons.filter(matches);
  cards.replaceChildren();
  for (const addon of shown) {
    const node = template.content.cloneNode(true);
    const set = (slot, value) => {
      node.querySelector(`[data-slot="${slot}"]`).textContent = value;
    };
    set("name", addon.name);
    set("type", addon.type);
    set("type-glyph", TYPE_GLYPH[addon.type] || "◆");
    set("summary", addon.summary);
    set("version", "v" + addon.version);
    set("origin", addon.origin === "community" ? "Community" : "App Dev For All");
    set("origin-glyph", ORIGIN_GLYPH[addon.origin] || ORIGIN_GLYPH.appdevforall);
    set("size", size(addon.download.size));
    const tags = node.querySelector('[data-slot="tags"]');
    for (const tag of addon.tags) tags.append(tagButton(tag, "card"));
    node.querySelector(".icon").src = safeUrl(addon.iconUrl);
    node.querySelector(".icon-dark").srcset = safeUrl(addon.iconDarkUrl);
    node.querySelector('[data-slot="download"]').href = safeUrl(addon.download.url);
    node.querySelector('[data-slot="page"]').href = safeUrl(addon.pageUrl);
    // For a plugin the tarball is the source deliverable (R39); sourceUrl stays
    // in the catalog as provenance for consumers that want the repository.
    // A template has no tarball: its .cgt is plain text, so the download is
    // already the source (ADFA-6252, schemaVersion 2 made the field optional).
    // Reading it unguarded threw a TypeError here, and because this runs inside
    // the card loop one such entry stopped the whole gallery rendering.
    const src = node.querySelector('[data-slot="source"]');
    if (addon.sourceTarball) {
      src.href = safeUrl(addon.sourceTarball.url);
      src.title = `Source tarball, ${size(addon.sourceTarball.size)}`;
    } else {
      src.remove();
    }
    cards.append(node);
  }
  renderActive();
  status.textContent = shown.length ? ""
    : state.addons.length ? "No addon matches these filters." : "Loading\u2026";
  syncUrl();
}

document.getElementById("q").addEventListener("input", (event) => {
  state.q = event.target.value;
  render();
});
document.getElementById("type").addEventListener("change", (event) => {
  state.type = event.target.value;
  render();
});
document.getElementById("origin").addEventListener("change", (event) => {
  state.origin = event.target.value;
  render();
});

// A select's own options are the allowed values, so an unknown or hostile
// query parameter falls back to "no filter" rather than reaching the
// comparison and silently hiding every card.
function fromParams(params, id) {
  const wanted = params.get(id) || "";
  const known = [...document.getElementById(id).options].map((o) => o.value);
  return known.includes(wanted) ? wanted : "";
}

const params = new URLSearchParams(location.search);
state.q = params.get("q") || "";
state.type = fromParams(params, "type");
state.origin = fromParams(params, "origin");
state.tags = (params.get("tags") || "").split(",").filter(Boolean);
document.getElementById("q").value = state.q;
document.getElementById("type").value = state.type;
document.getElementById("origin").value = state.origin;

fetch("v1/catalog.json")
  .then((response) => {
    if (!response.ok) throw new Error(response.status);
    return response.json();
  })
  .then((document_) => {
    state.addons = document_.addons;
    render();
  })
  .catch(() => {
    status.textContent = "The addon list did not load. Please try again later.";
  });
