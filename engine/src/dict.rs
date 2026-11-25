//! Read-only memory-mapped dictionary.
//!
//! Layout (little endian, every section 4-byte aligned):
//! header (`HEADER_LEN` bytes of u32 fields, see `Field`), syllable names,
//! word string table sorted bytewise (word id == rank), entries sorted by
//! syllable sequence then score, the u16 syllable pool, an entry index sorted
//! by word id, the alphabetic table (English words and mixed codes) sorted by
//! code, and static collocations sorted by previous word.

use crate::cancellation::{QueryCancellation, QueryCancelled};
use crate::fxhash::FxHashSet;
use crate::syllable::Syllables;
use std::cmp::Ordering;

pub const MAGIC: &[u8; 8] = b"KXDICT01";
// Increment this when the binary layout or the interpretation of stored fields changes.
pub const VERSION: u32 = 4;
pub const HEADER_LEN: usize = 128;
pub const ENTRY_LEN: usize = 12;
pub const ALPHA_LEN: usize = 12;
pub const CTX_LEN: usize = 12;
pub const PAIR_LEN: usize = 8;
pub(crate) const MAX_WORD_SYLLABLES: usize = 32;

/// A stroke candidate as (codepoint, score, exact match).
pub type StrokeCandidate = (u32, i32, bool);

/// Stack-allocated syllable sequence buffer (up to MAX_WORD_SYLLABLES).
#[derive(Clone, Copy, Debug)]
pub(crate) struct SylBuf {
    data: [u16; MAX_WORD_SYLLABLES],
    len: u8,
}

impl SylBuf {
    #[inline]
    pub(crate) const fn empty() -> Self {
        Self { data: [0; MAX_WORD_SYLLABLES], len: 0 }
    }

    #[inline]
    pub(crate) fn from_slice(src: &[u16]) -> Self {
        let mut buf = Self::empty();
        let n = src.len().min(MAX_WORD_SYLLABLES);
        buf.data[..n].copy_from_slice(&src[..n]);
        buf.len = n as u8;
        buf
    }

    #[inline]
    pub(crate) fn as_slice(&self) -> &[u16] {
        &self.data[..self.len as usize]
    }
}

/// Entry flag: bundled core/curated source.
pub const FLAG_CORE: u8 = 1;
/// Alphabetic table kinds.
pub const ALPHA_ENGLISH: u16 = 0;
pub const ALPHA_MIXED: u16 = 1;
pub const ALPHA_EMOJI: u16 = 2;

#[derive(Clone, Copy)]
#[repr(usize)]
pub enum Field {
    Version = 8,
    SylCount = 12,
    SylOff = 16,
    SylLen = 20,
    WordCount = 24,
    WordOffsetsOff = 28,
    WordBlobOff = 32,
    WordBlobLen = 36,
    EntryCount = 40,
    EntriesOff = 44,
    SeqCount = 48,
    SeqOff = 52,
    ByWordOff = 56,
    AlphaCount = 60,
    AlphaOff = 64,
    CtxCount = 68,
    CtxOff = 72,
    OpenccStCount = 76,
    OpenccStOff = 80,
    OpenccStMaxChars = 84,
    OpenccTsCount = 88,
    OpenccTsOff = 92,
    OpenccTsMaxChars = 96,
    StrokeSeqCount = 100,
    StrokeSeqOff = 104,
    StrokeSimpRankCount = 108,
    StrokeSimpRankOff = 112,
    StrokeTradRankCount = 116,
    StrokeTradRankOff = 120,
    FirstSylRangesOff = 124,
}

pub enum Backing {
    Mapped(memmap2::Mmap),
    Owned(Vec<u8>),
}

impl Backing {
    fn bytes(&self) -> &[u8] {
        match self {
            Backing::Mapped(m) => m,
            Backing::Owned(v) => v,
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub struct Entry {
    pub word: u32,
    pub seq: u32,
    pub score: u16,
    pub len: u8,
    pub flags: u8,
}

pub struct Dict {
    backing: Backing,
    pub syllables: Syllables,
    /// Offset of `syl_count + 1` u32 entry indices in the mmap'd dictionary for O(1) depth-0 buckets.
    first_syl_ranges: usize,
    syl_count: usize,
    word_count: usize,
    word_offsets: usize,
    word_blob: usize,
    word_blob_len: usize,
    entry_count: usize,
    entries: usize,
    seq: usize,
    by_word: usize,
    alpha_count: usize,
    alpha: usize,
    ctx_count: usize,
    ctx: usize,
    opencc_st_count: usize,
    opencc_st: usize,
    opencc_st_max_chars: usize,
    opencc_ts_count: usize,
    opencc_ts: usize,
    opencc_ts_max_chars: usize,
    stroke_seq_count: usize,
    stroke_seq: usize,
    stroke_simp_rank_count: usize,
    stroke_simp_rank: usize,
    stroke_trad_rank_count: usize,
    stroke_trad_rank: usize,
}

#[inline]
fn u32_at(b: &[u8], at: usize) -> u32 {
    u32::from_le_bytes([b[at], b[at + 1], b[at + 2], b[at + 3]])
}

#[inline]
fn u16_at(b: &[u8], at: usize) -> u16 {
    u16::from_le_bytes([b[at], b[at + 1]])
}

#[inline]
fn check_range(offset: usize, count: usize, stride: usize, total: usize) -> bool {
    count
        .checked_mul(stride)
        .and_then(|bytes| offset.checked_add(bytes))
        .is_some_and(|end| end <= total)
}

impl Dict {
    pub fn open(backing: Backing) -> Result<Dict, String> {
        let (fields, names) = {
            let b = backing.bytes();
            if b.len() < HEADER_LEN || &b[..8] != MAGIC {
                return Err("not a kaixin dictionary".into());
            }
            let fields: Vec<usize> = (0..HEADER_LEN / 4).map(|i| u32_at(b, i * 4) as usize).collect();
            let field = |f: Field| fields[f as usize / 4];
            if field(Field::Version) as u32 != VERSION {
                return Err("unsupported dictionary version".into());
            }
            let (syl_off, syl_len) = (field(Field::SylOff), field(Field::SylLen));
            if !check_range(syl_off, syl_len, 1, b.len()) {
                return Err("truncated dictionary".into());
            }
            let names_text = std::str::from_utf8(&b[syl_off..syl_off + syl_len])
                .map_err(|_| "invalid syllable table".to_string())?;
            let names: Vec<String> =
                names_text.split('\n').filter(|s| !s.is_empty()).map(String::from).collect();
            if names.len() != field(Field::SylCount) {
                return Err("syllable count mismatch".into());
            }
            (fields, names)
        };
        let field = |f: Field| fields[f as usize / 4];
        let syl_count = names.len();
        let word_blob_len = field(Field::WordBlobLen);
        let dict = Dict {
            syllables: Syllables::new(names),
            first_syl_ranges: field(Field::FirstSylRangesOff),
            syl_count,
            word_count: field(Field::WordCount),
            word_offsets: field(Field::WordOffsetsOff),
            word_blob: field(Field::WordBlobOff),
            word_blob_len,
            entry_count: field(Field::EntryCount),
            entries: field(Field::EntriesOff),
            seq: field(Field::SeqOff),
            by_word: field(Field::ByWordOff),
            alpha_count: field(Field::AlphaCount),
            alpha: field(Field::AlphaOff),
            ctx_count: field(Field::CtxCount),
            ctx: field(Field::CtxOff),
            opencc_st_count: field(Field::OpenccStCount),
            opencc_st: field(Field::OpenccStOff),
            opencc_st_max_chars: field(Field::OpenccStMaxChars),
            opencc_ts_count: field(Field::OpenccTsCount),
            opencc_ts: field(Field::OpenccTsOff),
            opencc_ts_max_chars: field(Field::OpenccTsMaxChars),
            stroke_seq_count: field(Field::StrokeSeqCount),
            stroke_seq: field(Field::StrokeSeqOff),
            stroke_simp_rank_count: field(Field::StrokeSimpRankCount),
            stroke_simp_rank: field(Field::StrokeSimpRankOff),
            stroke_trad_rank_count: field(Field::StrokeTradRankCount),
            stroke_trad_rank: field(Field::StrokeTradRankOff),
            backing,
        };
        let len = dict.bytes().len();
        let Some(word_offsets_count) = dict.word_count.checked_add(1) else {
            return Err("truncated dictionary".into());
        };
        let Some(first_syl_count) = syl_count.checked_add(1) else {
            return Err("truncated dictionary".into());
        };
        if !check_range(dict.word_offsets, word_offsets_count, 4, len)
            || !check_range(dict.word_blob, word_blob_len, 1, len)
            || !check_range(dict.entries, dict.entry_count, ENTRY_LEN, len)
            || !check_range(dict.seq, field(Field::SeqCount), 2, len)
            || !check_range(dict.by_word, dict.entry_count, 4, len)
            || !check_range(dict.alpha, dict.alpha_count, ALPHA_LEN, len)
            || !check_range(dict.ctx, dict.ctx_count, CTX_LEN, len)
            || !check_range(dict.opencc_st, dict.opencc_st_count, PAIR_LEN, len)
            || !check_range(dict.opencc_ts, dict.opencc_ts_count, PAIR_LEN, len)
            || !check_range(dict.stroke_seq, dict.stroke_seq_count, PAIR_LEN, len)
            || !check_range(dict.stroke_simp_rank, dict.stroke_simp_rank_count, PAIR_LEN, len)
            || !check_range(dict.stroke_trad_rank, dict.stroke_trad_rank_count, PAIR_LEN, len)
            || !check_range(dict.first_syl_ranges, first_syl_count, 4, len)
        {
            return Err("truncated dictionary".into());
        }

        // Spot-check word offset sentinels and precomputed first-syllable range bounds without faulting word_blob pages.
        {
            let b = dict.bytes();
            let first_off = u32_at(b, dict.word_offsets) as usize;
            let last_off = u32_at(b, dict.word_offsets + dict.word_count * 4) as usize;
            if first_off > last_off || last_off > word_blob_len {
                return Err("invalid word offset".into());
            }
            let mut prev = 0usize;
            for s in 0..first_syl_count {
                let bound = u32_at(b, dict.first_syl_ranges + s * 4) as usize;
                if bound < prev || bound > dict.entry_count {
                    return Err("invalid first-syllable range".into());
                }
                prev = bound;
            }
        }
        Ok(dict)
    }

    #[inline]
    pub(crate) fn first_syllable_range(&self, syl: u16) -> (usize, usize) {
        let s = syl as usize;
        if s < self.syl_count {
            let b = self.bytes();
            let at = self.first_syl_ranges + s * 4;
            (u32_at(b, at) as usize, u32_at(b, at + 4) as usize)
        } else {
            (0, 0)
        }
    }

    #[inline]
    fn bytes(&self) -> &[u8] {
        self.backing.bytes()
    }

    pub fn entry_count(&self) -> usize {
        self.entry_count
    }

    #[inline]
    pub fn entry(&self, i: usize) -> Entry {
        let b = self.bytes();
        let at = self.entries + i * ENTRY_LEN;
        Entry {
            word: u32_at(b, at),
            seq: u32_at(b, at + 4),
            score: u16_at(b, at + 8),
            len: b[at + 10],
            flags: b[at + 11],
        }
    }

    #[inline]
    pub fn seq_syllable(&self, entry: &Entry, depth: usize) -> u16 {
        u16_at(self.bytes(), self.seq + (entry.seq as usize + depth) * 2)
    }

    pub fn entry_syllables(&self, entry: &Entry) -> Vec<u16> {
        (0..entry.len as usize).map(|d| self.seq_syllable(entry, d)).collect()
    }

    #[inline]
    pub(crate) fn entry_syllables_buf(&self, entry: &Entry) -> SylBuf {
        let mut buf = SylBuf::empty();
        let n = (entry.len as usize).min(MAX_WORD_SYLLABLES);
        let b = self.bytes();
        let base = self.seq + entry.seq as usize * 2;
        for d in 0..n {
            buf.data[d] = u16_at(b, base + d * 2);
        }
        buf.len = n as u8;
        buf
    }

    #[inline]
    pub fn word(&self, id: u32) -> &str {
        assert!((id as usize) < self.word_count, "dictionary word ID out of range");
        let b = self.bytes();
        let start = u32_at(b, self.word_offsets + id as usize * 4) as usize;
        let end = u32_at(b, self.word_offsets + id as usize * 4 + 4) as usize;
        if start > end || end > self.word_blob_len {
            return "";
        }
        std::str::from_utf8(&b[self.word_blob + start..self.word_blob + end]).unwrap_or("")
    }

    /// Word id of an exact string.
    pub fn word_id(&self, word: &str) -> Option<u32> {
        let (mut lo, mut hi) = (0usize, self.word_count);
        while lo < hi {
            let mid = (lo + hi) / 2;
            match self.word(mid as u32).as_bytes().cmp(word.as_bytes()) {
                Ordering::Less => lo = mid + 1,
                Ordering::Greater => hi = mid,
                Ordering::Equal => return Some(mid as u32),
            }
        }
        None
    }

    pub(crate) fn entry_indices_of_word(&self, word: u32) -> impl Iterator<Item = usize> + '_ {
        let b = self.bytes();
        let at = move |i: usize| u32_at(b, self.by_word + i * 4) as usize;
        let (mut lo, mut hi) = (0usize, self.entry_count);
        while lo < hi {
            let mid = (lo + hi) / 2;
            if self.entry(at(mid)).word < word { lo = mid + 1 } else { hi = mid }
        }
        (lo..self.entry_count).map(at).take_while(move |&i| self.entry(i).word == word)
    }

    /// Entries of one word, highest score first.
    pub fn entries_of_word(&self, word: u32) -> Vec<Entry> {
        let mut out: Vec<_> = self.entry_indices_of_word(word).map(|i| self.entry(i)).collect();
        out.sort_by_key(|entry| std::cmp::Reverse(entry.score));
        out
    }

    /// Alphabetic row: (code word id, word id, score, kind).
    pub fn alpha(&self, i: usize) -> (u32, u32, u16, u16) {
        let b = self.bytes();
        let at = self.alpha + i * ALPHA_LEN;
        (u32_at(b, at), u32_at(b, at + 4), u16_at(b, at + 8), u16_at(b, at + 10))
    }

    /// Rows whose code starts with `prefix` (lowercase ASCII).
    pub fn alpha_prefix(&self, prefix: &str) -> std::ops::Range<usize> {
        let key = prefix.as_bytes();
        let code = |i: usize| self.word(self.alpha(i).0).as_bytes();
        let (mut lo, mut hi) = (0usize, self.alpha_count);
        while lo < hi {
            let mid = (lo + hi) / 2;
            if code(mid) < key { lo = mid + 1 } else { hi = mid }
        }
        let start = lo;
        hi = self.alpha_count;
        while lo < hi {
            let mid = (lo + hi) / 2;
            if code(mid).starts_with(key) || code(mid) < key { lo = mid + 1 } else { hi = mid }
        }
        start..lo
    }

    /// Static collocations following `prev`: (next word id, weight).
    pub fn collocations(&self, prev: u32) -> Vec<(u32, u32)> {
        let b = self.bytes();
        let row = |i: usize| {
            let at = self.ctx + i * CTX_LEN;
            (u32_at(b, at), u32_at(b, at + 4), u32_at(b, at + 8))
        };
        let (mut lo, mut hi) = (0usize, self.ctx_count);
        while lo < hi {
            let mid = (lo + hi) / 2;
            if row(mid).0 < prev { lo = mid + 1 } else { hi = mid }
        }
        let mut out = Vec::new();
        while lo < self.ctx_count {
            let (p, n, w) = row(lo);
            if p != prev {
                break;
            }
            out.push((n, w));
            lo += 1;
        }
        out
    }

    #[inline]
    fn pair_at(&self, base: usize, index: usize) -> (u32, u32) {
        let b = self.bytes();
        let at = base + index * PAIR_LEN;
        (u32_at(b, at), u32_at(b, at + 4))
    }

    fn lower_opencc(&self, base: usize, count: usize, key: &[u8]) -> usize {
        let (mut lo, mut hi) = (0usize, count);
        while lo < hi {
            let mid = (lo + hi) / 2;
            let (k_id, _) = self.pair_at(base, mid);
            if self.word(k_id).as_bytes() < key { lo = mid + 1 } else { hi = mid }
        }
        lo
    }

    fn upper_opencc_prefix(&self, base: usize, mut lo: usize, mut hi: usize, prefix: &[u8]) -> usize {
        while lo < hi {
            let mid = (lo + hi) / 2;
            let (k_id, _) = self.pair_at(base, mid);
            let k = self.word(k_id).as_bytes();
            if k.starts_with(prefix) || k < prefix { lo = mid + 1 } else { hi = mid }
        }
        lo
    }

    fn lookup_opencc_in(&self, base: usize, mut lo: usize, mut hi: usize, target: &[u8]) -> Option<&str> {
        while lo < hi {
            let mid = (lo + hi) / 2;
            let (k_id, v_id) = self.pair_at(base, mid);
            match self.word(k_id).as_bytes().cmp(target) {
                Ordering::Less => lo = mid + 1,
                Ordering::Greater => hi = mid,
                Ordering::Equal => return Some(self.word(v_id)),
            }
        }
        None
    }

    /// Longest-match Simplified <-> Traditional conversion directly over the mmap'd OpenCC table.
    pub fn convert_han(&self, text: &str, traditional: bool) -> Option<String> {
        let (base, count, max_chars) = if traditional {
            (self.opencc_st, self.opencc_st_count, self.opencc_st_max_chars)
        } else {
            (self.opencc_ts, self.opencc_ts_count, self.opencc_ts_max_chars)
        };
        if count == 0 || max_chars == 0 {
            return None;
        }
        if text.is_empty() {
            return Some(String::new());
        }
        let mut offsets: Vec<usize> = text.char_indices().map(|(idx, _)| idx).collect();
        offsets.push(text.len());
        let total_chars = offsets.len() - 1;
        let mut out = String::with_capacity(text.len());
        let mut i = 0usize;
        while i < total_chars {
            let first = &text[offsets[i]..offsets[i + 1]];
            let first_bytes = first.as_bytes();
            let lo = self.lower_opencc(base, count, first_bytes);
            if lo >= count {
                out.push_str(first);
                i += 1;
                continue;
            }
            let (k0_id, v0_id) = self.pair_at(base, lo);
            let k0 = self.word(k0_id).as_bytes();
            if !k0.starts_with(first_bytes) {
                out.push_str(first);
                i += 1;
                continue;
            }
            let max_len = (total_chars - i).min(max_chars);
            let has_multi = max_len > 1
                && (k0.len() > first_bytes.len()
                    || (lo + 1 < count && self.word(self.pair_at(base, lo + 1).0).as_bytes().starts_with(first_bytes)));
            let mut matched = false;
            if has_multi {
                let hi = self.upper_opencc_prefix(base, lo + 1, count, first_bytes);
                for len in (2..=max_len).rev() {
                    let candidate = &text.as_bytes()[offsets[i]..offsets[i + len]];
                    if let Some(replacement) = self.lookup_opencc_in(base, lo, hi, candidate) {
                        out.push_str(replacement);
                        i += len;
                        matched = true;
                        break;
                    }
                }
            }
            if !matched {
                if k0 == first_bytes {
                    out.push_str(self.word(v0_id));
                } else {
                    out.push_str(first);
                }
                i += 1;
            }
        }
        Some(out)
    }

    fn stroke_rank(&self, cp: u32, traditional: bool) -> Option<u32> {
        let (base, count) = if traditional {
            (self.stroke_trad_rank, self.stroke_trad_rank_count)
        } else {
            (self.stroke_simp_rank, self.stroke_simp_rank_count)
        };
        let (mut lo, mut hi) = (0usize, count);
        while lo < hi {
            let mid = (lo + hi) / 2;
            let (k, r) = self.pair_at(base, mid);
            match k.cmp(&cp) {
                Ordering::Less => lo = mid + 1,
                Ordering::Greater => hi = mid,
                Ordering::Equal => return Some(r),
            }
        }
        None
    }

    /// Five-stroke candidate lookup directly over the mmap'd stroke sequence & frequency tables,
    /// including '?' wildcard patterns. Returns `(codepoint, score, exact)` tuples sorted by frequency.
    pub fn suggest_stroke(&self, strokes: &str, traditional: bool, limit: usize) -> Option<Vec<StrokeCandidate>> {
        self.suggest_stroke_cancellable(strokes, traditional, limit, None, &QueryCancellation::none())
            .ok().flatten()
    }

    pub(crate) fn suggest_stroke_cancellable(
        &self, strokes: &str, traditional: bool, limit: usize,
        blocked: Option<&FxHashSet<String>>, cancel: &QueryCancellation<'_>,
    ) -> Result<Option<Vec<StrokeCandidate>>, QueryCancelled> {
        cancel.check()?;
        if self.stroke_seq_count == 0 {
            return Ok(None);
        }
        if strokes.is_empty()
            || limit == 0
            || !strokes.bytes().all(|b| (b'1'..=b'5').contains(&b) || b == b'?')
        {
            return Ok(Some(Vec::new()));
        }
        let key = strokes.as_bytes();
        let first_wildcard = key.iter().position(|&b| b == b'?');
        let literal_prefix = match first_wildcard {
            Some(pos) => &key[..pos],
            None => key,
        };
        let seq_bytes = |i: usize| self.word(self.pair_at(self.stroke_seq, i).0).as_bytes();
        let (start, end) = if literal_prefix.is_empty() {
            (0usize, self.stroke_seq_count)
        } else {
            let (mut lo, mut hi) = (0usize, self.stroke_seq_count);
            while lo < hi {
                let mid = (lo + hi) / 2;
                if seq_bytes(mid) < literal_prefix { lo = mid + 1 } else { hi = mid }
            }
            let start = lo;
            hi = self.stroke_seq_count;
            while lo < hi {
                let mid = (lo + hi) / 2;
                let s = seq_bytes(mid);
                if s.starts_with(literal_prefix) || s < literal_prefix { lo = mid + 1 } else { hi = mid }
            }
            (start, lo)
        };

        let mut exact_seen = FxHashSet::default();
        let mut exact: Vec<(u32, Option<u32>)> = Vec::new();
        let mut prefix_seen = FxHashSet::default();
        let mut prefix: Vec<(u32, Option<u32>)> = Vec::new();
        let mut ch_buf = [0u8; 4];
        for i in start..end {
            let (s_id, chars_id) = self.pair_at(self.stroke_seq, i);
            let candidate_seq = self.word(s_id).as_bytes();
            let is_exact = if let Some(wild_pos) = first_wildcard {
                cancel.step()?;
                if candidate_seq.len() < key.len() {
                    continue;
                }
                let mut matched = true;
                for idx in wild_pos..key.len() {
                    let k = key[idx];
                    if k != b'?' && candidate_seq[idx] != k {
                        matched = false;
                        break;
                    }
                }
                if !matched {
                    continue;
                }
                candidate_seq.len() == key.len()
            } else {
                candidate_seq == key
            };
            for ch in self.word(chars_id).chars() {
                cancel.step()?;
                if let Some(blocked_set) = blocked {
                    let s = ch.encode_utf8(&mut ch_buf);
                    if blocked_set.contains(s) {
                        continue;
                    }
                }
                let cp = ch as u32;
                if is_exact {
                    if exact_seen.insert(cp) {
                        exact.push((cp, self.stroke_rank(cp, traditional)));
                    }
                } else if prefix_seen.insert(cp) {
                    prefix.push((cp, self.stroke_rank(cp, traditional)));
                }
            }
        }
        prefix.retain(|(cp, _)| !exact_seen.contains(cp));

        cancel.check()?;
        exact.sort_unstable_by_key(|&(cp, rank)| (rank.unwrap_or(100_000 + cp), cp));
        prefix.sort_unstable_by_key(|&(cp, rank)| (rank.unwrap_or(100_000 + cp), cp));
        cancel.check()?;

        let mut result = Vec::with_capacity(limit.min(exact.len() + prefix.len()));
        for (list, is_exact) in [(&exact, true), (&prefix, false)] {
            for &(cp, rank) in list {
                if result.len() >= limit {
                    break;
                }
                let freq = rank.unwrap_or(100_000).min(900_000) as i32;
                let score = (if is_exact { 2_000_000 } else { 1_000_000 }) - freq;
                result.push((cp, score, is_exact));
            }
        }
        Ok(Some(result))
    }
}

/// A sorted collection of syllable sequences that can be descended one
/// syllable at a time, shared by the corpus and the in-memory user indexes.
pub trait SeqIndex {
    fn size(&self) -> usize;
    /// Syllable at `depth` of row `i`, or -1 when the row is shorter.
    fn syllable(&self, i: usize, depth: usize) -> i32;

    /// First row in [lo, hi) whose syllable at `depth` is >= `value`.
    fn lower(&self, mut lo: usize, mut hi: usize, depth: usize, value: i32) -> usize {
        while lo < hi {
            let mid = (lo + hi) / 2;
            if self.syllable(mid, depth) < value { lo = mid + 1 } else { hi = mid }
        }
        lo
    }

    /// Exponential (galloping) lower bound starting from `lo`, faster when the target is nearby.
    fn lower_gallop(&self, lo: usize, hi: usize, depth: usize, value: i32) -> usize {
        if lo >= hi || self.syllable(lo, depth) >= value {
            return lo;
        }
        let mut step = 1usize;
        let mut prev = lo;
        while let Some(probe) = lo.checked_add(step) {
            if probe >= hi {
                return self.lower(prev + 1, hi, depth, value);
            }
            if self.syllable(probe, depth) >= value {
                return self.lower(prev + 1, probe, depth, value);
            }
            prev = probe;
            step = step.saturating_mul(2);
        }
        self.lower(prev + 1, hi, depth, value)
    }
}

impl SeqIndex for Dict {
    fn size(&self) -> usize {
        self.entry_count
    }
    #[inline]
    fn syllable(&self, i: usize, depth: usize) -> i32 {
        let e = self.entry(i);
        if depth >= e.len as usize { -1 } else { self.seq_syllable(&e, depth) as i32 }
    }
}
