package net.shasankp000.Ship.Command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.shasankp000.Ship.Structure.ShipStructure;
import net.shasankp000.Ship.Structure.ShipStructureManager;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Registers the /ship command tree.
 *
 *   /ship list
 *   /ship remove <id>     — tab-completes with live ship IDs
 *   /ship removeall
 *   /ship freeze <id>
 *   /ship unfreeze <id>
 *
 * Requires op level 2 (same as /kill).
 */
public final class ShipCommands {

    private ShipCommands() {}

    // ---- Suggestion provider: live ship IDs ------------------------------

    private static final SuggestionProvider<ServerCommandSource> SHIP_ID_SUGGESTIONS =
        (ctx, builder) -> suggestShipIds(builder);

    private static CompletableFuture<Suggestions> suggestShipIds(SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase();
        for (ShipStructure s : ShipStructureManager.getInstance().getAllShips()) {
            String id = s.getShipId().toString();
            // Show short 8-char prefix as display but insert full UUID as value.
            String shortId = id.substring(0, 8);
            String label   = shortId + "  pos=(" + String.format("%.0f,%.0f,%.0f",
                s.getTransform().worldOffset().x,
                s.getTransform().worldOffset().y,
                s.getTransform().worldOffset().z) + ")";
            if (id.startsWith(remaining) || shortId.startsWith(remaining)) {
                builder.suggest(id, Text.literal(label));
            }
        }
        return builder.buildFuture();
    }

    // ---- Command registration --------------------------------------------

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            CommandManager.literal("ship")
                .requires(src -> src.hasPermissionLevel(2))

                .then(CommandManager.literal("list")
                    .executes(ShipCommands::executeList)
                )

                .then(CommandManager.literal("remove")
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(SHIP_ID_SUGGESTIONS)
                        .executes(ctx -> executeRemove(ctx,
                            StringArgumentType.getString(ctx, "id")))
                    )
                )

                .then(CommandManager.literal("removeall")
                    .executes(ShipCommands::executeRemoveAll)
                )

                .then(CommandManager.literal("freeze")
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(SHIP_ID_SUGGESTIONS)
                        .executes(ctx -> executeFreeze(ctx,
                            StringArgumentType.getString(ctx, "id"), true))
                    )
                )

                .then(CommandManager.literal("unfreeze")
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(SHIP_ID_SUGGESTIONS)
                        .executes(ctx -> executeFreeze(ctx,
                            StringArgumentType.getString(ctx, "id"), false))
                    )
                )
        );
    }

    // ---- Executors -------------------------------------------------------

    private static int executeList(CommandContext<ServerCommandSource> ctx) {
        Collection<ShipStructure> ships = ShipStructureManager.getInstance().getAllShips();
        if (ships.isEmpty()) {
            ctx.getSource().sendFeedback(() -> Text.literal("[Ships] No active ships."), false);
            return 0;
        }
        ctx.getSource().sendFeedback(
            () -> Text.literal("[Ships] " + ships.size() + " active ship(s):"), false);
        for (ShipStructure s : ships) {
            String line = "  " + s.getShipId() + "  pos=(" +
                String.format("%.1f, %.1f, %.1f",
                    s.getTransform().worldOffset().x,
                    s.getTransform().worldOffset().y,
                    s.getTransform().worldOffset().z) + ")" +
                (s.isPhysicsActive() ? "" : " [FROZEN]");
            ctx.getSource().sendFeedback(() -> Text.literal(line), false);
        }
        return ships.size();
    }

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

    private static int executeRemoveAll(CommandContext<ServerCommandSource> ctx) {
        Collection<ShipStructure> ships = ShipStructureManager.getInstance().getAllShips();
        int count = ships.size();
        if (count == 0) {
            ctx.getSource().sendFeedback(
                () -> Text.literal("[Ships] No ships to remove."), false);
            return 0;
        }
        ships.stream().map(ShipStructure::getShipId).toList()
             .forEach(id -> ShipStructureManager.getInstance().destroy(id));
        ctx.getSource().sendFeedback(
            () -> Text.literal("[Ships] Removed all " + count + " ship(s)."), true);
        return count;
    }

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
            () -> Text.literal("[Ships] " + verb + " ship " + target.getShipId() + "."), true);
        return 1;
    }

    // ---- Helpers ---------------------------------------------------------

    /** Full UUID or unique prefix match. */
    private static ShipStructure resolveShip(String idArg) {
        try {
            UUID exact = UUID.fromString(idArg);
            return ShipStructureManager.getInstance().getShip(exact);
        } catch (IllegalArgumentException ignored) {}

        ShipStructure match = null;
        for (ShipStructure s : ShipStructureManager.getInstance().getAllShips()) {
            if (s.getShipId().toString().startsWith(idArg)) {
                if (match != null) return null;
                match = s;
            }
        }
        return match;
    }
}
