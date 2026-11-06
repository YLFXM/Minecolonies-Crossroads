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

import java.util.UUID;
import com.YLFXM.rp.crossroads.network.OpenBuildToolPayload;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.blueprints.v1.BlueprintUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

public final class RoadNativeBuildTool {
    public static final UUID NO_SESSION=new UUID(0,0);
    public static int findToolSlot(final Inventory inventory) {
        final var tool=BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("structurize","sceptergold"));
        for(int i=0;i<inventory.getContainerSize();i++) if(inventory.getItem(i).is(tool)) return i;
        return -1;
    }
    public static void open(final ServerPlayer player, final RoadPlan plan, final Blueprint blueprint,
                            final double version, final UUID token) {
        PacketDistributor.sendToPlayer(player,new OpenBuildToolPayload(plan.id(),RoadTemporaryPack.pathFor(plan),
                BlueprintUtil.writeBlueprintToNBT(blueprint),version,plan.anchorPos(),token,RoadJointWork.impact(player.serverLevel(),plan.repairSession())));
    }
    private RoadNativeBuildTool() { }
}
