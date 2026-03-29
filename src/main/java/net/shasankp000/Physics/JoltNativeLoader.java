package net.shasankp000.Physics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
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
 * <p>Extraction strategy:
 * <ol>
 *   <li>System.loadLibrary — works if the native is already on java.library.path.</li>
 *   <li>Jar-on-disk scan — opens each JAR listed in {@code jolt.libs.path}
 *       (injected by build.gradle), extracts the native to a stable directory
 *       ({@code ~/.jolt-jni/}), prepends that dir to PATH on Windows so
 *       side-by-side DLL dependencies (VCRUNTIME etc.) can be found, then
 *       calls System.load().</li>
 *   <li>Classpath resource — last resort for the production mod jar where
 *       natives are bundled flat at the root.</li>
 * </ol>
 */
public final class JoltNativeLoader {

    private static final Logger LOGGER =
            LoggerFactory.getLogger("AetherialSkies/JoltNativeLoader");

    public static final String JOLT_LIBS_PATH_PROP = "jolt.libs.path";

    /** Stable extraction directory: ~/.jolt-jni/ */
    private static final Path NATIVE_DIR =
            Paths.get(System.getProperty("user.home"), ".jolt-jni");

    private static volatile boolean loaded = false;

    private JoltNativeLoader() {}

    public static synchronized void load() {
        if (loaded) return;

        String nativeLib = resolveNativeLibName();
        LOGGER.info("[JoltNativeLoader] Loading native library: {} (extraction dir: {})",
                nativeLib, NATIVE_DIR);

        // ------------------------------------------------------------------
        // 1. System.loadLibrary — fast path.
        // ------------------------------------------------------------------
        try {
            System.loadLibrary(stripExtension(nativeLib));
            LOGGER.info("[JoltNativeLoader] Loaded via System.loadLibrary.");
            loaded = true;
            return;
        } catch (UnsatisfiedLinkError ignored) {}

        // Ensure the stable extraction directory exists.
        try {
            Files.createDirectories(NATIVE_DIR);
        } catch (IOException e) {
            throw new RuntimeException("[JoltNativeLoader] Cannot create native dir: " + NATIVE_DIR, e);
        }

        // On Windows, prepend NATIVE_DIR to PATH so the DLL loader can find
        // any co-located dependencies (VCRUNTIME140.dll, etc.).
        prependToPath(NATIVE_DIR);

        // ------------------------------------------------------------------
        // 2. Jar-on-disk scan via jolt.libs.path property.
        // ------------------------------------------------------------------
        String libsPath = System.getProperty(JOLT_LIBS_PATH_PROP);
        if (libsPath != null && !libsPath.isEmpty()) {
            for (String jarPathStr : libsPath.split(";")) {
                jarPathStr = jarPathStr.trim();
                if (jarPathStr.isEmpty()) continue;
                Path extracted = tryExtractFromJar(Paths.get(jarPathStr), nativeLib);
                if (extracted != null) {
                    try {
                        System.load(extracted.toAbsolutePath().toString());
                        LOGGER.info("[JoltNativeLoader] Loaded from jar: {}", extracted);
                        loaded = true;
                        return;
                    } catch (UnsatisfiedLinkError e) {
                        // Log full message for diagnosis — this is a real error.
                        LOGGER.error("[JoltNativeLoader] System.load('{}') failed: {}",
                                extracted, e.getMessage());
                        throw new RuntimeException(
                            "[JoltNativeLoader] System.load failed for '" + extracted +
                            "'. This usually means a missing DLL dependency on Windows " +
                            "(e.g. VCRUNTIME140.dll). Make sure the Visual C++ Redistributable " +
                            "2019+ is installed: https://aka.ms/vs/17/release/vc_redist.x64.exe", e);
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // 3. Classpath resource fallback (production bundled jar).
        // ------------------------------------------------------------------
        try (InputStream in = JoltNativeLoader.class.getResourceAsStream("/" + nativeLib)) {
            if (in != null) {
                Path dest = NATIVE_DIR.resolve(nativeLib);
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                System.load(dest.toAbsolutePath().toString());
                LOGGER.info("[JoltNativeLoader] Loaded from classpath resource: {}", dest);
                loaded = true;
                return;
            }
        } catch (IOException | UnsatisfiedLinkError e) {
            LOGGER.error("[JoltNativeLoader] Classpath resource load failed: {}", e.getMessage());
        }

        // ------------------------------------------------------------------
        // 4. Give up.
        // ------------------------------------------------------------------
        throw new IllegalStateException(
            "[JoltNativeLoader] Could not load '" + nativeLib + "'. " +
            "Checked: System.loadLibrary, jar-on-disk (" + JOLT_LIBS_PATH_PROP +
            "=" + libsPath + "), classpath resource. " +
            "Ensure jolt-jni-Windows64-3.9.0.jar is in libs/."
        );
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Scans {@code jarPath} for an entry whose bare filename equals
     * {@code nativeLib}, extracts it to {@link #NATIVE_DIR}, and returns
     * the extracted path. Returns null if not found in this jar.
     */
    private static Path tryExtractFromJar(Path jarPath, String nativeLib) {
        if (!jarPath.toFile().exists()) {
            LOGGER.warn("[JoltNativeLoader] Jar not found on disk: {}", jarPath);
            return null;
        }
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            java.util.Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();
                String entryFile = entryName.contains("/")
                        ? entryName.substring(entryName.lastIndexOf('/') + 1)
                        : entryName;
                if (entryFile.equalsIgnoreCase(nativeLib)) {
                    LOGGER.info("[JoltNativeLoader] Found '{}' at '{}' inside {}",
                            nativeLib, entryName, jarPath.getFileName());
                    Path dest = NATIVE_DIR.resolve(nativeLib);
                    try (InputStream in = jar.getInputStream(entry)) {
                        Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                    LOGGER.info("[JoltNativeLoader] Extracted to: {}", dest);
                    return dest;
                }
            }
            LOGGER.warn("[JoltNativeLoader] '{}' not found inside {}", nativeLib, jarPath.getFileName());
        } catch (IOException e) {
            LOGGER.error("[JoltNativeLoader] Failed to scan jar {}: {}", jarPath, e.getMessage());
        }
        return null;
    }

    /**
     * Prepends {@code dir} to the JVM's internal usr_paths list so that
     * System.load can find DLL dependencies placed alongside the native.
     * This is a best-effort hack; if the reflection fails we log and continue.
     */
    private static void prependToPath(Path dir) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) return; // Only needed on Windows.
        try {
            Field usrPathsField = ClassLoader.class.getDeclaredField("usr_paths");
            usrPathsField.setAccessible(true);
            String[] current = (String[]) usrPathsField.get(null);
            String add = dir.toAbsolutePath().toString();
            for (String p : current) if (p.equals(add)) return; // already present
            String[] updated = new String[current.length + 1];
            updated[0] = add;
            System.arraycopy(current, 0, updated, 1, current.length);
            usrPathsField.set(null, updated);
            LOGGER.info("[JoltNativeLoader] Prepended '{}' to JVM usr_paths.", add);
        } catch (Exception e) {
            LOGGER.warn("[JoltNativeLoader] Could not prepend to usr_paths (non-fatal): {}", e.getMessage());
        }
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
