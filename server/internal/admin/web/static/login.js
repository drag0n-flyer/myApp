import { api, ApiError, clear } from "./common.js";

const box = document.getElementById("notice");
const title = document.getElementById("title");
const setupForm = document.getElementById("setup-form");
const loginForm = document.getElementById("login-form");
const codeForm = document.getElementById("code-form");

function show(...parts) {
  clear(box);
  box.className = "notice error";
  box.setAttribute("role", "alert");
  parts.forEach((p) => box.appendChild(typeof p === "string" ? document.createTextNode(p) : p));
}

function failure(err, fallback) {
  if (err instanceof ApiError && err.status === 429) show("Too many tries. Wait a minute.");
  else if (err instanceof ApiError && err.status === 409) show(err.message);
  else if (err instanceof ApiError && err.status === 400) show(err.message);
  else if (fallback && err instanceof ApiError && err.status === 401) show(...fallback);
  else show(err instanceof ApiError ? err.message : "Something went wrong.");
}

loginForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const username = document.getElementById("username").value.trim().toLowerCase();
  const password = document.getElementById("password").value;
  if (username === "" || password === "") {
    show("Enter your username and password.");
    return;
  }
  const button = document.getElementById("sign-in");
  button.disabled = true;
  try {
    const res = await api("POST", "/admin/api/login", { username, password }, { stay: true });
    if (res && res.needsCode) {
      // Two-step sign-in: the password was right; ask for the code.
      document.getElementById("password").value = "";
      loginForm.hidden = true;
      codeForm.hidden = false;
      clear(box);
      box.className = "";
      box.removeAttribute("role");
      document.getElementById("code").focus();
      return;
    }
    window.location.href = "index.html";
  } catch (err) {
    document.getElementById("password").value = "";
    failure(err, ["That username and password did not work."]);
    button.disabled = false;
  }
});

codeForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const code = document.getElementById("code").value.replace(/\s+/g, "");
  if (!/^[0-9]{6}$/.test(code)) {
    show("Enter the 6-digit code.");
    return;
  }
  const button = document.getElementById("sign-in-code");
  button.disabled = true;
  try {
    await api("POST", "/admin/api/login/code", { code }, { stay: true });
    window.location.href = "index.html";
  } catch (err) {
    document.getElementById("code").value = "";
    if (err instanceof ApiError && err.status === 401) {
      show("That code did not work, or the sign-in timed out. Check the code, or start again.");
    } else {
      failure(err);
    }
    button.disabled = false;
  }
});

setupForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const username = document.getElementById("setup-username").value.trim().toLowerCase();
  const password = document.getElementById("setup-password").value;
  const confirm = document.getElementById("setup-confirm").value;
  if (!/^[a-z0-9._-]{3,32}$/.test(username)) {
    show("The username must be 3 to 32 characters: letters, digits, dot, underscore or hyphen.");
    return;
  }
  if ([...password].length < 12) {
    show("The password must be at least 12 characters.");
    return;
  }
  if (password !== confirm) {
    show("The two passwords are not the same.");
    return;
  }
  const button = document.getElementById("create-first");
  button.disabled = true;
  try {
    await api("POST", "/admin/api/setup", { username, password }, { stay: true });
    window.location.href = "index.html";
  } catch (err) {
    document.getElementById("setup-password").value = "";
    document.getElementById("setup-confirm").value = "";
    failure(err);
    if (err instanceof ApiError && err.status === 409) {
      // Someone else finished the setup first: show the sign-in form.
      setupForm.hidden = true;
      loginForm.hidden = false;
      title.textContent = "Sign in";
    }
    button.disabled = false;
  }
});

// First visit: with no admin yet, offer to create the first one.
async function start() {
  let needed = false;
  try {
    const s = await api("GET", "/admin/api/setup", undefined, { stay: true });
    needed = Boolean(s && s.needed);
  } catch (err) {
    needed = false;
  }
  if (needed) {
    title.textContent = "Create the first admin";
    setupForm.hidden = false;
    loginForm.hidden = true;
    document.getElementById("setup-username").focus();
  } else {
    const params = new URLSearchParams(window.location.search);
    if (params.get("changed") === "1") {
      clear(box);
      box.className = "notice";
      box.appendChild(document.createTextNode("Password changed. Sign in again."));
    }
  }
}
start();
