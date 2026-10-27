import { api, el, clear, ready, fail, notice, formatTime, table, confirmDialog } from "./common.js";

const list = document.getElementById("admin-list");
const form = document.getElementById("add-form");

let me = null;
let rows = [];

function path(a, suffix) {
  return "/admin/api/admins/" + encodeURIComponent(a.id) + (suffix || "");
}

function checkPassword(password, confirm) {
  if ([...password].length < 12) return "The password must be at least 12 characters.";
  if (confirm !== undefined && password !== confirm) return "The two passwords are not the same.";
  return null;
}

async function toggle(a) {
  const disabling = !a.disabled;
  if (disabling) {
    const ok = await confirmDialog(
      "Disable " + a.username + "? They are signed out now and cannot sign in until you enable them again.",
      null,
      { title: "Disable admin", confirmLabel: "Disable" },
    );
    if (!ok) return;
  }
  try {
    await api("POST", path(a, disabling ? "/disable" : "/enable"));
    notice(a.username + (disabling ? " is disabled." : " is enabled."));
    await reload();
  } catch (err) {
    fail(err);
  }
}

async function resetPassword(a, own) {
  const id = "pw" + Math.random().toString(36).slice(2, 8);
  const input = el("input", { type: "password", id, autocomplete: "new-password" });
  const again = el("input", { type: "password", id: id + "-again", autocomplete: "new-password" });
  const extra = el(
    "div",
    {},
    el("p", {}, el("label", { for: id }, "New password (at least 12 characters)")),
    el("p", {}, input),
    el("p", {}, el("label", { for: id + "-again" }, "Confirm new password")),
    el("p", {}, again),
  );
  const ok = await confirmDialog(
    own
      ? "Choose a new password. You will be signed out everywhere and must sign in again."
      : "Choose a new password for " + a.username + ". They are signed out everywhere.",
    null,
    { title: own ? "Change your password" : "Reset password for " + a.username, confirmLabel: "Change password", extra },
  );
  if (!ok) return;
  const problem = checkPassword(input.value, again.value);
  if (problem) {
    notice(problem, true);
    return;
  }
  try {
    await api("POST", path(a, "/password"), { password: input.value });
    if (own) {
      window.location.href = "login.html?changed=1";
      return;
    }
    notice("Password changed for " + a.username + ".");
  } catch (err) {
    fail(err);
  }
}

async function remove(a) {
  const ok = await confirmDialog(
    "Remove " + a.username + "? They are signed out now and cannot sign in again. This cannot be undone.",
    null,
    { title: "Remove admin", confirmLabel: "Remove" },
  );
  if (!ok) return;
  try {
    await api("DELETE", path(a));
    notice(a.username + " is removed.");
    await reload();
  } catch (err) {
    fail(err);
  }
}

function button(label, aria, onClick) {
  const b = el("button", { type: "button", className: "secondary", "aria-label": aria }, label);
  b.addEventListener("click", onClick);
  return b;
}

function actions(a) {
  if (me && a.username === me) {
    return el("span", {}, button("Change password", "Change your password", () => resetPassword(a, true)));
  }
  return el(
    "span",
    {},
    button(a.disabled ? "Enable" : "Disable", (a.disabled ? "Enable " : "Disable ") + a.username, () => toggle(a)),
    " ",
    button("Reset password", "Reset password for " + a.username, () => resetPassword(a, false)),
    " ",
    button("Remove", "Remove " + a.username, () => remove(a)),
  );
}

function render() {
  clear(list);
  list.removeAttribute("aria-busy");
  if (rows.length === 0) {
    list.appendChild(el("p", {}, "There are no admins yet."));
    return;
  }
  const body = rows.map((a) => [
    me && a.username === me ? a.username + " (you)" : a.username,
    a.disabled ? "Disabled" : "Active",
    formatTime(a.createdAt),
    formatTime(a.lastLoginAt),
    actions(a),
  ]);
  list.appendChild(table("Admins", ["Username", "State", "Added", "Last signed in", "Actions"], body));
}

async function reload() {
  try {
    const data = await api("GET", "/admin/api/admins");
    rows = data.admins || [];
    render();
  } catch (err) {
    fail(err);
  }
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  const username = document.getElementById("new-username").value.trim().toLowerCase();
  const password = document.getElementById("new-password").value;
  const confirm = document.getElementById("new-confirm").value;
  const problem = checkPassword(password, confirm);
  if (problem) {
    notice(problem, true);
    return;
  }
  const button = document.getElementById("add-admin");
  button.disabled = true;
  try {
    await api("POST", "/admin/api/admins", { username, password });
    form.reset();
    notice("Added " + username + ".");
    await reload();
  } catch (err) {
    fail(err);
  } finally {
    button.disabled = false;
  }
});

const session = await ready;
me = session.username || null;
await reload();
