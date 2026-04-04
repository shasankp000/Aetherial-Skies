package net.shasankp000.Ship.Network;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * Server -> Client: tells the client that the local player has exited
 * pilot mode.  No payload needed.
 */
public final class ShipPilotExitedS2CPacket {

    public static final Identifier ID =
        new Identifier("aetherial-skies", "ship_pilot_exited");

    private ShipPilotExitedS2CPacket() {}

    public static void send(ServerPlayerEntity player) {
        if (!ServerPlayNetworking.canSend(player, ID)) return;
        PacketByteBuf buf = PacketByteBufs.create();
        ServerPlayNetworking.send(player, ID, buf);
    }

    /** Nothing to decode — zero-byte packet. */
    public static void decode(PacketByteBuf buf) {}
}
