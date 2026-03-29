package net.shasankp000.Ship.Command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.shasankp000.Ship.Structure.ShipStructure;
import net.shasankp000.Ship.Structure.ShipStructureManager;

import java.util.Collection;
import java.util.UUID;

/**
 * Registers the /ship command tree.
 *
 * Subcommands:
 *   /ship list                — lists all active ships with their IDs and positions
 *   /ship remove <id>         — destroys one ship by UUID (supports partial prefix matching)
 *   /ship removeall           — destroys every active ship (nuclear option)
 *   /ship freeze <id>         — pauses physics on one ship
 *   /ship unfreeze <id>       — resumes physics on one ship
 *
 * Requires operator permission level 2 (same as /kill).
 */
public final class ShipCommands {

    private ShipCommands() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            CommandManager.literal("ship")
                .requires(src -> src.hasPermissionLevel(2))

                // /ship list
                .then(CommandManager.literal("list")
                    .executes(ShipCommands::executeList)
                )

                // /ship remove <id>
                .then(CommandManager.literal("remove")
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .executes(ctx -> executeRemove(ctx,
                            StringArgumentType.getString(ctx, "id")))
                    )
                )

                // /ship removeall
                .then(CommandManager.literal("removeall")
                    .executes(ShipCommands::executeRemoveAll)
                )

                // /ship freeze <id>
                .then(CommandManager.literal("freeze")
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .executes(ctx -> executeFreeze(ctx,
                            StringArgumentType.getString(ctx, "id"), true))
                    )
                )

                // /ship unfreeze <id>
                .then(CommandManager.literal("unfreeze")
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .executes(ctx -> executeFreeze(ctx,
                            StringArgumentType.getString(ctx, "id"), false))
                    )
                )
        );
    }

    // ---- /ship list -------------------------------------------------------

    private static int executeList(CommandContext<ServerCommandSource> ctx) {
        Collection<ShipStructure> ships =
            ShipStructureManager.getInstance().getAllShips();

        if (ships.isEmpty()) {
            ctx.getSource().sendFeedback(
                () -> Text.literal("[Ships] No active ships."), false);
            return 0;
        }

        ctx.getSource().sendFeedback(
            () -> Text.literal("[Ships] " + ships.size() + " active ship(s):"), false);

        for (ShipStructure s : ships) {
            String pos = String.format("(%.1f, %.1f, %.1f)",
                s.getTransform().worldOffset().x,
                s.getTransform().worldOffset().y,
                s.getTransform().worldOffset().z);
            String frozen = s.isPhysicsActive() ? "" : " [FROZEN]";
            ctx.getSource().sendFeedback(
                () -> Text.literal("  id=" + s.getShipId() + "  pos=" + pos + frozen),
                false
            );
        }
        return ships.size();
    }

    // ---- /ship remove <id> ------------------------------------------------

    private static int executeRemove(
            CommandContext<ServerCommandSource> ctx, String idArg) {
        ShipStructure target = resolveShip(idArg);
        if (target == null) {
            ctx.getSource().sendError(
                Text.literal("[Ships] No ship found matching '" + idArg + "'."));
            return 0;
        }
        UUID shipId = target.getShipId();
        ShipStructureManager.getInstance().destroy(shipId);
        ctx.getSource().sendFeedback(
            () -> Text.literal("[Ships] Removed ship " + shipId + "."), true);
        return 1;
    }

    // ---- /ship removeall --------------------------------------------------

    private static int executeRemoveAll(CommandContext<ServerCommandSource> ctx) {
        Collection<ShipStructure> ships =
            ShipStructureManager.getInstance().getAllShips();
        int count = ships.size();
        if (count == 0) {
            ctx.getSource().sendFeedback(
                () -> Text.literal("[Ships] No ships to remove."), false);
            return 0;
        }
        // Copy IDs first — destroy() mutates the backing map.
        ships.stream()
             .map(ShipStructure::getShipId)
             .toList()
             .forEach(id -> ShipStructureManager.getInstance().destroy(id));
        ctx.getSource().sendFeedback(
            () -> Text.literal("[Ships] Removed all " + count + " ship(s)."), true);
        return count;
    }

    // ---- /ship freeze|unfreeze <id> ---------------------------------------

    private static int executeFreeze(
            CommandContext<ServerCommandSource> ctx, String idArg, boolean freeze) {
        ShipStructure target = resolveShip(idArg);
        if (target == null) {
            ctx.getSource().sendError(
                Text.literal("[Ships] No ship found matching '" + idArg + "'."));
            return 0;
        }
        target.setPhysicsActive(!freeze);
        String verb = freeze ? "Frozen" : "Unfrozen";
        ctx.getSource().sendFeedback(
            () -> Text.literal("[Ships] " + verb + " ship " + target.getShipId() + "."),
            true
        );
        return 1;
    }

    // ---- Helpers ----------------------------------------------------------

    /**
     * Resolves a ship by full UUID string or a unique prefix of its UUID.
     * e.g. "0d4a" matches "0d4a209a-..." if only one ship starts with that prefix.
     */
    private static ShipStructure resolveShip(String idArg) {
        // Try exact UUID match first.
        try {
            UUID exact = UUID.fromString(idArg);
            return ShipStructureManager.getInstance().getShip(exact);
        } catch (IllegalArgumentException ignored) {}

        // Fall back to prefix match.
        ShipStructure match = null;
        for (ShipStructure s : ShipStructureManager.getInstance().getAllShips()) {
            if (s.getShipId().toString().startsWith(idArg)) {
                if (match != null) return null; // ambiguous
                match = s;
            }
        }
        return match;
    }
}
