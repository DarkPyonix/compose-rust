// SHA-256, written out here because the build script verifies a download before it
// unpacks it and has nothing to verify with.
//
// The two obvious alternatives were both worse. A `build-dependencies` entry for a hash
// crate puts that crate, and its own tree, into every consumer's build. Shelling out to
// `shasum`, `sha256sum` or `certutil` makes the check depend on which of those happens to
// be installed and on the shape of its output, and the natural response to a missing tool
// is to skip the check, which is the same as not checking. Sixty lines of FIPS 180-4 is
// cheaper than either.
//
// `include!`d by `build/renderer_dir.rs`, so `tests/renderer_resolution.rs` gets the same
// code the build runs.

use std::fs::File;
use std::io::{BufReader, Read};
use std::path::Path;

const ROUND_CONSTANTS: [u32; 64] = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
];

const INITIAL_STATE: [u32; 8] = [
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
];

pub struct Sha256 {
    state: [u32; 8],
    block: [u8; 64],
    filled: usize,
    total_bytes: u64,
}

impl Default for Sha256 {
    fn default() -> Self {
        Self::new()
    }
}

impl Sha256 {
    pub fn new() -> Self {
        Self {
            state: INITIAL_STATE,
            block: [0; 64],
            filled: 0,
            total_bytes: 0,
        }
    }

    pub fn update(&mut self, mut bytes: &[u8]) {
        self.total_bytes = self.total_bytes.wrapping_add(bytes.len() as u64);
        while !bytes.is_empty() {
            let take = (64 - self.filled).min(bytes.len());
            self.block[self.filled..self.filled + take].copy_from_slice(&bytes[..take]);
            self.filled += take;
            bytes = &bytes[take..];
            if self.filled == 64 {
                let block = self.block;
                self.compress(&block);
                self.filled = 0;
            }
        }
    }

    /// The digest as lowercase hex, which is how `shasum -a 256` writes it and therefore
    /// how the published `.sha256` files spell it.
    pub fn finish_hex(mut self) -> String {
        let bit_length = self.total_bytes.wrapping_mul(8);
        self.update(&[0x80]);
        // The length occupies the last eight bytes of the final block. If the padding byte
        // left no room for it, fill this block out and let the length start a new one.
        while self.filled != 56 {
            self.update(&[0]);
        }
        self.total_bytes = 0;
        self.update(&bit_length.to_be_bytes());

        let mut hex = String::with_capacity(64);
        for word in self.state {
            hex.push_str(&format!("{word:08x}"));
        }
        hex
    }

    fn compress(&mut self, block: &[u8; 64]) {
        let mut schedule = [0u32; 64];
        for (index, word) in schedule.iter_mut().take(16).enumerate() {
            let start = index * 4;
            *word = u32::from_be_bytes([
                block[start],
                block[start + 1],
                block[start + 2],
                block[start + 3],
            ]);
        }
        for index in 16..64 {
            let s0 = schedule[index - 15].rotate_right(7)
                ^ schedule[index - 15].rotate_right(18)
                ^ (schedule[index - 15] >> 3);
            let s1 = schedule[index - 2].rotate_right(17)
                ^ schedule[index - 2].rotate_right(19)
                ^ (schedule[index - 2] >> 10);
            schedule[index] = schedule[index - 16]
                .wrapping_add(s0)
                .wrapping_add(schedule[index - 7])
                .wrapping_add(s1);
        }

        let [mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut h] = self.state;
        for index in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let choice = (e & f) ^ (!e & g);
            let temp1 = h
                .wrapping_add(s1)
                .wrapping_add(choice)
                .wrapping_add(ROUND_CONSTANTS[index])
                .wrapping_add(schedule[index]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let majority = (a & b) ^ (a & c) ^ (b & c);
            let temp2 = s0.wrapping_add(majority);

            h = g;
            g = f;
            f = e;
            e = d.wrapping_add(temp1);
            d = c;
            c = b;
            b = a;
            a = temp1.wrapping_add(temp2);
        }

        for (slot, value) in self
            .state
            .iter_mut()
            .zip([a, b, c, d, e, f, g, h])
        {
            *slot = slot.wrapping_add(value);
        }
    }
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    let mut digest = Sha256::new();
    digest.update(bytes);
    digest.finish_hex()
}

/// Streamed, because the artifact is tens of megabytes and a build script has no reason
/// to hold all of it at once.
pub fn sha256_file(path: &Path) -> std::io::Result<String> {
    let mut reader = BufReader::new(File::open(path)?);
    let mut digest = Sha256::new();
    let mut buffer = [0u8; 64 * 1024];
    loop {
        let read = reader.read(&mut buffer)?;
        if read == 0 {
            return Ok(digest.finish_hex());
        }
        digest.update(&buffer[..read]);
    }
}

/// The checksum out of a `shasum -a 256` line, which is the digest, two spaces, and the
/// file name. Only the digest is compared: the name in the file is whatever the release
/// job happened to pass on its command line.
pub fn checksum_from_sha256_file(contents: &str) -> Option<String> {
    let token = contents.split_whitespace().next()?;
    let digest = token.trim().to_ascii_lowercase();
    if digest.len() == 64 && digest.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        Some(digest)
    } else {
        None
    }
}
