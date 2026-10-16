// 共通スクリプト（どのアプリのサイトにもそのままコピーして使える）
(() => {
  // スクロールしたらヘッダーに境界線
  const header = document.querySelector(".site-header");
  if (header) {
    const onScroll = () => header.classList.toggle("is-scrolled", window.scrollY > 4);
    window.addEventListener("scroll", onScroll, { passive: true });
    onScroll();
  }

  // 言語切り替えを選んだら覚えておく（トップページの自動振り分けで使う）
  document.querySelectorAll("[data-set-lang]").forEach((a) => {
    a.addEventListener("click", () => {
      try { localStorage.setItem("lang", a.dataset.setLang); } catch (_) {}
    });
  });

  // コードブロックにコピーボタン
  const label = document.documentElement.lang === "ja" ? ["コピー", "コピーしました"] : ["Copy", "Copied"];
  document.querySelectorAll("pre").forEach((pre) => {
    if (!navigator.clipboard) return;
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "copy-btn";
    btn.textContent = label[0];
    btn.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(pre.querySelector("code")?.innerText ?? pre.innerText);
        btn.textContent = label[1];
        setTimeout(() => (btn.textContent = label[0]), 1500);
      } catch (_) {}
    });
    pre.appendChild(btn);
  });
})();
