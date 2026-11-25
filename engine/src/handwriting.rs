//! Native handwriting helpers: bounded CTC prefix beam search and geometric
//! trajectory template matching / five-stroke classification.

use crate::fxhash::FxHashMap;
use std::sync::OnceLock;

pub const CTC_CHOICES: usize = 7;
pub const CTC_MAX_CHARS: usize = 12;
const CTC_BEAM_WIDTH: usize = 32;
const NEG_INF: f64 = f64::NEG_INFINITY;

const SAMPLE_COUNT: usize = 16;
const MAX_STROKES: usize = 64;
const MAX_TEMPLATE_SCORE: f64 = 0.48;
const ALTERNATIVE_MARGIN: f64 = 0.12;

#[derive(Clone, Copy, PartialEq, Eq, Hash, Default)]
pub struct TokenSeq {
    pub data: [i32; CTC_MAX_CHARS],
    pub len: u8,
}

impl TokenSeq {
    #[inline]
    fn last(&self) -> i32 {
        if self.len == 0 { -1 } else { self.data[self.len as usize - 1] }
    }

    #[inline]
    fn extended(&self, token: i32) -> Self {
        let mut next = *self;
        next.data[next.len as usize] = token;
        next.len += 1;
        next
    }
}

#[derive(Clone, Copy)]
struct BeamScore {
    blank: f64,
    ink: f64,
}

impl Default for BeamScore {
    fn default() -> Self {
        Self { blank: NEG_INF, ink: NEG_INF }
    }
}

impl BeamScore {
    #[inline]
    fn total(self) -> f64 {
        log_add(self.blank, self.ink)
    }
}

#[inline]
fn log_prob(v: f32) -> f64 {
    if v > 0.0 { (v as f64).ln() } else { NEG_INF }
}

#[inline]
fn log_add(a: f64, b: f64) -> f64 {
    if a == NEG_INF {
        return b;
    }
    if b == NEG_INF {
        return a;
    }
    let m = a.max(b);
    m + (a.min(b) - m).exp().ln_1p()
}

pub struct CtcDecodeResult {
    pub sequences: Vec<TokenSeq>,
    pub confidence: f32,
    pub greedy_count: i32,
    pub last_emitted: i32,
}

/// Bounded CTC prefix beam search with zero heap allocations per beam key.
pub fn decode_ctc(
    ids: &[i32],
    probs: &[f32],
    steps: usize,
    allowed: &[u8],
    single: bool,
) -> CtcDecodeResult {
    let needed = steps.saturating_mul(CTC_CHOICES);
    if steps == 0 || ids.len() < needed || probs.len() < needed {
        return CtcDecodeResult {
            sequences: Vec::new(),
            confidence: 0.0,
            greedy_count: 0,
            last_emitted: 0,
        };
    }

    let max_len = if single { 1u8 } else { CTC_MAX_CHARS as u8 };
    let mut beams: FxHashMap<TokenSeq, BeamScore> = FxHashMap::default();
    beams.reserve(CTC_BEAM_WIDTH * 2);
    beams.insert(TokenSeq::default(), BeamScore { blank: 0.0, ink: NEG_INF });

    let mut next: FxHashMap<TokenSeq, BeamScore> = FxHashMap::default();
    next.reserve(CTC_BEAM_WIDTH * CTC_CHOICES);
    let mut ordered: Vec<(TokenSeq, BeamScore, f64)> = Vec::with_capacity(CTC_BEAM_WIDTH * CTC_CHOICES);

    let mut greedy_score = 0.0f32;
    let mut greedy_count = 0i32;
    let mut last_emitted = 0i32;
    let mut previous = 0i32;

    for time in 0..steps {
        next.clear();
        let offset = time * CTC_CHOICES;
        let mut best = 0usize;
        for j in 1..CTC_CHOICES {
            if probs[offset + j] > probs[offset + best] {
                best = j;
            }
        }
        let token = ids[offset + best];
        if token != 0 && token != previous {
            greedy_score += probs[offset + best];
            greedy_count += 1;
            last_emitted = token;
        }
        previous = token;

        let blank_lp = log_prob(probs[offset]);
        for (&seq, &score) in &beams {
            let total = score.total();
            let same = next.entry(seq).or_default();
            same.blank = log_add(same.blank, total + blank_lp);

            let last = seq.last();
            for j in 1..CTC_CHOICES {
                let id = ids[offset + j];
                if id <= 0 || (id as usize) >= allowed.len() || allowed[id as usize] == 0 {
                    continue;
                }
                let chance = log_prob(probs[offset + j]);
                if id == last {
                    let same = next.entry(seq).or_default();
                    same.ink = log_add(same.ink, score.ink + chance);
                }
                if seq.len >= max_len {
                    continue;
                }
                let ext = seq.extended(id);
                let longer = next.entry(ext).or_default();
                let base = if id == last { score.blank } else { total };
                longer.ink = log_add(longer.ink, base + chance);
            }
        }

        ordered.clear();
        for (&seq, &score) in &next {
            let t = score.total();
            if t.is_finite() {
                ordered.push((seq, score, t));
            }
        }
        ordered.sort_unstable_by(|a, b| b.2.total_cmp(&a.2));
        beams.clear();
        for &(seq, score, _) in ordered.iter().take(CTC_BEAM_WIDTH) {
            beams.insert(seq, score);
        }
    }

    ordered.clear();
    for (&seq, &score) in &beams {
        if seq.len > 0 && (!single || seq.len == 1) {
            ordered.push((seq, score, score.total()));
        }
    }
    ordered.sort_unstable_by(|a, b| b.2.total_cmp(&a.2));

    let sequences: Vec<TokenSeq> = ordered.into_iter().take(24).map(|(seq, _, _)| seq).collect();
    let confidence = if greedy_count == 0 { 0.0 } else { greedy_score / greedy_count as f32 };

    CtcDecodeResult {
        sequences,
        confidence,
        greedy_count,
        last_emitted,
    }
}

// ---- Trajectory template matching & five-stroke classification ----

#[derive(Clone, Copy)]
struct Point {
    x: f32,
    y: f32,
}

struct Template {
    value: &'static str,
    strokes: Vec<[Point; SAMPLE_COUNT]>,
}

#[inline]
fn sq_dist(a: Point, b: Point) -> f64 {
    let dx = (a.x - b.x) as f64;
    let dy = (a.y - b.y) as f64;
    dx * dx + dy * dy
}

#[inline]
fn dist(a: Point, b: Point) -> f64 {
    sq_dist(a, b).sqrt()
}

fn resample(points: &[Point]) -> [Point; SAMPLE_COUNT] {
    if points.len() == 1 {
        return [points[0]; SAMPLE_COUNT];
    }
    let mut total = 0.0f64;
    for w in points.windows(2) {
        total += dist(w[0], w[1]);
    }
    if total < 0.001 {
        return [points[0]; SAMPLE_COUNT];
    }
    let mut out = [points[0]; SAMPLE_COUNT];
    let mut segment = 1usize;
    let mut traversed = 0.0f64;
    for (sample, slot) in out.iter_mut().enumerate() {
        let target = total * (sample as f64) / ((SAMPLE_COUNT - 1) as f64);
        while segment < points.len() - 1 {
            let seg_len = dist(points[segment - 1], points[segment]);
            if traversed + seg_len < target {
                traversed += seg_len;
                segment += 1;
            } else {
                break;
            }
        }
        let start = points[segment - 1];
        let end = points[segment];
        let seg_len = dist(start, end);
        let frac = if seg_len < 0.001 {
            0.0f32
        } else {
            ((target - traversed) / seg_len).clamp(0.0, 1.0) as f32
        };
        *slot = Point {
            x: start.x + (end.x - start.x) * frac,
            y: start.y + (end.y - start.y) * frac,
        };
    }
    out
}

fn normalize(strokes: &[&[Point]]) -> Option<Vec<[Point; SAMPLE_COUNT]>> {
    let (mut min_x, mut min_y) = (f32::INFINITY, f32::INFINITY);
    let (mut max_x, mut max_y) = (f32::NEG_INFINITY, f32::NEG_INFINITY);
    let mut count = 0usize;
    for &stroke in strokes {
        if stroke.is_empty() {
            return None;
        }
        for p in stroke {
            if !p.x.is_finite() || !p.y.is_finite() {
                return None;
            }
            min_x = min_x.min(p.x);
            min_y = min_y.min(p.y);
            max_x = max_x.max(p.x);
            max_y = max_y.max(p.y);
            count += 1;
        }
    }
    if count == 0 {
        return None;
    }
    let width = max_x - min_x;
    let height = max_y - min_y;
    let side = width.max(height).max(1.0);
    let x_off = (100.0 - width * 100.0 / side) * 0.5;
    let y_off = (100.0 - height * 100.0 / side) * 0.5;

    let mut scaled = Vec::new();
    let mut result = Vec::with_capacity(strokes.len());
    for &stroke in strokes {
        scaled.clear();
        scaled.reserve(stroke.len());
        for p in stroke {
            scaled.push(Point {
                x: x_off + (p.x - min_x) * 100.0 / side,
                y: y_off + (p.y - min_y) * 100.0 / side,
            });
        }
        result.push(resample(&scaled));
    }
    Some(result)
}

fn classify_stroke(stroke: &[Point; SAMPLE_COUNT]) -> u8 {
    let first = stroke[0];
    let last = stroke[SAMPLE_COUNT - 1];
    let dx = last.x - first.x;
    let dy = last.y - first.y;
    let mut path_length = 0.0f64;
    let mut strongest_turn = 0.0f64;
    let mut prev_angle = f64::NAN;
    for w in stroke.windows(2) {
        let seg = dist(w[0], w[1]);
        if seg < 0.5 {
            continue;
        }
        path_length += seg;
        let angle = ((w[1].y - w[0].y) as f64).atan2((w[1].x - w[0].x) as f64);
        if !prev_angle.is_nan() {
            let mut change = (angle - prev_angle).abs();
            change = change.min(std::f64::consts::TAU - change);
            strongest_turn = strongest_turn.max(change);
        }
        prev_angle = angle;
    }
    let direct = (dx as f64).hypot(dy as f64);
    if strongest_turn > 0.85 || path_length > 8.0f64.max(direct * 1.35) {
        return b'5';
    }
    if dx.abs() > dy.abs() * 1.45 {
        return b'1';
    }
    if dy.abs() > dx.abs() * 1.65 {
        return b'2';
    }
    if dx < 0.0 && dy > 0.0 {
        return b'3';
    }
    if dy >= 0.0 {
        return b'4';
    }
    b'5'
}

fn template_score(input: &[[Point; SAMPLE_COUNT]], tmpl: &[[Point; SAMPLE_COUNT]]) -> f64 {
    let mut sum = 0.0f64;
    let mut count = 0usize;
    for (actual, expected) in input.iter().zip(tmpl.iter()) {
        for i in 0..SAMPLE_COUNT {
            sum += sq_dist(actual[i], expected[i]);
            count += 1;
        }
    }
    (sum / count.max(1) as f64).sqrt() / 100.0
}

fn templates() -> &'static [Template] {
    static TABLE: OnceLock<Vec<Template>> = OnceLock::new();
    TABLE.get_or_init(|| {
        let line = |x1: f32, y1: f32, x2: f32, y2: f32| vec![Point { x: x1, y: y1 }, Point { x: x2, y: y2 }];
        let path = |coords: &[f32]| -> Vec<Point> {
            coords.chunks_exact(2).map(|c| Point { x: c[0], y: c[1] }).collect()
        };
        let defs: Vec<(&'static str, Vec<Vec<Point>>)> = vec![
            ("一", vec![line(10.0, 50.0, 90.0, 50.0)]),
            ("丨", vec![line(50.0, 10.0, 50.0, 90.0)]),
            ("丿", vec![line(82.0, 12.0, 18.0, 88.0)]),
            ("丶", vec![line(62.0, 16.0, 38.0, 72.0)]),
            ("乙", vec![path(&[75.0, 16.0, 28.0, 16.0, 25.0, 52.0, 73.0, 52.0, 72.0, 83.0])]),
            ("十", vec![line(12.0, 50.0, 88.0, 50.0), line(50.0, 12.0, 50.0, 88.0)]),
            ("二", vec![line(12.0, 35.0, 88.0, 35.0), line(12.0, 68.0, 88.0, 68.0)]),
            ("人", vec![line(50.0, 14.0, 18.0, 88.0), line(50.0, 14.0, 84.0, 88.0)]),
            ("八", vec![line(45.0, 38.0, 16.0, 82.0), line(55.0, 38.0, 84.0, 82.0)]),
            ("大", vec![line(12.0, 30.0, 88.0, 30.0), line(50.0, 30.0, 16.0, 88.0), line(50.0, 30.0, 84.0, 88.0)]),
            ("小", vec![line(50.0, 10.0, 50.0, 90.0), line(39.0, 48.0, 18.0, 78.0), line(61.0, 48.0, 82.0, 78.0)]),
            ("工", vec![line(12.0, 25.0, 88.0, 25.0), line(50.0, 25.0, 50.0, 80.0), line(12.0, 80.0, 88.0, 80.0)]),
            ("土", vec![line(12.0, 28.0, 88.0, 28.0), line(50.0, 28.0, 50.0, 72.0), line(12.0, 80.0, 88.0, 80.0)]),
            ("干", vec![line(12.0, 25.0, 88.0, 25.0), line(12.0, 50.0, 88.0, 50.0), line(50.0, 50.0, 50.0, 90.0)]),
            ("口", vec![line(20.0, 12.0, 20.0, 82.0), path(&[20.0, 12.0, 82.0, 12.0, 82.0, 82.0]), line(20.0, 82.0, 82.0, 82.0)]),
            ("日", vec![line(20.0, 12.0, 20.0, 84.0), path(&[20.0, 12.0, 80.0, 12.0, 80.0, 84.0]), line(20.0, 48.0, 80.0, 48.0), line(20.0, 84.0, 80.0, 84.0)]),
            ("中", vec![line(18.0, 10.0, 18.0, 85.0), path(&[18.0, 28.0, 82.0, 28.0, 82.0, 72.0]), line(18.0, 72.0, 82.0, 72.0), line(50.0, 10.0, 50.0, 90.0)]),
            ("木", vec![line(12.0, 35.0, 88.0, 35.0), line(50.0, 12.0, 50.0, 88.0), line(50.0, 45.0, 18.0, 88.0), line(50.0, 45.0, 84.0, 88.0)]),
        ];
        defs.into_iter()
            .filter_map(|(value, raw_strokes)| {
                let slices: Vec<&[Point]> = raw_strokes.iter().map(Vec::as_slice).collect();
                normalize(&slices).map(|strokes| Template { value, strokes })
            })
            .collect()
    })
}

/// Analyzes flattened stroke coordinates into (template matches, five-stroke sequence).
pub fn analyze_strokes(coords: &[f32], stroke_lens: &[i32]) -> (Vec<&'static str>, String) {
    if stroke_lens.is_empty() || stroke_lens.len() > MAX_STROKES {
        return (Vec::new(), String::new());
    }
    let total_points: usize = stroke_lens.iter().map(|&n| n.max(0) as usize).sum();
    if coords.len() < total_points * 2 {
        return (Vec::new(), String::new());
    }
    let mut all_points: Vec<Point> = Vec::with_capacity(total_points);
    for i in 0..total_points {
        all_points.push(Point { x: coords[i * 2], y: coords[i * 2 + 1] });
    }
    let mut slices: Vec<&[Point]> = Vec::with_capacity(stroke_lens.len());
    let mut off = 0usize;
    for &len in stroke_lens {
        if len <= 0 {
            return (Vec::new(), String::new());
        }
        let n = len as usize;
        slices.push(&all_points[off..off + n]);
        off += n;
    }

    let mut stroke_seq = String::with_capacity(slices.len());
    for &s in &slices {
        stroke_seq.push(classify_stroke(&resample(s)) as char);
    }

    let Some(normalized) = normalize(&slices) else {
        return (Vec::new(), stroke_seq);
    };

    let mut matches: Vec<(&'static str, f64)> = Vec::new();
    for tmpl in templates() {
        if tmpl.strokes.len() != normalized.len() {
            continue;
        }
        matches.push((tmpl.value, template_score(&normalized, &tmpl.strokes)));
    }
    matches.sort_by(|a, b| a.1.total_cmp(&b.1));
    let mut candidates: Vec<&'static str> = Vec::new();
    if let Some(&(_, best)) = matches.first() {
        if best <= MAX_TEMPLATE_SCORE {
            for (val, sc) in matches {
                if sc > MAX_TEMPLATE_SCORE || sc > best + ALTERNATIVE_MARGIN || candidates.len() == 5 {
                    break;
                }
                if !candidates.contains(&val) {
                    candidates.push(val);
                }
            }
        }
    }
    (candidates, stroke_seq)
}
