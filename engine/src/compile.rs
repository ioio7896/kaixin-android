//! Build-time compiler from the text lexicon to the binary dictionary.

use crate::dict::{self, Field, ALPHA_EMOJI, ALPHA_ENGLISH, ALPHA_MIXED, FLAG_CORE, HEADER_LEN, MAGIC, VERSION};
use crate::syllable::{canonical_syllable, MAX_SYLLABLE_LEN, STANDARD};
use std::collections::{BTreeSet, HashMap, HashSet};
use std::fs;
use std::io::{ErrorKind, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

const MAX_WORD_SYLLABLES: usize = 32;

#[derive(Default)]
struct Rules {
    scale_prefix: Vec<(String, u32)>,
    cap: HashMap<String, u32>,
    cap_prefix: Vec<(String, u32)>,
    cap_word: HashMap<(String, String), u32>,
    exclude: HashSet<(String, String)>,
}

impl Rules {
    fn load(path: &Path) -> Result<Rules, String> {
        let mut rules = Rules::default();
        for line in read_lines(path)? {
            let cols: Vec<&str> = line.split('\t').collect();
            let number = |i: usize| cols.get(i).and_then(|v| v.trim().parse::<u32>().ok());
            match (cols[0], cols.len()) {
                ("scale-prefix", 3) => rules.scale_prefix.push((cols[1].into(), number(2).ok_or("bad scale")?)),
                ("cap", 3) => { rules.cap.insert(cols[1].into(), number(2).ok_or("bad cap")?); }
                ("cap-prefix", 3) => rules.cap_prefix.push((cols[1].into(), number(2).ok_or("bad cap-prefix")?)),
                ("cap-word", 4) => {
                    rules.cap_word.insert((cols[1].into(), cols[2].into()), number(3).ok_or("bad cap-word")?);
                }
                ("exclude", 3) => { rules.exclude.insert((cols[1].into(), cols[2].into())); }
                _ => return Err(format!("unknown rule: {line}")),
            }
        }
        Ok(rules)
    }

    /// Corrected score, or None when the word is excluded from this source.
    fn apply(&self, source: &str, word: &str, score: u32) -> Option<u32> {
        let key = (source.to_string(), word.to_string());
        if self.exclude.contains(&key) {
            return None;
        }
        let mut value = score;
        if let Some((_, pct)) = self
            .scale_prefix
            .iter()
            .filter(|(p, _)| source.starts_with(p.as_str()))
            .max_by_key(|(p, _)| p.len())
        {
            value = value * pct / 100;
        }
        if let Some(cap) = self.cap.get(source) {
            value = value.min(*cap);
        }
        for (prefix, cap) in &self.cap_prefix {
            if source.starts_with(prefix.as_str()) {
                value = value.min(*cap);
            }
        }
        if let Some(cap) = self.cap_word.get(&key) {
            value = value.min(*cap);
        }
        Some(value.clamp(1, 10_000))
    }
}

fn read_lines(path: &Path) -> Result<Vec<String>, String> {
    let text = fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))?;
    Ok(text
        .lines()
        .map(|l| l.trim_start_matches('\u{feff}').trim_end_matches('\r'))
        .filter(|l| !l.trim().is_empty() && !l.starts_with('#'))
        .map(String::from)
        .collect())
}

fn sorted_files(dir: &Path) -> Result<Vec<PathBuf>, String> {
    let mut files: Vec<PathBuf> = fs::read_dir(dir)
        .map_err(|e| format!("{}: {e}", dir.display()))?
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| p.extension().is_some_and(|x| x == "txt"))
        .collect();
    files.sort();
    Ok(files)
}

struct Builder {
    rules: Rules,
    /// (reading, word) -> (score, flags)
    words: HashMap<(String, String), (u32, u8)>,
    alpha: Vec<(String, String, u32, u16)>,
    skipped: usize,
}

#[derive(Clone, Copy)]
enum ScoreMerge {
    Highest,
    Override,
}

impl Builder {
    fn add(&mut self, source: &str, word: &str, reading: &str, score: u32, flags: u8, merge: ScoreMerge) {
        let word = word.trim();
        let syllables: Vec<&str> = reading.split_whitespace().map(canonical_syllable).collect();
        let valid = !word.is_empty()
            && !syllables.is_empty()
            && syllables.len() <= MAX_WORD_SYLLABLES
            && syllables.len() == word.chars().count()
            && syllables
                .iter()
                .all(|s| s.len() <= MAX_SYLLABLE_LEN && s.bytes().all(|b| b.is_ascii_lowercase()));
        if !valid {
            self.skipped += 1;
            return;
        }
        let Some(score) = self.rules.apply(source, word, score) else { return };
        let key = (syllables.join(" "), word.to_string());
        let slot = self.words.entry(key).or_insert((0, 0));
        slot.0 = match merge {
            ScoreMerge::Highest => slot.0.max(score),
            ScoreMerge::Override => score,
        };
        slot.1 |= flags;
    }

    fn load_three_column(&mut self, root: &Path, path: &Path, flags: u8, merge: ScoreMerge) -> Result<(), String> {
        let source = path
            .strip_prefix(root)
            .unwrap_or(path)
            .to_string_lossy()
            .replace('\\', "/");
        for line in read_lines(path)? {
            let cols: Vec<&str> = line.split('\t').collect();
            if cols.len() < 3 {
                self.skipped += 1;
                continue;
            }
            let Ok(score) = cols[2].trim().parse::<u32>() else {
                self.skipped += 1;
                continue;
            };
            let reading = cols[1].to_lowercase().replace("u:", "v").replace('ü', "v");
            self.add(&source, cols[0], &reading, score, flags, merge);
        }
        Ok(())
    }
}

pub struct Summary {
    pub entries: usize,
    pub words: usize,
    pub alpha: usize,
    pub collocations: usize,
    pub skipped: usize,
    pub bytes: usize,
}

/// `root` is the project root (containing lexicon/, data_sources/ and app/).
pub fn build(root: &Path, out: &Path, emoji_catalog: Option<&Path>) -> Result<Summary, String> {
    let lexicon = root.join("lexicon");
    let corrections = root.join("app/src/main/assets/lexicon-corrections");
    let mut b = Builder {
        rules: Rules::load(&corrections.join("rules.tsv"))?,
        words: HashMap::new(),
        alpha: Vec::new(),
        skipped: 0,
    };
    let pronunciation_aliases = lexicon.join("core/kaixin_pronunciation_aliases.txt");
    for file in sorted_files(&lexicon.join("core"))? {
        if file != pronunciation_aliases {
            b.load_three_column(&lexicon, &file, FLAG_CORE, ScoreMerge::Highest)?;
        }
    }
    for name in ["idioms.txt", "organizations.txt"] {
        let path = corrections.join(name);
        b.load_three_column(&corrections, &path, FLAG_CORE, ScoreMerge::Highest)?;
    }
    for dir in ["zh", "zh-ext"] {
        for file in sorted_files(&lexicon.join(dir))? {
            if file.file_name().is_some_and(|n| n == "single_char_common_8105.txt") {
                continue; // readings come from desktop_single_char_readings.txt
            }
            b.load_three_column(&lexicon, &file, 0, ScoreMerge::Highest)?;
        }
    }
    let singles = root.join("data_sources/desktop_single_char_readings.txt");
    b.load_three_column(root, &singles, FLAG_CORE, ScoreMerge::Highest)?;
    // Each curated pronunciation has its own score, including low-priority alternate readings.
    b.load_three_column(&lexicon, &pronunciation_aliases, FLAG_CORE, ScoreMerge::Override)?;

    for line in read_lines(&lexicon.join("en/kaixin_common_english.txt"))? {
        let cols: Vec<&str> = line.split('\t').collect();
        if cols.len() < 3 { continue; }
        let code = cols[1].trim().to_ascii_lowercase();
        if code.is_empty() || !code.bytes().all(|c| c.is_ascii_lowercase()) { continue; }
        let score = cols[2].trim().parse::<u32>().unwrap_or(1).clamp(1, 10_000);
        b.alpha.push((code, cols[0].trim().to_string(), score, ALPHA_ENGLISH));
    }
    for line in read_lines(&lexicon.join("en/kaixin_mixed_100.txt"))? {
        let cols: Vec<&str> = line.split('\t').collect();
        if cols.len() < 3 { continue; }
        let score = cols[2].trim().parse::<u32>().unwrap_or(1).clamp(1, 10_000);
        for code in cols[1].split(',') {
            let code = code.trim().to_ascii_lowercase();
            if code.is_empty() || !code.bytes().all(|c| c.is_ascii_lowercase()) { continue; }
            b.alpha.push((code, cols[0].trim().to_string(), score, ALPHA_MIXED));
        }
    }
    if let Some(path) = emoji_catalog {
        for (index, line) in read_lines(path)?.iter().enumerate() {
            let fields: Vec<_> = line.split('\t').collect();
            let [_, emoji, name, _, keywords, aliases, base] = fields.as_slice() else {
                return Err(format!("invalid emoji catalog row {}", index + 1));
            };
            if emoji != base {
                continue;
            }
            let mut terms = HashMap::new();
            let region = name.strip_prefix("旗: ").unwrap_or("");
            for (words, score) in [(*keywords, 6_000), (*name, 8_000), (region, 8_000), (*aliases, 10_000)] {
                for word in words.split('|').map(str::trim).filter(|word| !word.is_empty()) {
                    if word.chars().count() >= 2 || score == 10_000 {
                        terms.insert(word, score - (index as u32 / 3).min(2_000));
                    }
                }
            }
            b.alpha.extend(terms.into_iter()
                .map(|(word, score)| (word.to_string(), emoji.to_string(), score, ALPHA_EMOJI)));
        }
    }

    // Syllable inventory: standard syllables plus anything the corpus uses.
    let mut names: BTreeSet<String> = STANDARD.iter().map(|s| s.to_string()).collect();
    for (reading, _) in b.words.keys() {
        for s in reading.split(' ') {
            names.insert(s.to_string());
        }
    }
    let names: Vec<String> = names.into_iter().collect();
    if names.len() > u16::MAX as usize {
        return Err("too many syllables".into());
    }
    let syllable_id: HashMap<&str, u16> = names.iter().enumerate().map(|(i, n)| (n.as_str(), i as u16)).collect();

    // OpenCC Simplified <-> Traditional mappings.
    let opencc_dir = root.join("app/src/main/assets/opencc");
    let mut opencc_st_map: HashMap<String, String> = HashMap::new();
    let mut opencc_ts_map: HashMap<String, String> = HashMap::new();
    let load_opencc = |path: &Path, map: &mut HashMap<String, String>| -> Result<(), String> {
        if !path.is_file() {
            return Ok(());
        }
        for line in read_lines(path)? {
            let Some((key, value)) = line.split_once('\t') else { continue };
            if key.is_empty() || value.is_empty() { continue; }
            let target = value.split_once(' ').map_or(value, |(first, _)| first);
            if !target.is_empty() {
                map.entry(key.to_string()).or_insert_with(|| target.to_string());
            }
        }
        Ok(())
    };
    load_opencc(&opencc_dir.join("STPhrases.txt"), &mut opencc_st_map)?;
    load_opencc(&opencc_dir.join("STCharacters.txt"), &mut opencc_st_map)?;
    load_opencc(&opencc_dir.join("TSPhrases.txt"), &mut opencc_ts_map)?;
    load_opencc(&opencc_dir.join("TSCharacters.txt"), &mut opencc_ts_map)?;

    // Five-stroke index and simplified/traditional frequency rankings.
    let stroke_dir = root.join("app/src/main/assets/stroke");
    let mut stroke_seq_map: HashMap<String, String> = HashMap::new();
    let stroke_seq_path = stroke_dir.join("sequence-characters.txt");
    if stroke_seq_path.is_file() {
        for line in read_lines(&stroke_seq_path)? {
            let Some((seq, chars)) = line.split_once('\t') else { continue };
            if !seq.is_empty() && seq.bytes().all(|c| (b'1'..=b'5').contains(&c)) && !chars.is_empty() {
                stroke_seq_map.insert(seq.to_string(), chars.to_string());
            }
        }
    }
    let load_stroke_rank = |path: &Path| -> Result<Vec<(u32, u32)>, String> {
        if !path.is_file() {
            return Ok(Vec::new());
        }
        let mut seen = HashMap::new();
        let mut position = 0u32;
        for line in read_lines(path)? {
            for ch in line.chars() {
                if !ch.is_whitespace() {
                    let cp = ch as u32;
                    if let std::collections::hash_map::Entry::Vacant(slot) = seen.entry(cp) {
                        slot.insert(position);
                        position += 1;
                    }
                }
            }
        }
        let mut pairs: Vec<(u32, u32)> = seen.into_iter().collect();
        pairs.sort_by_key(|&(cp, _)| cp);
        Ok(pairs)
    };
    let stroke_simp_rank = load_stroke_rank(&stroke_dir.join("ranking-simplified.txt"))?;
    let stroke_trad_rank = load_stroke_rank(&stroke_dir.join("ranking-traditional.txt"))?;

    // String table.
    let mut strings: BTreeSet<&str> = BTreeSet::new();
    for (_, word) in b.words.keys() {
        strings.insert(word);
    }
    for (code, word, _, _) in &b.alpha {
        strings.insert(code);
        strings.insert(word);
    }
    for (k, v) in &opencc_st_map {
        strings.insert(k.as_str());
        strings.insert(v.as_str());
    }
    for (k, v) in &opencc_ts_map {
        strings.insert(k.as_str());
        strings.insert(v.as_str());
    }
    for (seq, chars) in &stroke_seq_map {
        strings.insert(seq.as_str());
        strings.insert(chars.as_str());
    }
    let colloc_path = root.join("app/src/main/assets/language-model/collocations.tsv");
    let colloc_lines = read_lines(&colloc_path)?;
    let strings: Vec<&str> = strings.into_iter().collect();
    let string_id: HashMap<&str, u32> = strings.iter().enumerate().map(|(i, s)| (*s, i as u32)).collect();

    // Entries sorted by syllable sequence, then score descending.
    struct Row { seq: Vec<u16>, word: u32, score: u16, flags: u8 }
    let mut rows: Vec<Row> = b
        .words
        .iter()
        .map(|((reading, word), (score, flags))| Row {
            seq: reading.split(' ').map(|s| syllable_id[s]).collect(),
            word: string_id[word.as_str()],
            score: *score as u16,
            flags: *flags,
        })
        .collect();
    rows.sort_by(|a, b| a.seq.cmp(&b.seq).then(b.score.cmp(&a.score)).then(a.word.cmp(&b.word)));

    let mut alpha: Vec<(u32, u32, u16, u16)> = b
        .alpha
        .iter()
        .map(|(code, word, score, kind)| (string_id[code.as_str()], string_id[word.as_str()], *score as u16, *kind))
        .collect();
    alpha.sort_by(|a, b| a.0.cmp(&b.0).then(b.2.cmp(&a.2)).then(a.1.cmp(&b.1)));
    alpha.dedup_by(|a, b| a.0 == b.0 && a.1 == b.1);

    let mut colloc: Vec<(u32, u32, u32)> = Vec::new();
    for line in &colloc_lines {
        let cols: Vec<&str> = line.split('\t').collect();
        if cols.len() < 3 { continue; }
        let (Some(&p), Some(&n)) = (string_id.get(cols[0]), string_id.get(cols[1])) else { continue };
        let Ok(w) = cols[2].trim().parse::<u32>() else { continue };
        colloc.push((p, n, w));
    }
    colloc.sort();
    colloc.dedup_by(|a, b| a.0 == b.0 && a.1 == b.1);

    let opencc_st_max_chars = opencc_st_map.keys().map(|k| k.chars().count()).max().unwrap_or(0) as u32;
    let mut opencc_st: Vec<(u32, u32)> = opencc_st_map
        .iter()
        .map(|(k, v)| (string_id[k.as_str()], string_id[v.as_str()]))
        .collect();
    opencc_st.sort_by_key(|&(k, _)| k);

    let opencc_ts_max_chars = opencc_ts_map.keys().map(|k| k.chars().count()).max().unwrap_or(0) as u32;
    let mut opencc_ts: Vec<(u32, u32)> = opencc_ts_map
        .iter()
        .map(|(k, v)| (string_id[k.as_str()], string_id[v.as_str()]))
        .collect();
    opencc_ts.sort_by_key(|&(k, _)| k);

    let mut stroke_seq: Vec<(u32, u32)> = stroke_seq_map
        .iter()
        .map(|(seq, chars)| (string_id[seq.as_str()], string_id[chars.as_str()]))
        .collect();
    stroke_seq.sort_by_key(|&(k, _)| k);

    // Serialize.
    let mut out_bytes = vec![0u8; HEADER_LEN];
    out_bytes[..8].copy_from_slice(MAGIC);
    let mut header: Vec<(Field, u32)> = vec![(Field::Version, VERSION)];
    let align = |buf: &mut Vec<u8>| while !buf.len().is_multiple_of(4) { buf.push(0) };

    let syl_text = names.join("\n");
    header.push((Field::SylCount, names.len() as u32));
    header.push((Field::SylOff, out_bytes.len() as u32));
    header.push((Field::SylLen, syl_text.len() as u32));
    out_bytes.extend_from_slice(syl_text.as_bytes());
    align(&mut out_bytes);

    header.push((Field::WordCount, strings.len() as u32));
    header.push((Field::WordOffsetsOff, out_bytes.len() as u32));
    let mut offset = 0u32;
    for s in &strings {
        out_bytes.extend_from_slice(&offset.to_le_bytes());
        offset += s.len() as u32;
    }
    out_bytes.extend_from_slice(&offset.to_le_bytes());
    header.push((Field::WordBlobOff, out_bytes.len() as u32));
    header.push((Field::WordBlobLen, offset));
    for s in &strings {
        out_bytes.extend_from_slice(s.as_bytes());
    }
    align(&mut out_bytes);

    header.push((Field::EntryCount, rows.len() as u32));
    header.push((Field::EntriesOff, out_bytes.len() as u32));
    let mut seq_pool: Vec<u16> = Vec::new();
    for row in &rows {
        out_bytes.extend_from_slice(&row.word.to_le_bytes());
        out_bytes.extend_from_slice(&(seq_pool.len() as u32).to_le_bytes());
        out_bytes.extend_from_slice(&row.score.to_le_bytes());
        out_bytes.push(row.seq.len() as u8);
        out_bytes.push(row.flags);
        seq_pool.extend_from_slice(&row.seq);
    }
    header.push((Field::SeqCount, seq_pool.len() as u32));
    header.push((Field::SeqOff, out_bytes.len() as u32));
    for s in &seq_pool {
        out_bytes.extend_from_slice(&s.to_le_bytes());
    }
    align(&mut out_bytes);

    let mut by_word: Vec<u32> = (0..rows.len() as u32).collect();
    by_word.sort_by(|&a, &b| {
        let (ra, rb) = (&rows[a as usize], &rows[b as usize]);
        ra.word.cmp(&rb.word).then(rb.score.cmp(&ra.score))
    });
    header.push((Field::ByWordOff, out_bytes.len() as u32));
    for i in &by_word {
        out_bytes.extend_from_slice(&i.to_le_bytes());
    }

    header.push((Field::AlphaCount, alpha.len() as u32));
    header.push((Field::AlphaOff, out_bytes.len() as u32));
    for (code, word, score, kind) in &alpha {
        out_bytes.extend_from_slice(&code.to_le_bytes());
        out_bytes.extend_from_slice(&word.to_le_bytes());
        out_bytes.extend_from_slice(&score.to_le_bytes());
        out_bytes.extend_from_slice(&kind.to_le_bytes());
    }

    header.push((Field::CtxCount, colloc.len() as u32));
    header.push((Field::CtxOff, out_bytes.len() as u32));
    for (p, n, w) in &colloc {
        out_bytes.extend_from_slice(&p.to_le_bytes());
        out_bytes.extend_from_slice(&n.to_le_bytes());
        out_bytes.extend_from_slice(&w.to_le_bytes());
    }

    header.push((Field::OpenccStCount, opencc_st.len() as u32));
    header.push((Field::OpenccStOff, out_bytes.len() as u32));
    header.push((Field::OpenccStMaxChars, opencc_st_max_chars));
    for (k, v) in &opencc_st {
        out_bytes.extend_from_slice(&k.to_le_bytes());
        out_bytes.extend_from_slice(&v.to_le_bytes());
    }

    header.push((Field::OpenccTsCount, opencc_ts.len() as u32));
    header.push((Field::OpenccTsOff, out_bytes.len() as u32));
    header.push((Field::OpenccTsMaxChars, opencc_ts_max_chars));
    for (k, v) in &opencc_ts {
        out_bytes.extend_from_slice(&k.to_le_bytes());
        out_bytes.extend_from_slice(&v.to_le_bytes());
    }

    header.push((Field::StrokeSeqCount, stroke_seq.len() as u32));
    header.push((Field::StrokeSeqOff, out_bytes.len() as u32));
    for (seq, chars) in &stroke_seq {
        out_bytes.extend_from_slice(&seq.to_le_bytes());
        out_bytes.extend_from_slice(&chars.to_le_bytes());
    }

    header.push((Field::StrokeSimpRankCount, stroke_simp_rank.len() as u32));
    header.push((Field::StrokeSimpRankOff, out_bytes.len() as u32));
    for (cp, rank) in &stroke_simp_rank {
        out_bytes.extend_from_slice(&cp.to_le_bytes());
        out_bytes.extend_from_slice(&rank.to_le_bytes());
    }

    header.push((Field::StrokeTradRankCount, stroke_trad_rank.len() as u32));
    header.push((Field::StrokeTradRankOff, out_bytes.len() as u32));
    for (cp, rank) in &stroke_trad_rank {
        out_bytes.extend_from_slice(&cp.to_le_bytes());
        out_bytes.extend_from_slice(&rank.to_le_bytes());
    }

    for (field, value) in header {
        let at = field as usize;
        out_bytes[at..at + 4].copy_from_slice(&value.to_le_bytes());
    }

    let bytes_len = out_bytes.len();
    write_dictionary(out, out_bytes, rows.len())?;
    Ok(Summary {
        entries: rows.len(),
        words: strings.len(),
        alpha: alpha.len(),
        collocations: colloc.len(),
        skipped: b.skipped,
        bytes: bytes_len,
    })
}

fn write_dictionary(out: &Path, bytes: Vec<u8>, entry_count: usize) -> Result<(), String> {
    static NEXT_TEMP: AtomicU64 = AtomicU64::new(0);
    let name = out.file_name().ok_or_else(|| format!("{}: missing output file name", out.display()))?;
    let parent = out.parent().filter(|path| !path.as_os_str().is_empty()).unwrap_or(Path::new("."));
    fs::create_dir_all(parent).map_err(|e| format!("{}: {e}", parent.display()))?;
    let (temporary, mut file) = loop {
        let serial = NEXT_TEMP.fetch_add(1, Ordering::Relaxed);
        let mut temporary_name = name.to_os_string();
        temporary_name.push(format!(".{}.{serial}.tmp", std::process::id()));
        let path = parent.join(temporary_name);
        match fs::OpenOptions::new().read(true).write(true).create_new(true).open(&path) {
            Ok(file) => break (path, file),
            Err(e) if e.kind() == ErrorKind::AlreadyExists => continue,
            Err(e) => return Err(format!("{}: {e}", path.display())),
        }
    };

    let result = (|| {
        file.write_all(&bytes).map_err(|e| format!("{}: {e}", temporary.display()))?;
        drop(bytes);
        {
            // SAFETY: This build created the file exclusively and leaves it untouched while mapped.
            let mapped = unsafe { memmap2::Mmap::map(&file) }
                .map_err(|e| format!("{}: {e}", temporary.display()))?;
            let check = dict::Dict::open(dict::Backing::Mapped(mapped))?;
            if check.entry_count() != entry_count {
                return Err("dictionary self-check failed".into());
            }
        }
        drop(file);
        fs::rename(&temporary, out).map_err(|e| format!("{}: {e}", out.display()))
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}
