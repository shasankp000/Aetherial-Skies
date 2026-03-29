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
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Loads the Jolt-JNI native library (.dll / .so / .dylib) at runtime.
 *
 * <h3>Strategy (in order)</h3>
 * <ol>
 *   <li><b>System.loadLibrary</b> – works when {@code java.library.path}
 *       already contains the native (production mod jar or explicit JVM arg).</li>
 *   <li><b>Jar-on-disk scan</b> – reads the paths listed in the system
 *       property {@code jolt.libs.path} (set by {@code build.gradle} for dev
 *       runs), opens each JAR file directly, finds the native by filename,
 *       extracts it to a temp directory, and calls {@code System.load}.</li>
 *   <li><b>Classpath resource</b> – last resort; tries {@code /joltjni.dll}
 *       etc. at the classpath root (works when natives are bundled flat into
 *       the production mod jar).</li>
 * </ol>
 */
public final class JoltNativeLoader {

    private static final Logger LOGGER =
            LoggerFactory.getLogger("AetherialSkies/JoltNativeLoader");

    /** System property injected by build.gradle listing joltLibs jar paths, semicolon-separated. */
    public static final String JOLT_LIBS_PATH_PROP = "jolt.libs.path";

    private static volatile boolean loaded = false;

    private JoltNativeLoader() {}

    public static synchronized void load() {
        if (loaded) return;

        String nativeLib = resolveNativeLibName();
        LOGGER.info("[JoltNativeLoader] Loading native library: {}", nativeLib);

        // ------------------------------------------------------------------
        // 1. System.loadLibrary – fast path for production / explicit path.
        // ------------------------------------------------------------------
        try {
            System.loadLibrary(stripExtension(nativeLib));
            LOGGER.info("[JoltNativeLoader] Loaded via System.loadLibrary.");
            loaded = true;
            return;
        } catch (UnsatisfiedLinkError ignored) {}

        // ------------------------------------------------------------------
        // 2. Scan the joltLibs jars listed in the 'jolt.libs.path' property.
        //    build.gradle injects this property for runClient / runServer.
        // ------------------------------------------------------------------
        String libsPath = System.getProperty(JOLT_LIBS_PATH_PROP);
        if (libsPath != null && !libsPath.isEmpty()) {
            for (String jarPath : libsPath.split(";")) {
                jarPath = jarPath.trim();
                if (jarPath.isEmpty()) continue;
                Path result = tryExtractFromJar(Paths.get(jarPath), nativeLib);
                if (result != null) {
                    try {
                        System.load(result.toAbsolutePath().toString());
                        LOGGER.info("[JoltNativeLoader] Loaded from jar-on-disk: {}", result);
                        loaded = true;
                        return;
                    } catch (UnsatisfiedLinkError e) {
                        LOGGER.warn("[JoltNativeLoader] System.load failed for {}: {}", result, e.getMessage());
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // 3. Classpath resource fallback (production bundled jar).
        // ------------------------------------------------------------------
        try (InputStream in = JoltNativeLoader.class.getResourceAsStream("/" + nativeLib)) {
            if (in != null) {
                Path tempLib = extractToTemp(in, nativeLib);
                System.load(tempLib.toAbsolutePath().toString());
                LOGGER.info("[JoltNativeLoader] Loaded from classpath resource: {}", tempLib);
                loaded = true;
                return;
            }
        } catch (IOException | UnsatisfiedLinkError e) {
            LOGGER.warn("[JoltNativeLoader] Classpath resource extraction failed: {}", e.getMessage());
        }

        // ------------------------------------------------------------------
        // 4. Give up with a clear message.
        // ------------------------------------------------------------------
        throw new IllegalStateException(
            "[JoltNativeLoader] Could not load native library '" + nativeLib + "'. " +
            "Checked: System.loadLibrary, jar-on-disk scan (" +
            JOLT_LIBS_PATH_PROP + "=" + libsPath + "), classpath resource. " +
            "Ensure jolt-jni-Windows64-3.9.0.jar (or the correct platform jar) " +
            "is present in libs/ and declared as joltLibs in build.gradle."
        );
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Opens the given JAR file, finds the first entry whose filename matches
     * {@code nativeLib} (case-insensitive), extracts it to a temp dir, and
     * returns the extracted path. Returns null if not found.
     */
    private static Path tryExtractFromJar(Path jarPath, String nativeLib) {
        if (!jarPath.toFile().exists()) return null;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            java.util.Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();
                // Match just the filename portion, any subdirectory.
                String entryFile = entryName.contains("/")
                        ? entryName.substring(entryName.lastIndexOf('/') + 1)
                        : entryName;
                if (entryFile.equalsIgnoreCase(nativeLib)) {
                    LOGGER.info("[JoltNativeLoader] Found '{}' at '{}' in {}",
                            nativeLib, entryName, jarPath.getFileName());
                    try (InputStream in = jar.getInputStream(entry)) {
                        return extractToTemp(in, nativeLib);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[JoltNativeLoader] Could not scan jar {}: {}", jarPath, e.getMessage());
        }
        return null;
    }

    private static Path extractToTemp(InputStream in, String nativeLib) throws IOException {
        Path tempDir = Files.createTempDirectory("jolt-jni-");
        tempDir.toFile().deleteOnExit();
        Path tempLib = tempDir.resolve(nativeLib);
        Files.copy(in, tempLib, StandardCopyOption.REPLACE_EXISTING);
        tempLib.toFile().deleteOnExit();
        return tempLib;
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

    private static String stripExtension(String filename) {
        String s = filename;
        if (s.startsWith("lib")) s = s.substring(3);
        int dot = s.lastIndexOf('.');
        if (dot >= 0) s = s.substring(0, dot);
        return s;
    }

    private static UnsupportedOperationException unsupported(String os, String arch) {
        return new UnsupportedOperationException(
            "[JoltNativeLoader] Unsupported platform: os=" + os + " arch=" + arch);
    }
}
