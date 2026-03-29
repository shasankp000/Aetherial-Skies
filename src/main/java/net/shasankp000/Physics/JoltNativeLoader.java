package net.shasankp000.Physics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Extracts and loads the correct Jolt-JNI native library for the current
 * platform at runtime.
 *
 * <p>jolt-jni platform JARs package the native library in one of these paths
 * inside the JAR (varies by version and build type):
 * <ul>
 *   <li>{@code <filename>}  (jar root — used when bundled into mod jar)</li>
 *   <li>{@code com/github/stephengold/joltjni/native/<platform>/release/<filename>}</li>
 *   <li>{@code com/github/stephengold/joltjni/native/<platform>/debug/<filename>}</li>
 * </ul>
 *
 * <p>Call {@link #load()} exactly once during mod initialisation. It is
 * idempotent.
 */
public final class JoltNativeLoader {

    private static final Logger LOGGER =
            LoggerFactory.getLogger("AetherialSkies/JoltNativeLoader");

    private static volatile boolean loaded = false;

    private JoltNativeLoader() {}

    public static synchronized void load() {
        if (loaded) return;

        String nativeLib  = resolveNativeLibName();
        String platformId = resolvePlatformId();
        LOGGER.info("[JoltNativeLoader] Loading native library: {}", nativeLib);

        // ----------------------------------------------------------------
        // 1. Try System.loadLibrary — succeeds when -Djava.library.path is
        //    set (e.g. via the extractJoltNatives Gradle task in dev).
        // ----------------------------------------------------------------
        try {
            System.loadLibrary(stripExtension(nativeLib));
            LOGGER.info("[JoltNativeLoader] Loaded via System.loadLibrary.");
            loaded = true;
            return;
        } catch (UnsatisfiedLinkError ignored) {
            // fall through to classpath extraction
        }

        // ----------------------------------------------------------------
        // 2. Probe classpath resource paths in priority order.
        //    Order matters: production mod jar bundles at root; raw platform
        //    jars (dev classpath) use the subdirectory paths.
        // ----------------------------------------------------------------
        String[] candidatePaths = {
            "/" + nativeLib,
            "/com/github/stephengold/joltjni/native/" + platformId + "/release/" + nativeLib,
            "/com/github/stephengold/joltjni/native/" + platformId + "/debug/"   + nativeLib,
        };

        for (String resourcePath : candidatePaths) {
            InputStream in = JoltNativeLoader.class.getResourceAsStream(resourcePath);
            if (in == null) continue;

            try {
                Path tempDir = Files.createTempDirectory("jolt-jni-");
                tempDir.toFile().deleteOnExit();
                Path tempLib = tempDir.resolve(nativeLib);
                Files.copy(in, tempLib, StandardCopyOption.REPLACE_EXISTING);
                in.close();
                tempLib.toFile().deleteOnExit();
                System.load(tempLib.toAbsolutePath().toString());
                LOGGER.info("[JoltNativeLoader] Extracted '{}' and loaded from: {}",
                        resourcePath, tempLib);
                loaded = true;
                return;
            } catch (IOException e) {
                throw new RuntimeException(
                        "[JoltNativeLoader] Failed to extract native library from: "
                        + resourcePath, e);
            }
        }

        // ----------------------------------------------------------------
        // 3. Nothing worked — give a clear error.
        // ----------------------------------------------------------------
        throw new IllegalStateException(
            "[JoltNativeLoader] Native library '" + nativeLib + "' not found "
            + "on classpath (tried root and jolt-jni platform subdirectories for "
            + "platform '" + platformId + "'). "
            + "Ensure the correct jolt-jni-<Platform>-3.9.0.jar is in libs/ and "
            + "declared as a joltLibs dependency in build.gradle."
        );
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the filename of the native library for the current OS + arch.
     *   Windows x64  -> joltjni.dll
     *   Linux  x64   -> libjoltjni.so
     *   macOS  any   -> libjoltjni.dylib
     */
    private static String resolveNativeLibName() {
        String os   = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        if (os.contains("win")) {
            if (arch.contains("64") || arch.contains("amd64")) return "joltjni.dll";
            throw unsupported(os, arch);
        }
        if (os.contains("linux")) {
            if (arch.contains("64") || arch.contains("amd64")) return "libjoltjni.so";
            throw unsupported(os, arch);
        }
        if (os.contains("mac")) {
            return "libjoltjni.dylib";
        }
        throw unsupported(os, arch);
    }

    /**
     * Returns the platform identifier used inside jolt-jni platform jars for
     * the native subdirectory, e.g. "windows64", "linux64", "macOSX64",
     * "macOSX_ARM64".
     */
    private static String resolvePlatformId() {
        String os   = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        if (os.contains("win"))   return "windows64";
        if (os.contains("linux")) return "linux64";
        if (os.contains("mac")) {
            boolean arm = arch.contains("aarch64") || arch.contains("arm");
            return arm ? "macOSX_ARM64" : "macOSX64";
        }
        return "unknown";
    }

    private static String stripExtension(String filename) {
        String s = filename;
        if (s.startsWith("lib")) s = s.substring(3);
        int dot = s.lastIndexOf('.');
        if (dot >= 0) s = s.substring(0, dot);
        return s;
    }

    private static UnsupportedOperationException unsupported(String os, String arch) {
        return new UnsupportedOperationException(
            "[JoltNativeLoader] Unsupported platform: os=" + os + " arch=" + arch
            + ". Supported: Windows x64, Linux x64, macOS x64/arm64."
        );
    }
}
