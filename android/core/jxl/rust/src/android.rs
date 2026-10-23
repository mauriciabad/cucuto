//! JNI entry points for `app.mauri.cucuto.core.jxl.JxlJni` (see `JxlJni.kt`).
//!
//! Two layers keep bad input from ever crashing the app: the decoding itself runs under [`crate::guard`], and every
//! entry point runs inside `EnvUnowned::with_env`, which catches any remaining panic. Errors and panics surface as
//! `java.lang.RuntimeException`, which the Kotlin side wraps in `JxlException`. Pixels are written straight into
//! the `android.graphics.Bitmap` through `AndroidBitmap_lockPixels` (libjnigraphics, NDK stable API), with no
//! intermediate copy.

use std::ffi::c_void;
use std::fmt;

use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JByteArray, JIntArray, JObject};
use jni::sys::{jint, jlong, jobject};

use crate::{Decoder, read_info};

#[repr(C)]
#[derive(Default)]
struct AndroidBitmapInfo {
    width: u32,
    height: u32,
    stride: u32,
    format: i32,
    flags: u32,
}

const ANDROID_BITMAP_FORMAT_RGBA_8888: i32 = 1;

#[link(name = "jnigraphics")]
unsafe extern "C" {
    fn AndroidBitmap_getInfo(env: *mut jni::sys::JNIEnv, bitmap: jobject, info: *mut AndroidBitmapInfo) -> i32;
    fn AndroidBitmap_lockPixels(env: *mut jni::sys::JNIEnv, bitmap: jobject, addr: *mut *mut c_void) -> i32;
    fn AndroidBitmap_unlockPixels(env: *mut jni::sys::JNIEnv, bitmap: jobject) -> i32;
}

#[derive(Debug)]
enum JniError {
    Jni(jni::errors::Error),
    Decode(crate::Error),
    Usage(&'static str),
}

impl fmt::Display for JniError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            JniError::Jni(e) => write!(f, "{e}"),
            JniError::Decode(e) => write!(f, "{e}"),
            JniError::Usage(m) => write!(f, "{m}"),
        }
    }
}

impl std::error::Error for JniError {}

impl From<jni::errors::Error> for JniError {
    fn from(e: jni::errors::Error) -> Self {
        JniError::Jni(e)
    }
}

impl From<crate::Error> for JniError {
    fn from(e: crate::Error) -> Self {
        JniError::Decode(e)
    }
}

/// # Safety
/// `handle` must come from `nativeOpen` and not have been passed to `nativeClose`. The Kotlin wrapper guarantees
/// this (one lock per handle, zeroed on close).
unsafe fn decoder<'a>(handle: jlong) -> Result<&'a mut Decoder, JniError> {
    if handle == 0 {
        return Err(JniError::Usage("decoder is closed"));
    }
    Ok(unsafe { &mut *(handle as *mut Decoder) })
}

fn max_side(value: jint) -> usize {
    value.max(1) as usize
}

/// `nativeReadInfo(data: ByteArray, maxSide: Int): IntArray` =
/// `[width, height, hasAlpha, animated, numLoops, colorSpace, frameCount]`; `frameCount` is -1 when the image is
/// larger than `maxSide` (frames are not scanned then).
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_mauri_cucuto_core_jxl_JxlJni_nativeReadInfo<'l>(
    mut env: EnvUnowned<'l>,
    _class: JObject<'l>,
    data: JByteArray<'l>,
    max: jint,
) -> JIntArray<'l> {
    env.with_env(|env| -> Result<JIntArray<'l>, JniError> {
        let bytes = env.convert_byte_array(&data)?;
        let (i, frames) = read_info(&bytes, max_side(max))?;
        let values = [
            i.width.min(i32::MAX as usize) as jint,
            i.height.min(i32::MAX as usize) as jint,
            i.has_alpha as jint,
            i.animated as jint,
            i.num_loops.min(i32::MAX as u32) as jint,
            i.color_space as jint,
            frames.map_or(-1, |n| n.min(i32::MAX as usize) as jint),
        ];
        let arr = env.new_int_array(values.len())?;
        arr.set_region(env, 0, &values)?;
        Ok(arr)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// `nativeOpen(data: ByteArray, maxSide: Int): Long`
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_mauri_cucuto_core_jxl_JxlJni_nativeOpen<'l>(
    mut env: EnvUnowned<'l>,
    _class: JObject<'l>,
    data: JByteArray<'l>,
    max: jint,
) -> jlong {
    env.with_env(|env| -> Result<jlong, JniError> {
        let bytes = env.convert_byte_array(&data)?;
        let d = Decoder::new(bytes, max_side(max))?;
        Ok(Box::into_raw(Box::new(d)) as jlong)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Unlocks the bitmap's pixels when dropped, also while unwinding.
struct LockedPixels {
    env: *mut jni::sys::JNIEnv,
    bitmap: jobject,
}

impl Drop for LockedPixels {
    fn drop(&mut self) {
        unsafe { AndroidBitmap_unlockPixels(self.env, self.bitmap) };
    }
}

/// `nativeNextFrame(handle: Long, bitmap: Bitmap): Int` = frame duration in ms, or -1 when there are no more
/// frames. The bitmap must be mutable ARGB_8888 of the image size.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_mauri_cucuto_core_jxl_JxlJni_nativeNextFrame<'l>(
    mut env: EnvUnowned<'l>,
    _class: JObject<'l>,
    handle: jlong,
    bitmap: JObject<'l>,
) -> jint {
    env.with_env(|env| -> Result<jint, JniError> {
        let d = unsafe { decoder(handle)? };
        if bitmap.is_null() {
            return Err(JniError::Usage("bitmap is null"));
        }
        let raw_env = env.get_raw();
        let bmp = bitmap.as_raw();
        let mut info = AndroidBitmapInfo::default();
        if unsafe { AndroidBitmap_getInfo(raw_env, bmp, &mut info) } != 0 {
            return Err(JniError::Usage("AndroidBitmap_getInfo failed"));
        }
        let img = d.info();
        if info.format != ANDROID_BITMAP_FORMAT_RGBA_8888
            || info.width as usize != img.width
            || info.height as usize != img.height
        {
            return Err(JniError::Usage("bitmap must be ARGB_8888 and match the image size"));
        }
        let mut pixels: *mut c_void = std::ptr::null_mut();
        if unsafe { AndroidBitmap_lockPixels(raw_env, bmp, &mut pixels) } != 0 || pixels.is_null() {
            return Err(JniError::Usage("AndroidBitmap_lockPixels failed"));
        }
        let _lock = LockedPixels { env: raw_env, bitmap: bmp };
        let stride = info.stride as usize;
        let len = stride * (img.height - 1) + img.width * 4;
        // SAFETY: lockPixels pins at least `len` writable bytes until `_lock` is dropped.
        let out = unsafe { std::slice::from_raw_parts_mut(pixels as *mut u8, len) };
        Ok(match d.next_frame(out, stride)? {
            Some(f) => f.duration_ms.round().clamp(0.0, i32::MAX as f64) as jint,
            None => -1,
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// `nativeRewind(handle: Long)`
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_mauri_cucuto_core_jxl_JxlJni_nativeRewind<'l>(
    mut env: EnvUnowned<'l>,
    _class: JObject<'l>,
    handle: jlong,
) {
    env.with_env(|_| -> Result<(), JniError> { Ok(unsafe { decoder(handle)? }.rewind()?) })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// `nativeClose(handle: Long)`
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_mauri_cucuto_core_jxl_JxlJni_nativeClose<'l>(
    mut env: EnvUnowned<'l>,
    _class: JObject<'l>,
    handle: jlong,
) {
    env.with_env(|_| -> Result<(), JniError> {
        if handle != 0 {
            drop(unsafe { Box::from_raw(handle as *mut Decoder) });
        }
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}
