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
package com.YLFXM.rp.crossroads.network;

import com.YLFXM.rp.crossroads.road.RoadNotices;
import com.YLFXM.rp.crossroads.road.RoadRules;
import com.YLFXM.rp.crossroads.road.RoadReplanning;
import com.YLFXM.rp.crossroads.road.common.RoadProposal;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.YLFXM.rp.crossroads.Config;
import com.YLFXM.rp.crossroads.Crossroads;
import com.YLFXM.rp.crossroads.ModRegistry;
import com.YLFXM.rp.crossroads.RoadWorkOrderRecovery;
import com.YLFXM.rp.crossroads.road.RoadBlueprintGenerator;
import com.YLFXM.rp.crossroads.road.RoadBlueprintStore;
import com.YLFXM.rp.crossroads.road.RoadBuildWatcher;
import com.YLFXM.rp.crossroads.road.RoadDataCleaner;
import com.YLFXM.rp.crossroads.road.RoadManager;
import com.YLFXM.rp.crossroads.road.RoadRepair;
import com.YLFXM.rp.crossroads.road.RoadRepairSession;
import com.YLFXM.rp.crossroads.road.RoadNames;
import com.YLFXM.rp.crossroads.road.RoadPlacementMode;
import com.YLFXM.rp.crossroads.road.RoadPlan;
import com.YLFXM.rp.crossroads.road.RoadPlanBuilder;
import com.YLFXM.rp.crossroads.road.RoadSegment;
import com.YLFXM.rp.crossroads.road.RoadState;
import com.YLFXM.rp.crossroads.road.SegmentKind;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Server-bound payloads, and the outbound pushes that answer them.
 *
 * <p><b>Server/client split.</b> This class must never name a client-only type,
 * not even in a local variable: the dedicated server loads it while
 * constructing the mod ({@code Crossroads} passes {@code Network::register}
 * to the mod event bus), and loading a class verifies every method in it --
 * including the stack map frames. One {@code Minecraft}/{@code LocalPlayer}
 * local here aborts startup with {@code Attempted to load class
 * net/minecraft/client/player/LocalPlayer for invalid dist DEDICATED_SERVER}.
 * Client-bound handlers therefore live in
 * {@code client.ClientPayloadHandlers}, reached only through the
 * {@code Dist.CLIENT} branch below.</p>
 */
public final class Network {
    public static void register(final RegisterPayloadHandlersEvent event) {
        final var registrar = event.registrar("15");
        registrar.playToServer(RoadPlanningPayload.TYPE,RoadPlanningPayload.CODEC,(payload,context) ->
                context.enqueueWork(() -> { if(context.player() instanceof ServerPlayer player)
                    com.YLFXM.rp.crossroads.road.RoadPlanningActions.handle(player,payload); }));
        registrar.playToServer(RequestRoadsPayload.TYPE, RequestRoadsPayload.CODEC, Network::handleRequest);
        registrar.playToServer(ConfigureRoadPayload.TYPE, ConfigureRoadPayload.CODEC, Network::handleConfigure);
        registrar.playToServer(RoadBulkActionPayload.TYPE, RoadBulkActionPayload.CODEC, Network::handleBulkAction);
        registrar.playToServer(RoadControllerActionPayload.TYPE, RoadControllerActionPayload.CODEC,
                Network::handleControllerAction);
        registrar.playToServer(SetRoadStylePayload.TYPE, SetRoadStylePayload.CODEC,
                Network::handleSetRoadStyle);
        registrar.playToServer(ConfirmRoadRepairPayload.TYPE,ConfirmRoadRepairPayload.CODEC,(payload,context) ->
                context.enqueueWork(() -> { if(context.player() instanceof ServerPlayer player) RoadRepair.confirm(player,payload); }));
        registrar.playToServer(NativeBuildClosedPayload.TYPE, NativeBuildClosedPayload.CODEC,
                Network::handleNativeBuildClosed);
        registrar.playToServer(RequestControllerWindowPayload.TYPE,RequestControllerWindowPayload.CODEC,(payload,context) ->
                context.enqueueWork(() -> { if(context.player() instanceof ServerPlayer player)
                    com.YLFXM.rp.crossroads.DecorationControllerHandler.handle(player.serverLevel(),player,payload.controllerPos()); }));
        registrar.playToServer(CancelWorkOrdersPayload.TYPE, CancelWorkOrdersPayload.CODEC,
                Network::handleCancelWorkOrders);
        registrar.playToServer(RequestRoadPreviewPayload.TYPE, RequestRoadPreviewPayload.CODEC,
                Network::handlePreviewRequest);
        registrar.playToServer(EditRoadCellPayload.TYPE, EditRoadCellPayload.CODEC,
                Network::handleEditCell);
        registrar.playToServer(ToggleBlueprintEditPayload.TYPE, ToggleBlueprintEditPayload.CODEC,
                Network::handleToggleBlueprintEdit);
        registrar.playToServer(TerrainOrderPayload.TYPE, TerrainOrderPayload.CODEC,
                Network::handleTerrainOrder);
        registrar.playToServer(RequestUnitStylesPayload.TYPE, RequestUnitStylesPayload.CODEC,
                Network::handleRequestUnitStyles);
        // Client-bound handlers live in a client-only class; see the class javadoc.
        // The same registrar is reused so the channel version is declared once.
        if (net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) {
            com.YLFXM.rp.crossroads.client.ClientPayloadHandlers.register(registrar);
        } else {
            declareClientBoundChannels(registrar);
        }
    }

    /**
     * Declares the nine client-bound channels on the dedicated server.
     *
     * <p>The handshake compares the channel <em>names</em> each side declares, not
     * the handlers behind them. A server that leaves these out makes every client
     * that has them refuse to join with "the server is missing a channel this
     * client requires" -- which is exactly what happened while the client-bound
     * registrations lived only in {@code ClientPayloadHandlers}.</p>
     *
     * <p>A server never receives a client-bound payload, so the empty handler
     * bodies are dead code. They must stay free of client types: naming one here
     * would re-verify into the {@code RuntimeDistCleaner} failure described in the
     * class javadoc. {@code tests/NetworkChannelsRegression.java} keeps this list
     * and the client list identical.</p>
     */
    private static void declareClientBoundChannels(final PayloadRegistrar registrar) {
        registrar.playToClient(RoadSyncPayload.TYPE, RoadSyncPayload.CODEC, (payload, context) -> { });
        registrar.playToClient(OpenRoadPlanPayload.TYPE, OpenRoadPlanPayload.CODEC, (payload, context) -> { });
        registrar.playToClient(BindRoadWandPayload.TYPE, BindRoadWandPayload.CODEC, (payload, context) -> { });
        registrar.playToClient(OpenBuildToolPayload.TYPE, OpenBuildToolPayload.CODEC, (payload, context) -> { });
        registrar.playToClient(WorkOrderListPayload.TYPE, WorkOrderListPayload.CODEC, (payload, context) -> { });
        registrar.playToClient(RoadPreviewPayload.TYPE, RoadPreviewPayload.CODEC, (payload, context) -> { });
        registrar.playToClient(RoadRemovedPayload.TYPE, RoadRemovedPayload.CODEC, (payload, context) -> { });
        registrar.playToClient(UnitStyleListPayload.TYPE, UnitStyleListPayload.CODEC, (payload, context) -> { });
        registrar.playToClient(OpenRemovalChoicePayload.TYPE, OpenRemovalChoicePayload.CODEC, (payload, context) -> { });
    }

    private static void handlePreviewRequest(final RequestRoadPreviewPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            final ServerLevel level = player.serverLevel();
            final BlockPos controllerPos = payload.controllerPos();
            final com.minecolonies.api.colony.IColony colony = IColonyManager.getInstance()
                    .getColonyByPosFromWorld(level, controllerPos);
            if (!com.YLFXM.rp.crossroads.RoadPermissions.canManage(player, colony)) {
                Crossroads.LOGGER.info("[rp-preview] refused: permission, controller={}", payload.controllerPos());
                return;
            }

            final RoadPlan boundPlan = RoadManager.get(level).findByController(controllerPos);
            if (boundPlan == null || !boundPlan.id().equals(payload.roadId())) {
                Crossroads.LOGGER.info("[rp-preview] refused: plan/id mismatch, controller={} bound={} asked={}",
                        payload.controllerPos(), boundPlan == null ? "-" : boundPlan.id(), payload.roadId());
                return;
            }
            if (boundPlan != null && (!canManageRoad(level, player, boundPlan)
                    || !RoadRules.canEdit(boundPlan) || findBoundWand(player, controllerPos) == null)) {
                Crossroads.LOGGER.info("[rp-preview] refused: canManage/canEdit/wand, controller={} state={}",
                        controllerPos, boundPlan.state());
                return;
            }
            try {
                if (boundPlan != null) RoadReplanning.validate(boundPlan, payload.a(), payload.b());
            } catch (final IllegalArgumentException ex) {
                Crossroads.LOGGER.info("[rp-preview] refused: validate {}", ex.getMessage());
                return;
            }
            final int width = boundPlan == null ? Math.max(1, payload.width()) : boundPlan.width();
            final boolean mirror = payload.mirror();
            final int effectiveWidthSide = mirror ? -1 : 1;

            // A road that is already planned has to follow a re-picked A/B. The
            // ghost and the outline are recomputed from the payload every time,
            // but the temporary blueprint the native tool opens is a file on disk
            // anchored on the OLD minimum corner: leaving it alone showed the new
            // shape in the preview while the build used the old one, in the old
            // place. Rebuilding keeps the plan identity, so the file name, the
            // Wand binding and the ownership all survive.
            final RoadPlan existing = findEditableRoad(level, controllerPos);
            // Click order must not decide which end is A: snap the pair to the
            // stored orientation before anything reads it.
            final BlockPos[] ends = orientEndpoints(existing, payload.a(), payload.b());
            final BlockPos a = ends[0];
            final BlockPos b = ends[1];
            // Preview and construction must identify the same real controller; A-to-B
            // belongs to travelDir, never to the controller's immutable facing.
            final Direction facing = boundPlan.controllerFacing();

            RoadPlan plan;
            if (existing != null
                    && geometryDiffers(existing, a, b, mirror,
                            Math.max(0, Math.min(8, payload.lampSpacing())))) {
                final RoadPlan rebuilt = rebuildPlannedRoad(level, player, payload, existing);
                if (rebuilt == null) {
                    Crossroads.LOGGER.info("[rp-preview] refused: rebuildPlannedRoad returned null");
                    return;
                }
                plan = rebuilt;
                sendRoads(player, colony);
            } else if (existing != null) {
                plan = existing;
            } else {
                try {
                    // The series has to come from the plan, not a literal: a main
                    // road asked for a preview here would be rebuilt as streets and
                    // rejected by the width check, which looks like "no ghost".
                    final String series = boundPlan == null
                            ? com.YLFXM.rp.crossroads.road.RoadUnitLibrary.STREETS
                            : boundPlan.series();
                    final String style = boundPlan == null
                            ? com.YLFXM.rp.crossroads.CustomRoadPack.UNIT_STYLE_ORIGINAL
                            : boundPlan.unitStyle();
                    final var seriesWalk = com.YLFXM.rp.crossroads.road.RoadUnitLibrary.seriesWalkRange(style,series);
                    final int seriesWidth = com.YLFXM.rp.crossroads.road.RoadUnitLibrary.seriesWidth(style,series);
                    final RoadPlanBuilder.Result result = RoadPlanBuilder.build(level, boundPlan == null ? payload.anchorPos() : boundPlan.anchorPos(),
                            controllerPos, facing, a, b,
                            com.YLFXM.rp.crossroads.CustomRoadPack.STYLE_PACK,
                            series,
                            seriesWidth > 0 ? seriesWidth : width,
                            seriesWalk != null ? seriesWalk.width() : Math.min(2, width),
                            effectiveWidthSide,
                            Math.max(0, payload.lampSpacing()));
                    plan = result.plan();
                    RoadReplanning.draftPreview(boundPlan,plan,player.getUUID(),mirror);
                } catch (final IllegalArgumentException ex) {
                    // Selection is not a road yet (diagonal, too long, anchor off
                    // the line). The client keeps its last preview; no chat noise.
                    return;
                }
            }

            com.YLFXM.rp.crossroads.road.RoadCrossingConsent.inspect(level,player,plan);
            if (RoadRules.canCreate(boundPlan)) {
                try { com.YLFXM.rp.crossroads.road.RoadAutoPlanning.initialize(level,plan,false); }
                catch (IllegalArgumentException ex) {
                    plan.planning().sample(com.YLFXM.rp.crossroads.road.RoadAutoPlanning.geometryKey(plan),new int[0]);
                    plan.planning().relationshipKey(com.YLFXM.rp.crossroads.road.RoadAutoPlanning.relationshipKey(level,plan));
                    plan.planning().issue(RoadNotices.reasonKey(ex));
                }
            }
            final int oldPlanningRevision=plan.planning().revision();
            final Blueprint blueprint = RoadBlueprintGenerator.generateQuiet(plan, level,
                    Config.defaultLampReserveHeight(),player);
            if (blueprint == null) {
                Crossroads.LOGGER.warn("[rp-preview] refused: generation returned null, series={} width={} state={}",
                        plan.series(), plan.width(), plan.state());
                return;
            }
            // A valid DRAFT re-pick must refresh the pending-row count too.
            // Accept it only after generation succeeds; the preview geometry is
            // still temporary and this never consumes Create or commits rows.
            if (RoadReplanning.acceptDraftRows(boundPlan, plan)) {
                // The live DRAFT is saved, not the generated preview: this never consumes Create or commits rows.
                RoadProposal.index(level,boundPlan,RoadProposal.Audience.NONE,player,colony);
                RoadProposal.save(level,boundPlan,blueprint,RoadProposal.Save.CHECKED);
                RoadProposal.sync(level,RoadProposal.Audience.ALL,player,colony);
            } else if(plan==boundPlan && oldPlanningRevision!=plan.planning().revision()) {
                RoadProposal.index(level,plan,RoadProposal.Audience.PLAYER,player,colony);
            }
            // The preview position is the anchor itself, NOT anchor - offset.
            // Structurize's BlueprintPreviewData.setPos takes the world position
            // of the blueprint's primary block and subtracts the offset itself
            // when rendering (WorldRenderContext#renderBoxes does
            // pos.subtract(primaryBlockOffset) and draws its red marker at pos).
            // Subtracting here as well would shift the whole ghost down by one
            // primary offset and leave it buried in the ground.
            // Placing real blocks is the opposite case: that path needs
            // anchor - offset to compute the minimum corner.
            final BlockPos origin = plan.anchorPos();
            Blueprint shown=blueprint;
            if(!payload.jointPeer().equals(new UUID(0,0))) {
                try {shown=com.YLFXM.rp.crossroads.road.RoadJointWork.preview(level,player,plan,payload.jointPeer());}
                catch(IllegalArgumentException ex) {
                    Crossroads.LOGGER.info("Joint preview refused for {}: {}",plan.id(),ex.getMessage());
                    RoadNotices.chat(player, plan.id(), "joint", "rp_crossroads.preview.joint_failed", RoadNotices.reason(ex));
                }
            }
            // One line per preview (the client de-duplicates, so this is not
            // per-frame). It is the quickest way to tell a content problem from
            // a placement problem: the ghost starts at origin - primaryOffset.
            Crossroads.LOGGER.info(
                    "Road preview {}: origin={} size={}x{}x{} primaryOffset={}",
                    plan.id(), origin, blueprint.getSizeX(), blueprint.getSizeY(),
                    blueprint.getSizeZ(), blueprint.getPrimaryBlockOffset());
            PacketDistributor.sendToPlayer(player, new RoadPreviewPayload(
                    com.ldtteam.structurize.blueprints.v1.BlueprintUtil.writeBlueprintToNBT(shown),
                    origin, payload.sequence(), boundPlan.id(), level.dimension().location().toString()));
        });
    }

    /**
     * Snaps a re-picked pair to the stored orientation.
     *
     * <p>Whichever point sits on the same side of the anchor as the stored A
     * becomes A, no matter which one the player clicked first. Two things fall
     * out of that: the travel direction never changes, so the edited-cell frame
     * is stable and the override table never needs flipping; and the player is
     * free to click the two ends in any order.</p>
     *
     * <p>Roads without stored endpoints, or with an anchor sitting exactly on A,
     * are returned untouched — there is no side to snap to yet.</p>
     */
    private static BlockPos[] orientEndpoints(final RoadPlan old, final BlockPos a, final BlockPos b) {
        return RoadReplanning.orient(old, a, b);
    }

    /**
     * The road attached to this controller while it can still be re-planned, or
     * null. Built roads are locked: their controller is placed and their
     * temporary blueprint is gone, so a new selection means a new road.
     */
    private static RoadPlan findEditableRoad(final ServerLevel level, final BlockPos controllerPos) {
        final RoadPlan plan = RoadManager.get(level).findByController(controllerPos);
        return RoadRules.canEdit(plan) && !RoadRules.canCreate(plan) ? plan : null;
    }

    /** True when the payload describes a different road than the plan holds. */
    private static boolean geometryDiffers(final RoadPlan plan, final BlockPos a, final BlockPos b,
                                           final boolean mirror, final int lampSpacing) {
        // Plans saved before endpoints were stored cannot be compared; treat
        // them as different so one rebuild fills the endpoints in. Lamp spacing
        // counts as geometry too: the lamps are baked into the blueprint, so a
        // new spacing needs the file rewritten just like a new A/B does.
        return !plan.hasEndpoints()
                || !plan.endpointA().equals(a)
                || !plan.endpointB().equals(b)
                || plan.mirror() != mirror
                || plan.lampSpacing() != lampSpacing;
    }

    /**
     * Re-lays a planned road on a new A/B while keeping its identity.
     *
     * <p>The id, creator and names are carried over: they decide the blueprint
     * file name and the Wand binding, and losing them would orphan the queued
     * work orders and the controller link.</p>
     */
    private static RoadPlan rebuildPlannedRoad(final ServerLevel level, final ServerPlayer player,
                                               final RequestRoadPreviewPayload payload,
                                               final RoadPlan old) {
        // Same snap as the configure path: a rebuilt road keeps its stored
        // orientation, so the edited-cell frame does not flip.
        final BlockPos[] ends = orientEndpoints(old, payload.a(), payload.b());
        final Direction facing = old.controllerFacing();
        final int width = Math.max(1, old.width());
        final int lampSpacing = Math.max(0, Math.min(8, payload.lampSpacing()));
        final boolean mirror = payload.mirror();
        final int effectiveWidthSide = mirror ? -Math.abs(old.widthSide()) : Math.abs(old.widthSide());

        final RoadPlan plan;
        try {
            plan = RoadPlanBuilder.build(level, old.anchorPos(), payload.controllerPos(), facing,
                    ends[0], ends[1], old.pack(), old.variant(),
                    width, Math.min(2, width), effectiveWidthSide, lampSpacing).plan();
        } catch (final IllegalArgumentException ex) {
            return null;
        }
        RoadReplanning.handoff(level,old,plan,player.getUUID(),false);
        plan.setMirror(mirror);
        reportRemap(player, old, plan);

        final Blueprint blueprint = RoadBlueprintGenerator.generateQuiet(plan, level,
                Config.defaultLampReserveHeight(),player);
        if (blueprint == null) {
            return null;
        }

        com.YLFXM.rp.crossroads.road.RoadDataCleaner.releaseWorkOrders(old);
        if(!RoadProposal.pack(level,old,plan,blueprint))return null;
        RoadManager.get(level).remove(old.id());
        RoadProposal.index(level,plan,RoadProposal.Audience.NONE,player,null);
        RoadProposal.save(level,plan,blueprint,RoadProposal.Save.VOID);
        // No completion watch: nothing is being built right now. Repair arms one
        // when it actually applies the blueprint.
        return plan;
    }

    /**
     * Cycles one road cell's unit type: {@code FLAT → STAIR_UP → STAIR_DOWN → FLAT}.
     *
     * <p>The client only sends the block it is aiming at; the server resolves it
     * against the plan, so a cell outside the paved body (or on somebody else's
     * road) is rejected instead of silently written.</p>
     *
     * <p>The edited table is stored on the plan and the temporary blueprint is
     * regenerated immediately, so the ghost preview and the file the native tool
     * opens stay in sync. Editing a BUILT road also drops it back to PLANNED:
     * the world still holds the old pavement, so it has to be rebuilt — Repair
     * applies the new shape.</p>
     */
    private static void handleEditCell(final EditRoadCellPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            final ServerLevel level = player.serverLevel();
            final BlockPos controllerPos = payload.controllerPos();
            final IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(level, controllerPos);
            if (!com.YLFXM.rp.crossroads.RoadPermissions.canManage(player, colony)) {
                Crossroads.LOGGER.info("Road cell edit denied for {} at {} (no manage-huts)",
                        player.getName().getString(), controllerPos);
                return;
            }
            // The gesture is performed with the wand in hand, so only that wand's
            // binding counts. Scanning the whole inventory let an unbound wand ride
            // on another wand's binding.
            if (heldWandBoundTo(player, controllerPos) == null) {
                Crossroads.LOGGER.info("Road cell edit ignored: wand not bound to {} (player {})",
                        controllerPos, player.getName().getString());
                RoadNotices.actionBar(player, "rp_crossroads.wand.not_bound_controller");
                return;
            }
            RoadPlan plan = RoadManager.get(level).findByController(controllerPos);
            if (plan == null || !plan.id().equals(payload.roadId())) {
                Crossroads.LOGGER.info("Road cell edit ignored: no plan at {}", controllerPos);
                return;
            }
            if (!canManageRoad(level, player, plan) || !RoadRules.canEdit(plan)) {
                Crossroads.LOGGER.info("Road cell edit refused: road {} is locked or under construction", plan.id());
                RoadNotices.actionBar(player, "rp_crossroads.road.locked");
                return;
            }
            RoadBlueprintStore.preserveBuilt(level, plan);
            final boolean draft = RoadRules.canCreate(plan);
            if (draft) {
                if (payload.a().equals(BlockPos.ZERO) || payload.b().equals(BlockPos.ZERO)) {
                    // Same predicate the client checks; asking the server guarantees the
                    // player is told even when the client's cache is stale.
                    RoadNotices.actionBar(player, "rp_crossroads.select.endpoints_first");
                    return;
                }
                try {
                    final RoadPlan oldDraft = plan;
                    plan = RoadPlanBuilder.build(level, oldDraft.anchorPos(), controllerPos, oldDraft.controllerFacing(),
                            payload.a(), payload.b(), oldDraft.pack(), oldDraft.variant(), oldDraft.width(),
                            oldDraft.walkRange().width(), payload.mirror() ? -1 : 1, oldDraft.lampSpacing()).plan();
                    RoadReplanning.inherit(oldDraft, plan, player.getUUID(), true);
                    plan.setMirror(payload.mirror());
                    if (RoadReplanning.clearRowsOnAxisChange(oldDraft,plan))RoadReplanning.noticeAxisChanged(player);
                } catch (final IllegalArgumentException ex) {
                    player.displayClientMessage(RoadNotices.reason(ex), true);
                    return;
                }
            }
            final Integer row = plan.rowAt(payload.cellPos());
            if (row == null) {
                Crossroads.LOGGER.info(
                        "Road row edit refused: {} is outside plan {} (anchor {} width {} side {} along {}..{})",
                        payload.cellPos(), plan.id(), plan.anchorPos(), plan.width(), plan.widthSide(),
                        plan.minAlong(), plan.maxAlong());
                RoadNotices.actionBar(player, "rp_crossroads.row.not_part");
                return;
            }
            final com.YLFXM.rp.crossroads.road.CellType current = plan.cellType(row);
            final boolean anchorRow=row==com.YLFXM.rp.crossroads.road.RoadHeightProfile.anchorAlong(plan);
            if(anchorRow && current==com.YLFXM.rp.crossroads.road.CellType.FLAT) {
                RoadNotices.actionBar(player, "rp_crossroads.row.pinned");
                return;
            }
            final com.YLFXM.rp.crossroads.road.CellType next = anchorRow ? com.YLFXM.rp.crossroads.road.CellType.FLAT : switch (current) {
                case FLAT -> com.YLFXM.rp.crossroads.road.CellType.STAIR_UP;
                case STAIR_UP -> com.YLFXM.rp.crossroads.road.CellType.STAIR_DOWN;
                case STAIR_DOWN -> com.YLFXM.rp.crossroads.road.CellType.FLAT;
            };
            if(next!=com.YLFXM.rp.crossroads.road.CellType.FLAT
                    && com.YLFXM.rp.crossroads.road.RoadCrossings.flatLocked(level,plan,row)) {
                RoadNotices.actionBar(player, "rp_crossroads.row.pinned");
                return;
            }
            // Validate a proposal copy; a rejected height/volume change must not
            // mutate the live plan's pending rows or stop its controller guard.
            plan=RoadPlan.load(plan.save());
            if(!plan.editRow(row,next)) {
                RoadNotices.actionBar(player, "rp_crossroads.row.pinned");
                return;
            }
            try {com.YLFXM.rp.crossroads.road.RoadAutoPlanning.initialize(level,plan,true);}
            catch(IllegalArgumentException ex) {plan.planning().issue(RoadNotices.reasonKey(ex));}

            final Blueprint blueprint = RoadBlueprintGenerator.generate(plan, level,
                    Config.defaultLampReserveHeight(),player);
            if (blueprint == null) {
                // The generator already reported the failure together with its reason.
                // A second line here was the same event twice (different dedupe keys,
                // so the player saw both).
                return;
            }
            RoadBuildWatcher.stop(plan);
            // The blueprint no longer matches the world, so the road is back to
            // "planned": PLANNED means "there is a shape waiting to be applied",
            // and only applying it (Repair / build tool / builder) makes it BUILT
            // again. No completion watch is armed here, because nothing is being
            // placed — that is what used to make the state flip back on its own.
            plan.setState(draft ? RoadState.DRAFT : RoadState.PLANNED);
            if(!RoadProposal.pack(level,plan,plan,blueprint))return;
            RoadProposal.save(level,plan,blueprint,RoadProposal.Save.VOID);
            RoadProposal.index(level,plan,RoadProposal.Audience.PLAYER,player,colony);
            Crossroads.LOGGER.info("Road {} row {} (display {}) edited by {}: -> {}",
                    plan.id(), row, plan.rowNumber(row), player.getName().getString(), next);
            // A Component argument, never the CellType itself: translatable arguments
            // accept only Component/Number/Boolean/String, and an enum there throws
            // inside the packet path (it showed up as a system_chat encode failure).
            RoadNotices.actionBar(player, "rp_crossroads.row.edited", plan.rowNumber(row),
                    net.minecraft.network.chat.Component.translatable(
                            "rp_crossroads.cell." + next.name().toLowerCase(java.util.Locale.ROOT)));
        });
    }

    /**
     * Flips the per-plan "edit blueprint" switch shown in the controller panel.
     *
     * <p>It does not touch the world: it only changes what the plan accepts
     * afterwards (cell edits and A/B re-selection on a built road).</p>
     */
    private static void handleToggleBlueprintEdit(final ToggleBlueprintEditPayload payload,
                                                  final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            final ServerLevel level = player.serverLevel();
            final BlockPos controllerPos = payload.controllerPos();
            final IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(level, controllerPos);
            if (!com.YLFXM.rp.crossroads.RoadPermissions.canManage(player, colony)) {
                Crossroads.LOGGER.info("[rp-preview] refused: permission, controller={}", payload.controllerPos());
                return;
            }
            final RoadPlan plan = RoadManager.get(level).findByController(controllerPos);
            if (plan == null) {
                return;
            }
            if (!canManageRoad(level, player, plan) || !RoadRules.canToggleEdit(plan)) return;
            plan.setAllowBlueprintEdit(payload.allow());
            RoadManager.get(level).add(plan);
            sendRoads(player, colony);
            Crossroads.LOGGER.info("Road {} edit switch -> {} (by {})",
                    plan.id(), payload.allow(), player.getName().getString());
            RoadNotices.actionBar(player, payload.allow()
                    ? "rp_crossroads.edit.enabled" : "rp_crossroads.edit.locked");
        });
    }

    private static void handleConfigure(final ConfigureRoadPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            final ServerLevel level = player.serverLevel();
            final BlockPos controllerPos = payload.controllerPos();
            // No player-to-controller distance gate: A/B are picked from both
            // ends of the road, so the player is legitimately far from the
            // controller on anything longer than a few blocks. The segment is
            // bounded by Config.maxRoadLength, and the controller itself names
            // the road this request is about — no held or bound wand is needed
            // for that. Authority is the management permission checked below.

            final RoadPlacementMode mode = payload.mode();
            // Permission is about authority, not cost. RoadPermissions mirrors
            // MineColonies' own check. No chat message on denial: the colony
            // permission system already reports it to the player.
            final IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(level, controllerPos);
            if (!com.YLFXM.rp.crossroads.RoadPermissions.canManage(player, colony)) {
                Crossroads.LOGGER.debug("Denied road configure for {} at {} (no manage-huts)",
                        player.getName().getString(), controllerPos);
                return;
            }

            final RoadPlan old = RoadManager.get(level).findByController(controllerPos);
            if (old == null) {
                // Unreachable in normal play: placing the controller creates the plan, and
                // this panel only opens after that. Kept as a null guard, without a notice.
                Crossroads.LOGGER.warn("Road configure ignored: no plan at {}", controllerPos);
                return;
            }
            if (!canManageRoad(level, player, old)
                    || !(mode == RoadPlacementMode.PLAN ? RoadRules.canCreate(old) : RoadRules.canEdit(old))) {
                Crossroads.LOGGER.info("Road configure refused: plan={} state={} mode={}", old.id(), old.state(), mode);
                RoadNotices.chat(player, null, "locked_repair", "rp_crossroads.plan.locked_repair");
                return;
            }
            // Road width is determined by the style-pack blueprint, not by a
            // free-form UI value. Reuse the plan's width.
            final int width = Math.max(1, old.width());
            // Lamp spacing is the one construction value the player edits in the
            // controller panel, so it comes from the payload rather than the old plan.
            final int lampSpacing = Math.max(0, Math.min(8, payload.lampSpacing()));

            // Click order must not decide which end is A: snap the pair to the
            // stored orientation so travelDir — and with it the edited-cell
            // frame — stays put.
            final BlockPos[] ends = orientEndpoints(old, payload.a(), payload.b());
            final BlockPos a = ends[0];
            final BlockPos b = ends[1];

            final Direction facing = old != null ? old.controllerFacing()
                    : (payload.hasEndpoints() ? directionBetween(a, b) : Direction.NORTH);
            final BlockPos anchorPos = old != null ? old.anchorPos() : controllerPos;
            final String pack = old != null ? old.pack() : com.YLFXM.rp.crossroads.CustomRoadPack.STYLE_PACK;
            final String variant = old != null ? old.variant()
                    : com.YLFXM.rp.crossroads.road.RoadUnitLibrary.STREETS;
            final int widthSide = old != null ? old.widthSide() : 1;
            final boolean mirror = payload.mirror();

            try {
                RoadReplanning.validate(old, payload.hasEndpoints() ? a : null, payload.hasEndpoints() ? b : null);
            } catch (final IllegalArgumentException ex) {
                player.displayClientMessage(RoadNotices.reason(ex), false);
                Crossroads.LOGGER.info("Road configure refused: {}", ex.getMessage());
                return;
            }

            final int effectiveWidthSide = mirror ? -Math.abs(widthSide) : Math.abs(widthSide);

            final RoadPlan plan;
            final int intersections;
            try {
                if (payload.hasEndpoints()) {
                    final RoadPlanBuilder.Result result = RoadPlanBuilder.build(level, anchorPos,
                            payload.controllerPos(), facing, a, b, pack, variant,
                            width, Math.min(2, width), effectiveWidthSide, lampSpacing);
                    plan = result.plan();
                    plan.setMirror(mirror);
                    intersections = result.intersections().size();
                } else {
                    final int length = Math.max(1, Math.min(payload.length(), Config.maxRoadLength()));
                    final Direction.Axis axis = facing.getAxis().isHorizontal() ? facing.getAxis() : Direction.Axis.X;
                    plan = new RoadPlan(UUID.randomUUID(), level.dimension(), anchorPos,
                            payload.controllerPos(), facing, mirror, pack, variant,
                            width, com.YLFXM.rp.crossroads.road.WalkRange.centered(width, Math.min(2, width)),
                            effectiveWidthSide, lampSpacing);
                    // Surface is the anchor's block minus one, same as the
                    // A/B path: endpoints never decide the height.
                    plan.addSegment(new RoadSegment(anchorPos.below(), length, axis, 0, SegmentKind.ROAD));
                    plan.setTravelDir(facing.getAxisDirection().getStep());
                    plan.setState(RoadState.PLANNED);
                    intersections = 0;
                }
            } catch (final IllegalArgumentException ex) {
                RoadNotices.chat(player, null, "endpoints", "rp_crossroads.plan.endpoints_invalid", RoadNotices.reason(ex));
                return;
            }

            RoadReplanning.handoff(level,old,plan,player.getUUID(),mode==RoadPlacementMode.CONFIGURE);
            // Creative picks the tier before the first build, and it has to be set
            // after handoff (which carries the old tier over) but before the
            // blueprint is generated. Clamped here as well: the client's list is
            // only a hint about what the series ships.
            if (payload.tier() > 0) {
                final int highest = com.YLFXM.rp.crossroads.road.RoadUnitLibrary
                        .maxLevel(plan.unitStyle(), plan.series());
                plan.setUnitLevel(Math.max(1, Math.min(payload.tier(), highest)));
            }
            if (RoadRules.canCreate(old) && RoadReplanning.clearRowsOnAxisChange(old,plan))RoadReplanning.noticeAxisChanged(player);
            reportRemap(player, old, plan);
            if (!old.displayName().isBlank()) {
                plan.setDisplayName(old.displayName());
            }
            if (plan.displayName().isEmpty()) {
                final RoadNames.Name generated = RoadNames.generate(level, plan.controllerPos(), player);
                plan.setRoadName(generated.base());
                plan.setDisplayName(generated.display());
            }

            if (mode == RoadPlacementMode.PLAN) {
                // First construction uses the same token, exact selection
                // checks, frozen target and completion snapshot as Repair.
                RoadRepair.beginInitial(level, player, plan);
                return;
            }

            final Blueprint blueprint = RoadBlueprintGenerator.generate(plan, level,
                    Config.defaultLampReserveHeight(),player);
            if (blueprint == null) {
                // The generator already reported the failure together with its reason.
                // A second line here was the same event twice (different dedupe keys,
                // so the player saw both).
                return;
            }
            if (old != null) {
                // Re-selecting A/B keeps this plan's UUID and display name, so
                // the generated file name is stable and gets overwritten in
                // place. Anything queued for the previous geometry must be
                // dropped first, otherwise a stale order would build the new
                // shape at the old anchor.
                RoadDataCleaner.releaseWorkOrders(old);
                RoadDataCleaner.releaseWorkOrders(plan);
            }
            if(!RoadProposal.pack(level,old,plan,blueprint))return;

            if (old != null) {
                RoadManager.get(level).remove(old.id());
            }

            if (mode == RoadPlacementMode.CONFIGURE) {
                // Settings only: keep the plan (and its geometry) but change the
                // lamp spacing. No work order, no window, nothing placed — the
                // client re-requests the preview as soon as the synced plan
                // arrives, so the ghost redraws with the new spacing.
                RoadProposal.save(level,plan,blueprint,RoadProposal.Save.VOID);
                // No completion watch here: the world is untouched, so there is
                // nothing to wait for. Repair arms one when it applies the shape.
                RoadProposal.index(level,plan,RoadProposal.Audience.PLAYER,player,colony);
                RoadNotices.actionBar(player, "rp_crossroads.plan.lamp_spacing_set", plan.lampSpacing());
                return;
            }

            // Push the new state (BUILT / BUILDING) so the client panel and
            // preview do not keep using the pre-build DRAFT/PLANNED plan.
            sendRoads(player, colony);
        });
    }

    private static void reportRemap(final ServerPlayer player, final RoadPlan old, final RoadPlan plan) {
        if (RoadRules.canCreate(old)) return;
        final boolean changedRange = old.minAlong() != plan.minAlong() || old.maxAlong() != plan.maxAlong();
        if (!changedRange && plan.lostRowCount() == 0) return;
        RoadNotices.chat(player, plan.id(), "replan", "rp_crossroads.plan.replan_summary",
                plan.editedRowCount(), plan.lostRowCount());
    }

    /**
     * Any wand in the inventory bound to this controller.
     *
     * <p>Only the preview request uses this now: a ghost belongs to the wand's own
     * selection session, so the road has to be the one that wand is bound to.
     * The panel does not ask for it — the controller names the road — and
     * gestures use {@link #heldWandBoundTo} instead.</p>
     */
    private static net.minecraft.world.item.ItemStack findBoundWand(final ServerPlayer player,
                                                                    final BlockPos controllerPos) {
        return com.YLFXM.rp.crossroads.road.RoadWandBinding.findBound(player.getInventory(), controllerPos);
    }

    /** The wand actually in hand, and only when it is the one bound to this controller. */
    private static net.minecraft.world.item.ItemStack heldWandBoundTo(final ServerPlayer player,
                                                                     final BlockPos controllerPos) {
        final var inventory = player.getInventory();
        final net.minecraft.world.item.ItemStack main = inventory.getSelected();
        final net.minecraft.world.item.ItemStack off = inventory.offhand.get(0);
        final net.minecraft.world.item.ItemStack held = main.is(ModRegistry.ROAD_WAND.get()) ? main
                : off.is(ModRegistry.ROAD_WAND.get()) ? off : net.minecraft.world.item.ItemStack.EMPTY;
        return controllerPos.equals(com.YLFXM.rp.crossroads.road.RoadWandBinding.getBoundController(held))
                ? held : null;
    }

    private static Direction directionBetween(final BlockPos a, final BlockPos b) {
        if (a.getX() == b.getX()) {
            return b.getZ() > a.getZ() ? Direction.SOUTH : Direction.NORTH;
        }
        return b.getX() > a.getX() ? Direction.EAST : Direction.WEST;
    }

    private static void handleRequest(final RequestRoadsPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            RoadWorkOrderRecovery.recoverAll(player.server);
            if (payload.surveyor()) {
                final var main = player.getMainHandItem();
                final var stack = main.is(ModRegistry.ROAD_SURVEYOR.get()) ? main : player.getOffhandItem();
                if (!stack.is(ModRegistry.ROAD_SURVEYOR.get())) return;
                final BlockPos townHall = com.YLFXM.rp.crossroads.TownHallBinding.get(stack);
                final IColony scope = townHall == null ? null : IColonyManager.getInstance().getColonyByPosFromWorld(player.serverLevel(), townHall);
                sendSurveyorRoads(player, scope, scope == null);
            } else sendRoads(player);
            Crossroads.LOGGER.debug("Sent road index to {}", player.getName().getString());
        });
    }

    private static void handleBulkAction(final RoadBulkActionPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            final ServerLevel level = player.serverLevel();
            // Same judgement as the single-road Remove button: in the developer
            // switch the player chooses builder or native demolition first, and
            // only a selection that really tears blocks down is worth asking
            // about — a DRAFT-only selection is a management cleanup either way.
            if (payload.removeBlocks() && !payload.confirmed()
                    && com.YLFXM.rp.crossroads.road.RoadDevMode.enabled()
                    && !allDraft(level, payload.roadIds())) {
                final java.util.List<java.util.UUID> manageable = manageableOf(level, player, payload.roadIds());
                if (!manageable.isEmpty()) {
                    PacketDistributor.sendToPlayer(player,
                            new OpenRemovalChoicePayload(BlockPos.ZERO, manageable));
                    return;
                }
            }
            int cleared = 0;
            int denied = 0;
            com.minecolonies.api.colony.IColony scope = null;
            for (final java.util.UUID id : payload.roadIds()) {
                final RoadPlan plan = RoadManager.get(level).get(id);
                if (plan == null) {
                    continue;
                }
                if (!canManageRoad(level, player, plan)) {
                    denied++;
                    continue;
                }
                if (scope == null) {
                    // Keep the refreshed view scoped to the colony being acted on.
                    scope = IColonyManager.getInstance()
                            .getColonyByPosFromWorld(level, plan.controllerPos());
                }
                if (payload.removeBlocks()) {
                    if (com.YLFXM.rp.crossroads.road.RoadRemover.remove(level,player,plan,payload.instant())) cleared++;
                } else { if(RoadDataCleaner.clear(level,plan)) cleared++; else denied++; }
            }
            sendRoads(player, scope);
            final int count = cleared;
            if (denied > 0) {
                // No permission prompt in chat: the colony system already covers
                // it. Keep the count in the debug log for diagnosis.
                Crossroads.LOGGER.debug("Skipped {} road(s) for {} (no manage-huts)",
                        denied, player.getName().getString());
            }
            RoadNotices.chat(player, null, "processed", "rp_crossroads.plan.processed", count);
        });
    }

    /** True when every resolvable id is a DRAFT road, i.e. nothing but management blocks would go. */
    private static boolean allDraft(final ServerLevel level, final java.util.List<java.util.UUID> ids) {
        boolean any = false;
        for (final java.util.UUID id : ids) {
            final RoadPlan plan = RoadManager.get(level).get(id);
            if (plan == null) {
                continue;
            }
            any = true;
            if (plan.state() != RoadState.DRAFT) {
                return false;
            }
        }
        return any;
    }

    /** Subset of {@code ids} this player may act on, in the order given. */
    private static java.util.List<java.util.UUID> manageableOf(final ServerLevel level, final ServerPlayer player,
                                                              final java.util.List<java.util.UUID> ids) {
        final java.util.List<java.util.UUID> result = new java.util.ArrayList<>(ids.size());
        for (final java.util.UUID id : ids) {
            final RoadPlan plan = RoadManager.get(level).get(id);
            if (plan != null && canManageRoad(level, player, plan)) {
                result.add(id);
            }
        }
        return result;
    }

    /** Permission for destructive road actions: manage-huts in the owning colony. */
    private static boolean canManageRoad(final ServerLevel level, final ServerPlayer player,
                                         final RoadPlan plan) {
        return com.YLFXM.rp.crossroads.RoadPermissions.canManage(level, player, plan);
    }

    private static void handleControllerAction(final RoadControllerActionPayload payload,
                                               final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            final ServerLevel level = player.serverLevel();
            final RoadPlan plan = RoadManager.get(level).findByController(payload.controllerPos());
            if (plan == null) {
                // Unreachable in normal play, see the configure handler. Guard only.
                Crossroads.LOGGER.warn("Controller action ignored: no plan at {}", payload.controllerPos());
                return;
            }
            if (!canManageRoad(level, player, plan)) {
                Crossroads.LOGGER.debug("Denied controller action {} for {} (no manage-huts)",
                        payload.action(), player.getName().getString());
                return;
            }
            switch (payload.action()) {
                case REMOVE -> {
                    // Which demolition runs is a server decision: a colony builder
                    // in normal play, the native queue only in the developer
                    // switch — where the player picks between them first. The
                    // confirmed flag is that answer coming back, so it must not
                    // open the prompt a second time.
                    if (com.YLFXM.rp.crossroads.road.RoadDevMode.enabled() && !payload.confirmed()) {
                        PacketDistributor.sendToPlayer(player,
                                new OpenRemovalChoicePayload(payload.controllerPos(), java.util.List.of()));
                        return;
                    }
                    com.YLFXM.rp.crossroads.road.RoadRemover.remove(level,player,plan,false);
                }
                case REMOVE_INSTANT -> com.YLFXM.rp.crossroads.road.RoadRemover.remove(level,player,plan,true);
                // Repair and Upgrade are the same order with a different target
                // tier: a colony builder replaces the road from a freshly
                // generated blueprint. The developer switch keeps the old
                // "freeze, then place it yourself in the build tool" flow for
                // testing, which is also how creative play uses it.
                case REPAIR -> {
                    if (com.YLFXM.rp.crossroads.road.RoadDevMode.enabled()) {
                        RoadRepair.begin(level,player,plan);
                    } else {
                        RoadRepair.beginSurvival(level,player,plan,plan.unitLevel());
                    }
                }
                case UPGRADE -> {
                    final int max = com.YLFXM.rp.crossroads.road.RoadUnitLibrary
                            .maxLevel(plan.unitStyle(),plan.series());
                    if (plan.unitLevel() >= max) {
                        RoadNotices.chat(player,null,"upgrade_max","rp_crossroads.plan.upgrade_max");
                    } else {
                        RoadRepair.beginSurvival(level,player,plan,plan.unitLevel()+1);
                    }
                }
            }
            sendRoads(player, IColonyManager.getInstance()
                    .getColonyByPosFromWorld(level, plan.controllerPos()));
        });
    }

    /**
     * Turns the wand's two selected corners into a terrain dig or fill order.
     *
     * <p>Both corners must sit in the same colony: the order is admitted there,
     * and the builder who executes it comes from that colony.</p>
     */
    private static void handleTerrainOrder(final TerrainOrderPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            final ServerLevel level = player.serverLevel();
            // The colony comes from the blueprint anchor (the box's minimum
            // corner). Whether the rest of the box is inside is the native
            // work-order check's business, not ours.
            final com.minecolonies.api.colony.IColony colony = IColonyManager.getInstance()
                    .getColonyByPosFromWorld(level, com.YLFXM.rp.crossroads.road.TerrainOrders.anchor(payload.a(), payload.b()));
            if (colony == null) {
                RoadNotices.chat(player, null, "terrain_colony", "rp_crossroads.terrain.no_colony");
                return;
            }
            if (!com.YLFXM.rp.crossroads.RoadPermissions.canManage(player, colony)) {
                com.YLFXM.rp.crossroads.RoadPermissions.notifyDenied(player);
                return;
            }
            // Prepare and open in one step: the box the player saw was drawn from
            // their own A/B, so there is nothing for the server to confirm first.
            if (com.YLFXM.rp.crossroads.road.TerrainOrders.prepare(level, player,
                    payload.a(), payload.b(), payload.fill()) != null) {
                com.YLFXM.rp.crossroads.road.TerrainOrders.openTool(level, player);
            }
        });
    }

    /**
     * Scans this save's pack for the styles and series that can actually be built
     * and sends the result.
     *
     * <p>Runs on the server because the pack is the server's: a client that
     * scanned its own copy would offer styles that are absent here. The scan is
     * two directory listings, so no caching and no paging.</p>
     */
    private static void handleRequestUnitStyles(final RequestUnitStylesPayload payload,
                                                final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            final java.util.List<String> styles =
                    com.YLFXM.rp.crossroads.road.RoadUnitLibrary.styles(player.server);
            final java.util.Map<String, java.util.List<String>> series = new java.util.LinkedHashMap<>();
            for (final String style : styles) {
                series.put(style, com.YLFXM.rp.crossroads.road.RoadUnitLibrary.seriesIds(player.server, style));
            }
            PacketDistributor.sendToPlayer(player, new UnitStyleListPayload(styles, series));
        });
    }

    /**
     * Applies a style/series switch and, for a road that already exists in the
     * world, rebuilds it through the normal repair path.
     *
     * <p>Width is a series property (streets 4, main 5) and the units only exist
     * for the declared width, so the width has to follow the series. Endpoints
     * are left alone: they decide where the road runs and how long it is, not
     * how wide it is.</p>
     */
    private static void handleSetRoadStyle(final SetRoadStylePayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            final ServerLevel level = player.serverLevel();
            final RoadPlan plan = RoadManager.get(level).findByController(payload.controllerPos());
            if (plan == null) {
                return;
            }
            if (!canManageRoad(level, player, plan)) {
                Crossroads.LOGGER.debug("Denied style switch for {} (no manage-huts)",
                        player.getName().getString());
                return;
            }
            // A road with a session or an open build order is mid-flight; letting
            // its units change under it would mix two styles in one build.
            if (plan.isRepairBusy() || com.YLFXM.rp.crossroads.road.RoadBuildWatcher.hasWorkOrder(plan)
                    || com.YLFXM.rp.crossroads.road.RoadBuildWatcher.isWatching(plan)) {
                RoadNotices.chat(player, plan.id(), "style_busy", "rp_crossroads.style.busy");
                return;
            }
            if (!com.YLFXM.rp.crossroads.road.RoadUnitLibrary.styles(level.getServer())
                    .contains(payload.style())) {
                RoadNotices.chat(player, plan.id(), "style_unknown", "rp_crossroads.style.unknown", payload.style());
                return;
            }
            final String style = payload.style();
            final String series = com.YLFXM.rp.crossroads.road.RoadUnitLibrary.normalizeSeries(payload.series());
            final int width = com.YLFXM.rp.crossroads.road.RoadUnitLibrary.seriesWidth(style,series);
            final com.YLFXM.rp.crossroads.road.WalkRange range =
                    com.YLFXM.rp.crossroads.road.RoadUnitLibrary.seriesWalkRange(style,series);
            final int maxLevel = com.YLFXM.rp.crossroads.road.RoadUnitLibrary.maxLevel(style,series);
            if (width <= 0 || range == null || maxLevel <= 0) {
                RoadNotices.chat(player, plan.id(), "series_unknown", "rp_crossroads.style.unknown", payload.series());
                return;
            }
            // A series may ship fewer tiers than the road is currently on, so the
            // tier is clamped against the pair being applied, not the old one.
            final int tier = Math.max(1, Math.min(payload.level(), maxLevel));
            if (width == plan.width() && series.equals(plan.series())
                    && tier == plan.unitLevel() && style.equals(plan.unitStyle())) {
                return;
            }
            // Only a width change reaches cells outside this road's own footprint.
            // A tier change keeps the footprint and is reconciled per junction cell
            // like any other repair, so it does not need the crossing refusal.
            final boolean widthChanged = width != plan.width();
            // A crossing is a shared cell contract: the junction cell is written
            // by both roads, and a series switch changes that cell's block. Letting
            // the switch through would either silently rewrite the neighbour's
            // junction or abort deep inside the merge with "conflicting native work
            // targets". Refuse up front and say why.
            if (crossesAnotherRoad(level, plan)) {
                RoadNotices.chat(player, plan.id(), "style_crossing", "rp_crossroads.style.crossing");
                return;
            }
            final int previousLevel = plan.unitLevel();
            Crossroads.LOGGER.info("Road {} unit switch: style {} series {} -> {} width {} -> {} tier {} -> {} state {}",
                    plan.id(), style, plan.series(), series, plan.width(), width, previousLevel, tier, plan.state());
            plan.setUnitStyle(style);
            plan.setVariant(series);
            plan.setWidth(width);
            plan.setWalkRange(range);
            plan.setUnitLevel(tier);
            RoadManager.get(level).add(plan);
            // Re-plan first: it samples the world and uses the stored geometry as
            // the terrain baseline, so it has to run while that geometry still
            // exists. A tier change keeps the footprint, so the plan stands.
            if (widthChanged && !plan.segments().isEmpty()) {
                try {
                    com.YLFXM.rp.crossroads.road.RoadAutoPlanning.initialize(level, plan, true);
                } catch (final IllegalArgumentException ex) {
                    Crossroads.LOGGER.warn("Unit switch could not re-plan road {}: {}", plan.id(), ex.getMessage());
                    RoadNotices.chat(player, plan.id(), "style_replan", "rp_crossroads.style.replan_failed",
                            RoadNotices.reason(ex));
                    return;
                }
            }
            if (widthChanged) {
                // The old units describe a road of another width, so their applied
                // snapshot is no longer this road's target. The blueprint file
                // stays: it is what the world currently holds, and cancelling the
                // order has to be able to point back at it.
                plan.setAppliedGeometry(null);
                plan.setAppliedBounds(null);
                RoadManager.get(level).add(plan);
            }
            if (com.YLFXM.rp.crossroads.road.RoadRules.canRepair(plan)) {
                // An existing road is re-laid by a builder, whether the player only
                // changed the tier or also the series.
                if (!RoadRepair.beginSurvival(level, player, plan, tier, previousLevel)) {
                    RoadNotices.chat(player, plan.id(), "style_repair", "rp_crossroads.style.repair_refused");
                }
            } else if (plan.state() == com.YLFXM.rp.crossroads.road.RoadState.DRAFT) {
                republishDraft(level, player, plan);
            }
            sendRoads(player, IColonyManager.getInstance()
                    .getColonyByPosFromWorld(level, plan.controllerPos()));
        });
    }

    /**
     * Regenerates the ghost of a road that has not been built yet.
     *
     * <p>The draft ghost is the published proposal, not the generator's return
     * value, so generating alone leaves the previous proposal on disk and the
     * change looks like "nothing happened". {@code generated/} is deliberately
     * outside the pack's content hash, hence the revision bump at the end.</p>
     */
    private static void republishDraft(final ServerLevel level, final ServerPlayer player, final RoadPlan plan) {
        final com.ldtteam.structurize.blueprints.v1.Blueprint proposal =
                com.YLFXM.rp.crossroads.road.RoadBlueprintGenerator.generate(
                        plan, level, com.YLFXM.rp.crossroads.Config.defaultLampReserveHeight(), player);
        Crossroads.LOGGER.info("[rp-style] draft proposal generated={} series={} tier={} width={} state={}",
                proposal != null, plan.series(), plan.unitLevel(), plan.width(), plan.state());
        if (proposal == null) {
            return;
        }
        final com.minecolonies.api.colony.IColony colony = IColonyManager.getInstance()
                .getColonyByPosFromWorld(level, plan.controllerPos());
        com.YLFXM.rp.crossroads.road.common.RoadProposal.save(level, plan, proposal,
                com.YLFXM.rp.crossroads.road.common.RoadProposal.Save.CHECKED);
        com.YLFXM.rp.crossroads.road.common.RoadProposal.pack(level, plan, plan, proposal);
        com.YLFXM.rp.crossroads.road.common.RoadProposal.index(level, plan,
                com.YLFXM.rp.crossroads.road.common.RoadProposal.Audience.ALL, player, colony);
        // generated/ is outside contentVersion: without a bump the client keeps
        // the old file until it restarts, which reads as a failed switch.
        com.YLFXM.rp.crossroads.CustomRoadPack.install(level.getServer(), true);
    }

    /**
     * True when any other road shares a cell with this one.
     *
     * <p>Crossings are the one case a series switch cannot handle on its own: the
     * junction cell belongs to both roads, so changing the primary's units would
     * contradict the neighbour's blueprint. Refusing beats writing a half-merged
     * target and failing later.</p>
     */
    private static boolean crossesAnotherRoad(final ServerLevel level, final RoadPlan plan) {
        if (plan.segments().isEmpty()) {
            return false;
        }
        final var body = com.YLFXM.rp.crossroads.road.RoadCrossingGeometry.body(plan);
        for (final RoadPlan other : RoadManager.get(level).all()) {
            if (other.id().equals(plan.id()) || other.segments().isEmpty()) {
                continue;
            }
            if (body.intersect(com.YLFXM.rp.crossroads.road.RoadCrossingGeometry.body(other)) != null) {
                return true;
            }
        }
        return false;
    }

    public static void sendWorkOrders(final ServerPlayer player,
                                      final com.minecolonies.api.colony.IColony colony) {
        sendWorkOrders(player, colony, true);
    }

    /**
     * @param requireView false only for the refresh after an action the player
     *                    was already allowed to perform, so the window cannot
     *                    go stale just because a custom rank was given
     *                    {@code MANAGE_HUTS} without {@code ACCESS_HUTS}
     */
    public static void sendWorkOrders(final ServerPlayer player,
                                      final com.minecolonies.api.colony.IColony colony,
                                      final boolean requireView) {
        // Colony internals: the work order list needs ACCESS_HUTS, the same bit
        // the original uses to let a player into a hut at all. Vanilla's
        // resource scroll actually ships no check, but shipping colony internals
        // to anyone holding a bound item is a leak, not a feature.
        if (requireView && !com.YLFXM.rp.crossroads.RoadPermissions.canView(player, colony)) {
            // Nothing is sent: the client opens the window only when this
            // payload arrives, so staying silent keeps it closed -- the same
            // outcome as vanilla denying a hut click.
            com.YLFXM.rp.crossroads.RoadPermissions.notifyDenied(player);
            return;
        }
        final java.util.List<WorkOrderListPayload.Entry> entries = new java.util.ArrayList<>();
        for (final var order : colony.getWorkManager().getWorkOrders().values()) {
            if (order == null) {
                continue;
            }
            // A single unreadable order must never take down the whole list:
            // decoration orders can have no stage yet, and getStage() returns
            // null until a builder picks the order up.
            try {
                final var stage = order.getStage();
                // Keep every player-facing part of the row language-independent:
                // the name is serialized as a component (so its translatable
                // contents resolve on the client) and the state travels as an
                // enum name plus a flag instead of a pre-rendered English phrase.
                final RoadPlan road = com.YLFXM.rp.crossroads.road.RoadWorkOrders.find(player.serverLevel(), order);
                final net.minecraft.network.chat.Component name = road != null
                        ? net.minecraft.network.chat.Component.literal(com.YLFXM.rp.crossroads.road.RoadNames.label(road))
                        : order.getDisplayName();
                final String nameJson = net.minecraft.network.chat.Component.Serializer.toJson(
                        name, player.serverLevel().registryAccess());
                entries.add(new WorkOrderListPayload.Entry(order.getID(),
                        order.getWorkOrderType() == null ? "UNKNOWN" : order.getWorkOrderType().name(),
                        nameJson,
                        order.getLocation().toShortString(),
                        order.isClaimed(),
                        stage == null ? null : stage.name(),
                        order.getPriority(),
                        order.getCurrentLevel(),
                        order.getTargetLevel()));
            } catch (final RuntimeException ex) {
                Crossroads.LOGGER.warn("Skipping unreadable work order in colony {}",
                        colony.getID(), ex);
            }
        }
        entries.sort(java.util.Comparator.comparingInt(WorkOrderListPayload.Entry::priority)
                .thenComparingInt(WorkOrderListPayload.Entry::id));
        PacketDistributor.sendToPlayer(player, new WorkOrderListPayload(colony.getID(), colony.getName(),
                entries, true, com.YLFXM.rp.crossroads.RoadPermissions.canManage(player, colony)));
    }

    /** Unbound scroll: open an empty list that tells the player how to bind. */
    public static void sendUnboundWorkOrders(final ServerPlayer player) {
        PacketDistributor.sendToPlayer(player,
                new WorkOrderListPayload(-1, "", java.util.List.of(), false, false));
    }

    /**
     * Cancels the selected work orders and pushes a fresh list back so the open
     * window refreshes from authoritative data.
     */
    private static void handleCancelWorkOrders(final CancelWorkOrdersPayload payload,
                                               final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            IColony colony = null;
            for (final IColony candidate : IColonyManager.getInstance().getAllColonies()) {
                if (candidate.getID() == payload.colonyId()) {
                    colony = candidate;
                    break;
                }
            }
            if (colony == null) {
                RoadNotices.chat(player, null, "colony_gone", "rp_crossroads.plan.colony_gone");
                return;
            }
            if (!com.YLFXM.rp.crossroads.RoadPermissions.canManage(player, colony)) {
                Crossroads.LOGGER.debug("Denied work order cancel for {} in colony {}",
                        player.getName().getString(), payload.colonyId());
                return;
            }
            int cancelled = 0;
            final java.util.Set<java.util.UUID> affected=new java.util.HashSet<>();
            for (final int orderId : payload.orderIds()) {
                try {
                    final var order=colony.getWorkManager().getWorkOrders().get(orderId);
                    if (order!=null) {
                        if(colony.getWorld() instanceof ServerLevel roadLevel) {
                            final var road=com.YLFXM.rp.crossroads.road.RoadWorkOrders.find(roadLevel,order);
                            if(road!=null) {
                                if(road.creator()!=null) affected.add(road.creator());
                                if(road.repairSession()!=null) affected.add(road.repairSession().owner());
                                if(road.removalSession()!=null) affected.add(road.removalSession().owner());
                            }
                        }
                        colony.getWorkManager().removeWorkOrder(orderId);
                        cancelled++;
                    }
                } catch (final RuntimeException ex) {
                    Crossroads.LOGGER.warn("Could not cancel work order {} in colony {}",
                            orderId, payload.colonyId(), ex);
                }
            }
            final int count = cancelled;
            RoadNotices.chat(player, null, "orders_cancelled", "rp_crossroads.plan.orders_cancelled", count);
            if(count>0) for(final var recipient:player.server.getPlayerList().getPlayers()) {
                if(!recipient.getUUID().equals(player.getUUID()) && (affected.contains(recipient.getUUID())
                        || com.YLFXM.rp.crossroads.RoadPermissions.canView(recipient,colony)
                        || com.YLFXM.rp.crossroads.RoadPermissions.canManage(recipient,colony)))
                    RoadNotices.chat(recipient, null, "cancelled_by", "rp_crossroads.workorders.cancelled_by",
                            player.getDisplayName(), count, colony.getName());
            }
            // Already authorised by canManage above; refresh unconditionally.
            sendWorkOrders(player, colony, false);
        });
    }

    private static void handleNativeBuildClosed(final NativeBuildClosedPayload payload,
                                                final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            final ServerLevel level = player.serverLevel();
            final RoadPlan plan = RoadManager.get(level).get(payload.planId());
            if (plan != null && plan.repairSession()!=null) {
                RoadRepair.closed(player,plan,payload.repairToken());
            } else if (plan != null && payload.repairToken().equals(com.YLFXM.rp.crossroads.road.RoadNativeBuildTool.NO_SESSION)
                    && canManageRoad(level,player,plan)) {
                RoadBuildWatcher.requestClose(level, plan);
            }
        });
    }

    public static void sendRoads(final ServerPlayer player) {
        sendRoads(player, null);
    }

    /**
     * Sends the road index for a player.
     *
     * <p>With a colony, only that colony's roads are included and the client
     * marks the view as bound. Without one (the Road Wand panel) every road the
     * player is allowed to know about is sent.</p>
     *
     * <p><b>Visibility rules.</b> A road is only sent to a player who could
     * actually see it in the world:</p>
     * <ul>
     *   <li>Colony road — members of that colony may see it (viewing is not
     *       restricted; the house-manage permission only gates the buttons).</li>
     *   <li>Wilderness road — <em>creator only</em>. Wilderness is nobody's
     *       business, and a road's name plus controller position already leaks
     *       where someone is building.</li>
     * </ul>
     * <p>Roads from another colony, and wilderness roads planned by somebody
     * else, are omitted entirely rather than greyed out — a greyed row would
     * still tell the player a road exists there.</p>
     */
    public static void sendRoads(final ServerPlayer player,
                                 final com.minecolonies.api.colony.IColony colony) {
        sendRoads(player, colony, false);
    }

    /**
     * @param wildernessOnly when unbound, list wilderness roads only. The Road
     *                       Surveyor asks for this: a road standing inside a
     *                       colony belongs to that colony, so it has no business
     *                       in an unbound view. The Wand must not use it — it
     *                       resolves its bound controller out of this same cache
     *                       and would stop finding colony roads.
     */
    public static void sendRoads(final ServerPlayer player,
                                 final com.minecolonies.api.colony.IColony colony,
                                 final boolean wildernessOnly) {
        sendRoadIndex(player, colony, wildernessOnly, false);
    }

    public static void sendSurveyorRoads(final ServerPlayer player, final IColony colony, final boolean wildernessOnly) {
        sendRoadIndex(player, colony, wildernessOnly, true);
    }

    private static void sendRoadIndex(final ServerPlayer player, final IColony colony,
                                      final boolean wildernessOnly, final boolean surveyView) {
        final ServerLevel level = player.serverLevel();
        final CompoundTag tag = new CompoundTag();
        tag.putBoolean("surveyView", surveyView);
        if (surveyView) tag.putString("surveyContext", com.YLFXM.rp.crossroads.RoadToolContext.key(player.getInventory()));
        tag.putString("dimension", level.dimension().location().toString());
        tag.putBoolean("bound", colony != null);
        tag.putString("colony", colony == null ? "" : colony.getName());
        final ListTag list = new ListTag();
        final ListTag surveyIds = new ListTag();
        // Which roads this player may act on; the client only uses it to grey out
        // buttons, every action is re-checked server side.
        final ListTag manageable = new ListTag();
        final RoadPlan relationOrigin=RoadManager.get(level).findByController(com.YLFXM.rp.crossroads.road.RoadWandBinding.heldController(player.getInventory()));
        for (final RoadPlan plan : RoadManager.get(level).all()) {
            final com.minecolonies.api.colony.IColony owner = IColonyManager.getInstance()
                    .getColonyByPosFromWorld(level, plan.controllerPos());
            final boolean visible=isVisibleTo(plan,owner,player);
            final boolean relationOnly=!visible && relationOrigin!=null && !relationOrigin.id().equals(plan.id())
                    && canManageRoad(level,player,relationOrigin) && !relationOrigin.segments().isEmpty() && !plan.segments().isEmpty()
                    && (com.YLFXM.rp.crossroads.road.RoadJointWork.potentialOverlap(relationOrigin,plan)
                        || com.YLFXM.rp.crossroads.road.RoadJunctions.find(level,relationOrigin.id(),plan.id())!=null);
            if(!visible && !relationOnly)continue;
            final boolean inScope = colony != null
                    ? owner == null || owner.getID() == colony.getID()
                    : !wildernessOnly || owner == null;
            if (inScope && !relationOnly) surveyIds.add(net.minecraft.nbt.StringTag.valueOf(plan.id().toString()));
            RoadBlueprintStore.ensureAppliedBounds(level,plan);
            final CompoundTag sync;
            if(relationOnly) {
                boolean candidateOverlap=com.YLFXM.rp.crossroads.road.RoadCrossingGeometry.body(relationOrigin).intersect(
                        com.YLFXM.rp.crossroads.road.RoadCrossingGeometry.body(plan))!=null;
                sync=(candidateOverlap ? plan:com.YLFXM.rp.crossroads.road.RoadCrossings.reference(level,plan)).saveForSync();
                var planning=new CompoundTag();var original=sync.getCompound("planning");
                planning.put("pairs",original.getList("pairs",10).copy());planning.put("spanFloors",original.getList("spanFloors",10).copy());
                sync.put("planning",planning);sync.remove("creator");sync.putBoolean("relationOnly",true);
                sync.putString("state",plan.state().name());sync.putBoolean("allowBlueprintEdit",plan.allowBlueprintEdit());
                if(plan.appliedBounds()!=null)sync.put("appliedBounds",plan.appliedBounds().save());
            } else sync=plan.saveForSync();
            injectJunctions(level,plan,sync);
            sync.putBoolean("repairBusy",plan.isRepairBusy());
            writeBuilder(level,plan,sync);
            if(relationOrigin!=null && relationOrigin.id().equals(plan.id()) && canManageRoad(level,player,plan))com.YLFXM.rp.crossroads.road.RoadCrossingConsent.addAccessSync(level,player,plan,sync);
            list.add(sync);
            if (canManageRoad(level, player, plan)) {
                manageable.add(net.minecraft.nbt.StringTag.valueOf(plan.id().toString()));
            }
        }
        tag.put("roads", list);
        tag.put("surveyIds", surveyIds);
        tag.put("manageable", manageable);
        // Every blueprint file name this player is entitled to, across all
        // dimensions. The client prunes anything else from its local
        // blueprints/<road pack>/generated/ so blueprints pushed in an
        // earlier session (or for a road the player lost access to) cannot
        // linger in the native build tool.
        tag.put("blueprints", entitledBlueprintNames(player));
        PacketDistributor.sendToPlayer(player, new RoadSyncPayload(tag));
    }

    /**
     * Tells the client who is working on this road, so the controller panel can name them
     * instead of showing a bare "busy" state.
     *
     * <p>Sync-only data: the session itself stays authoritative, and the name is resolved
     * from the player list at send time (an offline owner simply has no name).</p>
     */
    public static void writeBuilder(final ServerLevel level, final RoadPlan plan, final CompoundTag sync) {
        final UUID who;
        final String phase;
        final RoadRepairSession repair = plan.repairSession();
        if (repair != null) {
            who = repair.owner();
            phase = repair.phase() == RoadRepairSession.Phase.PREPARED ? "PREPARED" : "BUILDING";
        } else if (plan.removalSession() != null) {
            who = plan.removalSession().owner();
            phase = "REMOVING";
        } else {
            return;
        }
        final ServerPlayer online = level.getServer().getPlayerList().getPlayer(who);
        sync.putUUID("builder", who);
        sync.putString("builderName", online == null ? "" : online.getGameProfile().getName());
        sync.putString("builderPhase", phase);
    }

    /** Projects this dimension's junction records into the client's read-only relation view. */
    private static void injectJunctions(final ServerLevel level, final RoadPlan plan, final CompoundTag sync) {
        final ListTag pairs = new ListTag();
        for (final var junction : com.YLFXM.rp.crossroads.road.RoadJunctions.forRoad(level, plan.id())) {
            final UUID other = junction.a().equals(plan.id()) ? junction.b() : junction.a();
            if (RoadManager.get(level).get(other) == null) continue;
            final var rule = com.YLFXM.rp.crossroads.road.RoadJunctions.ruleFor(junction, plan.id());
            final CompoundTag pair = new CompoundTag();
            pair.putUUID("road", other);
            pair.putBoolean("layered", rule.layered());
            pair.putInt("order", rule.order());
            pair.putLong("version", rule.version());
            if (rule.platform() != null) pair.putInt("platform", rule.platform());
            pair.putIntArray("rect", new int[]{junction.rect().minX(), junction.rect().minZ(),
                    junction.rect().maxX(), junction.rect().maxZ()});
            pair.putBoolean("builtPeer", junction.a().equals(plan.id()) ? junction.builtB() : junction.builtA());
            pairs.add(pair);
        }
        sync.getCompound("planning").put("pairs", pairs);
    }

    /**
     * Names of the road blueprints {@code player} is entitled to, in every
     * dimension.
     *
     * <p>Names rather than ids: the client stores blueprints as
     * {@code <name>.blueprint}, and the name is what the build tool lists.</p>
     *
     * <p>Deliberately stricter than {@link #isVisibleTo}: seeing that a road
     * exists is not the same as being handed its blueprint, which describes the
     * colony's layout block by block.</p>
     */
    private static ListTag entitledBlueprintNames(final ServerPlayer player) {
        final ListTag names = new ListTag();
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (final ServerLevel other : player.getServer().getAllLevels()) {
            for (final RoadPlan plan : RoadManager.get(other).all()) {
                // Finished roads have no blueprint anywhere: the server removed it
                // from the pack when the road was marked built. Leaving the name
                // in this list would let the client keep its copy, and the native
                // build tool would go on listing a road that no longer needs one.
                if (!RoadRules.canPublish(plan)) {
                    continue;
                }
                final com.minecolonies.api.colony.IColony owner = IColonyManager.getInstance()
                        .getColonyByPosFromWorld(other, plan.controllerPos());
                if (isEntitledToBlueprint(plan, owner, player) && seen.add(RoadNames.fileName(plan))) {
                    names.add(net.minecraft.nbt.StringTag.valueOf(RoadNames.fileName(plan)));
                }
            }
        }
        return names;
    }

    /**
     * Who may receive the blueprint file of a road.
     *
     * <p>Ownership has exactly two forms, and colony ownership wins: a road
     * standing on colony land belongs to that colony, <b>not</b> to whoever drew
     * it. So the original creator keeps no special right there — if the colony
     * grew over their wilderness road, the road is the colony's now.</p>
     *
     * <ul>
     *   <li>Colony roads: whoever holds {@code MANAGE_HUTS} in that colony, i.e.
     *       the people who can actually authorise work there.</li>
     *   <li>Wilderness roads: the creator only. Never a colony manager from
     *       somewhere else. Roads planned before the creator field existed are
     *       unowned, and unowned means public.</li>
     * </ul>
     */
    private static boolean isEntitledToBlueprint(final RoadPlan plan,
                                                 final com.minecolonies.api.colony.IColony owner,
                                                 final ServerPlayer player) {
        return com.YLFXM.rp.crossroads.RoadPermissions.canReceiveBlueprint(plan, owner, player);
    }

    /**
     * Whether {@code player} is allowed to know that {@code plan} exists.
     *
     * <p>Colony roads need {@code ACCESS_HUTS} in the owning colony — the same
     * bit the original uses to let a player into a hut at all. Do <b>not</b>
     * replace that with a "member of a colony" test: MineColonies has no such
     * notion, and {@code Permissions#getPlayers()} is a rank roster that also
     * holds <em>hostile</em> players (a colony adds an attacker's owner with
     * {@code HOSTILE_RANK_ID}, see {@code EntityCitizen}), so using "is in the
     * roster" as "is a member" would hand a raider the colony's whole road
     * index.</p>
     *
     * <p>Wilderness roads have no colony to consult, and an unbuilt plan would
     * otherwise reveal where somebody intends to build, so they are limited to
     * their creator. Roads planned before the creator field existed are unowned,
     * and unowned means public.</p>
     */
    private static boolean isVisibleTo(final RoadPlan plan,
                                       final com.minecolonies.api.colony.IColony owner,
                                       final ServerPlayer player) {
        return com.YLFXM.rp.crossroads.RoadPermissions.canSeeRoad(plan, owner, player);
    }

    private Network() {
    }
}
