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
import com.YLFXM.mcreconstruction.network.ClientRoadCache;
import com.YLFXM.mcreconstruction.road.RoadManager;
import com.YLFXM.mcreconstruction.road.RoadPlan;
import com.YLFXM.mcreconstruction.road.RoadSegment;
import com.YLFXM.mcreconstruction.road.RoadState;
import com.YLFXM.mcreconstruction.road.RoadWandBinding;
import com.YLFXM.mcreconstruction.road.SegmentKind;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

/**
 * Client-only planning preview built from the current A/B selection and the
 * Wand-bound roadmap. It is rendered as a white AABB before any WorkOrder.
 */
public final class ClientRoadPreview {
    private static RoadPlan preview;
    /** Selection the last preview request was sent for. */
    private static BlockPos lastController;
    private static BlockPos lastAnchor;
    private static BlockPos lastA;
    private static BlockPos lastB;
    private static boolean lastMirror;
    private static int lastWidth;
    private static int lastLampSpacing;

    public static RoadPlan plan() {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            preview = null;
            com.YLFXM.mcreconstruction.client.ClientRoadPreviewRenderer.clear();
            return null;
        }
        final BlockPos controller = boundController(minecraft);
        RoadPlan base = null;
        if (controller != null) {
            for (final RoadPlan candidate : ClientRoadCache.roads()) {
                if (candidate.controllerPos().equals(controller)) {
                    base = candidate;
                    break;
                }
            }
        }
        // The A/B selection lives in client memory only, so it dies on every
        // restart and the player would drop back to the plain outline box even
        // though the plan is still PLANNED. The plan remembers the endpoints it
        // was built from; reuse them until the player picks a new pair.
        BlockPos a = RoadSelectionClient.a();
        BlockPos b = RoadSelectionClient.b();
        boolean adopted = false;
        if ((a.equals(BlockPos.ZERO) || b.equals(BlockPos.ZERO))
                && base != null && base.hasEndpoints()) {
            a = base.endpointA();
            b = base.endpointB();
            adopted = true;
        }
        if (a.equals(BlockPos.ZERO) || b.equals(BlockPos.ZERO)) {
            preview = null;
            com.YLFXM.mcreconstruction.client.ClientRoadPreviewRenderer.clear();
            return null;
        }
        if (adopted) {
            // Show the restored selection in the Wand panel too, and take the
            // mirror flag with it: the stored geometry was planned with that
            // flag, so previewing it with the other one would flip the road.
            RoadSelectionClient.setA(a);
            RoadSelectionClient.setB(b);
            RoadSelectionClient.setMirror(base.mirror());
        }
        if (base != null && base.state() == RoadState.BUILT) {
            // The road is finished, so PLANNED is over: the ghost has done its
            // job and the plain outline (drawn in blue for built roads) takes
            // over. This is what ends the preview after a build, because the
            // selection is no longer cleared when the plan is created.
            preview = null;
            com.YLFXM.mcreconstruction.client.ClientRoadPreviewRenderer.clear();
            return null;
        }
        if (base == null) {
            final Direction facing = a.getX() == b.getX()
                    ? (b.getZ() > a.getZ() ? Direction.SOUTH : Direction.NORTH)
                    : (b.getX() > a.getX() ? Direction.EAST : Direction.WEST);
            base = new RoadPlan(UUID.randomUUID(), minecraft.level.dimension(), a, a,
                    facing, false, com.YLFXM.mcreconstruction.CustomRoadPack.STYLE_PACK, "street",
                    4, com.YLFXM.mcreconstruction.road.WalkRange.centered(4, 2), 1,
                    com.YLFXM.mcreconstruction.Config.lampSpacing());
        }

        final boolean sameX = a.getX() == b.getX();
        final boolean sameZ = a.getZ() == b.getZ();
        if (sameX == sameZ) {
            preview = null;
            com.YLFXM.mcreconstruction.client.ClientRoadPreviewRenderer.clear();
            return null;
        }
        final Direction.Axis axis = sameX ? Direction.Axis.Z : Direction.Axis.X;
        final int minX = Math.min(a.getX(), b.getX());
        final int minZ = Math.min(a.getZ(), b.getZ());
        final int length = (axis == Direction.Axis.X
                ? Math.max(a.getX(), b.getX()) - minX
                : Math.max(a.getZ(), b.getZ()) - minZ) + 1;

        final boolean mirrored = RoadSelectionClient.mirror();
        final int side = mirrored ? -Math.abs(base.widthSide()) : Math.abs(base.widthSide());
        final RoadPlan plan = new RoadPlan(UUID.randomUUID(), base.dimension(), base.anchorPos(),
                base.controllerPos(), base.controllerFacing(), mirrored, base.pack(), base.variant(),
                base.width(), base.walkRange(), side, base.lampSpacing());
        plan.addSegment(new RoadSegment(new BlockPos(minX, a.getY(), minZ), length, axis, 0, SegmentKind.ROAD));
        plan.setTravelDir(Integer.signum((axis == Direction.Axis.X ? b.getX() - a.getX() : b.getZ() - a.getZ())));
        plan.setState(RoadState.PLANNED);
        preview = plan;
        requestBlueprint(plan, a, b);
        return plan;
    }

    /**
     * Asks the server for the blueprint this selection builds, so the preview can
     * show real blocks instead of a box.
     *
     * <p>Only fires when the selection actually changed. Endpoints are picked with
     * clicks rather than dragged, so a road costs a handful of packets, not one per
     * frame. The anchor is sent too because an unbound wand synthesises it from
     * endpoint A, and the server must place the blueprint at the same spot.</p>
     */
    /**
     * Forgets the selection the last request was sent for, so the next frame asks
     * the server again.
     *
     * <p>Called whenever the ghost is cleared. Without it, dropping the wand (or
     * opening the build tool) and coming back would leave the de-duplication
     * thinking nothing changed, send no request, and leave the player staring at
     * an empty selection — the preview could only return by re-picking an
     * endpoint.</p>
     */
    static void invalidateRequest() {
        lastController = null;
        lastAnchor = null;
        lastA = null;
        lastB = null;
    }

    private static void requestBlueprint(final RoadPlan plan, final BlockPos a, final BlockPos b) {
        final BlockPos controller = plan.controllerPos();
        final BlockPos anchor = plan.anchorPos();
        final boolean mirrored = plan.mirror();
        final int width = plan.width();
        final int lampSpacing = plan.lampSpacing();
        if (java.util.Objects.equals(lastController, controller)
                && java.util.Objects.equals(lastAnchor, anchor)
                && java.util.Objects.equals(lastA, a)
                && java.util.Objects.equals(lastB, b)
                && lastMirror == mirrored
                && lastWidth == width
                && lastLampSpacing == lampSpacing) {
            return;
        }
        lastController = controller;
        lastAnchor = anchor;
        lastA = a;
        lastB = b;
        lastMirror = mirrored;
        lastWidth = width;
        lastLampSpacing = lampSpacing;
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.YLFXM.mcreconstruction.network.RequestRoadPreviewPayload(
                        controller, anchor, a, b, mirrored, width, lampSpacing,
                        com.YLFXM.mcreconstruction.client.ClientRoadPreviewRenderer.nextSequence()));
    }

    private static BlockPos boundController(final Minecraft minecraft) {
        final ItemStack main = minecraft.player.getMainHandItem();
        if (main.is(ModRegistry.ROAD_WAND.get())) {
            final BlockPos bound = RoadWandBinding.getBoundController(main);
            if (bound != null) {
                return bound;
            }
        }
        final ItemStack offhand = minecraft.player.getOffhandItem();
        if (offhand.is(ModRegistry.ROAD_WAND.get())) {
            final BlockPos bound = RoadWandBinding.getBoundController(offhand);
            if (bound != null) {
                return bound;
            }
        }
        for (final ItemStack stack : minecraft.player.getInventory().items) {
            if (stack.is(ModRegistry.ROAD_WAND.get())) {
                final BlockPos bound = RoadWandBinding.getBoundController(stack);
                if (bound != null) {
                    return bound;
                }
            }
        }
        return null;
    }

    private ClientRoadPreview() {
    }
}
