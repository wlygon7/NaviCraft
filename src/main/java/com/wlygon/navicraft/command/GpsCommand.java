package com.wlygon.navicraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.wlygon.navicraft.nav.NavTarget;
import com.wlygon.navicraft.nav.NavigationManager;
import com.wlygon.navicraft.waypoint.Waypoint;
import com.wlygon.navicraft.waypoint.WaypointStore;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The {@code /gps} command tree:
 * <pre>
 * /gps navi &lt;x y z&gt; [dimension]   set navigation target
 * /gps marker &lt;x y z&gt; &lt;name&gt; [--portal]  save a named waypoint (~ ~ ~ supported);
 *                                 --portal marks it as a dimension portal
 * /gps goto &lt;name&gt;                navigate to a saved waypoint
 * /gps list                       list saved waypoints
 * /gps remove &lt;name&gt;              delete a waypoint
 * /gps stop                       clear active navigation
 * </pre>
 */
public final class GpsCommand {
    private static final SuggestionProvider<CommandSourceStack> MARKER_SUGGESTIONS = (context, builder) -> {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return builder.buildFuture();
        }
        List<Waypoint> waypoints = WaypointStore.get().list(context.getSource().getServer(), player.getUUID());
        return SharedSuggestionProvider.suggest(waypoints.stream().map(Waypoint::name), builder);
    };

    private GpsCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                CommandBuildContext buildContext,
                                Commands.CommandSelection environment) {
        dispatcher.register(Commands.literal("gps")
                .then(Commands.literal("navi")
                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                .executes(ctx -> navi(ctx, ctx.getSource().getLevel()))
                                .then(Commands.argument("dimension", DimensionArgument.dimension())
                                        .executes(ctx -> navi(ctx, DimensionArgument.getDimension(ctx, "dimension"))))))
                .then(Commands.literal("marker")
                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> marker(ctx, false))
                                        .then(Commands.literal("--portal")
                                                .executes(ctx -> marker(ctx, true))))))
                .then(Commands.literal("goto")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(MARKER_SUGGESTIONS)
                                .executes(GpsCommand::gotoMarker)))
                .then(Commands.literal("list")
                        .executes(GpsCommand::list))
                .then(Commands.literal("remove")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(MARKER_SUGGESTIONS)
                                .executes(GpsCommand::remove)))
                .then(Commands.literal("stop")
                        .executes(GpsCommand::stop)));
    }

    private static int navi(CommandContext<CommandSourceStack> ctx, ServerLevel level) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
        String dimensionId = level.dimension().identifier().toString();

        NavigationManager.setTarget(player, new NavTarget(dimensionId, pos, ""));

        String suffix = sameDimension(player, dimensionId) ? "" : " (in " + dimensionId + ")";
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Navigating to " + formatPos(pos) + suffix + ".").withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int marker(CommandContext<CommandSourceStack> ctx, boolean portal) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
        String name = StringArgumentType.getString(ctx, "name");
        String dimensionId = player.level().dimension().identifier().toString();

        boolean replaced = WaypointStore.get().put(ctx.getSource().getServer(), player.getUUID(),
                new Waypoint(name, dimensionId, pos.x, pos.y, pos.z, portal));
        NavigationManager.onMarkersChanged(player);

        String verb = replaced ? "Updated" : "Saved";
        String kind = portal ? "portal marker" : "marker";
        ctx.getSource().sendSuccess(() -> Component.literal(
                verb + " " + kind + " \"" + name + "\" at " + formatPos(pos) + ".").withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int gotoMarker(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");

        Waypoint waypoint = WaypointStore.get().find(ctx.getSource().getServer(), player.getUUID(), name);
        if (waypoint == null) {
            ctx.getSource().sendFailure(Component.literal("No marker named \"" + name + "\"."));
            return 0;
        }

        NavigationManager.setTarget(player, new NavTarget(waypoint.dimension(), waypoint.pos(), waypoint.name()));

        String suffix = sameDimension(player, waypoint.dimension()) ? "" : " (in " + waypoint.dimension() + ")";
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Navigating to \"" + waypoint.name() + "\" at " + formatPos(waypoint.pos()) + suffix + ".")
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        List<Waypoint> waypoints = WaypointStore.get().list(ctx.getSource().getServer(), player.getUUID());

        if (waypoints.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "No markers saved. Use /gps marker <x> <y> <z> <name>.").withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        String playerDimension = player.level().dimension().identifier().toString();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Markers (" + waypoints.size() + "):").withStyle(ChatFormatting.GOLD), false);
        for (Waypoint waypoint : waypoints) {
            boolean sameDim = waypoint.dimension().equals(playerDimension);
            String distance = sameDim
                    ? String.format("%.0fm away", player.position().distanceTo(waypoint.pos()))
                    : waypoint.dimension();
            String tag = waypoint.portal() ? " [portal]" : "";
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "  " + waypoint.name() + tag + "  " + formatPos(waypoint.pos()) + "  (" + distance + ")")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return waypoints.size();
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");

        if (WaypointStore.get().remove(ctx.getSource().getServer(), player.getUUID(), name)) {
            NavigationManager.onMarkersChanged(player);
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Removed marker \"" + name + "\".").withStyle(ChatFormatting.YELLOW), false);
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal("No marker named \"" + name + "\"."));
        return 0;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (NavigationManager.clearTarget(player)) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Navigation stopped.").withStyle(ChatFormatting.YELLOW), false);
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal("No active navigation."));
        return 0;
    }

    private static boolean sameDimension(ServerPlayer player, String dimensionId) {
        return player.level().dimension().identifier().toString().equals(dimensionId);
    }

    private static String formatPos(Vec3 pos) {
        return String.format("[%.0f, %.0f, %.0f]", pos.x, pos.y, pos.z);
    }
}
