package net.shasankp000.Ship.Network;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.UUID;

/**
 * Server -> Client: tells the client that the local player has entered
 * pilot mode for the given ship.  The client stores the shipId so it can
 * read yaw from ShipTransformCache and keep the camera synced.
 */
public final class ShipPilotEnteredS2CPacket {

    public static final Identifier ID =
        new Identifier("aetherial-skies", "ship_pilot_entered");

    private ShipPilotEnteredS2CPacket() {}

    public static void send(ServerPlayerEntity player, UUID shipId) {
        if (!ServerPlayNetworking.canSend(player, ID)) return;
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(shipId);
        ServerPlayNetworking.send(player, ID, buf);
    }

    public static UUID decode(PacketByteBuf buf) {
        return buf.readUuid();
    }
}
