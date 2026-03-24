package net.shasankp000.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.shasankp000.Entity.GravityBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    @Shadow public net.minecraft.client.option.GameOptions options;
    @Shadow public net.minecraft.client.network.ClientPlayerEntity player;
    @Shadow public net.minecraft.client.network.ClientPlayerInteractionManager interactionManager;
    @Shadow public net.minecraft.util.hit.HitResult crosshairTarget;
    @Shadow public net.minecraft.client.gui.screen.Screen currentScreen;

    @Unique
    private boolean wasHoldingGravityMine = false;

    @Unique
    private int gravityMineHoldIntervalTicks = 0;

    @Inject(method = "handleInputEvents", at = @At("TAIL"))
    private void holdToMineGravityEntity(CallbackInfo ci) {
        if (this.currentScreen != null || this.player == null || this.interactionManager == null) {
            this.wasHoldingGravityMine = false;
            this.gravityMineHoldIntervalTicks = 0;
            return;
        }

        boolean attackHeld = this.options.attackKey.isPressed();
        if (!attackHeld || this.player.isUsingItem()) {
            this.wasHoldingGravityMine = false;
            this.gravityMineHoldIntervalTicks = 0;
            return;
        }

        if (this.crosshairTarget == null || this.crosshairTarget.getType() != HitResult.Type.ENTITY) {
            this.wasHoldingGravityMine = false;
            this.gravityMineHoldIntervalTicks = 0;
            return;
        }

        Entity target = ((EntityHitResult) this.crosshairTarget).getEntity();
        if (!(target instanceof GravityBlockEntity)) {
            this.wasHoldingGravityMine = false;
            this.gravityMineHoldIntervalTicks = 0;
            return;
        }

        // First click comes from vanilla doAttack(); sustained hold continues mining at a steady cadence.
        if (this.wasHoldingGravityMine && this.gravityMineHoldIntervalTicks-- <= 0) {
            this.interactionManager.attackEntity(this.player, target);
            this.gravityMineHoldIntervalTicks = 1;
        }

        this.wasHoldingGravityMine = true;
    }
}


