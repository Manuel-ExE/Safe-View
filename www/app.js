(function () {
  const DEFAULTS = {
    enabled: true,
    strict: true,
    reveal: false,
    aiEnabled: false
  };

  function loadSettings() {
    try {
      const raw = localStorage.getItem("safeview-settings");
      return raw ? { ...DEFAULTS, ...JSON.parse(raw) } : { ...DEFAULTS };
    } catch {
      return { ...DEFAULTS };
    }
  }

  function saveSettings(s) {
    localStorage.setItem("safeview-settings", JSON.stringify(s));
  }

  let settings = loadSettings();

  // UI bindings
  const ids = ["enabled", "strict", "reveal", "aiEnabled"];
  ids.forEach((id) => {
    const el = document.getElementById(id);
    if (!el) return;
    el.checked = Boolean(settings[id]);
    el.addEventListener("change", () => {
      settings[id] = el.checked;
      saveSettings(settings);
    });
  });

  const browser = document.getElementById("browser");
  const frame = document.getElementById("frame");
  const urlBar = document.getElementById("urlBar");

  function openBrowser(url) {
    url = String(url || "").trim();
    if (url.startsWith("http://")) {
      window.alert("Only HTTPS pages are supported.");
      return;
    }
    if (!url.startsWith("https://")) url = "https://" + url;
    urlBar.value = url;
    browser.hidden = false;
    // Note: cross-origin iframes cannot receive our filter script.
    // The real filtering happens in the native Android WebView (see android-skeleton).
    // Here we still load the page for a basic experience / demo.
    frame.src = url;
  }

  function closeBrowser() {
    browser.hidden = true;
    frame.src = "about:blank";
  }

  document.getElementById("btnPinterest").onclick = () =>
    openBrowser("https://www.pinterest.com/");
  document.getElementById("btnGoogle").onclick = () =>
    openBrowser("https://www.google.com/imghp");
  document.getElementById("btnCustom").onclick = () => {
    const u = prompt("Enter URL", "https://");
    if (u) openBrowser(u);
  };
  document.getElementById("btnClose").onclick = closeBrowser;
  document.getElementById("btnBack").onclick = () => {
    try { frame.contentWindow.history.back(); } catch (_) {}
  };
  document.getElementById("btnGo").onclick = () => {
    openBrowser(urlBar.value.trim());
  };
  urlBar.addEventListener("keydown", (e) => {
    if (e.key === "Enter") openBrowser(urlBar.value.trim());
  });

  // Expose settings for native bridge (Capacitor / WebView interface)
  window.SafeViewBridge = {
    getSettings: () => settings,
    setSettings: (s) => {
      settings = { ...settings, ...s };
      saveSettings(settings);
      ids.forEach((id) => {
        const el = document.getElementById(id);
        if (el) el.checked = Boolean(settings[id]);
      });
    }
  };
})();
