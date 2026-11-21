//! Desktop report for handwritten daily inputs and the sampled corpus.

use kaixin_engine::dict::Backing;
use kaixin_engine::engine::{
    Engine, Request, F_BASIC_CONTEXT, F_DOUBLE_INITIALS, F_FULL, F_MIXED_ENGLISH, F_SENTENCE,
    F_SHORT, MODE_NINE, MODE_PINYIN,
};
use kaixin_engine::graph::Part;
use kaixin_engine::syllable::STANDARD;
use std::collections::BTreeMap;
use std::fs::{self, File};
use std::io::{BufWriter, Write};
use std::path::Path;
use std::time::Instant;

const LIMIT: usize = 256;
const KEY_BUCKETS: [&str; 8] = ["all", "1", "2", "3-4", "5-8", "9-12", "13-20", "21+"];
const LETTER_FLAGS: u32 =
    F_FULL | F_SHORT | F_DOUBLE_INITIALS | F_SENTENCE | F_MIXED_ENGLISH | F_BASIC_CONTEXT;
const NINE_FLAGS: u32 = F_FULL | F_SHORT | F_DOUBLE_INITIALS | F_MIXED_ENGLISH | F_BASIC_CONTEXT;
const COLUMNS: &str = "kind\tkeys\tprevious\tinput\texpected\trank\telapsed_us\tfirst\tsecond\tthird\tfirst_cost\texpected_cost\tcost_gap\tfirst_consumed\texpected_consumed";

struct Case<'a> {
    kind: &'a str,
    raw: &'a str,
    expected: &'a str,
    previous: &'a str,
    keys: usize,
}

impl<'a> Case<'a> {
    fn parse(line: &'a str, curated: bool) -> Option<Self> {
        let mut fields = line.split('\t');
        let (label, raw, expected) = (fields.next()?, fields.next()?, fields.next()?);
        if fields.next().is_some() || raw.is_empty() || expected.is_empty() {
            return None;
        }
        let (kind, previous) = match label {
            "full" | "short" | "mixed_head" | "mixed_tail" | "mixed_alternate" => (label, ""),
            "sentence" | "prefix" | "nine" | "xiaohe" if curated => (label, ""),
            _ if curated && !label.is_ascii() && label.chars().all(char::is_alphabetic) => {
                ("context", label)
            }
            _ => return None,
        };
        let keys = raw.bytes().filter(|b| *b != b'\'').count();
        let valid_keys = raw.bytes().filter(|b| *b != b'\'').all(|b| {
            if kind == "nine" {
                (b'2'..=b'9').contains(&b)
            } else {
                b.is_ascii_lowercase()
            }
        });
        (keys > 0 && valid_keys).then_some(Self {
            kind,
            raw,
            expected,
            previous,
            keys,
        })
    }

    fn length_bucket(&self) -> usize {
        match self.keys {
            1 => 1,
            2 => 2,
            3..=4 => 3,
            5..=8 => 4,
            9..=12 => 5,
            13..=20 => 6,
            _ => 7,
        }
    }
}

/// Mirror the Xiaohe assignments in ShuangpinInput.java for desk-only evaluation.
fn xiaohe_pair(syllable: &str) -> Option<[u8; 2]> {
    let first = syllable.as_bytes()[0];
    let zero_initial = matches!(first, b'a' | b'e' | b'o');
    if zero_initial && syllable.len() <= 2 {
        return Some([first, *syllable.as_bytes().last()?]);
    }
    let (initial, final_part) = if zero_initial {
        (first, syllable)
    } else if let Some(rest) = syllable.strip_prefix("zh") {
        (b'v', rest)
    } else if let Some(rest) = syllable.strip_prefix("ch") {
        (b'i', rest)
    } else if let Some(rest) = syllable.strip_prefix("sh") {
        (b'u', rest)
    } else {
        (first, &syllable[1..])
    };
    let last = match final_part {
        "iu" => b'q',
        "ei" => b'w',
        "e" => b'e',
        "uan" => b'r',
        "ue" | "ve" => b't',
        "un" => b'y',
        "u" => b'u',
        "i" => b'i',
        "o" | "uo" => b'o',
        "ie" => b'p',
        "a" => b'a',
        "iong" | "ong" => b's',
        "ai" => b'd',
        "en" => b'f',
        "eng" => b'g',
        "ang" => b'h',
        "an" => b'j',
        "ing" | "uai" => b'k',
        "iang" | "uang" => b'l',
        "ou" => b'z',
        "ia" | "ua" => b'x',
        "ao" => b'c',
        "ui" | "v" => b'v',
        "in" => b'b',
        "iao" => b'n',
        "ian" => b'm',
        _ => return None,
    };
    Some([initial, last])
}

fn xiaohe_parts(raw: &str, pairs: &BTreeMap<[u8; 2], Vec<String>>) -> Option<Vec<Part>> {
    let mut parts = Vec::new();
    let mut offset = 0;
    for group in raw.split('\'') {
        for keys in group.as_bytes().chunks(2) {
            let end = offset + keys.len();
            let part = if let [first, last] = *keys {
                Part::Alternatives {
                    start: offset,
                    end,
                    readings: pairs.get(&[first, last])?.clone(),
                }
            } else {
                let prefix = match keys[0] {
                    b'v' => "zh".into(),
                    b'i' => "ch".into(),
                    b'u' => "sh".into(),
                    initial => char::from(initial).to_string(),
                };
                Part::LockedPrefix {
                    start: offset,
                    end,
                    prefix,
                }
            };
            parts.push(part);
            offset = end;
        }
        offset += 1;
    }
    Some(parts)
}

#[derive(Default)]
struct Summary {
    top1: usize,
    top3: usize,
    found: usize,
    samples: Vec<u128>,
}

impl Summary {
    fn record(&mut self, rank: Option<usize>, elapsed_us: u128) {
        self.top1 += usize::from(rank == Some(1));
        self.top3 += usize::from(rank.is_some_and(|rank| rank <= 3));
        self.found += usize::from(rank.is_some());
        self.samples.push(elapsed_us);
    }

    fn write(&mut self, report: &mut impl Write, group: &str, label: &str) -> std::io::Result<()> {
        self.samples.sort_unstable();
        let count = self.samples.len();
        let rate = |hits| 100.0 * hits as f64 / count as f64;
        let percentile = |p: usize| self.samples[(count * p).div_ceil(100) - 1];
        writeln!(
            report,
            "{group}\t{label}\t{count}\t{}\t{:.2}\t{}\t{:.2}\t{}\t{:.2}\t{}\t{}\t{}",
            self.top1,
            rate(self.top1),
            self.top3,
            rate(self.top3),
            self.found,
            rate(self.found),
            percentile(50),
            percentile(95),
            self.samples[count - 1],
        )
    }
}

fn evaluate_set(
    engine: &Engine,
    path: &Path,
    name: &str,
    curated: bool,
    pairs: &BTreeMap<[u8; 2], Vec<String>>,
    report: &mut impl Write,
) -> Result<(), Box<dyn std::error::Error>> {
    let text = fs::read_to_string(path)?;
    let mut total = Summary::default();
    let mut buckets: BTreeMap<(&str, usize), Summary> = BTreeMap::new();
    let mut failures = Vec::new();
    writeln!(report, "# {name} cases\n{COLUMNS}")?;
    for (index, line) in text.trim_start_matches('\u{feff}').lines().enumerate() {
        if line.trim().is_empty() || line.starts_with('#') {
            continue;
        }
        let invalid = || {
            format!(
                "invalid evaluation case at {}:{}",
                path.display(),
                index + 1
            )
        };
        let case = Case::parse(line, curated).ok_or_else(invalid)?;
        let parts = if case.kind == "xiaohe" {
            Some(xiaohe_parts(case.raw, pairs).ok_or_else(invalid)?)
        } else {
            None
        };
        let numeric = case.kind == "nine";
        let request = Request {
            mode: if numeric { MODE_NINE } else { MODE_PINYIN },
            raw: case.raw,
            parts,
            prev2: "",
            prev: case.previous,
            flags: if numeric { NINE_FLAGS } else { LETTER_FLAGS },
            limit: LIMIT,
            touch_corrections: &[],
        };
        let started = Instant::now();
        let candidates = engine.query(&request);
        let elapsed_us = started.elapsed().as_micros();
        let expected_index = candidates
            .iter()
            .position(|candidate| candidate.word == case.expected);
        let rank = expected_index.map(|i| i + 1);
        total.record(rank, elapsed_us);
        let length = case.length_bucket();
        for bucket in [(case.kind, 0), ("all", length), (case.kind, length)] {
            buckets.entry(bucket).or_default().record(rank, elapsed_us);
        }
        let word = |i: usize| {
            candidates
                .get(i)
                .map_or("", |candidate| candidate.word.as_str())
        };
        let first = candidates.first();
        let expected = expected_index.map(|i| &candidates[i]);
        let gap = first
            .zip(expected)
            .map(|(a, b)| i64::from(b.cost) - i64::from(a.cost));
        let num = |n: Option<i64>| n.map_or_else(String::new, |n| n.to_string());
        let row = format!(
            "{}\t{}\t{}\t{}\t{}\t{}\t{elapsed_us}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
            case.kind,
            case.keys,
            case.previous,
            case.raw,
            case.expected,
            num(rank.map(|r| r as i64)),
            word(0),
            word(1),
            word(2),
            num(first.map(|c| i64::from(c.cost))),
            num(expected.map(|c| i64::from(c.cost))),
            num(gap),
            num(first.map(|c| c.consumed as i64)),
            num(expected.map(|c| c.consumed as i64)),
        );
        writeln!(report, "{row}")?;
        if rank != Some(1) {
            failures.push(row);
        }
    }
    if total.samples.is_empty() {
        return Err(format!("no evaluation cases in {}", path.display()).into());
    }
    writeln!(report, "\n# {name} summary")?;
    writeln!(
        report,
        "kind\tkey_bucket\tcases\ttop1_hits\ttop1_pct\ttop3_hits\ttop3_pct\treturned_hits\treturned_pct\tp50_us\tp95_us\tmax_us"
    )?;
    total.write(report, "all", "all")?;
    total.write(&mut std::io::stdout().lock(), name, "all")?;
    for ((kind, length), mut summary) in buckets {
        summary.write(report, kind, KEY_BUCKETS[length])?;
    }
    writeln!(report, "\n# {name} top1 failures\n{COLUMNS}")?;
    for row in failures {
        writeln!(report, "{row}")?;
    }
    writeln!(report)?;
    Ok(())
}

fn evaluate(report: &Path) -> Result<(), Box<dyn std::error::Error>> {
    let project = Path::new(env!("CARGO_MANIFEST_DIR")).join("..");
    let dictionary = project.join("build/engine-assets/engine/kaixin.dict");
    let data = fs::read(&dictionary)
        .map_err(|err| format!("cannot read {}: {err}", dictionary.display()))?;
    let engine = Engine::new(Backing::Owned(data))?;
    let mut pairs = BTreeMap::<_, Vec<String>>::new();
    for &syllable in STANDARD {
        if let Some(pair) = xiaohe_pair(syllable) {
            pairs.entry(pair).or_default().push(syllable.into());
        }
    }
    let mut report = BufWriter::new(File::create(report)?);
    writeln!(
        report,
        "# letter_flags={LETTER_FLAGS}\tnine_flags={NINE_FLAGS}\tlimit={LIMIT}\tuser=empty"
    )?;
    writeln!(report, "# latency is engine query only; gap = expected_cost - first_cost; blank rank = outside returned results\n")?;
    evaluate_set(
        &engine,
        &project.join("engine/eval/curated.tsv"),
        "curated",
        true,
        &pairs,
        &mut report,
    )?;
    evaluate_set(
        &engine,
        &project.join("app/src/androidTest/assets/pinyin-evaluation.tsv"),
        "sampled",
        false,
        &pairs,
        &mut report,
    )?;
    report.flush()?;
    Ok(())
}

fn main() {
    let args: Vec<_> = std::env::args_os().skip(1).collect();
    let [report] = args.as_slice() else {
        eprintln!("usage: cargo run --release --bin evaluate -- <report.tsv>");
        std::process::exit(2);
    };
    if let Err(err) = evaluate(Path::new(report)) {
        eprintln!("evaluate: {err}");
        std::process::exit(1);
    }
}
