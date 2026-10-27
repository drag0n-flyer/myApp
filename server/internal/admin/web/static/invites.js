import { api, el, clear, ready, fail, notice, formatTime, table, confirmDialog, announce } from "./common.js";

const list = document.getElementById("invite-list");
const more = document.getElementById("more");
const showAll = document.getElementById("show-all");

let rows = [];
let next = null;

const STATE_WORDS = { active: "Active", "used up": "Used up", expired: "Expired", revoked: "Revoked" };

function usesLeft(inv) {
  return inv.state === "active" ? String(inv.usesLeft) : "—";
}

async function revoke(inv) {
  const ok = await confirmDialog("Revoke invite " + inv.codePrefix + "? Nobody can use it to sign up after this.", null, {
    title: "Revoke invite",
    confirmLabel: "Revoke",
  });
  if (!ok) return;
  try {
    await api("POST", "/admin/api/invites/" + encodeURIComponent(inv.id) + "/revoke");
    notice("Invite revoked.");
    await reload();
  } catch (err) {
    fail(err);
  }
}

function visible() {
  return showAll.checked ? rows : rows.filter((r) => r.state === "active");
}

function render() {
  clear(list);
  const shown = visible();
  if (shown.length === 0) {
    list.appendChild(el("p", {}, showAll.checked ? "There are no invites." : "There are no active invites."));
  } else {
    const body = shown.map((inv) => {
      let action = "—";
      if (inv.state === "active") {
        action = el("button", { type: "button", className: "secondary", "aria-label": "Revoke invite " + inv.codePrefix }, "Revoke");
        action.addEventListener("click", () => revoke(inv));
      }
      return [el("code", {}, inv.codePrefix), STATE_WORDS[inv.state] || inv.state, usesLeft(inv), formatTime(inv.createdAt), formatTime(inv.expiresAt), action];
    });
    list.appendChild(table("Invites", ["Code", "State", "Uses left", "Created", "Expires", "Action"], body));
  }
  more.hidden = next === null;
}

// fetchPage loads one page. Without "Show all" it keeps going until it has
// something to show, because the list is filtered here.
async function fetchPage() {
  let guard = 0;
  do {
    const data = await api("GET", "/admin/api/invites" + (next ? "?after=" + encodeURIComponent(next) : ""));
    rows = rows.concat(data.invites || []);
    next = data.next || null;
    guard += 1;
  } while (next !== null && visible().length === 0 && guard < 20);
}

async function reload() {
  rows = [];
  next = null;
  try {
    await fetchPage();
    render();
  } catch (err) {
    fail(err);
  }
}

more.addEventListener("click", async () => {
  try {
    await fetchPage();
    render();
  } catch (err) {
    fail(err);
  }
});

showAll.addEventListener("change", async () => {
  render();
  if (visible().length === 0 && next !== null) {
    try {
      await fetchPage();
      render();
    } catch (err) {
      fail(err);
    }
  }
});

document.getElementById("invite-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const uses = Number(document.getElementById("uses").value);
  const days = Number(document.getElementById("days").value);
  if (!Number.isInteger(uses) || uses < 1 || uses > 100) {
    notice("Uses must be a whole number from 1 to 100.", true);
    return;
  }
  if (!Number.isInteger(days) || days < 1 || days > 90) {
    notice("Days must be a whole number from 1 to 90.", true);
    return;
  }
  try {
    const made = await api("POST", "/admin/api/invites", { uses, days });
    const box = document.getElementById("created");
    const field = document.getElementById("invite-link");
    field.value = made.link;
    box.hidden = false;
    field.focus();
    field.select();
    notice("Invite created.");
    await reload();
  } catch (err) {
    fail(err);
  }
});

document.getElementById("copy-link").addEventListener("click", async () => {
  const field = document.getElementById("invite-link");
  field.select();
  try {
    await navigator.clipboard.writeText(field.value);
    announce("Link copied.");
  } catch (e) {
    announce("Press Control+C to copy the selected link.");
  }
});

await ready;
await reload();
