import { api, el, ready, fail, definitionList, onOff } from "./common.js";

const root = document.getElementById("server");

const WORDS = { url: "URL", id: "ID", ip: "IP", dns: "DNS" };

// humanize turns "trustProxy" or "max_devices" into "Trust proxy".
function humanize(key) {
  const words = key
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .replace(/[_-]+/g, " ")
    .toLowerCase()
    .split(" ")
    .map((w) => WORDS[w] || w);
  const text = words.join(" ");
  return text.charAt(0).toUpperCase() + text.slice(1);
}

function isPlain(v) {
  return v === null || typeof v !== "object";
}

// value shows any JSON value as words, never as raw JSON.
function value(v) {
  if (v === null || v === undefined || v === "") return "—";
  if (typeof v === "boolean") return onOff(v);
  if (Array.isArray(v)) {
    if (v.length === 0) return "None";
    if (v.every(isPlain)) return v.map((x) => (typeof x === "boolean" ? onOff(x) : String(x))).join(", ");
    return el("ul", {}, v.map((x) => el("li", {}, value(x))));
  }
  if (typeof v === "object") return facts(v);
  return String(v);
}

function facts(obj) {
  return definitionList(Object.entries(obj).map(([k, v]) => [humanize(k), value(v)]));
}

function section(title, obj) {
  root.appendChild(el("h2", {}, title));
  root.appendChild(obj && typeof obj === "object" ? facts(obj) : el("p", {}, "Nothing to show."));
}

await ready;
try {
  const s = await api("GET", "/admin/api/server");
  const settings = s.settings || {};
  const labKey = Object.keys(settings).find((k) => /lab/i.test(k));
  if (labKey && settings[labKey] === true) {
    document.getElementById("lab-banner").appendChild(
      el(
        "div",
        { className: "banner", role: "alert" },
        el("strong", {}, "Warning: lab mode is on. "),
        "Anyone with shell access to this server can sign in as any account, including existing ones. Never set APP_LAB_MODE on a real server. Turn it off and restart the server.",
      ),
    );
  }
  root.appendChild(el("p", {}, "This page is read-only. To change the policy, edit the policy file on the server and restart it."));
  section("Settings", settings);
  section("Policy", s.policy);
  section("Server information", s.info);
} catch (err) {
  fail(err);
}
