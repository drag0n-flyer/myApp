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

function newInput(type, autocomplete, extra) {
  const id = "f" + Math.random().toString(36).slice(2, 8);
  return { id, input: el("input", Object.assign({ type, id, autocomplete }, extra || {})) };
}

// turnOnTwoStep walks through the setup: secret and QR code, then a code.
async function turnOnTwoStep() {
  const pw = newInput("password", "current-password");
  const askPassword = await confirmDialog("Enter your password to start setting up two-step sign-in.", null, {
    title: "Turn on two-step sign-in",
    confirmLabel: "Continue",
    extra: el("div", {}, el("p", {}, el("label", { for: pw.id }, "Your password")), el("p", {}, pw.input)),
  });
  if (!askPassword) return;
  let start;
  try {
    start = await api("POST", "/admin/api/me/totp/start", { password: pw.input.value });
  } catch (err) {
    fail(err);
    return;
  }
  const code = newInput("text", "one-time-code", { inputMode: "numeric", pattern: "[0-9 ]*" });
  const qr = el("img", {
    src: "/admin/api/me/totp/qr?t=" + Date.now(),
    alt: "QR code for your authenticator app",
    width: "256",
    height: "256",
  });
  const extra = el(
    "div",
    {},
    el("p", {}, qr),
    el("p", {}, "Or type this key into the app by hand:"),
    el("p", {}, el("code", {}, start.secret)),
    el("p", {}, el("label", { for: code.id }, "Code from the app")),
    el("p", {}, code.input),
  );
  const ok = await confirmDialog(
    "Scan the picture with an authenticator app, then enter the 6-digit code it shows. If you lose the app, another person with command-line access can turn this off for you.",
    null,
    { title: "Turn on two-step sign-in", confirmLabel: "Turn on", extra },
  );
  if (!ok) return;
  try {
    await api("POST", "/admin/api/me/totp/enable", { code: code.input.value.replace(/\s+/g, "") });
    notice("Two-step sign-in is on.");
    await reload();
  } catch (err) {
    fail(err);
  }
}

async function turnOffTwoStep() {
  const pw = newInput("password", "current-password");
  const code = newInput("text", "one-time-code", { inputMode: "numeric", pattern: "[0-9 ]*" });
  const extra = el(
    "div",
    {},
    el("p", {}, el("label", { for: pw.id }, "Your password")),
    el("p", {}, pw.input),
    el("p", {}, el("label", { for: code.id }, "Current code from the app")),
    el("p", {}, code.input),
  );
  const ok = await confirmDialog("Enter your password and a current code to turn two-step sign-in off.", null, {
    title: "Turn off two-step sign-in",
    confirmLabel: "Turn off",
    extra,
  });
  if (!ok) return;
  try {
    await api("POST", "/admin/api/me/totp/disable", { password: pw.input.value, code: code.input.value.replace(/\s+/g, "") });
    notice("Two-step sign-in is off.");
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
    return el(
      "span",
      {},
      button("Change password", "Change your password", () => resetPassword(a, true)),
      " ",
      a.twoStep
        ? button("Turn off two-step sign-in", "Turn off two-step sign-in", turnOffTwoStep)
        : button("Turn on two-step sign-in", "Turn on two-step sign-in", turnOnTwoStep),
    );
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
    a.twoStep ? "On" : "Off",
    formatTime(a.createdAt),
    formatTime(a.lastLoginAt),
    actions(a),
  ]);
  list.appendChild(table("Admins", ["Username", "State", "Two-step sign-in", "Added", "Last signed in", "Actions"], body));
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
