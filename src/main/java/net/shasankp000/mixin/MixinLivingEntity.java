package net.shasankp000.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
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
 * Server-side deck-snap mixin.
 *
 * Injects at HEAD of LivingEntity#travel so that BEFORE vanilla physics
 * runs for this tick, we snap the player flush onto any ship deck they
 * are standing on or have just fallen through.
 *
 * This is the VS2 EntityDragger pattern: skip the block-collision pipeline
 * and directly correct the player's Y before Entity#move sees anything wrong.
 *
 * We use the ShipTransformCache + ShipCollisionProvider data (same source as
 * the renderer and ChunkCache mixin) to find the deck top Y for each ship.
 */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity {

    private static final double SNAP_TOLERANCE = 1.1D;
    private static final double MAX_ABOVE      = 0.55D;

    @Inject(method = "travel", at = @At("HEAD"))
    private void onTravelHead(Vec3d movementInput, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;

        if (!(self instanceof PlayerEntity player)) return;
        if (player.isSpectator()) return;
        // Server side only; client handled in MixinClientPlayerEntity
        if (player.getWorld().isClient()) return;

        double bestFloorY = findBestFloorY(player);
        if (Double.isNaN(bestFloorY)) return;

        boolean falling = player.getVelocity().y <= 0.001D;
        if (!falling) return;

        double feetY = player.getY();
        if (feetY < bestFloorY) {
            // Fell through: push back up
            player.setPosition(player.getX(), bestFloorY, player.getZ());
            Vec3d vel = player.getVelocity();
            player.setVelocity(vel.x, 0.0D, vel.z);
            player.setOnGround(true);
        } else if (feetY <= bestFloorY + MAX_ABOVE) {
            // Resting on or fractionally above deck: snap flush
            player.setPosition(player.getX(), bestFloorY, player.getZ());
            Vec3d vel = player.getVelocity();
            if (vel.y < 0) player.setVelocity(vel.x, 0.0D, vel.z);
            player.setOnGround(true);
        }
    }

    /**
     * Iterates all cached ships and finds the highest deck-top Y that the
     * player is horizontally above and within the vertical snap window.
     *
     * Uses ShipTransformCache (same data source as ChunkCache mixin and
     * renderer) so we are consistent with what the player sees.
     */
    private static double findBestFloorY(PlayerEntity player) {
        double px = player.getX();
        double pz = player.getZ();
        double feetY = player.getY();

        double best = Double.NaN;
        for (ShipTransformCache.ClientShip ship : ShipTransformCache.INSTANCE.getAll()) {
            ShipTransform t = ship.transform;
            if (t == null || ship.blocks == null || ship.blocks.isEmpty()) continue;

            // Compute deck top Y for this ship (same logic as ShipPassengerTracker)
            double deckTopY = computeDeckTopY(ship, t);

            // Vertical snap window check
            if (deckTopY > feetY + MAX_ABOVE) continue;
            if (deckTopY < feetY - SNAP_TOLERANCE) continue;

            // Horizontal footprint check: is player above any block of this ship?
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
            // Player's XZ must be within the 1x1 block column of this block
            if (px >= bp.getX() && px <= bp.getX() + 1.0D
             && pz >= bp.getZ() && pz <= bp.getZ() + 1.0D) {
                return true;
            }
        }
        return false;
    }
}
