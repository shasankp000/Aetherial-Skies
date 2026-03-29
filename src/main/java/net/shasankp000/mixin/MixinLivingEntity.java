package net.shasankp000.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
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
 * Horizontal coverage uses a local-space AABB test:
 *   1. Translate the player's world XZ by the ship's worldOffset.
 *   2. Inverse-rotate by the ship's yaw to get the player position in
 *      the ship's local coordinate frame.
 *   3. Check against the local hull AABB (min/max X/Z across all blocks).
 *
 * This is rotation-agnostic: no integer rounding gaps, full deck coverage
 * at any yaw.
 */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity {

    private static final double SNAP_TOLERANCE = 1.1D;
    private static final double MAX_ABOVE      = 0.55D;
    /** Small margin added to hull AABB edges so the player doesn't slip off the very edge. */
    private static final double HULL_MARGIN    = 0.35D;

    @Inject(method = "travel", at = @At("HEAD"))
    private void onTravelHead(Vec3d movementInput, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;

        if (!(self instanceof PlayerEntity player)) return;
        if (player.isSpectator()) return;
        if (player.getWorld().isClient()) return;

        double bestFloorY = findBestFloorY(player.getX(), player.getZ(), player.getY());
        if (Double.isNaN(bestFloorY)) return;

        boolean falling = player.getVelocity().y <= 0.001D;
        if (!falling) return;

        double feetY = player.getY();
        if (feetY < bestFloorY) {
            player.setPosition(player.getX(), bestFloorY, player.getZ());
            Vec3d vel = player.getVelocity();
            player.setVelocity(vel.x, 0.0D, vel.z);
            player.setOnGround(true);
        } else if (feetY <= bestFloorY + MAX_ABOVE) {
            player.setPosition(player.getX(), bestFloorY, player.getZ());
            Vec3d vel = player.getVelocity();
            if (vel.y < 0) player.setVelocity(vel.x, 0.0D, vel.z);
            player.setOnGround(true);
        }
    }

    static double findBestFloorY(double px, double pz, double feetY) {
        double best = Double.NaN;
        for (ShipTransformCache.ClientShip ship : ShipTransformCache.INSTANCE.getAll()) {
            ShipTransform t = ship.transform;
            if (t == null || ship.blocks == null || ship.blocks.isEmpty()) continue;

            double deckTopY = computeDeckTopY(ship, t);

            if (deckTopY > feetY + MAX_ABOVE) continue;
            if (deckTopY < feetY - SNAP_TOLERANCE) continue;

            if (isPlayerAboveShipLocal(px, pz, ship, t)) {
                if (Double.isNaN(best) || deckTopY > best) {
                    best = deckTopY;
                }
            }
        }
        return best;
    }

    static double computeDeckTopY(ShipTransformCache.ClientShip ship, ShipTransform t) {
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

    /**
     * Checks whether the world-space XZ point (px, pz) lies within
     * the ship's hull footprint, accounting for the ship's current yaw.
     *
     * Steps:
     *   1. Translate: move origin to ship's worldOffset centre.
     *   2. Inverse-rotate by yaw (undo the ship's rotation).
     *   3. Compare against the local-space AABB built from block offsets.
     */
    static boolean isPlayerAboveShipLocal(double px, double pz,
            ShipTransformCache.ClientShip ship, ShipTransform t) {

        // 1. Translate player into ship-centred coordinates
        double dx = px - t.worldOffset().x;
        double dz = pz - t.worldOffset().z;

        // 2. Inverse-rotate by ship yaw (renderer uses -yaw, so inverse is +yaw)
        double rad = Math.toRadians(t.yaw());
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double localX = dx * cos - dz * sin;
        double localZ = dx * sin + dz * cos;

        // 3. Build local AABB from block offsets and test
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (ShipCrateService.PackedBlock pb : ship.blocks) {
            Vec3d lo = pb.localOffset();
            double bx = lo.x - 0.5D;
            double bz = lo.z - 0.5D;
            if (bx < minX) minX = bx;
            if (bx + 1.0D > maxX) maxX = bx + 1.0D;
            if (bz < minZ) minZ = bz;
            if (bz + 1.0D > maxZ) maxZ = bz + 1.0D;
        }

        return localX >= minX - HULL_MARGIN && localX <= maxX + HULL_MARGIN
            && localZ >= minZ - HULL_MARGIN && localZ <= maxZ + HULL_MARGIN;
    }
}
