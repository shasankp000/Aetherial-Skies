package net.shasankp000.Ship.Item;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.shasankp000.Ship.ShipDeployService;

public class ShipCrateItem extends Item {
    public ShipCrateItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        if (context.getWorld().isClient()) {
            return ActionResult.SUCCESS;
        }
        if (!(context.getWorld() instanceof ServerWorld serverWorld) || !(context.getPlayer() instanceof ServerPlayerEntity player)) {
            return ActionResult.PASS;
        }

        ItemStack stack = context.getStack();
        ShipDeployService.DeployResult result = ShipDeployService.deployFromCrate(player, serverWorld, context.getBlockPos(), stack);
        player.sendMessage(Text.literal(result.message()), true);
        return result.success() ? ActionResult.SUCCESS : ActionResult.FAIL;
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, net.minecraft.entity.player.PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (world.isClient()) {
            return TypedActionResult.success(stack, true);
        }
        if (!(world instanceof ServerWorld serverWorld) || !(user instanceof ServerPlayerEntity player)) {
            return TypedActionResult.pass(stack);
        }

        BlockHitResult hitResult = raycast(world, user, RaycastContext.FluidHandling.SOURCE_ONLY);
        BlockPos targetPos = hitResult.getType() == HitResult.Type.BLOCK ? hitResult.getBlockPos() : user.getBlockPos();
        ShipDeployService.DeployResult result = ShipDeployService.deployFromCrate(player, serverWorld, targetPos, stack);
        player.sendMessage(Text.literal(result.message()), true);
        return result.success() ? TypedActionResult.success(stack, false) : TypedActionResult.fail(stack);
    }
}
