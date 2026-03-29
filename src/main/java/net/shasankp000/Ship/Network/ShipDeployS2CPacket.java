package net.shasankp000.Ship.Network;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Ship.ShipCrateService;
import net.shasankp000.Ship.ShipHullData;

import java.util.UUID;

/**
 * Sent server -> client once when a ship is deployed.
 * Carries the full hull data (block list + transform) so the client
 * can populate its local ShipTransformCache and begin rendering.
 *
 * Packet layout:
 *   UUID   shipId
 *   double structureOriginX/Y/Z
 *   double worldOffsetX/Y/Z
 *   float  yaw
 *   int    blockCount
 *   for each block:
 *     String  blockId
 *     double  localOffsetX/Y/Z
 *     boolean isHelm
 *
 * send() is guarded by ServerPlayNetworking.canSend(): if the client has
 * not yet completed the channel registration handshake the packet is
 * skipped rather than silently dropped by the network layer.
 * The JOIN listener in AetherialSkies replays this packet for every
 * active ship once a player's connection is fully ready.
 */
public final class ShipDeployS2CPacket {

    public static final Identifier ID =
        new Identifier("aetherial-skies", "ship_deploy");

    private ShipDeployS2CPacket() {}

    public static void send(
            ServerPlayerEntity player,
            UUID shipId,
            Vec3d structureOrigin,
            Vec3d worldOffset,
            float yaw,
            ShipHullData hullData
    ) {
        // Guard: only send if the client has acknowledged this channel.
        if (!ServerPlayNetworking.canSend(player, ID)) {
            return;
        }

        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(shipId);
        buf.writeDouble(structureOrigin.x);
        buf.writeDouble(structureOrigin.y);
        buf.writeDouble(structureOrigin.z);
        buf.writeDouble(worldOffset.x);
        buf.writeDouble(worldOffset.y);
        buf.writeDouble(worldOffset.z);
        buf.writeFloat(yaw);

        buf.writeInt(hullData.blocks().size());
        for (ShipCrateService.PackedBlock pb : hullData.blocks()) {
            buf.writeString(pb.blockId());
            buf.writeDouble(pb.localOffset().x);
            buf.writeDouble(pb.localOffset().y);
            buf.writeDouble(pb.localOffset().z);
            buf.writeBoolean(pb.isHelm());
        }
        ServerPlayNetworking.send(player, ID, buf);
    }

    // ---- Client-side decode (called from AetherialSkiesClient) -----------

    public record Payload(
        UUID shipId,
        Vec3d structureOrigin,
        Vec3d worldOffset,
        float yaw,
        java.util.List<ShipCrateService.PackedBlock> blocks
    ) {}

    public static Payload decode(PacketByteBuf buf) {
        UUID id  = buf.readUuid();
        double sox = buf.readDouble(), soy = buf.readDouble(), soz = buf.readDouble();
        double wox = buf.readDouble(), woy = buf.readDouble(), woz = buf.readDouble();
        float yaw  = buf.readFloat();
        int count  = buf.readInt();
        java.util.List<ShipCrateService.PackedBlock> blocks = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String blockId = buf.readString();
            double lx = buf.readDouble(), ly = buf.readDouble(), lz = buf.readDouble();
            boolean isHelm = buf.readBoolean();
            blocks.add(new ShipCrateService.PackedBlock(blockId, new Vec3d(lx, ly, lz), isHelm));
        }
        return new Payload(id, new Vec3d(sox, soy, soz), new Vec3d(wox, woy, woz), yaw, blocks);
    }
}
