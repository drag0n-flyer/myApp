import { api, ApiError, el, clear } from "./common.js";

const form = document.getElementById("login-form");
const box = document.getElementById("notice");

function show(...parts) {
  clear(box);
  box.className = "notice error";
  box.setAttribute("role", "alert");
  parts.forEach((p) => box.appendChild(typeof p === "string" ? document.createTextNode(p) : p));
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  const token = document.getElementById("token").value.trim();
  if (token === "") {
    show("Paste your admin token first.");
    return;
  }
  const button = document.getElementById("sign-in");
  button.disabled = true;
  try {
    await api("POST", "/admin/api/login", { token }, { stay: true });
    window.location.href = "index.html";
  } catch (err) {
    document.getElementById("token").value = "";
    if (err instanceof ApiError && err.status === 401) {
      show("That token is not valid. Create a new one with ", el("code", {}, "appserver admin token create"), ".");
    } else if (err instanceof ApiError && err.status === 429) {
      show("Too many tries. Wait a minute.");
    } else {
      show(err instanceof ApiError ? err.message : "Something went wrong.");
    }
    button.disabled = false;
  }
});
