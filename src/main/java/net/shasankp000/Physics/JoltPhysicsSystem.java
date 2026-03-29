package net.shasankp000.Physics;

import com.github.stephengold.joltjni.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Singleton that owns and manages the Jolt PhysicsSystem for Aetherial Skies.
 *
 * Object layers:
 *   0 = TERRAIN  - static world blocks, never moves
 *   1 = SHIP     - kinematic ship bodies, position set each tick
 *
 * Lifecycle:
 *   init()            - called once on SERVER_STARTED
 *   stepSimulation()  - called every SERVER_TICK (1/20 s)
 *   destroy()         - called on SERVER_STOPPING
 */
public final class JoltPhysicsSystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("AetherialSkies/Jolt");

    public static final int LAYER_TERRAIN = 0;
    public static final int LAYER_SHIP    = 1;
    private static final int NUM_LAYERS    = 2;
    private static final int NUM_BP_LAYERS = 2; // BP 0 = terrain, BP 1 = ship

    private static final int MAX_BODIES              = 1024;
    private static final int NUM_BODY_MUTEXES         = 0;    // 0 = auto
    private static final int MAX_BODY_PAIRS           = 4096;
    private static final int MAX_CONTACT_CONSTRAINTS  = 2048;

    private static final float FIXED_TIMESTEP = 1f / 20f;

    // ---- singleton --------------------------------------------------------
    private static final JoltPhysicsSystem INSTANCE = new JoltPhysicsSystem();
    public static JoltPhysicsSystem getInstance() { return INSTANCE; }
    private JoltPhysicsSystem() {}

    // ---- Jolt objects -----------------------------------------------------
    private TempAllocator                      tempAllocator;
    private JobSystem                          jobSystem;
    private BroadPhaseLayerInterfaceTable      bpLayerInterface;
    private ObjectLayerPairFilterTable         oLayerFilter;
    private ObjectVsBroadPhaseLayerFilterTable ovsBpFilter;
    private PhysicsSystem                      physicsSystem;
    private BodyInterface                      bodyInterface;

    private volatile boolean initialised = false;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    public synchronized void init() {
        if (initialised) return;
        LOGGER.info("[JoltPhysicsSystem] Initialising Jolt Physics...");

        // Native library already loaded by JoltNativeLoader.load() in onInitialize().
        // Must call registerDefaultAllocator() before any other Jolt object is created.
        Jolt.registerDefaultAllocator();
        Jolt.installDefaultAssertCallback();
        Jolt.installDefaultTraceCallback();
        Jolt.newFactory();
        Jolt.registerTypes();

        tempAllocator = new TempAllocatorImpl(16 * 1024 * 1024); // 16 MB

        // JobSystemThreadPool(maxJobs, maxBarriers, numThreads)
        jobSystem = new JobSystemThreadPool(
            Jolt.cMaxPhysicsJobs,
            Jolt.cMaxPhysicsBarriers,
            Math.max(1, Runtime.getRuntime().availableProcessors() - 1)
        );

        // Map object layers to broad-phase layers.
        // LAYER_TERRAIN -> BP 0, LAYER_SHIP -> BP 1
        bpLayerInterface = new BroadPhaseLayerInterfaceTable(NUM_LAYERS, NUM_BP_LAYERS);
        bpLayerInterface.mapObjectToBroadPhaseLayer(LAYER_TERRAIN, 0);
        bpLayerInterface.mapObjectToBroadPhaseLayer(LAYER_SHIP,    1);

        // All collisions disabled by default; enable only SHIP <-> TERRAIN.
        oLayerFilter = new ObjectLayerPairFilterTable(NUM_LAYERS);
        oLayerFilter.enableCollision(LAYER_SHIP, LAYER_TERRAIN);

        // Broad-phase vs object-layer filter: automatically derived from
        // the pair filter + layer interface.
        ovsBpFilter = new ObjectVsBroadPhaseLayerFilterTable(
            bpLayerInterface, NUM_BP_LAYERS,
            oLayerFilter, NUM_LAYERS
        );

        physicsSystem = new PhysicsSystem();
        physicsSystem.init(
            MAX_BODIES, NUM_BODY_MUTEXES, MAX_BODY_PAIRS,
            MAX_CONTACT_CONSTRAINTS,
            bpLayerInterface, ovsBpFilter, oLayerFilter
        );

        bodyInterface = physicsSystem.getBodyInterface();
        initialised   = true;
        LOGGER.info("[JoltPhysicsSystem] Jolt Physics ready.");
    }

    /**
     * Steps the simulation by exactly one Minecraft tick (1/20 s).
     * Called from ServerTickEvents.END_SERVER_TICK.
     */
    public void stepSimulation() {
        if (!initialised) return;
        physicsSystem.update(FIXED_TIMESTEP, 1, tempAllocator, jobSystem);
    }

    public synchronized void destroy() {
        if (!initialised) return;
        LOGGER.info("[JoltPhysicsSystem] Shutting down Jolt Physics...");

        Jolt.unregisterTypes();
        Jolt.destroyFactory();

        // Free in reverse order of allocation.
        physicsSystem.close();
        ovsBpFilter.close();
        oLayerFilter.close();
        bpLayerInterface.close();
        jobSystem.close();
        tempAllocator.close();

        initialised = false;
        LOGGER.info("[JoltPhysicsSystem] Jolt Physics shut down.");
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public PhysicsSystem getPhysicsSystem() { return physicsSystem; }
    public BodyInterface getBodyInterface()  { return bodyInterface; }
    public boolean isInitialised()           { return initialised; }
}
