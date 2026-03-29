package net.shasankp000.Ship.Network;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

/**
 * Sent server -> client every tick for each active ship.
 * Carries the minimal data the client renderer needs to position
 * the ship's block overlay in the overworld.
 *
 * Packet layout (bytes):
 *   UUID   shipId          (16)
 *   double worldOffsetX    (8)
 *   double worldOffsetY    (8)
 *   double worldOffsetZ    (8)
 *   float  yaw             (4)
 *   double velocityX       (8)
 *   double velocityY       (8)
 *   double velocityZ       (8)
 *                        = 68 bytes total
 *
 * send() is guarded by canSend(): tick-rate packets are skipped rather
 * than dropped by the network layer when the channel is not yet ready.
 */
public final class ShipTransformSyncS2CPacket {

    public static final Identifier ID =
        new Identifier("aetherial-skies", "ship_transform_sync");

    private ShipTransformSyncS2CPacket() {}

    public static void send(
            ServerPlayerEntity player,
            UUID shipId,
            Vec3d worldOffset,
            float yaw,
            Vec3d velocity
    ) {
        if (!ServerPlayNetworking.canSend(player, ID)) return;

        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(shipId);
        buf.writeDouble(worldOffset.x);
        buf.writeDouble(worldOffset.y);
        buf.writeDouble(worldOffset.z);
        buf.writeFloat(yaw);
        buf.writeDouble(velocity.x);
        buf.writeDouble(velocity.y);
        buf.writeDouble(velocity.z);
        ServerPlayNetworking.send(player, ID, buf);
    }

    // ---- Client-side decode (called from AetherialSkiesClient) -----------

    public record Payload(
        UUID shipId,
        Vec3d worldOffset,
        float yaw,
        Vec3d velocity
    ) {}

    public static Payload decode(PacketByteBuf buf) {
        UUID id       = buf.readUuid();
        double ox     = buf.readDouble();
        double oy     = buf.readDouble();
        double oz     = buf.readDouble();
        float  yaw    = buf.readFloat();
        double vx     = buf.readDouble();
        double vy     = buf.readDouble();
        double vz     = buf.readDouble();
        return new Payload(id, new Vec3d(ox, oy, oz), yaw, new Vec3d(vx, vy, vz));
    }
}
