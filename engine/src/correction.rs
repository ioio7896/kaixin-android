use crate::syllable::{Syllables, MAX_SYLLABLE_LEN};

const EDITS_PER_KIND: usize = 4;
const INSERT_LETTERS: &[u8] = b"aeiouvnghrszc";
const MAX_CORRECTION_LEN: usize = 36;

/// QWERTY keyboard neighbors per letter ('a'..='z'): same-row adjacent first, then immediate vertical neighbors.
fn neighbors(letter: u8) -> &'static [(u8, i32)] {
    match letter {
        b'a' => &[(b's', 380), (b'q', 410), (b'w', 410), (b'z', 410)],
        b'b' => &[(b'v', 380), (b'n', 380), (b'g', 410), (b'h', 410)],
        b'c' => &[(b'x', 380), (b'v', 380), (b'd', 410), (b'f', 410)],
        b'd' => &[(b's', 380), (b'f', 380), (b'e', 410), (b'c', 410)],
        b'e' => &[(b'w', 380), (b'r', 380), (b'd', 410), (b's', 410)],
        b'f' => &[(b'd', 380), (b'g', 380), (b'r', 410), (b'v', 410)],
        b'g' => &[(b'f', 380), (b'h', 380), (b't', 410), (b'b', 410)],
        b'h' => &[(b'g', 380), (b'j', 380), (b'y', 410), (b'n', 410)],
        b'i' => &[(b'u', 380), (b'o', 380), (b'k', 410), (b'j', 410)],
        b'j' => &[(b'h', 380), (b'k', 380), (b'u', 410), (b'm', 410)],
        b'k' => &[(b'j', 380), (b'l', 380), (b'i', 410), (b'm', 410)],
        b'l' => &[(b'k', 380), (b'o', 410), (b'p', 410)],
        b'm' => &[(b'n', 380), (b'j', 410), (b'k', 410)],
        b'n' => &[(b'b', 380), (b'm', 380), (b'h', 410), (b'j', 410)],
        b'o' => &[(b'i', 380), (b'p', 380), (b'l', 410), (b'k', 410)],
        b'p' => &[(b'o', 380), (b'l', 410)],
        b'q' => &[(b'w', 380), (b'a', 410)],
        b'r' => &[(b'e', 380), (b't', 380), (b'f', 410), (b'd', 410)],
        b's' => &[(b'a', 380), (b'd', 380), (b'w', 410), (b'x', 410)],
        b't' => &[(b'r', 380), (b'y', 380), (b'g', 410), (b'f', 410)],
        b'u' => &[(b'y', 380), (b'i', 380), (b'j', 410), (b'h', 410)],
        b'v' => &[(b'c', 380), (b'b', 380), (b'f', 410), (b'g', 410)],
        b'w' => &[(b'q', 380), (b'e', 380), (b's', 410), (b'a', 410)],
        b'x' => &[(b'z', 380), (b'c', 380), (b's', 410), (b'd', 410)],
        b'y' => &[(b't', 380), (b'u', 380), (b'h', 410), (b'g', 410)],
        b'z' => &[(b'x', 380), (b'a', 410), (b's', 410)],
        _ => &[],
    }
}

pub fn variants(syllables: &Syllables, raw: &str, spelling: bool, touches: &[(String, i32)]) -> Vec<(String, i32)> {
    let mut out = Vec::new();
    for (code, penalty) in touches.iter().take(3) {
        if (180..=310).contains(penalty) && code.len() == raw.len() {
            add(&mut out, syllables, raw, code.as_bytes(), *penalty);
        }
    }
    if !spelling || raw.len() + 1 >= MAX_CORRECTION_LEN {
        return out;
    }

    let raw_bytes = raw.as_bytes();
    let n = raw_bytes.len();
    let mut buf = [0u8; MAX_CORRECTION_LEN];
    let mut swaps = Vec::new();
    let mut deletions = Vec::new();
    let mut replacements = Vec::new();
    let mut insertions = Vec::new();

    for i in (0..n).rev() {
        if swaps.len() < EDITS_PER_KIND && i > 0 && raw_bytes[i - 1] != raw_bytes[i] {
            buf[..n].copy_from_slice(raw_bytes);
            buf.swap(i - 1, i);
            add(&mut swaps, syllables, raw, &buf[..n], 280);
        }
        if deletions.len() < EDITS_PER_KIND {
            buf[..i].copy_from_slice(&raw_bytes[..i]);
            buf[i..n - 1].copy_from_slice(&raw_bytes[i + 1..]);
            add(&mut deletions, syllables, raw, &buf[..n - 1], 340);
        }
        if replacements.len() < EDITS_PER_KIND {
            for &(letter, penalty) in neighbors(raw_bytes[i]) {
                if replacements.len() >= EDITS_PER_KIND {
                    break;
                }
                buf[..n].copy_from_slice(raw_bytes);
                buf[i] = letter;
                add(&mut replacements, syllables, raw, &buf[..n], penalty);
            }
        }
    }
    'positions: for i in (0..=n).rev() {
        for &letter in INSERT_LETTERS {
            if insertions.len() >= EDITS_PER_KIND {
                break 'positions;
            }
            buf[..i].copy_from_slice(&raw_bytes[..i]);
            buf[i] = letter;
            buf[i + 1..n + 1].copy_from_slice(&raw_bytes[i..]);
            add(&mut insertions, syllables, raw, &buf[..n + 1], 420);
        }
    }
    out.extend(swaps);
    out.extend(deletions);
    out.extend(replacements);
    out.extend(insertions);
    out
}

fn add(out: &mut Vec<(String, i32)>, syllables: &Syllables, raw: &str, bytes: &[u8], penalty: i32) {
    if bytes.len() >= MAX_CORRECTION_LEN || !bytes.iter().all(u8::is_ascii_lowercase) {
        return;
    }
    // SAFETY: all bytes are ASCII lowercase.
    let code = unsafe { std::str::from_utf8_unchecked(bytes) };
    if code == raw || out.iter().any(|(existing, _)| existing == code) {
        return;
    }

    // Count complete syllables along a valid split, ignoring single-syllable edits.
    let mut count = [0u8; MAX_CORRECTION_LEN];
    for start in 0..code.len() {
        if start > 0 && count[start] == 0 {
            continue;
        }
        for end in start + 1..=code.len().min(start + MAX_SYLLABLE_LEN) {
            if syllables.standard_id(&code[start..end]).is_some() {
                count[end] = count[end].max(count[start] + 1);
            }
        }
    }
    if count[code.len()] >= 2 {
        out.push((code.to_string(), penalty));
    }
}
