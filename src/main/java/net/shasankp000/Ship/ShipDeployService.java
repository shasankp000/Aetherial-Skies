package net.shasankp000.Ship;

import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Entity.ShipBoatEntity;

public final class ShipDeployService {
    private static final String SHIP_CRATE_TAG = "ShipCrateData";

    private ShipDeployService() {
    }

    public static DeployResult deployFromCrate(ServerPlayerEntity player, ServerWorld world, BlockPos clickedPos, ItemStack stack) {
        NbtCompound stackNbt = stack.getNbt();
        if (stackNbt == null || !stackNbt.contains(SHIP_CRATE_TAG, 10)) {
            return new DeployResult(false, "This crate is empty.");
        }

        NbtCompound shipTag = stackNbt.getCompound(SHIP_CRATE_TAG);
        ShipHullData hullData = ShipHullData.fromCrateTag(shipTag);
        if (hullData.blocks().isEmpty()) {
            return new DeployResult(false, "This crate contains no ship blocks.");
        }

        if (hullData.blocks().stream().noneMatch(ShipCrateService.PackedBlock::isHelm)) {
            return new DeployResult(false, "Invalid crate: ship helm is missing.");
        }

        Vec3d spawnPos = findSpawnPosition(world, clickedPos);
        if (spawnPos == null) {
            return new DeployResult(false, "Deployment blocked: no water/air clearance for ship spawn.");
        }

        ShipBoatEntity shipBoat = new ShipBoatEntity(world, spawnPos.x, spawnPos.y, spawnPos.z);
        shipBoat.setHullData(hullData);
        float shipYaw = player.getYaw() - hullData.helmYawDegrees();
        shipBoat.setYaw(shipYaw);
        shipBoat.setBodyYaw(shipYaw);
        shipBoat.setHeadYaw(shipYaw);
        shipBoat.setVariant(net.minecraft.entity.vehicle.BoatEntity.Type.OAK);

        if (!world.spawnEntity(shipBoat)) {
            return new DeployResult(false, "Failed to spawn ship.");
        }

        if (!player.isCreative()) {
            stack.decrement(1);
        }
        return new DeployResult(true, "Ship deployed. Right-click the ship to ride.");
    }

    private static Vec3d findSpawnPosition(ServerWorld world, BlockPos clickedPos) {
        int baseX = clickedPos.getX();
        int baseZ = clickedPos.getZ();
        int worldTop = world.getTopY() - 1;
        int topY = MathHelper.clamp(clickedPos.getY() + 3, world.getBottomY() + 2, worldTop - 2);
        int bottomY = MathHelper.clamp(clickedPos.getY() - 6, world.getBottomY() + 1, worldTop - 4);

        for (int y = topY; y >= bottomY; y--) {
            BlockPos waterPos = new BlockPos(baseX, y, baseZ);
            BlockPos bodyPos = waterPos.up();
            BlockPos headPos = waterPos.up(2);
            BlockState waterState = world.getBlockState(waterPos);
            if (!waterState.getFluidState().isStill()) {
                continue;
            }
            if (!world.getBlockState(bodyPos).isAir() || !world.getBlockState(headPos).isAir()) {
                continue;
            }
            return new Vec3d(baseX + 0.5D, y + 1.05D, baseZ + 0.5D);
        }

        return null;
    }

    public record DeployResult(boolean success, String message) {
    }
}
