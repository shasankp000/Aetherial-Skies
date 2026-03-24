# Changelogs

## Session continuity (2026-03-25)

This section records the latest standing/physics/rendering work and current behavior after in-game validation.

## Completed work (2026-03-25)

### Standing + collision investigation
- Verified that stable standing on land but unstable standing on floating blocks is primarily a footing/drift issue, not a pure vanilla cramming issue.
- Reverted broad vanilla cramming interception approach (no global `LivingEntity` push suppression kept).
- Kept collision handling localized to `GravityBlockEntity` top-contact guards.

### Rendering alignment + artifact fix
- Fixed black-face shading artifact for rotated block rendering by switching the base render path to `BlockRenderManager.renderBlockAsEntity(...)` in `GravityBlockEntityRenderer`.
- Retained client-side visual rotation realism (did not keep settle-to-upright visual override).

### Physics improvements from `changelogs/physics_improvements.md`
- Added freefall tumble injection in `GravityBlockEntity.tick()` based on horizontal speed and mass.
- Added spawn-time horizontal velocity nudge in `AetherialSkies` so tumble starts naturally from spawn.
- Updated `applyGroundFriction(...)` retention formula to create clearer material-dependent sliding differences.

### Runtime performance patch
- Added sampled/cached player-load evaluation (`computePlayerLoadTarget`) instead of per-tick full player queries.
- Staggered load sampling per entity to reduce synchronized spikes.
- Throttled render telemetry `DataTracker` updates to reduce per-tick churn.

### Stacked floating-block behavior
- Added stacked-block load contribution (gravity blocks above now affect buoyancy/load of lower floating block).
- Added stacked fluid interactions (downforce + shear) so heavy-on-light stacks no longer remain fully static.
- Improved head-on stack contact detection (touching/overlap tolerant, not strict AABB intersection only).
- Reduced “rattle” with shear impulse rate limiting and vertical relative-velocity damping while preserving slide/sink outcomes.
- Current observed behavior:
- Heavy on light now often sinks pair or slides off in water as expected.
- Some jitter can still appear during descent for centered heavy-on-light stacks.
- On solid bottom support, stack locking is currently considered acceptable.

### Ship-roadmap planning checkpoint
- Agreed direction: keep single-block realism for now, revisit polish later.
- High-level staged ship plan drafted (foundation -> rigid-body -> custom collision layer -> player/platform support transfer).

## Key files touched (2026-03-25)
- `src/main/java/net/shasankp000/Entity/GravityBlockEntity.java`
- `src/main/java/net/shasankp000/Client/Renderer/GravityBlockEntityRenderer.java`
- `src/main/java/net/shasankp000/AetherialSkies.java`
- `src/main/java/net/shasankp000/mixin/PlayerEntityMixin.java`
- `src/main/resources/aetherial-skies.mixins.json`
- `changelogs/physics_improvements.md`

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
