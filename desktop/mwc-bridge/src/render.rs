//! デコード済み YUV420 を出力解像度へ回転・反転・レターボックス付きで変換する。
//!
//! 回転/反転/拡縮はいずれも「行に依存する項 + 列に依存する項」に分解できるので、
//! プレーンごとにルックアップテーブルを作って 1 ピクセル 1 回の加算で済ませる。

/// 連続した I420 (YU12) フレーム
pub struct I420 {
    pub width: usize,
    pub height: usize,
    pub data: Vec<u8>,
}

impl I420 {
    pub fn new(width: usize, height: usize) -> Self {
        let mut f = Self {
            width,
            height,
            data: vec![0; Self::len(width, height)],
        };
        f.fill_black();
        f
    }

    pub fn len(width: usize, height: usize) -> usize {
        width * height + 2 * width.div_ceil(2) * height.div_ceil(2)
    }

    fn fill_black(&mut self) {
        let y = self.width * self.height;
        self.data[..y].fill(16);
        self.data[y..].fill(128);
    }
}

/// デコーダから借りたプレーン
pub struct PlanesRef<'a> {
    pub width: usize,
    pub height: usize,
    pub y: &'a [u8],
    pub u: &'a [u8],
    pub v: &'a [u8],
    pub y_stride: usize,
    pub uv_stride: usize,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct Transform {
    /// 時計回り 0/90/180/270
    pub rotate: u16,
    pub mirror: bool,
}

impl Transform {
    /// 回転後のサイズ
    pub fn output_dims(&self, w: usize, h: usize) -> (usize, usize) {
        if self.rotate % 180 == 90 {
            (h, w)
        } else {
            (w, h)
        }
    }
}

#[derive(PartialEq, Eq, Clone, Copy)]
struct Key {
    sw: usize,
    sh: usize,
    stride: usize,
    dw: usize,
    dh: usize,
}

/// 1 プレーン分の LUT。usize::MAX = レターボックス領域
struct PlaneLut {
    key: Key,
    rows: Vec<usize>,
    cols: Vec<usize>,
}

impl PlaneLut {
    fn build(key: Key, t: Transform) -> Self {
        let Key {
            sw,
            sh,
            stride,
            dw,
            dh,
        } = key;
        let (rw, rh) = t.output_dims(sw, sh);
        // アスペクト比を保って収める
        let scale = (dw as f64 / rw as f64).min(dh as f64 / rh as f64);
        let cw = ((rw as f64 * scale).round() as usize).min(dw);
        let ch = ((rh as f64 * scale).round() as usize).min(dh);
        let ox = (dw - cw) / 2;
        let oy = (dh - ch) / 2;

        let map = |d: usize, o: usize, c: usize, r: usize| -> Option<usize> {
            if d < o || d >= o + c {
                None
            } else {
                Some((((d - o) as f64 + 0.5) * r as f64 / c as f64) as usize).map(|v| v.min(r - 1))
            }
        };

        // 回転後座標 (rx, ry) → 元座標 (sx, sy)
        //   0: (rx, ry)   90: (ry, sh-1-rx)   180: (sw-1-rx, sh-1-ry)   270: (sw-1-ry, rx)
        let mut rows = vec![usize::MAX; dh];
        let mut cols = vec![usize::MAX; dw];
        for (x, col) in cols.iter_mut().enumerate() {
            if let Some(mut rx) = map(x, ox, cw, rw) {
                if t.mirror {
                    rx = rw - 1 - rx;
                }
                *col = match t.rotate {
                    90 => (sh - 1 - rx) * stride,
                    180 => sw - 1 - rx,
                    270 => rx * stride,
                    _ => rx,
                };
            }
        }
        for (y, row) in rows.iter_mut().enumerate() {
            if let Some(ry) = map(y, oy, ch, rh) {
                *row = match t.rotate {
                    90 => ry,
                    180 => (sh - 1 - ry) * stride,
                    270 => sw - 1 - ry,
                    _ => ry * stride,
                };
            }
        }
        Self { key, rows, cols }
    }

    fn apply(&self, src: &[u8], dst: &mut [u8], fill: u8) {
        let dw = self.key.dw;
        for (y, &row) in self.rows.iter().enumerate() {
            let out = &mut dst[y * dw..(y + 1) * dw];
            if row == usize::MAX {
                out.fill(fill);
                continue;
            }
            for (o, &col) in out.iter_mut().zip(&self.cols) {
                *o = if col == usize::MAX {
                    fill
                } else {
                    src.get(row + col).copied().unwrap_or(fill)
                };
            }
        }
    }
}

pub struct Renderer {
    transform: Transform,
    luts: [Option<PlaneLut>; 2],
}

impl Renderer {
    pub fn new(transform: Transform) -> Self {
        Self {
            transform,
            luts: [None, None],
        }
    }

    pub fn render(&mut self, src: &PlanesRef, dst: &mut I420) {
        let (dw, dh) = (dst.width, dst.height);
        let (cdw, cdh) = (dw.div_ceil(2), dh.div_ceil(2));
        let (csw, csh) = (src.width.div_ceil(2), src.height.div_ceil(2));
        let identity = self.transform
            == Transform {
                rotate: 0,
                mirror: false,
            };

        let (y_out, uv_out) = dst.data.split_at_mut(dw * dh);
        let (u_out, v_out) = uv_out.split_at_mut(cdw * cdh);

        if identity && src.width == dw && src.height == dh {
            copy_plane(src.y, src.y_stride, y_out, dw, dh);
            copy_plane(src.u, src.uv_stride, u_out, cdw, cdh);
            copy_plane(src.v, src.uv_stride, v_out, cdw, cdh);
            return;
        }

        let t = self.transform;
        let ykey = Key {
            sw: src.width,
            sh: src.height,
            stride: src.y_stride,
            dw,
            dh,
        };
        let ckey = Key {
            sw: csw,
            sh: csh,
            stride: src.uv_stride,
            dw: cdw,
            dh: cdh,
        };
        if self.luts[0].as_ref().is_none_or(|l| l.key != ykey) {
            self.luts[0] = Some(PlaneLut::build(ykey, t));
        }
        if self.luts[1].as_ref().is_none_or(|l| l.key != ckey) {
            self.luts[1] = Some(PlaneLut::build(ckey, t));
        }
        let [Some(yl), Some(cl)] = &self.luts else {
            unreachable!()
        };
        yl.apply(src.y, y_out, 16);
        cl.apply(src.u, u_out, 128);
        cl.apply(src.v, v_out, 128);
    }
}

fn copy_plane(src: &[u8], stride: usize, dst: &mut [u8], w: usize, h: usize) {
    for y in 0..h {
        dst[y * w..(y + 1) * w].copy_from_slice(&src[y * stride..y * stride + w]);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn planes(w: usize, h: usize, y: &[u8]) -> (Vec<u8>, Vec<u8>) {
        let _ = y;
        (
            vec![128; w.div_ceil(2) * h.div_ceil(2)],
            vec![128; w.div_ceil(2) * h.div_ceil(2)],
        )
    }

    fn render_y(src_y: &[u8], w: usize, h: usize, t: Transform) -> Vec<u8> {
        let (u, v) = planes(w, h, src_y);
        let (dw, dh) = t.output_dims(w, h);
        let mut dst = I420::new(dw, dh);
        let p = PlanesRef {
            width: w,
            height: h,
            y: src_y,
            u: &u,
            v: &v,
            y_stride: w,
            uv_stride: w.div_ceil(2),
        };
        Renderer::new(t).render(&p, &mut dst);
        dst.data[..dw * dh].to_vec()
    }

    // 元画像 (3x2):
    // 1 2 3
    // 4 5 6
    const SRC: [u8; 6] = [1, 2, 3, 4, 5, 6];

    #[test]
    fn rotate_90() {
        // 時計回り 90°:
        // 4 1
        // 5 2
        // 6 3
        assert_eq!(
            render_y(
                &SRC,
                3,
                2,
                Transform {
                    rotate: 90,
                    mirror: false
                }
            ),
            vec![4, 1, 5, 2, 6, 3]
        );
    }

    #[test]
    fn rotate_180() {
        assert_eq!(
            render_y(
                &SRC,
                3,
                2,
                Transform {
                    rotate: 180,
                    mirror: false
                }
            ),
            vec![6, 5, 4, 3, 2, 1]
        );
    }

    #[test]
    fn rotate_270() {
        // 3 6
        // 2 5
        // 1 4
        assert_eq!(
            render_y(
                &SRC,
                3,
                2,
                Transform {
                    rotate: 270,
                    mirror: false
                }
            ),
            vec![3, 6, 2, 5, 1, 4]
        );
    }

    #[test]
    fn mirror() {
        assert_eq!(
            render_y(
                &SRC,
                3,
                2,
                Transform {
                    rotate: 0,
                    mirror: true
                }
            ),
            vec![3, 2, 1, 6, 5, 4]
        );
    }

    #[test]
    fn letterbox() {
        // 2x1 を 2x2 に収める → 1 行ぶん余るので下側に黒帯
        let src = [10u8, 20];
        let u = [128u8];
        let v = [128u8];
        let mut dst = I420::new(2, 2);
        let p = PlanesRef {
            width: 2,
            height: 1,
            y: &src,
            u: &u,
            v: &v,
            y_stride: 2,
            uv_stride: 1,
        };
        Renderer::new(Transform {
            rotate: 0,
            mirror: false,
        })
        .render(&p, &mut dst);
        assert_eq!(&dst.data[..4], &[10, 20, 16, 16]);
    }
}
