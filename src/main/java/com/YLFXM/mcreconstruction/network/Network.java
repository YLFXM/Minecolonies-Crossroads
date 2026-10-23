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
package com.YLFXM.mcreconstruction.network;

import com.YLFXM.mcreconstruction.road.RoadRules;
import com.YLFXM.mcreconstruction.road.RoadReplanning;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.permissions.Action;
import com.YLFXM.mcreconstruction.Config;
import com.YLFXM.mcreconstruction.MCReconstruction;
import com.YLFXM.mcreconstruction.ModRegistry;
import com.YLFXM.mcreconstruction.RoadWorkOrderRecovery;
import com.YLFXM.mcreconstruction.road.RoadBlueprintGenerator;
import com.YLFXM.mcreconstruction.road.RoadBlueprintStore;
import com.YLFXM.mcreconstruction.road.RoadBuildWatcher;
import com.YLFXM.mcreconstruction.road.RoadDataCleaner;
import com.YLFXM.mcreconstruction.road.RoadManager;
import com.YLFXM.mcreconstruction.road.RoadRepair;
import com.YLFXM.mcreconstruction.road.RoadNames;
import com.YLFXM.mcreconstruction.road.RoadPlacementMode;
import com.YLFXM.mcreconstruction.road.RoadPlan;
import com.YLFXM.mcreconstruction.road.RoadPlanBuilder;
import com.YLFXM.mcreconstruction.road.RoadQuickBuilder;
import com.YLFXM.mcreconstruction.road.RoadSegment;
import com.YLFXM.mcreconstruction.road.RoadState;
import com.YLFXM.mcreconstruction.road.RoadTemporaryPack;
import com.YLFXM.mcreconstruction.road.RoadWorkOrderService;
import com.YLFXM.mcreconstruction.road.SegmentKind;
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

public final class Network {
    /** The item the native build tool is bound to; see Structurize's ModItems. */
    private static final net.minecraft.world.item.Item BUILD_TOOL =
            net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("structurize", "sceptergold"));

    /**
     * Rendering-cache slot the native build-tool window renders into.
     *
     * <p>Hard-coded to {@code "blueprint"} because that is what
     * {@code WindowExtendedBuildTool} passes as its blueprint id to
     * {@code AbstractBlueprintManipulationWindow}. Our own planning ghost uses a
     * different key, so the two never fight over one slot.</p>
     */
    private static final String BUILD_TOOL_PREVIEW_KEY = "blueprint";

    public static void register(final RegisterPayloadHandlersEvent event) {
        final var registrar = event.registrar("3");
        registrar.playToServer(RequestRoadsPayload.TYPE, RequestRoadsPayload.CODEC, Network::handleRequest);
        registrar.playToServer(ConfigureRoadPayload.TYPE, ConfigureRoadPayload.CODEC, Network::handleConfigure);
        registrar.playToServer(RoadBulkActionPayload.TYPE, RoadBulkActionPayload.CODEC, Network::handleBulkAction);
        registrar.playToServer(RoadControllerActionPayload.TYPE, RoadControllerActionPayload.CODEC,
                Network::handleControllerAction);
        registrar.playToServer(NativeBuildClosedPayload.TYPE, NativeBuildClosedPayload.CODEC,
                Network::handleNativeBuildClosed);
        registrar.playToServer(CancelWorkOrdersPayload.TYPE, CancelWorkOrdersPayload.CODEC,
                Network::handleCancelWorkOrders);
        registrar.playToServer(RequestRoadPreviewPayload.TYPE, RequestRoadPreviewPayload.CODEC,
                Network::handlePreviewRequest);
        registrar.playToServer(EditRoadCellPayload.TYPE, EditRoadCellPayload.CODEC,
                Network::handleEditCell);
        registrar.playToServer(ToggleBlueprintEditPayload.TYPE, ToggleBlueprintEditPayload.CODEC,
                Network::handleToggleBlueprintEdit);
        registrar.playToClient(RoadSyncPayload.TYPE, RoadSyncPayload.CODEC, Network::handleSync);
        registrar.playToClient(OpenRoadPlanPayload.TYPE, OpenRoadPlanPayload.CODEC, Network::handleOpenPlan);
        registrar.playToClient(BindRoadWandPayload.TYPE, BindRoadWandPayload.CODEC, Network::handleBindWand);
        registrar.playToClient(OpenBuildToolPayload.TYPE, OpenBuildToolPayload.CODEC, Network::handleOpenBuildTool);
        registrar.playToClient(WorkOrderListPayload.TYPE, WorkOrderListPayload.CODEC, Network::handleWorkOrderList);
        registrar.playToClient(RoadPreviewPayload.TYPE, RoadPreviewPayload.CODEC, Network::handlePreview);
        registrar.playToClient(RoadRemovedPayload.TYPE, RoadRemovedPayload.CODEC, (payload, context) -> context.enqueueWork(() -> {
            final var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level == null || !mc.level.dimension().location().toString().equals(payload.dimension())) return;
            ClientRoadCache.remove(payload.roadId());
            com.YLFXM.mcreconstruction.client.RoadSelectionClient.removed(payload.roadId());
            if (mc.player != null) for (final var stack : com.YLFXM.mcreconstruction.road.RoadWandBinding.wands(mc.player.getInventory())) {
                if (payload.controller().equals(com.YLFXM.mcreconstruction.road.RoadWandBinding.getBoundController(stack))) {
                    com.YLFXM.mcreconstruction.road.RoadWandBinding.unbind(stack);
                }
            }
        }));
    }

    /**
     * Generates the blueprint for a selection that is still being planned and
     * sends it back for the ghost preview.
     *
     * <p>Nothing is persisted: the plan is built, turned into a blueprint and
     * thrown away, so previewing a road cannot create one. Any failure — no
     * permission, endpoints that do not form a valid road, generation problems —
     * simply sends no answer, and the client keeps showing the previous preview
     * rather than flashing an empty one.</p>
     */
    private static void handlePreview(final RoadPreviewPayload payload, final IPayloadContext context) {
        context.enqueueWork(() ->
                com.YLFXM.mcreconstruction.client.ClientRoadPreviewRenderer.accept(payload));
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
            if (!com.YLFXM.mcreconstruction.RoadPermissions.canManage(player, colony)) {
                return;
            }

            final RoadPlan boundPlan = RoadManager.get(level).findByController(controllerPos);
            if (boundPlan == null || !boundPlan.id().equals(payload.roadId())) return;
            if (boundPlan != null && (!canManageRoad(level, player, boundPlan)
                    || !RoadRules.canEdit(boundPlan) || findBoundWand(player, controllerPos) == null)) {
                MCReconstruction.LOGGER.info("Road preview refused: bound/state/permission check at {}", controllerPos);
                return;
            }
            try {
                if (boundPlan != null) RoadReplanning.validate(boundPlan, payload.a(), payload.b());
            } catch (final IllegalArgumentException ex) {
                MCReconstruction.LOGGER.info("Road preview refused: {}", ex.getMessage());
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
            final Direction facing = directionBetween(a, b);

            RoadPlan plan;
            if (existing != null
                    && geometryDiffers(existing, a, b, mirror,
                            Math.max(0, Math.min(8, payload.lampSpacing())))) {
                final RoadPlan rebuilt = rebuildPlannedRoad(level, player, payload, existing);
                if (rebuilt == null) {
                    return;
                }
                plan = rebuilt;
                sendRoads(player, colony);
            } else if (existing != null) {
                plan = existing;
            } else {
                try {
                    final RoadPlanBuilder.Result result = RoadPlanBuilder.build(level, boundPlan == null ? payload.anchorPos() : boundPlan.anchorPos(),
                            controllerPos, facing, a, b,
                            com.YLFXM.mcreconstruction.CustomRoadPack.STYLE_PACK, "street",
                            width, Math.min(2, width), effectiveWidthSide,
                            Math.max(0, payload.lampSpacing()));
                    plan = result.plan();
                    plan.setMirror(mirror);
                    if (boundPlan.axis() == plan.axis() || boundPlan.segments().isEmpty()) {
                        plan.adoptCommittedRows(boundPlan.rowOverrides()); plan.adoptPendingRows(boundPlan.pendingRows());
                    }
                } catch (final IllegalArgumentException ex) {
                    // Selection is not a road yet (diagonal, too long, anchor off
                    // the line). The client keeps its last preview; no chat noise.
                    return;
                }
            }

            final Blueprint blueprint = RoadBlueprintGenerator.generate(plan, level,
                    Config.defaultLampReserveHeight());
            if (blueprint == null) {
                return;
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
            // One line per preview (the client de-duplicates, so this is not
            // per-frame). It is the quickest way to tell a content problem from
            // a placement problem: the ghost starts at origin - primaryOffset.
            MCReconstruction.LOGGER.info(
                    "Road preview {}: origin={} size={}x{}x{} primaryOffset={}",
                    plan.id(), origin, blueprint.getSizeX(), blueprint.getSizeY(),
                    blueprint.getSizeZ(), blueprint.getPrimaryBlockOffset());
            PacketDistributor.sendToPlayer(player, new RoadPreviewPayload(
                    com.ldtteam.structurize.blueprints.v1.BlueprintUtil.writeBlueprintToNBT(blueprint),
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
        RoadBlueprintStore.preserveBuilt(level, old);
        RoadBuildWatcher.stop(old);
        RoadReplanning.inherit(old, plan, player.getUUID(), false);
        plan.setMirror(mirror);
        reportRemap(player, old, plan);

        final Blueprint blueprint = RoadBlueprintGenerator.generate(plan, level,
                Config.defaultLampReserveHeight());
        if (blueprint == null) {
            return null;
        }

        com.YLFXM.mcreconstruction.road.RoadDataCleaner.releaseWorkOrders(old);
        com.YLFXM.mcreconstruction.road.RoadTemporaryPack.replace(level, old, plan, blueprint);
        RoadManager.get(level).remove(old.id());
        RoadManager.get(level).add(plan);
        com.YLFXM.mcreconstruction.road.RoadBlueprintStore.save(level, plan, blueprint);
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
            if (!com.YLFXM.mcreconstruction.RoadPermissions.canManage(player, colony)) {
                MCReconstruction.LOGGER.info("Road cell edit denied for {} at {} (no manage-huts)",
                        player.getName().getString(), controllerPos);
                return;
            }
            if (findBoundWand(player, controllerPos) == null) {
                MCReconstruction.LOGGER.info("Road cell edit ignored: wand not bound to {} (player {})",
                        controllerPos, player.getName().getString());
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "Road Wand is not bound to this controller."), false);
                return;
            }
            RoadPlan plan = RoadManager.get(level).findByController(controllerPos);
            if (plan == null || !plan.id().equals(payload.roadId())) {
                MCReconstruction.LOGGER.info("Road cell edit ignored: no plan at {}", controllerPos);
                return;
            }
            if (!canManageRoad(level, player, plan) || !RoadRules.canEdit(plan)) {
                MCReconstruction.LOGGER.info("Road cell edit refused: road {} is locked or under construction", plan.id());
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "This road is locked or under construction."), false);
                return;
            }
            RoadBlueprintStore.preserveBuilt(level, plan);
            final boolean draft = RoadRules.canCreate(plan);
            if (draft) {
                if (payload.a().equals(BlockPos.ZERO) || payload.b().equals(BlockPos.ZERO)) return;
                try {
                    final RoadPlan oldDraft = plan;
                    plan = RoadPlanBuilder.build(level, oldDraft.anchorPos(), controllerPos, oldDraft.controllerFacing(),
                            payload.a(), payload.b(), oldDraft.pack(), oldDraft.variant(), oldDraft.width(),
                            oldDraft.walkRange().width(), payload.mirror() ? -1 : 1, oldDraft.lampSpacing()).plan();
                    RoadReplanning.inherit(oldDraft, plan, player.getUUID(), true);
                    plan.setMirror(payload.mirror());
                    if (!oldDraft.segments().isEmpty() && oldDraft.axis() != plan.axis()) {
                        plan.clearRowOverrides();
                        player.displayClientMessage(net.minecraft.network.chat.Component.literal("Draft axis changed; row edits cleared."), false);
                    }
                } catch (final IllegalArgumentException ex) {
                    player.displayClientMessage(net.minecraft.network.chat.Component.literal(ex.getMessage()), true);
                    return;
                }
            }
            final Integer row = plan.rowAt(payload.cellPos());
            if (row == null) {
                MCReconstruction.LOGGER.info(
                        "Road row edit refused: {} is outside plan {} (anchor {} width {} side {} along {}..{})",
                        payload.cellPos(), plan.id(), plan.anchorPos(), plan.width(), plan.widthSide(),
                        plan.minAlong(), plan.maxAlong());
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "That block is not part of this road."), false);
                return;
            }
            final com.YLFXM.mcreconstruction.road.CellType current = plan.cellType(row);
            final com.YLFXM.mcreconstruction.road.CellType next = switch (current) {
                case FLAT -> com.YLFXM.mcreconstruction.road.CellType.STAIR_UP;
                case STAIR_UP -> com.YLFXM.mcreconstruction.road.CellType.STAIR_DOWN;
                case STAIR_DOWN -> com.YLFXM.mcreconstruction.road.CellType.FLAT;
            };
            RoadBuildWatcher.stop(plan);
            plan.setRowType(row, next);

            final Blueprint blueprint = RoadBlueprintGenerator.generate(plan, level,
                    Config.defaultLampReserveHeight());
            if (blueprint == null) {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "Road blueprint generation failed."), false);
                return;
            }
            // The blueprint no longer matches the world, so the road is back to
            // "planned": PLANNED means "there is a shape waiting to be applied",
            // and only applying it (Repair / build tool / builder) makes it BUILT
            // again. No completion watch is armed here, because nothing is being
            // placed — that is what used to make the state flip back on its own.
            plan.setState(draft ? RoadState.DRAFT : RoadState.PLANNED);
            com.YLFXM.mcreconstruction.road.RoadTemporaryPack.replace(level, plan, plan, blueprint);
            com.YLFXM.mcreconstruction.road.RoadBlueprintStore.save(level, plan, blueprint);
            RoadManager.get(level).add(plan);
            sendRoads(player, colony);
            MCReconstruction.LOGGER.info("Road {} row {} edited by {}: -> {}",
                    plan.id(), row, player.getName().getString(), next);
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "Row " + row + ": " + next), true);
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
            if (!com.YLFXM.mcreconstruction.RoadPermissions.canManage(player, colony)) {
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
            MCReconstruction.LOGGER.info("Road {} edit switch -> {} (by {})",
                    plan.id(), payload.allow(), player.getName().getString());
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    payload.allow() ? "Blueprint editing enabled." : "Blueprint editing locked."), true);
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
            // controller on anything longer than a few blocks. The plan is
            // already authorized by the wand binding below, and the segment is
            // bounded by Config.maxRoadLength.

            final RoadPlacementMode mode = payload.mode();
            final boolean creativeInstant = mode != RoadPlacementMode.SURVIVAL
                    && mode != RoadPlacementMode.PLAN
                    && Config.creativeInstantBuild() && player.isCreative();
            // Permission is about authority, not cost. RoadPermissions mirrors
            // MineColonies' own check. No chat message on denial: the colony
            // permission system already reports it to the player.
            final IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(level, controllerPos);
            if (!com.YLFXM.mcreconstruction.RoadPermissions.canManage(player, colony)) {
                MCReconstruction.LOGGER.debug("Denied road configure for {} at {} (no manage-huts)",
                        player.getName().getString(), controllerPos);
                return;
            }
            if (mode == RoadPlacementMode.SURVIVAL && colony == null) {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "No colony covers this road; a builder order needs a colony."), false);
                return;
            }

            final RoadPlan old = RoadManager.get(level).findByController(controllerPos);
            if (old == null) {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "No road plan is bound to this controller. Right-click it with the Road Wand first."), false);
                return;
            }
            if (findBoundWand(player, controllerPos) == null) {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "Road Wand is not bound to this controller."), false);
                return;
            }
            if (!canManageRoad(level, player, old)
                    || !(mode == RoadPlacementMode.PLAN ? RoadRules.canCreate(old) : RoadRules.canEdit(old))) {
                MCReconstruction.LOGGER.info("Road configure refused: plan={} state={} mode={}", old.id(), old.state(), mode);
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "This road is locked; confirmed roads are applied with Repair."), false);
                return;
            }
            // Road width is determined by the style-pack blueprint, not by a
            // free-form UI value. Reuse the plan's width.
            final int width = Math.max(1, old.width());
            // Lamp spacing is the one construction value the player edits in the
            // Wand panel, so it comes from the payload rather than the old plan.
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
            final String pack = old != null ? old.pack() : com.YLFXM.mcreconstruction.CustomRoadPack.STYLE_PACK;
            final String variant = old != null ? old.variant() : "street";
            final int widthSide = old != null ? old.widthSide() : 1;
            final boolean mirror = payload.mirror();

            try {
                RoadReplanning.validate(old, payload.hasEndpoints() ? a : null, payload.hasEndpoints() ? b : null);
            } catch (final IllegalArgumentException ex) {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(ex.getMessage()), false);
                MCReconstruction.LOGGER.info("Road configure refused: {}", ex.getMessage());
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
                            width, com.YLFXM.mcreconstruction.road.WalkRange.centered(width, Math.min(2, width)),
                            effectiveWidthSide, lampSpacing);
                    // Surface is the anchor's block minus one, same as the
                    // A/B path: endpoints never decide the height.
                    plan.addSegment(new RoadSegment(anchorPos.below(), length, axis, 0, SegmentKind.ROAD));
                    plan.setTravelDir(facing.getAxisDirection().getStep());
                    plan.setState(RoadState.PLANNED);
                    intersections = 0;
                }
            } catch (final IllegalArgumentException ex) {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "Road endpoints invalid: " + ex.getMessage()), false);
                return;
            }

            RoadBlueprintStore.preserveBuilt(level, old);
        RoadBuildWatcher.stop(old);
            RoadReplanning.inherit(old, plan, player.getUUID(), mode == RoadPlacementMode.CONFIGURE);
            if (RoadRules.canCreate(old) && !old.segments().isEmpty() && old.axis() != plan.axis()) {
                plan.clearRowOverrides();
                player.displayClientMessage(net.minecraft.network.chat.Component.literal("Draft axis changed; row edits cleared."), false);
            }
            reportRemap(player, old, plan);
            if (!old.displayName().isBlank()) {
                plan.setDisplayName(old.displayName());
            }
            if (plan.displayName().isEmpty()) {
                final RoadNames.Name generated = RoadNames.generate(level, plan.controllerPos(), player);
                plan.setRoadName(generated.base());
                plan.setDisplayName(generated.display());
            }

            final Blueprint blueprint = RoadBlueprintGenerator.generate(plan, level,
                    Config.defaultLampReserveHeight());
            if (blueprint == null) {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "Road blueprint generation failed."), false);
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
            final long packVersion = RoadTemporaryPack.replace(level, old, plan, blueprint);

            if (old != null) {
                RoadManager.get(level).remove(old.id());
            }

            if (mode == RoadPlacementMode.CONFIGURE) {
                // Settings only: keep the plan (and its geometry) but change the
                // lamp spacing. No work order, no window, nothing placed — the
                // client re-requests the preview as soon as the synced plan
                // arrives, so the ghost redraws with the new spacing.
                RoadBlueprintStore.save(level, plan, blueprint);
                // No completion watch here: the world is untouched, so there is
                // nothing to wait for. Repair arms one when it applies the shape.
                RoadManager.get(level).add(plan);
                sendRoads(player, colony);
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "Lamp spacing set to " + plan.lampSpacing()), false);
                return;
            }

            if (mode == RoadPlacementMode.PLAN) {
                RoadBlueprintStore.save(level, plan, blueprint);
                RoadManager.get(level).add(plan);
                if (old.state() == com.YLFXM.mcreconstruction.road.RoadState.DRAFT) {
                    // This is the road's first (and only) commit. If the tool is
                    // closed without building, the watcher puts it back in DRAFT so
                    // the button that opened the tool is usable again.
                    RoadBuildWatcher.noteOpenedFromDraft(plan);
                }
                RoadBuildWatcher.watch(level, plan);
                PacketDistributor.sendToPlayer(player, new OpenBuildToolPayload(
                        plan.id(), RoadTemporaryPack.pathFor(plan),
                        com.ldtteam.structurize.blueprints.v1.BlueprintUtil.writeBlueprintToNBT(blueprint),
                        packVersion, plan.anchorPos()));
                sendRoads(player, colony);
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "Road " + RoadNames.label(plan)
                                + " planned. Choose Complete/Pretty/Survival in the native build tool."), false);
                return;
            }

            if (creativeInstant) {
                com.YLFXM.mcreconstruction.road.RoadBlueprintStore.save(level, plan, blueprint);
                RoadQuickBuilder.place(level, plan, blueprint, mode == RoadPlacementMode.PRETTY);
                // Complete/Pretty write the road body over the old controller
                // when it sat inside the footprint, and never move it. Recreate
                // it on the reserved inner cell with the same migration the
                // builder path uses. The legacy custom controller is no longer
                // placed here.
                RoadBuildWatcher.markBuilt(level, plan);
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "Road " + RoadNames.label(plan) + ": bounds "
                                + plan.bounds(Config.defaultLampReserveHeight()).sizeX() + "x"
                                + plan.bounds(Config.defaultLampReserveHeight()).sizeY() + "x"
                                + plan.bounds(Config.defaultLampReserveHeight()).sizeZ()
                                + ", intersections " + intersections + ", placed instantly (" + mode + ")."), false);
            } else {
                RoadWorkOrderService.createAndAdd(level, plan, blueprint, colony);
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "Road " + RoadNames.label(plan) + ": bounds "
                                + plan.bounds(Config.defaultLampReserveHeight()).sizeX() + "x"
                                + plan.bounds(Config.defaultLampReserveHeight()).sizeY() + "x"
                                + plan.bounds(Config.defaultLampReserveHeight()).sizeZ()
                                + ", intersections " + intersections + ", work order created."), false);
            }

            // Push the new state (BUILT / BUILDING) so the client panel and
            // preview do not keep using the pre-build DRAFT/PLANNED plan.
            sendRoads(player, colony);
        });
    }

    private static void reportRemap(final ServerPlayer player, final RoadPlan old, final RoadPlan plan) {
        if (RoadRules.canCreate(old)) return;
        final boolean changedRange = old.minAlong() != plan.minAlong() || old.maxAlong() != plan.maxAlong();
        final int discarded = Math.max(0, old.editedRowCount() - plan.editedRowCount());
        if (!changedRange && discarded == 0) return;
        player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                "Re-plan: " + plan.editedRowCount() + " row(s) edited, " + plan.lastDroppedRows()
                        + " dropped. Repair applies it."
                        + (discarded > 0 ? " " + discarded + " pending row(s) outside the road discarded." : "")), false);
    }

    private static net.minecraft.world.item.ItemStack findBoundWand(final ServerPlayer player,
                                                                    final BlockPos controllerPos) {
        final var inventory = player.getInventory();
        final var bound = com.YLFXM.mcreconstruction.road.RoadWandBinding.findBound(inventory, controllerPos);
        if (bound != null) return bound;
        final String key = "mcreconstruction:road_wand_controller";
        if (player.getPersistentData().contains(key)
                && player.getPersistentData().getLong(key) == controllerPos.asLong()) {
            final var wands = com.YLFXM.mcreconstruction.road.RoadWandBinding.wands(inventory);
            if (!wands.isEmpty()) return wands.get(0);
        }
        return null;
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
                final BlockPos townHall = com.YLFXM.mcreconstruction.TownHallBinding.get(stack);
                final IColony scope = townHall == null ? null : IColonyManager.getInstance().getColonyByPosFromWorld(player.serverLevel(), townHall);
                sendSurveyorRoads(player, scope, scope == null);
            } else sendRoads(player);
            MCReconstruction.LOGGER.debug("Sent road index to {}", player.getName().getString());
        });
    }

    private static void handleBulkAction(final RoadBulkActionPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            final ServerLevel level = player.serverLevel();
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
                RoadDataCleaner.clear(level, plan, payload.removeBlocks());
                cleared++;
            }
            sendRoads(player, scope);
            final int count = cleared;
            if (denied > 0) {
                // No permission prompt in chat: the colony system already covers
                // it. Keep the count in the debug log for diagnosis.
                MCReconstruction.LOGGER.debug("Skipped {} road(s) for {} (no manage-huts)",
                        denied, player.getName().getString());
            }
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "Processed " + count + " road(s)."), false);
        });
    }

    /** Permission for destructive road actions: manage-huts in the owning colony. */
    private static boolean canManageRoad(final ServerLevel level, final ServerPlayer player,
                                         final RoadPlan plan) {
        return com.YLFXM.mcreconstruction.RoadPermissions.canManage(level, player, plan);
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
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "No road plan on this controller."), false);
                return;
            }
            if (!canManageRoad(level, player, plan)) {
                MCReconstruction.LOGGER.debug("Denied controller action {} for {} (no manage-huts)",
                        payload.action(), player.getName().getString());
                return;
            }
            switch (payload.action()) {
                case REMOVE -> com.YLFXM.mcreconstruction.road.RoadRemover.remove(level, plan);
                case REPAIR -> { if (RoadRules.canRepair(plan)) RoadRepair.repair(level, plan); }
                case UPGRADE -> player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "Road upgrades are not implemented yet."), false);
            }
            sendRoads(player, IColonyManager.getInstance()
                    .getColonyByPosFromWorld(level, plan.controllerPos()));
        });
    }

    private static void handleSync(final RoadSyncPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientRoadCache.set(payload.data());
            // Drop local blueprint copies the server no longer vouches for. This
            // only ever removes files under generated/ that this client is not
            // supposed to see, never anything the player is entitled to.
            final ListTag allowed = payload.data().getList("blueprints", 8);
            final java.util.Set<String> keep = new java.util.HashSet<>();
            for (int i = 0; i < allowed.size(); i++) {
                keep.add(allowed.getString(i));
            }
            com.YLFXM.mcreconstruction.client.ClientRoadPackWriter.retainOnly(keep);
        });
    }

    private static void handleBindWand(final BindRoadWandPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            final var player = net.minecraft.client.Minecraft.getInstance().player;
            if (player != null) {
                com.YLFXM.mcreconstruction.road.RoadWandBinding.rebind(
                        player.getInventory(), payload.previousPos(), payload.controllerPos());
            }
        });
    }

    private static void handleOpenBuildTool(final OpenBuildToolPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            final var minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft.level == null || minecraft.player == null) {
                return;
            }
            // Nothing to build with. Opening the native tool anyway would put a
            // panel in front of a player who cannot place anything (the placement
            // path itself never checks the held item), so say so and stop before
            // any client state is touched -- no pack written, no pack switched.
            if (findBuildToolSlot(minecraft.player.getInventory()) < 0) {
                minecraft.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "No build tool in your inventory."), false);
                return;
            }
            // The server pushes the blueprint with the payload: packs are only
            // synced on join, so a freshly generated one does not exist here yet.
            com.YLFXM.mcreconstruction.client.ClientRoadPackWriter.ensure(
                    payload.blueprintPath(), payload.blueprint(), payload.packVersion());
            // The selected pack is a global: whichever pack is selected when a
            // building is placed or a colony is created is the one MineColonies
            // records in the save. Switching to the road pack therefore has to be
            // undone as soon as the window closes, or the colony ends up with
            // "Reconstruction Plan" as its style pack and looks for
            // fundamentals/townhall1 inside our pack on every startup.
            final com.ldtteam.structurize.storage.StructurePackMeta previous =
                    com.ldtteam.structurize.storage.StructurePacks.getSelectedPack();
            com.YLFXM.mcreconstruction.client.ClientNativeBuildTracker.begin(
                    payload.planId(), previous == null ? null : previous.getName());
            com.ldtteam.structurize.storage.StructurePacks.switchSelectedPack(
                    com.YLFXM.mcreconstruction.CustomRoadPack.PACK_NAME);
            // The native window draws its own ghost for the same blueprint, so
            // ours has to go or the two would stack.
            com.YLFXM.mcreconstruction.client.ClientRoadPreviewRenderer.clear();
            // Where the window puts its box is decided once, from the preview
            // cache, and only when that cache has no position yet -- a position
            // left over from an earlier open would win over the anchor we pass.
            // Clearing it first makes the anchor below authoritative.
            com.ldtteam.structurize.storage.rendering.RenderingCache
                    .getOrCreateBlueprintPreviewData(BUILD_TOOL_PREVIEW_KEY)
                    .setPos(null);
            // The blueprint we just pushed may have the same name, file name,
            // path and volume as the previous one — a re-planned road keeps all
            // of them — and Blueprint.equals ignores the blocks entirely. The
            // native tool would therefore draw the previously cached mesh: same
            // shape as before, only moved. Drop the meshes so it is rebuilt from
            // the blueprint actually on disk.
            com.ldtteam.structurize.client.BlueprintHandler.getInstance().clearCache();
            // The anchor is the blueprint's primary block (the road controller's
            // support block). Passing null here makes Structurize fall back to
            // "ten blocks in front of the player", which is why the box used to
            // drift off in whatever direction the player was facing.
            new com.ldtteam.structurize.client.gui.WindowExtendedBuildTool(
                    payload.anchorPos(),
                    com.ldtteam.structurize.api.constants.Constants.GROUNDSTYLE_RELATIVE,
                    minecraft.level.registryAccess()).open();
            // Hand the player the tool they are about to build with. This is
            // presentation only: the placement path never checks the held item.
            selectBuildTool(minecraft);
        });
    }

    /**
     * First inventory slot holding the Structurize build tool, or -1.
     *
     * <p>Takes the {@code Inventory}, not the player: passing
     * {@code Minecraft.player} (a {@code LocalPlayer}) as an argument would make
     * the JVM verifier load that client-only class, which the dist cleaner
     * rejects on a dedicated server -- the whole mod fails to construct with a
     * {@code BootstrapMethodError} pointing at {@code Network::register}.</p>
     *
     * <p>Scans the whole inventory: slots 0-8 are the hotbar and can simply be
     * selected, anything above has to be swapped into the selected slot to be
     * reachable at all.</p>
     */
    private static int findBuildToolSlot(final net.minecraft.world.entity.player.Inventory inventory) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(BUILD_TOOL)) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Points the player's selected hotbar slot at the Structurize build tool.
     *
     * <p>If the tool is somewhere in the backpack it cannot be selected at all, so
     * it is swapped into the selected slot — the one case where items move. The
     * caller has already verified a tool exists.</p>
     */
    private static void selectBuildTool(final net.minecraft.client.Minecraft minecraft) {
        if (minecraft.player == null) {
            return;
        }
        final net.minecraft.world.entity.player.Inventory inventory = minecraft.player.getInventory();
        final int found = findBuildToolSlot(minecraft.player.getInventory());
        if (found < 0) {
            return;
        }
        if (found <= 8) {
            inventory.selected = found;
            return;
        }
        final int selected = inventory.selected;
        final net.minecraft.world.item.ItemStack displaced = inventory.getItem(selected);
        inventory.setItem(selected, inventory.getItem(found));
        inventory.setItem(found, displaced);
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
        if (requireView && !com.YLFXM.mcreconstruction.RoadPermissions.canView(player, colony)) {
            // Nothing is sent: the client opens the window only when this
            // payload arrives, so staying silent keeps it closed -- the same
            // outcome as vanilla denying a hut click.
            com.YLFXM.mcreconstruction.RoadPermissions.notifyDenied(player);
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
                final String status = !order.isClaimed()
                        ? "Unclaimed"
                        : "Claimed" + (stage == null ? "" : " / " + stage.name());
                String displayName = order.getDisplayName().getString();
                final RoadPlan road = com.YLFXM.mcreconstruction.road.RoadWorkOrders.find(player.serverLevel(), order);
                if (road != null) {
                    displayName = RoadNames.label(road);
                }
                entries.add(new WorkOrderListPayload.Entry(order.getID(),
                        order.getWorkOrderType() == null ? "UNKNOWN" : order.getWorkOrderType().name(),
                        displayName,
                        order.getLocation().toShortString(),
                        status,
                        order.getPriority(),
                        order.getCurrentLevel(),
                        order.getTargetLevel()));
            } catch (final RuntimeException ex) {
                MCReconstruction.LOGGER.warn("Skipping unreadable work order in colony {}",
                        colony.getID(), ex);
            }
        }
        entries.sort(java.util.Comparator.comparingInt(WorkOrderListPayload.Entry::priority)
                .thenComparingInt(WorkOrderListPayload.Entry::id));
        PacketDistributor.sendToPlayer(player, new WorkOrderListPayload(colony.getID(), colony.getName(),
                entries, true, com.YLFXM.mcreconstruction.RoadPermissions.canManage(player, colony)));
    }

    /** Unbound scroll: open an empty list that tells the player how to bind. */
    public static void sendUnboundWorkOrders(final ServerPlayer player) {
        PacketDistributor.sendToPlayer(player,
                new WorkOrderListPayload(-1, "", java.util.List.of(), false, false));
    }

    private static void handleWorkOrderList(final WorkOrderListPayload payload,
                                            final IPayloadContext context) {
        context.enqueueWork(() -> com.YLFXM.mcreconstruction.client.WindowWorkOrderList
                .show(payload.colonyId(), payload.colonyName(), payload.entries(),
                        payload.bound(), payload.canManage()));
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
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "That colony no longer exists."), false);
                return;
            }
            if (!com.YLFXM.mcreconstruction.RoadPermissions.canManage(player, colony)) {
                MCReconstruction.LOGGER.debug("Denied work order cancel for {} in colony {}",
                        player.getName().getString(), payload.colonyId());
                return;
            }
            int cancelled = 0;
            for (final int orderId : payload.orderIds()) {
                try {
                    if (colony.getWorkManager().getWorkOrders().containsKey(orderId)) {
                        colony.getWorkManager().removeWorkOrder(orderId);
                        cancelled++;
                    }
                } catch (final RuntimeException ex) {
                    MCReconstruction.LOGGER.warn("Could not cancel work order {} in colony {}",
                            orderId, payload.colonyId(), ex);
                }
            }
            final int count = cancelled;
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "Cancelled " + count + " work order(s)."), false);
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
            if (plan != null) {
                RoadBuildWatcher.requestClose(level, plan);
            }
        });
    }

    private static void handleOpenPlan(final OpenRoadPlanPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            com.YLFXM.mcreconstruction.client.RoadSelectionClient.setControllerPos(payload.controllerPos());
            new com.YLFXM.mcreconstruction.client.WindowRoadController(payload.controllerPos()).open();
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
        if (surveyView) tag.putString("surveyContext", com.YLFXM.mcreconstruction.RoadToolContext.key(player.getInventory()));
        tag.putString("dimension", level.dimension().location().toString());
        tag.putBoolean("bound", colony != null);
        tag.putString("colony", colony == null ? "" : colony.getName());
        final ListTag list = new ListTag();
        final ListTag surveyIds = new ListTag();
        // Which roads this player may act on; the client only uses it to grey out
        // buttons, every action is re-checked server side.
        final ListTag manageable = new ListTag();
        for (final RoadPlan plan : RoadManager.get(level).all()) {
            final com.minecolonies.api.colony.IColony owner = IColonyManager.getInstance()
                    .getColonyByPosFromWorld(level, plan.controllerPos());
            if (!isVisibleTo(plan, owner, player)) continue;
            final boolean inScope = colony != null
                    ? owner == null || owner.getID() == colony.getID()
                    : !wildernessOnly || owner == null;
            if (inScope) surveyIds.add(net.minecraft.nbt.StringTag.valueOf(plan.id().toString()));
            list.add(plan.save());
            if (canManageRoad(level, player, plan)) {
                manageable.add(net.minecraft.nbt.StringTag.valueOf(plan.id().toString()));
            }
        }
        tag.put("roads", list);
        tag.put("surveyIds", surveyIds);
        tag.put("manageable", manageable);
        // Every blueprint file name this player is entitled to, across all
        // dimensions. The client prunes anything else from its local
        // blueprints/Reconstruction Plan/generated/ so blueprints pushed in an
        // earlier session (or for a road the player lost access to) cannot
        // linger in the native build tool.
        tag.put("blueprints", entitledBlueprintNames(player));
        PacketDistributor.sendToPlayer(player, new RoadSyncPayload(tag));
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
        return com.YLFXM.mcreconstruction.RoadPermissions.canReceiveBlueprint(plan, owner, player);
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
        return com.YLFXM.mcreconstruction.RoadPermissions.canSeeRoad(plan, owner, player);
    }

    private Network() {
    }
}
