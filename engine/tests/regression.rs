use kaixin_engine::compile;
use kaixin_engine::dict::{Backing, Dict, Field, FLAG_CORE, VERSION};
use kaixin_engine::engine::{parse_parts, split_all};
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
