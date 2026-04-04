package net.shasankp000.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.shasankp000.AetherialSkiesClient;
import net.shasankp000.Entity.GravityBlockEntity;
import net.shasankp000.Ship.Client.ShipTransformCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    @Shadow public net.minecraft.client.option.GameOptions options;
    @Shadow public net.minecraft.client.network.ClientPlayerEntity player;
    @Shadow public net.minecraft.client.network.ClientPlayerInteractionManager interactionManager;
    @Shadow public net.minecraft.util.hit.HitResult crosshairTarget;
    @Shadow public net.minecraft.client.gui.screen.Screen currentScreen;

    @Unique private boolean wasHoldingGravityMine = false;
    @Unique private int gravityMineHoldIntervalTicks = 0;

    // ---- Existing: hold-to-mine gravity entity ---------------------------

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

        if (this.wasHoldingGravityMine && this.gravityMineHoldIntervalTicks-- <= 0) {
            this.interactionManager.attackEntity(this.player, target);
            this.player.swingHand(Hand.MAIN_HAND);
            this.gravityMineHoldIntervalTicks = 1;
        }
        this.wasHoldingGravityMine = true;
    }

    // ---- Pilot: redirect mouse X delta to ship turn ----------------------
    //
    // When piloting, mouse left/right should rotate the ship (server-side via
    // the turn field in ShipSteerC2SPacket, driven by A/D keys in
    // AetherialSkiesClient) rather than snapping the player head.
    //
    // The cleanest approach for camera-yaw lock is:  the ClientTickEvents
    // handler in AetherialSkiesClient already re-sets player.setYaw() every
    // tick to the server-authoritative ship yaw.  That means any yaw delta
    // applied by vanilla's mouse look will be overwritten one tick later,
    // which gives a one-frame lag flicker.  We suppress it completely here by
    // zeroing the cursorDeltaX argument passed into updateCameraAndRender when
    // the player is piloting.
    //
    // method = "method_1507" is the obfuscated name for updateMouse / moveMouse.
    // We target the call to Entity#changeLookDirection inside it and zero the
    // first double argument (deltaX / yaw delta) while piloting.
    //
    // NOTE: We only zero the yaw (horizontal) delta, not the pitch, so the
    // player can still look up/down freely at the ship while piloting.
    @ModifyVariable(
        method = "method_1507",  // Mouse::onCursorPos callback -> changeLookDirection
        at = @At("HEAD"),
        ordinal = 0,
        argsOnly = true
    )
    private double suppressYawDeltaWhilePiloting(double deltaX) {
        if (AetherialSkiesClient.pilotingShipId != null && this.player != null) {
            return 0.0;
        }
        return deltaX;
    }
}
