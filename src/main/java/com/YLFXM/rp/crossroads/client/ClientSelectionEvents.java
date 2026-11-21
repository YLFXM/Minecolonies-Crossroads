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
        // Terrain mode owns the corners: a terrain volume has nothing to do with
        // the bound road's axis, so none of the road checks below may run. This is
        // exactly what blocked picking ground off a built road's line.
        if (TerrainClientState.isTerrain()) {
            final BlockPos terrainPos = event.getPos();
            if (event.getEntity().isShiftKeyDown()) {
                RoadSelectionClient.setB(terrainPos);
            } else {
                RoadSelectionClient.setA(terrainPos);
            }
            // Keep A on the box's minimum corner. The blueprint's origin is the
            // anchor and a blueprint cannot grow towards negative coordinates, so
            // ordering the corners here is what lets origin and marker be the same
            // point no matter which corner was clicked first.
            final BlockPos first = RoadSelectionClient.a();
            final BlockPos second = RoadSelectionClient.b();
            if (!first.equals(BlockPos.ZERO) && !second.equals(BlockPos.ZERO)) {
                final BlockPos low = new BlockPos(Math.min(first.getX(), second.getX()),
                        Math.min(first.getY(), second.getY()), Math.min(first.getZ(), second.getZ()));
                final BlockPos high = new BlockPos(Math.max(first.getX(), second.getX()),
                        Math.max(first.getY(), second.getY()), Math.max(first.getZ(), second.getZ()));
                if (!low.equals(first)) {
                    RoadSelectionClient.setA(low);
                    RoadSelectionClient.setB(high);
                }
            }
            // Report the clicked cell, not the reordered corner: the box is what
            // matters and the labels only say which key was used.
            com.YLFXM.rp.crossroads.road.RoadNotices.actionBar(event.getEntity(),
                    event.getEntity().isShiftKeyDown() ? "rp_crossroads.select.endpoint_b" : "rp_crossroads.select.endpoint_a",
                    terrainPos.getX(), terrainPos.getY(), terrainPos.getZ());
            event.setCanceled(true);
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
     * Right-click gestures, in one place so their priority is readable:
     *
     * <ol>
     *   <li>controller block — panel (plain) or bind (shift), never a road edit;</li>
     *   <li>shift — cycle road → dig → fill, clearing A/B;</li>
     *   <li>dig/fill mode — turn the two corners into a terrain order;</li>
     *   <li>road mode — cycle the aimed row's unit type.</li>
     * </ol>
     *
     * <p>The wand has no window of its own any more, so every one of these is a
     * gesture; the server still re-validates whichever packet comes out.</p>
     */
    @SubscribeEvent
    public static void onRightClickBlock(final PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        final net.minecraft.world.entity.player.Player player = event.getEntity();
        if (!holdsWand(player)) {
            return;
        }
        final BlockPos pos = event.getPos();
        if (event.getLevel().getBlockState(pos).getBlock()
                instanceof com.minecolonies.core.blocks.BlockDecorationController) {
            // The controller keeps its own gestures: plain right-click asks the
            // server to open the panel, shift-right binds the wand. Neither may
            // be swallowed here, or the panel could never open.
            return;
        }
        if (player.isShiftKeyDown()) {
            cycleMode(player);
            claim(event);
            return;
        }
        if (TerrainClientState.isTerrain()) {
            // A terrain volume has nothing to do with the bound road's axis, so
            // no road check may run here — that is what used to block picking
            // ground off a built road's line.
            dispatchTerrain(player);
            claim(event);
            return;
        }
        editAimedRow(player, pos, event);
    }

    /**
     * Air right-click: mode cycling and the terrain dispatch, the same gestures
     * as on a block.
     *
     * <p>Both have to work without a block under the crosshair — looking at the
     * sky is exactly when the player wants to leave terrain mode — so this path
     * mirrors {@link #onRightClickBlock}: shift cycles the mode, dig/fill
     * dispatches the order, road mode does nothing. Only a real miss is handled:
     * when a block is in the crosshair the block path owns the gesture, and
     * handling both would fire it twice.</p>
     */
    @SubscribeEvent
    public static void onRightClickItem(final PlayerInteractEvent.RightClickItem event) {
        if (!event.getLevel().isClientSide() || !holdsWand(event.getEntity())) {
            return;
        }
        final net.minecraft.world.phys.HitResult hit = net.minecraft.client.Minecraft.getInstance().hitResult;
        if (hit != null && hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            return;
        }
        if (event.getEntity().isShiftKeyDown()) {
            cycleMode(event.getEntity());
            event.setCanceled(true);
            return;
        }
        if (!TerrainClientState.isTerrain()) {
            return;
        }
        dispatchTerrain(event.getEntity());
        event.setCanceled(true);
    }

    /**
     * Sends the two corners as a terrain order.
     *
     * <p>Both corners are needed before the server can build anything, and
     * saying so here beats a silent no-op. The mode rides along only as "fill or
     * not": the server resolves the colony and the volume from the box.</p>
     */
    private static void dispatchTerrain(final net.minecraft.world.entity.player.Player player) {
        if (!RoadSelectionClient.hasBoth()) {
            com.YLFXM.rp.crossroads.road.RoadNotices.actionBar(player,
                    "rp_crossroads.select.endpoints_first");
            return;
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.YLFXM.rp.crossroads.network.TerrainOrderPayload(
                        RoadSelectionClient.a(), RoadSelectionClient.b(),
                        TerrainClientState.mode() == TerrainClientState.Mode.FILL));
    }

    /**
     * Cycles road → dig → fill → road, clearing the corners each time.
     *
     * <p>Clearing is deliberate: corners picked for a road mean nothing as a
     * box (and the reverse), so carrying them over would make the next build
     * look like a leftover. The mode lives only on the client; the server learns
     * it from the request that follows.</p>
     */
    private static void cycleMode(final net.minecraft.world.entity.player.Player player) {
        final TerrainClientState.Mode next = switch (TerrainClientState.mode()) {
            case NONE -> TerrainClientState.Mode.DIG;
            case DIG -> TerrainClientState.Mode.FILL;
            case FILL -> TerrainClientState.Mode.NONE;
        };
        TerrainClientState.setMode(next);
        RoadSelectionClient.clear();
        com.YLFXM.rp.crossroads.road.RoadNotices.actionBar(player, switch (next) {
            case DIG -> "rp_crossroads.terrain.mode_dig";
            case FILL -> "rp_crossroads.terrain.mode_fill";
            case NONE -> "rp_crossroads.terrain.mode_road";
        });
    }

    /** Claims the click: no block use, no item use, nothing else may act on it. */
    private static void claim(final PlayerInteractEvent.RightClickBlock event) {
        event.setUseBlock(net.neoforged.neoforge.common.util.TriState.FALSE);
        event.setUseItem(net.neoforged.neoforge.common.util.TriState.FALSE);
        event.setCanceled(true);
    }

    private static boolean holdsWand(final net.minecraft.world.entity.player.Player player) {
        return player.getMainHandItem().is(ModRegistry.ROAD_WAND.get())
                || player.getOffhandItem().is(ModRegistry.ROAD_WAND.get());
    }

    /**
     * Cycles the aimed cell's unit type on the bound road.
     *
     * <p>Only fires when the aimed block really belongs to a synced road that
     * may be edited, and the click is claimed only in that case, so a
     * right-click on ordinary ground still does whatever it normally does.</p>
     */
    private static void editAimedRow(final net.minecraft.world.entity.player.Player player, final BlockPos pos,
                                     final PlayerInteractEvent.RightClickBlock event) {
        com.YLFXM.rp.crossroads.road.RoadPlan target = boundPlan(player);
        if (target != null) RoadSelectionClient.bindTo(target);
        if (target != null && !RoadRules.canEdit(target)) {
            com.YLFXM.rp.crossroads.road.RoadNotices.actionBar(player,
                    "rp_crossroads.road.locked");
            claim(event);
            return;
        }
        // Same predicate the server uses to reject the edit: a road that can still create
        // its shape (DRAFT, no session) needs both endpoints. Asking "state == DRAFT" let
        // the two sides disagree and the edit failed silently.
        if (target != null && RoadRules.canCreate(target) && !RoadSelectionClient.hasBoth()) {
            com.YLFXM.rp.crossroads.road.RoadNotices.actionBar(player,
                    "rp_crossroads.select.endpoints_first");
            claim(event);
            return;
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
        claim(event);
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
