# papermc-rust: Horizon's Rust worldgen in Paper

A prototype Paper 26.3 plugin that generates terrain with [Horizon](https://github.com/horizon-rust/horizon)'s
Rust port of vanilla worldgen, called through Java's Foreign Function & Memory API (Panama).

Rust generates structure starts, biomes, noise, surface and carvers. Paper's own vanilla code then adds
structure pieces, features and mobs on top, so plugins that hook those stages keep working.

## Layout

| Path | What |
| --- | --- |
| `horizon-bridge/` | Rust `cdylib`: a C ABI over `horizon-world`'s generator (`hz_open`, `hz_generate`, `hz_noise_biomes`, name lookups). Thread safe, with a pool of workers. |
| `paper-plugin/` | The `HorizonGen` Paper plugin: FFM bindings, the `ChunkGenerator`, and a self-test against vanilla. Bundles the DLL. |
| `third_party/horizon/` | Horizon as a git submodule (pinned commit), used as a path dependency. |
| `test-server/` | Paper 26.3 build 246 with the plugin installed and the world set to `generator: HorizonGen`. |

## Build

```sh
git submodule update --init --depth 1 third_party/horizon
cd horizon-bridge && cargo build --release
cd paper-plugin && ./gradlew build   # bundles ../horizon-bridge/target/release/horizon_bridge.dll
```

Horizon's worldgen data comes from your own copy of 26.3 (client and server jar, checked against Horizon's pins):

```sh
cd third_party/horizon
cargo run --release -p horizon-prepare -- --prepare --server-jar ../../test-server/cache/mojang_26.3.jar
```

The test server's `plugins/HorizonGen/config.yml` already points `resources` at that output folder,
`../third_party/horizon/.local/horizon-client/26.3`, relative to `test-server/`.

The test server's Paper jar, libraries and EULA acceptance are not in git. Download
`paper-26.3-246.jar` into `test-server/`, run it once with `-Dpaperclip.patchonly=true` (this also fetches
`cache/mojang_26.3.jar`), and accept the EULA in `eula.txt`. Copy `server.properties.example` to `server.properties` for the test
settings (seed 12345, port 25599). The plugin build needs that first run too,
for `versions/26.3/paper-26.3.jar`.

## Run

```sh
cd test-server
java --enable-native-access=ALL-UNNAMED -jar paper-26.3-246.jar --nogui
```

## Results (seed 12345, 2026-11-09)

### Correctness

| Test (`compare-on-start.mode`) | Area | Blocks differing | Biomes differing |
| --- | --- | --- | --- |
| `terrain`: Rust terrain vs Paper terrain, no decoration | 17x17 chunks, 3 areas | 60 / 95 / 302 of 28.4M (0.0002–0.0011%) | 0 |
| `terrain-control`: Paper vs Paper terrain | 17x17 chunks | 0 | 0 |
| `full`: Horizon world vs Paper, decorated | 9x9 chunks | 4,466 (0.056%) | 0 |
| `control`: Paper vs Paper, decorated | 9x9 chunks | 3,459 (0.043%) | 0 |

- **Terrain-only differences are Paper's own, not Horizon's.** Horizon's reference harness
  (`tools/test_chunk_generation_parity.py --phase terrain --rect ...`) runs unmodified Mojang vanilla
  26.3. On two of the areas, the 60-block and 302-block ones, it found 0 differences between Horizon
  and Mojang. All 362 blocks that differ from Paper match Mojang's value. A plain Paper world, made
  with no plugin generator, also has Paper's value there. So Paper 26.3 generates very slightly different
  terrain from Mojang's vanilla: single blocks where solid meets air or water, and surface layers.
  Horizon matches Mojang.
- **Decorated differences** are all decoration (ores, dripstone, stone patches) and come from the order
  chunks get decorated in. Paper differs from itself by about as much.

### Speed

`compare-on-start.mode: bench`: a fresh 41x41-chunk area per world through Paper's async chunk system,
24 threads (`HorizonBench`).

| World | Chunks/s | vs Paper |
| --- | --- | --- |
| Paper, terrain only | 369–381 | |
| Horizon, terrain only, packed sections (`fast-write: true`) | 576–606 | **1.56–1.59x** |
| Horizon, terrain only, plugin API copy (`fast-write: false`) | 502 | 1.33x |
| Paper, full generation | 268–278 | |
| Horizon, full generation, packed sections | 430–432 | **1.55–1.61x** |
| Horizon, full generation, plugin API copy | 370 | 1.36x |

- **Fast path:** Rust packs each 16x16x16 section in the server's `PalettedContainer` format (palette
  plus packed longs, with the server's block-state IDs). `FastChunkWriter` loads each one with a single
  `PalettedContainer.read`, then primes the two worldgen heightmaps, as vanilla's noise stage does.
  That took the copy from 1.9 ms to 0.15 ms per chunk.
- **Native time:** Horizon's own generation is about 5.8 ms per chunk, summed over threads, and is now
  nearly all of its cost. Further gains have to come from the Rust side, or from moving more stages
  (features, structures) to Rust.
- **Fallback:** sections with more than 256 distinct states, which terrain never produced here, and
  Paper builds whose internals differ fall back to block-by-block writes.

## Not done yet

- Scheduled fluid ticks from aquifers and Horizon's post-processing marks aren't passed to Paper.
- Only unzipped datapack folders are read. A datapack that adds new biomes isn't handled (Horizon leaves them out).
- Windows only so far (`.dll`); other platforms need the library built for them.
- The plugin compiles against `test-server/versions/26.3/paper-26.3.jar`, which exists only after the server has run once.
- `FastChunkWriter` depends on Paper 26.3's internals (Mojang names, `paperweight-mappings-namespace: mojang`),
  so it needs checking on each Paper update. A fork would make this a patch instead.
