(function () {
  function setDarkMode(enabled) {
    const body = document.body;
    const root = document.documentElement;
    if (!body || !root) {
      return;
    }

    body.classList.toggle("dark-mode", enabled);
    root.classList.toggle("dark-mode", enabled);
  }

  function applyStoredTheme() {
    const isDark = window.localStorage.getItem("theme") === "dark";
    setDarkMode(isDark);
  }

  window.toggleTheme = function toggleTheme() {
    const body = document.body;
    if (!body) {
      return;
    }

    const nextDark = !body.classList.contains("dark-mode");
    setDarkMode(nextDark);
    window.localStorage.setItem("theme", nextDark ? "dark" : "light");
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", applyStoredTheme, { once: true });
  } else {
    applyStoredTheme();
  }
})();
