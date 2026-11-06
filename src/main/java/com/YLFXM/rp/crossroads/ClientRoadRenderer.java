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
package com.YLFXM.rp.crossroads;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.YLFXM.rp.crossroads.network.ClientRoadCache;
import com.YLFXM.rp.crossroads.road.RoadBounds;
import com.YLFXM.rp.crossroads.road.RoadPlan;
import com.YLFXM.rp.crossroads.client.RoadSelectionClient;
import com.YLFXM.rp.crossroads.road.RoadRenderRange;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Tool-scoped road bounds and a world-aligned A-to-B arrow for the visible ghost.
 */
@EventBusSubscriber(modid = Crossroads.MODID, value = Dist.CLIENT)
public final class ClientRoadRenderer {
    @SubscribeEvent
    public static void onRenderLevelStage(final RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        final RoadPlan preview = com.YLFXM.rp.crossroads.client.ClientRoadPreview.plan();
        com.YLFXM.rp.crossroads.client.ClientRoadPreviewRenderer.updateRange();
        final String mode = com.YLFXM.rp.crossroads.client.ClientRoadPreview.mode();
        final List<RoadPlan> plans = new ArrayList<>();
        if ("wand".equals(mode)) {
            final var controller = com.YLFXM.rp.crossroads.road.RoadWandBinding.heldController(minecraft.player.getInventory());
            final var bound = ClientRoadCache.findByController(controller);
            if (bound != null) {
                if (preview == null) plans.add(bound);
                else if (!com.YLFXM.rp.crossroads.client.ClientRoadPreviewRenderer.isVisible()) plans.add(preview);
            }
        } else if ("surveyor".equals(mode)) plans.addAll(ClientRoadCache.surveyRoads());
        else return;

        final PoseStack pose = event.getPoseStack();
        final Vec3 camera = event.getCamera().getPosition();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);

        final MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        if ("wand".equals(mode) && preview != null
                && preview.dimension().equals(minecraft.level.dimension())
                && com.YLFXM.rp.crossroads.client.ClientRoadPreviewRenderer.isVisible()) {
            renderDirectionArrow(pose, buffers.getBuffer(RenderType.debugQuads()), preview, camera);
            buffers.endBatch(RenderType.debugQuads());
        }
        final VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (final RoadPlan plan : plans) {
            if (!plan.dimension().equals(minecraft.level.dimension())) {
                continue;
            }
            final boolean showApplied=(plan.state()==com.YLFXM.rp.crossroads.road.RoadState.BUILT && !plan.allowBlueprintEdit())
                    || plan.state()==com.YLFXM.rp.crossroads.road.RoadState.REMOVING;
            final RoadBounds bounds = showApplied && plan.appliedBounds()!=null
                    ? plan.appliedBounds() : RoadBounds.ofRoadOnly(plan, Config.defaultLampReserveHeight());
            final AABB box = new AABB(bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX() + 1.0, bounds.maxY() + 1.0, bounds.maxZ() + 1.0);
            if (!com.YLFXM.rp.crossroads.road.RoadRenderRange.contains(box, camera)) {
                continue;
            }
            if (plan.state() == com.YLFXM.rp.crossroads.road.RoadState.PLANNED
                    || plan.state() == com.YLFXM.rp.crossroads.road.RoadState.DRAFT) {
                LevelRenderer.renderLineBox(pose, lines, box, 1.0F, 1.0F, 1.0F, 1.0F);
            } else {
                LevelRenderer.renderLineBox(pose, lines, box, 0.2F, 0.6F, 1.0F, 1.0F);
            }
        }
        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }

    // Upright in the road-axis/world-Y plane; the outline and depth are scaled to 75%.
    private static final float[][] ARROW_OUTLINE = {
            {-0.8F, -0.12F}, {0.15F, -0.12F}, {0.15F, -0.4F},
            {0.9F, 0.0F}, {0.15F, 0.4F}, {0.15F, 0.12F}, {-0.8F, 0.12F}
    };
    private static final int[][] ARROW_FACES = {{0, 1, 5, 6}, {2, 3, 4, 4}};

    private static void renderDirectionArrow(final PoseStack pose, final VertexConsumer faces,
                                             final RoadPlan plan, final Vec3 camera) {
        final var controller = plan.controllerPos();
        final double x = controller.getX() + 0.5, y = controller.getY() + 1.65, z = controller.getZ() + 0.5;
        if (!RoadRenderRange.contains(new AABB(x - 0.675, y - 0.3, z - 0.675,
                x + 0.675, y + 0.3, z + 0.675), camera)) {
            return;
        }
        // Read the current endpoints, including an unconfirmed DRAFT selection; never face the camera.
        final var aPoint = RoadSelectionClient.a();
        final var bPoint = RoadSelectionClient.b();
        if (aPoint == null || bPoint == null) return;
        final Direction forward = plan.axis() == Direction.Axis.X
                ? (bPoint.getX() > aPoint.getX() ? Direction.EAST : Direction.WEST)
                : (bPoint.getZ() > aPoint.getZ() ? Direction.SOUTH : Direction.NORTH);
        final float dx = forward.getStepX(), dz = forward.getStepZ();
        pose.pushPose();
        pose.translate(x, y, z);
        pose.scale(0.75F, 0.75F, 0.75F);
        // Shaft quad and a triangle (repeated fourth vertex) cover each broad
        // face without filling the concave gaps behind the arrow head.
        for (final int[] face : ARROW_FACES) {
            for (final int index : face) arrowVertex(pose, faces, dx, dz, index, 0.075F, 1.0F);
            for (int i = face.length - 1; i >= 0; i--)
                arrowVertex(pose, faces, dx, dz, face[i], -0.075F, 1.0F);
        }
        for (int i = 0; i < ARROW_OUTLINE.length; i++) {
            final int next = (i + 1) % ARROW_OUTLINE.length;
            arrowVertex(pose, faces, dx, dz, i, -0.075F, 0.8F);
            arrowVertex(pose, faces, dx, dz, next, -0.075F, 0.8F);
            arrowVertex(pose, faces, dx, dz, next, 0.075F, 0.8F);
            arrowVertex(pose, faces, dx, dz, i, 0.075F, 0.8F);
        }
        pose.popPose();
    }

    private static void arrowVertex(final PoseStack pose, final VertexConsumer faces,
                                    final float dx, final float dz, final int index,
                                    final float depth, final float shade) {
        final float along = ARROW_OUTLINE[index][0], height = ARROW_OUTLINE[index][1];
        faces.addVertex(pose.last().pose(), dx * along - dz * depth, height, dz * along + dx * depth)
                .setColor(shade, 0.8F * shade, 0.15F * shade, 1.0F);
    }

    private ClientRoadRenderer() {
    }
}
