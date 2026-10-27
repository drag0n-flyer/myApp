import { api, el, clear, ready, fail, notice, formatTime, formatDuration, formatNumber, table } from "./common.js";

const root = document.getElementById("overview");

function card(label, value) {
  return el("div", { className: "card" }, el("div", { className: "label" }, label), el("div", { className: "value" }, value));
}

function state(ok, good, bad) {
  return el("span", { className: ok ? "state-ok" : "state-bad" }, ok ? good : bad);
}

function render(o) {
  clear(root);
  const c = o.counts || {};
  const push = o.push || {};
  const calls = o.calls || {};
  root.appendChild(
    el(
      "dl",
      { className: "facts" },
      el("dt", {}, "Health"),
      el("dd", {}, state(o.database === "ok", "Working", "Database not answering")),
      el("dt", {}, "Version"),
      el("dd", {}, o.version || "unknown"),
      el("dt", {}, "Go version"),
      el("dd", {}, o.goVersion || "unknown"),
      el("dt", {}, "Started"),
      el("dd", {}, formatTime(o.startedAt)),
      el("dt", {}, "Uptime"),
      el("dd", {}, formatDuration(o.uptimeSeconds || 0)),
    ),
  );
  root.appendChild(
    el(
      "div",
      { className: "cards" },
      card("Accounts", formatNumber(c.accounts)),
      card("Devices", formatNumber(c.devices)),
      card("Conversations", formatNumber(c.conversations)),
      card("Messages today", formatNumber(c.messagesToday)),
      card("Live connections", formatNumber(o.connections)),
      card("Pushes sent", push.enabled ? formatNumber(push.sent) : "Off"),
      card("Pushes failed", push.enabled ? formatNumber(push.failed) : "Off"),
      card("Call relays", calls.enabled ? formatNumber((calls.relays || []).length) : "Off"),
    ),
  );
  root.appendChild(el("h2", {}, "Call relays"));
  if (!calls.enabled) {
    root.appendChild(el("p", {}, "Calls are off on this server."));
  } else if ((calls.relays || []).length === 0) {
    root.appendChild(el("p", {}, "No call relays are set up."));
  } else {
    const rows = calls.relays.map((r) => [
      r.host,
      r.region || "—",
      state(r.healthy, "Working", "Not answering"),
      r.healthy && typeof r.rttMs === "number" ? r.rttMs + " ms" : "—",
    ]);
    root.appendChild(table("Call relays", ["Host", "Region", "State", "Reply time"], rows));
  }
  root.appendChild(el("p", { className: "muted" }, "Updated " + new Date().toLocaleTimeString() + ". This page refreshes every 30 seconds."));
  root.setAttribute("aria-busy", "false");
}

async function load(background) {
  try {
    render(await api("GET", "/admin/api/overview", undefined, { background }));
    notice("");
  } catch (err) {
    fail(err);
  }
}

await ready;
await load(false);
window.setInterval(() => {
  if (document.visibilityState === "visible") load(true);
}, 30000);
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible") load(true);
});
