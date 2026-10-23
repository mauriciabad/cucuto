//! Spike for issue #7: decode JPEG XL sprites and covers with jxl-rs.
//!
//! The public surface is deliberately tiny so the JNI layer stays thin:
//! open a file, read its basic info, pull frames one at a time into a caller-owned
//! RGBA_8888 (premultiplied) buffer, rewind for looping animations, drop.
//! The same code builds for the host (benchmarks, fuzz-ish robustness checks) and for
//! `aarch64-linux-android` (JNI entry points in [`android`]).

use std::fmt;
use std::sync::Mutex;
use std::sync::atomic::{AtomicUsize, Ordering};

use jxl::api::{
    AlphaMode, Event, ExtraChannel, JxlDecoder, JxlDecoderOptions, JxlOutputBuffer,
    JxlParallelRunner, JxlParallelRunnerFun, JxlPixelFormat,
};

#[cfg(target_os = "android")]
pub mod android;

/// Packs allow at most 512 px per side; anything far beyond that is rejected before any pixel
/// buffer is allocated so a hostile pack cannot make the app allocate gigabytes.
pub const MAX_SIDE: usize = 2048;

#[derive(Debug)]
pub enum Error {
    Jxl(jxl::error::Error),
    Truncated,
    TooLarge { width: usize, height: usize },
    BadOutputBuffer,
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Jxl(e) => write!(f, "JPEG XL decode error: {e}"),
            Error::Truncated => write!(f, "truncated JPEG XL file"),
            Error::TooLarge { width, height } => {
                write!(f, "image is {width}x{height}, over the {MAX_SIDE} px limit")
            }
            Error::BadOutputBuffer => write!(f, "output buffer does not match the image size"),
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

#[derive(Clone, Copy, Debug)]
pub struct Info {
    pub width: usize,
    pub height: usize,
    pub has_alpha: bool,
    pub animated: bool,
    /// 0 means loop forever (JPEG XL semantics).
    pub num_loops: u32,
}

#[derive(Clone, Copy, Debug)]
pub struct Frame {
    pub duration_ms: f64,
    pub is_last: bool,
}

/// A minimal parallel runner on scoped std threads (no rayon, keeps the .so small).
pub struct ScopedThreads(pub usize);

impl JxlParallelRunner for ScopedThreads {
    fn run(&mut self, num: usize, fun: &JxlParallelRunnerFun<'_>) -> jxl::error::Result<()> {
        let workers = self.0.min(num);
        if workers <= 1 {
            return (0..num).try_for_each(fun);
        }
        let next = AtomicUsize::new(0);
        let first_error = Mutex::new(None);
        std::thread::scope(|s| {
            for _ in 0..workers {
                s.spawn(|| {
                    loop {
                        let i = next.fetch_add(1, Ordering::Relaxed);
                        if i >= num {
                            return;
                        }
                        if let Err(e) = fun(i) {
                            first_error.lock().unwrap().get_or_insert(e);
                            next.store(num, Ordering::Relaxed);
                            return;
                        }
                    }
                });
            }
        });
        match first_error.into_inner().unwrap() {
            Some(e) => Err(e),
            None => Ok(()),
        }
    }

    fn num_threads(&self) -> usize {
        self.0
    }
}

pub struct Decoder {
    data: Vec<u8>,
    pos: usize,
    dec: JxlDecoder,
    info: Info,
    finished: bool,
    runner: Option<ScopedThreads>,
}

fn options() -> JxlDecoderOptions {
    let mut o = JxlDecoderOptions::default();
    // Android ARGB_8888 bitmaps are premultiplied; ask the decoder to produce that directly.
    o.alpha_mode = AlphaMode::Premultiplied;
    // Hard cap on samples per frame (pixels x channels) on top of the side check below.
    o.sample_limit = Some(MAX_SIDE * MAX_SIDE * 5);
    o
}

impl Decoder {
    /// Parses headers up to the basic info. `threads <= 1` decodes on the calling thread.
    pub fn new(data: Vec<u8>, threads: usize) -> Result<Self> {
        let mut d = Decoder {
            data,
            pos: 0,
            dec: JxlDecoder::new(options()),
            info: Info { width: 0, height: 0, has_alpha: false, animated: false, num_loops: 0 },
            finished: false,
            runner: (threads > 1).then_some(ScopedThreads(threads)),
        };
        d.read_header()?;
        Ok(d)
    }

    fn read_header(&mut self) -> Result<()> {
        loop {
            match self.step(None)? {
                Event::BasicInfo => break,
                Event::NeedMoreInput { .. } => return Err(Error::Truncated),
                _ => {}
            }
        }
        let bi = self.dec.basic_info().expect("basic info after BasicInfo event").clone();
        let (width, height) = bi.size;
        if width == 0 || height == 0 || width > MAX_SIDE || height > MAX_SIDE {
            return Err(Error::TooLarge { width, height });
        }
        self.info = Info {
            width,
            height,
            has_alpha: bi.extra_channels.iter().any(|c| c.ec_type == ExtraChannel::Alpha),
            animated: bi.animation.is_some(),
            num_loops: bi.animation.as_ref().map_or(0, |a| a.num_loops),
        };
        // 8-bit RGBA, extra channels (alpha) interleaved; output profile defaults to sRGB for
        // XYB (lossy) files and to the embedded profile otherwise.
        self.dec.set_pixel_format(JxlPixelFormat::rgba8(bi.extra_channels.len()))?;
        Ok(())
    }

    fn step(&mut self, buffers: Option<&mut [JxlOutputBuffer]>) -> Result<Event> {
        let mut input: &[u8] = &self.data[self.pos..];
        let runner = self.runner.as_mut().map(|r| r as &mut dyn JxlParallelRunner);
        let event = self.dec.process(&mut input, buffers, runner);
        self.pos = self.data.len() - input.len();
        Ok(event?)
    }

    pub fn info(&self) -> Info {
        self.info
    }

    /// Decodes the next visible frame into `out` (RGBA_8888, premultiplied, `stride` bytes per
    /// row). Returns `None` once every frame has been produced.
    pub fn next_frame(&mut self, out: &mut [u8], stride: usize) -> Result<Option<Frame>> {
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

    /// Back to frame 0 (for looping animations). Internal buffers are kept.
    pub fn rewind(&mut self) -> Result<()> {
        self.dec.reset();
        self.pos = 0;
        self.finished = false;
        self.read_header()
    }
}

/// Counts visible frames and their durations without producing pixels.
pub fn scan_frames(data: &[u8]) -> Result<Vec<f64>> {
    let mut o = options();
    o.scan_frames_only = true;
    let mut dec = JxlDecoder::new(o);
    let mut input = data;
    loop {
        match dec.process(&mut input, None, None)? {
            Event::Complete => break,
            Event::NeedMoreInput { .. } => return Err(Error::Truncated),
            _ => {}
        }
    }
    Ok(dec.scanned_frames().iter().map(|f| f.duration_ms).collect())
}

/// Convenience: first frame as a tightly packed RGBA_8888 buffer.
pub fn decode_rgba(data: &[u8], threads: usize) -> Result<(Info, Vec<u8>)> {
    let mut d = Decoder::new(data.to_vec(), threads)?;
    let info = d.info();
    let mut out = vec![0u8; info.width * info.height * 4];
    d.next_frame(&mut out, info.width * 4)?.ok_or(Error::Truncated)?;
    Ok((info, out))
}
