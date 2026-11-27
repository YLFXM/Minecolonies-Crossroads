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
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(modid = com.YLFXM.rp.crossroads.Crossroads.MODID, value = Dist.CLIENT)
public final class ClientSelectionEvents {
    /** Last terrain order actually sent, so one press can never queue two. */
    private static long dispatchedTick = Long.MIN_VALUE;
    private static BlockPos dispatchedA = BlockPos.ZERO;
    private static BlockPos dispatchedB = BlockPos.ZERO;
    private static boolean dispatchedFill;
    /**
     * Corner selection, once per press.
     *
     * <p>This is deliberately not {@code PlayerInteractEvent.LeftClickBlock}. NeoForge
     * fires that event from {@code startDestroyBlock} <em>and</em> from
     * {@code continueDestroyBlock}, so holding the button re-fires it every tick — and in
     * survival a click is always held for a moment. Each repeat rewrote the corner while
     * the crosshair drifted, which is why a box collapsed into a one-block line along the
     * direction the cursor swept. Creative breaks the block instantly, never entering
     * {@code continueDestroyBlock}, so the same gesture behaved there.</p>
     *
     * <p>{@code InteractionKeyMappingTriggered} is raised once per physical press, before
     * block breaking starts, and cancelling it keeps the wand from mining the block it
     * just measured. The held path stays guarded by {@code RoadWandHandler}, which cancels
     * {@code LeftClickBlock} on both sides.</p>
     */
    @SubscribeEvent
    public static void onAttackInput(final InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack()) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        final boolean terrain = TerrainClientState.isTerrain();
        if (!holdsWand(minecraft.player) || !ownsGesture(minecraft.player, event.getHand())) {
            if (terrain) {
                com.YLFXM.rp.crossroads.Crossroads.LOGGER.info(
                        "Terrain corner ignored: hand={} main={} off={}", event.getHand(),
                        minecraft.player.getMainHandItem().getItem(),
                        minecraft.player.getOffhandItem().getItem());
            }
            return;
        }
        final HitResult hit = minecraft.hitResult;
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            if (terrain) {
                // The entity flag only follows the sneak input on the next tick, so a quick
                // shift+click used to arrive as "A". The key's own state is immediate.
                com.YLFXM.rp.crossroads.Crossroads.LOGGER.info(
                        "Terrain corner skipped: hit={} shiftKey={}", hit == null ? "null" : hit.getType(),
                        minecraft.options.keyShift.isDown());
            }
            // Air and entity attacks keep their normal meaning.
            return;
        }
        if (!pickCorner(minecraft.player, blockHit.getBlockPos(), minecraft.options.keyShift.isDown())) {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(false);
        // The cancel also suppresses the vanilla swing, so give the press its feedback.
        minecraft.player.swing(InteractionHand.MAIN_HAND);
    }

    /**
     * Records one corner of the current selection.
     *
     * @return true when the click was a selection and must not break the block
     */
    private static boolean pickCorner(final Player player, final BlockPos pos, final boolean wantB) {
        // Terrain mode owns the corners: a terrain volume has nothing to do with
        // the bound road's axis, so none of the road checks below may run. This is
        // exactly what blocked picking ground off a built road's line.
        if (TerrainClientState.isTerrain()) {
            // The same press can reach this method more than once: NeoForge fires the
            // input event from startAttack *and* from continueAttack, so holding the
            // button re-delivers it. Handing the click to the selection state (which keeps
            // the clicked cells apart from the normalized box) makes that a no-op instead
            // of shrinking the box by one axis.
            final boolean changed = RoadSelectionClient.setTerrainCorner(wantB, pos);
            if (!changed) {
                com.YLFXM.rp.crossroads.Crossroads.LOGGER.debug(
                        "Terrain corner repeat ignored: {} pos={}", wantB ? "B" : "A", pos);
                return true;
            }
            final BlockPos a = RoadSelectionClient.a();
            final BlockPos b = RoadSelectionClient.b();
            com.YLFXM.rp.crossroads.Crossroads.LOGGER.info("Terrain corner {}: pos={} a={} b={}",
                    wantB ? "B" : "A", pos, a, b);
            // Report the clicked cell, not the reordered corner: the box is what
            // matters and the labels only say which key was used. Once both corners
            // exist the size is the useful part — a box that collapsed to one axis is
            // otherwise indistinguishable from a correct one on screen.
            if (!a.equals(BlockPos.ZERO) && !b.equals(BlockPos.ZERO)) {
                com.YLFXM.rp.crossroads.road.RoadNotices.actionBar(player,
                        "rp_crossroads.select.corner_box",
                        net.minecraft.network.chat.Component.translatable(
                                wantB ? "rp_crossroads.select.corner_b" : "rp_crossroads.select.corner_a"),
                        pos.getX(), pos.getY(), pos.getZ(),
                        Math.abs(a.getX() - b.getX()) + 1,
                        Math.abs(a.getY() - b.getY()) + 1,
                        Math.abs(a.getZ() - b.getZ()) + 1);
            } else {
                com.YLFXM.rp.crossroads.road.RoadNotices.actionBar(player,
                        wantB ? "rp_crossroads.select.endpoint_b" : "rp_crossroads.select.endpoint_a",
                        pos.getX(), pos.getY(), pos.getZ());
            }
            return true;
        }

        // A/B belong to the BOUND controller, so the lock is decided by that
        // plan — not by what the click happens to hit. Re-planning a built road
        // normally means clicking ground outside the old footprint, so testing
        // the clicked cell alone let the player keep re-picking A/B freely.
        final com.YLFXM.rp.crossroads.road.RoadPlan bound = boundPlan(player);
        if (bound != null) RoadSelectionClient.bindTo(bound);
        if (bound != null && !RoadRules.canEdit(bound)) {
            com.YLFXM.rp.crossroads.road.RoadNotices.actionBar(player,
                    "rp_crossroads.road.locked");
            return true;
        }
        // Shift+left is the B key, plain left the A key — but on an existing road
        // the POSITION decides which end a point belongs to (see below), so the
        // key only expresses an intent that may get swapped.
        boolean assignToB = wantB;

        if (bound != null && bound.state() != com.YLFXM.rp.crossroads.road.RoadState.DRAFT) {
            final net.minecraft.core.Direction.Axis axis = bound.axis();
            final boolean alongX = axis == net.minecraft.core.Direction.Axis.X;

            // Off-axis points are refused right here, at the click, instead of
            // letting them through and failing later when the blueprint is built.
            final int lateralOrigin = alongX ? bound.anchorPos().getZ() : bound.anchorPos().getX();
            if (!com.YLFXM.rp.crossroads.road.RoadReplanning.pointOnLine(bound, pos)) {
                com.YLFXM.rp.crossroads.road.RoadNotices.actionBar(player,
                        "rp_crossroads.select.on_axis", alongX ? "X" : "Z", alongX ? "z" : "x", lateralOrigin);
                return true;
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
            com.YLFXM.rp.crossroads.road.RoadNotices.actionBar(player,
                    "rp_crossroads.select.endpoint_b", pos.getX(), pos.getY(), pos.getZ());
        } else {
            RoadSelectionClient.setA(pos);
            com.YLFXM.rp.crossroads.road.RoadNotices.actionBar(player,
                    "rp_crossroads.select.endpoint_a", pos.getX(), pos.getY(), pos.getZ());
        }
        return true;
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
        if (!holdsWand(player) || !ownsGesture(player, event.getHand())) {
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
        if (!event.getLevel().isClientSide() || !holdsWand(event.getEntity())
                || !ownsGesture(event.getEntity(), event.getHand())) {
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
        final BlockPos a = RoadSelectionClient.a();
        final BlockPos b = RoadSelectionClient.b();
        final boolean fill = TerrainClientState.mode() == TerrainClientState.Mode.FILL;
        // One press, one order. Vanilla walks the hands for a use, so the same click can
        // reach both handlers; a second order built from the same corners within the same
        // tick is a duplicate, never a second intention (two clicks cannot share a tick).
        final long tick = player.level().getGameTime();
        if (tick == dispatchedTick && fill == dispatchedFill && a.equals(dispatchedA) && b.equals(dispatchedB)) {
            return;
        }
        dispatchedTick = tick;
        dispatchedA = a;
        dispatchedB = b;
        dispatchedFill = fill;
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.YLFXM.rp.crossroads.network.TerrainOrderPayload(a, b, fill));
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
        // Road mode has its own ways back: the corners of a terrain box mean nothing
        // as a road selection, so returning to the road restores the road's own pair
        // instead of leaving the wand empty.
        if (next == TerrainClientState.Mode.NONE) {
            RoadSelectionClient.reloadFromRoad(boundPlan(player));
        } else {
            RoadSelectionClient.clear();
        }
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
     * The hand that carries the wand owns the gesture.
     *
     * <p>Vanilla calls use/use-on once per hand, so without this test a single press ran
     * the handler twice — that is where the doubled terrain order came from. Picking the
     * hand from where the wand actually is keeps the offhand wand working and makes the
     * main-hand case ignore the second pass.</p>
     */
    private static boolean ownsGesture(final net.minecraft.world.entity.player.Player player,
                                       final net.minecraft.world.InteractionHand hand) {
        if (player.getMainHandItem().is(ModRegistry.ROAD_WAND.get())) {
            return hand == net.minecraft.world.InteractionHand.MAIN_HAND;
        }
        return hand == net.minecraft.world.InteractionHand.OFF_HAND
                && player.getOffhandItem().is(ModRegistry.ROAD_WAND.get());
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
