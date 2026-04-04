package net.shasankp000.Ship.Network;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

/**
 * Client -> Server every tick while the local player is piloting a ship.
 *
 * Layout (bytes):
 *   byte  forward   (-1 = S, 0 = none, +1 = W)   (1)
 *   byte  turn      (-1 = A left, 0 = none, +1 = D right) (1)
 *                                                = 2 bytes
 *
 * We use signed bytes rather than floats so a malicious client cannot
 * inject arbitrary thrust magnitudes; the server always clamps to ±1.
 */
public final class ShipSteerC2SPacket {

    public static final Identifier ID =
        new Identifier("aetherial-skies", "ship_steer");

    private ShipSteerC2SPacket() {}

    // ---- Client: encode + send -------------------------------------------

    public static void send(int forward, int turn) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(forward);
        buf.writeByte(turn);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(ID, buf);
    }

    // ---- Server: decode --------------------------------------------------

    public record Payload(int forward, int turn) {}

    public static Payload decode(PacketByteBuf buf) {
        int forward = buf.readByte();
        int turn    = buf.readByte();
        // clamp so a crafted packet cannot exceed ±1
        forward = Math.max(-1, Math.min(1, forward));
        turn    = Math.max(-1, Math.min(1, turn));
        return new Payload(forward, turn);
    }
}
