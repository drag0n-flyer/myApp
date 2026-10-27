import { api, el, clear, ready, fail, formatTime, table } from "./common.js";

const list = document.getElementById("report-list");
const more = document.getElementById("more");
const select = document.getElementById("state");

let rows = [];
let next = null;

const name = (v, fallback) => (v === null || v === undefined ? fallback : v);


function render() {
  clear(list);
  if (rows.length === 0) {
    list.appendChild(el("p", {}, "No reports to show."));
  } else {
    const body = rows.map((r) => [
      el("a", { href: "report.html?id=" + encodeURIComponent(r.id) }, formatTime(r.createdAt)),
      r.reason,
      name(r.reportedUsername, "deleted account"),
      name(r.reporterUsername, "deleted"),
      String(r.messageCount),
      r.hasNote ? "Yes" : "No",
      r.handledAt ? "Handled " + formatTime(r.handledAt) : "Open",
    ]);
    list.appendChild(table("Reports", ["Reported", "Reason", "Reported account", "Reporter", "Shared messages", "Note", "State"], body));
  }
  more.hidden = next === null;
}

async function load(append) {
  const params = new URLSearchParams({ state: select.value });
  if (append && next) params.set("after", next);
  try {
    const data = await api("GET", "/admin/api/reports?" + params.toString());
    rows = append ? rows.concat(data.reports || []) : data.reports || [];
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
