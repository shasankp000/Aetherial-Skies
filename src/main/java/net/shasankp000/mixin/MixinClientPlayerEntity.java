package net.shasankp000.mixin;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Ship.Client.ShipCollisionProvider;
import net.shasankp000.Ship.Client.ShipTransformCache;
import net.shasankp000.Ship.ShipCrateService;
import net.shasankp000.Ship.Transform.ShipTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client-side mirror of the deck-snap logic.
 *
 * Without this, the following bad sequence happens every tick:
 *   1. Client travel() runs -> player falls through the deck visually.
 *   2. Server corrects Y via MixinLivingEntity / ShipPassengerTracker.
 *   3. Client receives the correction and rubber-bands backward.
 *
 * By running the same snap here at HEAD of tick(), the client and server
 * always agree on the player's Y and there is no visual rubber-band.
 *
 * Uses the same ShipTransformCache data as the renderer and ChunkCache mixin.
 */
@Environment(EnvType.CLIENT)
@Mixin(ClientPlayerEntity.class)
public abstract class MixinClientPlayerEntity {

    private static final double SNAP_TOLERANCE = 1.1D;
    private static final double MAX_ABOVE      = 0.55D;

    @Inject(method = "tick", at = @At("HEAD"))
    private void onClientTickHead(CallbackInfo ci) {
        ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
        if (self.isSpectator()) return;

        double bestFloorY = findBestFloorY(self);
        if (Double.isNaN(bestFloorY)) return;

        boolean falling = self.getVelocity().y <= 0.001D;
        if (!falling) return;

        double feetY = self.getY();
        if (feetY < bestFloorY || (feetY >= bestFloorY && feetY <= bestFloorY + MAX_ABOVE)) {
            self.setPosition(self.getX(), bestFloorY, self.getZ());
            Vec3d vel = self.getVelocity();
            if (vel.y < 0) self.setVelocity(vel.x, 0.0D, vel.z);
            self.setOnGround(true);
        }
    }

    private static double findBestFloorY(ClientPlayerEntity player) {
        double px = player.getX();
        double pz = player.getZ();
        double feetY = player.getY();

        double best = Double.NaN;
        for (ShipTransformCache.ClientShip ship : ShipTransformCache.INSTANCE.getAll()) {
            ShipTransform t = ship.transform;
            if (t == null || ship.blocks == null || ship.blocks.isEmpty()) continue;

            double deckTopY = computeDeckTopY(ship, t);

            if (deckTopY > feetY + MAX_ABOVE) continue;
            if (deckTopY < feetY - SNAP_TOLERANCE) continue;

            if (isPlayerAboveShip(px, pz, ship, t)) {
                if (Double.isNaN(best) || deckTopY > best) {
                    best = deckTopY;
                }
            }
        }
        return best;
    }

    private static double computeDeckTopY(ShipTransformCache.ClientShip ship, ShipTransform t) {
        java.util.HashMap<Integer, Integer> layerCount = new java.util.HashMap<>();
        for (ShipCrateService.PackedBlock pb : ship.blocks) {
            int iy = (int) Math.round(pb.localOffset().y);
            layerCount.merge(iy, 1, Integer::sum);
        }
        int deckLocalY = 0;
        int best = -1;
        for (java.util.Map.Entry<Integer, Integer> e : layerCount.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                deckLocalY = e.getKey();
            }
        }
        return t.worldOffset().y + deckLocalY + 1.0D;
    }

    private static boolean isPlayerAboveShip(double px, double pz,
            ShipTransformCache.ClientShip ship, ShipTransform t) {
        for (ShipCrateService.PackedBlock pb : ship.blocks) {
            net.minecraft.util.math.BlockPos bp = ShipCollisionProvider.worldPosOf(t, pb);
            if (px >= bp.getX() && px <= bp.getX() + 1.0D
             && pz >= bp.getZ() && pz <= bp.getZ() + 1.0D) {
                return true;
            }
        }
        return false;
    }
}
