//! Lightweight non-cryptographic FxHash collections for internal hot paths.
//! Zero external dependencies so offline builds stay self-contained.

use std::collections::{HashMap, HashSet};
use std::hash::{BuildHasherDefault, Hasher};

#[cfg(target_pointer_width = "64")]
const K: usize = 0x517c_c1b7_2722_0a95;
#[cfg(target_pointer_width = "32")]
const K: usize = 0x9e37_79b9;

#[derive(Default)]
pub(crate) struct FxHasher {
    hash: usize,
}

impl FxHasher {
    #[inline]
    fn add_to_hash(&mut self, i: usize) {
        self.hash = self.hash.rotate_left(5).bitxor_mul(i);
    }
}

trait BitXorMul {
    fn bitxor_mul(self, other: Self) -> Self;
}

impl BitXorMul for usize {
    #[inline]
    fn bitxor_mul(self, other: Self) -> Self {
        (self ^ other).wrapping_mul(K)
    }
}

impl Hasher for FxHasher {
    #[inline]
    fn write(&mut self, mut bytes: &[u8]) {
        #[cfg(target_pointer_width = "64")]
        while bytes.len() >= 8 {
            let chunk = u64::from_ne_bytes([
                bytes[0], bytes[1], bytes[2], bytes[3],
                bytes[4], bytes[5], bytes[6], bytes[7],
            ]);
            self.add_to_hash(chunk as usize);
            bytes = &bytes[8..];
        }
        while bytes.len() >= 4 {
            let chunk = u32::from_ne_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]);
            self.add_to_hash(chunk as usize);
            bytes = &bytes[4..];
        }
        if bytes.len() >= 2 {
            let chunk = u16::from_ne_bytes([bytes[0], bytes[1]]);
            self.add_to_hash(chunk as usize);
            bytes = &bytes[2..];
        }
        if let Some(&last) = bytes.first() {
            self.add_to_hash(last as usize);
        }
    }

    #[inline]
    fn write_u8(&mut self, i: u8) {
        self.add_to_hash(i as usize);
    }

    #[inline]
    fn write_u16(&mut self, i: u16) {
        self.add_to_hash(i as usize);
    }

    #[inline]
    fn write_u32(&mut self, i: u32) {
        self.add_to_hash(i as usize);
    }

    #[inline]
    fn write_u64(&mut self, i: u64) {
        #[cfg(target_pointer_width = "64")]
        self.add_to_hash(i as usize);
        #[cfg(target_pointer_width = "32")]
        {
            self.add_to_hash(i as usize);
            self.add_to_hash((i >> 32) as usize);
        }
    }

    #[inline]
    fn write_usize(&mut self, i: usize) {
        self.add_to_hash(i);
    }

    #[inline]
    fn finish(&self) -> u64 {
        self.hash as u64
    }
}

pub(crate) type FxBuildHasher = BuildHasherDefault<FxHasher>;
pub(crate) type FxHashMap<K, V> = HashMap<K, V, FxBuildHasher>;
pub(crate) type FxHashSet<T> = HashSet<T, FxBuildHasher>;
