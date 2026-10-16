//! mwc-bridge: MobileWebCamera (Android) から H.264 を受信し、デコードして
//! v4l2loopback 仮想カメラ（OBS の「映像キャプチャデバイス」）や Y4M パイプへ出力する。

mod output;
mod protocol;
mod render;

use anyhow::{Context, Result, bail};
use clap::{Parser, ValueEnum};
use openh264::decoder::Decoder;
use openh264::formats::YUVSource;
use output::{NullOutput, Output, Y4mOutput};
use render::{I420, PlanesRef, Renderer, Transform};
use std::io::BufReader;
use std::net::{TcpStream, ToSocketAddrs};
use std::process::Command;
use std::time::{Duration, Instant};

#[derive(Copy, Clone, Debug, PartialEq, Eq, ValueEnum)]
enum OutputKind {
    /// v4l2loopback デバイス (Linux)
    V4l2,
    /// YUV4MPEG2 をファイル/標準出力へ
    Y4m,
    /// 出力しない（受信テスト用）
    Null,
}

#[derive(Parser, Debug)]
#[command(
    version,
    about = "MobileWebCamera desktop bridge (Android → v4l2loopback / Y4M)"
)]
struct Args {
    /// 端末の IP アドレス（--adb 使用時は無視して 127.0.0.1）
    #[arg(long, default_value = "127.0.0.1")]
    host: String,

    /// RAW ストリームのポート
    #[arg(long, default_value_t = 4748)]
    port: u16,

    /// USB 接続: `adb forward` を自動実行して 127.0.0.1 経由で受信する
    #[arg(long)]
    adb: bool,

    /// adb の端末シリアル (複数台接続時)
    #[arg(long)]
    serial: Option<String>,

    /// 出力先
    #[arg(long, value_enum, default_value_t = default_output())]
    output: OutputKind,

    /// v4l2loopback デバイス
    #[arg(long, default_value = "/dev/video10")]
    device: String,

    /// Y4M の出力先 ("-" で標準出力)
    #[arg(long, default_value = "-")]
    y4m_path: String,

    /// 出力の幅（省略時は最初のフレームから決定）。以降サイズが変わってもこの解像度に収める
    #[arg(long)]
    width: Option<usize>,

    /// 出力の高さ
    #[arg(long)]
    height: Option<usize>,

    /// 時計回りの回転 (0/90/180/270)
    #[arg(long, default_value_t = 0, value_parser = parse_rotation)]
    rotate: u16,

    /// 左右反転
    #[arg(long)]
    mirror: bool,

    /// Y4M ヘッダに書くフレームレート
    #[arg(long, default_value_t = 30)]
    fps: u32,
}

fn default_output() -> OutputKind {
    if cfg!(target_os = "linux") {
        OutputKind::V4l2
    } else {
        OutputKind::Y4m
    }
}

fn parse_rotation(s: &str) -> Result<u16, String> {
    match s.parse::<u16>() {
        Ok(v @ (0 | 90 | 180 | 270)) => Ok(v),
        _ => Err("0, 90, 180, 270 のいずれか".into()),
    }
}

/// 接続をまたいで保持する出力状態（仮想カメラは再接続しても開いたまま）
struct Sink {
    output: Option<Box<dyn Output>>,
    frame: Option<I420>,
    renderer: Renderer,
}

fn main() -> Result<()> {
    let args = Args::parse();
    let transform = Transform {
        rotate: args.rotate,
        mirror: args.mirror,
    };
    let mut sink = Sink {
        output: None,
        frame: None,
        renderer: Renderer::new(transform),
    };

    loop {
        match run_session(&args, &mut sink) {
            Ok(()) => eprintln!("[mwc] 切断されました"),
            Err(e) => eprintln!("[mwc] {e:#}"),
        }
        eprintln!("[mwc] 2 秒後に再接続します…");
        std::thread::sleep(Duration::from_secs(2));
    }
}

fn adb_forward(args: &Args) -> Result<()> {
    let mut cmd = Command::new("adb");
    if let Some(s) = &args.serial {
        cmd.args(["-s", s]);
    }
    let spec = format!("tcp:{}", args.port);
    let status = cmd
        .args(["forward", &spec, &spec])
        .status()
        .context("adb を実行できません")?;
    if !status.success() {
        bail!("adb forward が失敗しました（端末が接続され USB デバッグが許可されているか確認）");
    }
    Ok(())
}

fn run_session(args: &Args, sink: &mut Sink) -> Result<()> {
    let host = if args.adb {
        adb_forward(args)?;
        "127.0.0.1"
    } else {
        args.host.as_str()
    };
    let addr = (host, args.port)
        .to_socket_addrs()?
        .next()
        .with_context(|| format!("アドレス解決に失敗: {host}"))?;
    eprintln!("[mwc] {addr} に接続中…");
    let stream = TcpStream::connect_timeout(&addr, Duration::from_secs(3))
        .with_context(|| format!("{addr} に接続できません"))?;
    stream.set_nodelay(true)?;
    stream.set_read_timeout(Some(Duration::from_secs(5)))?;
    let mut reader = BufReader::with_capacity(1 << 20, stream);
    protocol::read_handshake(&mut reader)?;
    eprintln!("[mwc] 接続しました。キーフレーム待ち…");

    let mut decoder = Decoder::new().context("openh264 デコーダ初期化")?;
    let mut buf = Vec::new();
    let mut need_key = true;
    let mut stats = Stats::new();

    loop {
        let frame = match protocol::read_frame(&mut reader, &mut buf) {
            Ok(f) => f,
            Err(e) if is_eof(&e) => return Ok(()),
            Err(e) => return Err(e),
        };
        stats.bytes += frame.data.len();
        if need_key && !frame.is_key {
            buf = frame.data;
            continue;
        }
        need_key = false;

        match decoder.decode(&frame.data) {
            Ok(Some(yuv)) => {
                let (w, h) = yuv.dimensions();
                let (ys, uvs, _) = yuv.strides();
                let planes = PlanesRef {
                    width: w,
                    height: h,
                    y: yuv.y(),
                    u: yuv.u(),
                    v: yuv.v(),
                    y_stride: ys,
                    uv_stride: uvs,
                };
                deliver(args, sink, &planes)?;
                stats.frames += 1;
            }
            Ok(None) => {}
            Err(e) => {
                eprintln!("[mwc] デコードエラー ({e}) — 次のキーフレームまでスキップ");
                need_key = true;
            }
        }
        buf = frame.data;
        stats.report(sink);
    }
}

fn is_eof(e: &anyhow::Error) -> bool {
    e.downcast_ref::<std::io::Error>()
        .is_some_and(|io| io.kind() == std::io::ErrorKind::UnexpectedEof)
}

fn deliver(args: &Args, sink: &mut Sink, planes: &PlanesRef) -> Result<()> {
    if sink.output.is_none() {
        let transform = Transform {
            rotate: args.rotate,
            mirror: args.mirror,
        };
        let (rw, rh) = transform.output_dims(planes.width, planes.height);
        // I420 なので偶数に揃える
        let w = args.width.unwrap_or(rw) & !1;
        let h = args.height.unwrap_or(rh) & !1;
        let out: Box<dyn Output> = match args.output {
            #[cfg(target_os = "linux")]
            OutputKind::V4l2 => Box::new(output::V4l2Output::open(&args.device, w, h)?),
            #[cfg(not(target_os = "linux"))]
            OutputKind::V4l2 => {
                bail!("v4l2 出力は Linux のみ対応です。--output y4m を使ってください")
            }
            OutputKind::Y4m => Box::new(Y4mOutput::new(&args.y4m_path, args.fps)?),
            OutputKind::Null => Box::new(NullOutput),
        };
        eprintln!(
            "[mwc] 出力: {} (入力 {}x{})",
            out.describe(),
            planes.width,
            planes.height
        );
        sink.frame = Some(I420::new(w, h));
        sink.output = Some(out);
    }
    let frame = sink.frame.as_mut().unwrap();
    sink.renderer.render(planes, frame);
    sink.output.as_mut().unwrap().write_frame(frame)
}

struct Stats {
    since: Instant,
    frames: u32,
    bytes: usize,
}

impl Stats {
    fn new() -> Self {
        Self {
            since: Instant::now(),
            frames: 0,
            bytes: 0,
        }
    }

    fn report(&mut self, sink: &Sink) {
        let dt = self.since.elapsed();
        if dt < Duration::from_secs(5) {
            return;
        }
        let secs = dt.as_secs_f64();
        eprintln!(
            "[mwc] {:.1} fps, {:.2} Mbps → {}",
            self.frames as f64 / secs,
            self.bytes as f64 * 8.0 / secs / 1e6,
            sink.output
                .as_ref()
                .map(|o| o.describe())
                .unwrap_or_default()
        );
        *self = Self::new();
    }
}
