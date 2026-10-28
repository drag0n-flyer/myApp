// Shared helpers for every dashboard page. Plain JavaScript, no framework.

const BANNED_ATTRS = /^(on.*|style|innerhtml|outerhtml)$/i;

// el builds an element. Text children are added as text, never as markup.
export function el(tag, attrs, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs || {})) {
    if (BANNED_ATTRS.test(key)) {
      throw new Error("attribute not allowed: " + key);
    }
    if (value === false || value === null || value === undefined) continue;
    node.setAttribute(key === "className" ? "class" : key, value === true ? "" : String(value));
  }
  const add = (child) => {
    if (Array.isArray(child)) child.forEach(add);
    else if (child === null || child === undefined || child === false) return;
    else if (child instanceof Node) node.appendChild(child);
    else node.appendChild(document.createTextNode(String(child)));
  };
  children.forEach(add);
  return node;
}

// clear removes every child of a node.
export function clear(node) {
  while (node.firstChild) node.removeChild(node.firstChild);
  return node;
}

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

let csrf = null;

async function loadSession() {
  const s = await api("GET", "/admin/api/session");
  csrf = s.csrf;
  return s;
}

// api calls the JSON API. On 401 it goes to the login page, unless
// opts.stay is set. opts.background marks a refresh the page makes on its own,
// which must not keep the session alive. It throws ApiError for any other failure.
export async function api(method, path, body, opts) {
  const headers = { Accept: "application/json" };
  if (method !== "GET" && csrf === null && !path.startsWith("/admin/api/login")) {
    await loadSession();
  }
  if (csrf !== null) headers["X-CSRF-Token"] = csrf;
  const init = { method, headers, credentials: "same-origin" };
  if (opts && opts.background) headers["X-Admin-Background"] = "1";
  if (method !== "GET" && method !== "HEAD") {
    headers["Content-Type"] = "application/json";
    init.body = body === undefined ? "{}" : JSON.stringify(body);
  }
  let res;
  try {
    res = await fetch(path, init);
  } catch (e) {
    throw new ApiError(0, "network", "The server did not answer. Check the tunnel and try again.");
  }
  if (res.status === 401 && !(opts && opts.stay)) {
    window.location.href = "login.html";
    return new Promise(() => {});
  }
  if (res.status === 204) return null;
  let data = null;
  try {
    data = await res.json();
  } catch (e) {
    data = null;
  }
  if (!res.ok) {
    const err = (data && data.error) || {};
    throw new ApiError(res.status, err.code || "error", err.message || "Something went wrong.");
  }
  return data;
}

let liveRegion = null;

// announce tells screen readers about a change, through the polite live region.
export function announce(text) {
  if (!liveRegion) {
    liveRegion = el("div", { id: "live", className: "visually-hidden", role: "status", "aria-live": "polite" });
    document.body.appendChild(liveRegion);
  }
  liveRegion.textContent = "";
  window.setTimeout(() => {
    liveRegion.textContent = text;
  }, 50);
}

// formatTime shows an ISO time in the browser's own time zone.
export function formatTime(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "—";
  return d.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

// formatDuration turns seconds into "3 days 4 hours" style text.
export function formatDuration(total) {
  let s = Math.max(0, Math.floor(total));
  const days = Math.floor(s / 86400);
  s -= days * 86400;
  const hours = Math.floor(s / 3600);
  s -= hours * 3600;
  const minutes = Math.floor(s / 60);
  const unit = (n, word) => n + " " + word + (n === 1 ? "" : "s");
  if (days > 0) return unit(days, "day") + " " + unit(hours, "hour");
  if (hours > 0) return unit(hours, "hour") + " " + unit(minutes, "minute");
  return unit(minutes, "minute");
}

// formatNumber groups digits for the reader's locale.
export function formatNumber(n) {
  return typeof n === "number" ? n.toLocaleString() : "—";
}

// status shows (or clears) a message in the page's #notice element.
export function notice(text, isError) {
  const box = document.getElementById("notice");
  if (!box) return;
  clear(box);
  box.className = text ? (isError ? "notice error" : "notice") : "";
  if (isError) box.setAttribute("role", "alert");
  else box.removeAttribute("role");
  if (text) {
    box.appendChild(document.createTextNode(text));
    if (!isError) announce(text);
  }
}

// fail shows an error from the API in the notice box.
export function fail(err) {
  notice(err instanceof ApiError ? err.message : "Something went wrong.", true);
}

// table builds a real table that scrolls sideways on a narrow screen.
export function table(caption, headers, rows) {
  const head = el("tr", {}, headers.map((h) => el("th", { scope: "col" }, h)));
  const body = rows.map((cells) => el("tr", {}, cells.map((c) => el("td", {}, c))));
  return el(
    "div",
    { className: "table-wrap", tabindex: "0", role: "region", "aria-label": caption },
    el("table", {}, el("caption", { className: "visually-hidden" }, caption), el("thead", {}, head), el("tbody", {}, body)),
  );
}

// definitionList builds a <dl> from [label, value] pairs.
export function definitionList(pairs) {
  const dl = el("dl", { className: "facts" });
  for (const [label, value] of pairs) {
    dl.appendChild(el("dt", {}, label));
    dl.appendChild(el("dd", {}, value === null || value === undefined || value === "" ? "—" : value));
  }
  return dl;
}

// onOff shows a setting as plain words.
export function onOff(v) {
  return v ? "On" : "Off";
}

// confirmDialog asks the operator to confirm. It resolves true or false.
// With `typed`, the confirm button stays off until that exact text is typed.
// opts: title, confirmLabel, extra (nodes shown above the typed field).
export function confirmDialog(text, typed, opts) {
  const o = opts || {};
  return new Promise((resolve) => {
    const opener = document.activeElement;
    const id = "dlg" + Math.random().toString(36).slice(2, 8);
    const confirm = el("button", { type: "button", className: "danger" }, o.confirmLabel || "Confirm");
    const cancel = el("button", { type: "button" }, "Cancel");
    const parts = [el("h2", { id: id + "-title" }, o.title || "Are you sure?"), el("p", {}, text), o.extra || null];
    let input = null;
    if (typed) {
      input = el("input", { type: "text", id: id + "-typed", autocomplete: "off", autocapitalize: "off", spellcheck: "false" });
      parts.push(el("p", {}, el("label", { for: id + "-typed" }, "Type " + typed + " to continue")), el("p", {}, input));
      confirm.disabled = true;
      input.addEventListener("input", () => {
        confirm.disabled = input.value !== typed;
      });
    }
    parts.push(el("div", { className: "actions" }, cancel, confirm));
    const dialog = el("dialog", { "aria-labelledby": id + "-title" }, parts);
    let result = false;
    dialog.addEventListener("close", () => {
      dialog.remove();
      if (opener && opener.focus) opener.focus();
      resolve(result);
    });
    cancel.addEventListener("click", () => dialog.close());
    confirm.addEventListener("click", () => {
      result = true;
      dialog.close();
    });
    document.body.appendChild(dialog);
    dialog.showModal();
    (input || cancel).focus();
  });
}

const NAV = [
  ["index", "index.html", "Overview"],
  ["invites", "invites.html", "Invites"],
  ["accounts", "accounts.html", "Accounts"],
  ["reports", "reports.html", "Reports"],
  ["server", "server.html", "Server"],
  ["admins", "admins.html", "Admins"],
  ["audit", "audit.html", "Audit log"],
];

// buildNav fills the header's nav with the section links and "Sign out".
function buildNav(page) {
  const nav = document.getElementById("nav");
  if (!nav || page === "login") return;
  const items = NAV.map(([key, href, label]) =>
    el("li", {}, el("a", { href, "aria-current": key === page ? "page" : false }, label)),
  );
  const out = el("button", { type: "button", id: "signout" }, "Sign out");
  out.addEventListener("click", async () => {
    try {
      await api("POST", "/admin/api/logout", undefined, { stay: true });
    } catch (e) {
      // Leave anyway: the cookie ends with the browser.
    }
    window.location.href = "login.html";
  });
  nav.appendChild(el("ul", {}, items, el("li", {}, out)));
}

function startPage() {
  const page = document.body.dataset.page;
  buildNav(page);
  if (page === "login") return Promise.resolve(null);
  return loadSession().then((s) => {
    const who = document.getElementById("who");
    if (who) who.textContent = "Signed in as " + (s.username || "admin");
    return s;
  });
}

// ready resolves with the session once the page is set up. On a page that
// needs a session it never resolves if the session is gone (the browser goes
// to the login page).
export const ready = startPage();
