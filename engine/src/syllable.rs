//! Pinyin syllable inventory, T9 key mapping and the lookup tables used to
//! turn typed letters or digits into syllable sets.

use crate::fxhash::FxHashMap;

/// Ordinary full syllables that may be produced from keyboard input.
/// Syllabic interjections (m/n/ng/hm/hng) may exist in the corpus but never
/// create input paths, which keeps segmentation stable.
pub const STANDARD: &[&str] = &[
    "a", "ai", "an", "ang", "ao", "ba", "bai", "ban", "bang", "bao", "bei", "ben", "beng", "bi",
    "bian", "biang", "biao", "bie", "bin", "bing", "bo", "bu", "ca", "cai", "can", "cang", "cao",
    "ce", "cei", "cen", "ceng", "cha", "chai", "chan", "chang", "chao", "che", "chen", "cheng",
    "chi", "chong", "chou", "chu", "chua", "chuai", "chuan", "chuang", "chui", "chun", "chuo",
    "ci", "cong", "cou", "cu", "cuan", "cui", "cun", "cuo", "da", "dai", "dan", "dang", "dao",
    "de", "dei", "den", "deng", "di", "dia", "dian", "diao", "die", "ding", "diu", "dong", "dou",
    "du", "duan", "dui", "dun", "duo", "e", "ei", "en", "eng", "er", "fa", "fan", "fang", "fei",
    "fen", "feng", "fiao", "fo", "fou", "fu", "ga", "gai", "gan", "gang", "gao", "ge", "gei",
    "gen", "geng", "gong", "gou", "gu", "gua", "guai", "guan", "guang", "gui", "gun", "guo", "ha",
    "hai", "han", "hang", "hao", "he", "hei", "hen", "heng", "hong", "hou", "hu", "hua", "huai",
    "huan", "huang", "hui", "hun", "huo", "ji", "jia", "jian", "jiang", "jiao", "jie", "jin",
    "jing", "jiong", "jiu", "ju", "juan", "jue", "jun", "ka", "kai", "kan", "kang", "kao", "ke",
    "kei", "ken", "keng", "kong", "kou", "ku", "kua", "kuai", "kuan", "kuang", "kui", "kun",
    "kuo", "la", "lai", "lan", "lang", "lao", "le", "lei", "leng", "li", "lia", "lian", "liang",
    "liao", "lie", "lin", "ling", "liu", "lo", "long", "lou", "lu", "luan", "lun", "luo", "lv",
    "lve", "ma", "mai", "man", "mang", "mao", "me", "mei", "men", "meng", "mi", "mian", "miao",
    "mie", "min", "ming", "miu", "mo", "mou", "mu", "na", "nai", "nan", "nang", "nao", "ne",
    "nei", "nen", "neng", "ni", "nian", "niang", "niao", "nie", "nin", "ning", "niu", "nong",
    "nou", "nu", "nuan", "nuo", "nv", "nve", "o", "ou", "pa", "pai", "pan", "pang", "pao", "pei",
    "pen", "peng", "pi", "pian", "piao", "pie", "pin", "ping", "po", "pou", "pu", "qi", "qia",
    "qian", "qiang", "qiao", "qie", "qin", "qing", "qiong", "qiu", "qu", "quan", "que", "qun",
    "ran", "rang", "rao", "re", "ren", "reng", "ri", "rong", "rou", "ru", "rua", "ruan", "rui",
    "run", "ruo", "sa", "sai", "san", "sang", "sao", "se", "sen", "seng", "sha", "shai", "shan",
    "shang", "shao", "she", "shei", "shen", "sheng", "shi", "shou", "shu", "shua", "shuai",
    "shuan", "shuang", "shui", "shun", "shuo", "si", "song", "sou", "su", "suan", "sui", "sun",
    "suo", "ta", "tai", "tan", "tang", "tao", "te", "tei", "teng", "ti", "tian", "tiao", "tie",
    "ting", "tong", "tou", "tu", "tuan", "tui", "tun", "tuo", "wa", "wai", "wan", "wang", "wei",
    "wen", "weng", "wo", "wu", "xi", "xia", "xian", "xiang", "xiao", "xie", "xin", "xing",
    "xiong", "xiu", "xu", "xuan", "xue", "xun", "ya", "yan", "yang", "yao", "ye", "yi", "yin",
    "ying", "yo", "yong", "you", "yu", "yuan", "yue", "yun", "za", "zai", "zan", "zang", "zao",
    "ze", "zei", "zen", "zeng", "zha", "zhai", "zhan", "zhang", "zhao", "zhe", "zhei", "zhen",
    "zheng", "zhi", "zhong", "zhou", "zhu", "zhua", "zhuai", "zhuan", "zhuang", "zhui", "zhun",
    "zhuo", "zi", "zong", "zou", "zu", "zuan", "zui", "zun", "zuo",
];

pub const MAX_SYLLABLE_LEN: usize = 6;

/// The keyboard accepts both spellings, and the dictionary stores a single ID.
pub(crate) fn canonical_syllable(name: &str) -> &str {
    match name {
        "lue" => "lve",
        "nue" => "nve",
        _ => name,
    }
}

pub fn t9_digit(c: u8) -> u8 {
    match c {
        b'a'..=b'c' => b'2',
        b'd'..=b'f' => b'3',
        b'g'..=b'i' => b'4',
        b'j'..=b'l' => b'5',
        b'm'..=b'o' => b'6',
        b'p'..=b's' => b'7',
        b't'..=b'v' => b'8',
        b'w'..=b'z' => b'9',
        other => other,
    }
}

pub fn to_t9(text: &str) -> String {
    text.bytes().map(|b| t9_digit(b.to_ascii_lowercase()) as char).collect()
}

/// Typed letters that act as a bare initial (abbreviation) on the keyboard.
pub fn is_initial(prefix: &str) -> bool {
    matches!(prefix, "zh" | "ch" | "sh")
        || (prefix.len() == 1 && "bpmfdtnlgkhjqxzcsryw".contains(prefix))
}

/// Fuzzy pinyin pairs in the order of their settings bits.
pub const FUZZY_PAIRS: &[(&str, &str, bool)] = &[
    ("zh", "z", true),
    ("ch", "c", true),
    ("sh", "s", true),
    ("n", "l", true),
    ("h", "f", true),
    ("ang", "an", false),
    ("eng", "en", false),
    ("ing", "in", false),
    ("iang", "ian", false),
];

/// Syllable inventory of one dictionary file plus derived input tables.
pub struct Syllables {
    pub names: Vec<String>,
    pub standard: Vec<bool>,
    index: FxHashMap<String, u16>,
    /// letters prefix (including complete syllables) -> standard ids, sorted.
    letter_prefix: FxHashMap<String, Vec<u16>>,
    /// letters prefix excluding its own exact standard syllable id, sorted.
    letter_prefix_others: FxHashMap<String, Vec<u16>>,
    /// full T9 code -> standard ids.
    t9_full: FxHashMap<String, Vec<u16>>,
    /// T9 code prefix -> standard ids.
    t9_prefix: FxHashMap<String, Vec<u16>>,
    /// T9 code prefix excluding ids in `t9_full`, sorted.
    t9_prefix_others: FxHashMap<String, Vec<u16>>,
    /// T9 code -> entered letter prefixes (complete syllables first, then lexicographical).
    t9_entered: FxHashMap<String, Vec<String>>,
    /// Precomputed fuzzy swap target per syllable id and fuzzy pair bit (0..9).
    fuzzy_table: Vec<[Option<u16>; 9]>,
}

impl Syllables {
    pub fn new(names: Vec<String>) -> Self {
        let mut index = FxHashMap::default();
        index.reserve(names.len());
        let mut standard = vec![false; names.len()];
        for (i, name) in names.iter().enumerate() {
            index.insert(name.clone(), i as u16);
        }
        let mut letter_prefix: FxHashMap<String, Vec<u16>> = FxHashMap::default();
        let mut t9_full: FxHashMap<String, Vec<u16>> = FxHashMap::default();
        let mut t9_prefix: FxHashMap<String, Vec<u16>> = FxHashMap::default();
        for name in STANDARD {
            let Some(&id) = index.get(*name) else { continue };
            standard[id as usize] = true;
            let code = to_t9(name);
            t9_full.entry(code.clone()).or_default().push(id);
            for len in 1..=name.len() {
                letter_prefix.entry(name[..len].to_string()).or_default().push(id);
                t9_prefix.entry(code[..len].to_string()).or_default().push(id);
            }
        }
        for map in [&mut letter_prefix, &mut t9_full, &mut t9_prefix] {
            for ids in map.values_mut() {
                ids.sort_unstable();
                ids.dedup();
            }
        }

        let mut letter_prefix_others: FxHashMap<String, Vec<u16>> = FxHashMap::default();
        letter_prefix_others.reserve(letter_prefix.len());
        for (prefix, ids) in &letter_prefix {
            let own = index.get(canonical_syllable(prefix)).copied().filter(|&id| standard[id as usize]);
            let others: Vec<u16> = ids.iter().copied().filter(|&x| Some(x) != own).collect();
            if !others.is_empty() {
                letter_prefix_others.insert(prefix.clone(), others);
            }
        }

        let mut t9_prefix_others: FxHashMap<String, Vec<u16>> = FxHashMap::default();
        t9_prefix_others.reserve(t9_prefix.len());
        for (code, ids) in &t9_prefix {
            let full = t9_full.get(code).map(Vec::as_slice).unwrap_or(&[]);
            let others: Vec<u16> = ids.iter().copied().filter(|x| full.binary_search(x).is_err()).collect();
            if !others.is_empty() {
                t9_prefix_others.insert(code.clone(), others);
            }
        }

        let mut t9_entered: FxHashMap<String, Vec<String>> = FxHashMap::default();
        for prefix in letter_prefix.keys() {
            t9_entered.entry(to_t9(prefix)).or_default().push(prefix.clone());
        }
        for list in t9_entered.values_mut() {
            list.sort_by(|a, b| {
                let fa = index.get(canonical_syllable(a)).copied().filter(|&id| standard[id as usize]).is_none();
                let fb = index.get(canonical_syllable(b)).copied().filter(|&id| standard[id as usize]).is_none();
                fa.cmp(&fb).then_with(|| a.cmp(b))
            });
        }

        let mut fuzzy_table = vec![[None; 9]; names.len()];
        for (id, name) in names.iter().enumerate() {
            if !standard[id] {
                continue;
            }
            for (bit, (long, short, initial)) in FUZZY_PAIRS.iter().enumerate() {
                let candidate = if *initial {
                    if let Some(rest) = name.strip_prefix(long) {
                        Some(format!("{short}{rest}"))
                    } else if let Some(rest) = name.strip_prefix(short) {
                        // "s" also prefixes "sh"; only swap a bare initial.
                        if !(short.len() == 1 && rest.starts_with('h') && long.len() == 2) {
                            Some(format!("{long}{rest}"))
                        } else {
                            None
                        }
                    } else {
                        None
                    }
                } else if let Some(head) = name.strip_suffix(long) {
                    Some(format!("{head}{short}"))
                } else {
                    name.strip_suffix(short).map(|head| format!("{head}{long}"))
                };
                if let Some(cand) = candidate {
                    if let Some(&other) = index.get(canonical_syllable(&cand)) {
                        if standard[other as usize] && other != id as u16 {
                            fuzzy_table[id][bit] = Some(other);
                        }
                    }
                }
            }
        }

        Syllables {
            names,
            standard,
            index,
            letter_prefix,
            letter_prefix_others,
            t9_full,
            t9_prefix,
            t9_prefix_others,
            t9_entered,
            fuzzy_table,
        }
    }

    pub fn id(&self, name: &str) -> Option<u16> {
        self.index.get(canonical_syllable(name)).copied()
    }

    pub fn name(&self, id: u16) -> &str {
        &self.names[id as usize]
    }

    /// The standard id for a complete syllable typed with letters.
    pub fn standard_id(&self, name: &str) -> Option<u16> {
        self.id(name).filter(|&id| self.standard[id as usize])
    }

    pub fn letter_prefix(&self, prefix: &str) -> &[u16] {
        self.letter_prefix.get(canonical_syllable(prefix)).map(Vec::as_slice).unwrap_or(&[])
    }

    pub(crate) fn letter_prefix_others(&self, prefix: &str) -> &[u16] {
        self.letter_prefix_others.get(canonical_syllable(prefix)).map(Vec::as_slice).unwrap_or(&[])
    }

    pub fn t9_full(&self, code: &str) -> &[u16] {
        self.t9_full.get(code).map(Vec::as_slice).unwrap_or(&[])
    }

    pub fn t9_prefix(&self, code: &str) -> &[u16] {
        self.t9_prefix.get(code).map(Vec::as_slice).unwrap_or(&[])
    }

    pub(crate) fn t9_prefix_others(&self, code: &str) -> &[u16] {
        self.t9_prefix_others.get(code).map(Vec::as_slice).unwrap_or(&[])
    }

    pub fn is_entered_prefix(&self, letters: &str) -> bool {
        self.letter_prefix.contains_key(canonical_syllable(letters))
    }

    /// Letter strings whose keys equal `digits` and that begin at least one
    /// standard syllable, complete syllables first.
    pub fn entered_from_digits(&self, digits: &str) -> Vec<String> {
        self.t9_entered.get(digits).cloned().unwrap_or_default()
    }

    /// Standard syllables reachable from `id` through the enabled fuzzy pairs.
    pub fn fuzzy_variants(&self, id: u16, mask: u32) -> Vec<u16> {
        let Some(row) = self.fuzzy_table.get(id as usize) else {
            return Vec::new();
        };
        let mut out = Vec::new();
        let mut bits = mask & ((1u32 << FUZZY_PAIRS.len()) - 1);
        while bits != 0 {
            let bit = bits.trailing_zeros() as usize;
            bits &= bits - 1;
            if let Some(other) = row[bit] {
                if !out.contains(&other) {
                    out.push(other);
                }
            }
        }
        out.sort_unstable();
        out
    }
}

/// Splits a stored reading ("ni hao", "ni'hao") into syllable names.
pub fn split_reading(reading: &str) -> Vec<&str> {
    reading
        .split([' ', '\'', '·'])
        .filter(|s| !s.is_empty())
        .collect()
}
