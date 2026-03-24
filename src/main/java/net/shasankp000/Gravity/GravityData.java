package net.shasankp000.Gravity;

import net.minecraft.block.Block;

import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;


public class GravityData {

    public record PhysicsProfile(
            float mass,
            float gravityAccel,
            float airDrag,
            float angularDrag,
            float groundFriction,
            float restitution,
            float relativeDensity,
            float displacedVolume,
            float buoyancyAssist
    ) {
    }

    // This record is intended for future ship/raft support where many blocks compose one rigid body.
    public record HydroComposition(
            float totalMass,
            float totalDisplacedVolume,
            float effectiveRelativeDensity,
            float buoyancyAssist
    ) {
    }

    private static final Set<Identifier> GRAVITY_ENABLED_BLOCKS = new HashSet<>();
    private static final Map<Identifier, Float> GRAVITY_WEIGHTS = new HashMap<>();
    private static final Map<Identifier, PhysicsProfile> PHYSICS_PROFILES = new HashMap<>();

    private static final PhysicsProfile DEFAULT_PROFILE = new PhysicsProfile(
            1.0f,
            0.08f,
            0.02f,
            0.02f,
            0.60f,
            0.05f,
            1.15f,
            1.0f,
            0.0f
    );

    // Static initializer: populate the map with desired blocks
    static {
        // The list of blocks that have gravity
        GRAVITY_ENABLED_BLOCKS.add(Identifier.of("minecraft", "dirt"));
        GRAVITY_ENABLED_BLOCKS.add(Identifier.of("minecraft", "cobblestone"));
        GRAVITY_ENABLED_BLOCKS.add(Identifier.of("minecraft", "stone"));
        GRAVITY_ENABLED_BLOCKS.add(Identifier.of("minecraft", "grass_block"));
        GRAVITY_ENABLED_BLOCKS.add(Identifier.of("minecraft", "oak_planks"));
        GRAVITY_ENABLED_BLOCKS.add(Identifier.of("minecraft", "bookshelf"));
        GRAVITY_ENABLED_BLOCKS.add(Identifier.of("minecraft", "netherrack"));
        GRAVITY_ENABLED_BLOCKS.add(Identifier.of("minecraft", "end_stone"));
        GRAVITY_ENABLED_BLOCKS.add(Identifier.of("minecraft", "clay"));
        GRAVITY_ENABLED_BLOCKS.add(Identifier.of("minecraft", "brick_block"));
        GRAVITY_ENABLED_BLOCKS.add(Identifier.of("minecraft", "quartz_block"));

        // Assign weights
        GRAVITY_WEIGHTS.put(Identifier.of("minecraft", "dirt"), 2.0f);
        GRAVITY_WEIGHTS.put(Identifier.of("minecraft", "cobblestone"), 2.5f);
        GRAVITY_WEIGHTS.put(Identifier.of("minecraft", "stone"), 3.0f);
        GRAVITY_WEIGHTS.put(Identifier.of("minecraft", "grass_block"), 2.0f);
        GRAVITY_WEIGHTS.put(Identifier.of("minecraft", "oak_planks"), 1.5f);
        GRAVITY_WEIGHTS.put(Identifier.of("minecraft", "bookshelf"), 1.8f);
        GRAVITY_WEIGHTS.put(Identifier.of("minecraft", "netherrack"), 2.2f);
        GRAVITY_WEIGHTS.put(Identifier.of("minecraft", "end_stone"), 2.8f);
        GRAVITY_WEIGHTS.put(Identifier.of("minecraft", "clay"), 1.7f);
        GRAVITY_WEIGHTS.put(Identifier.of("minecraft", "brick"), 3.2f);
        GRAVITY_WEIGHTS.put(Identifier.of("minecraft", "quartz_block"), 2.9f);

        PHYSICS_PROFILES.put(Identifier.of("minecraft", "dirt"), new PhysicsProfile(2.0f, 0.075f, 0.025f, 0.03f, 0.62f, 0.03f, 1.25f, 1.0f, 0.0f));
        PHYSICS_PROFILES.put(Identifier.of("minecraft", "cobblestone"), new PhysicsProfile(2.5f, 0.08f, 0.018f, 0.018f, 0.68f, 0.02f, 1.55f, 1.0f, 0.0f));
        PHYSICS_PROFILES.put(Identifier.of("minecraft", "stone"), new PhysicsProfile(3.0f, 0.082f, 0.016f, 0.016f, 0.72f, 0.015f, 1.75f, 1.0f, 0.0f));
        PHYSICS_PROFILES.put(Identifier.of("minecraft", "grass_block"), new PhysicsProfile(2.0f, 0.075f, 0.025f, 0.03f, 0.64f, 0.025f, 1.20f, 1.0f, 0.0f));
        PHYSICS_PROFILES.put(Identifier.of("minecraft", "oak_planks"), new PhysicsProfile(1.5f, 0.07f, 0.03f, 0.035f, 0.58f, 0.08f, 0.72f, 1.0f, 0.06f));
        PHYSICS_PROFILES.put(Identifier.of("minecraft", "bookshelf"), new PhysicsProfile(1.8f, 0.072f, 0.028f, 0.032f, 0.60f, 0.07f, 0.84f, 1.0f, 0.05f));
        PHYSICS_PROFILES.put(Identifier.of("minecraft", "netherrack"), new PhysicsProfile(2.2f, 0.078f, 0.021f, 0.022f, 0.63f, 0.03f, 1.05f, 1.0f, 0.01f));
        PHYSICS_PROFILES.put(Identifier.of("minecraft", "end_stone"), new PhysicsProfile(2.8f, 0.08f, 0.017f, 0.017f, 0.70f, 0.02f, 1.62f, 1.0f, 0.0f));
        PHYSICS_PROFILES.put(Identifier.of("minecraft", "clay"), new PhysicsProfile(1.7f, 0.073f, 0.026f, 0.03f, 0.66f, 0.02f, 1.32f, 1.0f, 0.0f));
        PHYSICS_PROFILES.put(Identifier.of("minecraft", "quartz_block"), new PhysicsProfile(2.9f, 0.081f, 0.016f, 0.017f, 0.71f, 0.02f, 1.68f, 1.0f, 0.0f));
    }

    /**
     * Checks if the given block is gravity-enabled.
     */
    public static boolean isGravityEnabled(Block block) {
        Identifier id = Registries.BLOCK.getId(block);
        return GRAVITY_ENABLED_BLOCKS.contains(id);
    }


    /**
     * Retrieves the custom weight of a block, defaulting to 1.0 if not found.
     */
    public static float getWeight(Block block) {
        Identifier id = Registries.BLOCK.getId(block);
        return GRAVITY_WEIGHTS.getOrDefault(id, 1.0f);
    }

    public static PhysicsProfile getProfile(Block block) {
        Identifier id = Registries.BLOCK.getId(block);
        PhysicsProfile profile = PHYSICS_PROFILES.get(id);
        if (profile != null) {
            return profile;
        }

        float weight = getWeight(block);
        return new PhysicsProfile(
                Math.max(0.5f, weight),
                MathHelper.clamp(0.06f + (weight * 0.008f), 0.06f, 0.10f),
                MathHelper.clamp(0.03f - (weight * 0.003f), 0.012f, 0.03f),
                MathHelper.clamp(0.03f - (weight * 0.003f), 0.012f, 0.03f),
                MathHelper.clamp(0.55f + (weight * 0.06f), 0.5f, 0.8f),
                MathHelper.clamp(0.08f - (weight * 0.01f), 0.01f, 0.1f),
                MathHelper.clamp(0.65f + (weight * 0.25f), 0.65f, 1.9f),
                1.0f,
                0.0f
        );
    }

    public static HydroComposition composeHydrodynamics(Iterable<PhysicsProfile> profiles) {
        float totalMass = 0.0f;
        float totalDisplacedVolume = 0.0f;
        float densityWeightedMass = 0.0f;
        float volumeWeightedBuoyancyAssist = 0.0f;

        for (PhysicsProfile profile : profiles) {
            float displacedVolume = Math.max(0.0001f, profile.displacedVolume());
            totalMass += profile.mass();
            totalDisplacedVolume += displacedVolume;
            densityWeightedMass += profile.relativeDensity() * displacedVolume;
            volumeWeightedBuoyancyAssist += profile.buoyancyAssist() * displacedVolume;
        }

        if (totalDisplacedVolume <= 0.0f) {
            return new HydroComposition(0.0f, 0.0f, DEFAULT_PROFILE.relativeDensity(), DEFAULT_PROFILE.buoyancyAssist());
        }

        float effectiveRelativeDensity = densityWeightedMass / totalDisplacedVolume;
        float effectiveBuoyancyAssist = volumeWeightedBuoyancyAssist / totalDisplacedVolume;
        return new HydroComposition(totalMass, totalDisplacedVolume, effectiveRelativeDensity, effectiveBuoyancyAssist);
    }

    public static HydroComposition composeHydrodynamicsForBlocks(Iterable<Block> blocks) {
        java.util.ArrayList<PhysicsProfile> profiles = new java.util.ArrayList<>();
        for (Block block : blocks) {
            profiles.add(getProfile(block));
        }
        return composeHydrodynamics(profiles);
    }

    public static PhysicsProfile getDefaultProfile() {
        return DEFAULT_PROFILE;
    }

}
