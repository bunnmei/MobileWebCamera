//! Android アプリ (StreamServer, RAW プロトコル) とのワイヤフォーマット。
//!
//! ```text
//! 接続直後: b"MWC1"
//! 以降フレームごと:
//!   u32 BE  payload 長
//!   u8      flags (bit0 = キーフレーム)
//!   [u8; 3] 予約
//!   u64 BE  PTS (マイクロ秒)
//!   payload H.264 Annex-B アクセスユニット（キーフレームは SPS/PPS 付き）
//! ```

use anyhow::{Context, Result, bail};
use std::io::Read;

pub const MAGIC: &[u8; 4] = b"MWC1";
const MAX_FRAME: usize = 32 * 1024 * 1024;

pub struct Frame {
    pub data: Vec<u8>,
    #[allow(dead_code)] // 現状は未使用（A/V 同期を追加する際に使う）
    pub pts_us: u64,
    pub is_key: bool,
}

pub fn read_handshake(r: &mut impl Read) -> Result<()> {
    let mut magic = [0u8; 4];
    r.read_exact(&mut magic).context("handshake")?;
    if &magic != MAGIC {
        bail!(
            "unexpected magic {magic:?} (MPEG-TS ポート 4747 ではなく RAW ポート 4748 に接続してください)"
        );
    }
    Ok(())
}

pub fn read_frame(r: &mut impl Read, buf: &mut Vec<u8>) -> Result<Frame> {
    let mut header = [0u8; 16];
    r.read_exact(&mut header)?;
    let len = u32::from_be_bytes(header[0..4].try_into().unwrap()) as usize;
    if len > MAX_FRAME {
        bail!("frame too large: {len} bytes");
    }
    let is_key = header[4] & 1 != 0;
    let pts_us = u64::from_be_bytes(header[8..16].try_into().unwrap());
    buf.resize(len, 0);
    r.read_exact(buf)?;
    Ok(Frame {
        data: std::mem::take(buf),
        pts_us,
        is_key,
    })
}
