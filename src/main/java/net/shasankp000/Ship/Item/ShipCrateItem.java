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
            return new DeployResult(false, "Deployment blocked: no open water surface near that position.");
        }

        float yaw = player.getYaw() - hullData.helmYawDegrees();

        try {
            ShipStructure ship = ShipStructureManager.getInstance().deploy(hullData, spawnPos, yaw);
            if (!player.isCreative()) stack.decrement(1);
            return new DeployResult(true,
                "Ship deployed at y=" + String.format("%.1f", spawnPos.y)
                + " (id: " + ship.getShipId().toString().substring(0, 8) + ").");
        } catch (Exception e) {
            return new DeployResult(false, "Deploy failed: " + e.getMessage());
        }
    }

    /**
     * Finds the TRUE water surface at the clicked XZ column.
     *
     * Strategy:
     *  1. Scan downward from clickedPos+3 to find ANY still-water block.
     *  2. From that block, walk UPWARD to find the topmost contiguous
     *     water block — this is the real ocean/lake surface even if the
     *     player clicked a submerged block deep in the ocean.
     *  3. Require the block directly above the topmost water to be air
     *     (open surface, not ice or a roof).
     *  4. Return (bx+0.5, surfaceY+1.0, bz+0.5) — the top face of the
     *     surface water block, which is where the ship hull bottom sits.
     *
     * This prevents deploying into submerged rock when clicking deep water.
     */
    private static Vec3d findWaterSurface(ServerWorld world, BlockPos clickedPos) {
        int bx = clickedPos.getX();
        int bz = clickedPos.getZ();
        int top    = world.getTopY() - 1;
        int startY = Math.min(clickedPos.getY() + 3, top - 1);
        int endY   = Math.max(clickedPos.getY() - 32, world.getBottomY() + 1);

        // Step 1: find any still-water block scanning downward.
        int firstWaterY = Integer.MIN_VALUE;
        for (int y = startY; y >= endY; y--) {
            if (world.getBlockState(new BlockPos(bx, y, bz)).getFluidState().isStill()) {
                firstWaterY = y;
                break;
            }
        }
        if (firstWaterY == Integer.MIN_VALUE) return null;

        // Step 2: walk upward from firstWaterY to find the topmost
        // contiguous water block (the real surface).
        int surfaceY = firstWaterY;
        for (int y = firstWaterY + 1; y <= Math.min(firstWaterY + 64, top); y++) {
            if (world.getBlockState(new BlockPos(bx, y, bz)).getFluidState().isStill()) {
                surfaceY = y;
            } else {
                break;
            }
        }

        // Step 3: the block above the topmost water must be air.
        if (!world.getBlockState(new BlockPos(bx, surfaceY + 1, bz)).isAir()) return null;

        // Step 4: ship worldOffset = top face of surface water block.
        return new Vec3d(bx + 0.5, surfaceY + 1.0, bz + 0.5);
    }

    private record DeployResult(boolean success, String message) {}
}
