/*
 * Copyright (C) 2026 ZhuanZ (YLFXM)
 *
 * MineColonies: Reconstruction Plan - a feature expansion addon for MineColonies.
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
package com.YLFXM.mcreconstruction.client;

import com.YLFXM.mcreconstruction.ModRegistry;
import com.YLFXM.mcreconstruction.network.*;
import com.YLFXM.mcreconstruction.road.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.network.PacketDistributor;

/** Bound-road display session; a filtered overview never owns Wand selection. */
public final class ClientRoadPreview {
    private static RoadPlan preview;
    private static String lastKey;
    private static long requestedAt;
    private static String modeKey = "";

    public static String mode() {
        final var mc = Minecraft.getInstance();
        if (mc.player == null) return "none";
        final var main = mc.player.getMainHandItem();
        final var off = mc.player.getOffhandItem();
        if (main.is(ModRegistry.ROAD_WAND.get())) return "wand";
        if (main.is(ModRegistry.ROAD_SURVEYOR.get())) return "surveyor";
        if (off.is(ModRegistry.ROAD_WAND.get())) return "wand";
        return off.is(ModRegistry.ROAD_SURVEYOR.get()) ? "surveyor" : "none";
    }

    public static RoadPlan plan() {
        final var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        final String mode = mode();
        final BlockPos controller = RoadWandBinding.heldController(mc.player.getInventory());
        final RoadPlan base = ClientRoadCache.findByController(controller);
        final String context = mc.level.dimension().location() + ":" + mode + ":"
                + (base == null ? controller : base.id()) + ":"
                + com.YLFXM.mcreconstruction.RoadToolContext.key(mc.player.getInventory());
        if (!context.equals(modeKey)) {
            modeKey = context;
            ClientRoadPreviewRenderer.clear();
            if ("surveyor".equals(mode)) ClientRoadCache.expectSurvey(
                    com.YLFXM.mcreconstruction.RoadToolContext.key(mc.player.getInventory()));
            PacketDistributor.sendToServer(new RequestRoadsPayload("surveyor".equals(mode)));
            com.YLFXM.mcreconstruction.MCReconstruction.LOGGER.info("Road display session: {}", context);
        }
        if (!"wand".equals(mode) || base == null || !base.dimension().equals(mc.level.dimension())
                || !RoadRules.canEdit(base) || ClientNativeBuildTracker.isActive()) {
            preview = null;
            ClientRoadPreviewRenderer.clear();
            return null;
        }
        RoadSelectionClient.bindTo(base);
        if (!RoadSelectionClient.hasBoth()) {
            preview = null; ClientRoadPreviewRenderer.clear(); return null;
        }
        final BlockPos a = RoadSelectionClient.a(), b = RoadSelectionClient.b();
        final boolean x = a.getZ() == b.getZ(), z = a.getX() == b.getX();
        if (x == z) { preview = null; ClientRoadPreviewRenderer.clear(); return null; }
        final Direction.Axis axis = x ? Direction.Axis.X : Direction.Axis.Z;
        final int length = (x ? Math.abs(a.getX()-b.getX()) : Math.abs(a.getZ()-b.getZ())) + 1;
        final boolean mirror = RoadSelectionClient.mirror();
        preview = new RoadPlan(base.id(), base.dimension(), base.anchorPos(), base.controllerPos(),
                base.controllerFacing(), mirror, base.pack(), base.variant(), base.width(), base.walkRange(),
                mirror ? -1 : 1, base.lampSpacing());
        preview.addSegment(new RoadSegment(new BlockPos(Math.min(a.getX(),b.getX()),base.anchorPos().getY()-1,
                Math.min(a.getZ(),b.getZ())),length,axis,0,SegmentKind.ROAD));
        final String key = base.id()+":"+a+":"+b+":"+mirror+":"+base.lampSpacing()+":"+base.editedRowCount();
        final long now = mc.level.getGameTime();
        if (!key.equals(lastKey) || (!ClientRoadPreviewRenderer.isLoaded() && now-requestedAt >= 40)) {
            lastKey = key; requestedAt = now;
            final long sequence = ClientRoadPreviewRenderer.nextSequence();
            ClientRoadPreviewRenderer.expect(base.id(), sequence);
            PacketDistributor.sendToServer(new RequestRoadPreviewPayload(base.controllerPos(),base.anchorPos(),
                    a,b,mirror,base.width(),base.lampSpacing(),sequence,base.id()));
        }
        return preview;
    }

    static void invalidateRequest() { lastKey = null; }
    public static void reset() { preview = null; modeKey = ""; lastKey = null; }
    private ClientRoadPreview() { }
}
