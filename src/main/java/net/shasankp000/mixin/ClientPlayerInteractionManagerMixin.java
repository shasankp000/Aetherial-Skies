package net.shasankp000.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.shasankp000.Entity.GravityBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerMixin {

    @Inject(
            method = "attackEntity",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/player/PlayerEntity;attack(Lnet/minecraft/entity/Entity;)V"
            ),
            cancellable = true
    )
    private void skipClientCombatAttackForGravityBlocks(PlayerEntity player, Entity target, CallbackInfo ci) {
        if (target instanceof GravityBlockEntity) {
            // Packet is already sent at this point; cancel local combat attack to avoid entity-hit sounds.
            ci.cancel();
        }
    }
}

