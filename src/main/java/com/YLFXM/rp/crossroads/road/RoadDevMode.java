/*
 * Copyright (C) 2026 YLFXM
 *
 * MineColonies: Crossroads - a feature expansion addon for MineColonies.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.YLFXM.rp.crossroads.road;

import com.YLFXM.rp.crossroads.Crossroads;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Session-scoped switch that keeps the old "place it yourself" path alive.
 *
 * <p>Repair and Upgrade normally hand the work to a colony builder. During
 * development that round trip is too slow to test with, and in creative play
 * the native build tool is the only way to place a road instantly. With dev mode
 * on, the panel's Repair button freezes the road and opens the build tool again
 * (the 0.2.x behaviour); with it off, the order goes to the colony.</p>
 *
 * <p>Deliberately in-memory: it is a debugging aid, not a world setting, so it
 * resets on restart rather than persisting into a save that outlives the
 * debugging session.</p>
 */
@EventBusSubscriber(modid=Crossroads.MODID)
public final class RoadDevMode {
    private static volatile boolean enabled;

    public static boolean enabled() {
        return enabled;
    }

    @SubscribeEvent
    public static void commands(final RegisterCommandsEvent event) {
        // Same root as the crossing consent command; Brigadier merges the two
        // registrations, and a literal wins over the sibling argument, so
        // "/mcrcross dev" is never mistaken for a request name.
        event.getDispatcher().register(Commands.literal("mcrcross")
                .then(Commands.literal("dev")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> apply(context,!enabled))
                        .then(Commands.literal("on").executes(context -> apply(context,true)))
                        .then(Commands.literal("off").executes(context -> apply(context,false)))));
    }

    private static int apply(final CommandContext<CommandSourceStack> context,final boolean value) {
        enabled=value;
        context.getSource().sendSuccess(() -> Component.translatable(
                value ? "rp_crossroads.dev.on" : "rp_crossroads.dev.off"),true);
        Crossroads.LOGGER.info("Road developer mode {}",value ? "enabled" : "disabled");
        return 1;
    }

    private RoadDevMode() { }
}
