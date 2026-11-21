//! JNI entry points for `com.example.offlineime.NativeEngine`.
//! Every call catches panics and returns an empty value instead.

use crate::cancellation::QueryCancellation;
use crate::dict::Backing;
use crate::engine::{self, Engine, Request};
use crate::fxhash::FxHashMap;
use crate::syllable::{Syllables, STANDARD};
use jni::objects::{JClass, JMethodID, JObject, JObjectArray, JString};
use jni::signature::{Primitive, ReturnType};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jlongArray, jmethodID, jobjectArray, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::cell::RefCell;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, OnceLock, RwLock};

fn guard<T>(default: T, f: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(default)
}

fn is_canceled_method(env: &mut JNIEnv) -> Option<JMethodID> {
    static CACHED: OnceLock<usize> = OnceLock::new();
    let raw = if let Some(&id) = CACHED.get() {
        id
    } else {
        let method = env
            .get_method_id("android/os/CancellationSignal", "isCanceled", "()Z")
            .ok()?;
        let id = method.into_raw() as usize;
        let _ = CACHED.set(id);
        id
    };
    if raw == 0 {
        return None;
    }
    // SAFETY: `android/os/CancellationSignal` is a boot-class-loader class whose jmethodID stays valid for the JVM lifetime.
    Some(unsafe { JMethodID::from_raw(raw as jmethodID) })
}

fn with_cancellation<T>(
    env: &mut JNIEnv, signal: &JObject, run: impl FnOnce(&QueryCancellation<'_>) -> Option<T>,
) -> Option<T> {
    if signal.is_null() {
        return run(&QueryCancellation::new(None));
    }
    let method = is_canceled_method(env)?;
    let env = RefCell::new(env);
    let poll = || {
        // The JNI parameter is a CancellationSignal; isCanceled takes no arguments and returns boolean.
        unsafe {
            env.borrow_mut().call_method_unchecked(signal, method, ReturnType::Primitive(Primitive::Boolean), &[])
        }
        .and_then(|value| value.z())
        .unwrap_or(true)
    };
    run(&QueryCancellation::new(Some(&poll)))
}

fn text(env: &mut JNIEnv, value: &JString) -> String {
    if value.is_null() {
        return String::new();
    }
    env.get_string(value).map(|v| v.into()).unwrap_or_default()
}

fn strings(env: &mut JNIEnv, values: &[String]) -> jobjectArray {
    string_array(env, values.iter().map(|value| Some(value.as_str())))
}

fn string_array<'a>(
    env: &mut JNIEnv, values: impl ExactSizeIterator<Item = Option<&'a str>>,
) -> jobjectArray {
    let Ok(array) = env.new_object_array(values.len() as i32, "java/lang/String", JObject::null()) else {
        return std::ptr::null_mut();
    };
    for (i, value) in values.enumerate() {
        let Some(value) = value else { continue };
        if let Ok(s) = env.new_string(value) {
            let _ = env.set_object_array_element(&array, i as i32, &s);
            let _ = env.delete_local_ref(s);
        }
    }
    array.into_raw()
}

fn read_strings(env: &mut JNIEnv, array: &JObjectArray) -> Vec<String> {
    if array.is_null() {
        return Vec::new();
    }
    let len = env.get_array_length(array).unwrap_or(0);
    let mut out = Vec::with_capacity(len as usize);
    for i in 0..len {
        let Ok(item) = env.get_object_array_element(array, i) else { break };
        let s = JString::from(item);
        out.push(text(env, &s));
        let _ = env.delete_local_ref(s);
    }
    out
}

fn new_string(env: &mut JNIEnv, value: &str) -> jstring {
    env.new_string(value).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn write_candidate_text(rows: &mut Vec<u8>, value: &str) {
    rows.extend_from_slice(&(value.len() as i32).to_le_bytes());
    rows.extend_from_slice(value.as_bytes());
}

fn handle_table() -> &'static RwLock<FxHashMap<jlong, Arc<Engine>>> {
    static TABLE: OnceLock<RwLock<FxHashMap<jlong, Arc<Engine>>>> = OnceLock::new();
    TABLE.get_or_init(|| RwLock::new(FxHashMap::default()))
}

fn engine(handle: jlong) -> Option<Arc<Engine>> {
    if handle == 0 {
        return None;
    }
    handle_table()
        .read()
        .unwrap_or_else(|p| p.into_inner())
        .get(&handle)
        .cloned()
}

fn into_handle(result: Result<Engine, String>) -> jlong {
    static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);
    match result {
        Ok(e) => {
            let id = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
            handle_table()
                .write()
                .unwrap_or_else(|p| p.into_inner())
                .insert(id, Arc::new(e));
            id
        }
        Err(_) => 0,
    }
}

fn standard() -> &'static Syllables {
    static TABLE: OnceLock<Syllables> = OnceLock::new();
    TABLE.get_or_init(|| Syllables::new(STANDARD.iter().map(|s| s.to_string()).collect()))
}

#[cfg(unix)]
fn map_fd(fd: i32, offset: u64, len: usize) -> Result<memmap2::Mmap, String> {
    use std::os::fd::BorrowedFd;
    // SAFETY: the caller keeps the descriptor open for the duration of this call;
    // the mapping keeps its own reference afterwards.
    let owned = unsafe { BorrowedFd::borrow_raw(fd) }.try_clone_to_owned().map_err(|e| e.to_string())?;
    let file = std::fs::File::from(owned);
    // SAFETY: the bundled dictionary is read-only for the life of the process.
    unsafe { memmap2::MmapOptions::new().offset(offset).len(len).map(&file) }.map_err(|e| e.to_string())
}

#[cfg(not(unix))]
fn map_fd(_fd: i32, _offset: u64, _len: usize) -> Result<memmap2::Mmap, String> {
    Err("descriptor mapping is only available on Android".into())
}

#[no_mangle]
pub extern "system" fn Java_com_example_offlineime_NativeEngine_openFd(
    _env: JNIEnv, _class: JClass, fd: jint, offset: jlong, length: jlong,
) -> jlong {
    guard(0, || {
        if fd < 0 || offset < 0 || length <= 0 {
            return 0;
        }
        into_handle(map_fd(fd, offset as u64, length as usize).and_then(|m| Engine::new(Backing::Mapped(m))))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_offlineime_NativeEngine_openFile(
    mut env: JNIEnv, _class: JClass, path: JString,
) -> jlong {
    let path = text(&mut env, &path);
    guard(0, || {
        let mapped = std::fs::File::open(&path)
            .map_err(|e| e.to_string())
            // SAFETY: the extracted dictionary file is private and never rewritten in place.
            .and_then(|f| unsafe { memmap2::Mmap::map(&f) }.map_err(|e| e.to_string()));
        into_handle(mapped.and_then(|m| Engine::new(Backing::Mapped(m))))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_offlineime_NativeEngine_close(_env: JNIEnv, _class: JClass, handle: jlong) {
    guard((), || {
        if handle != 0 {
            handle_table()
                .write()
                .unwrap_or_else(|p| p.into_inner())
                .remove(&handle);
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_offlineime_NativeEngine_standardSyllables(
    mut env: JNIEnv, _class: JClass,
) -> jobjectArray {
    string_array(&mut env, STANDARD.iter().copied().map(Some))
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_example_offlineime_NativeEngine_query(
    mut env: JNIEnv, _class: JClass, handle: jlong, mode: jint, raw: JString, constraint: JString,
    prev2: JString, prev: JString, flags: jint, limit: jint, touches: JObjectArray, signal: JObject,
) -> jbyteArray {
    let raw = text(&mut env, &raw);
    let constraint = text(&mut env, &constraint);
    let prev2 = text(&mut env, &prev2);
    let prev = text(&mut env, &prev);
    let touch_corrections: Vec<(String, i32)> = read_strings(&mut env, &touches).iter().filter_map(|row| {
        let (penalty, code) = row.split_once(':')?;
        Some((code.to_string(), penalty.parse().ok()?))
    }).collect();
    let rows = guard(None, || with_cancellation(&mut env, &signal, |cancel| {
        let engine = engine(handle)?;
        let request = Request {
            mode,
            raw: &raw,
            parts: engine::parse_parts(&constraint),
            prev2: &prev2,
            prev: &prev,
            flags: flags as u32,
            limit: limit.max(1) as usize,
            touch_corrections: &touch_corrections,
        };
        let candidates = engine.query_cancellable(&request, cancel).ok()?;
        cancel.check().ok()?;
        let mut rows = Vec::new();
        for c in candidates {
            cancel.step().ok()?;
            rows.extend_from_slice(&(c.consumed as i32).to_le_bytes());
            rows.extend_from_slice(&c.tier.to_le_bytes());
            rows.extend_from_slice(&(100_000 - c.cost).to_le_bytes());
            write_candidate_text(&mut rows, &c.word);
            write_candidate_text(&mut rows, &c.reading);
            rows.extend_from_slice(&(c.segments.len() as i32).to_le_bytes());
            for segment in &c.segments {
                write_candidate_text(&mut rows, segment);
            }
        }
        cancel.check().ok()?;
        Some(rows)
    }));
    rows.and_then(|rows| env.byte_array_from_slice(&rows).ok())
        .map(|array| array.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_example_offlineime_NativeEngine_emojiSuggestions(
    mut env: JNIEnv, _class: JClass, handle: jlong, word: JString,
) -> jobjectArray {
    let word = text(&mut env, &word);
    let values = guard(Vec::new(), || {
        engine(handle).map_or_else(Vec::new, |e| e.emoji_suggestions(&word))
    });
    strings(&mut env, &values)
}

#[no_mangle]
pub extern "system" fn Java_com_example_offlineime_NativeEngine_paths(
    mut env: JNIEnv, _class: JClass, handle: jlong, raw: JString, numeric: jboolean, double_initials: jboolean, limit: jint,
) -> jobjectArray {
    let raw = text(&mut env, &raw);
    let rows = guard(Vec::new(), || {
        engine(handle).map_or_else(Vec::new, |e| {
            e.paths(&raw, numeric != JNI_FALSE, double_initials != JNI_FALSE, limit.max(1) as usize)
                .into_iter().map(|p| p.join("'")).collect()
        })
    });
    strings(&mut env, &rows)
}

#[no_mangle]
pub extern "system" fn Java_com_example_offlineime_NativeEngine_syllableChoices(
    mut env: JNIEnv, _class: JClass, handle: jlong, raw: JString, numeric: jboolean,
) -> jobjectArray {
    let raw = text(&mut env, &raw);
    let rows = guard(Vec::new(), || {
        engine(handle).map_or_else(Vec::new, |e| e.syllable_choices(&raw, numeric != JNI_FALSE))
    });
    strings(&mut env, &rows)
}

#[no_mangle]
pub extern "system" fn Java_com_example_offlineime_NativeEngine_predict(
    mut env: JNIEnv, _class: JClass, handle: jlong, prev2: JString, prev: JString, limit: jint, basic: jboolean,
) -> jobjectArray {
    let prev2 = text(&mut env, &prev2);
    let prev = text(&mut env, &prev);
    let rows = guard(Vec::new(), || {
        engine(handle).map_or_else(Vec::new, |e| e.predict(&prev2, &prev, limit.max(1) as usize, basic != JNI_FALSE))
    });
    strings(&mut env, &rows)
}

#[no_mangle]
pub extern "system" fn Java_com_example_offlineime_NativeEngine_readings(
    mut env: JNIEnv, _class: JClass, handle: jlong, word: JString,
) -> jobjectArray {
    let word = text(&mut env, &word);
    let rows = guard(Vec::new(), || engine(handle).map_or_else(Vec::new, |e| e.readings(&word)));
    strings(&mut env, &rows)
}

/// Returns `syl'syl` or `!message` when the reading is invalid or ambiguous.
#[no_mangle]
pub extern "system" fn Java_com_example_offlineime_NativeEngine_canonicalReading(
    mut env: JNIEnv, _class: JClass, code: JString, word: JString,
) -> jstring {
    let code = text(&mut env, &code);
    let word = text(&mut env, &word);
    let value = guard("!拼音无法识别".to_string(), || match engine::canonical_reading(standard(), &code, &word) {
        Ok(v) => v,
        Err(message) => format!("!{message}"),
    });
    new_string(&mut env, &value)
}

#[no_mangle]
pub extern "system" fn Java_com_example_offlineime_NativeEngine_learn(
    mut env: JNIEnv, _class: JClass, handle: jlong, reading: JString, word: JString, english: jboolean,
) -> jboolean {
    let reading = text(&mut env, &reading);
    let word = text(&mut env, &word);
    let learned = guard(false, || engine(handle).is_some_and(|e| e.learn(&reading, &word, english != JNI_FALSE)));
    if learned { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_example_offlineime_NativeEngine_learnContext(
    mut env: JNIEnv, _class: JClass, handle: jlong, prev2: JString, prev: JString, word: JString,
) {
    let prev2 = text(&mut env, &prev2);
    let prev = text(&mut env, &prev);
    let word = text(&mut env, &word);
    guard((), || {
        if let Some(e) = engine(handle) {
            e.user_mut().learn_context(&prev2, &prev, &word);
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_offlineime_NativeEngine_setCustomWords(
    mut env: JNIEnv, _class: JClass, handle: jlong, rows: JObjectArray,
) {
    let rows = read_strings(&mut env, &rows);
    guard((), || {
        if let Some(e) = engine(handle) {
            let (words, aliases) = engine::custom_words(e.syllables(), &rows);
            e.user_mut().set_custom(words, aliases);
        }
    })
}

/// Null arguments keep the current data of that kind.
#[no_mangle]
pub extern "system" fn Java_com_example_offlineime_NativeEngine_importUser(
    mut env: JNIEnv, _class: JClass, handle: jlong, words: JString, context: JString,
) -> jlongArray {
    let words = if words.is_null() { None } else { Some(text(&mut env, &words)) };
    let context = if context.is_null() { None } else { Some(text(&mut env, &context)) };
    let revisions = guard(None, || {
        let e = engine(handle)?;
        let mut user = e.user_mut();
        user.import(e.syllables(), words.as_deref(), context.as_deref());
        Some([user.words_revision as jlong, user.context_revision as jlong])
    });
    let Some(revisions) = revisions else { return std::ptr::null_mut() };
    let Ok(array) = env.new_long_array(2) else { return std::ptr::null_mut() };
    if env.set_long_array_region(&array, 0, &revisions).is_err() {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_offlineime_NativeEngine_snapshotUser(
    mut env: JNIEnv, _class: JClass, handle: jlong, saved_words: jlong, saved_context: jlong,
) -> jobjectArray {
    let rows = guard(Vec::new(), || {
        let Some(e) = engine(handle) else { return Vec::new() };
        let user = e.user();
        let words_revision = user.words_revision as jlong;
        let context_revision = user.context_revision as jlong;
        if words_revision == saved_words && context_revision == saved_context {
            return Vec::new();
        }
        vec![
            words_revision.to_string(),
            context_revision.to_string(),
            if words_revision != saved_words { user.export_words(e.syllables()) } else { String::new() },
            if context_revision != saved_context { user.export_context() } else { String::new() },
        ]
    });
    strings(&mut env, &rows)
}

#[no_mangle]
pub extern "system" fn Java_com_example_offlineime_NativeEngine_forget(
    mut env: JNIEnv, _class: JClass, handle: jlong, word: JString,
) -> jboolean {
    let word = text(&mut env, &word);
    let changed = guard(false, || engine(handle).is_some_and(|e| e.user_mut().forget(&word)));
    if changed { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_example_offlineime_NativeEngine_setPinned(
    mut env: JNIEnv, _class: JClass, handle: jlong, word: JString, pinned: jboolean,
) -> jboolean {
    let word = text(&mut env, &word);
    let changed = guard(false, || engine(handle).is_some_and(|e| e.user_mut().set_pinned(&word, pinned != JNI_FALSE)));
    if changed { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_example_offlineime_NativeEngine_convertHan(
    mut env: JNIEnv, _class: JClass, handle: jlong, input: JString, traditional: jboolean,
) -> jstring {
    let input = text(&mut env, &input);
    let converted = guard(None, || {
        engine(handle).and_then(|e| e.dict.convert_han(&input, traditional != JNI_FALSE))
    });
    match converted {
        Some(value) => new_string(&mut env, &value),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_offlineime_NativeEngine_convertHanBatch(
    mut env: JNIEnv, _class: JClass, handle: jlong, inputs: JObjectArray, signal: JObject,
) -> jobjectArray {
    let words = read_strings(&mut env, &inputs);
    let converted = guard(None, || with_cancellation(&mut env, &signal, |cancel| {
        cancel.check().ok()?;
        let eng = engine(handle)?;
        let dict = &eng.dict;
        let mut converted = Vec::with_capacity(words.len());
        for word in &words {
            cancel.step().ok()?;
            let text = dict.convert_han(word, true)?;
            converted.push((text != *word).then_some(text));
        }
        cancel.check().ok()?;
        Some(converted)
    }));
    match converted {
        Some(words) => string_array(&mut env, words.iter().map(Option::as_deref)),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_offlineime_NativeEngine_suggestStroke(
    mut env: JNIEnv, _class: JClass, handle: jlong, strokes: JString, traditional: jboolean, limit: jint, signal: JObject,
) -> jbyteArray {
    let strokes = text(&mut env, &strokes);
    let encoded = guard(None, || with_cancellation(&mut env, &signal, |cancel| {
        let e = engine(handle)?;
        let rows = e.dict.suggest_stroke_cancellable(
            &strokes, traditional != JNI_FALSE, limit.max(0) as usize, cancel,
        ).ok()??;
        cancel.check().ok()?;
        let mut out = Vec::with_capacity(rows.len() * 12);
        for (cp, score, exact) in rows {
            cancel.step().ok()?;
            out.extend_from_slice(&(cp as i32).to_le_bytes());
            out.extend_from_slice(&score.to_le_bytes());
            out.extend_from_slice(&(if exact { 1i32 } else { 0i32 }).to_le_bytes());
        }
        cancel.check().ok()?;
        Some(out)
    }));
    match encoded {
        Some(bytes) => env.byte_array_from_slice(&bytes).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}
