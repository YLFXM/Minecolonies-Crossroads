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
    private static java.util.UUID jointRoad,jointPeer;
    public static boolean jointSelected(java.util.UUID road,java.util.UUID peer) {return road.equals(jointRoad) && peer.equals(jointPeer);}
    public static void joint(java.util.UUID road,java.util.UUID peer) {
        jointRoad=road;jointPeer=peer;lastKey=null;ClientRoadPreviewRenderer.clear();
    }
    private static java.util.UUID jointPeer(java.util.UUID road) {return road.equals(jointRoad) && jointPeer!=null ? jointPeer : new java.util.UUID(0,0);}

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
        if(base!=null && base.id().equals(jointRoad) && jointPeer!=null) {
            var peer=ClientRoadCache.roads().stream().filter(p -> p.id().equals(jointPeer)).findFirst().orElse(null);
            if(peer==null || peer.state()==RoadState.REMOVED || peer.segments().isEmpty() || !peer.dimension().equals(base.dimension())
                    || !ClientRoadCache.canManage(peer.id()) && base.planning().access(peer.id())!=2
                    || base.segments().isEmpty() || RoadCrossingGeometry.body(base).intersect(RoadCrossingGeometry.body(peer))==null) {
                jointRoad=null;jointPeer=null;lastKey=null;ClientRoadPreviewRenderer.clear();
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.translatable("mcreconstruction.crossing.preview_ended"),false);
            }
        }
        final String context = mc.level.dimension().location() + ":" + mode + ":"+mc.player.isCreative()+":"
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
        if(base.segments().isEmpty() || base.axis()==axis) {
            preview.adoptPlanning(base.planning());
            preview.adoptCommittedRows(base.rowOverrides()); preview.adoptPendingRows(base.pendingRows());
            preview.setTravelDir(base.state()==RoadState.DRAFT ? (x ? Integer.signum(b.getX()-a.getX()) : Integer.signum(b.getZ()-a.getZ())) : base.travelDir());
        }
        final String peerRevision=ClientRoadCache.roads().stream().filter(p -> p.id().equals(jointPeer(base.id())))
                .map(p -> RoadHeightProfile.editSignature(p)+":"+p.state()+":"+p.minAlong()+":"+p.maxAlong()+":"+p.widthSide()+":"+p.lampSpacing()).findFirst().orElse("");
        final String relations=ClientRoadCache.roads().stream().filter(p -> !p.id().equals(base.id()) && p.dimension().equals(base.dimension()) && !p.segments().isEmpty()
                && RoadCrossingGeometry.body(preview).intersect(RoadCrossingGeometry.body(p))!=null)
                .sorted(java.util.Comparator.comparing(RoadPlan::id)).map(p -> p.id()+":"+RoadAutoPlanning.geometryKey(p)+":"+RoadHeightProfile.editSignature(p)+":"+p.planning().crossings().hashCode()+":"+base.planning().access(p.id())+":"+base.planning().policy(p.id()))
                .collect(java.util.stream.Collectors.joining(";"));
        final String key = base.id()+":"+a+":"+b+":"+mirror+":"+base.lampSpacing()+":"+RoadHeightProfile.editSignature(preview)+":"+jointPeer(base.id())+":"+peerRevision+":"+relations;
        final long now = mc.level.getGameTime();
        if (!key.equals(lastKey) || (!ClientRoadPreviewRenderer.isLoaded() && now-requestedAt >= 40)) {
            lastKey = key; requestedAt = now;
            final long sequence = ClientRoadPreviewRenderer.nextSequence();
            ClientRoadPreviewRenderer.expect(base.id(), sequence);
            PacketDistributor.sendToServer(new RequestRoadPreviewPayload(base.controllerPos(),base.anchorPos(),
                    a,b,mirror,base.width(),base.lampSpacing(),sequence,base.id(),jointPeer(base.id())));
        }
        return preview;
    }

    static void invalidateRequest() { lastKey = null; }
    public static void reset() { preview = null; modeKey = ""; lastKey = null;jointRoad=null;jointPeer=null; }
    private ClientRoadPreview() { }
}
