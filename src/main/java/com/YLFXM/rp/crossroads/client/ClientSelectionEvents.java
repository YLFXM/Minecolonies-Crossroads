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
package com.YLFXM.rp.crossroads.client;

import com.YLFXM.rp.crossroads.road.RoadRules;

import com.YLFXM.rp.crossroads.ModRegistry;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(modid = com.YLFXM.rp.crossroads.Crossroads.MODID, value = Dist.CLIENT)
public final class ClientSelectionEvents {
    @SubscribeEvent
    public static void onLeftClickBlock(final PlayerInteractEvent.LeftClickBlock event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        if (!event.getEntity().getMainHandItem().is(ModRegistry.ROAD_WAND.get())) {
            return;
        }
        // A/B belong to the BOUND controller, so the lock is decided by that
        // plan — not by what the click happens to hit. Re-planning a built road
        // normally means clicking ground outside the old footprint, so testing
        // the clicked cell alone let the player keep re-picking A/B freely.
        final com.YLFXM.rp.crossroads.road.RoadPlan bound = boundPlan(event.getEntity());
        if (bound != null) RoadSelectionClient.bindTo(bound);
        if (bound != null && !RoadRules.canEdit(bound)) {
            com.YLFXM.rp.crossroads.road.RoadNotices.actionBar(event.getEntity(),
                    "rp_crossroads.road.locked");
            event.setCanceled(true);
            return;
        }
        // Shift+left is the B key, plain left the A key — but on an existing road
        // the POSITION decides which end a point belongs to (see below), so the
        // key only expresses an intent that may get swapped.
        boolean assignToB = event.getEntity().isShiftKeyDown();
        final BlockPos pos = event.getPos();

        if (bound != null && bound.state() != com.YLFXM.rp.crossroads.road.RoadState.DRAFT) {
            final net.minecraft.core.Direction.Axis axis = bound.axis();
            final boolean alongX = axis == net.minecraft.core.Direction.Axis.X;

            // Off-axis points are refused right here, at the click, instead of
            // letting them through and failing later when the blueprint is built.
            final int lateralOrigin = alongX ? bound.anchorPos().getZ() : bound.anchorPos().getX();
            if (!com.YLFXM.rp.crossroads.road.RoadReplanning.pointOnLine(bound, pos)) {
                com.YLFXM.rp.crossroads.road.RoadNotices.actionBar(event.getEntity(),
                        "rp_crossroads.select.on_axis", alongX ? "X" : "Z", alongX ? "z" : "x", lateralOrigin);
                event.setCanceled(true);
                return;
            }

            // Snap to the stored orientation: a point on A's side of the anchor
            // always becomes A, whichever key was pressed. That keeps travelDir
            // (and the edited-cell frame) stable and makes click order irrelevant.
            final int anchorAlong = alongX ? bound.anchorPos().getX() : bound.anchorPos().getZ();
            final int along = alongX ? pos.getX() : pos.getZ();
            final int side = Integer.signum(along - anchorAlong);
            final int aSide = Integer.signum(bound.alongOfA() - anchorAlong);
            if (side != 0 && aSide != 0) {
                assignToB = side != aSide;
            }
        }

        if (assignToB) {
            RoadSelectionClient.setB(pos);
            com.YLFXM.rp.crossroads.road.RoadNotices.actionBar(event.getEntity(),
                    "rp_crossroads.select.endpoint_b", pos.getX(), pos.getY(), pos.getZ());
        } else {
            RoadSelectionClient.setA(pos);
            com.YLFXM.rp.crossroads.road.RoadNotices.actionBar(event.getEntity(),
                    "rp_crossroads.select.endpoint_a", pos.getX(), pos.getY(), pos.getZ());
        }
        event.setCanceled(true);
    }

    /**
     * Shift + right-click with the Wand cycles the aimed cell's unit type:
     * {@code FLAT → STAIR_UP → STAIR_DOWN → FLAT}.
     *
     * <p>Only fires when the aimed block really belongs to a synced road, and
     * never on a decoration controller — that cell keeps the bind / open-panel
     * gesture. The click is claimed only in that case, so a shift-right-click on
     * ordinary ground still does whatever it normally does.</p>
     */
    @SubscribeEvent
    public static void onRightClickBlock(final PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        if (!event.getEntity().isShiftKeyDown()) {
            return;
        }
        if (!event.getEntity().getMainHandItem().is(ModRegistry.ROAD_WAND.get())
                && !event.getEntity().getOffhandItem().is(ModRegistry.ROAD_WAND.get())) {
            return;
        }
        final BlockPos pos = event.getPos();
        if (event.getLevel().getBlockState(pos).getBlock()
                instanceof com.minecolonies.core.blocks.BlockDecorationController) {
            return;
        }
        com.YLFXM.rp.crossroads.road.RoadPlan target = boundPlan(event.getEntity());
        if (target != null) RoadSelectionClient.bindTo(target);
        if (target != null && !RoadRules.canEdit(target)) {
            com.YLFXM.rp.crossroads.road.RoadNotices.actionBar(event.getEntity(),
                    "rp_crossroads.road.locked");
            event.setCanceled(true);
            return;
        }
        // Same predicate the server uses to reject the edit: a road that can still create
        // its shape (DRAFT, no session) needs both endpoints. Asking "state == DRAFT" let
        // the two sides disagree and the edit failed silently.
        if (target != null && RoadRules.canCreate(target) && !RoadSelectionClient.hasBoth()) {
            com.YLFXM.rp.crossroads.road.RoadNotices.actionBar(event.getEntity(),
                    "rp_crossroads.select.endpoints_first");
            event.setCanceled(true); return;
        }
        if (target == null) {
            com.YLFXM.rp.crossroads.Crossroads.LOGGER.info(
                    "Road edit gesture ignored: no road covers {} ({} roads synced)",
                    pos, com.YLFXM.rp.crossroads.network.ClientRoadCache.roads().size());
            return;
        }
        com.YLFXM.rp.crossroads.Crossroads.LOGGER.info(
                "Road edit gesture: {} -> plan {} (state {}, edit {})",
                pos, target.id(), target.state(), target.allowBlueprintEdit());
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.YLFXM.rp.crossroads.network.EditRoadCellPayload(target.controllerPos(), pos,
                        RoadSelectionClient.a(), RoadSelectionClient.b(), RoadSelectionClient.mirror(), target.id()));
        event.setUseBlock(net.neoforged.neoforge.common.util.TriState.FALSE);
        event.setUseItem(net.neoforged.neoforge.common.util.TriState.FALSE);
        event.setCanceled(true);
    }

    /** Road the player's Wand is bound to, or null when it is bound to nothing. */
    private static com.YLFXM.rp.crossroads.road.RoadPlan boundPlan(
            final net.minecraft.world.entity.player.Player player) {
        return com.YLFXM.rp.crossroads.network.ClientRoadCache.findByController(boundController(player));
    }

    private static BlockPos boundController(final net.minecraft.world.entity.player.Player player) {
        return com.YLFXM.rp.crossroads.road.RoadWandBinding.heldController(player.getInventory());
    }

    private ClientSelectionEvents() {
    }
}
