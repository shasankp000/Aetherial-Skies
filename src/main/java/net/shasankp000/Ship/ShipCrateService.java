package net.shasankp000.Ship;

import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Gravity.GravityData;
import net.shasankp000.Registry.ModItems;

import java.util.List;
import java.util.UUID;

public final class ShipCrateService {
    private static final String SHIP_CRATE_TAG = "ShipCrateData";

    private ShipCrateService() {
    }

    public static PackResult createPackedCrate(ServerPlayerEntity player, List<PackedBlock> blocks, float mass, Vec3d helmOffset, float helmYawDegrees, GravityData.HydroComposition hydro) {
        if (blocks.isEmpty()) {
            return new PackResult(false, "Nothing to pack into a crate.");
        }

        ItemStack crate = new ItemStack(ModItems.SHIP_CRATE);
        ShipHullData hullData = new ShipHullData(
                UUID.randomUUID(),
                mass,
                helmOffset,
                helmYawDegrees,
                hydro.effectiveRelativeDensity(),
                hydro.buoyancyAssist(),
                hydro.totalDisplacedVolume(),
                List.copyOf(blocks)
        );

        crate.getOrCreateNbt().put(SHIP_CRATE_TAG, hullData.toCrateTag());
        crate.setCustomName(Text.literal("Packed Ship (" + blocks.size() + " blocks)"));

        if (!player.getInventory().insertStack(crate)) {
            player.dropItem(crate, false);
        }
        return new PackResult(true, "Ship packed into crate.");
    }

    public record PackedBlock(String blockId, Vec3d localOffset, boolean isHelm) {
    }

    public record PackResult(boolean success, String message) {
    }
}
