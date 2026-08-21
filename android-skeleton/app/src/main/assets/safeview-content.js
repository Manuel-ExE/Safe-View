(function () {
  if (window.__safeviewInjected) return;
  window.__safeviewInjected = true;

  const DEFAULTS = {
    enabled: true,
    strict: true,
    reveal: false,
    displayMode: "blur",
    aiEnabled: false,
    aiThreshold: 0.40,
    revealingThreshold: 0.12,
    nonce: "",
    generation: 0,
    originAllowed: false
  };

  let settings = Object.assign({}, DEFAULTS, window.SafeViewNativeSettings || {});

  let processed = new WeakSet();
  let lastSources = new WeakMap();
  let aiChecked = new WeakSet();
  let retryCounts = new WeakMap();
  const pendingById = new Map();
  const MAX_PENDING = 64;
  const PENDING_TTL_MS = 15000;

  function uuid() {
    if (typeof crypto !== "undefined" && crypto.randomUUID) return crypto.randomUUID();
    return "sv-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2, 10);
  }

  const explicitTerms =
    /(^|[^a-z])(nude|nudity|nsfw|porn|porno|xxx|sex|sexual|erotic|onlyfans|boobs?|breasts?|tits?|nipples?|penis|vagina|genitals?|fuck|fucking|blowjob|anal|hentai|escort|adult|topless|bottomless)([^a-z]|$)/i;
  const strongTerms =
    /porn|porno|xxx|onlyfans|nude|nudity|nsfw|explicit|fucking|blowjob|hentai|topless|bottomless/i;
  const revealingTerms =
    /deep[ -]?cleavage|cleavage|sideboob|underboob|bare[ -]?midriff|bare[ -]?chest|bare[ -]?back|bare[ -]?shoulder|see[ -]?through|sheer|transparent|lingerie|bra|bralette|thong|g[ -]?string|bikini|micro[ -]?bikini|swimsuit|swimwear|one[ -]?piece|monokini|micro[ -]?dress|mini[ -]?dress|short[ -]?dress|tight[ -]?dress|bodycon|skin[ -]?tight|revealing|low[ -]?cut|plunging|off[ -]?shoulder|strapless|backless|crop[ -]?top|cropped|hot[ -]?pants|short[ -]?shorts|booty[ -]?shorts|yoga[ -]?pants|leggings|busty|big[ -]?(?:boobs?|breasts?|butt|ass)|booty|twerk|curvy|curves|sexy|seductive|alluring|provocative|pin[ -]?up|glamour|boudoir|underwear|panties|negligee|corset|bustier|fishnet|mesh|lace[ -]?bra|push[ -]?up/i;

  const visibleObserver = new IntersectionObserver(
    function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) requestAiClassify(entry.target);
      });
    },
    { rootMargin: "800px", threshold: 0 }
  );

  function clean(value) {
    try {
      return decodeURIComponent(String(value).replace(/[+_-]+/g, " "));
    } catch {
      return String(value);
    }
  }

  function nearbyText(el) {
    const pieces = [];
    let node = el;
    for (let depth = 0; node && depth < 3; depth++, node = node.parentElement) {
      pieces.push(
        node.getAttribute && node.getAttribute("alt"),
        node.getAttribute && node.getAttribute("title"),
        node.getAttribute && node.getAttribute("aria-label"),
        node.getAttribute && node.getAttribute("href"),
        node.getAttribute && node.getAttribute("src"),
        node.getAttribute && node.getAttribute("poster")
      );
      if (node.innerText && node.innerText.length < 240) pieces.push(node.innerText);
    }
    return clean(pieces.filter(Boolean).join(" ")).slice(0, 900);
  }

  function isExplicit(el) {
    const text = nearbyText(el);
    return (
      explicitTerms.test(text) ||
      (settings.strict && (strongTerms.test(text) || revealingTerms.test(text)))
    );
  }

  function mediaTarget(el) {
    if (!el || !el.matches) return null;
    return el.matches("img, video")
      ? el
      : el.querySelector && el.querySelector("img, video, [data-src], [data-srcset]");
  }

  function block(el) {
    el.classList.add("safeview-blocked");
    if (settings.displayMode === "placeholder") {
      el.classList.add("safeview-placeholder-mode");
    }
    const parent = el.parentElement;
    if (!parent) return;
    const style = window.getComputedStyle(parent);
    if (style.position === "static") {
      parent.dataset.safeviewAddedPosition = "true";
      parent.style.position = "relative";
    }
    if (!parent.querySelector(":scope > .safeview-label") && settings.displayMode !== "placeholder") {
      const label = document.createElement("span");
      label.className = "safeview-label";
      label.textContent = settings.reveal
        ? "Potentially explicit — tap to reveal"
        : "Potentially explicit media";
      parent.appendChild(label);
      if (settings.reveal) {
        el.addEventListener(
          "click",
          function () {
            el.classList.add("safeview-revealed");
            label.classList.add("safeview-revealed");
          },
          { passive: true }
        );
      }
    }
  }

  function inspect(el) {
    const target = mediaTarget(el);
    if (!target) return;
    const source =
      target.currentSrc ||
      target.src ||
      (target.getAttribute && target.getAttribute("data-src")) ||
      target.poster ||
      "";
    if (processed.has(target) && lastSources.get(target) === source) return;
    lastSources.set(target, source);
    processed.add(target);
    aiChecked.delete(target);

    if (!settings.enabled) return;
    if (isExplicit(target)) {
      block(target);
      return;
    }
    if (settings.aiEnabled && settings.originAllowed && window.SafeViewAndroid) {
      visibleObserver.observe(target);
    }
  }

  function tryCaptureDataUrl(el) {
    try {
      const size = 224;
      const canvas = document.createElement("canvas");
      canvas.width = size;
      canvas.height = size;
      const ctx = canvas.getContext("2d", { alpha: false });
      if (!ctx) return null;
      ctx.drawImage(el, 0, 0, size, size);
      return canvas.toDataURL("image/jpeg", 0.7);
    } catch (_) {
      return null;
    }
  }

  function prunePending() {
    const now = Date.now();
    pendingById.forEach(function (entry, id) {
      if (now - entry.ts > PENDING_TTL_MS) {
        pendingById.delete(id);
        if (entry.el) aiChecked.delete(entry.el);
      }
    });
    if (pendingById.size <= MAX_PENDING) return;
    const ordered = Array.from(pendingById.entries()).sort(function (a, b) {
      return a[1].ts - b[1].ts;
    });
    while (pendingById.size > MAX_PENDING && ordered.length) {
      const dead = ordered.shift();
      pendingById.delete(dead[0]);
      if (dead[1].el) aiChecked.delete(dead[1].el);
    }
  }

  function requestAiClassify(target) {
    if (!settings.enabled || !settings.aiEnabled || !settings.originAllowed) return;
    if (!window.SafeViewAndroid || !settings.nonce) return;
    if (aiChecked.has(target)) return;

    const rect = target.getBoundingClientRect && target.getBoundingClientRect();
    if (rect && (rect.width < 56 || rect.height < 56)) return;

    if (target instanceof HTMLVideoElement && target.readyState < 2) {
      scheduleRetry(target, 800);
      return;
    }
    if (target instanceof HTMLImageElement && (!target.complete || !target.naturalWidth)) {
      scheduleRetry(target, 800);
      return;
    }

    const src = target.currentSrc || target.src || target.poster || "";
    const dataUrl = tryCaptureDataUrl(target);
    if (!dataUrl && !(src && src.indexOf("https://") === 0)) return;

    prunePending();
    const id = uuid();
    const gen = settings.generation;
    const nonce = settings.nonce;
    aiChecked.add(target);
    pendingById.set(id, { el: target, ts: Date.now(), nonce: nonce, generation: gen });

    try {
      window.SafeViewAndroid.classify(
        JSON.stringify({
          id: id,
          nonce: nonce,
          src: src.indexOf("https://") === 0 ? src : "",
          dataUrl: dataUrl || ""
        })
      );
    } catch (_) {
      pendingById.delete(id);
      aiChecked.delete(target);
    }
  }

  function scheduleRetry(target, delay) {
    const attempts = retryCounts.get(target) || 0;
    if (attempts >= 5 || target.dataset.safeviewRetryPending === "true") return;
    retryCounts.set(target, attempts + 1);
    target.dataset.safeviewRetryPending = "true";
    setTimeout(function () {
      delete target.dataset.safeviewRetryPending;
      requestAiClassify(target);
    }, delay);
  }

  window.SafeViewOnClassifyResult = function (result) {
    if (!result || !result.id) return;
    // Must match current page session — drop cross-navigation / colliding results
    if (result.nonce && result.nonce !== settings.nonce) {
      pendingById.delete(result.id);
      return;
    }
    if (typeof result.generation === "number" && result.generation !== settings.generation) {
      pendingById.delete(result.id);
      return;
    }
    const entry = pendingById.get(result.id);
    pendingById.delete(result.id);
    if (!entry || !entry.el) return;
    if (entry.nonce !== settings.nonce) return;
    if (result.blocked && settings.enabled) {
      block(entry.el);
    } else if (result.error) {
      aiChecked.delete(entry.el);
    }
  };

  setInterval(prunePending, 5000);

  function scan(root) {
    if (!settings.enabled) return;
    root = root || document;
    const nodes = root.querySelectorAll
      ? root.querySelectorAll(
          'img, video, [role="img"], picture, [data-test-id*="pin"], [data-test-id*="Pin"]'
        )
      : [];
    for (let i = 0; i < nodes.length; i++) inspect(nodes[i]);
  }

  const style = document.createElement("style");
  style.textContent = [
    ".safeview-blocked { position: relative !important; filter: blur(28px) grayscale(1) !important; background: #151922 !important; }",
    ".safeview-blocked.safeview-placeholder-mode { filter: none !important; opacity: 0 !important; }",
    ".safeview-blocked.safeview-revealed { filter: none !important; opacity: 1 !important; }",
    ".safeview-label { position: absolute !important; z-index: 2147483647 !important; inset: 50% auto auto 50% !important; transform: translate(-50%, -50%) !important; padding: 8px 12px !important; border-radius: 8px !important; color: white !important; background: rgba(10,12,18,.86) !important; font: 600 12px/1.2 system-ui, sans-serif !important; pointer-events: none !important; white-space: nowrap !important; }",
    ".safeview-label.safeview-revealed { display: none !important; }"
  ].join("\n");
  (document.head || document.documentElement).appendChild(style);

  const observer = new MutationObserver(function (records) {
    for (let r = 0; r < records.length; r++) {
      const record = records[r];
      if (record.type === "attributes" && record.target.matches) {
        if (record.target.matches("img, video, [role=img], picture")) {
          processed.delete(record.target);
          aiChecked.delete(record.target);
          inspect(record.target);
        }
      }
      for (let n = 0; n < record.addedNodes.length; n++) {
        const node = record.addedNodes[n];
        if (node.nodeType === 1) {
          inspect(node);
          scan(node);
        }
      }
    }
  });

  observer.observe(document.documentElement, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: [
      "alt", "title", "aria-label", "src", "srcset", "data-src", "data-srcset",
      "data-lazy-src", "poster", "href"
    ]
  });

  scan();
  setInterval(function () {
    if (settings.enabled) scan();
  }, 3000);

  window.SafeViewUpdateSettings = function (s) {
    settings = Object.assign({}, settings, s || {});
    document.querySelectorAll(".safeview-blocked").forEach(function (el) {
      el.classList.remove("safeview-blocked", "safeview-revealed", "safeview-placeholder-mode");
    });
    document.querySelectorAll(".safeview-label").forEach(function (label) {
      label.remove();
    });
    processed = new WeakSet();
    lastSources = new WeakMap();
    aiChecked = new WeakSet();
    pendingById.clear();
    if (settings.enabled) scan();
  };

  console.log("[SafeView] content script injected (origin-gated AI, generation-bound callbacks)");
})();
