//! Personal data held in memory. Persistence is text so the Android side can
//! keep encrypting it with the platform keystore.
//!
//! Learned words:  `W\t<word>\t<reading>\t<count>\t<last seconds>\t<flags>`
//!   flags: bit0 pinned, bit1 English.
//! Context:        `B\t<prev>\t<word>\t<count>\t<last>` and
//!                 `T\t<prev2>\t<prev>\t<word>\t<count>\t<last>`.
//! Context snapshots start with a header identifying the seconds-based format.

use crate::dict::SeqIndex;
use crate::fxhash::{FxHashMap, FxHashSet};
use crate::syllable::{split_reading, Syllables};
use std::collections::BTreeMap;
use std::fmt::Write;
use std::ops::Bound::{Included, Unbounded};
use std::sync::OnceLock;

pub const MAX_LEARNED: usize = 20_000;
pub const MAX_CONTEXT: usize = 30_000;
const CONTEXT_HEADER: &str = "KXCTX1";
const WORDS_BIN_MAGIC: &[u8; 6] = b"KXUSR1";
const CONTEXT_BIN_MAGIC: &[u8; 6] = b"KXCTX2";

#[derive(Clone, Debug)]
pub struct UserWord {
    pub word: String,
    pub seq: Vec<u16>,
    pub count: u32,
    pub last: u64,
    pub pinned: bool,
    pub custom: bool,
}

#[derive(Default)]
pub(crate) struct AliasIndex {
    pub letters: FxHashMap<String, Vec<(String, bool)>>,
    pub digits: FxHashMap<String, Vec<(String, bool)>>,
}

type ContextCounts = FxHashMap<String, (u32, u64)>;

#[derive(Default)]
struct ContextGroup {
    bigram: ContextCounts,
    trigram: FxHashMap<String, ContextCounts>,
}

#[derive(Default)]
pub struct UserModel {
    learned: Vec<UserWord>,
    learned_at: FxHashMap<String, Vec<(Vec<u16>, usize)>>,
    custom_at: FxHashMap<String, Vec<(Vec<u16>, bool)>>,
    english: BTreeMap<String, (String, u32, u64)>,
    custom: Vec<UserWord>,
    aliases: AliasIndex,
    blocked: FxHashSet<String>,
    context: FxHashMap<String, ContextGroup>,
    bigram_count: usize,
    trigram_count: usize,
    /// learned + custom rows sorted by syllable sequence.
    index: Vec<(bool, usize)>,
    pub words_revision: u64,
    pub context_revision: u64,
    /// Custom words are stored separately from the learned-word snapshot.
    pub(crate) custom_words_revision: u64,
}

pub(crate) fn now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn clean(field: &str) -> String {
    field.replace(['\t', '\n', '\r'], " ")
}

#[inline]
fn write_str_u16(out: &mut Vec<u8>, value: &str) {
    let bytes = value.as_bytes();
    let len = bytes.len().min(u16::MAX as usize);
    let len = if len < bytes.len() {
        let mut end = len;
        while end > 0 && !value.is_char_boundary(end) {
            end -= 1;
        }
        end
    } else {
        len
    };
    out.extend_from_slice(&(len as u16).to_le_bytes());
    out.extend_from_slice(&bytes[..len]);
}

#[inline]
fn read_u8(raw: &[u8], cursor: &mut usize) -> Option<u8> {
    let v = *raw.get(*cursor)?;
    *cursor += 1;
    Some(v)
}

#[inline]
fn read_u32(raw: &[u8], cursor: &mut usize) -> Option<u32> {
    let end = cursor.checked_add(4)?;
    let slice = raw.get(*cursor..end)?;
    *cursor = end;
    Some(u32::from_le_bytes([slice[0], slice[1], slice[2], slice[3]]))
}

#[inline]
fn read_u64(raw: &[u8], cursor: &mut usize) -> Option<u64> {
    let end = cursor.checked_add(8)?;
    let slice = raw.get(*cursor..end)?;
    *cursor = end;
    Some(u64::from_le_bytes([
        slice[0], slice[1], slice[2], slice[3],
        slice[4], slice[5], slice[6], slice[7],
    ]))
}

#[inline]
fn read_str_u16<'a>(raw: &'a [u8], cursor: &mut usize) -> Option<&'a str> {
    let end_len = cursor.checked_add(2)?;
    let len_bytes = raw.get(*cursor..end_len)?;
    let len = u16::from_le_bytes([len_bytes[0], len_bytes[1]]) as usize;
    let end = end_len.checked_add(len)?;
    let text = std::str::from_utf8(raw.get(end_len..end)?).ok()?;
    *cursor = end;
    Some(text)
}

fn read_words_binary(raw: &[u8], mut row: impl FnMut(&str, &str, u32, u64, u8)) -> Option<()> {
    let raw = raw.strip_prefix(WORDS_BIN_MAGIC)?;
    let mut cursor = 0;
    let learned_count = read_u32(raw, &mut cursor)?;
    for _ in 0..learned_count {
        let word = read_str_u16(raw, &mut cursor)?;
        let reading = read_str_u16(raw, &mut cursor)?;
        let count = read_u32(raw, &mut cursor)?;
        let last = read_u64(raw, &mut cursor)?;
        let flags = read_u8(raw, &mut cursor)?;
        row(word, reading, count, last, flags & 1);
    }
    let english_count = read_u32(raw, &mut cursor)?;
    for _ in 0..english_count {
        let code = read_str_u16(raw, &mut cursor)?;
        let word = read_str_u16(raw, &mut cursor)?;
        let count = read_u32(raw, &mut cursor)?;
        let last = read_u64(raw, &mut cursor)?;
        row(word, code, count, last, 2);
    }
    (cursor == raw.len()).then_some(())
}

/// Preserve the stored readings for the word manager without consulting a syllable table.
pub(crate) fn decode_words_to_text(raw: &[u8]) -> Option<String> {
    if !raw.starts_with(WORDS_BIN_MAGIC) {
        return std::str::from_utf8(raw).ok().map(str::to_owned);
    }
    let mut text = String::new();
    read_words_binary(raw, |word, reading, count, last, flags| {
        let _ = writeln!(text, "W\t{}\t{}\t{count}\t{last}\t{flags}", clean(word), clean(reading));
    })?;
    Some(text)
}

impl UserModel {
    fn row(&self, i: usize) -> &UserWord {
        let (custom, at) = self.index[i];
        if custom { &self.custom[at] } else { &self.learned[at] }
    }

    pub fn word_at(&self, i: usize) -> &UserWord {
        self.row(i)
    }

    pub(crate) fn words_with_reading(&self, reading: &[u16]) -> impl Iterator<Item = &str> + '_ {
        let compare = |&(custom, i): &(bool, usize)| {
            let word = if custom { &self.custom[i] } else { &self.learned[i] };
            word.seq.as_slice().cmp(reading)
        };
        let first = self.index.partition_point(|row| compare(row).is_lt());
        let count = self.index[first..].partition_point(|row| compare(row).is_eq());
        (first..first + count).map(|i| self.row(i).word.as_str())
    }

    fn reindex(&mut self) {
        let mut index: Vec<(bool, usize)> = (0..self.learned.len())
            .map(|i| (false, i))
            .chain((0..self.custom.len()).map(|i| (true, i)))
            .collect();
        index.sort_by(|a, b| {
            let wa = if a.0 { &self.custom[a.1] } else { &self.learned[a.1] };
            let wb = if b.0 { &self.custom[b.1] } else { &self.learned[b.1] };
            wa.seq.cmp(&wb.seq)
        });
        self.index = index;
        self.learned_at.clear();
        for (i, w) in self.learned.iter().enumerate() {
            let readings = self.learned_at.entry(w.word.clone()).or_default();
            if let Some(slot) = readings.iter_mut().find(|(s, _)| s == &w.seq) {
                slot.1 = i;
            } else {
                readings.push((w.seq.clone(), i));
            }
        }
        self.custom_at.clear();
        for w in &self.custom {
            let readings = self.custom_at.entry(w.word.clone()).or_default();
            if let Some(slot) = readings.iter_mut().find(|(s, _)| s == &w.seq) {
                slot.1 = slot.1 || w.pinned;
            } else {
                readings.push((w.seq.clone(), w.pinned));
            }
        }
    }

    /// Personal bonus (lower cost) for a word under a reading.
    pub fn bonus(&self, word: &str, seq: &[u16], query_time: u64) -> i32 {
        let mut best = 0;
        if let Some(readings) = self.learned_at.get(word) {
            if let Some(&(_, i)) = readings.iter().find(|(s, _)| s.as_slice() == seq) {
                best = learned_bonus(&self.learned[i], query_time);
            }
        }
        if let Some(readings) = self.custom_at.get(word) {
            if let Some(&(_, pinned)) = readings.iter().find(|(s, _)| s.as_slice() == seq) {
                best = best.max(if pinned { 3_000 } else { 900 });
            }
        }
        best
    }

    pub fn english_bonus(&self, word: &str, query_time: u64) -> i32 {
        self.english_bonus_lower(&word.to_lowercase(), query_time)
    }

    #[inline]
    pub fn english_bonus_lower(&self, lower: &str, query_time: u64) -> i32 {
        self.english
            .get(lower)
            .map_or(0, |(_, c, t)| learned_bonus_values(*c, *t, query_time))
    }

    pub fn english_prefix(&self, prefix: &str, query_time: u64) -> Vec<(String, i32)> {
        self.english
            .range::<str, _>((Included(prefix), Unbounded))
            .take_while(|(code, _)| code.starts_with(prefix))
            .map(|(_, (word, c, t))| (word.clone(), learned_bonus_values(*c, *t, query_time)))
            .collect()
    }

    pub fn aliases(&self, code: &str, numeric: bool) -> &[(String, bool)] {
        let index = if numeric { &self.aliases.digits } else { &self.aliases.letters };
        index.get(code).map(Vec::as_slice).unwrap_or(&[])
    }

    pub fn learn(&mut self, word: &str, seq: Vec<u16>) {
        self.words_revision += 1;
        let t = now();
        if let Some(readings) = self.learned_at.get(word) {
            if let Some(&(_, i)) = readings.iter().find(|(s, _)| s.as_slice() == seq.as_slice()) {
                let w = &mut self.learned[i];
                w.count = w.count.saturating_add(1);
                w.last = t;
                return;
            }
        }
        let new_idx = self.learned.len();
        self.learned.push(UserWord { word: word.to_string(), seq: seq.clone(), count: 1, last: t, pinned: false, custom: false });
        if self.learned.len() > MAX_LEARNED {
            // Drop the least valuable quarter: rarely and least recently used.
            self.learned.sort_by_cached_key(|w| {
                std::cmp::Reverse(learned_bonus(w, t) as i64 * 1_000_000 + (w.last / 3600) as i64)
            });
            self.learned.truncate(MAX_LEARNED * 3 / 4);
            self.reindex();
            return;
        }
        // Incremental index update: O(log N) search + single insert instead of full reindex.
        let pos = self.index.partition_point(|&(custom, at)| {
            let existing = if custom { &self.custom[at].seq } else { &self.learned[at].seq };
            existing.as_slice() < seq.as_slice()
        });
        self.index.insert(pos, (false, new_idx));
        self.learned_at.entry(word.to_string()).or_default().push((seq, new_idx));
    }

    pub fn learn_english(&mut self, word: &str) {
        let entry = self.english.entry(word.to_lowercase()).or_insert((word.to_string(), 0, 0));
        entry.0 = word.to_string();
        entry.1 = entry.1.saturating_add(1);
        entry.2 = now();
        self.words_revision += 1;
    }

    pub fn set_pinned(&mut self, word: &str, pinned: bool) -> bool {
        let mut changed = false;
        for w in self.learned.iter_mut().filter(|w| w.word == word) {
            w.pinned = pinned;
            changed = true;
        }
        if changed {
            self.words_revision += 1;
        }
        changed
    }

    pub fn forget(&mut self, word: &str) -> bool {
        let before = self.learned.len() + self.english.len();
        let context_before = self.bigram_count + self.trigram_count;
        self.learned.retain(|w| w.word != word);
        self.english.retain(|_, (w, _, _)| w != word);
        self.context.remove(word);
        for group in self.context.values_mut() {
            group.bigram.remove(word);
            group.trigram.remove(word);
            for rows in group.trigram.values_mut() {
                rows.remove(word);
            }
        }
        self.recount_context();
        let changed = before != self.learned.len() + self.english.len();
        if changed {
            self.words_revision += 1;
            self.reindex();
        }
        let context_changed = context_before != self.bigram_count + self.trigram_count;
        if context_changed {
            self.context_revision += 1;
        }
        changed || context_changed
    }

    pub fn learn_context(&mut self, prev2: &str, prev: &str, word: &str) {
        if prev.is_empty() || word.is_empty() {
            return;
        }
        let t = now();
        let group = self.context.entry(prev.to_string()).or_default();
        let b = group.bigram.entry(word.to_string()).or_insert_with(|| {
            self.bigram_count += 1;
            (0, 0)
        });
        b.0 = b.0.saturating_add(1);
        b.1 = t;
        if !prev2.is_empty() {
            let rows = group.trigram.entry(prev2.to_string()).or_default();
            let tr = rows.entry(word.to_string()).or_insert_with(|| {
                self.trigram_count += 1;
                (0, 0)
            });
            tr.0 = tr.0.saturating_add(1);
            tr.1 = t;
        }
        let prune = self.bigram_count > MAX_CONTEXT || self.trigram_count > MAX_CONTEXT;
        if self.bigram_count > MAX_CONTEXT {
            let mut oldest: Vec<u64> = self.context.values()
                .flat_map(|group| group.bigram.values()).map(|v| v.1).collect();
            oldest.sort_unstable();
            let cut = oldest[oldest.len() / 4];
            for group in self.context.values_mut() {
                group.bigram.retain(|_, v| v.1 > cut);
            }
        }
        if self.trigram_count > MAX_CONTEXT {
            let mut oldest: Vec<u64> = self.context.values()
                .flat_map(|group| group.trigram.values()).flat_map(FxHashMap::values)
                .map(|v| v.1).collect();
            oldest.sort_unstable();
            let cut = oldest[oldest.len() / 4];
            for group in self.context.values_mut() {
                for rows in group.trigram.values_mut() {
                    rows.retain(|_, v| v.1 > cut);
                }
            }
        }
        if prune {
            self.recount_context();
        }
        self.context_revision += 1;
    }

    fn recount_context(&mut self) {
        self.context.retain(|_, group| {
            group.trigram.retain(|_, rows| !rows.is_empty());
            !group.bigram.is_empty() || !group.trigram.is_empty()
        });
        self.bigram_count = self.context.values().map(|group| group.bigram.len()).sum();
        self.trigram_count = self.context.values()
            .flat_map(|group| group.trigram.values()).map(FxHashMap::len).sum();
    }

    /// Learned context support for `word` after `prev2 prev`.
    pub fn context_bonus(&self, prev2: &str, prev: &str, word: &str) -> i32 {
        if prev.is_empty() {
            return 0;
        }
        let Some(group) = self.context.get(prev) else { return 0 };
        let mut bonus = 0;
        if let Some((c, _)) = group.bigram.get(word) {
            bonus += bigram_bonus(*c);
        }
        if !prev2.is_empty() {
            if let Some((c, _)) = group.trigram.get(prev2).and_then(|rows| rows.get(word)) {
                bonus += trigram_bonus(*c);
            }
        }
        bonus
    }

    fn context_rows(&self, prev2: &str, prev: &str) -> (Option<&ContextCounts>, Option<&ContextCounts>) {
        let Some(group) = self.context.get(prev) else { return (None, None) };
        let trigram = if prev2.is_empty() { None } else { group.trigram.get(prev2) };
        (Some(&group.bigram), trigram)
    }

    pub(crate) fn context_words(&self, prev2: &str, prev: &str) -> impl Iterator<Item = &str> {
        let (bigram, trigram) = self.context_rows(prev2, prev);
        bigram.into_iter().chain(trigram).flat_map(|rows| rows.keys().map(String::as_str))
    }

    /// Next-word predictions with relative weights.
    pub fn predict(&self, prev2: &str, prev: &str) -> Vec<(String, u32)> {
        let mut scores: FxHashMap<&str, u32> = FxHashMap::default();
        let (bigram, trigram) = self.context_rows(prev2, prev);
        for (w, (c, _)) in bigram.into_iter().flatten() {
            *scores.entry(w.as_str()).or_default() += c * 2;
        }
        for (w, (c, _)) in trigram.into_iter().flatten() {
            *scores.entry(w.as_str()).or_default() += c * 3;
        }
        scores.into_iter().map(|(w, s)| (w.to_string(), s)).collect()
    }

    /// Replaces the explicit word list maintained by the custom word manager.
    pub(crate) fn set_custom(&mut self, words: Vec<UserWord>, aliases: AliasIndex) {
        self.custom = words;
        self.aliases = aliases;
        self.reindex();
        self.custom_words_revision += 1;
    }

    pub fn set_blocked(&mut self, words: Vec<String>) {
        self.blocked.clear();
        self.blocked.extend(words.into_iter().filter(|w| !w.is_empty()));
    }

    #[inline]
    pub fn is_blocked(&self, word: &str) -> bool {
        !self.blocked.is_empty() && self.blocked.contains(word)
    }

    #[inline]
    pub(crate) fn blocked_set(&self) -> Option<&FxHashSet<String>> {
        if self.blocked.is_empty() { None } else { Some(&self.blocked) }
    }

    pub fn learned_readings(&self, syl: &Syllables, word: &str) -> Vec<String> {
        self.learned
            .iter()
            .chain(self.custom.iter())
            .filter(|w| w.word == word)
            .map(|w| w.seq.iter().map(|&s| syl.name(s)).collect::<Vec<_>>().join(" "))
            .collect()
    }

    pub fn export_words(&self, syl: &Syllables) -> String {
        let mut out = String::new();
        for w in &self.learned {
            let reading: Vec<&str> = w.seq.iter().map(|&s| syl.name(s)).collect();
            out.push_str(&format!(
                "W\t{}\t{}\t{}\t{}\t{}\n",
                clean(&w.word),
                reading.join(" "),
                w.count,
                w.last,
                if w.pinned { 1 } else { 0 }
            ));
        }
        for (code, (word, count, last)) in &self.english {
            out.push_str(&format!("W\t{}\t{}\t{}\t{}\t2\n", clean(word), clean(code), count, last));
        }
        out
    }

    pub fn export_context(&self) -> String {
        let mut out = format!("{CONTEXT_HEADER}\n");
        for (prev, group) in &self.context {
            for (word, (count, last)) in &group.bigram {
                out.push_str(&format!("B\t{}\t{}\t{}\t{}\n", clean(prev), clean(word), count, last));
            }
            for (prev2, rows) in &group.trigram {
                for (word, (count, last)) in rows {
                    out.push_str(&format!("T\t{}\t{}\t{}\t{}\t{}\n", clean(prev2), clean(prev), clean(word), count, last));
                }
            }
        }
        out
    }

    pub fn export_words_binary(&self, syl: &Syllables) -> Vec<u8> {
        let mut out = Vec::with_capacity(16 + (self.learned.len() + self.english.len()) * 32);
        out.extend_from_slice(WORDS_BIN_MAGIC);
        out.extend_from_slice(&(self.learned.len() as u32).to_le_bytes());
        let mut reading = String::new();
        for w in &self.learned {
            write_str_u16(&mut out, &w.word);
            reading.clear();
            for (idx, &s) in w.seq.iter().enumerate() {
                if idx > 0 {
                    reading.push(' ');
                }
                reading.push_str(syl.name(s));
            }
            write_str_u16(&mut out, &reading);
            out.extend_from_slice(&w.count.to_le_bytes());
            out.extend_from_slice(&w.last.to_le_bytes());
            out.push(if w.pinned { 1 } else { 0 });
        }
        out.extend_from_slice(&(self.english.len() as u32).to_le_bytes());
        for (code, (word, count, last)) in &self.english {
            write_str_u16(&mut out, code);
            write_str_u16(&mut out, word);
            out.extend_from_slice(&count.to_le_bytes());
            out.extend_from_slice(&last.to_le_bytes());
        }
        out
    }

    pub fn export_context_binary(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(16 + (self.bigram_count + self.trigram_count) * 24);
        out.extend_from_slice(CONTEXT_BIN_MAGIC);
        out.extend_from_slice(&(self.context.len() as u32).to_le_bytes());
        for (prev, group) in &self.context {
            write_str_u16(&mut out, prev);
            out.extend_from_slice(&(group.bigram.len() as u32).to_le_bytes());
            for (word, (count, last)) in &group.bigram {
                write_str_u16(&mut out, word);
                out.extend_from_slice(&count.to_le_bytes());
                out.extend_from_slice(&last.to_le_bytes());
            }
            out.extend_from_slice(&(group.trigram.len() as u32).to_le_bytes());
            for (prev2, rows) in &group.trigram {
                write_str_u16(&mut out, prev2);
                out.extend_from_slice(&(rows.len() as u32).to_le_bytes());
                for (word, (count, last)) in rows {
                    write_str_u16(&mut out, word);
                    out.extend_from_slice(&count.to_le_bytes());
                    out.extend_from_slice(&last.to_le_bytes());
                }
            }
        }
        out
    }

    /// Imports learned words and/or context from either binary (`KXUSR1`/`KXCTX2`) or legacy UTF-8 text.
    pub fn import_bytes(&mut self, syl: &Syllables, words: Option<&[u8]>, context: Option<&[u8]>) {
        if let Some(raw) = words {
            if raw.starts_with(WORDS_BIN_MAGIC) {
                if self.import_words_binary(syl, raw) {
                    self.reindex();
                    self.words_revision += 1;
                }
            } else if let Ok(text) = std::str::from_utf8(raw) {
                self.import(syl, Some(text), None);
            }
        }
        if let Some(raw) = context {
            if raw.starts_with(CONTEXT_BIN_MAGIC) {
                if self.import_context_binary(raw) {
                    self.recount_context();
                    self.context_revision += 1;
                }
            } else if let Ok(text) = std::str::from_utf8(raw) {
                self.import(syl, None, Some(text));
            }
        }
    }

    fn import_words_binary(&mut self, syl: &Syllables, raw: &[u8]) -> bool {
        let mut learned = Vec::new();
        let mut english = BTreeMap::new();
        let parsed = read_words_binary(raw, |word, reading, count, last, flags| {
            if flags & 2 != 0 {
                english.insert(reading.to_string(), (word.to_string(), count, last));
                return;
            }
            let seq: Option<Vec<u16>> = split_reading(reading).iter().map(|s| syl.id(s)).collect();
            let Some(seq) = seq.filter(|s| !s.is_empty()) else { return };
            learned.push(UserWord {
                word: word.to_string(),
                seq,
                count,
                last,
                pinned: flags & 1 != 0,
                custom: false,
            });
        });
        if parsed.is_none() {
            return false;
        }
        self.learned = learned;
        self.english = english;
        true
    }

    fn import_context_binary(&mut self, raw: &[u8]) -> bool {
        let mut cursor = CONTEXT_BIN_MAGIC.len();
        let Some(group_count) = read_u32(raw, &mut cursor) else { return false };
        let group_count = (group_count as usize).min(MAX_CONTEXT * 2);
        let mut context: FxHashMap<String, ContextGroup> = FxHashMap::default();
        context.reserve(group_count.min(4096));
        for _ in 0..group_count {
            let Some(prev) = read_str_u16(raw, &mut cursor) else { return false };
            let Some(bi_count) = read_u32(raw, &mut cursor) else { return false };
            let mut group = ContextGroup::default();
            for _ in 0..(bi_count as usize).min(MAX_CONTEXT) {
                let Some(word) = read_str_u16(raw, &mut cursor) else { return false };
                let Some(count) = read_u32(raw, &mut cursor) else { return false };
                let Some(last) = read_u64(raw, &mut cursor) else { return false };
                group.bigram.insert(word.to_string(), (count, last));
            }
            let Some(tri_groups) = read_u32(raw, &mut cursor) else { return false };
            for _ in 0..(tri_groups as usize).min(MAX_CONTEXT) {
                let Some(prev2) = read_str_u16(raw, &mut cursor) else { return false };
                let Some(row_count) = read_u32(raw, &mut cursor) else { return false };
                let mut rows = ContextCounts::default();
                for _ in 0..(row_count as usize).min(MAX_CONTEXT) {
                    let Some(word) = read_str_u16(raw, &mut cursor) else { return false };
                    let Some(count) = read_u32(raw, &mut cursor) else { return false };
                    let Some(last) = read_u64(raw, &mut cursor) else { return false };
                    rows.insert(word.to_string(), (count, last));
                }
                if !rows.is_empty() {
                    group.trigram.insert(prev2.to_string(), rows);
                }
            }
            if !group.bigram.is_empty() || !group.trigram.is_empty() {
                context.insert(prev.to_string(), group);
            }
        }
        self.context = context;
        true
    }

    /// Replaces learned words and/or context from exported text.
    pub fn import(&mut self, syl: &Syllables, words: Option<&str>, context: Option<&str>) {
        if let Some(text) = words {
            self.learned.clear();
            self.english.clear();
            for line in text.lines() {
                let cols: Vec<&str> = line.split('\t').collect();
                if cols.len() != 6 || cols[0] != "W" {
                    continue;
                }
                let count = cols[3].parse().unwrap_or(1);
                let last = cols[4].parse().unwrap_or(0);
                let flags: u32 = cols[5].parse().unwrap_or(0);
                if flags & 2 != 0 {
                    self.english.insert(cols[2].to_string(), (cols[1].to_string(), count, last));
                    continue;
                }
                let seq: Option<Vec<u16>> = split_reading(cols[2]).iter().map(|s| syl.id(s)).collect();
                let Some(seq) = seq.filter(|s| !s.is_empty()) else { continue };
                self.learned.push(UserWord {
                    word: cols[1].to_string(),
                    seq,
                    count,
                    last,
                    pinned: flags & 1 != 0,
                    custom: false,
                });
            }
            self.reindex();
            self.words_revision += 1;
        }
        if let Some(text) = context {
            self.context.clear();
            self.bigram_count = 0;
            self.trigram_count = 0;
            self.context_revision += 1;
            let mut lines = text.lines();
            if lines.next() != Some(CONTEXT_HEADER) {
                return;
            }
            for line in lines {
                let cols: Vec<&str> = line.split('\t').collect();
                match (cols.first().copied(), cols.len()) {
                    (Some("B"), 5) => {
                        let v = (cols[3].parse().unwrap_or(1), cols[4].parse().unwrap_or(0));
                        self.context.entry(cols[1].into()).or_default().bigram.insert(cols[2].into(), v);
                    }
                    (Some("T"), 6) => {
                        let v = (cols[4].parse().unwrap_or(1), cols[5].parse().unwrap_or(0));
                        self.context.entry(cols[2].into()).or_default().trigram
                            .entry(cols[1].into()).or_default().insert(cols[3].into(), v);
                    }
                    _ => {}
                }
            }
            self.recount_context();
        }
    }
}

fn log_tables() -> &'static ([i32; 27], [i32; 27], [i32; 27]) {
    static LUT: OnceLock<([i32; 27], [i32; 27], [i32; 27])> = OnceLock::new();
    LUT.get_or_init(|| {
        let mut learned = [0i32; 27];
        let mut bigram = [0i32; 27];
        let mut trigram = [0i32; 27];
        for c in 0..27 {
            let ln = (1.0 + c as f64).ln();
            learned[c] = ((160.0 * ln) as i32).min(520);
            bigram[c] = ((140.0 * ln) as i32).min(450);
            trigram[c] = ((100.0 * ln) as i32).min(300);
        }
        (learned, bigram, trigram)
    })
}

#[inline]
fn bigram_bonus(count: u32) -> i32 {
    if count >= 26 { 450 } else { log_tables().1[count as usize] }
}

#[inline]
fn trigram_bonus(count: u32) -> i32 {
    if count >= 26 { 300 } else { log_tables().2[count as usize] }
}

fn learned_bonus(w: &UserWord, query_time: u64) -> i32 {
    if w.pinned {
        return 3_000;
    }
    learned_bonus_values(w.count, w.last, query_time)
}

fn learned_bonus_values(count: u32, last: u64, query_time: u64) -> i32 {
    let frequency = if count >= 26 { 520 } else { log_tables().0[count as usize] };
    let age_secs = query_time.saturating_sub(last);
    // Fresh choices get a short-lived lift that fades over three days (259_200 seconds).
    let recency = if age_secs < 259_200 {
        (200.0 * (1.0 - age_secs as f64 / 259_200.0)) as i32
    } else {
        0
    };
    frequency + recency
}

impl SeqIndex for UserModel {
    fn size(&self) -> usize {
        self.index.len()
    }
    fn syllable(&self, i: usize, depth: usize) -> i32 {
        let w = self.row(i);
        w.seq.get(depth).map_or(-1, |&s| s as i32)
    }
}

/// Small read-only index of generated sample names for the name mode.
pub struct NameIndex {
    pub rows: Vec<(String, Vec<u16>, u16)>,
}

const SURNAMES: &str = "王:wang 李:li 张:zhang 刘:liu 陈:chen 杨:yang 赵:zhao 黄:huang 周:zhou 吴:wu 徐:xu 孙:sun 胡:hu 朱:zhu 高:gao 林:lin 何:he 郭:guo 马:ma 罗:luo 梁:liang 宋:song 郑:zheng 谢:xie 韩:han 唐:tang 冯:feng 于:yu 董:dong 萧:xiao 肖:xiao 程:cheng 曹:cao 袁:yuan 邓:deng 许:xu 傅:fu 沈:shen 曾:zeng 彭:peng 吕:lv 苏:su 卢:lu 蒋:jiang 蔡:cai 贾:jia 丁:ding 魏:wei 薛:xue 叶:ye 阎:yan 余:yu 潘:pan 杜:du 戴:dai 夏:xia 钟:zhong 汪:wang 田:tian 任:ren 欧阳:ou_yang 司马:si_ma 上官:shang_guan 诸葛:zhu_ge";
const GIVEN: &str = "伟:wei 芳:fang 娜:na 秀英:xiu_ying 敏:min 静:jing 丽:li 强:qiang 磊:lei 军:jun 洋:yang 勇:yong 艳:yan 杰:jie 涛:tao 明:ming 超:chao 秀兰:xiu_lan 霞:xia 平:ping 刚:gang 桂英:gui_ying 燕:yan 华:hua 辉:hui 欣:xin 宇:yu 晨:chen 浩:hao 凯:kai 婷:ting 雪:xue 宁:ning 安:an 轩:xuan 妍:yan 睿:rui 博:bo 文:wen 鑫:xin 子涵:zi_han 梓涵:zi_han 浩然:hao_ran 宇轩:yu_xuan 子轩:zi_xuan 欣怡:xin_yi 诗涵:shi_han 雨桐:yu_tong 雨欣:yu_xin 思涵:si_han 若曦:ruo_xi 晨曦:chen_xi 一诺:yi_nuo 佳怡:jia_yi 佳琪:jia_qi 子墨:zi_mo 梓轩:zi_xuan 俊杰:jun_jie 俊豪:jun_hao 志强:zhi_qiang 建国:jian_guo 建军:jian_jun 国庆:guo_qing 海燕:hai_yan 晓明:xiao_ming 晓红:xiao_hong 晓燕:xiao_yan 文博:wen_bo 博文:bo_wen 嘉宁:jia_ning 嘉怡:jia_yi 思远:si_yuan 致远:zhi_yuan 明轩:ming_xuan 天宇:tian_yu 皓宇:hao_yu 泽宇:ze_yu 泽楷:ze_kai 雨晴:yu_qing 语嫣:yu_yan";

impl NameIndex {
    pub fn new(syl: &Syllables) -> NameIndex {
        let mut rows = Vec::new();
        for (si, surname) in SURNAMES.split(' ').enumerate() {
            let (family, family_reading) = surname.split_once(':').unwrap_or((surname, ""));
            for (gi, given) in GIVEN.split(' ').enumerate() {
                let (name, name_reading) = given.split_once(':').unwrap_or((given, ""));
                let reading = format!("{family_reading}_{name_reading}");
                let seq: Option<Vec<u16>> = reading.split('_').map(|s| syl.id(s)).collect();
                if let Some(seq) = seq {
                    let score = 6500i32 - si as i32 * 24 - gi as i32 * 9;
                    rows.push((format!("{family}{name}"), seq, score.max(1) as u16));
                }
            }
        }
        rows.sort_by(|a, b| a.1.cmp(&b.1).then(b.2.cmp(&a.2)));
        NameIndex { rows }
    }
}

impl SeqIndex for NameIndex {
    fn size(&self) -> usize {
        self.rows.len()
    }
    fn syllable(&self, i: usize, depth: usize) -> i32 {
        self.rows[i].1.get(depth).map_or(-1, |&s| s as i32)
    }
}
