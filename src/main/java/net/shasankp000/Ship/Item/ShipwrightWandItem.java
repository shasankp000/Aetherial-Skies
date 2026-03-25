package net.shasankp000.Ship.Item;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.shasankp000.Registry.ModBlocks;
import net.shasankp000.Ship.ShipCompileService;
import net.shasankp000.Ship.ShipSelectionManager;

public class ShipwrightWandItem extends Item {
    public ShipwrightWandItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        World world = context.getWorld();
        PlayerEntity player = context.getPlayer();
        if (player == null) {
            return ActionResult.PASS;
        }

        BlockPos pos = context.getBlockPos();
        if (world.getBlockState(pos).isOf(ModBlocks.SHIPWRIGHTS_WORKBENCH)) {
            if (!world.isClient() && player instanceof ServerPlayerEntity serverPlayer && world instanceof ServerWorld serverWorld) {
                ShipCompileService.CompileResult result = ShipCompileService.compileSelection(serverPlayer, serverWorld);
                if (result.success()) {
                    ShipSelectionManager.clear(player.getUuid());
                }
                serverPlayer.sendMessage(Text.literal(result.message()), false);
            }
            return ActionResult.SUCCESS;
        }

        boolean hasFirstCorner = ShipSelectionManager.getFirstCorner(player.getUuid()).isPresent();
        if (player.isSneaking() || !hasFirstCorner) {
            ShipSelectionManager.setFirstCorner(player.getUuid(), pos);
            ShipSelectionManager.clearSecondCorner(player.getUuid());
            if (!world.isClient()) {
                player.sendMessage(Text.literal("Ship selection start set to " + pos.toShortString() + ". Select the second corner."), true);
            }
            return ActionResult.SUCCESS;
        }

        ShipSelectionManager.setSecondCorner(player.getUuid(), pos);
        if (!world.isClient()) {
            player.sendMessage(Text.literal("Ship selection second corner set to " + pos.toShortString()), true);
        }
        return ActionResult.SUCCESS;
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        if (user.isSneaking()) {
            ShipSelectionManager.clear(user.getUuid());
            if (!world.isClient()) {
                user.sendMessage(Text.literal("Ship selection cleared."), true);
            }
            return TypedActionResult.success(user.getStackInHand(hand), true);
        }
        return super.use(world, user, hand);
    }
}
