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
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(modid = com.YLFXM.mcreconstruction.MCReconstruction.MODID, value = Dist.CLIENT)
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
        final com.YLFXM.mcreconstruction.road.RoadPlan bound = boundPlan(event.getEntity());
        if (bound != null
                && bound.state() == com.YLFXM.mcreconstruction.road.RoadState.BUILT
                && !bound.allowBlueprintEdit()) {
            event.getEntity().displayClientMessage(Component.literal(
                    "Turn on \"Edit Blueprint\" in the controller panel to change a built road."), true);
            event.setCanceled(true);
            return;
        }
        // Shift+left is the B key, plain left the A key — but on an existing road
        // the POSITION decides which end a point belongs to (see below), so the
        // key only expresses an intent that may get swapped.
        boolean assignToB = event.getEntity().isShiftKeyDown();
        final BlockPos pos = event.getPos();

        if (bound != null && bound.state() != com.YLFXM.mcreconstruction.road.RoadState.DRAFT) {
            final net.minecraft.core.Direction.Axis axis = bound.axis();
            final boolean alongX = axis == net.minecraft.core.Direction.Axis.X;

            // Off-axis points are refused right here, at the click, instead of
            // letting them through and failing later when the blueprint is built.
            final int lateralOrigin = alongX ? bound.anchorPos().getZ() : bound.anchorPos().getX();
            final int pointLateral = alongX ? pos.getZ() : pos.getX();
            if (pointLateral != lateralOrigin) {
                event.getEntity().displayClientMessage(Component.literal(
                        "This road runs along " + (alongX ? "X" : "Z")
                                + "; pick a point on " + (alongX ? "z=" : "x=") + lateralOrigin + "."), true);
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
            event.getEntity().displayClientMessage(Component.literal("Road endpoint B: "
                    + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()), true);
        } else {
            RoadSelectionClient.setA(pos);
            event.getEntity().displayClientMessage(Component.literal("Road endpoint A: "
                    + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()), true);
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
        com.YLFXM.mcreconstruction.road.RoadPlan target = roadAt(pos);
        if (target == null) {
            com.YLFXM.mcreconstruction.MCReconstruction.LOGGER.info(
                    "Road edit gesture ignored: no road covers {} ({} roads synced)",
                    pos, com.YLFXM.mcreconstruction.network.ClientRoadCache.roads().size());
            return;
        }
        com.YLFXM.mcreconstruction.MCReconstruction.LOGGER.info(
                "Road edit gesture: {} -> plan {} (state {}, edit {})",
                pos, target.id(), target.state(), target.allowBlueprintEdit());
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.YLFXM.mcreconstruction.network.EditRoadCellPayload(target.controllerPos(), pos));
        event.setUseBlock(net.neoforged.neoforge.common.util.TriState.FALSE);
        event.setUseItem(net.neoforged.neoforge.common.util.TriState.FALSE);
        event.setCanceled(true);
    }

    /** Road the player's Wand is bound to, or null when it is bound to nothing. */
    private static com.YLFXM.mcreconstruction.road.RoadPlan boundPlan(
            final net.minecraft.world.entity.player.Player player) {
        final BlockPos controller = boundController(player);
        if (controller == null) {
            return null;
        }
        for (final com.YLFXM.mcreconstruction.road.RoadPlan plan
                : com.YLFXM.mcreconstruction.network.ClientRoadCache.roads()) {
            if (plan.controllerPos().equals(controller)) {
                return plan;
            }
        }
        return null;
    }

    private static BlockPos boundController(final net.minecraft.world.entity.player.Player player) {
        final net.minecraft.world.item.ItemStack main = player.getMainHandItem();
        if (main.is(ModRegistry.ROAD_WAND.get())) {
            final BlockPos bound =
                    com.YLFXM.mcreconstruction.road.RoadWandBinding.getBoundController(main);
            if (bound != null) {
                return bound;
            }
        }
        final net.minecraft.world.item.ItemStack off = player.getOffhandItem();
        if (off.is(ModRegistry.ROAD_WAND.get())) {
            final BlockPos bound =
                    com.YLFXM.mcreconstruction.road.RoadWandBinding.getBoundController(off);
            if (bound != null) {
                return bound;
            }
        }
        for (final net.minecraft.world.item.ItemStack stack : player.getInventory().items) {
            if (stack.is(ModRegistry.ROAD_WAND.get())) {
                final BlockPos bound =
                        com.YLFXM.mcreconstruction.road.RoadWandBinding.getBoundController(stack);
                if (bound != null) {
                    return bound;
                }
            }
        }
        return null;
    }

    /**
     * Road whose paved volume contains this position, if any.
     *
     * <p>Deliberately looser than {@code RoadPlan#cellAt}: the client only needs
     * to know WHICH road was clicked, and the server re-derives the exact cell
     * and rejects anything off the pavement. A strict check here swallowed
     * clicks on a lamp verge or one block above the surface, which looked like
     * "the gesture does nothing".</p>
     */
    private static com.YLFXM.mcreconstruction.road.RoadPlan roadAt(final BlockPos pos) {
        for (final com.YLFXM.mcreconstruction.road.RoadPlan plan
                : com.YLFXM.mcreconstruction.network.ClientRoadCache.roads()) {
            final com.YLFXM.mcreconstruction.road.RoadBounds b = plan.bounds(0);
            if (pos.getX() >= b.minX() && pos.getX() <= b.maxX()
                    && pos.getZ() >= b.minZ() && pos.getZ() <= b.maxZ()
                    && pos.getY() >= b.minY() - 1 && pos.getY() <= b.maxY() + 1) {
                return plan;
            }
        }
        return null;
    }

    private ClientSelectionEvents() {
    }
}
