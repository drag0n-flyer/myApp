import { api, el, clear, ready, fail, notice, formatTime, definitionList } from "./common.js";

const root = document.getElementById("report");
const id = new URLSearchParams(window.location.search).get("id");

const name = (v, fallback) => (v === null || v === undefined ? fallback : v);

async function markHandled() {
  try {
    await api("POST", "/admin/api/reports/" + encodeURIComponent(id) + "/handle");
    notice("Report marked as handled.");
    await load();
  } catch (err) {
    fail(err);
  }
}

function render(r) {
  clear(root);
  root.appendChild(
    definitionList([
      ["Reported", formatTime(r.createdAt)],
      ["Reason", r.reason],
      ["Reported account", name(r.reportedUsername, "deleted account")],
      ["Reporter", name(r.reporterUsername, "deleted")],
      ["Conversation ID", r.conversationID],
      ["Shared messages", String(r.messageCount)],
      ["Note", r.hasNote ? "yes" : "no"],
      ["State", r.handledAt ? "Handled " + formatTime(r.handledAt) : "Open"],
      ["Handled by (admin id)", r.handledBy],
    ]),
  );
  // O-2: the shared messages and the note are not shown until the owner approves it.
  const button = el("button", { type: "button", disabled: r.handledAt ? true : false }, "Mark handled");
  button.addEventListener("click", markHandled);
  root.appendChild(el("p", {}, button));
}

async function load() {
  try {
    render(await api("GET", "/admin/api/reports/" + encodeURIComponent(id)));
  } catch (err) {
    clear(root);
    fail(err.status === 404 ? Object.assign(err, { message: "That report does not exist." }) : err);
  }
}

await ready;
if (!id) {
  notice("No report was chosen. Go back to the reports.", true);
} else {
  await load();
}
