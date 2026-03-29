package net.shasankp000.Ship.Dimension;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/**
 * Identifies the dedicated ship_storage dimension where all deployed ship
 * block structures are permanently anchored.
 *
 * Players have no way to enter this dimension. It exists purely as a
 * server-side block-storage backend. All blocks in this dimension are
 * fully lit (ambient_light 1.0) and the dimension runs at fixed noon
 * (fixed_time 6000) so block models are always captured at full brightness.
 */
public final class ShipStorageDimension {

    private ShipStorageDimension() {}

    public static final RegistryKey<World> DIMENSION_KEY = RegistryKey.of(
        RegistryKeys.WORLD,
        new Identifier("aetherial-skies", "ship_storage")
    );

    /** Y level at which all ship structures are stored. */
    public static final int STORAGE_Y = 64;

    public static boolean isShipStorageWorld(RegistryKey<World> key) {
        return DIMENSION_KEY.equals(key);
    }
}
