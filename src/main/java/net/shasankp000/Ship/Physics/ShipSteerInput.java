package net.shasankp000.Ship.Physics;

/**
 * Immutable snapshot of one tick's steering input for a ship.
 * forward: -1 (S/back), 0 (no thrust), +1 (W/forward)
 * turn:    -1 (A/left), 0 (no turn),   +1 (D/right)
 */
public record ShipSteerInput(int forward, int turn) {
    public static final ShipSteerInput NONE = new ShipSteerInput(0, 0);
}
