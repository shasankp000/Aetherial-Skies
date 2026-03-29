package net.shasankp000.Physics;

import com.github.stephengold.joltjni.*;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Ship.ShipHullData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Singleton that owns and manages the Jolt PhysicsSystem for Aetherial Skies.
 *
 * Object layers:
 *   0 = TERRAIN  – static world blocks, never moves
 *   1 = SHIP     – kinematic ship bodies, position set each tick by ShipPhysicsEngine
 *
 * Lifecycle:
 *   init()                    – called once on SERVER_STARTED
 *   registerShipBody()        – called when a ship is deployed
 *   updateBodyTransform()     – called every tick per ship (after PD controller)
 *   stepSimulation()          – called every SERVER_TICK after all body updates
 *   removeShipBody()          – called when a ship is destroyed/docked
 *   destroy()                 – called on SERVER_STOPPING
 */
public final class JoltPhysicsSystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("AetherialSkies/Jolt");

    public static final int LAYER_TERRAIN = 0;
    public static final int LAYER_SHIP    = 1;
    private static final int NUM_LAYERS    = 2;
    private static final int NUM_BP_LAYERS = 2;

    private static final int MAX_BODIES             = 1024;
    private static final int NUM_BODY_MUTEXES        = 0;
    private static final int MAX_BODY_PAIRS          = 4096;
    private static final int MAX_CONTACT_CONSTRAINTS = 2048;

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

    /** Maps ship UUID → Jolt BodyID value (int). */
    private final Map<UUID, Integer> shipBodyIds = new HashMap<>();

    private volatile boolean initialised = false;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    public synchronized void init() {
        if (initialised) return;
        LOGGER.info("[JoltPhysicsSystem] Initialising Jolt Physics...");

        Jolt.registerDefaultAllocator();
        Jolt.installDefaultAssertCallback();
        Jolt.installDefaultTraceCallback();
        Jolt.newFactory();
        Jolt.registerTypes();

        tempAllocator = new TempAllocatorImpl(16 * 1024 * 1024);

        jobSystem = new JobSystemThreadPool(
            Jolt.cMaxPhysicsJobs,
            Jolt.cMaxPhysicsBarriers,
            Math.max(1, Runtime.getRuntime().availableProcessors() - 1)
        );

        bpLayerInterface = new BroadPhaseLayerInterfaceTable(NUM_LAYERS, NUM_BP_LAYERS);
        bpLayerInterface.mapObjectToBroadPhaseLayer(LAYER_TERRAIN, 0);
        bpLayerInterface.mapObjectToBroadPhaseLayer(LAYER_SHIP,    1);

        oLayerFilter = new ObjectLayerPairFilterTable(NUM_LAYERS);
        oLayerFilter.enableCollision(LAYER_SHIP, LAYER_TERRAIN);

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

    // -----------------------------------------------------------------------
    // Ship body management
    // -----------------------------------------------------------------------

    /**
     * Registers a kinematic box body for the given ship.
     * The box dimensions are derived from the hull AABB.
     * Must be called on the server thread after init().
     */
    public synchronized void registerShipBody(UUID shipId, ShipHullData hullData, Vec3d initialPos, float initialYaw) {
        if (!initialised) return;
        if (shipBodyIds.containsKey(shipId)) return; // already registered

        ShipHullData.HullBounds bounds = hullData.computeBounds();

        // Half-extents for the box shape (Jolt uses half-extents).
        float hx = (float) (bounds.widthX() / 2.0);
        float hy = (float) (bounds.height()  / 2.0);
        float hz = (float) (bounds.widthZ()  / 2.0);

        // Jolt minimum half-extent is 0.05 to avoid degenerate shapes.
        hx = Math.max(hx, 0.05f);
        hy = Math.max(hy, 0.05f);
        hz = Math.max(hz, 0.05f);

        BoxShapeSettings shapeSettings = new BoxShapeSettings(hx, hy, hz);
        ShapeResult shapeResult = shapeSettings.create();
        if (shapeResult.hasError()) {
            LOGGER.error("[JoltPhysicsSystem] Failed to create BoxShape for ship {}: {}",
                    shipId, shapeResult.getError());
            return;
        }
        // ShapeResult.get() returns ShapeRefC in jolt-jni 3.9.0
        ShapeRefC shape = shapeResult.get();

        // Centre of the box in world space = ship position + hull centre offset.
        float cx = (float) (initialPos.x + (bounds.minX() + bounds.maxX()) / 2.0);
        float cy = (float) (initialPos.y + (bounds.minY() + bounds.maxY()) / 2.0);
        float cz = (float) (initialPos.z + (bounds.minZ() + bounds.maxZ()) / 2.0);

        // Convert yaw (degrees, Y-axis) to a Jolt quaternion.
        float halfYaw = (float) Math.toRadians(initialYaw / 2.0);
        float qw = (float) Math.cos(halfYaw);
        float qy = (float) Math.sin(halfYaw);

        BodyCreationSettings settings = new BodyCreationSettings(
            shape,
            new RVec3(cx, cy, cz),
            new Quat(0f, qy, 0f, qw),
            EMotionType.Kinematic,
            LAYER_SHIP
        );

        Body body = bodyInterface.createBody(settings);
        if (body == null) {
            LOGGER.error("[JoltPhysicsSystem] createBody returned null for ship {} (body limit reached?)", shipId);
            return;
        }

        // Body.getId() returns a primitive int in jolt-jni 3.9.0
        int bodyId = body.getId();
        bodyInterface.addBody(bodyId, EActivation.Activate);
        shipBodyIds.put(shipId, bodyId);
        LOGGER.info("[JoltPhysicsSystem] Registered kinematic body for ship {} (bodyId={})",
                shipId, bodyId);
    }

    /**
     * Moves the Jolt kinematic body to match the position/yaw computed by
     * ShipPhysicsEngine this tick. Call this BEFORE stepSimulation().
     */
    public void updateBodyTransform(UUID shipId, Vec3d pos, float yawDegrees,
                                    ShipHullData hullData) {
        if (!initialised) return;
        Integer rawId = shipBodyIds.get(shipId);
        if (rawId == null) return;

        ShipHullData.HullBounds bounds = hullData.computeBounds();
        float cx = (float) (pos.x + (bounds.minX() + bounds.maxX()) / 2.0);
        float cy = (float) (pos.y + (bounds.minY() + bounds.maxY()) / 2.0);
        float cz = (float) (pos.z + (bounds.minZ() + bounds.maxZ()) / 2.0);

        float halfYaw = (float) Math.toRadians(yawDegrees / 2.0);
        float qw = (float) Math.cos(halfYaw);
        float qy = (float) Math.sin(halfYaw);

        // BodyInterface.setPositionAndRotation takes a plain int bodyId
        bodyInterface.setPositionAndRotation(
            rawId,
            new RVec3(cx, cy, cz),
            new Quat(0f, qy, 0f, qw),
            EActivation.Activate
        );
    }

    /**
     * Reads the current Jolt body centre-of-mass position back, converts it
     * to the ship's worldOffset (pivot point), and returns it.
     * Returns the fallback position if the body is not registered.
     */
    public Vec3d getBodyPosition(UUID shipId, ShipHullData hullData, Vec3d fallback) {
        if (!initialised) return fallback;
        Integer rawId = shipBodyIds.get(shipId);
        if (rawId == null) return fallback;

        ShipHullData.HullBounds bounds = hullData.computeBounds();
        // BodyInterface.getPosition takes a plain int bodyId
        RVec3 centre = bodyInterface.getPosition(rawId);

        // Reverse the centre-offset to get the ship pivot position.
        double px = centre.xx() - (bounds.minX() + bounds.maxX()) / 2.0;
        double py = centre.yy() - (bounds.minY() + bounds.maxY()) / 2.0;
        double pz = centre.zz() - (bounds.minZ() + bounds.maxZ()) / 2.0;
        return new Vec3d(px, py, pz);
    }

    /**
     * Removes and destroys the Jolt body for the given ship.
     */
    public synchronized void removeShipBody(UUID shipId) {
        if (!initialised) return;
        Integer rawId = shipBodyIds.remove(shipId);
        if (rawId == null) return;

        // BodyInterface.removeBody / destroyBody take a plain int bodyId
        bodyInterface.removeBody(rawId);
        bodyInterface.destroyBody(rawId);
        LOGGER.info("[JoltPhysicsSystem] Removed body for ship {}", shipId);
    }

    // -----------------------------------------------------------------------
    // Simulation step
    // -----------------------------------------------------------------------

    /**
     * Steps the simulation by exactly one Minecraft tick (1/20 s).
     * Called from ShipTransformManager.tick() AFTER all updateBodyTransform() calls.
     */
    public void stepSimulation() {
        if (!initialised) return;
        physicsSystem.update(FIXED_TIMESTEP, 1, tempAllocator, jobSystem);
    }

    // -----------------------------------------------------------------------
    // Shutdown
    // -----------------------------------------------------------------------

    public synchronized void destroy() {
        if (!initialised) return;
        LOGGER.info("[JoltPhysicsSystem] Shutting down Jolt Physics...");

        // Remove all ship bodies before tearing down the system.
        for (Integer rawId : shipBodyIds.values()) {
            bodyInterface.removeBody(rawId);
            bodyInterface.destroyBody(rawId);
        }
        shipBodyIds.clear();

        Jolt.unregisterTypes();
        Jolt.destroyFactory();

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
