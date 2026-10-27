import { api, el, clear, ready, fail, notice, formatTime, definitionList, table, confirmDialog } from "./common.js";

const root = document.getElementById("account");
const id = new URLSearchParams(window.location.search).get("id");

const REASONS = [
  ["user_request", "The user asked"],
  ["abuse", "Abuse"],
  ["other", "Other"],
];

async function signOutDevice(acc, dev) {
  const ok = await confirmDialog(
    "Sign out " + dev.name + " from " + acc.username + "? That device is signed out now and has to sign in again.",
    null,
    { title: "Sign out this device", confirmLabel: "Sign out this device" },
  );
  if (!ok) return;
  try {
    await api("POST", "/admin/api/accounts/" + encodeURIComponent(acc.id) + "/devices/" + encodeURIComponent(dev.id) + "/revoke");
    notice("Device signed out.");
    await load();
  } catch (err) {
    fail(err);
  }
}

async function deleteAccount(acc) {
  const select = el("select", { id: "delete-reason" }, REASONS.map(([v, l]) => el("option", { value: v }, l)));
  const extra = el("p", {}, el("label", { for: "delete-reason" }, "Reason"), select);
  const ok = await confirmDialog(
    "This deletes the account " + acc.username + " and all its devices. It cannot be undone.",
    acc.username,
    { title: "Delete account", confirmLabel: "Delete account", extra },
  );
  if (!ok) return;
  try {
    await api("POST", "/admin/api/accounts/" + encodeURIComponent(acc.id) + "/delete", {
      confirmUsername: acc.username,
      reason: select.value,
    });
    window.location.href = "accounts.html?deleted=1";
  } catch (err) {
    fail(err);
  }
}

function render(acc) {
  clear(root);
  document.title = acc.username + " - Server admin";
  root.appendChild(
    definitionList([
      ["Username", acc.username],
      ["Display name", acc.displayName],
      ["Email", acc.email],
      ["Created", formatTime(acc.createdAt)],
      ["Devices", String((acc.devices || []).length)],
    ]),
  );
  root.appendChild(el("h2", {}, "Devices"));
  if ((acc.devices || []).length === 0) {
    root.appendChild(el("p", {}, "This account has no devices."));
  } else {
    const body = acc.devices.map((d) => {
      const b = el("button", { type: "button", className: "secondary", "aria-label": "Sign out this device: " + d.name }, "Sign out this device");
      b.addEventListener("click", () => signOutDevice(acc, d));
      return [d.name, d.platform, formatTime(d.createdAt), formatTime(d.lastSeenAt), b];
    });
    root.appendChild(table("Devices", ["Name", "Type", "Added", "Last seen", "Action"], body));
  }
  root.appendChild(el("h2", {}, "Delete account"));
  root.appendChild(el("p", {}, "Deleting an account removes it for good. You will be asked to type the username."));
  const del = el("button", { type: "button", className: "danger" }, "Delete account");
  del.addEventListener("click", () => deleteAccount(acc));
  root.appendChild(el("p", {}, del));
}

async function load() {
  try {
    render(await api("GET", "/admin/api/accounts/" + encodeURIComponent(id)));
  } catch (err) {
    clear(root);
    fail(err.status === 404 ? Object.assign(err, { message: "That account does not exist." }) : err);
  }
}

await ready;
if (!id) {
  notice("No account was chosen. Go back to the account search.", true);
} else {
  await load();
}
