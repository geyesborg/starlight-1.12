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
