package net.shasankp000.Physics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Loads the Jolt-JNI native library at runtime by extracting it from the
 * classpath (i.e. from the jolt-jni platform jar on the classpath).
 *
 * <p>The Maven Central platform jars store natives at:
 * <pre>
 *   windows/x86-64/com/github/stephengold/joltjni.dll
 *   linux/x86-64/com/github/stephengold/libjoltjni.so
 *   osx/x86-64/com/github/stephengold/libjoltjni.dylib
 *   osx/aarch64/com/github/stephengold/libjoltjni.dylib
 * </pre>
 */
public final class JoltNativeLoader {

    private static final Logger LOGGER =
            LoggerFactory.getLogger("AetherialSkies/JoltNativeLoader");

    private static final Path NATIVE_DIR =
            Paths.get(System.getProperty("user.home"), ".jolt-jni");

    private static volatile boolean loaded = false;

    private JoltNativeLoader() {}

    public static synchronized void load() {
        if (loaded) return;

        String nativeLib = resolveNativeLibName();
        String resourcePath = resolveResourcePath(nativeLib);

        LOGGER.info("[JoltNativeLoader] Loading '{}' from classpath resource '{}'",
                nativeLib, resourcePath);

        // Try the known Maven Central resource path first, then flat root fallback.
        InputStream in = JoltNativeLoader.class.getResourceAsStream(resourcePath);
        if (in == null) {
            LOGGER.warn("[JoltNativeLoader] Not found at '{}', trying flat root '/{}'.",
                    resourcePath, nativeLib);
            in = JoltNativeLoader.class.getResourceAsStream("/" + nativeLib);
        }

        if (in == null) {
            throw new IllegalStateException(
                "[JoltNativeLoader] Could not find '" + nativeLib + "' on the classpath. " +
                "Tried: '" + resourcePath + "' and '/" + nativeLib + "'. " +
                "Ensure the correct jolt-jni platform dependency is declared in build.gradle."
            );
        }

        try {
            Files.createDirectories(NATIVE_DIR);
            Path dest = NATIVE_DIR.resolve(nativeLib);
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            in.close();
            System.load(dest.toAbsolutePath().toString());
            LOGGER.info("[JoltNativeLoader] Successfully loaded from: {}", dest);
            loaded = true;
        } catch (IOException e) {
            throw new RuntimeException("[JoltNativeLoader] Failed to extract native library.", e);
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException(
                "[JoltNativeLoader] System.load() failed for '" + nativeLib + "'. " +
                "On Windows, ensure the Visual C++ Redistributable 2019+ is installed: " +
                "https://aka.ms/vs/17/release/vc_redist.x64.exe", e);
        }
    }

    /**
     * Returns the classpath resource path for the native library as stored
     * inside the Maven Central jolt-jni platform jars.
     */
    private static String resolveResourcePath(String nativeLib) {
        String os   = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        String osDir;
        String archDir;

        if (os.contains("win")) {
            osDir = "windows";
            archDir = "x86-64";
        } else if (os.contains("linux")) {
            osDir = "linux";
            archDir = "x86-64";
        } else if (os.contains("mac")) {
            osDir = "osx";
            archDir = arch.contains("aarch64") || arch.contains("arm") ? "aarch64" : "x86-64";
        } else {
            // Unknown OS - fall back to flat root, resolveNativeLibName will have already thrown
            // for truly unsupported platforms.
            return "/" + nativeLib;
        }

        return "/" + osDir + "/" + archDir + "/com/github/stephengold/" + nativeLib;
    }

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
        if (os.contains("mac")) return "libjoltjni.dylib";
        throw unsupported(os, arch);
    }

    private static UnsupportedOperationException unsupported(String os, String arch) {
        return new UnsupportedOperationException(
                "[JoltNativeLoader] Unsupported platform: os=" + os + " arch=" + arch);
    }
}
