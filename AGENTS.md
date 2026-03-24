# AGENTS.md

## Project snapshot
- Fabric mod for Minecraft `1.21.4` using Loom (`build.gradle`, `gradle.properties`), Java 21 source/target.
- Core feature: selected placed blocks convert into a custom falling entity with custom physics + mining behavior.
- Entrypoints are declared in `src/main/resources/fabric.mod.json` (`main`, `client`, `fabric-datagen`).

## Architecture and data flow
- Server pipeline:
  1. `BlockMixin` (`Block.onPlaced`) and `WorldMixin` (`World.setBlockState`) enqueue positions into `AetherialSkies.blocksToCheck`.
  2. `AetherialSkies.onInitialize` processes that set each server tick (`ServerTickEvents.END_SERVER_TICK`).
  3. If below is air and block is gravity-enabled, world block is removed and `GravityBlockEntity` is spawned.
- Client pipeline:
  - `AetherialSkiesClient` registers `GravityBlockEntityRenderer` for `GRAVITY_BLOCK_ENTITY`.
  - Renderer reads tracked state (`BLOCK_ID`, rotation, roll, mining progress) and renders a block model via `BlockRenderManager`.
  - Renderer state is staged through `GravityBlockRenderState` (`updateRenderState(...)` -> `render(...)`); keep new client-visible fields flowing through that state object.
- Mixins are enabled by `src/main/resources/aetherial-skies.mixins.json` (`BlockMixin`, `WorldMixin`).

## Critical code locations
- Registration + tick loop: `src/main/java/net/shasankp000/AetherialSkies.java`
- Physics, collision, mining, drops, NBT: `src/main/java/net/shasankp000/Entity/GravityBlockEntity.java`
- Gravity/weight rules in code: `src/main/java/net/shasankp000/Gravity/GravityData.java`
- Block-name fallback mapping used by entity/renderer: `src/main/java/net/shasankp000/Util/BlockStateRegistry.java`
- Mining gating/tuning helpers used by `GravityBlockEntity.damage(...)`: `src/main/java/net/shasankp000/Util/HandMineableBlocks.java`, `src/main/java/net/shasankp000/Util/ToolStrength.java`
- Client render-state carrier for entity -> renderer sync: `src/main/java/net/shasankp000/Client/Renderer/GravityBlockRenderState.java`
- Resource data currently present but not consumed at runtime: `src/main/resources/data/aetherial_skies/**`

## Project-specific conventions and gotchas
- Runtime gravity source of truth is **Java static data** (`GravityData`), not JSON tags/weights.
- `BlockStateRegistry` and hand-mine checks are string-based; they depend on exact names/IDs and are easy to desync.
- `GravityBlockEntity.damage(...)` mining speed logic also uses tool display-name string matching; changes to `ToolStrength` / `HandMineableBlocks` should be validated in-game.
- `GravityBlockEntity` uses `DataTracker` for client-visible fields; add new render-visible state there (not plain fields only).
- `AetherialSkies.blocksToCheck` is a static shared set; preserve add/remove behavior to avoid duplicate or stale checks.
- `RotatingFallingBlockEntity` exists as older prototype code but is not registered in `AetherialSkies`; runtime behavior comes from `GravityBlockEntity`.
- Logging is mixed (`LOGGER`, `System.out.println`); keep debug prints minimal in committed changes.

## Build and run workflows
- Standard commands (when local JDK/Gradle are compatible):
  - `./gradlew build`
  - `./gradlew runClient`
  - `./gradlew runServer`
- Gradle daemon JVM is pinned to Java 21 via `gradle/gradle-daemon-jvm.properties` (`toolchainVersion=21`).
- Datagen entrypoint exists (`AetherialSkiesDataGenerator`) but is currently empty.
- In this workspace, `./gradlew tasks --all` failed with `Unsupported class file major version 69`; verify Java/Gradle compatibility before assuming tasks run.

## Change playbooks
- Add a new gravity-enabled block:
  - Update `GravityData` (enabled set + weight).
  - Update `BlockStateRegistry` fallback mapping.
  - Keep resource JSON (`gravity_enabled.json`, `gravity_weights.json`) aligned if you want packs/docs to reflect runtime behavior.
- Change falling/mining behavior:
  - Primary edits belong in `GravityBlockEntity.tick()` and `GravityBlockEntity.damage(...)`.
  - Validate server/client sync for any new render-affecting value via `DataTracker` + renderer state.

## Existing AI guidance scan
- One glob scan for `**/{.github/copilot-instructions.md,AGENT.md,AGENTS.md,CLAUDE.md,.cursorrules,.windsurfrules,.clinerules,.cursor/rules/**,.windsurf/rules/**,.clinerules/**,README.md}` found: `AGENTS.md`, `README.md`.
