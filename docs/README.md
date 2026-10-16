# docs/ — GitHub Pages サイト

https://bunnmei.github.io/MobileWebCamera/ で公開されるアプリのサイトです（日本語 / 英語）。

ローカルで起動

```
 python3 -m http.server 8000
```

```
docs/
  .nojekyll        Jekyll を通さず、そのまま公開する
  index.html       言語の振り分け（前回選んだ言語 → ブラウザの言語）
  assets/          ← 共通部品。ほかのアプリにもそのままコピーして使う
    site.css         デザイン（ライト/ダーク自動切替）
    site.js          ヘッダーの影、言語の記憶、コードのコピーボタン
  img/icon.webp    ← アプリごと（アイコン）
  ja/  en/         ← アプリごと（各ページの本文）
    index.html       トップ
    guide.html       使い方
    privacy.html     プライバシーポリシー
```

## 公開のしかた

GitHub のリポジトリ → Settings → Pages → Source を「Deploy from a branch」、Branch を `main` / `/docs` にして保存。

Google Play Console に入れるプライバシーポリシーの URL：
`https://bunnmei.github.io/MobileWebCamera/en/privacy.html`（日本語版は `/ja/privacy.html`）

## ほかのアプリで使い回すには

1. `docs/` をまるごと新しいリポジトリにコピーする
2. `img/icon.webp` を差し替える
3. `ja/` `en/` の各ページで、アプリ名・説明・リポジトリの URL を書き換える
4. アプリの色に合わせたいときは、`<html lang="ja" style="--accent: #c2185b">` のように `--accent` を上書きする

### 使える部品（site.css）

| クラス | 見た目 |
|---|---|
| `.hero` | トップの大きな見出し |
| `.summary` | ページ冒頭の要約ボックス |
| `.callout` / `.callout--warn` | 補足・注意のボックス |
| `.steps`（`<ol>`） | 丸数字付きの手順。各 `<li>` の最初の `<strong>` が見出しになる |
| `.cards` > `.card` | カードの並び（`<a class="card">` でリンクにもできる） |
| `.toc` | 目次 |
| `.table-wrap` > `<table>` | 角丸の表 |
| `<details>` / `<summary>` | 開閉する Q&A |
| `.btn` / `.btn--tonal` | ボタン |

ページを足すときは、既存のページをコピーして、`<nav class="nav">` の `aria-current="page"` と、言語切替リンク（`.lang-switch`）の行き先を合わせて直してください。
