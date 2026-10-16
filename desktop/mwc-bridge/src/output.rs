//! デコード済みフレームの出力先。

use crate::render::I420;
use anyhow::{Context, Result};
use std::io::Write;

pub trait Output {
    fn write_frame(&mut self, frame: &I420) -> Result<()>;
    fn describe(&self) -> String;
}

/// YUV4MPEG2 (stdout またはファイル)。`| ffplay -` や `| ffmpeg -i - ...` に渡せる。
pub struct Y4mOutput {
    sink: Box<dyn Write>,
    header_written: bool,
    fps: u32,
    path: String,
}

impl Y4mOutput {
    pub fn new(path: &str, fps: u32) -> Result<Self> {
        let sink: Box<dyn Write> = if path == "-" {
            Box::new(std::io::stdout().lock())
        } else {
            Box::new(std::fs::File::create(path).with_context(|| format!("create {path}"))?)
        };
        Ok(Self {
            sink,
            header_written: false,
            fps,
            path: path.to_string(),
        })
    }
}

impl Output for Y4mOutput {
    fn write_frame(&mut self, frame: &I420) -> Result<()> {
        if !self.header_written {
            writeln!(
                self.sink,
                "YUV4MPEG2 W{} H{} F{}:1 Ip A1:1 C420jpeg",
                frame.width, frame.height, self.fps
            )?;
            self.header_written = true;
        }
        self.sink.write_all(b"FRAME\n")?;
        self.sink.write_all(&frame.data)?;
        self.sink.flush()?;
        Ok(())
    }

    fn describe(&self) -> String {
        format!("y4m:{}", self.path)
    }
}

/// 何もしない（受信・デコード性能の確認用）
pub struct NullOutput;

impl Output for NullOutput {
    fn write_frame(&mut self, _frame: &I420) -> Result<()> {
        Ok(())
    }

    fn describe(&self) -> String {
        "null".into()
    }
}

#[cfg(target_os = "linux")]
pub use v4l2::V4l2Output;

#[cfg(target_os = "linux")]
mod v4l2 {
    //! v4l2loopback への書き込み。OBS では「映像キャプチャデバイス (V4L2)」で選択できる。

    use super::Output;
    use crate::render::I420;
    use anyhow::{Context, Result, bail};
    use std::fs::{File, OpenOptions};
    use std::io::Write;
    use std::os::fd::AsRawFd;

    #[repr(C)]
    #[derive(Default)]
    struct V4l2PixFormat {
        width: u32,
        height: u32,
        pixelformat: u32,
        field: u32,
        bytesperline: u32,
        sizeimage: u32,
        colorspace: u32,
        priv_: u32,
        flags: u32,
        ycbcr_enc: u32,
        quantization: u32,
        xfer_func: u32,
    }

    /// struct v4l2_format（64bit: union が 8 バイト境界に置かれ全体 208 バイト）
    #[repr(C)]
    struct V4l2Format {
        type_: u32,
        _pad: u32,
        pix: V4l2PixFormat,
        _rest: [u8; 200 - size_of::<V4l2PixFormat>()],
    }

    const _: () = assert!(size_of::<V4l2Format>() == 208);

    const fn iowr(ty: u8, nr: u8, size: usize) -> u64 {
        (3u64 << 30) | ((size as u64) << 16) | ((ty as u64) << 8) | nr as u64
    }

    const VIDIOC_S_FMT: u64 = iowr(b'V', 5, size_of::<V4l2Format>());
    const V4L2_BUF_TYPE_VIDEO_OUTPUT: u32 = 2;
    const V4L2_FIELD_NONE: u32 = 1;
    const V4L2_COLORSPACE_REC709: u32 = 3;
    const V4L2_QUANTIZATION_LIM_RANGE: u32 = 2;

    const fn fourcc(s: &[u8; 4]) -> u32 {
        (s[0] as u32) | ((s[1] as u32) << 8) | ((s[2] as u32) << 16) | ((s[3] as u32) << 24)
    }

    pub struct V4l2Output {
        file: File,
        path: String,
        width: usize,
        height: usize,
    }

    impl V4l2Output {
        pub fn open(path: &str, width: usize, height: usize) -> Result<Self> {
            let file = OpenOptions::new()
                .read(true)
                .write(true)
                .open(path)
                .with_context(|| {
                    format!(
                        "{path} を開けません。v4l2loopback を読み込んでください:\n  \
                         sudo modprobe v4l2loopback devices=1 video_nr=10 card_label=\"MobileWebCamera\" exclusive_caps=1"
                    )
                })?;
            let mut fmt = V4l2Format {
                type_: V4L2_BUF_TYPE_VIDEO_OUTPUT,
                _pad: 0,
                pix: V4l2PixFormat {
                    width: width as u32,
                    height: height as u32,
                    pixelformat: fourcc(b"YU12"),
                    field: V4L2_FIELD_NONE,
                    bytesperline: width as u32,
                    sizeimage: I420::len(width, height) as u32,
                    colorspace: V4L2_COLORSPACE_REC709,
                    quantization: V4L2_QUANTIZATION_LIM_RANGE,
                    ..Default::default()
                },
                _rest: [0; 200 - size_of::<V4l2PixFormat>()],
            };
            // SAFETY: fmt は struct v4l2_format と同じレイアウトで、ioctl の間有効
            let r = unsafe {
                libc::ioctl(
                    file.as_raw_fd(),
                    VIDIOC_S_FMT as _,
                    &mut fmt as *mut V4l2Format,
                )
            };
            if r < 0 {
                let e = std::io::Error::last_os_error();
                bail!(
                    "VIDIOC_S_FMT {width}x{height} に失敗: {e}\n\
                     (他のアプリがこのデバイスを使用中の場合はフォーマットを変更できません。OBS 側のソースを一度無効化してください)"
                );
            }
            Ok(Self {
                file,
                path: path.to_string(),
                width,
                height,
            })
        }
    }

    impl Output for V4l2Output {
        fn write_frame(&mut self, frame: &I420) -> Result<()> {
            debug_assert_eq!((frame.width, frame.height), (self.width, self.height));
            self.file.write_all(&frame.data)?;
            Ok(())
        }

        fn describe(&self) -> String {
            format!("v4l2:{} {}x{} YU12", self.path, self.width, self.height)
        }
    }
}
