package net.shasankp000.Ship.Item;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.shasankp000.Ship.ShipHullData;
import net.shasankp000.Ship.Structure.ShipStructure;
import net.shasankp000.Ship.Structure.ShipStructureManager;

public class ShipCrateItem extends Item {

    private static final String SHIP_CRATE_TAG = "ShipCrateData";

    public ShipCrateItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        if (context.getWorld().isClient()) return ActionResult.SUCCESS;
        if (!(context.getWorld() instanceof ServerWorld sw)
                || !(context.getPlayer() instanceof ServerPlayerEntity player)) {
            return ActionResult.PASS;
        }
        DeployResult result = deploy(player, sw, context.getBlockPos(), context.getStack());
        player.sendMessage(Text.literal(result.message()), true);
        return result.success() ? ActionResult.SUCCESS : ActionResult.FAIL;
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, net.minecraft.entity.player.PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (world.isClient()) return TypedActionResult.success(stack, true);
        if (!(world instanceof ServerWorld sw) || !(user instanceof ServerPlayerEntity player)) {
            return TypedActionResult.pass(stack);
        }
        BlockHitResult hit = raycast(world, user, RaycastContext.FluidHandling.SOURCE_ONLY);
        BlockPos target = hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : user.getBlockPos();
        DeployResult result = deploy(player, sw, target, stack);
        player.sendMessage(Text.literal(result.message()), true);
        return result.success() ? TypedActionResult.success(stack, false) : TypedActionResult.fail(stack);
    }

    // -------------------------------------------------------------------------

    private static DeployResult deploy(
            ServerPlayerEntity player,
            ServerWorld world,
            BlockPos clickedPos,
            ItemStack stack
    ) {
        NbtCompound nbt = stack.getNbt();
        if (nbt == null || !nbt.contains(SHIP_CRATE_TAG, 10)) {
            return new DeployResult(false, "This crate is empty.");
        }

        ShipHullData hullData = ShipHullData.fromCrateTag(nbt.getCompound(SHIP_CRATE_TAG));
        if (hullData.blocks().isEmpty()) {
            return new DeployResult(false, "This crate contains no ship blocks.");
        }
        if (hullData.blocks().stream().noneMatch(net.shasankp000.Ship.ShipCrateService.PackedBlock::isHelm)) {
            return new DeployResult(false, "Invalid crate: ship helm is missing.");
        }

        Vec3d spawnPos = findWaterSurface(world, clickedPos);
        if (spawnPos == null) {
            return new DeployResult(false, "Deployment blocked: no water/air clearance near that position.");
        }

        float yaw = player.getYaw() - hullData.helmYawDegrees();

        try {
            ShipStructure ship = ShipStructureManager.getInstance().deploy(hullData, spawnPos, yaw);
            if (!player.isCreative()) stack.decrement(1);
            return new DeployResult(true,
                "Ship deployed (id: " + ship.getShipId().toString().substring(0, 8) + ").");
        } catch (Exception e) {
            return new DeployResult(false, "Deploy failed: " + e.getMessage());
        }
    }

    /**
     * Finds the water-surface Y at (or near) clickedPos. Returns the Vec3d
     * representing the visual centre of the ship in overworld space — i.e. the
     * point that maps to structureOrigin after the transform is applied.
     *
     * For now this returns the water-surface XZ centre at the first still-water
     * block found scanning downward from clickedPos, identical to the logic in
     * the old ShipDeployService.findSpawnPosition().
     */
    private static Vec3d findWaterSurface(ServerWorld world, BlockPos clickedPos) {
        int bx = clickedPos.getX(), bz = clickedPos.getZ();
        int top = world.getTopY() - 1;
        int startY = MathHelper.clamp(clickedPos.getY() + 3, world.getBottomY() + 2, top - 2);
        int endY   = MathHelper.clamp(clickedPos.getY() - 6, world.getBottomY() + 1, top - 4);

        for (int y = startY; y >= endY; y--) {
            BlockPos waterPos = new BlockPos(bx, y, bz);
            if (!world.getBlockState(waterPos).getFluidState().isStill()) continue;
            if (!world.getBlockState(waterPos.up()).isAir())  continue;
            if (!world.getBlockState(waterPos.up(2)).isAir()) continue;
            // Water surface is at y + 1.0. Use that as the ship's worldOffset.
            return new Vec3d(bx + 0.5, y + 1.0, bz + 0.5);
        }
        return null;
    }

    private record DeployResult(boolean success, String message) {}
}
