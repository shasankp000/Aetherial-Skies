package net.shasankp000.Physics;

import electrostatic4j.snaploader.LibraryInfo;
import electrostatic4j.snaploader.LoadingCriterion;
import electrostatic4j.snaploader.NativeBinaryLoader;
import electrostatic4j.snaploader.filesystem.DirectoryPath;
import electrostatic4j.snaploader.platform.NativeDynamicLibrary;
import electrostatic4j.snaploader.platform.util.PlatformPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the Jolt-JNI native library using JSnapLoader.
 *
 * <p>The jolt-jni platform jars store natives at paths like:
 * <pre>
 *   windows/x86-64/com/github/stephengold/joltjni.dll
 *   linux/x86-64/com/github/stephengold/libjoltjni.so
 *   osx/aarch64/com/github/stephengold/libjoltjni.dylib
 * </pre>
 * JSnapLoader extracts and loads the correct one for the current platform.
 */
public final class JoltNativeLoader {

    private static final Logger LOGGER =
            LoggerFactory.getLogger("AetherialSkies/JoltNativeLoader");

    private static volatile boolean loaded = false;

    private JoltNativeLoader() {}

    public static synchronized void load() {
        if (loaded) return;

        LOGGER.info("[JoltNativeLoader] Loading Jolt native library via JSnapLoader...");
        try {
            LibraryInfo info = new LibraryInfo(null, "joltjni", DirectoryPath.USER_DIR);
            NativeBinaryLoader loader = new NativeBinaryLoader(info);

            NativeDynamicLibrary[] libraries = {
                new NativeDynamicLibrary(
                    "linux/aarch64/com/github/stephengold", PlatformPredicate.LINUX_ARM_64),
                new NativeDynamicLibrary(
                    "linux/armhf/com/github/stephengold",   PlatformPredicate.LINUX_ARM_32),
                new NativeDynamicLibrary(
                    "linux/x86-64/com/github/stephengold",  PlatformPredicate.LINUX_X86_64),
                new NativeDynamicLibrary(
                    "osx/aarch64/com/github/stephengold",   PlatformPredicate.MACOS_ARM_64),
                new NativeDynamicLibrary(
                    "osx/x86-64/com/github/stephengold",    PlatformPredicate.MACOS_X86_64),
                new NativeDynamicLibrary(
                    "windows/x86-64/com/github/stephengold", PlatformPredicate.WIN_X86_64)
            };

            loader.registerNativeLibraries(libraries).initPlatformLibrary();
            loader.loadLibrary(LoadingCriterion.CLEAN_EXTRACTION);

            LOGGER.info("[JoltNativeLoader] Jolt native library loaded successfully.");
            loaded = true;
        } catch (Exception e) {
            throw new RuntimeException(
                "[JoltNativeLoader] Failed to load Jolt native library. " +
                "Ensure the jolt-jni-<Platform>:ReleaseSp dependency is on the classpath.", e);
        }
    }
}
