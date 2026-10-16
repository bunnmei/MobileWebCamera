# MobileWebCamera

Android 端末のカメラを PC 用 Web カメラとして使うアプリです。OBS で受信できます。

- **Android アプリ** (`app/`): Camera2 + Jetpack Compose + Hilt。H.264 にハードウェアエンコードして Wi-Fi / USB で配信
- **デスクトップブリッジ** (`desktop/mwc-bridge/`, Rust): 受信・デコードして v4l2loopback の仮想カメラに出力（任意）

```
[Camera2] ─┬─ SurfaceView (プレビュー)
           └─ MediaCodec H.264 ─┬─ :4747  MPEG-TS (tcp:// / http://)  → OBS「メディアソース」/ VLC
                                └─ :4748  RAW (独自フレーミング)      → mwc-bridge → /dev/videoN → OBS「映像キャプチャデバイス」
```

## Android アプリの機能

| タブ | 内容 |
|---|---|
| 配信 | 配信開始/停止、接続 URL 表示、解像度・フレームレート・ビットレート（配信中も変更可）、キーフレーム要求 |
| レンズ | 全カメラの切替（論理マルチカメラ／cameraIdList に出ていない物理カメラも列挙）、ズーム（0.5x 等の超広角含む）、トーチ・トーチ強度 |
| フォーカス | AF モード（AF-C 動画/写真、AF-S、マクロ、EDOF、MF）、MF 時はピント距離スライダー・過焦点距離・無限遠、プレビューのタップで AF/AE（長押しで解除） |
| 露出 | AE モード（マニュアル含む）、ISO・シャッタースピード、露出補正、AE ロック、フリッカー低減、AE 優先モード(Android 16+) |
| 色 | ホワイトバランス各モード、手動色温度（Android 16 の CCT または RGGB ゲイン）、AWB ロック、エフェクト、トーンマップ、色収差補正 |
| 画質 | 電子/光学手ブレ補正、ノイズリダクション、エッジ強調、3A 制御モード、シーンモード、ボケ(拡張シーン)、オートフレーミング |
| 詳細 | 顔検出、ホットピクセル・シェーディング・歪み補正、テストパターン、絞り、ND フィルタ |
| 情報 | 選択中カメラの CameraCharacteristics 全キー |

各項目はその端末の `CameraCharacteristics` から自動で選択肢が作られるため、端末が対応している機能だけが表示されます。

配信中はアプリをバックグラウンドにしても前景サービスで配信が継続します。

## OBS で受信する

### 方法 A: メディアソース（追加ソフト不要、Windows/macOS/Linux）

1. アプリの「配信」タブで **配信開始**
2. OBS →ソース「＋」→ **メディアソース**
   - 「ローカルファイル」: オフ
   - 入力: `tcp://<端末のIP>:4747`（アプリに表示されます）
   - 入力フォーマット: `mpegts`
   - ネットワークバッファリング: 0 MB（遅延を減らす）
   - 「可能な場合ハードウェアデコードを使用」: オン

`http://<端末のIP>:4747/` でも受信できます（VLC などでも再生可）。

### 方法 B: mwc-bridge + v4l2loopback（Linux, 低遅延・他アプリからもカメラとして見える）

```sh
# 仮想カメラデバイスを作成（Fedora: sudo dnf install v4l2loopback）
sudo modprobe v4l2loopback devices=1 video_nr=10 card_label="MobileWebCamera" exclusive_caps=1

cd desktop/mwc-bridge
cargo build --release
./target/release/mwc-bridge --host <端末のIP>          # Wi-Fi
./target/release/mwc-bridge --adb                       # USB（adb forward を自動実行）
```

OBS →ソース「＋」→ **映像キャプチャデバイス (V4L2)** → `MobileWebCamera` を選択。

主なオプション:

| オプション | 説明 |
|---|---|
| `--rotate 90/180/270` / `--mirror` | 回転・左右反転 |
| `--width 1920 --height 1080` | 出力解像度を固定（途中で解像度・カメラを変えてもレターボックスで収める） |
| `--output y4m --y4m-path -` | Y4M を標準出力へ（例: `mwc-bridge --output y4m \| ffplay -`）|
| `--device /dev/video10` | v4l2loopback デバイス |

Windows/macOS では v4l2 出力は使えないので方法 A を使ってください。

### USB 接続（方法 A）

```sh
adb forward tcp:4747 tcp:4747
# OBS の入力を tcp://127.0.0.1:4747 に
```

## 補足

- 映像の向きはスマホの持ち方に合わせて自動で正立します（配信タブで固定も可能）。
- 物理カメラ単体での出力に対応していない端末では、自動で論理カメラ出力にフォールバックします（ズーム倍率でレンズを切り替えてください）。
- デスクトップブリッジは openh264 でデコードするため、アプリは H.264 Baseline でエンコードしています。

## 自動ビルド (GitHub Actions)

| ワークフロー | タイミング | 内容 |
|---|---|---|
| `CI` | push / PR | Android: lint・単体テスト・デバッグ APK / Rust: fmt・clippy・テスト |
| `Release` | `v*` タグの push | 署名済み AAB・APK、mwc-bridge (Linux / Windows / macOS) を GitHub Release に公開。Play の認証情報があれば Google Play にもアップロード |

```sh
git tag v1.1.0
git push origin v1.1.0
```

## ライセンス

[0BSD](LICENSE)
