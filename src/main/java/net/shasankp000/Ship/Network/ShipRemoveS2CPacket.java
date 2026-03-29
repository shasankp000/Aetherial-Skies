package net.shasankp000.Ship.Network;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.UUID;

/**
 * Sent server → client when a ship is destroyed / unloaded.
 * Tells the client to evict the ship from ShipTransformCache.
 */
public final class ShipRemoveS2CPacket {

    public static final Identifier ID =
        new Identifier("aetherial-skies", "ship_remove");

    private ShipRemoveS2CPacket() {}

    public static void send(ServerPlayerEntity player, UUID shipId) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(shipId);
        ServerPlayNetworking.send(player, ID, buf);
    }

    public static UUID decode(PacketByteBuf buf) {
        return buf.readUuid();
    }
}
