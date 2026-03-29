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
 * Jolt-JNI ships one native artifact per platform (Windows64, Linux64,
 * MacOSX64, MacOSX_ARM64). Each artifact contains the compiled .dll/.so/.dylib
 * on its classpath root. We locate the file by name, copy it to a temp
 * directory, and call System.load() on it.
 *
 * Call JoltNativeLoader.load() exactly once during mod initialisation
 * (AetherialSkiesServer.onInitialize). It is idempotent.
 */
public final class JoltNativeLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("AetherialSkies/JoltNativeLoader");
    private static volatile boolean loaded = false;

    private JoltNativeLoader() {}

    public static synchronized void load() {
        if (loaded) return;

        String nativeLib = resolveNativeLibName();
        LOGGER.info("[JoltNativeLoader] Loading native library: {}", nativeLib);

        try {
            // Try System.loadLibrary first (works if the lib is on java.library.path)
            // This succeeds in dev environments where Gradle puts natives on the path.
            System.loadLibrary(stripExtension(nativeLib));
            LOGGER.info("[JoltNativeLoader] Loaded via System.loadLibrary.");
            loaded = true;
            return;
        } catch (UnsatisfiedLinkError ignored) {
            // Not on library path — extract from classpath jar instead.
        }

        // Extract from classpath (production: native jar is on the classpath)
        try (InputStream in = JoltNativeLoader.class.getResourceAsStream("/" + nativeLib)) {
            if (in == null) {
                throw new IllegalStateException(
                    "[JoltNativeLoader] Native library not found on classpath: " + nativeLib
                    + ". Ensure the correct jolt-jni-<Platform> artifact is a runtime dependency."
                );
            }
            Path tempDir = Files.createTempDirectory("jolt-jni-");
            tempDir.toFile().deleteOnExit();
            Path tempLib = tempDir.resolve(nativeLib);
            Files.copy(in, tempLib, StandardCopyOption.REPLACE_EXISTING);
            tempLib.toFile().deleteOnExit();
            System.load(tempLib.toAbsolutePath().toString());
            LOGGER.info("[JoltNativeLoader] Extracted and loaded from: {}", tempLib);
            loaded = true;
        } catch (IOException e) {
            throw new RuntimeException("[JoltNativeLoader] Failed to extract native library: " + nativeLib, e);
        }
    }

    /**
     * Returns the filename of the native library for the current OS + arch.
     * Jolt-JNI uses the naming convention:
     *   Windows x64  -> joltjni.dll
     *   Linux x64    -> libjoltjni.so
     *   macOS x64    -> libjoltjni.dylib
     *   macOS arm64  -> libjoltjni.dylib  (same name, different artifact jar)
     */
    private static String resolveNativeLibName() {
        String os   = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        if (os.contains("win")) {
            if (arch.contains("64")) return "joltjni.dll";
            throw unsupported(os, arch);
        }
        if (os.contains("linux")) {
            if (arch.contains("64") || arch.contains("amd64")) return "libjoltjni.so";
            throw unsupported(os, arch);
        }
        if (os.contains("mac")) {
            // Both x64 and arm64 macOS use the same filename, different jars
            return "libjoltjni.dylib";
        }
        throw unsupported(os, arch);
    }

    private static String stripExtension(String filename) {
        // System.loadLibrary wants the bare name without lib prefix or extension
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
