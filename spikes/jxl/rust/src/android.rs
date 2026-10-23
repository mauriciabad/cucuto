//! JNI entry points for `app.mauri.cucuto.jxl.JxlNative` (see the Kotlin wrapper).
//!
//! Every entry point runs inside `EnvUnowned::with_env`, which wraps the body in
//! `catch_unwind`; errors and panics surface as `java.lang.RuntimeException` instead of
//! aborting the app. Pixels are written straight into the `android.graphics.Bitmap` through
//! `AndroidBitmap_lockPixels` (libjnigraphics, part of the NDK stable API), so there is no
//! intermediate copy.

use std::ffi::c_void;
use std::fmt;

use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JByteArray, JIntArray, JObject};
use jni::sys::{jint, jlong, jobject};

use crate::{Decoder, scan_frames};

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
    fn AndroidBitmap_getInfo(
        env: *mut jni::sys::JNIEnv,
        bitmap: jobject,
        info: *mut AndroidBitmapInfo,
    ) -> i32;
    fn AndroidBitmap_lockPixels(
        env: *mut jni::sys::JNIEnv,
        bitmap: jobject,
        addr: *mut *mut c_void,
    ) -> i32;
    fn AndroidBitmap_unlockPixels(env: *mut jni::sys::JNIEnv, bitmap: jobject) -> i32;
}

#[derive(Debug)]
enum JniError {
    Jni(jni::errors::Error),
    Decode(crate::Error),
    Bitmap(&'static str),
}

impl fmt::Display for JniError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            JniError::Jni(e) => write!(f, "{e}"),
            JniError::Decode(e) => write!(f, "{e}"),
            JniError::Bitmap(m) => write!(f, "{m}"),
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
/// `handle` must come from `nativeOpen` and not have been passed to `nativeClose`.
unsafe fn decoder<'a>(handle: jlong) -> Result<&'a mut Decoder, JniError> {
    if handle == 0 {
        return Err(JniError::Bitmap("decoder is closed"));
    }
    Ok(unsafe { &mut *(handle as *mut Decoder) })
}

/// `nativeOpen(data: ByteArray, threads: Int): Long`
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_mauri_cucuto_jxl_JxlNative_nativeOpen<'l>(
    mut env: EnvUnowned<'l>,
    _class: JObject<'l>,
    data: JByteArray<'l>,
    threads: jint,
) -> jlong {
    env.with_env(|env| -> Result<jlong, JniError> {
        let bytes = env.convert_byte_array(&data)?;
        let d = Decoder::new(bytes, threads.max(1) as usize)?;
        Ok(Box::into_raw(Box::new(d)) as jlong)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// `nativeInfo(handle: Long): IntArray` = [width, height, hasAlpha, animated, numLoops]
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_mauri_cucuto_jxl_JxlNative_nativeInfo<'l>(
    mut env: EnvUnowned<'l>,
    _class: JObject<'l>,
    handle: jlong,
) -> JIntArray<'l> {
    env.with_env(|env| -> Result<JIntArray<'l>, JniError> {
        let i = unsafe { decoder(handle)? }.info();
        let values = [
            i.width as jint,
            i.height as jint,
            i.has_alpha as jint,
            i.animated as jint,
            i.num_loops as jint,
        ];
        let arr = env.new_int_array(values.len())?;
        arr.set_region(env, 0, &values)?;
        Ok(arr)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// `nativeNextFrame(handle: Long, bitmap: Bitmap): Int` = frame duration in ms, or -1 when
/// there are no more frames. The bitmap must be mutable ARGB_8888 of the image size.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_mauri_cucuto_jxl_JxlNative_nativeNextFrame<'l>(
    mut env: EnvUnowned<'l>,
    _class: JObject<'l>,
    handle: jlong,
    bitmap: JObject<'l>,
) -> jint {
    env.with_env(|env| -> Result<jint, JniError> {
        let d = unsafe { decoder(handle)? };
        let raw_env = env.get_raw();
        let bmp = bitmap.as_raw();
        let mut info = AndroidBitmapInfo::default();
        if unsafe { AndroidBitmap_getInfo(raw_env, bmp, &mut info) } != 0 {
            return Err(JniError::Bitmap("AndroidBitmap_getInfo failed"));
        }
        let img = d.info();
        if info.format != ANDROID_BITMAP_FORMAT_RGBA_8888
            || info.width as usize != img.width
            || info.height as usize != img.height
        {
            return Err(JniError::Bitmap("bitmap must be ARGB_8888 and match the image size"));
        }
        let mut pixels: *mut c_void = std::ptr::null_mut();
        if unsafe { AndroidBitmap_lockPixels(raw_env, bmp, &mut pixels) } != 0 || pixels.is_null() {
            return Err(JniError::Bitmap("AndroidBitmap_lockPixels failed"));
        }
        let stride = info.stride as usize;
        let len = stride * (img.height - 1) + img.width * 4;
        // SAFETY: lockPixels pins `len` writable bytes until unlockPixels.
        let out = unsafe { std::slice::from_raw_parts_mut(pixels as *mut u8, len) };
        let result = d.next_frame(out, stride);
        unsafe { AndroidBitmap_unlockPixels(raw_env, bmp) };
        Ok(match result? {
            Some(f) => f.duration_ms.round().clamp(0.0, i32::MAX as f64) as jint,
            None => -1,
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// `nativeRewind(handle: Long)`
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_mauri_cucuto_jxl_JxlNative_nativeRewind<'l>(
    mut env: EnvUnowned<'l>,
    _class: JObject<'l>,
    handle: jlong,
) {
    env.with_env(|_| -> Result<(), JniError> { Ok(unsafe { decoder(handle)? }.rewind()?) })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// `nativeClose(handle: Long)`
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_mauri_cucuto_jxl_JxlNative_nativeClose<'l>(
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

/// `nativeFrameCount(data: ByteArray): Int` (header/TOC scan only, no pixels)
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_mauri_cucuto_jxl_JxlNative_nativeFrameCount<'l>(
    mut env: EnvUnowned<'l>,
    _class: JObject<'l>,
    data: JByteArray<'l>,
) -> jint {
    env.with_env(|env| -> Result<jint, JniError> {
        let bytes = env.convert_byte_array(&data)?;
        Ok(scan_frames(&bytes)?.len() as jint)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}
