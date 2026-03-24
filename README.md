# Aetherial Skies

Fabric mod for Minecraft `1.21.4` (Java 21).

## What this project is
Aetherial Skies turns selected placed blocks into a custom falling entity with custom physics and mining behavior.

- Mod id: `aetherial-skies`
- Main entrypoint: `net.shasankp000.AetherialSkies`
- Client entrypoint: `net.shasankp000.AetherialSkiesClient`

For codebase architecture and agent-focused guidance, see `AGENTS.md`.

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
git clone <your-fork-or-repo-url>
cd Aetherial-Skies
chmod +x gradlew
./gradlew --version
./gradlew clean build
```

### Windows (PowerShell)
```powershell
git clone <your-fork-or-repo-url>
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

