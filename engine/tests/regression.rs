use kaixin_engine::compile;
use kaixin_engine::dict::{Backing, Dict, Field, FLAG_CORE, VERSION};
use kaixin_engine::engine::{
    parse_parts, split_all, Engine, Request, F_FULL, F_SHORT, MODE_PINYIN, TIER_EXACT_FULL,
};
use kaixin_engine::graph::Part;
use kaixin_engine::syllable::{Syllables, STANDARD};
use kaixin_engine::user::UserModel;
use std::fs;
use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};

fn standard_syllables() -> Syllables {
    Syllables::new(STANDARD.iter().map(|s| s.to_string()).collect())
}

#[test]
fn segmentation_keeps_both_readings_of_xian() {
    let syllables = standard_syllables();
    let paths = split_all(&syllables, "xian", 32);
    assert!(paths.iter().any(|path| path == &["xian"]));
    assert!(paths.iter().any(|path| path == &["xi", "an"]));
    assert!(split_all(&syllables, "xyz", 32).is_empty());
}

#[test]
fn editor_constraints_keep_the_original_key_spans() {
    let encoded = "~0,2,\u{1f}=2,5,hao\u{1f}^5,6,sh\u{1f}|6,8,kuai,king";
    let parts = parse_parts(encoded).unwrap();
    assert!(matches!(
        parts.as_slice(),
        [
            Part::Raw { start: 0, end: 2 },
            Part::Locked { start: 2, end: 5, reading },
            Part::LockedPrefix { start: 5, end: 6, prefix },
            Part::Alternatives { start: 6, end: 8, readings },
        ] if reading == "hao" && prefix == "sh" && readings == &["kuai", "king"]
    ));
    assert!(parse_parts("").is_none());
    assert!(parse_parts("=a,2,ni").is_none());
    assert!(parse_parts("?0,2,ni").is_none());
}

struct Fixture(PathBuf);

impl Fixture {
    fn new() -> Self {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let path = std::env::temp_dir().join(format!("kaixin-dict-{}-{stamp}", std::process::id()));
        fs::create_dir(&path).unwrap();
        Self(path)
    }

    fn write(&self, name: &str, data: &str) {
        let path = self.0.join(name);
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, data).unwrap();
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

#[test]
fn compiled_dictionary_round_trips_readings_and_collocations() {
    let fixture = Fixture::new();
    for folder in ["lexicon/zh", "lexicon/zh-ext"] {
        fs::create_dir_all(fixture.0.join(folder)).unwrap();
    }
    fixture.write(
        "lexicon/core/fixture.txt",
        "你好\tni hao\t9000\n世界\tshi jie\t6000\n",
    );
    fixture.write(
        "lexicon/en/kaixin_common_english.txt",
        "Hello\thello\t8000\n",
    );
    for file in [
        "lexicon/en/kaixin_mixed_100.txt",
        "data_sources/desktop_single_char_readings.txt",
        "app/src/main/assets/lexicon-corrections/rules.tsv",
        "app/src/main/assets/lexicon-corrections/idioms.txt",
        "app/src/main/assets/lexicon-corrections/organizations.txt",
    ] {
        fixture.write(file, "");
    }
    fixture.write(
        "app/src/main/assets/language-model/collocations.tsv",
        "你好\t世界\t120\n",
    );
    let output = fixture.0.join("fixture.dict");
    let summary = compile::build(&fixture.0, &output, None).unwrap();
    assert_eq!(summary.entries, 2);
    let mut bytes = fs::read(output).unwrap();
    let dict = Dict::open(Backing::Owned(bytes.clone())).unwrap();
    let hello = dict.word_id("你好").unwrap();
    let world = dict.word_id("世界").unwrap();
    let entries = dict.entries_of_word(hello);
    assert_eq!(dict.word(hello), "你好");
    assert_eq!(entries.len(), 1);
    assert_eq!(entries[0].score, 9000);
    assert_eq!(entries[0].flags, FLAG_CORE);
    assert_eq!(
        dict.entry_syllables(&entries[0]),
        ["ni", "hao"].map(|reading| dict.syllables.id(reading).unwrap())
    );
    assert_eq!(dict.collocations(hello), [(world, 120)]);
    let alpha_row = dict.alpha_prefix("hel").next().unwrap();
    assert_eq!(dict.word(dict.alpha(alpha_row).1), "Hello");

    let version_offset = Field::Version as usize;
    bytes[version_offset..version_offset + 4].copy_from_slice(&(VERSION + 1).to_le_bytes());
    assert_eq!(
        Dict::open(Backing::Owned(bytes)).err().as_deref(),
        Some("unsupported dictionary version")
    );
}

#[test]
fn personal_data_round_trips_learned_words_and_context() {
    let syllables = standard_syllables();
    let mut original = UserModel::default();
    original.import(
        &syllables,
        Some("W\t你好\tni hao\t9\t100\t1\nW\tHello\thello\t6\t200\t2\n"),
        Some("KXCTX1\nB\t你好\t世界\t3\t300\nT\t大家\t你好\t世界\t2\t400\n"),
    );
    let words = original.export_words(&syllables);
    let context = original.export_context();
    let mut restored = UserModel::default();
    restored.import(&syllables, Some(&words), Some(&context));
    assert_eq!(restored.export_words(&syllables), words);
    assert_eq!(restored.export_context(), context);
    let reading = ["ni", "hao"].map(|s| syllables.id(s).unwrap());
    assert_eq!(restored.bonus("你好", &reading, 500), 3_000);
    assert_eq!(restored.english_prefix("hel", 500)[0].0, "Hello");
    assert_eq!(restored.predict("大家", "你好"), [("世界".into(), 12)]);
}

#[test]
fn standard_syllables_and_entered_prefix_cover_rare_and_umlaut_spellings() {
    let syllables = standard_syllables();
    for syl in ["biang", "cei", "fiao", "kei", "tei"] {
        assert!(
            syllables.standard_id(syl).is_some(),
            "expected '{syl}' in STANDARD syllables"
        );
    }
    assert!(syllables.is_entered_prefix("lue"));
    assert!(syllables.is_entered_prefix("nue"));
    assert_eq!(syllables.standard_id("lue"), syllables.standard_id("lve"));
    assert_eq!(syllables.standard_id("nue"), syllables.standard_id("nve"));
}

#[test]
fn compiled_dictionary_includes_supplemental_and_rare_syllable_single_chars() {
    let fixture = Fixture::new();
    for folder in ["lexicon/core", "lexicon/zh", "lexicon/zh-ext"] {
        fs::create_dir_all(fixture.0.join(folder)).unwrap();
    }
    for file in [
        "lexicon/en/kaixin_common_english.txt",
        "lexicon/en/kaixin_mixed_100.txt",
        "app/src/main/assets/lexicon-corrections/rules.tsv",
        "app/src/main/assets/lexicon-corrections/idioms.txt",
        "app/src/main/assets/lexicon-corrections/organizations.txt",
        "app/src/main/assets/language-model/collocations.tsv",
    ] {
        fixture.write(file, "");
    }
    fixture.write(
        "data_sources/desktop_single_char_readings.txt",
        "忒\ttei\t800\n刻\tkei\t600\n略\tlve\t5000\n",
    );
    let output = fixture.0.join("singles.dict");
    compile::build(&fixture.0, &output, None).unwrap();
    let engine = Engine::new(Backing::Owned(fs::read(output).unwrap())).unwrap();

    for (raw, expected) in [
        ("tei", "忒"),
        ("kei", "刻"),
        ("lue", "略"),
        ("ling", "〇"),
        ("tong", "々"),
        ("shen", "谌"),
        ("lao", "乐"),
        ("yao", "乐"),
    ] {
        let found = engine.query(&Request {
            mode: MODE_PINYIN,
            raw,
            parts: None,
            prev2: "",
            prev: "",
            flags: F_FULL | F_SHORT,
            limit: 64,
            touch_corrections: &[],
        });
        assert!(
            found.iter().any(|c| c.word == expected && c.tier <= TIER_EXACT_FULL),
            "expected '{expected}' as exact candidate for '{raw}', got {:?}",
            found.iter().map(|c| &c.word).collect::<Vec<_>>()
        );
    }
}

#[test]
fn single_syllable_query_never_evicts_low_frequency_exact_chars_under_limit() {
    let fixture = Fixture::new();
    for folder in ["lexicon/core", "lexicon/zh", "lexicon/zh-ext"] {
        fs::create_dir_all(fixture.0.join(folder)).unwrap();
    }
    for file in [
        "lexicon/en/kaixin_common_english.txt",
        "lexicon/en/kaixin_mixed_100.txt",
        "app/src/main/assets/lexicon-corrections/rules.tsv",
        "app/src/main/assets/lexicon-corrections/idioms.txt",
        "app/src/main/assets/lexicon-corrections/organizations.txt",
        "app/src/main/assets/language-model/collocations.tsv",
    ] {
        fixture.write(file, "");
    }
    let mut singles = String::new();
    // 10 exact "xi" characters including very low frequency ones (score = 1)
    let exact_chars = ['西', '希', '喜', '洗', '系', '戏', '细', '习', '夕', '兮'];
    for (idx, ch) in exact_chars.iter().enumerate() {
        let score = if idx < 5 { 9000 - idx * 500 } else { 1 };
        singles.push_str(&format!("{ch}\txi\t{score}\n"));
    }
    // 40 high-frequency prefix "xian" / "xiao" characters that outscore score=1 even with prefix penalty
    let prefix_chars = "先现线县限显险鲜闲献贤咸嫌衔掀仙纤弦馅腺小笑校效消销晓肖萧孝硝削哮嚣宵潇霄逍晓晓";
    for (idx, ch) in prefix_chars.chars().enumerate() {
        let syl = if idx < 20 { "xian" } else { "xiao" };
        singles.push_str(&format!("{ch}\t{syl}\t9500\n"));
    }
    fixture.write("data_sources/desktop_single_char_readings.txt", &singles);

    let output = fixture.0.join("evict.dict");
    compile::build(&fixture.0, &output, None).unwrap();
    let engine = Engine::new(Backing::Owned(fs::read(output).unwrap())).unwrap();

    // Limit = 16 is much smaller than total candidates (~50), but larger than exact_chars (10).
    let found = engine.query(&Request {
        mode: MODE_PINYIN,
        raw: "xi",
        parts: None,
        prev2: "",
        prev: "",
        flags: F_FULL | F_SHORT,
        limit: 16,
        touch_corrections: &[],
    });
    assert_eq!(found.len(), 16);
    for ch in exact_chars {
        let s = ch.to_string();
        assert!(
            found.iter().any(|c| c.word == s),
            "exact single char '{s}' was evicted from top-16 for 'xi': {:?}",
            found.iter().map(|c| &c.word).collect::<Vec<_>>()
        );
    }
}
