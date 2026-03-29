package net.shasankp000.Physics;

import com.github.stephengold.joltjni.*;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import com.github.stephengold.joltjni.enumerate.EActivation;
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
    private static final int NUM_LAYERS   = 2;

    // Jolt tuning constants
    private static final int MAX_BODIES          = 1024;
    private static final int NUM_BODY_MUTEXES     = 0;    // 0 = auto
    private static final int MAX_BODY_PAIRS       = 4096;
    private static final int MAX_CONTACT_CONSTRAINTS = 2048;

    private static final float FIXED_TIMESTEP = 1f / 20f;

    // ---- singleton --------------------------------------------------------
    private static final JoltPhysicsSystem INSTANCE = new JoltPhysicsSystem();
    public static JoltPhysicsSystem getInstance() { return INSTANCE; }
    private JoltPhysicsSystem() {}

    // ---- Jolt objects (allocated in init, freed in destroy) ---------------
    private TempAllocator      tempAllocator;
    private JobSystem          jobSystem;
    private BroadPhaseLayerInterface     bpLayerInterface;
    private ObjectVsBroadPhaseLayerFilter ovsBpFilter;
    private ObjectLayerPairFilter         oLayerFilter;
    private PhysicsSystem      physicsSystem;
    private BodyInterface      bodyInterface;

    private volatile boolean initialised = false;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    public synchronized void init() {
        if (initialised) return;
        LOGGER.info("[JoltPhysicsSystem] Initialising Jolt Physics...");

        // Must be called once before any Jolt object is created
        Jolt.load();
        Jolt.registerDefaultAllocator();
        Jolt.installDefaultAssertCallback();
        Jolt.installDefaultTraceCallback();
        Jolt.registerTypes();

        tempAllocator = new TempAllocatorImpl(16 * 1024 * 1024); // 16 MB
        jobSystem     = new JobSystemThreadPool(
            Jolt.maxPhysicsJobs(), Jolt.maxPhysicsBarriers(),
            Math.max(1, Runtime.getRuntime().availableProcessors() - 1)
        );

        // Broad-phase layers: TERRAIN -> BP layer 0, SHIP -> BP layer 1
        bpLayerInterface = new BroadPhaseLayerInterfaceTable(NUM_LAYERS, 2);
        ((BroadPhaseLayerInterfaceTable) bpLayerInterface)
            .mapObjectToBroadPhaseLayer(LAYER_TERRAIN, new BroadPhaseLayer(0))
            .mapObjectToBroadPhaseLayer(LAYER_SHIP,    new BroadPhaseLayer(1));

        // Filters: SHIP collides with TERRAIN, not with other SHIPs
        ovsBpFilter = new ObjectVsBroadPhaseLayerFilterTable(
            bpLayerInterface, NUM_LAYERS, 2);
        ((ObjectVsBroadPhaseLayerFilterTable) ovsBpFilter)
            .disablePair(LAYER_TERRAIN, 1)
            .enablePair(LAYER_SHIP,    0)
            .disablePair(LAYER_SHIP,   1);

        oLayerFilter = new ObjectLayerPairFilterTable(NUM_LAYERS);
        ((ObjectLayerPairFilterTable) oLayerFilter)
            .disablePair(LAYER_TERRAIN, LAYER_TERRAIN)
            .enablePair(LAYER_SHIP,    LAYER_TERRAIN)
            .disablePair(LAYER_SHIP,   LAYER_SHIP);

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
     * Call from ServerTickEvents.END_SERVER_TICK.
     */
    public void stepSimulation() {
        if (!initialised) return;
        physicsSystem.update(FIXED_TIMESTEP, 1, tempAllocator, jobSystem);
    }

    public synchronized void destroy() {
        if (!initialised) return;
        LOGGER.info("[JoltPhysicsSystem] Shutting down Jolt Physics...");

        // Free in reverse order of allocation
        physicsSystem.close();
        oLayerFilter.close();
        ovsBpFilter.close();
        bpLayerInterface.close();
        jobSystem.close();
        tempAllocator.close();

        initialised = false;
        LOGGER.info("[JoltPhysicsSystem] Jolt Physics shut down.");
    }

    // -----------------------------------------------------------------------
    // Accessors for later steps
    // -----------------------------------------------------------------------

    public PhysicsSystem getPhysicsSystem() { return physicsSystem; }
    public BodyInterface getBodyInterface()  { return bodyInterface; }
    public boolean isInitialised()           { return initialised; }
}
