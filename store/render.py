#!/usr/bin/env python3
"""Google Play 用の画像を store/out/ に書き出す。

  python3 store/render.py            フィーチャーグラフィックとスクリーンショットをすべて
  python3 store/render.py feature    フィーチャーグラフィックだけ

スクリーンショットの中身は store/screenshots.json、元画像は store/raw/ に置く。
"""
import json
import shutil
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlencode

STORE = Path(__file__).resolve().parent
OUT = STORE / "out"
CHROME = next(
    (c for c in ("google-chrome", "chromium", "chromium-browser") if shutil.which(c)), None
)


def shoot(page: str, fragment: str, size: tuple[int, int], dest: Path) -> None:
    subprocess.run(
        [
            CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars",
            "--allow-file-access-from-files", "--virtual-time-budget=5000",
            f"--window-size={size[0]},{size[1]}", f"--screenshot={dest}",
            f"{(STORE / page).as_uri()}#{fragment}",
        ],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    print(dest.relative_to(STORE.parent))


def main() -> None:
    if CHROME is None:
        sys.exit("Chrome / Chromium が見つかりません")
    OUT.mkdir(exist_ok=True)
    only = sys.argv[1] if len(sys.argv) > 1 else None

    for lang in ("ja", "en"):
        shoot("feature-graphic.html", lang, (1024, 500), OUT / f"feature-graphic-{lang}.png")
    if only == "feature":
        return

    shots = json.loads((STORE / "screenshots.json").read_text())
    for lang, items in shots.items():
        for i, item in enumerate(items, 1):
            if not (STORE / item["img"]).is_file():
                print(f"skip: {item['img']} がありません")
                continue
            shoot("screenshot.html", urlencode({**item, "lang": lang}), (1080, 1920),
                  OUT / f"screenshot-{lang}-{i:02}.png")


if __name__ == "__main__":
    main()
