# Agent guide: starlight-1.12

## Overview

Port of Spottedleaf's Starlight lighting engine (as maintained inside Moonrise,
`Tuinity/Moonrise`, `ca.spottedleaf.moonrise.patches.starlight`) to Minecraft
1.12.2 on Cleanroom. Separate mod from gl46core: lighting is world simulation
(integrated and dedicated server, saves, chunk generation), not rendering.

- License: GPL-3.0 (derivative of Moonrise). Keep Spottedleaf's notices on ported files.
- Reference checkouts (not part of the build): `../_ref/Moonrise`, `../_ref/Alfheim`
  (MIT; 1.12 hook research only).
- Mutually exclusive with other lighting engines (Alfheim, Phosphor, Hesperus):
  refuse to run with a clear message rather than half-apply.

## Build

Same toolchain as gl46core (CleanroomGradle 0.17.4, userdev 0.6.10 from Maven
Local, JDK 25): `gradle-dev.bat build`, `gradle-dev.bat runClient`.
Cleanroom versioning needs at least one git commit.

## Starlight engine (Moonrise) summary

- `StarLightEngine` (~1450 lines): BFS propagation with direction bitsets over a
  5x5 chunk cache; `BlockStarLightEngine` (emission sources), `SkyStarLightEngine`
  (sky, heightmap-driven initial light, "extrude" of sky light into empty sections).
- `SWMRNibbleArray`: single-writer/multi-reader light arrays with update/visible
  buffers and states NULL / UNINIT / HIDDEN / INIT; sections -1..16 (one above and
  below the world) exist so edge propagation never needs special cases.
- `StarLightInterface`: `blockChange(pos)`, `sectionChange(pos, empty)`,
  `lightChunk(chunk, emptySections)`, `loadInChunk`, `checkChunkEdges`,
  `relightChunks`, `propagateChanges()`, readers `getSkyLightValue/getBlockLightValue`.
- Modern-only parts that drop out in 1.12: VoxelShape face occlusion
  (conditionally opaque blocks), ChunkStatus/ProtoChunk handling, threaded light
  tasks/tickets, light packets.

## 1.12.2 mapping (vanilla/Cleanroom entry point -> port)

| 1.12.2 | Port |
|---|---|
| `World.checkLightFor(type, pos)` (all block and sky updates, both sides) | queue `blockChange(pos)` once for both light types; return value kept |
| `World.checkLight(pos)` | same, single queue entry |
| `Chunk.setBlockState` sky handling (`generateSkylightMap` when a section is created, `relightBlock`, `propagateSkylightOcclusion`) | keep heightmap maintenance (rain, `canSeeSky`, precipitation use it); drop the light work: `blockChange` covers sky |
| `Chunk.generateSkylightMap` (generation) | `lightChunk` once the chunk exists; replaces vanilla `checkLight()`/`isLightPopulated` deferral |
| `Chunk.checkLight()` / `recheckGaps` / `onTick` relight checks / `enqueueRelightChecks` | no-op: Starlight keeps no deferred state |
| neighbour chunk load (`Chunk.onLoad`) | `checkChunkEdges` for the chunk and loaded neighbours |
| `Chunk.getLightFor`, `Chunk.getLightSubtracted`, `World.getLightFromNeighborsFor`, `ChunkCache.getLightForExt` | read Starlight arrays (null sections included) |
| `Chunk.setLightFor` | only used by mods/commands; write through to Starlight arrays |
| light storage: `ExtendedBlockStorage` block/sky `NibbleArray` (null for empty sections) | Starlight arrays per chunk, sections -1..16; mirror into `ExtendedBlockStorage` arrays where a section exists so rendering, packets and saves keep working |
| save/load: `AnvilChunkLoader.writeChunkToNBT` / `readChunkFromNBT` | also write/read light for all sections plus a Starlight light-version tag; chunks without it (vanilla/Alfheim saves) are relit |
| flush points: `ChunkProviderServer.saveChunks`, unload in `tick`, `SPacketChunkData(chunk, mask)` | `propagateChanges()` first (as Alfheim does) |
| server tick | `propagateChanges()` at end of world tick |
| client: `World.checkLightFor` on the client world, `Minecraft.runTick` | client queue, drained once per tick; changed sections -> `RenderGlobal.notifyLightSet` / `markBlockRangeForRenderUpdate` (Celeritas section rebuild) |
| client chunk data (`Chunk.read`): light only for non-empty sections | decide in phase 3: vanilla sky rule for null sections, or a client-side `lightChunk` |

## Block properties

- Opacity: Forge `IBlockState.getLightOpacity(IBlockAccess, BlockPos)` (0..255);
  vanilla propagation subtracts `max(1, opacity)`. No face occlusion in 1.12.
- Emission: Forge `IBlockState.getLightValue(IBlockAccess, BlockPos)` (can depend
  on position / tile entities). Cache per state only for blocks that don't
  override the position-aware methods (detect by reflection, as gl46core does for
  FontRenderer subclasses); call the position-aware methods otherwise.

## Engine port (phase 1, done)

- `com.github.starlight.light`: `SWMRNibbleArray`, `StarLightEngine`, `BlockStarLightEngine`,
  `SkyStarLightEngine` ported from Moonrise. 1.12 has no per-face occlusion, so the
  conditionally-transparent propagation branches are removed: blocks are opacity + emission.
- Platform layer: `LightWorld` (chunk lookup, sections as opaque objects, per-position
  opacity/emission, light-update callback) and `LightChunk` (position, light-ready flag,
  nibbles, emptiness maps). One implementation of each at runtime, so the calls inline.
- Light sections -1..16 (index = sectionY + 1), block sections 0..15. Positions for
  `blocksChangedInChunk` are packed int triples; relight chunk keys are `z << 32 | x`.
- Tests (`gradle-dev.bat test`): `SyntheticWorld` + `StarLightEngineTest` compare every
  position of a 5x5-chunk world with an exact light fixpoint (bucket-queue max-propagation)
  after chunk-by-chunk lighting, edit batches (incl. whole sections filled/cleared) and a
  full relight. Null sky sections are read like the game: bottom row of the first
  initialised section above, 15 above all. A deliberately broken engine fails them.

## Server integration (phase 2, done)

- `world.WorldLight`: per-WorldServer Starlight (Moonrise's StarLightInterface for 1.12):
  engines, change queue (block positions + section emptiness per chunk), readers, and the
  mirror of Starlight's visible arrays into `ExtendedBlockStorage` arrays (`onLightUpdate`,
  whole chunk after lighting/loading, new sections on creation). Chunk lookups use
  `ChunkProviderServer.loadedChunks` directly (`getLoadedChunk` cancels queued unloads).
- Mixins act on server worlds only (`world.isRemote` keeps vanilla until phase 3):
  `WorldMixin.checkLightFor` queues; `ChunkMixin` keeps heightmap work in
  `generateSkylightMap`/`relightBlock`, no-ops `propagateSkylightOcclusion`/`recheckGaps`/
  `enqueueRelightChecks`, `checkLight()` only sets the populated flags, lights chunks in
  `onLoad` (populated) and at the end of `populate(IChunkGenerator)`, queues section
  emptiness changes in `setBlockState`, reads `getLightFor`/`getLightSubtracted` from
  Starlight once lit, writes `setLightFor` through. Flush points: `WorldServer.tick` end,
  `ChunkProviderServer.saveChunks`, `AnvilChunkLoader.saveChunk`, `SPacketChunkData` ctor.
- Saved light: `world.LightSave` writes a `starlight` compound into the chunk Level tag
  (version, per-light-section states block+sky, explicit data only where vanilla arrays
  can't carry it: no ExtendedBlockStorage, sections -1/16, hidden block data). Reading
  restores it (safe on Forge's chunk IO thread); `onLoad` then registers emptiness
  (`forceHandleEmptySectionChanges`) and checks edges against loaded neighbours instead of
  relighting. Vanilla arrays stay in the save, so worlds open without Starlight; chunks
  without the tag (vanilla, Alfheim) are relit.
- `StarlightMixinPlugin` disables every mixin when Alfheim, Phosphor, Hesperus or Cubic
  Chunks is present (their mixin config / main class resource) and logs why.
- Dev checks: `-Dstarlight.verify=true` compares a random lit chunk (5x5 neighbourhood
  loaded) with exact light from the real blocks every 200 ticks; `-Dstarlight.verifyEdits=true`
  makes 150 random block edits around it first. Results so far: every verified chunk exact
  (idle, after edits, after reload from saved light). Lighting ~0.55 ms per chunk, loading
  saved light ~0.15 ms per chunk.
- Testing in a client: build, copy `build/libs/starlight-*-dev.jar` into gl46core's
  `run/cleanroom-client/mods`, use its `Bench-Session` (auto-join), remove the jar after.
  For save/load tests use `-world <save>` (not restored between launches).
- Editing this file: it uses LF line endings; anchor-based scripted edits must match LF.
## Client integration (phase 3, done)

- The client world has its own `WorldLight` (`WorldClientMixin`); the server-side
  `ChunkMixin` replacements apply to it too (they key on "world has Starlight"), except
  chunk lighting on load/population, which is server-only.
- `ChunkClientMixin`: `Chunk.read` (whole chunk or sections from the server) queues the chunk
  for relighting and marks it not ready, so reads fall back to the packet's light until then.
- `EntityRendererMixin`: once per frame before rendering, queued block changes are applied
  and queued chunks are lit within a 1 ms budget (`-Dstarlight.clientBudgetMicros`).
- Singleplayer: a queued client chunk whose integrated-server copy is lit takes the server's
  visible light (`WorldLight.importLight`, exact copies of visible data + states, read across
  threads) instead of recomputing it; multiplayer computes. Measured first 8 s after a
  32-chunk join: 2.1-2.5 ms average frame when computing, 0.65-0.69 ms importing, vanilla
  0.55-0.65 ms; client-vs-server check identical.
- Server-thread cost (JFR, same join): vanilla's lighting ~0.5% (it only fills sky columns at
  generation and defers the rest to relight checks), Starlight ~19% (complete light for ~4000
  chunks at ~0.5 ms each; mostly sky propagation through air and BlockStateContainer.get).
- Mirroring compares first (`copyVisibleIntoIfChanged`); on the client a section is re-rendered
  (`markBlockRangeForRenderUpdate`, exact 16^3 box) only when its mirrored light changed, so
  a singleplayer join (server light == client light) doesn't re-mesh everything.
- Client-only classes stay out of common code (`ClientChunks`, `ClientLightVerifier`), so a
  dedicated server never resolves them.
- Dev check: `-Dstarlight.verifyClient=true` (singleplayer) compares a random client chunk
  (3x3 lit on both sides) with the integrated server's: Starlight light read across threads
  and the client's vanilla arrays. First run: 9/9 identical, 9/9 server chunks exact with edits.
## Verification (phase 4)

- Your world (Prism "New World", saved with Alfheim; tested on a copy, `slworld` in
  gl46core's run saves): launch 1 relit ~4000 chunks (~0.6 ms each), launch 2 loaded ~2000
  with saved light (~0.2 ms) and relit only chunks launch 1 never saved; server verifier 16/16
  exact (with edits), client-vs-server 16/16 identical; frame renders normally.
- Dedicated server: `gradle-dev.bat runServer` (dir `run/cleanroom-server`, dev jar in
  `mods/`, class dirs filtered off the classpath like runClient, else the manifest's mixin
  config is never registered and no hook applies). The task asks for offline mode and the
  EULA on stdin but reads only one piped answer per launch: `eula.txt` / `server.properties`
  (online-mode=false) in the run dir answer both (EULA accepted by the user for dev).
  Verifier flags via `JAVA_TOOL_OPTIONS`. Result: no mixin/class-loading errors, 6/6 exact
  with edits. `IntegratedServer$1/$2` "invalid side" errors at start are Forge's own probe
  (`ForgeModContainer.modConstruction`), not Starlight.
## Memory and robustness (post phase 4)

- Visible light of a section with an ExtendedBlockStorage lives in the vanilla NibbleArray's
  byte[] (`SWMRNibbleArray.bindVisibleStorage`, called by `WorldLight.mirrorSection`): no
  duplicate arrays and no mirror copies; writes still go to a working array and
  `updateVisible` publishes into the bound array (zeros when the nibble has no data). Re-bind
  is automatic if a mod replaces the vanilla arrays (identity check on every mirror).
- Unbound all-15 visible data (sky light of air sections above terrain) shares one constant
  array (`FULL`), copied before any write (`updateVisible` never writes into it;
  `SWMRNibbleArray.of` shares it for imported/loaded data). Work-array pool capped at 256.
- Measured (server, 4225 chunks): private light arrays 37 MB -> 0.2-0.5 MB; ~13.7k bound,
  ~6.1k shared all-15. `-Dstarlight.memStats=true` logs this every 400 ticks. Heap totals are
  too noisy between runs (byte[] 327-462 MB with the same build) to measure this; count arrays.
- Section emptiness: each chunk keeps the emptiness Starlight registered (`setKnownEmptiness`
  when lit/loaded/imported) and `setBlockState` RETURN queues changes against it. The earlier
  HEAD/RETURN instance fields lost changes when setBlockState re-entered on the same chunk
  (breakBlock/onBlockAdded callbacks); don't reintroduce per-call state in instance fields.
- Unlit server chunks (not populated yet) read like vanilla's no-data rule (sky 15 where the
  column sees the sky, block 0): their vanilla arrays are zeros since the vanilla sky fill is
  skipped. JFR attributing server samples to `postLightUpdate`'s client-only loop is a
  line-attribution artifact of inlining (engines are created with the right side).
## Generation and join speed

- Source scan: `BlockStarLightEngine.getSources` read all 4096 blocks of every non-empty
  section (22% of chunk lighting). `WorldLight.mayHaveEmission` -> `Emitters` now checks the
  section palette first (accessor mixins on BlockStateContainer / BlockStatePaletteLinear /
  BlockStatePaletteHashMap): a state may emit if `getLightValue() > 0` or its block overrides
  Forge's position-aware `getLightValue`; the registry palette (>256 states) is scanned.
- Lit as new: `StarLightEngine.light(chunk, empty, fresh)`. A just-generated chunk, or a chunk
  saved without Starlight light with no loaded neighbour holding light from its save
  (`WorldLight.canLightAsNew`, `StarlightChunkState.isLightFromSave`), pulls neighbour edge
  light instead of the full edge check (which re-evaluates every edge cell both sides).
  Neighbours lit in this session can't hold stale light from it: a chunk can't change while
  unloaded, and a lit chunk is saved with its light. `relitChunkCorrectsStaleNeighbourLight`
  shows why the full check stays for the other cases (fails with the fast path).
- Saved-light loads no longer mark chunks modified (`mirrorChunk(chunk, false)`): every chunk
  loaded with saved light was re-saved at the next autosave/quit. Updates that change light
  still mark it (onLightUpdate).
- `LightStats` logs chunks lit as new / with edge checks / loaded, every 500. Dev run, vanilla-
  saved world: 4500 lit as new at ~0.34 ms (was ~0.5-0.6 ms with edge checks); tick totals on
  this machine vary too much (idle tick 4.5-8.6 ms between runs) to show per-change gains -
  compare LightStats averages.
## Compatibility to handle

Fluidlogged API (Alfheim has a hook), dynamic-lights mods (client light value),
Cubic Chunks (incompatible: detect and refuse), other lighting engines (refuse).

## Phases

0. Skeleton: mod + mixin config load, `World.checkLightFor` probe applies (dev and production refmap).
1. Engine port: Starlight core against 1.12 types (chunks, `ExtendedBlockStorage`,
   `IBlockState`), no VoxelShapes; unit tests on synthetic chunks.
2. Server integration: entry points above, storage mirroring, save/load, flush points.
3. Client integration: client queue, render updates, chunk packets.
4. Verification: compare light values against vanilla and Alfheim in test worlds;
   measure generation/relight time and server tick share.
