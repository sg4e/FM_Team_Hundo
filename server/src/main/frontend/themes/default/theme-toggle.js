(function () {
  function applyStoredTheme() {
    const root = document.body;
    if (!root) {
      return;
    }

    const isDark = window.localStorage.getItem("theme") === "dark";
    root.classList.toggle("dark-mode", isDark);
  }

  window.toggleTheme = function toggleTheme() {
    const root = document.body;
    if (!root) {
      return;
    }

    const nextDark = !root.classList.contains("dark-mode");
    root.classList.toggle("dark-mode", nextDark);
    window.localStorage.setItem("theme", nextDark ? "dark" : "light");
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", applyStoredTheme, { once: true });
  } else {
    applyStoredTheme();
  }
})();
