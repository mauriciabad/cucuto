//! Host benchmark and robustness check for the spike crate.
//!
//!   cargo run --release --bin jxl-bench -- ../build/test-images/{sprite,photo,anim}.jxl
//!
//! For each file: median/min decode time of the first frame (1 and 4 threads), frame scan and
//! full-animation decode for animated files, then a mutation pass (truncations and random byte
//! corruptions) that counts clean errors versus panics. A panic would be caught by the JNI layer
//! (`catch_unwind`) and become a Java exception, but it still counts as a decoder bug here.

use std::panic;
use std::time::{Duration, Instant};

use cucuto_jxl::{Decoder, decode_rgba, scan_frames};

fn median(mut v: Vec<Duration>) -> Duration {
    v.sort();
    v[v.len() / 2]
}

fn ms(d: Duration) -> f64 {
    d.as_secs_f64() * 1000.0
}

fn time<F: FnMut()>(reps: usize, mut f: F) -> (Duration, Duration) {
    for _ in 0..5 {
        f();
    }
    let v: Vec<Duration> = (0..reps)
        .map(|_| {
            let t = Instant::now();
            f();
            t.elapsed()
        })
        .collect();
    let min = *v.iter().min().unwrap();
    (median(v), min)
}

/// xorshift64*, enough for reproducible corruptions without a rand dependency.
struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 >> 12;
        self.0 ^= self.0 << 25;
        self.0 ^= self.0 >> 27;
        self.0.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }
    fn below(&mut self, n: usize) -> usize {
        (self.next() % n as u64) as usize
    }
}

fn decode_all_frames(data: &[u8]) -> Result<usize, cucuto_jxl::Error> {
    let mut d = Decoder::new(data.to_vec(), 1)?;
    let info = d.info();
    let mut out = vec![0u8; info.width * info.height * 4];
    let mut n = 0;
    while d.next_frame(&mut out, info.width * 4)?.is_some() {
        n += 1;
    }
    Ok(n)
}

fn main() {
    let files: Vec<String> = std::env::args().skip(1).collect();
    if files.is_empty() {
        eprintln!("usage: jxl-bench <file.jxl>...");
        std::process::exit(2);
    }
    panic::set_hook(Box::new(|_| {})); // panics are counted, not printed

    for path in files {
        let data = std::fs::read(&path).expect("read input");
        let (info, rgba) = decode_rgba(&data, 1).expect("decode");
        let opaque = rgba.chunks_exact(4).filter(|p| p[3] == 255).count();
        println!(
            "\n{path}: {} bytes, {}x{}, alpha={}, animated={}, opaque px={}",
            data.len(),
            info.width,
            info.height,
            info.has_alpha,
            info.animated,
            opaque
        );

        for threads in [1usize, 4] {
            let (med, min) = time(200, || {
                decode_rgba(&data, threads).unwrap();
            });
            let mpx = (info.width * info.height) as f64 / 1e6 / med.as_secs_f64();
            println!(
                "  first frame, {threads} thread(s): median {:.3} ms, min {:.3} ms ({mpx:.1} MP/s)",
                ms(med),
                ms(min)
            );
        }

        if info.animated {
            let durations = scan_frames(&data).unwrap();
            let (scan, _) = time(200, || {
                scan_frames(&data).unwrap();
            });
            let n = decode_all_frames(&data).unwrap();
            let (all, _) = time(50, || {
                decode_all_frames(&data).unwrap();
            });
            println!(
                "  frames: scan says {} (durations ms {:?}), decoded {n}; scan {:.3} ms; all frames {:.3} ms ({:.3} ms/frame)",
                durations.len(),
                durations,
                ms(scan),
                ms(all),
                ms(all) / n as f64
            );
            // Rewind + seek-by-decoding to the last frame (what a looping drawable does).
            let mut d = Decoder::new(data.clone(), 1).unwrap();
            let mut out = vec![0u8; info.width * info.height * 4];
            while d.next_frame(&mut out, info.width * 4).unwrap().is_some() {}
            let t = Instant::now();
            d.rewind().unwrap();
            d.next_frame(&mut out, info.width * 4).unwrap().unwrap();
            println!("  rewind + frame 0: {:.3} ms", ms(t.elapsed()));
        }

        // Robustness: every 7th truncation point plus 3000 random corruptions.
        let (mut ok, mut err, mut panics) = (0, 0, 0);
        let mut rng = Rng(0x9E37_79B9_7F4A_7C15);
        let mut cases: Vec<Vec<u8>> = (0..data.len()).step_by(7).map(|n| data[..n].to_vec()).collect();
        for _ in 0..3000 {
            let mut m = data.clone();
            for _ in 0..1 + rng.below(8) {
                let i = rng.below(m.len());
                m[i] = rng.next() as u8;
            }
            cases.push(m);
        }
        let t = Instant::now();
        let total = cases.len();
        for case in cases {
            match panic::catch_unwind(|| decode_all_frames(&case)) {
                Ok(Ok(_)) => ok += 1,
                Ok(Err(_)) => err += 1,
                Err(_) => panics += 1,
            }
        }
        println!(
            "  mutations: {total} cases in {:.1} s -> decoded {ok}, clean error {err}, panic {panics}",
            t.elapsed().as_secs_f64()
        );
    }
}
