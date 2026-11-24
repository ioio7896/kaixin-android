//! Candidate generation and ranking.
//!
//! Costs are in hundredths of a natural log: a word costs
//! `100 * (ln 10000 - ln score)` plus per-syllable penalties for
//! abbreviations, completions and fuzzy matches, plus `WORD_COST` per word so
//! longer dictionary words beat compositions of shorter ones. Personal and
//! context evidence subtract from the cost.

use crate::cancellation::{QueryCancellation, QueryCancelled};
use crate::dict::{Backing, Dict, SeqIndex, SylBuf, ALPHA_EMOJI, ALPHA_ENGLISH};
use crate::fxhash::{FxHashMap, FxHashSet};
use crate::graph::{Edge, EdgeIds, Graph, Kind, Options, Part};
use crate::syllable::{split_reading, to_t9, Syllables, MAX_SYLLABLE_LEN};
use crate::user::{now, AliasIndex, NameIndex, UserModel, UserWord};
use std::ops::Range;
use std::sync::{Arc, Mutex, OnceLock, RwLock};

pub const TIER_USER: i32 = 0;
pub const TIER_EXACT_FULL: i32 = 10;
pub const TIER_EXACT_SHORT: i32 = 20;
pub const TIER_MIXED: i32 = 25;
pub const TIER_PREFIX: i32 = 30;
pub const TIER_FUZZY: i32 = 40;
pub const TIER_COMPOSED: i32 = 50;

pub const F_FULL: u32 = 1;
pub const F_SHORT: u32 = 2;
pub const F_FUZZY: u32 = 4;
pub const F_SENTENCE: u32 = 8;
pub const F_MIXED_ENGLISH: u32 = 16;
pub const F_BASIC_CONTEXT: u32 = 32;
pub const F_NAMES: u32 = 64;
pub const F_CREATION: u32 = 128;
pub const F_DOUBLE_INITIALS: u32 = 1 << 8;
pub const F_NINE_COMPLETION: u32 = 1 << 9;
pub const F_SPELLING_CORRECTION: u32 = 1 << 10;
pub const FUZZY_SHIFT: u32 = 16;

pub const MODE_PINYIN: i32 = 0;
pub const MODE_NINE: i32 = 1;
pub const MODE_ENGLISH: i32 = 2;

const WORD_COST: i32 = 300;
const MAX_DEPTH: usize = 12;
const USER_WALK_BUDGET: usize = 40_000;
const DICT_START_BUDGET: usize = 280_000;
const DICT_MID_BUDGET: usize = 160_000;
const NAMES_WALK_BUDGET: usize = 40_000;
const COMPOSE_BEAM_WIDTH: usize = 4;
const COMPOSE_SPAN_LIMIT: usize = 3;
const COMMON_SCORE: u16 = 4_000;
const NINE_COMPLETION_LIMIT: usize = 2;
const CORRECTION_LIMIT: usize = 6;
const MIN_DIRECT_RANGE_LIMIT: usize = 32;

#[derive(Clone, Debug)]
pub struct Candidate {
    pub word: String,
    pub reading: String,
    pub consumed: usize,
    pub tier: i32,
    pub cost: i32,
    pub segments: Vec<String>,
}

#[derive(Clone, Copy, Default)]
struct PathInfo {
    penalty: i32,
    abbr: u8,
    prefix: u8,
    fuzzy: u8,
    full: u8,
    automatic_prefix: bool,
}

impl PathInfo {
    fn add(mut self, kind: Kind, numeric: bool) -> PathInfo {
        self.penalty += kind.penalty(numeric);
        match kind {
            Kind::Abbr => self.abbr += 1,
            Kind::Prefix => {
                self.prefix += 1;
                self.automatic_prefix = true;
            }
            Kind::LockedPrefix => self.prefix += 1,
            Kind::Fuzzy => self.fuzzy += 1,
            Kind::Full | Kind::Locked => self.full += 1,
        }
        self
    }

    fn tier(&self) -> i32 {
        if self.fuzzy > 0 {
            TIER_FUZZY
        } else if self.prefix > 0 {
            TIER_PREFIX
        } else if self.abbr > 0 {
            if self.full == 0 { TIER_EXACT_SHORT } else { TIER_MIXED }
        } else {
            TIER_EXACT_FULL
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
enum Src {
    Dict,
    User,
    Names,
}

#[derive(Clone, Copy)]
struct RangeHit {
    src: Src,
    start: usize,
    end: usize,
    lo: usize,
    hi: usize,
    info: PathInfo,
    completion: bool,
}

struct Walker<'a, I: SeqIndex> {
    idx: &'a I,
    dict_first: Option<&'a Dict>,
    g: &'a Graph<'a>,
    src: Src,
    start: usize,
    budget: &'a mut usize,
    out: &'a mut Vec<RangeHit>,
    /// Sentence-only walks skip edges that composition would discard anyway.
    plain_only: bool,
    complete_nine: bool,
    cancel: &'a QueryCancellation<'a>,
}

impl<I: SeqIndex> Walker<'_, I> {
    fn collect(
        &mut self, previous: Option<&QueryCache>, completed: &mut Option<CompletedWalks>,
    ) -> Result<(), QueryCancelled> {
        let key = (self.src, self.start);
        let initial_budget = *self.budget;
        let first_hit = self.out.len();
        let reusable = previous.and_then(|cache| {
            if *cache.can_reach_tail.get(self.start)? {
                return None;
            }
            let walk = cache.completed.get(&key)?;
            (walk.steps < initial_budget).then_some((cache, walk))
        });
        if let Some((cache, walk)) = reusable {
            for &hit in &cache.hits[walk.hits.clone()] {
                self.cancel.step()?;
                self.out.push(hit);
            }
            // A cache hit spends the same source budget as a fresh traversal.
            *self.budget -= walk.steps;
        } else {
            self.walk(self.start, 0, 0, self.idx.size(), PathInfo::default())?;
        }
        // A walk that used its entire budget might have stopped partway through this start.
        if *self.budget != 0 {
            if let Some(completed) = completed {
                completed.insert(key, CachedWalk {
                    steps: initial_budget - *self.budget,
                    hits: first_hit..self.out.len(),
                });
            }
        }
        Ok(())
    }

    fn walk(&mut self, node: usize, depth: usize, lo: usize, hi: usize, info: PathInfo) -> Result<(), QueryCancelled> {
        let exact_end = if depth == 0 {
            lo
        } else {
            self.idx.lower(lo, hi, depth, 0)
        };
        if depth > 0 && exact_end > lo {
            self.out.push(RangeHit { src: self.src, start: self.start, end: node, lo, hi: exact_end, info, completion: false });
        }
        if self.complete_nine && node == self.g.len && info.full >= 2
            && info.abbr == 0 && (2..4).contains(&depth) && exact_end < hi
        {
            self.out.push(RangeHit { src: self.src, start: self.start, end: node, lo: exact_end, hi, info, completion: true });
        }
        if depth >= MAX_DEPTH || exact_end >= hi {
            return Ok(());
        }
        let g = self.g;
        for edge in &g.out[node] {
            if self.plain_only && matches!(edge.kind, Kind::Abbr | Kind::Fuzzy) {
                continue;
            }
            if *self.budget == 0 {
                return Ok(());
            }
            // Fast path: depth 0 on Dict uses O(1) first-syllable bucket ranges.
            if depth == 0 {
                if let Some(dict) = self.dict_first {
                    let next_info = info.add(edge.kind, g.numeric);
                    for &id in edge.ids.as_slice() {
                        if *self.budget == 0 {
                            return Ok(());
                        }
                        self.cancel.step()?;
                        *self.budget -= 1;
                        let (sub_lo, sub_hi) = dict.first_syllable_range(id);
                        if sub_lo < sub_hi {
                            self.walk(edge.to, 1, sub_lo, sub_hi, next_info)?;
                        }
                    }
                    continue;
                }
            }
            // Fast path: single-syllable edge avoids binary_search + extra syllable read.
            if let EdgeIds::Single(target) = edge.ids {
                self.cancel.step()?;
                *self.budget -= 1;
                let sub_lo = self.idx.lower(exact_end, hi, depth, target as i32);
                if sub_lo < hi && self.idx.syllable(sub_lo, depth) == target as i32 {
                    let up = self.idx.lower(sub_lo, hi, depth, target as i32 + 1);
                    self.walk(edge.to, depth + 1, sub_lo, up, info.add(edge.kind, g.numeric))?;
                }
                continue;
            }
            let ids = edge.ids.as_slice();
            let mut pos = exact_end;
            while pos < hi {
                if *self.budget == 0 {
                    return Ok(());
                }
                self.cancel.step()?;
                *self.budget -= 1;
                let value = self.idx.syllable(pos, depth) as u16;
                match ids.binary_search(&value) {
                    Ok(_) => {
                        let up = self.idx.lower(pos, hi, depth, value as i32 + 1);
                        self.walk(edge.to, depth + 1, pos, up, info.add(edge.kind, g.numeric))?;
                        pos = up;
                    }
                    Err(k) => {
                        if k >= ids.len() {
                            break;
                        }
                        pos = self.idx.lower(pos, hi, depth, ids[k] as i32);
                    }
                }
            }
        }
        Ok(())
    }
}

pub fn base_cost(score: u16) -> i32 {
    static LUT: OnceLock<[i32; 10_001]> = OnceLock::new();
    let table = LUT.get_or_init(|| {
        let ln_max = 10_000f64.ln();
        let mut t = [0i32; 10_001];
        for (s, slot) in t.iter_mut().enumerate() {
            *slot = ((ln_max - (s.max(1) as f64).ln()) * 100.0) as i32;
        }
        t
    });
    let idx = score as usize;
    if idx < table.len() {
        table[idx]
    } else {
        ((10_000f64.ln() - (score as f64).ln()) * 100.0) as i32
    }
}

struct CachedWalk {
    steps: usize,
    hits: Range<usize>,
}

type CompletedWalks = FxHashMap<(Src, usize), CachedWalk>;

struct QueryCache {
    mode: i32,
    flags: u32,
    raw: String,
    words_revision: u64,
    custom_words_revision: u64,
    hits: Vec<RangeHit>,
    can_reach_tail: Vec<bool>,
    completed: CompletedWalks,
}

pub struct Request<'a> {
    pub mode: i32,
    pub raw: &'a str,
    pub parts: Option<Vec<Part>>,
    pub prev2: &'a str,
    pub prev: &'a str,
    pub flags: u32,
    pub limit: usize,
    pub touch_corrections: &'a [(String, i32)],
}

pub struct Engine {
    pub dict: Dict,
    pub user: RwLock<UserModel>,
    names: OnceLock<NameIndex>,
    cache: Mutex<Option<Arc<QueryCache>>>,
}

impl Engine {
    pub fn new(backing: Backing) -> Result<Engine, String> {
        Ok(Engine {
            dict: Dict::open(backing)?,
            user: RwLock::new(UserModel::default()),
            names: OnceLock::new(),
            cache: Mutex::new(None),
        })
    }

    pub fn syllables(&self) -> &Syllables {
        &self.dict.syllables
    }

    pub(crate) fn user(&self) -> std::sync::RwLockReadGuard<'_, UserModel> {
        self.user.read().unwrap_or_else(|p| p.into_inner())
    }

    pub fn user_mut(&self) -> std::sync::RwLockWriteGuard<'_, UserModel> {
        self.user.write().unwrap_or_else(|p| p.into_inner())
    }

    fn names(&self) -> &NameIndex {
        self.names.get_or_init(|| NameIndex::new(&self.dict.syllables))
    }

    fn reading_of(&self, seq: &[u16]) -> String {
        seq.iter().map(|&s| self.dict.syllables.name(s)).collect::<Vec<_>>().join(" ")
    }

    pub fn query(&self, req: &Request) -> Vec<Candidate> {
        self.query_cancellable(req, &QueryCancellation::new(None)).unwrap_or_default()
    }

    pub(crate) fn query_cancellable(&self, req: &Request, cancel: &QueryCancellation<'_>) -> Result<Vec<Candidate>, QueryCancelled> {
        cancel.check()?;
        let candidates = if req.mode == MODE_ENGLISH {
            self.english(req.raw, req.limit, cancel)?
        } else {
            self.chinese(req, cancel)?
        };
        cancel.check()?;
        Ok(candidates)
    }

    fn options(&self, flags: u32, numeric: bool) -> Options {
        let mut opt = Options {
            full: flags & F_FULL != 0,
            abbreviations: flags & F_SHORT != 0,
            double_initials: flags & F_DOUBLE_INITIALS != 0,
            fuzzy_mask: if flags & F_FUZZY != 0 && !numeric { (flags >> FUZZY_SHIFT) & 0x1ff } else { 0 },
        };
        if !opt.full && !opt.abbreviations {
            opt.full = true;
        }
        opt
    }

    #[allow(clippy::too_many_arguments)]
    fn collect_hits(
        &self, g: &Graph<'_>, user: &UserModel, starts: &[usize], names: bool, complete_nine: bool,
        hits: &mut Vec<RangeHit>, cancel: &QueryCancellation<'_>,
        previous: Option<&QueryCache>, completed: &mut Option<CompletedWalks>,
    ) -> Result<(), QueryCancelled> {
        // Walk User first with an isolated budget so user words are never starved by Dict.
        let mut user_budget = USER_WALK_BUDGET;
        for &start in starts {
            if user_budget == 0 {
                break;
            }
            let mut w = Walker {
                idx: user,
                dict_first: None,
                g,
                src: Src::User,
                start,
                budget: &mut user_budget,
                out: hits,
                plain_only: start != g.start,
                complete_nine: false,
                cancel,
            };
            w.collect(previous, completed)?;
        }

        // Isolate g.start and mid-sentence budgets for Dict so long T9/fuzzy queries don't starve sentence arcs.
        let mut dict_start_budget = DICT_START_BUDGET;
        let mut dict_mid_budget = DICT_MID_BUDGET;
        for &start in starts {
            let budget = if start == g.start { &mut dict_start_budget } else { &mut dict_mid_budget };
            if *budget == 0 {
                continue;
            }
            let mut w = Walker {
                idx: &self.dict,
                dict_first: Some(&self.dict),
                g,
                src: Src::Dict,
                start,
                budget,
                out: hits,
                plain_only: start != g.start,
                complete_nine: complete_nine && start == g.start,
                cancel,
            };
            w.collect(previous, completed)?;
        }

        if names {
            let index = self.names();
            let mut names_budget = NAMES_WALK_BUDGET;
            for &start in starts {
                if names_budget == 0 {
                    break;
                }
                let mut w = Walker {
                    idx: index,
                    dict_first: None,
                    g,
                    src: Src::Names,
                    start,
                    budget: &mut names_budget,
                    out: hits,
                    plain_only: start != g.start,
                    complete_nine: false,
                    cancel,
                };
                w.collect(previous, completed)?;
            }
        }
        Ok(())
    }

    /// Expands a range hit without allocating temporary syllable sequences.
    #[allow(clippy::too_many_arguments)]
    fn for_each_expanded<'a>(
        &'a self, hit: &RangeHit, user: &'a UserModel, take: usize, ctx: &Context<'_>, bonus_rows: &[usize],
        cancel: &QueryCancellation<'_>,
        mut emit: impl FnMut(&'a str, SylBuf, i32, bool),
    ) -> Result<(), QueryCancelled> {
        let base_penalty = hit.info.penalty + WORD_COST;
        match hit.src {
            Src::Dict => {
                let end = hit.hi.min(hit.lo.saturating_add(take));
                let extra = bonus_rows.partition_point(|&row| row < end);
                let boosted = bonus_rows[extra..].iter().copied().take_while(|&row| row < hit.hi);
                for i in (hit.lo..end).chain(boosted) {
                    cancel.step()?;
                    let e = self.dict.entry(i);
                    let word = self.dict.word(e.word);
                    let seq = self.dict.entry_syllables_buf(&e);
                    let personal = user.bonus(word, seq.as_slice(), ctx.query_time);
                    let cost = base_cost(e.score) + base_penalty - personal - ctx.bonus(user, word);
                    emit(word, seq, cost, personal >= 3_000);
                }
            }
            Src::User => {
                for i in hit.lo..hit.hi {
                    cancel.step()?;
                    let w: &UserWord = user.word_at(i);
                    let seq = SylBuf::from_slice(&w.seq);
                    let personal = user.bonus(&w.word, seq.as_slice(), ctx.query_time);
                    let cost = base_cost(3_000) + base_penalty - personal - ctx.bonus(user, &w.word);
                    emit(&w.word, seq, cost, w.pinned);
                }
            }
            Src::Names => {
                let names = self.names();
                for i in hit.lo..hit.hi.min(hit.lo.saturating_add(take)) {
                    cancel.step()?;
                    let (word, seq_vec, score) = &names.rows[i];
                    let seq = SylBuf::from_slice(seq_vec);
                    let cost = base_cost(*score) + base_penalty - 500 - user.bonus(word, seq.as_slice(), ctx.query_time);
                    emit(word, seq, cost, false);
                }
            }
        }
        Ok(())
    }

    /// Keep rows whose learned reading or context can make them outrank their corpus frequency.
    fn bonus_rows<'a>(
        &self, personal: impl IntoIterator<Item = &'a str>, user: &'a UserModel, ctx: &Context<'a>,
        cancel: &QueryCancellation<'_>,
    ) -> Result<Vec<usize>, QueryCancelled> {
        let basic = ctx.collocations.keys().copied();
        let context = user.context_words(ctx.prev2, ctx.prev);
        let mut words = FxHashSet::default();
        for word in basic.chain(personal).chain(context) {
            cancel.step()?;
            if let Some(id) = self.dict.word_id(word) {
                words.insert(id);
            }
        }
        let mut rows = Vec::new();
        for row in words.into_iter().flat_map(|id| self.dict.entry_indices_of_word(id)) {
            cancel.step()?;
            rows.push(row);
        }
        cancel.check()?;
        rows.sort_unstable();
        Ok(rows)
    }

    fn frequency_take(&self, hit: &RangeHit, limit: usize, cancel: &QueryCancellation<'_>) -> Result<usize, QueryCancelled> {
        if hit.src != Src::Dict || hit.hi - hit.lo <= limit {
            return Ok(usize::MAX);
        }
        // Rounded frequency costs can tie at the cutoff. Keep those rows for the final tie-breakers.
        let mut end = hit.lo + limit;
        let cutoff = base_cost(self.dict.entry(end - 1).score);
        while end < hit.hi && base_cost(self.dict.entry(end).score) == cutoff {
            cancel.step()?;
            end += 1;
        }
        Ok(end - hit.lo)
    }

    fn chinese(&self, req: &Request, cancel: &QueryCancellation<'_>) -> Result<Vec<Candidate>, QueryCancelled> {
        struct RawCandidate {
            word: String,
            word_chars: u16,
            seq: SylBuf,
            reading: Option<String>,
            consumed: usize,
            tier: i32,
            cost: i32,
            segments: Vec<String>,
        }

        let numeric = req.mode == MODE_NINE;
        let limit = req.limit.max(1);
        let creation = req.flags & F_CREATION != 0;
        let constrained = req.parts.is_some();
        let opt = self.options(req.flags, numeric);
        let g = Graph::build(&self.dict.syllables, req.raw, numeric, req.parts.clone(), opt);
        let n = g.len;
        if g.start >= n {
            return Ok(Vec::new());
        }
        let user = self.user();
        cancel.check()?;
        let ctx = Context::new(req, &self.dict);
        let sentence = !creation && req.flags & F_SENTENCE != 0;
        let starts = if sentence { g.reachable() } else { vec![g.start] };
        let names = !creation && req.flags & F_NAMES != 0;
        let complete_nine = numeric && !creation && req.flags & (F_NINE_COMPLETION | F_FULL) == (F_NINE_COMPLETION | F_FULL);
        let cacheable = !constrained && !creation && !complete_nine;

        let previous = if cacheable {
            let guard = self.cache.lock().unwrap_or_else(|p| p.into_inner());
            guard.as_ref().filter(|cached| {
                cached.mode == req.mode
                    && cached.flags == req.flags
                    && cached.words_revision == user.words_revision
                    && cached.custom_words_revision == user.custom_words_revision
                    && g.raw.len() > cached.raw.len()
                    && g.raw.starts_with(&cached.raw)
            }).cloned()
        } else {
            None
        };
        let mut hits = Vec::new();
        let mut completed = cacheable.then(CompletedWalks::default);
        self.collect_hits(
            &g, &user, &starts, names, complete_nine, &mut hits, cancel,
            previous.as_deref(), &mut completed,
        )?;
        cancel.check()?;
        if let Some(completed) = completed {
            // Appending can change syllables near the end or edges that skip trailing apostrophes.
            // An unchanged walk stays outside that tail, including after following separator skips.
            let boundary = n.saturating_sub(MAX_SYLLABLE_LEN);
            let can_reach_tail = g.can_reach_tail(boundary);
            let next = Arc::new(QueryCache {
                mode: req.mode,
                flags: req.flags,
                raw: g.raw.clone(),
                words_revision: user.words_revision,
                custom_words_revision: user.custom_words_revision,
                hits: hits.clone(),
                can_reach_tail,
                completed,
            });
            let mut guard = self.cache.lock().unwrap_or_else(|p| p.into_inner());
            *guard = Some(next);
        }

        if numeric && !creation {
            // After two complete syllables, a partial third or fourth syllable is also an early completion.
            for hit in &mut hits {
                cancel.step()?;
                if hit.src == Src::Dict && hit.start == g.start && hit.end == n
                    && (2..=3).contains(&hit.info.full) && hit.info.abbr == 0
                    && hit.info.prefix == 1 && hit.info.automatic_prefix
                {
                    hit.completion = true;
                }
            }
        }
        let correct_spelling = req.flags & F_SPELLING_CORRECTION != 0;
        let corrections_enabled = !numeric && !constrained && !creation && req.flags & F_FULL != 0
            && (correct_spelling || !req.touch_corrections.is_empty())
            && (4..=32).contains(&req.raw.len()) && req.raw.bytes().all(|b| b.is_ascii_lowercase());

        let exact_single_syllable = !numeric
            && !creation
            && opt.full
            && g.start == 0
            && !constrained
            && g.raw.bytes().all(|b| b.is_ascii_lowercase())
            && self.dict.syllables.standard_id(&g.raw).is_some();
        let single_full_dict_hit = |hit: &RangeHit| {
            !numeric
                && !creation
                && opt.full
                && hit.src == Src::Dict
                && hit.start == g.start
                && hit.end == n
                && hit.info.tier() == TIER_EXACT_FULL
                && hit.info.full == 1
        };
        let better_candidate = |old_consumed: usize, old_tier: i32, old_cost: i32,
                                new_consumed: usize, new_tier: i32, new_cost: i32| {
            if new_consumed != old_consumed {
                return new_consumed > old_consumed;
            }
            if exact_single_syllable && new_consumed == n {
                let old_exact = old_tier <= TIER_EXACT_FULL;
                let new_exact = new_tier <= TIER_EXACT_FULL;
                if new_exact != old_exact {
                    return new_exact;
                }
            }
            new_cost < old_cost
        };

        let mut by_word: FxHashMap<String, usize> = FxHashMap::default();
        let mut list: Vec<RawCandidate> = Vec::new();
        let push_raw = |list: &mut Vec<RawCandidate>, by_word: &mut FxHashMap<String, usize>, c: RawCandidate| {
            if let Some(&i) = by_word.get(&c.word) {
                let old = &list[i];
                if better_candidate(old.consumed, old.tier, old.cost, c.consumed, c.tier, c.cost) {
                    list[i] = c;
                }
            } else {
                by_word.insert(c.word.clone(), list.len());
                list.push(c);
            }
        };

        let direct = || hits.iter().filter(|h| h.start == g.start && !h.completion);
        // Completion filters against all direct words, including those outside the returned list.
        let range_limit = if complete_nine { usize::MAX } else { limit.max(MIN_DIRECT_RANGE_LIMIT) };
        let bonus_rows = if direct().any(|h| h.src == Src::Dict && !single_full_dict_hit(h) && h.hi - h.lo > range_limit) {
            let personal = hits.iter().filter(|hit| hit.start == g.start && hit.src == Src::User)
                .flat_map(|hit| hit.lo..hit.hi).map(|row| user.word_at(row).word.as_str());
            self.bonus_rows(personal, &user, &ctx, cancel)?
        } else {
            Vec::new()
        };
        for hit in direct() {
            let hit_limit = if single_full_dict_hit(hit) { usize::MAX } else { range_limit };
            let take = self.frequency_take(hit, hit_limit, cancel)?;
            let tier_base = hit.info.tier();
            let end = hit.end;
            self.for_each_expanded(hit, &user, take, &ctx, &bonus_rows, cancel, |word, seq, cost, pinned| {
                let tier = if pinned { TIER_USER } else { tier_base };
                if let Some(&i) = by_word.get(word) {
                    let old = &list[i];
                    if better_candidate(old.consumed, old.tier, old.cost, end, tier, cost) {
                        list[i].seq = seq;
                        list[i].reading = None;
                        list[i].consumed = end;
                        list[i].tier = tier;
                        list[i].cost = cost;
                        list[i].segments.clear();
                    }
                } else {
                    let word_str = word.to_string();
                    let word_chars = word_str.chars().count() as u16;
                    by_word.insert(word_str.clone(), list.len());
                    list.push(RawCandidate {
                        word: word_str,
                        word_chars,
                        seq,
                        reading: None,
                        consumed: end,
                        tier,
                        cost,
                        segments: Vec::new(),
                    });
                }
            })?;
        }

        if sentence {
            let basic_ctx = req.flags & F_BASIC_CONTEXT != 0;
            if let Some(c) = self.compose(&g, &hits, &user, &ctx, basic_ctx, cancel)? {
                let duplicate = by_word.get(&c.word).is_some_and(|&i| list[i].consumed == n);
                if !duplicate {
                    let word_chars = c.word.chars().count() as u16;
                    push_raw(&mut list, &mut by_word, RawCandidate {
                        word: c.word,
                        word_chars,
                        seq: SylBuf::empty(),
                        reading: Some(c.reading),
                        consumed: c.consumed,
                        tier: c.tier,
                        cost: c.cost,
                        segments: c.segments,
                    });
                }
            }
        }

        if !constrained && !creation {
            let lower = &g.raw;
            for (word, pinned) in user.aliases(lower, numeric) {
                cancel.step()?;
                let word_chars = word.chars().count() as u16;
                push_raw(&mut list, &mut by_word, RawCandidate {
                    word: word.clone(),
                    word_chars,
                    seq: SylBuf::empty(),
                    reading: Some(String::new()),
                    consumed: n,
                    tier: if *pinned { TIER_USER } else { TIER_EXACT_SHORT },
                    cost: if *pinned { -3_000 } else { 0 },
                    segments: Vec::new(),
                });
            }
            let letters = lower.len() >= 2 && lower.bytes().all(|b| b.is_ascii_lowercase());
            if !numeric && letters && req.flags & F_MIXED_ENGLISH != 0 {
                let full_chinese = list.iter().any(|c| c.consumed == n);
                for c in self.alpha_matches(lower, &user, !full_chinese, ctx.query_time, cancel)? {
                    cancel.step()?;
                    let word_chars = c.word.chars().count() as u16;
                    push_raw(&mut list, &mut by_word, RawCandidate {
                        word: c.word,
                        word_chars,
                        seq: SylBuf::empty(),
                        reading: Some(c.reading),
                        consumed: c.consumed,
                        tier: c.tier,
                        cost: c.cost,
                        segments: c.segments,
                    });
                }
            }
        }

        cancel.check()?;
        list.sort_by(|a, b| {
            let fa = a.consumed == n;
            let fb = b.consumed == n;
            fb.cmp(&fa)
                .then_with(|| if fa { std::cmp::Ordering::Equal } else { b.consumed.cmp(&a.consumed) })
                .then(a.cost.cmp(&b.cost))
                .then(a.word_chars.cmp(&b.word_chars))
                .then(a.word.cmp(&b.word))
        });
        // Prevent high-frequency prefix/abbreviation completions from evicting low-frequency
        // exact single-syllable characters past the candidate limit, while preserving the
        // relative order of all kept candidates.
        if exact_single_syllable && list.len() > limit {
            let exact_count = list
                .iter()
                .filter(|c| c.consumed == n && c.tier <= TIER_EXACT_FULL)
                .count();
            let mut non_exact_budget = limit.saturating_sub(exact_count);
            let mut exact_budget = limit;
            list.retain(|c| {
                if c.consumed == n && c.tier <= TIER_EXACT_FULL {
                    if exact_budget > 0 {
                        exact_budget -= 1;
                        true
                    } else {
                        false
                    }
                } else if non_exact_budget > 0 {
                    non_exact_budget -= 1;
                    true
                } else {
                    false
                }
            });
        }
        cancel.check()?;
        let mut final_list: Vec<Candidate> = Vec::with_capacity(limit.min(list.len()) + CORRECTION_LIMIT);
        let materialize = |raw: RawCandidate| Candidate {
            reading: raw.reading.unwrap_or_else(|| self.reading_of(raw.seq.as_slice())),
            word: raw.word,
            consumed: raw.consumed,
            tier: raw.tier,
            cost: raw.cost,
            segments: raw.segments,
        };

        let run_corrections = corrections_enabled && !hits.iter().any(|hit| {
            hit.src == Src::Dict && hit.start == g.start && hit.end == n
                && hit.info.tier() == TIER_EXACT_FULL && self.dict.entry(hit.lo).score >= COMMON_SCORE
        });

        if run_corrections {
            let existing_full: FxHashSet<&str> = list
                .iter()
                .filter(|c| c.consumed == n)
                .map(|c| c.word.as_str())
                .collect();
            let corrections = self.corrected(req, &user, &ctx, &existing_full, correct_spelling, cancel)?;
            if !corrections.is_empty() {
                let words: FxHashSet<&str> = corrections.iter().map(|c| c.word.as_str()).collect();
                let mut inserted = false;
                for raw in list {
                    if words.contains(raw.word.as_str()) {
                        continue;
                    }
                    if !inserted && raw.consumed < n {
                        final_list.extend(corrections.iter().cloned());
                        inserted = true;
                        if final_list.len() >= limit {
                            break;
                        }
                    }
                    final_list.push(materialize(raw));
                    if final_list.len() >= limit {
                        break;
                    }
                }
                if !inserted && final_list.len() < limit {
                    final_list.extend(corrections);
                }
            } else {
                for raw in list.into_iter().take(limit) {
                    final_list.push(materialize(raw));
                }
            }
        } else {
            for raw in list.into_iter().take(limit) {
                final_list.push(materialize(raw));
            }
        }

        if complete_nine {
            let slots = (limit - usize::from(!final_list.is_empty())).min(NINE_COMPLETION_LIMIT);
            let completions = self.nine_completions(&hits, &user, &ctx, &by_word, slots, cancel)?;
            final_list.truncate(limit - completions.len());
            final_list.extend(completions);
        }
        final_list.truncate(limit);
        Ok(final_list)
    }

    fn corrected(
        &self, req: &Request, user: &UserModel, ctx: &Context, existing: &FxHashSet<&str>, spelling: bool,
        cancel: &QueryCancellation<'_>,
    ) -> Result<Vec<Candidate>, QueryCancelled> {
        cancel.check()?;
        let variants = crate::correction::variants(&self.dict.syllables, req.raw, spelling, req.touch_corrections);
        if variants.is_empty() {
            return Ok(Vec::new());
        }
        let mut best: FxHashMap<String, Candidate> = FxHashMap::default();
        let opt = Options { full: true, abbreviations: false, double_initials: false, fuzzy_mask: 0 };
        let mut budget = 20_000;
        let mut hits = Vec::new();
        for (variant, penalty) in variants {
            cancel.check()?;
            let g = Graph::build(&self.dict.syllables, &variant, false, None, opt);
            hits.clear();
            let mut walker = Walker {
                idx: &self.dict,
                dict_first: Some(&self.dict),
                g: &g,
                src: Src::Dict,
                start: g.start,
                budget: &mut budget,
                out: &mut hits,
                plain_only: true,
                complete_nine: false,
                cancel,
            };
            walker.walk(g.start, 0, 0, self.dict.size(), PathInfo::default())?;
            for hit in hits.iter().filter(|h| h.end == g.len && h.info.full >= 2 && h.info.prefix == 0) {
                for i in hit.lo..hit.hi.min(hit.lo + 4) {
                    cancel.step()?;
                    let entry = self.dict.entry(i);
                    let word = self.dict.word(entry.word);
                    if entry.score < 1_000 || existing.contains(word) {
                        continue;
                    }
                    let seq = self.dict.entry_syllables_buf(&entry);
                    let cost = base_cost(entry.score) + WORD_COST + penalty
                        - user.bonus(word, seq.as_slice(), ctx.query_time) - ctx.bonus(user, word);
                    if best.get(word).is_none_or(|old| cost < old.cost) {
                        best.insert(word.to_string(), Candidate {
                            word: word.to_string(),
                            reading: self.reading_of(seq.as_slice()),
                            consumed: req.raw.len(),
                            tier: TIER_FUZZY,
                            cost,
                            segments: Vec::new(),
                        });
                    }
                }
            }
            if budget == 0 {
                break;
            }
        }
        let mut found: Vec<Candidate> = best.into_values().collect();
        cancel.check()?;
        found.sort_by(|a, b| a.cost.cmp(&b.cost).then(a.word.cmp(&b.word)));
        found.truncate(CORRECTION_LIMIT);
        Ok(found)
    }

    fn nine_completions(
        &self, hits: &[RangeHit], user: &UserModel, ctx: &Context, existing: &FxHashMap<String, usize>, limit: usize,
        cancel: &QueryCancellation<'_>,
    ) -> Result<Vec<Candidate>, QueryCancelled> {
        if limit == 0 {
            return Ok(Vec::new());
        }
        let mut best: Vec<Candidate> = Vec::with_capacity(limit + 1);
        for hit in hits.iter().filter(|hit| hit.completion) {
            for i in hit.lo..hit.hi {
                cancel.step()?;
                let entry = self.dict.entry(i);
                if !(3..=4).contains(&entry.len) || entry.score < COMMON_SCORE {
                    continue;
                }
                let word = self.dict.word(entry.word);
                if existing.contains_key(word) || word.chars().count() != entry.len as usize {
                    continue;
                }
                let seq = self.dict.entry_syllables_buf(&entry);
                let cost = base_cost(entry.score) + WORD_COST + hit.info.penalty
                    - user.bonus(word, seq.as_slice(), ctx.query_time) - ctx.bonus(user, word);
                if let Some(old) = best.iter().position(|c| c.word == word) {
                    if best[old].cost <= cost {
                        continue;
                    }
                    best.remove(old);
                } else if best.len() == limit && best.last().is_some_and(|c| (cost, word) >= (c.cost, c.word.as_str())) {
                    continue;
                }
                best.push(Candidate {
                    word: word.to_string(),
                    reading: self.reading_of(seq.as_slice()),
                    consumed: hit.end,
                    tier: TIER_PREFIX,
                    cost,
                    segments: Vec::new(),
                });
                best.sort_by(|a, b| a.cost.cmp(&b.cost).then(a.word.cmp(&b.word)));
                best.truncate(limit);
            }
        }
        Ok(best)
    }

    /// Best multi-word sentence covering the whole input, using beam search with static + user context.
    fn compose(
        &self, g: &Graph<'_>, hits: &[RangeHit], user: &UserModel, ctx: &Context, basic_ctx: bool,
        cancel: &QueryCancellation<'_>,
    ) -> Result<Option<Candidate>, QueryCancelled> {
        let n = g.len;
        let mut hits_by_start = vec![Vec::new(); n + 1];
        for hit in hits {
            cancel.step()?;
            // Single words covering the whole input already enter the direct candidate list.
            if hit.start == g.start && hit.end == n {
                continue;
            }
            if hit.completion || hit.info.abbr > 0 || hit.info.fuzzy > 0 || (hit.info.prefix > 0 && hit.end != n) {
                continue;
            }
            hits_by_start[hit.start].push(hit);
        }

        struct SpanWord<'a> {
            word: &'a str,
            seq: SylBuf,
            cost: i32,
        }

        struct BeamState<'a> {
            cost: i32,
            prev_node: usize,
            prev_beam: usize,
            word: &'a str,
            seq: SylBuf,
        }
        let mut beam: Vec<Vec<BeamState>> = (0..=n).map(|_| Vec::new()).collect();
        beam[g.start].push(BeamState {
            cost: 0,
            prev_node: usize::MAX,
            prev_beam: usize::MAX,
            word: "",
            seq: SylBuf::empty(),
        });

        for (node, node_hits) in hits_by_start.iter_mut().enumerate().take(n).skip(g.start) {
            let (current, future) = beam.split_at_mut(node + 1);
            let states = &current[node];
            if states.is_empty() || node_hits.is_empty() {
                continue;
            }

            node_hits.sort_unstable_by_key(|hit| hit.end);
            let mut ranges = Vec::with_capacity(node_hits.len());
            let mut truncated = FxHashSet::default();
            let mut personal = FxHashSet::default();
            for &hit in node_hits.iter() {
                cancel.step()?;
                let take = self.frequency_take(hit, COMPOSE_SPAN_LIMIT, cancel)?;
                ranges.push((hit, take));
                if take < hit.hi - hit.lo && truncated.insert(hit.lo) {
                    // The user walk has its own budget. Find learned rows for this exact dictionary reading.
                    let seq = self.dict.entry_syllables_buf(&self.dict.entry(hit.lo));
                    for word in user.words_with_reading(seq.as_slice()) {
                        cancel.step()?;
                        personal.insert(word);
                    }
                }
            }

            let mut top: Vec<SpanWord> = Vec::with_capacity(COMPOSE_SPAN_LIMIT + 1);
            for (si, state) in states.iter().enumerate() {
                let following;
                let rank_ctx = if node == g.start {
                    ctx
                } else {
                    following = Context::for_previous("", state.word, ctx.query_time, &self.dict, basic_ctx);
                    &following
                };
                let bonus_rows = if truncated.is_empty() {
                    Vec::new()
                } else {
                    self.bonus_rows(personal.iter().copied(), user, rank_ctx, cancel)?
                };

                for span in ranges.chunk_by(|a, b| a.0.end == b.0.end) {
                    top.clear();
                    for &(hit, take) in span {
                        // A lower-frequency row can compete if it receives a personal or contextual bonus.
                        self.for_each_expanded(hit, user, take, rank_ctx, &bonus_rows, cancel, |word, seq, cost, _| {
                            let cost = if hit.src == Src::Names { cost - rank_ctx.bonus(user, word) } else { cost };
                            if let Some(slot) = top.iter_mut().find(|slot| slot.word == word) {
                                if (cost, seq.as_slice()) >= (slot.cost, slot.seq.as_slice()) {
                                    return;
                                }
                                *slot = SpanWord { word, seq, cost };
                            } else {
                                if top.len() == COMPOSE_SPAN_LIMIT {
                                    let last = &top[COMPOSE_SPAN_LIMIT - 1];
                                    if (cost, word) >= (last.cost, last.word) {
                                        return;
                                    }
                                }
                                top.push(SpanWord { word, seq, cost });
                            }
                            top.sort_unstable_by_key(|slot| (slot.cost, slot.word));
                            top.truncate(COMPOSE_SPAN_LIMIT);
                        })?;
                    }

                    let target = &mut future[span[0].0.end - node - 1];
                    for choice in &top {
                        let next = BeamState {
                            cost: state.cost + choice.cost,
                            prev_node: node,
                            prev_beam: si,
                            word: choice.word,
                            seq: choice.seq,
                        };
                        if let Some(slot) = target.iter_mut().find(|slot| slot.word == next.word) {
                            if next.cost >= slot.cost {
                                continue;
                            }
                            *slot = next;
                        } else {
                            if target.len() == COMPOSE_BEAM_WIDTH {
                                let last = &target[COMPOSE_BEAM_WIDTH - 1];
                                if (next.cost, next.word) >= (last.cost, last.word) {
                                    continue;
                                }
                            }
                            target.push(next);
                        }
                        target.sort_unstable_by_key(|slot| (slot.cost, slot.word));
                        target.truncate(COMPOSE_BEAM_WIDTH);
                    }
                }
            }
        }

        let Some(best_end) = beam[n].first() else { return Ok(None) };
        let total = best_end.cost;
        let mut words = Vec::new();
        let mut readings = Vec::new();
        let mut node = n;
        let mut bi = 0usize;
        while node != g.start {
            let state = &beam[node][bi];
            if state.prev_node == usize::MAX {
                return Ok(None);
            }
            words.push(state.word.to_string());
            readings.push(self.reading_of(state.seq.as_slice()));
            let prev_node = state.prev_node;
            bi = state.prev_beam;
            node = prev_node;
        }
        words.reverse();
        readings.reverse();
        Ok(Some(Candidate {
            word: words.concat(),
            reading: readings.join(" "),
            consumed: n,
            tier: TIER_COMPOSED,
            cost: total,
            segments: words,
        }))
    }

    fn alpha_matches(
        &self, code: &str, user: &UserModel, completions: bool, query_time: u64, cancel: &QueryCancellation<'_>,
    ) -> Result<Vec<Candidate>, QueryCancelled> {
        let mut out = Vec::new();
        let range = self.dict.alpha_prefix(code);
        let mut prefix_rows = Vec::new();
        for i in range {
            cancel.step()?;
            let (code_id, word_id, score, kind) = self.dict.alpha(i);
            if kind == ALPHA_EMOJI {
                continue;
            }
            let row_code = self.dict.word(code_id);
            let word = self.dict.word(word_id).to_string();
            let english = kind == ALPHA_ENGLISH;
            let personal = user.english_bonus_lower(row_code, query_time);
            if row_code == code {
                let extra = if english { 380 } else { 120 };
                out.push(Candidate {
                    consumed: code.len(),
                    tier: TIER_EXACT_FULL,
                    cost: base_cost(score) + extra + WORD_COST - personal,
                    reading: String::new(),
                    segments: Vec::new(),
                    word,
                });
            } else if completions {
                prefix_rows.push(Candidate {
                    consumed: code.len(),
                    tier: TIER_PREFIX,
                    cost: base_cost(score) + 500 + WORD_COST - personal,
                    reading: String::new(),
                    segments: Vec::new(),
                    word,
                });
            }
        }
        cancel.check()?;
        prefix_rows.sort_by_key(|c| c.cost);
        prefix_rows.truncate(16);
        out.extend(prefix_rows);
        Ok(out)
    }

    /// Emoji keywords use the selected Chinese word, never its spelling.
    pub fn emoji_suggestions(&self, word: &str) -> Vec<String> {
        let lookup = |term: &str| {
            if term.is_empty() {
                return Vec::new();
            }
            self.dict.alpha_prefix(term)
                .map(|i| self.dict.alpha(i))
                .take_while(|(code, _, _, _)| self.dict.word(*code) == term)
                .filter(|(_, _, _, kind)| *kind == ALPHA_EMOJI)
                .map(|(_, id, _, _)| self.dict.word(id).to_string())
                .collect()
        };
        let exact = lookup(word);
        if !exact.is_empty() {
            return exact;
        }
        let stem = word.trim_end_matches(['的', '了', '啊', '呀']);
        let stem = ["非常", "特别", "真的", "好", "很", "太", "真", "超"]
            .into_iter()
            .find_map(|prefix| stem.strip_prefix(prefix).filter(|rest| rest.chars().count() >= 2))
            .unwrap_or(stem);
        if stem != word && stem.chars().count() >= 2 { lookup(stem) } else { Vec::new() }
    }

    fn english(&self, raw: &str, limit: usize, cancel: &QueryCancellation<'_>) -> Result<Vec<Candidate>, QueryCancelled> {
        let code: String = raw.chars().filter(|c| c.is_ascii_alphabetic()).collect::<String>().to_ascii_lowercase();
        if code.is_empty() {
            return Ok(Vec::new());
        }
        let user = self.user();
        cancel.check()?;
        let query_time = now();
        let mut best: FxHashMap<String, Candidate> = FxHashMap::default();
        let mut add = |c: Candidate| {
            let key = c.word.to_lowercase();
            if best.get(&key).is_none_or(|old| c.cost < old.cost) {
                best.insert(key, c);
            }
        };
        for i in self.dict.alpha_prefix(&code) {
            cancel.step()?;
            let (code_id, word_id, score, kind) = self.dict.alpha(i);
            if kind != ALPHA_ENGLISH {
                continue;
            }
            let row_code = self.dict.word(code_id);
            let word = self.dict.word(word_id).to_string();
            let extra = if row_code == code { 0 } else { 200 + 10 * (row_code.len() - code.len()) as i32 };
            add(Candidate {
                cost: base_cost(score) + extra - user.english_bonus_lower(row_code, query_time),
                consumed: raw.len(),
                tier: if extra == 0 { TIER_EXACT_FULL } else { TIER_PREFIX },
                reading: String::new(),
                segments: Vec::new(),
                word,
            });
        }
        for (word, bonus) in user.english_prefix(&code, query_time) {
            cancel.step()?;
            let exact = word.to_lowercase() == code;
            add(Candidate {
                cost: base_cost(3_000) + if exact { 0 } else { 200 } - bonus,
                consumed: raw.len(),
                tier: if exact { TIER_EXACT_FULL } else { TIER_PREFIX },
                reading: String::new(),
                segments: Vec::new(),
                word,
            });
        }
        let mut list: Vec<Candidate> = best.into_values().collect();
        cancel.check()?;
        list.sort_by(|a, b| a.cost.cmp(&b.cost).then(a.word.len().cmp(&b.word.len())));
        list.truncate(limit.max(1));
        Ok(list)
    }

    /// Next-word suggestions after the last committed words.
    pub fn predict(&self, prev2: &str, prev: &str, limit: usize, basic: bool) -> Vec<String> {
        let user = self.user();
        let mut scores: FxHashMap<String, u32> = FxHashMap::default();
        for (word, weight) in user.predict(prev2, prev) {
            *scores.entry(word).or_default() += weight * 100;
        }
        if basic {
            if let Some(id) = self.dict.word_id(prev) {
                for (next, weight) in self.dict.collocations(id) {
                    *scores.entry(self.dict.word(next).to_string()).or_default() += weight;
                }
            }
        }
        let mut list: Vec<(String, u32)> = scores.into_iter().collect();
        list.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
        list.into_iter().take(limit).map(|(w, _)| w).collect()
    }

    /// Readable segmentations of the input, most plausible first.
    pub fn paths(&self, raw: &str, numeric: bool, double_initials: bool, limit: usize) -> Vec<Vec<String>> {
        let opt = Options { full: true, abbreviations: true, double_initials, fuzzy_mask: 0 };
        let g = Graph::build(&self.dict.syllables, raw, numeric, None, opt);
        let mut sequences: Vec<Vec<&Edge<'_>>> = Vec::new();
        let mut steps = 0usize;
        let mut stack: Vec<&Edge<'_>> = Vec::new();
        enumerate(&g, g.start, &mut stack, &mut sequences, &mut steps);
        let non_full = |s: &Vec<&Edge<'_>>| s.iter().filter(|e| e.kind != Kind::Full).count();
        sequences.sort_by_key(|s| (non_full(s), s.len()));
        let mut out: Vec<(bool, usize, u16, Vec<String>)> = Vec::new();
        let syl = &self.dict.syllables;
        for (order, seq) in sequences.iter().take(48).enumerate() {
            if !numeric {
                let readings = seq
                    .iter()
                    .map(|e| if e.kind == Kind::Full && e.ids.len() == 1 {
                        syl.name(e.ids.first()).to_string()
                    } else {
                        e.text(&g.raw).to_string()
                    })
                    .collect();
                out.push((true, order, 0, readings));
                continue;
            }
            let supported = self.best_sequences(seq, 3);
            let truncate = |e: &Edge<'_>, id: u16| {
                let name = syl.name(id);
                let text = e.text(&g.raw);
                if e.kind == Kind::Full { name.to_string() } else { name[..text.len().min(name.len())].to_string() }
            };
            if supported.is_empty() {
                let readings = seq.iter().map(|e| truncate(e, e.ids.first())).collect();
                out.push((false, order, 0, readings));
            }
            for (score, ids) in supported {
                let readings = seq.iter().zip(ids.iter()).map(|(e, &id)| truncate(e, id)).collect();
                out.push((true, order, score, readings));
            }
        }
        out.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)).then(b.2.cmp(&a.2)));
        let mut result: Vec<Vec<String>> = Vec::new();
        for (_, _, _, readings) in out {
            if !result.contains(&readings) {
                result.push(readings);
            }
            if result.len() >= limit {
                break;
            }
        }
        result
    }

    /// Up to `take` corpus syllable sequences matching a fixed edge sequence.
    fn best_sequences(&self, edges: &[&Edge<'_>], take: usize) -> Vec<(u16, Vec<u16>)> {
        let mut found: Vec<(u16, usize)> = Vec::new();
        let mut budget = 20_000usize;
        self.walk_fixed(edges, 0, 0, self.dict.size(), &mut found, &mut budget);
        found.sort_by_key(|a| std::cmp::Reverse(a.0));
        found
            .into_iter()
            .take(take)
            .map(|(score, row)| (score, self.dict.entry_syllables(&self.dict.entry(row))))
            .collect()
    }

    fn walk_fixed(&self, edges: &[&Edge<'_>], depth: usize, lo: usize, hi: usize, found: &mut Vec<(u16, usize)>, budget: &mut usize) {
        let exact_end = if depth == 0 { lo } else { self.dict.lower(lo, hi, depth, 0) };
        if depth == edges.len() {
            if exact_end > lo {
                found.push((self.dict.entry(lo).score, lo));
            }
            return;
        }
        let edge = edges[depth];
        if depth == 0 {
            for &id in edge.ids.as_slice() {
                if *budget == 0 {
                    return;
                }
                *budget -= 1;
                let (sub_lo, sub_hi) = self.dict.first_syllable_range(id);
                if sub_lo < sub_hi {
                    self.walk_fixed(edges, 1, sub_lo, sub_hi, found, budget);
                }
            }
            return;
        }
        let ids = edge.ids.as_slice();
        let mut pos = exact_end;
        while pos < hi && *budget > 0 {
            *budget -= 1;
            let value = self.dict.syllable(pos, depth) as u16;
            match ids.binary_search(&value) {
                Ok(_) => {
                    let up = self.dict.lower(pos, hi, depth, value as i32 + 1);
                    self.walk_fixed(edges, depth + 1, pos, up, found, budget);
                    pos = up;
                }
                Err(k) => {
                    if k >= ids.len() {
                        break;
                    }
                    pos = self.dict.lower(pos, hi, depth, ids[k] as i32);
                }
            }
        }
    }

    /// Readings selectable for one typed span.
    pub fn syllable_choices(&self, raw: &str, numeric: bool) -> Vec<String> {
        let syl = &self.dict.syllables;
        let raw = raw.to_ascii_lowercase();
        let mut out: Vec<String> = Vec::new();
        if numeric {
            for &id in syl.t9_full(&raw) {
                out.push(syl.name(id).to_string());
            }
            for prefix in syl.entered_from_digits(&raw) {
                if !out.contains(&prefix) {
                    out.push(prefix);
                }
            }
        } else {
            if let Some(id) = syl.standard_id(&raw) {
                out.push(syl.name(id).to_string());
            }
            for &id in syl.letter_prefix(&raw) {
                let name = syl.name(id).to_string();
                if !out.contains(&name) {
                    out.push(name);
                }
            }
        }
        out
    }

    /// Every known reading of a word, most common first.
    pub fn readings(&self, word: &str) -> Vec<String> {
        let mut out: Vec<String> = Vec::new();
        if let Some(id) = self.dict.word_id(word) {
            for e in self.dict.entries_of_word(id) {
                let r = self.reading_of(&self.dict.entry_syllables(&e));
                if !out.contains(&r) {
                    out.push(r);
                }
            }
        }
        for r in self.user().learned_readings(&self.dict.syllables, word) {
            if !out.contains(&r) {
                out.push(r);
            }
        }
        if out.is_empty() {
            // Compose from single characters.
            let mut parts = Vec::new();
            for ch in word.chars() {
                let Some(id) = self.dict.word_id(&ch.to_string()) else { return out };
                let Some(e) = self.dict.entries_of_word(id).into_iter().next() else { return out };
                if e.len != 1 {
                    return out;
                }
                parts.push(self.dict.syllables.name(self.dict.seq_syllable(&e, 0)).to_string());
            }
            if !parts.is_empty() {
                out.push(parts.join(" "));
            }
        }
        out
    }

    /// Syllable ids for a reading of `word`, falling back to the corpus.
    pub fn resolve_reading(&self, reading: &str, word: &str) -> Option<Vec<u16>> {
        let chars = word.chars().count();
        let parsed: Option<Vec<u16>> = split_reading(reading).iter().map(|s| self.dict.syllables.id(s)).collect();
        if let Some(seq) = parsed.filter(|s| !s.is_empty() && s.len() == chars) {
            return Some(seq);
        }
        if let Ok(canonical) = canonical_reading(&self.dict.syllables, reading, word) {
            let seq: Option<Vec<u16>> = canonical.split('\'').map(|s| self.dict.syllables.id(s)).collect();
            if let Some(seq) = seq.filter(|s| s.len() == chars) {
                return Some(seq);
            }
        }
        let first = self.readings(word).into_iter().next()?;
        split_reading(&first).iter().map(|s| self.dict.syllables.id(s)).collect()
    }

    pub fn learn(&self, reading: &str, word: &str, english: bool) -> bool {
        if word.is_empty() {
            return false;
        }
        if english || word.chars().all(|c| c.is_ascii_alphabetic()) {
            self.user_mut().learn_english(word);
            return true;
        }
        match self.resolve_reading(reading, word) {
            Some(seq) => {
                self.user_mut().learn(word, seq);
                true
            }
            None => false,
        }
    }
}

fn enumerate<'g, 'a>(g: &'g Graph<'a>, node: usize, stack: &mut Vec<&'g Edge<'a>>, out: &mut Vec<Vec<&'g Edge<'a>>>, steps: &mut usize) {
    if *steps > 4_000 || out.len() >= 256 {
        return;
    }
    *steps += 1;
    if node == g.len {
        if !stack.is_empty() {
            out.push(stack.clone());
        }
        return;
    }
    for e in &g.out[node] {
        if e.kind == Kind::Fuzzy {
            continue;
        }
        stack.push(e);
        enumerate(g, e.to, stack, out, steps);
        stack.pop();
    }
}

/// Previous-word evidence for ranking a candidate or the next word in a sentence.
struct Context<'a> {
    prev2: &'a str,
    prev: &'a str,
    query_time: u64,
    collocations: FxHashMap<&'a str, i32>,
}

impl<'a> Context<'a> {
    fn new(req: &Request<'a>, dict: &'a Dict) -> Self {
        Self::for_previous(req.prev2, req.prev, now(), dict, req.flags & F_BASIC_CONTEXT != 0)
    }

    fn for_previous(prev2: &'a str, prev: &'a str, query_time: u64, dict: &'a Dict, basic: bool) -> Self {
        let collocations = if basic && !prev.is_empty() {
            dict.word_id(prev).map_or_else(FxHashMap::default, |id| {
                dict.collocations(id).into_iter()
                    .map(|(next, weight)| (dict.word(next), weight.min(300) as i32))
                    .collect()
            })
        } else {
            FxHashMap::default()
        };
        Self { prev2, prev, query_time, collocations }
    }

    fn bonus(&self, user: &UserModel, word: &str) -> i32 {
        if self.prev.is_empty() {
            return 0;
        }
        let personal = user.context_bonus(self.prev2, self.prev, word);
        let basic = self.collocations.get(word).copied().unwrap_or(0);
        (personal + basic).min(700)
    }
}

/// Normalizes a user-entered reading for `word` to `syl'syl'...`.
/// Errors carry a user-facing message.
pub fn canonical_reading(syl: &Syllables, value: &str, word: &str) -> Result<String, String> {
    let normalized: String = value
        .trim()
        .to_lowercase()
        .replace("u:", "v")
        .replace('ü', "v")
        .replace(['’', '·'], "'")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join("'");
    let valid = !normalized.is_empty()
        && normalized.split('\'').all(|t| !t.is_empty() && t.bytes().all(|b| b.is_ascii_lowercase()));
    if !valid {
        return Err("请填写完整拼音，音节用空格或 ' 分隔".into());
    }
    if normalized.contains('\'') {
        let mut readings = Vec::new();
        for token in normalized.split('\'') {
            let Some(id) = syl.standard_id(token) else {
                return Err(format!("音节「{token}」不完整或不合法"));
            };
            readings.push(syl.name(id));
        }
        return Ok(readings.join("'"));
    }
    let paths = split_all(syl, &normalized, 32);
    let length = word.chars().count();
    let matching: Vec<&Vec<String>> = paths.iter().filter(|p| p.len() == length).collect();
    if matching.len() == 1 {
        return Ok(matching[0].join("'"));
    }
    if matching.is_empty() && paths.len() == 1 {
        return Ok(paths[0].join("'"));
    }
    if paths.is_empty() {
        return Err(format!("音节「{normalized}」不完整或不合法"));
    }
    Err("拼音有多种切分，请用空格明确音节，例如 xi an".into())
}

/// Every split of `letters` into complete standard syllables.
pub fn split_all(syl: &Syllables, letters: &str, limit: usize) -> Vec<Vec<String>> {
    let n = letters.len();
    // ways[i] = splits of letters[i..]
    let mut ways: Vec<Vec<Vec<String>>> = vec![Vec::new(); n + 1];
    ways[n].push(Vec::new());
    for i in (0..n).rev() {
        let mut here = Vec::new();
        for len in 1..=crate::syllable::MAX_SYLLABLE_LEN.min(n - i) {
            let piece = &letters[i..i + len];
            let Some(id) = syl.standard_id(piece) else {
                continue;
            };
            for rest in &ways[i + len] {
                if here.len() >= limit {
                    break;
                }
                let mut path = vec![syl.name(id).to_string()];
                path.extend(rest.iter().cloned());
                here.push(path);
            }
        }
        ways[i] = here;
    }
    std::mem::take(&mut ways[0])
}

/// Parses the editor constraint: parts joined by U+001F, each
/// `<kind><start>,<end>,<text>` with kind `~` raw, `=` locked reading,
/// `^` locked prefix, `|` comma-separated alternatives.
pub fn parse_parts(encoded: &str) -> Option<Vec<Part>> {
    if encoded.is_empty() {
        return None;
    }
    let mut parts = Vec::new();
    for item in encoded.split('\u{1f}') {
        let mut chars = item.chars();
        let kind = chars.next()?;
        let rest = chars.as_str();
        let mut fields = rest.splitn(3, ',');
        let start: usize = fields.next()?.parse().ok()?;
        let end: usize = fields.next()?.parse().ok()?;
        let text = fields.next().unwrap_or("").to_string();
        parts.push(match kind {
            '~' => Part::Raw { start, end },
            '=' => Part::Locked { start, end, reading: text },
            '^' => Part::LockedPrefix { start, end, prefix: text },
            '|' => Part::Alternatives { start, end, readings: text.split(',').map(String::from).collect() },
            _ => return None,
        });
    }
    Some(parts)
}

/// Custom words from the word manager: flat rows of
/// (word, readings separated by ';', aliases separated by ';', pinned "1"/"0").
pub(crate) fn custom_words(syl: &Syllables, rows: &[String]) -> (Vec<UserWord>, AliasIndex) {
    let mut words = Vec::new();
    let mut aliases = AliasIndex::default();
    for chunk in rows.chunks(4) {
        if chunk.len() < 4 {
            break;
        }
        let pinned = chunk[3] == "1";
        for reading in chunk[1].split(';').filter(|r| !r.is_empty()) {
            let seq: Option<Vec<u16>> = split_reading(reading).iter().map(|s| syl.id(s)).collect();
            if let Some(seq) = seq.filter(|s| !s.is_empty()) {
                words.push(UserWord { word: chunk[0].clone(), seq, count: 1, last: 0, pinned, custom: true });
            }
        }
        for alias in chunk[2].split(';').filter(|a| !a.is_empty()) {
            let code = alias.to_ascii_lowercase();
            if code.bytes().all(|b| b.is_ascii_lowercase()) {
                aliases.letters.entry(code).or_default().push((chunk[0].clone(), pinned));
            }
            aliases.digits.entry(to_t9(alias)).or_default().push((chunk[0].clone(), pinned));
        }
    }
    (words, aliases)
}
