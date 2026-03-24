# Aetherial-Skies

Fabric mod for Minecraft `1.21.4` (Java 21).

## What this project is
Aetherial-Skies aims to be a lightweight, modular, physics engine that adds gravity blocks and gravity based entity supports, aiming to be an alternative of the famous but yet buggy mod, ValkyrienSkies.
(This is not a fork of ValkyrienSkies, but a new mod built from scratch with the same goals in mind)

- Mod id: `aetherial-skies`
- Main entrypoint: `net.shasankp000.AetherialSkies`
- Client entrypoint: `net.shasankp000.AetherialSkiesClient`

## Feature status

### Implemented (working in-game)
- Gravity-enabled placed blocks convert into a custom falling entity (`GravityBlockEntity`).
- Single-block rigid-body-like fall behavior with rotation, impact damping, and settling.
- Survival mining on gravity blocks with hold-left-click support, crack progression, and block-mining sound routing.
- Tool-relative mining progression (wood/stone/iron/diamond/netherite) and valid free-hand mining path.
- Correct crack overlay rendering (destroy-stage textures) tied to mining progress with regression on stop.
- Registry-based block identity + DataTracker/NBT sync for stable server/client rendering and relog persistence.
- Single-block fluid interaction: relative-density buoyancy, sinking/floating behavior, and wave-driven bobbing/drift.

### Planned roadmap
- Multi-block structures/ships/rafts using aggregated hydrodynamics (mass, displacement, effective density).
- Hull-aware floating behavior so vessel shape/composition controls buoyancy realistically.
- Biome/worldgen-driven wave metrics (stronger oceans/deep oceans, calmer inland waters) exposed as tunable data.
- Structure systems will not be limited to water: land-based multi-block structure support is planned as well.
- Further standability/collision tuning for players standing on floating entities (top-face push exemptions).
- Ongoing in-game balancing pass for mining cadence and fluid response constants.

### Current WIP / known limitations
- Standing on floating single-block entities can still feel unstable in some cases because entity pushback/collision handling is still being tuned.
- Fluid-wave behavior is currently profile-driven in code; worldgen/data-driven wave metrics are planned but not finalized.
- Multi-block structures (water and land) are planned but not implemented yet.
- Mining and tool-speed balance is playable, but values are still under active tuning for closer vanilla feel.

# For mod development and contribution

For latest implementation checkpoint notes and session continuity, see `changelogs/README.md`.

---
# Initial Mod demo (work in progress)

https://streamable.com/fah157

---

## Prerequisites
- OS: Linux/macOS/Windows
- Git
- A Java 21 JDK installed (recommended)
  - The repo is configured to compile with Java 21 (`build.gradle` toolchain + release 21).
  - Gradle daemon JVM is pinned to Java 21 (`gradle/gradle-daemon-jvm.properties`).
- Internet access on first run (Gradle wrapper + dependencies)

## Quick setup
Clone and build with the Gradle wrapper.

### Linux/macOS
```bash
git clone https://github.com/shasankp000/Aetherial-Skies.git
cd Aetherial-Skies
chmod +x gradlew
./gradlew --version
./gradlew clean build
```

### Windows (PowerShell)
```powershell
git clone https://github.com/shasankp000/Aetherial-Skies.git
cd Aetherial-Skies
.\gradlew.bat --version
.\gradlew.bat clean build
```

## Run targets
Use these from project root.

### Linux/macOS
```bash
./gradlew runClient
./gradlew runServer
./gradlew build
```

### Windows (PowerShell)
```powershell
.\gradlew.bat runClient
.\gradlew.bat runServer
.\gradlew.bat build
```

## Data generation
A datagen entrypoint exists (`net.shasankp000.AetherialSkiesDataGenerator`) but is currently empty.

## Troubleshooting
### `Unsupported class file major version 69`
This usually means Gradle is running on an incompatible JDK.

1. Ensure Java 21 is installed.
2. Check what Gradle is using:

```bash
./gradlew --version
```

Look for a Java 21-compatible daemon JVM.

3. If needed, set `JAVA_HOME` to your Java 21 installation before running Gradle:

```bash
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew clean build
```

### Dependency/toolchain cache issues after interrupted builds
Try:

```bash
./gradlew --stop
./gradlew clean build --refresh-dependencies
```

## Notes for contributors
- Use the Gradle wrapper (`./gradlew` / `gradlew.bat`) instead of a system Gradle install.
- Keep gameplay gravity source updates in sync with Java runtime data in `src/main/java/net/shasankp000/Gravity/GravityData.java`.

