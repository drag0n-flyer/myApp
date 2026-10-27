import { api, el, clear, ready, fail, formatTime, table } from "./common.js";

const list = document.getElementById("audit-list");
const more = document.getElementById("more");
const select = document.getElementById("action");

const ACTIONS = [
  ["token.create", "Admin token created"],
  ["sessions.end", "All sessions ended"],
  ["login", "Signed in"],
  ["login.failed", "Sign-in failed"],
  ["logout", "Signed out"],
  ["invite.create", "Invite created"],
  ["invite.revoke", "Invite revoked"],
  ["account.view", "Account viewed"],
  ["device.revoke", "Device signed out"],
  ["account.delete", "Account deleted"],
  ["report.view", "Report viewed"],
  ["report.handle", "Report marked handled"],
  ["admin.setup", "First admin created"],
  ["admin.add", "Admin added"],
  ["admin.disable", "Admin disabled"],
  ["admin.enable", "Admin enabled"],
  ["admin.password", "Admin password changed"],
  ["admin.remove", "Admin removed"],
];
const LABELS = new Map(ACTIONS);

for (const [value, label] of ACTIONS) {
  select.appendChild(el("option", { value }, label));
}

let entries = [];
let next = null;

function who(e) {
  if (e.actor === "cli") return "Command line on the server";
  if (e.username) return "Dashboard, admin " + e.username;
  if (e.adminID) return "Dashboard, removed admin (" + e.adminID.slice(0, 8) + ")";
  const label = e.tokenLabel ? e.tokenLabel : "no label";
  const id = e.tokenID ? " (" + e.tokenID.slice(0, 8) + ")" : "";
  return "Dashboard, token " + label + id;
}

function render() {
  clear(list);
  if (entries.length === 0) {
    list.appendChild(el("p", {}, "No entries."));
  } else {
    const rows = entries.map((e) => [
      formatTime(e.at),
      who(e),
      LABELS.has(e.action) ? LABELS.get(e.action) + " (" + e.action + ")" : e.action,
      e.target ? el("code", {}, e.target) : "—",
    ]);
    list.appendChild(table("Audit log, newest first", ["Time", "Who", "Action", "Target"], rows));
  }
  more.hidden = next === null;
}

async function load(append) {
  const params = new URLSearchParams();
  if (select.value) params.set("action", select.value);
  if (append && next) params.set("after", next);
  const qs = params.toString();
  try {
    const data = await api("GET", "/admin/api/audit" + (qs ? "?" + qs : ""));
    entries = append ? entries.concat(data.entries || []) : data.entries || [];
    next = data.next || null;
    render();
  } catch (err) {
    fail(err);
  }
}

select.addEventListener("change", () => load(false));
more.addEventListener("click", () => load(true));

await ready;
await load(false);
