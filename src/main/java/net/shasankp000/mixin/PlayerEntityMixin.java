package net.shasankp000.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.shasankp000.Entity.GravityBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {

    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void redirectGravityBlockAttacksToMining(Entity target, CallbackInfo ci) {
        if (!(target instanceof GravityBlockEntity gravityBlock)) {
            return;
        }

        PlayerEntity player = (PlayerEntity) (Object) this;

        // Prevent vanilla entity-combat attack sounds/logic; we treat this as mining interaction instead.
        if (player.getWorld() instanceof ServerWorld serverWorld) {
            float attackDamage = (float) player.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE);
            DamageSource source = player.getDamageSources().playerAttack(player);
            gravityBlock.damage(source, attackDamage);
        }

        ci.cancel();
    }
}
