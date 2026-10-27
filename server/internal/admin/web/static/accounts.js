import { api, el, clear, ready, fail, notice, formatTime, table } from "./common.js";

const list = document.getElementById("results");
const more = document.getElementById("more");
const input = document.getElementById("q");

let rows = [];
let next = null;
let query = "";

function render() {
  clear(list);
  if (query === "") {
    list.appendChild(el("p", {}, "Type the start of a username and choose Search."));
  } else if (rows.length === 0) {
    list.appendChild(el("p", {}, "No account starts with “" + query + "”."));
  } else {
    const body = rows.map((a) => [
      el("a", { href: "account.html?id=" + encodeURIComponent(a.id) }, a.username),
      a.displayName || "—",
      a.email || "—",
      formatTime(a.createdAt),
      String(a.devices),
    ]);
    list.appendChild(table("Accounts", ["Username", "Display name", "Email", "Created", "Devices"], body));
  }
  more.hidden = next === null;
}

async function load(append) {
  const params = new URLSearchParams({ q: query });
  if (append && next) params.set("after", next);
  try {
    const data = await api("GET", "/admin/api/accounts?" + params.toString());
    rows = append ? rows.concat(data.accounts || []) : data.accounts || [];
    next = data.next || null;
    render();
  } catch (err) {
    fail(err);
  }
}

document.getElementById("search-form").addEventListener("submit", (event) => {
  event.preventDefault();
  notice("");
  query = input.value.trim().toLowerCase();
  if (query === "") {
    rows = [];
    next = null;
    render();
    return;
  }
  load(false);
});

more.addEventListener("click", () => load(true));

await ready;
if (new URLSearchParams(window.location.search).get("deleted") === "1") {
  notice("Account deleted.");
}
render();
