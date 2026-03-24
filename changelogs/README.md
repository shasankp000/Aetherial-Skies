# Changelogs

## Session continuity (2026-03-24)

This file summarizes the current implementation state so future sessions can resume without re-discovery.

## Completed work

### Physics and entity behavior
- `GravityBlockEntity.tick()` was refactored into staged simulation with profile-driven gravity/drag/collision response.
- Settling/sleep behavior was added (`settleTicks`, thresholds) to reduce post-impact jitter.
- Spawn now preserves placed block state directly in `AetherialSkies` (`setBlockState(state)`), reducing state desync.

### Data sync and persistence
- Render-visible telemetry is tracked through `DataTracker` (`VERTICAL_SPEED`, `HORIZONTAL_SPEED`, `IMPACT_AMPLITUDE`, `SETTLE_PROGRESS`).
- NBT persistence/restore was expanded for rotation, timers, mining progress, weights, and telemetry.
- Canonical block identity is registry-based (`minecraft:id`) and used through state restore fallbacks.

### Mining and interaction flow
- Mining progression is normalized to a `[0..1]` threshold (`MINING_THRESHOLD = 1.0f`) with decay when not mined.
- Hold-left-click mining for `GravityBlockEntity` was implemented via client mixins.
- Player attack sounds were redirected away from vanilla entity-combat flow for gravity blocks.
- Empty hand is now a valid mining path with slower progression than proper tools.
- Tool material differences are applied through `ToolStrength` using registry item IDs.

### Rendering
- Cracks now render correctly (no tint/hue-only artifacts) using destruction textures and overlay composition.
- Damage/crack stage is tied to mining progress and regresses as progress decays.

## Key files touched
- `src/main/java/net/shasankp000/Entity/GravityBlockEntity.java`
- `src/main/java/net/shasankp000/Client/Renderer/GravityBlockEntityRenderer.java`
- `src/main/java/net/shasankp000/Client/Renderer/GravityBlockRenderState.java`
- `src/main/java/net/shasankp000/Gravity/GravityData.java`
- `src/main/java/net/shasankp000/AetherialSkies.java`
- `src/main/java/net/shasankp000/Util/BlockStateRegistry.java`
- `src/main/java/net/shasankp000/Util/HandMineableBlocks.java`
- `src/main/java/net/shasankp000/Util/ToolStrength.java`
- `src/main/java/net/shasankp000/mixin/PlayerEntityMixin.java`
- `src/main/java/net/shasankp000/mixin/ClientPlayerInteractionManagerMixin.java`
- `src/main/java/net/shasankp000/mixin/MinecraftClientMixin.java`
- `src/main/resources/aetherial-skies.mixins.json`

## Current status
- Crack visuals: working.
- Mining sound routing: working (block-mining sound path).
- Hold-to-mine: implemented and working.
- Tool-relative mining speed: implemented and currently acceptable for this checkpoint.

## Suggested next session start points
1. Fine-tune per-tool mining cadence against vanilla feel (wood/stone/iron/diamond/netherite).
2. Add focused in-game validation matrix (free hand vs tools, hand-mineable vs non-hand-mineable blocks).
3. If needed, expose mining multipliers via configurable constants for faster balancing.

## Investigation notes
- Entity pushback/slip investigation: `changelogs/ENTITY_COLLISION_INVESTIGATION_README.md`

## Feature roadmap mirror

### Planned roadmap
- Multi-block structures/ships/rafts using aggregated hydrodynamics (mass, displacement, effective density).
- Hull-aware floating behavior so vessel shape/composition controls buoyancy realistically.
- Biome/worldgen-driven wave metrics (stronger oceans/deep oceans, calmer inland waters) exposed as tunable data.
- Structure systems are planned for both water and land (not water-only).
- Further standability/collision tuning for players standing on floating entities (top-face push exemptions).
- Ongoing in-game balancing pass for mining cadence and fluid response constants.

### Current WIP / known limitations
- Standing on floating single-block entities can still feel unstable in some cases because entity pushback/collision handling is still being tuned.
- Fluid-wave behavior is currently profile-driven in code; worldgen/data-driven wave metrics are planned but not finalized.
- Multi-block structures (water and land) are planned but not implemented yet.
- Mining and tool-speed balance is playable, but values are still under active tuning for closer vanilla feel.

