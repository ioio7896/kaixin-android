//! Input graph: raw key positions are nodes, edges carry the set of
//! syllables a run of keys may spell.

use crate::syllable::{is_initial, Syllables, MAX_SYLLABLE_LEN};

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Kind {
    /// Complete syllable typed in full (or every syllable sharing the keys in T9).
    Full,
    /// Bare initial used as an abbreviation.
    Abbr,
    /// Incomplete final syllable, completed from the dictionary.
    Prefix,
    /// Fuzzy pinyin substitution.
    Fuzzy,
    /// Reading chosen explicitly by the user.
    Locked,
    /// Partial reading chosen explicitly by the user.
    LockedPrefix,
}

impl Kind {
    pub fn penalty(self, numeric: bool) -> i32 {
        match self {
            Kind::Full | Kind::Locked => 0,
            Kind::LockedPrefix => 60,
            Kind::Prefix => 150,
            Kind::Abbr => if numeric { 320 } else { 250 },
            Kind::Fuzzy => 300,
        }
    }
}

pub enum EdgeIds<'a> {
    Single(u16),
    Slice(&'a [u16]),
    Owned(Vec<u16>),
}

impl<'a> EdgeIds<'a> {
    #[inline]
    pub fn is_empty(&self) -> bool {
        match self {
            EdgeIds::Single(_) => false,
            EdgeIds::Slice(s) => s.is_empty(),
            EdgeIds::Owned(v) => v.is_empty(),
        }
    }

    #[inline]
    pub fn len(&self) -> usize {
        match self {
            EdgeIds::Single(_) => 1,
            EdgeIds::Slice(s) => s.len(),
            EdgeIds::Owned(v) => v.len(),
        }
    }

    #[inline]
    pub fn first(&self) -> u16 {
        match self {
            EdgeIds::Single(id) => *id,
            EdgeIds::Slice(s) => s[0],
            EdgeIds::Owned(v) => v[0],
        }
    }

    #[inline]
    pub fn as_slice(&self) -> &[u16] {
        match self {
            EdgeIds::Single(id) => std::slice::from_ref(id),
            EdgeIds::Slice(s) => s,
            EdgeIds::Owned(v) => v.as_slice(),
        }
    }
}

pub enum EdgeText {
    Span(usize, usize),
    Owned(String),
}

pub struct Edge<'a> {
    pub to: usize,
    pub ids: EdgeIds<'a>,
    pub kind: Kind,
    /// Keys covered by this edge (letters or digits, without separators).
    text: EdgeText,
}

impl<'a> Edge<'a> {
    #[inline]
    pub fn text<'b>(&'b self, raw: &'b str) -> &'b str {
        match &self.text {
            EdgeText::Span(start, end) => &raw[*start..*end],
            EdgeText::Owned(s) => s.as_str(),
        }
    }
}

/// One explicit span of the composition handed over by the editor.
#[derive(Clone, PartialEq)]
pub enum Part {
    Raw { start: usize, end: usize },
    Locked { start: usize, end: usize, reading: String },
    LockedPrefix { start: usize, end: usize, prefix: String },
    Alternatives { start: usize, end: usize, readings: Vec<String> },
}

#[derive(Clone, Copy)]
pub struct Options {
    pub full: bool,
    pub abbreviations: bool,
    pub double_initials: bool,
    pub fuzzy_mask: u32,
}

pub struct Graph<'a> {
    pub len: usize,
    pub start: usize,
    pub numeric: bool,
    pub raw: String,
    pub out: Vec<Vec<Edge<'a>>>,
}

impl<'a> Graph<'a> {
    pub fn skip(raw: &[u8], mut at: usize) -> usize {
        while at < raw.len() && raw[at] == b'\'' {
            at += 1;
        }
        at
    }

    pub fn build(syl: &'a Syllables, raw: &str, numeric: bool, parts: Option<Vec<Part>>, opt: Options) -> Graph<'a> {
        let lower = raw.to_ascii_lowercase();
        let bytes = lower.as_bytes();
        let len = bytes.len();
        let start = Graph::skip(bytes, 0);
        let parts = parts.unwrap_or_else(|| {
            let mut v = Vec::new();
            let mut at = 0;
            while at < len {
                if bytes[at] == b'\'' {
                    at += 1;
                    continue;
                }
                let end = bytes[at..].iter().position(|&b| b == b'\'').map_or(len, |p| at + p);
                v.push(Part::Raw { start: at, end });
                at = end;
            }
            v
        });
        let mut g = Graph { len, start, numeric, raw: lower, out: (0..=len).map(|_| Vec::new()).collect() };
        for part in parts {
            match part {
                Part::Raw { start, end } => g.raw_part(syl, start.min(len), end.min(len), opt),
                Part::Locked { start, end, reading } => {
                    if let Some(id) = syl.id(&reading) {
                        g.push(start, end, EdgeIds::Single(id), Kind::Locked, EdgeText::Owned(reading));
                    } else {
                        g.raw_part(syl, start.min(len), end.min(len), opt);
                    }
                }
                Part::LockedPrefix { start, end, prefix } => {
                    let ids = syl.letter_prefix(&prefix);
                    if ids.is_empty() {
                        g.raw_part(syl, start.min(len), end.min(len), opt);
                    } else {
                        g.push(start, end, EdgeIds::Slice(ids), Kind::LockedPrefix, EdgeText::Owned(prefix));
                    }
                }
                Part::Alternatives { start, end, readings } => {
                    let mut ids: Vec<u16> = readings.iter().filter_map(|r| syl.id(r)).collect();
                    ids.sort_unstable();
                    ids.dedup();
                    let s = start.min(len);
                    let e = end.min(len);
                    if !ids.is_empty() {
                        let text = if g.raw.is_char_boundary(s) && g.raw.is_char_boundary(e) {
                            EdgeText::Span(s, e)
                        } else {
                            EdgeText::Owned(String::from_utf8_lossy(&g.raw.as_bytes()[s..e]).into_owned())
                        };
                        g.push(start, end, EdgeIds::Owned(ids), Kind::Full, text);
                    }
                }
            }
        }
        g
    }

    fn push(&mut self, start: usize, end: usize, ids: EdgeIds<'a>, kind: Kind, text: EdgeText) {
        if start >= end || end > self.len || ids.is_empty() {
            return;
        }
        let to = Graph::skip(self.raw.as_bytes(), end);
        self.out[start].push(Edge { to, ids, kind, text });
    }

    fn raw_part(&mut self, syl: &'a Syllables, start: usize, end: usize, opt: Options) {
        let input_end = Graph::skip(self.raw.as_bytes(), end) == self.len;
        for i in start..end {
            for len in 1..=MAX_SYLLABLE_LEN.min(end - i) {
                let piece = &self.raw.as_bytes()[i..i + len];
                let at_end = input_end && i + len == end;
                if piece.iter().all(u8::is_ascii_lowercase) {
                    // SAFETY: all bytes are ASCII lowercase, so valid UTF-8.
                    let text = unsafe { std::str::from_utf8_unchecked(piece) };
                    let own = syl.standard_id(text);
                    let fuzzy_variants = if opt.full && opt.fuzzy_mask != 0 {
                        own.map(|id| syl.fuzzy_variants(id, opt.fuzzy_mask)).unwrap_or_default()
                    } else {
                        Vec::new()
                    };
                    let others = syl.letter_prefix_others(text);
                    let is_abbr = opt.abbreviations && is_initial(text) && (text.len() == 1 || opt.double_initials);
                    let j = i + len;
                    if let Some(id) = own {
                        if opt.full {
                            self.push(i, j, EdgeIds::Single(id), Kind::Full, EdgeText::Span(i, j));
                            if !fuzzy_variants.is_empty() {
                                self.push(i, j, EdgeIds::Owned(fuzzy_variants), Kind::Fuzzy, EdgeText::Span(i, j));
                            }
                        }
                    }
                    if is_abbr {
                        if !others.is_empty() {
                            self.push(i, j, EdgeIds::Slice(others), Kind::Abbr, EdgeText::Span(i, j));
                        }
                    } else if at_end && (opt.full || opt.abbreviations) && !others.is_empty() {
                        self.push(i, j, EdgeIds::Slice(others), Kind::Prefix, EdgeText::Span(i, j));
                    }
                } else if piece.iter().all(|b| (b'2'..=b'9').contains(b)) {
                    // SAFETY: all bytes are ASCII digits '2'..='9'.
                    let text = unsafe { std::str::from_utf8_unchecked(piece) };
                    let full = syl.t9_full(text);
                    let others = syl.t9_prefix_others(text);
                    let j = i + len;
                    if opt.full && !full.is_empty() {
                        let ids = if full.len() == 1 { EdgeIds::Single(full[0]) } else { EdgeIds::Slice(full) };
                        self.push(i, j, ids, Kind::Full, EdgeText::Span(i, j));
                    }
                    if others.is_empty() {
                        continue;
                    }
                    if len == 1 && opt.abbreviations {
                        self.push(i, j, EdgeIds::Slice(others), Kind::Abbr, EdgeText::Span(i, j));
                    } else {
                        if opt.abbreviations && opt.double_initials && len == 2 {
                            let initials: Vec<u16> = others
                                .iter()
                                .copied()
                                .filter(|&id| syl.name(id).get(..2).is_some_and(is_initial))
                                .collect();
                            if !initials.is_empty() {
                                self.push(i, j, EdgeIds::Owned(initials), Kind::Abbr, EdgeText::Span(i, j));
                            }
                        }
                        if at_end && len >= 2 {
                            self.push(i, j, EdgeIds::Slice(others), Kind::Prefix, EdgeText::Span(i, j));
                        }
                    }
                }
            }
        }
    }

    /// Nodes reachable from the start, ascending.
    pub fn reachable(&self) -> Vec<usize> {
        let mut seen = vec![false; self.len + 1];
        seen[self.start] = true;
        for node in 0..=self.len {
            if !seen[node] {
                continue;
            }
            for e in &self.out[node] {
                seen[e.to] = true;
            }
        }
        (0..=self.len).filter(|&n| seen[n]).collect()
    }

    /// Marks every node that has any path reaching `>= boundary`.
    pub fn can_reach_tail(&self, boundary: usize) -> Vec<bool> {
        let mut reach = vec![false; self.len + 1];
        for node in (0..=self.len).rev() {
            if node >= boundary || self.out[node].iter().any(|e| reach[e.to]) {
                reach[node] = true;
            }
        }
        reach
    }
}
