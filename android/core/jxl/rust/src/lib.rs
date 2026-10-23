//! JPEG XL decoding for Cucuto (`:core:jxl`), on top of jxl-rs (ADR 0006).
//!
//! The surface is deliberately small so the JNI layer in [`android`] stays thin: read a file's header (size,
//! animation, frame count, color space) without decoding pixels, open a decoder, pull visible frames one at a time
//! into a caller-owned premultiplied RGBA_8888 buffer, rewind for looping animations, drop.
//!
//! Safety rules: images are rejected from their header, before any pixel buffer exists, when a side exceeds the
//! caller's limit (never more than [`HARD_MAX_SIDE`]); the decoder also gets a sample limit. Every call that touches
//! jxl-rs runs under [`guard`], which turns a panic into [`Error::Panic`] and poisons the decoder, so a hostile file
//! can only ever produce an error.

use std::fmt;
use std::panic::{AssertUnwindSafe, catch_unwind};

use jxl::api::{
    AlphaMode, Event, ExtraChannel, JxlColorEncoding, JxlColorProfile, JxlDecoder, JxlDecoderOptions,
    JxlOutputBuffer, JxlPixelFormat, JxlPrimaries, JxlTransferFunction, JxlWhitePoint,
};

#[cfg(target_os = "android")]
pub mod android;

/// Absolute ceiling on either side, whatever the caller asks for. Packs allow 512 px (pack-spec
/// `Limits.MAX_BITMAP_SIDE_PX`); the Kotlin side passes that.
pub const HARD_MAX_SIDE: usize = 2048;

#[derive(Debug)]
pub enum Error {
    Jxl(jxl::error::Error),
    Truncated,
    TooLarge { width: usize, height: usize, max: usize },
    BadOutputBuffer,
    Poisoned,
    Panic(String),
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Jxl(e) => write!(f, "JPEG XL decode error: {e}"),
            Error::Truncated => write!(f, "truncated JPEG XL file"),
            Error::TooLarge { width, height, max } => {
                write!(f, "image is {width}x{height} px, over the {max} px limit")
            }
            Error::BadOutputBuffer => write!(f, "output buffer does not match the image size"),
            Error::Poisoned => write!(f, "decoder unusable after an earlier internal error"),
            Error::Panic(m) => write!(f, "internal decoder error: {m}"),
        }
    }
}

impl std::error::Error for Error {}

impl From<jxl::error::Error> for Error {
    fn from(e: jxl::error::Error) -> Self {
        Error::Jxl(e)
    }
}

pub type Result<T> = std::result::Result<T, Error>;

/// Runs `f`, turning a panic into [`Error::Panic`]. Needs `panic = "unwind"` (Cargo.toml).
pub fn guard<T>(f: impl FnOnce() -> Result<T>) -> Result<T> {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or_else(|payload| {
        let msg = payload
            .downcast_ref::<&str>()
            .map(|s| s.to_string())
            .or_else(|| payload.downcast_ref::<String>().cloned())
            .unwrap_or_else(|| "panic".to_string());
        Err(Error::Panic(msg))
    })
}

/// The color space of the decoded pixels, as far as an Android `ColorSpace.Named` can describe it. The numbers are
/// a contract with `JxlColorSpace` in Kotlin.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(i32)]
pub enum ColorSpace {
    /// An ICC profile or an encoding Android has no named space for: the pixels are the embedded profile's values.
    Unknown = 0,
    Srgb = 1,
    LinearSrgb = 2,
    DisplayP3 = 3,
    /// BT.2020 primaries with the BT.709 transfer function (Android's `BT2020`).
    Bt2020 = 4,
}

impl ColorSpace {
    fn of(profile: Option<&JxlColorProfile>) -> Self {
        use JxlTransferFunction as Tf;
        match profile {
            Some(JxlColorProfile::Simple(JxlColorEncoding::RgbColorSpace {
                white_point: JxlWhitePoint::D65,
                primaries,
                transfer_function,
                ..
            })) => match (primaries, transfer_function) {
                (JxlPrimaries::SRGB, Tf::SRGB) => ColorSpace::Srgb,
                (JxlPrimaries::SRGB, Tf::Linear) => ColorSpace::LinearSrgb,
                (JxlPrimaries::P3, Tf::SRGB) => ColorSpace::DisplayP3,
                (JxlPrimaries::BT2100, Tf::BT709) => ColorSpace::Bt2020,
                _ => ColorSpace::Unknown,
            },
            // Gray is expanded to RGB, so gray with the sRGB curve is sRGB.
            Some(JxlColorProfile::Simple(JxlColorEncoding::GrayscaleColorSpace {
                white_point: JxlWhitePoint::D65,
                transfer_function,
                ..
            })) => match transfer_function {
                Tf::SRGB => ColorSpace::Srgb,
                Tf::Linear => ColorSpace::LinearSrgb,
                _ => ColorSpace::Unknown,
            },
            _ => ColorSpace::Unknown,
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub struct Info {
    pub width: usize,
    pub height: usize,
    pub has_alpha: bool,
    pub animated: bool,
    /// 0 means loop forever (JPEG XL semantics).
    pub num_loops: u32,
    pub color_space: ColorSpace,
}

#[derive(Clone, Copy, Debug)]
pub struct Frame {
    pub duration_ms: f64,
    pub is_last: bool,
}

fn options(max_side: usize, scan_only: bool) -> JxlDecoderOptions {
    let mut o = JxlDecoderOptions::default();
    // Android ARGB_8888 bitmaps are premultiplied; ask the decoder to produce that directly.
    o.alpha_mode = AlphaMode::Premultiplied;
    // Hard cap on samples per frame (pixels x channels) on top of the side check on the header.
    o.sample_limit = Some(max_side * max_side * 5);
    o.scan_frames_only = scan_only;
    o
}

fn effective_max(max_side: usize) -> usize {
    max_side.clamp(1, HARD_MAX_SIDE)
}

/// After [`Event::BasicInfo`]: reads the header, selects 8-bit RGBA output (which also fixes the output color
/// profile: sRGB for XYB files unless the embedded one is simple, the embedded profile otherwise).
fn header(dec: &mut JxlDecoder) -> Result<Info> {
    let bi = dec.basic_info().ok_or(Error::Truncated)?.clone();
    dec.set_pixel_format(JxlPixelFormat::rgba8(bi.extra_channels.len()))?;
    let (width, height) = bi.size;
    Ok(Info {
        width,
        height,
        has_alpha: bi.extra_channels.iter().any(|c| c.ec_type == ExtraChannel::Alpha),
        animated: bi.animation.is_some(),
        num_loops: bi.animation.as_ref().map_or(0, |a| a.num_loops),
        color_space: ColorSpace::of(dec.output_color_profile()),
    })
}

fn check_size(info: &Info, max: usize) -> Result<()> {
    if info.width == 0 || info.height == 0 || info.width > max || info.height > max {
        return Err(Error::TooLarge { width: info.width, height: info.height, max });
    }
    Ok(())
}

/// Reads the header and, when the image fits in `max_side`, counts the visible frames (frame headers and TOCs only,
/// no pixels). An oversized image is not an error here: its size is returned with `None` frames, so callers can
/// report the size.
pub fn read_info(data: &[u8], max_side: usize) -> Result<(Info, Option<usize>)> {
    guard(|| {
        let max = effective_max(max_side);
        // The header alone, without a sample limit (jxl-rs would reject an oversized image before telling its size).
        // Nothing is allocated in proportion to the image size before the first frame header.
        let mut o = options(max, true);
        o.sample_limit = None;
        let mut dec = JxlDecoder::new(o);
        let mut input = data;
        let info = loop {
            match dec.process(&mut input, None, None)? {
                Event::BasicInfo => break header(&mut dec)?,
                Event::NeedMoreInput { .. } => return Err(Error::Truncated),
                _ => {}
            }
        };
        if check_size(&info, max).is_err() {
            return Ok((info, None));
        }
        // It fits: count the frames (headers and TOCs only), with the sample limit back on.
        let mut dec = JxlDecoder::new(options(max, true));
        let mut input = data;
        loop {
            match dec.process(&mut input, None, None)? {
                Event::Complete => break,
                Event::NeedMoreInput { .. } => return Err(Error::Truncated),
                Event::BasicInfo | Event::FrameHeader | Event::FrameComplete { .. } => {}
            }
        }
        Ok((info, Some(dec.scanned_frames().len())))
    })
}

/// A JPEG XL decoder over an in-memory file, producing one visible (coalesced) frame per call.
pub struct Decoder {
    data: Vec<u8>,
    pos: usize,
    dec: JxlDecoder,
    info: Info,
    max: usize,
    finished: bool,
    poisoned: bool,
}

impl Decoder {
    /// Parses the header; fails with [`Error::TooLarge`] before allocating anything when a side exceeds `max_side`.
    pub fn new(data: Vec<u8>, max_side: usize) -> Result<Self> {
        let max = effective_max(max_side);
        let mut d = Decoder {
            data,
            pos: 0,
            dec: JxlDecoder::new(options(max, false)),
            info: Info {
                width: 0,
                height: 0,
                has_alpha: false,
                animated: false,
                num_loops: 0,
                color_space: ColorSpace::Unknown,
            },
            max,
            finished: false,
            poisoned: false,
        };
        d.guarded(Self::read_header)?;
        Ok(d)
    }

    /// Runs `f` under [`guard`]; a panic poisons the decoder (its state is unknown from then on).
    fn guarded<T>(&mut self, f: impl FnOnce(&mut Self) -> Result<T>) -> Result<T> {
        if self.poisoned {
            return Err(Error::Poisoned);
        }
        let result = guard(|| f(self));
        if matches!(result, Err(Error::Panic(_))) {
            self.poisoned = true;
        }
        result
    }

    fn read_header(&mut self) -> Result<()> {
        loop {
            match self.step(None)? {
                Event::BasicInfo => break,
                Event::NeedMoreInput { .. } => return Err(Error::Truncated),
                _ => {}
            }
        }
        let info = header(&mut self.dec)?;
        check_size(&info, self.max)?;
        self.info = info;
        Ok(())
    }

    fn step(&mut self, buffers: Option<&mut [JxlOutputBuffer]>) -> Result<Event> {
        let mut input: &[u8] = &self.data[self.pos..];
        let event = self.dec.process(&mut input, buffers, None);
        self.pos = self.data.len() - input.len();
        Ok(event?)
    }

    pub fn info(&self) -> Info {
        self.info
    }

    /// Decodes the next visible frame into `out` (RGBA_8888, premultiplied, `stride` bytes per row). Returns `None`
    /// once every frame has been produced.
    pub fn next_frame(&mut self, out: &mut [u8], stride: usize) -> Result<Option<Frame>> {
        self.guarded(|d| d.next_frame_inner(out, stride))
    }

    fn next_frame_inner(&mut self, out: &mut [u8], stride: usize) -> Result<Option<Frame>> {
        if self.finished {
            return Ok(None);
        }
        let (w, h) = (self.info.width, self.info.height);
        let row = w * 4;
        if stride < row || out.len() < stride * (h - 1) + row {
            return Err(Error::BadOutputBuffer);
        }
        let mut buf = JxlOutputBuffer::new_with_stride(out, h, row, stride);
        let mut duration_ms = 0.0;
        loop {
            match self.step(Some(std::slice::from_mut(&mut buf)))? {
                Event::FrameHeader => {
                    duration_ms = self.dec.frame_header().and_then(|f| f.duration).unwrap_or(0.0);
                }
                Event::FrameComplete { has_more_frames } => {
                    self.finished = !has_more_frames;
                    return Ok(Some(Frame { duration_ms, is_last: !has_more_frames }));
                }
                Event::Complete => {
                    self.finished = true;
                    return Ok(None);
                }
                Event::NeedMoreInput { .. } => return Err(Error::Truncated),
                Event::BasicInfo => {}
            }
        }
    }

    /// Back to frame 0 (looping animations). Internal buffers are kept.
    pub fn rewind(&mut self) -> Result<()> {
        self.guarded(|d| {
            d.dec.reset();
            d.pos = 0;
            d.finished = false;
            d.read_header()
        })
    }
}
