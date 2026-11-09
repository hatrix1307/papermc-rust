//! C ABI over Horizon's vanilla 26.3 world generator.
//!
//! The Paper plugin binds these functions with Java's Foreign Function & Memory API.
//! Every function is safe to call from many threads at once: a handle owns one
//! seeded generator and a pool of per-thread workers.
//!
//! Buffers are caller-owned. Block layout: `((y - min_y) * 16 + z) * 16 + x`, local
//! x/z in 0..16. Biome layout (quarts): `(qy * 4 + qz) * 4 + qx`, qy from the
//! bottom of the chunk. Strings come back NUL-free into a byte buffer; the return
//! value is the full length so the caller can retry with a larger buffer.

use horizon_core::position::ChunkPos;
use horizon_world::{
    DATA_VERSION,
    nbt::Tag,
    registry::{BlockRegistry, BlockStateId},
    worldgen::{
        generator::{GeneratorData, GeneratorWorker, OverworldGenerator},
        overrides::{PackFile, WorldgenOverrides},
    },
};
use serde_json::Value;
use std::{
    ffi::{CStr, c_char},
    fs,
    panic::{AssertUnwindSafe, catch_unwind},
    path::Path,
    sync::{Arc, Mutex},
};

type Error = Box<dyn std::error::Error + Send + Sync>;

pub struct Handle {
    data: Arc<GeneratorData>,
    registry: &'static BlockRegistry,
    generator: OverworldGenerator,
    workers: Mutex<Vec<GeneratorWorker>>,
    /// Block state strings (`name[k=v,...]`), by Horizon state id.
    states: Vec<String>,
    /// The server's own block-state ids (`Block.BLOCK_STATE_REGISTRY`), by Horizon state id,
    /// for `hz_generate_packed`. Set once by `hz_set_server_ids`.
    server_ids: std::sync::OnceLock<Vec<u32>>,
    /// The `minecraft:air` state id (sections of only this are skipped).
    air: u32,
    min_y: i32,
    height: i32,
}

impl Handle {
    fn with_worker<T>(&self, f: impl FnOnce(&mut GeneratorWorker) -> T) -> T {
        let mut worker = self
            .workers
            .lock()
            .unwrap()
            .pop()
            .unwrap_or_else(|| self.generator.worker());
        let result = f(&mut worker);
        self.workers.lock().unwrap().push(worker);
        result
    }
}

/// The client resources' `facts.json` block registry, as the probes load it.
fn registry(resources: &Path) -> Result<&'static BlockRegistry, Error> {
    fn tag(v: &Value) -> Tag {
        match v {
            Value::String(s) => Tag::String(s.as_str().into()),
            Value::Number(n) => Tag::Int(n.as_i64().unwrap_or(0) as i32),
            Value::Array(a) => Tag::List(a.iter().map(tag).collect()),
            Value::Object(m) => {
                Tag::Compound(m.iter().map(|(k, v)| (k.as_str().into(), tag(v))).collect())
            }
            _ => Tag::End,
        }
    }
    let facts: Value = serde_json::from_str(&fs::read_to_string(resources.join("facts.json"))?)?;
    let states = facts["states"].as_array().ok_or("facts.json has no states")?;
    let registry = BlockRegistry::from_nbt(&Tag::compound([
        ("DataVersion", Tag::Int(DATA_VERSION)),
        (
            "states",
            Tag::List(states.iter().map(|v| tag(&v[4])).collect()),
        ),
        ("defaults", tag(&facts["defaults"])),
    ]))?;
    Ok(Box::leak(Box::new(registry)))
}

/// A world's datapacks folder: every folder pack, enabled after vanilla in name order.
/// Zipped packs are not read yet.
fn datapacks(dir: &Path) -> Result<WorldgenOverrides, Error> {
    fn walk(root: &Path, dir: &Path, out: &mut Vec<String>) {
        let Ok(entries) = fs::read_dir(dir) else {
            return;
        };
        let mut entries: Vec<_> = entries.flatten().map(|e| e.path()).collect();
        entries.sort();
        for path in entries {
            if path.is_dir() {
                walk(root, &path, out);
            } else if let Ok(rel) = path.strip_prefix(root) {
                out.push(rel.to_string_lossy().replace('\\', "/"));
            }
        }
    }
    let mut overrides = WorldgenOverrides::default();
    let Ok(entries) = fs::read_dir(dir) else {
        return Ok(overrides);
    };
    let mut packs: Vec<_> = entries
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.join("pack.mcmeta").exists())
        .collect();
    packs.sort();
    for pack in packs {
        let id = format!(
            "file/{}",
            pack.file_name().unwrap_or_default().to_string_lossy()
        );
        let mut files = Vec::new();
        walk(&pack, &pack.join("data"), &mut files);
        for file in files {
            let read = || fs::read_to_string(pack.join(&file)).ok();
            if let PackFile::Unreadable = overrides.add_file(&id, &file, read) {
                return Err(format!("{id}: {file}: unreadable").into());
            }
        }
    }
    for warning in overrides.finish(|_| true) {
        eprintln!("[horizon-bridge] data pack worldgen: {warning}");
    }
    Ok(overrides)
}

fn state_string(registry: &BlockRegistry, id: u32) -> String {
    let Some(state) = registry.state(BlockStateId(id)) else {
        return "minecraft:air".into();
    };
    let mut s = state.name.clone();
    if !state.properties.is_empty() {
        s.push('[');
        for (i, (k, v)) in state
            .properties
            .keys()
            .zip(state.properties.values())
            .enumerate()
        {
            if i > 0 {
                s.push(',');
            }
            s.push_str(k);
            s.push('=');
            s.push_str(v);
        }
        s.push(']');
    }
    s
}

fn open(
    resources: &Path,
    datapacks_dir: Option<&Path>,
    seed: i64,
    dimension: &str,
) -> Result<Handle, Error> {
    let registry = registry(resources)?;
    let overrides = match datapacks_dir {
        Some(dir) => datapacks(dir)?,
        None => WorldgenOverrides::default(),
    };
    let data = GeneratorData::load_with(&resources.join("worldgen"), registry, &overrides)?;
    for warning in data.warnings() {
        eprintln!("[horizon-bridge] data pack worldgen: {warning}");
    }
    let generator =
        OverworldGenerator::for_dimension(data.clone(), seed, "minecraft:normal", dimension)?;
    let mut worker = generator.worker();
    // One chunk up front: the dimension's build range, and a warm cache.
    let probe = worker.generate_proto(ChunkPos::new(0, 0))?;
    let (min_y, height) = (probe.terrain.min_y(), probe.terrain.height());
    let states: Vec<String> = (0..registry.len() as u32)
        .map(|id| state_string(registry, id))
        .collect();
    let air = states
        .iter()
        .position(|s| s == "minecraft:air")
        .map_or(u32::MAX, |i| i as u32);
    Ok(Handle {
        data,
        registry,
        generator,
        workers: Mutex::new(vec![worker]),
        states,
        server_ids: std::sync::OnceLock::new(),
        air,
        min_y,
        height,
    })
}

unsafe fn path_arg<'a>(p: *const c_char) -> Option<&'a Path> {
    if p.is_null() {
        return None;
    }
    let s = unsafe { CStr::from_ptr(p) }.to_str().ok()?;
    (!s.is_empty()).then(|| Path::new(s))
}

unsafe fn write_str(s: &str, buf: *mut u8, len: usize) -> usize {
    if !buf.is_null() {
        let n = s.len().min(len);
        unsafe { std::ptr::copy_nonoverlapping(s.as_ptr(), buf, n) };
    }
    s.len()
}

/// Opens a generator. `resources` is horizon-prepare's output directory (the one
/// holding `facts.json` and `worldgen/`); `datapacks` may be null. On failure
/// returns null and writes the error message to `err` (up to `err_len` bytes).
///
/// # Safety
/// The strings must be NUL-terminated; `err` must hold `err_len` bytes or be null.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_open(
    resources: *const c_char,
    datapacks: *const c_char,
    seed: i64,
    dimension: *const c_char,
    err: *mut u8,
    err_len: usize,
) -> *mut Handle {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<Handle, Error> {
        let resources = unsafe { path_arg(resources) }.ok_or("no resources directory")?;
        let datapacks = unsafe { path_arg(datapacks) };
        let dimension = if dimension.is_null() {
            "minecraft:overworld"
        } else {
            unsafe { CStr::from_ptr(dimension) }.to_str()?
        };
        open(resources, datapacks, seed, dimension)
    }));
    let message = match result {
        Ok(Ok(handle)) => return Box::into_raw(Box::new(handle)),
        Ok(Err(e)) => e.to_string(),
        Err(_) => "horizon panicked while loading".to_owned(),
    };
    unsafe { write_str(&message, err, err_len) };
    std::ptr::null_mut()
}

/// # Safety
/// `h` must come from `hz_open` and not be used afterwards.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_close(h: *mut Handle) {
    if !h.is_null() {
        drop(unsafe { Box::from_raw(h) });
    }
}

/// # Safety
/// `h` must be a live handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_min_y(h: *const Handle) -> i32 {
    unsafe { &*h }.min_y
}

/// # Safety
/// `h` must be a live handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_height(h: *const Handle) -> i32 {
    unsafe { &*h }.height
}

/// # Safety
/// `h` must be a live handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_state_count(h: *const Handle) -> u32 {
    unsafe { &*h }.registry.len() as u32
}

/// The block state string of a Horizon state id, e.g. `minecraft:oak_log[axis=y]`.
///
/// # Safety
/// `h` must be a live handle; `buf` must hold `len` bytes or be null.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_state_name(h: *const Handle, id: u32, buf: *mut u8, len: usize) -> usize {
    let h = unsafe { &*h };
    let s = h.states.get(id as usize).map_or("minecraft:air", String::as_str);
    unsafe { write_str(s, buf, len) }
}

/// # Safety
/// `h` must be a live handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_biome_count(h: *const Handle) -> u32 {
    unsafe { &*h }.data.biome_names().len() as u32
}

/// # Safety
/// `h` must be a live handle; `buf` must hold `len` bytes or be null.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_biome_name(h: *const Handle, id: u32, buf: *mut u8, len: usize) -> usize {
    let h = unsafe { &*h };
    let s = h.data.biome_names().get(id as usize).map_or("minecraft:plains", |n| &**n);
    unsafe { write_str(s, buf, len) }
}

/// Generates one chunk through structure starts, biomes, noise, surface and carvers
/// (vanilla's statuses up to CARVERS). Writes `16 * 16 * height` block state ids and
/// `4 * 4 * height / 4` biome ids. Returns 0, or -1 when the buffers are too small,
/// -2 on a generation error, -3 on a panic.
///
/// # Safety
/// `h` must be a live handle; the buffers must hold the lengths given.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_generate(
    h: *const Handle,
    cx: i32,
    cz: i32,
    blocks: *mut u32,
    blocks_len: usize,
    biomes: *mut u16,
    biomes_len: usize,
) -> i32 {
    let h = unsafe { &*h };
    let height = h.height as usize;
    if blocks_len < 256 * height || biomes_len < 16 * height / 4 {
        return -1;
    }
    let blocks = unsafe { std::slice::from_raw_parts_mut(blocks, 256 * height) };
    let biomes = unsafe { std::slice::from_raw_parts_mut(biomes, 16 * height / 4) };
    let result = catch_unwind(AssertUnwindSafe(|| {
        let chunk = match h.with_worker(|w| w.generate_proto(ChunkPos::new(cx, cz))) {
            Ok(chunk) => chunk,
            Err(e) => {
                eprintln!("[horizon-bridge] chunk {cx} {cz}: {e}");
                return -2;
            }
        };
        let (bx, bz) = (cx * 16, cz * 16);
        let mut i = 0;
        for y in h.min_y..h.min_y + h.height {
            for z in 0..16 {
                for x in 0..16 {
                    blocks[i] = chunk.block(bx + x, y, bz + z).0;
                    i += 1;
                }
            }
        }
        let mut i = 0;
        for qy in 0..h.height / 4 {
            for qz in 0..4 {
                for qx in 0..4 {
                    let id = chunk.biome_id_at(bx + qx * 4, h.min_y + qy * 4, bz + qz * 4);
                    biomes[i] = id.0 as u16;
                    i += 1;
                }
            }
        }
        0
    }));
    result.unwrap_or(-3)
}

/// Noise biomes at `n` quart positions (`xyz` holds `3 * n` ints, quart coordinates,
/// i.e. block >> 2). Writes biome ids, or `u16::MAX` where there is none. Returns 0,
/// or -3 on a panic.
///
/// # Safety
/// `h` must be a live handle; `xyz` must hold `3 * n` ints and `out` `n` shorts.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_noise_biomes(
    h: *const Handle,
    xyz: *const i32,
    n: usize,
    out: *mut u16,
) -> i32 {
    let h = unsafe { &*h };
    let xyz = unsafe { std::slice::from_raw_parts(xyz, 3 * n) };
    let out = unsafe { std::slice::from_raw_parts_mut(out, n) };
    let quarts: Vec<[i32; 3]> = xyz.chunks_exact(3).map(|c| [c[0], c[1], c[2]]).collect();
    let result = catch_unwind(AssertUnwindSafe(|| {
        let names = h.with_worker(|w| w.noise_biomes(&quarts));
        for (slot, name) in out.iter_mut().zip(names) {
            *slot = name
                .and_then(|n| h.data.biome_id(&n))
                .map_or(u16::MAX, |id| id.0 as u16);
        }
        0
    }));
    result.unwrap_or(-3)
}

/// Sets the server's block-state id of every Horizon state id (`n` entries, in Horizon id
/// order), once per handle. Returns 0, or -1 when `n` is not the state count or it was set.
///
/// # Safety
/// `h` must be a live handle; `ids` must hold `n` ints.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_set_server_ids(h: *const Handle, ids: *const u32, n: usize) -> i32 {
    let h = unsafe { &*h };
    if n != h.registry.len() {
        return -1;
    }
    let ids = unsafe { std::slice::from_raw_parts(ids, n) }.to_vec();
    if h.server_ids.set(ids).is_err() { -1 } else { 0 }
}

fn write_varint(out: &mut Vec<u8>, mut v: u32) {
    loop {
        if v & !0x7f == 0 {
            out.push(v as u8);
            return;
        }
        out.push((v as u8 & 0x7f) | 0x80);
        v >>= 7;
    }
}

/// One section's block states in the server's `PalettedContainer.read` format: the bits byte,
/// the palette, then the packed longs (big endian, values never straddle a long, no length
/// prefix). `None` when it needs the global palette (more than 256 distinct states).
fn encode_section(ids: &[u32], server: &[u32], out: &mut Vec<u8>) -> Option<()> {
    // Palette in first-seen order; Horizon ids are dense, so a small linear scan is fastest
    // for the handful of states a terrain section holds.
    let mut palette: Vec<u32> = Vec::with_capacity(16);
    let mut indices = [0u16; 4096];
    let mut last = (u32::MAX, 0u16);
    for (i, &id) in ids.iter().enumerate() {
        if id == last.0 {
            indices[i] = last.1;
            continue;
        }
        let at = match palette.iter().position(|&p| p == id) {
            Some(at) => at,
            None => {
                if palette.len() == 256 {
                    return None;
                }
                palette.push(id);
                palette.len() - 1
            }
        };
        last = (id, at as u16);
        indices[i] = at as u16;
    }
    let bits = match palette.len() {
        1 => 0,
        2..=16 => 4,
        n => (usize::BITS - (n - 1).leading_zeros()) as usize,
    };
    out.push(bits as u8);
    if bits == 0 {
        write_varint(out, server[palette[0] as usize]);
        return Some(());
    }
    write_varint(out, palette.len() as u32);
    for &p in &palette {
        write_varint(out, server[p as usize]);
    }
    let per_long = 64 / bits;
    for cell in indices.chunks(per_long) {
        let mut word = 0u64;
        for (j, &v) in cell.iter().enumerate() {
            word |= u64::from(v) << (j * bits);
        }
        out.extend_from_slice(&word.to_be_bytes());
    }
    Some(())
}

/// `hz_generate`, plus every section packed for the server (`hz_set_server_ids` first).
/// Sections go one after another into `packed`; `lens[i]` is section i's byte length (from
/// the bottom of the chunk), 0 when it is all `minecraft:air`, -1 when it could not be packed
/// (read it from `blocks`). Returns 0, -1 for small buffers, -2 on a generation error, -3 on a
/// panic, -4 when the server ids were never set.
///
/// # Safety
/// `h` must be a live handle; the buffers must hold the lengths given.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hz_generate_packed(
    h: *const Handle,
    cx: i32,
    cz: i32,
    blocks: *mut u32,
    blocks_len: usize,
    biomes: *mut u16,
    biomes_len: usize,
    packed: *mut u8,
    packed_cap: usize,
    lens: *mut i32,
    sections: usize,
) -> i32 {
    let handle = unsafe { &*h };
    let Some(server) = handle.server_ids.get() else {
        return -4;
    };
    let count = handle.height as usize / 16;
    if sections < count {
        return -1;
    }
    let status = unsafe { hz_generate(h, cx, cz, blocks, blocks_len, biomes, biomes_len) };
    if status != 0 {
        return status;
    }
    let blocks = unsafe { std::slice::from_raw_parts(blocks, 256 * handle.height as usize) };
    let lens = unsafe { std::slice::from_raw_parts_mut(lens, count) };
    let air = handle.air;
    let result = catch_unwind(AssertUnwindSafe(|| {
        let mut out = Vec::with_capacity(16 * 1024);
        for (i, ids) in blocks.chunks_exact(4096).enumerate() {
            if ids.iter().all(|&id| id == air) {
                lens[i] = 0;
                continue;
            }
            let start = out.len();
            lens[i] = match encode_section(ids, server, &mut out) {
                Some(()) => (out.len() - start) as i32,
                None => {
                    out.truncate(start);
                    -1
                }
            };
        }
        if out.len() > packed_cap {
            return -1;
        }
        unsafe { std::ptr::copy_nonoverlapping(out.as_ptr(), packed, out.len()) };
        0
    }));
    result.unwrap_or(-3)
}
