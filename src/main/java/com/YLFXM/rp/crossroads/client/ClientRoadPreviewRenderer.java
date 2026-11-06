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

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.blueprints.v1.BlueprintUtil;
import com.ldtteam.structurize.storage.rendering.RenderingCache;
import com.ldtteam.structurize.storage.rendering.types.BlueprintPreviewData;
import com.YLFXM.rp.crossroads.network.RoadPreviewPayload;
import net.minecraft.client.Minecraft;

import com.YLFXM.rp.crossroads.road.common.BlueprintCells;

/**
 * Draws the road being planned as real ghost blocks by handing a
 * {@link BlueprintPreviewData} to Structurize's rendering cache.
 *
 * <p>Two things this deliberately avoids:</p>
 *
 * <ul>
 *   <li><b>The native build tool's cache slot.</b> That window registers its
 *       preview under a {@code null} key, so sharing it would stack two
 *       identical ghosts on top of each other.</li>
 *   <li><b>Server sync.</b> {@link BlueprintPreviewData} built with
 *       {@code false} stays local: a planning preview is one player's business,
 *       and broadcasting it would put a ghost road in everybody else's world.</li>
 * </ul>
 *
 * <p>Stale answers are dropped by sequence number. Endpoints are picked with
 * clicks, so requests are naturally spaced, but a mirror toggle or a re-pick can
 * still leave an older answer in flight, and showing it would place the ghost of
 * a road the player no longer selected.</p>
 */
public final class ClientRoadPreviewRenderer {
    /**
     * Cache key for the planning preview. Must differ from the build tool's
     * {@code null} key, and must never collide with a pack name.
     */
    public static final String CACHE_KEY = "rp_crossroads:road-preview";

    /** Sequence handed out with the next request. */
    private static long sequence;

    /** Replies below this belong to a selection the player has already moved on from. */
    private static long staleBelow;
    private static java.util.UUID expectedRoad;
    private static long expectedSequence;
    private static long shownSequence = -1;
    private static BlueprintPreviewData loadedPreview;

    public static boolean isLoaded() { return loadedPreview != null && shownSequence == expectedSequence; }

    public static void updateRange() {
        if (!isLoaded()) return;
        final var mc = Minecraft.getInstance();
        if (mc.level == null || mc.gameRenderer.getMainCamera() == null) return;
        final var blueprint = loadedPreview.getBlueprint();
        final var origin = loadedPreview.getPos().subtract(blueprint.getPrimaryBlockOffset());
        final var bounds = new net.minecraft.world.phys.AABB(origin.getX(), origin.getY(), origin.getZ(),
                origin.getX()+blueprint.getSizeX(), origin.getY()+blueprint.getSizeY(), origin.getZ()+blueprint.getSizeZ());
        if (com.YLFXM.rp.crossroads.road.RoadRenderRange.contains(bounds, mc.gameRenderer.getMainCamera().getPosition())) {
            RenderingCache.queue(CACHE_KEY, loadedPreview);
        } else RenderingCache.removeBlueprint(CACHE_KEY);
    }

    public static void expect(final java.util.UUID road, final long request) {
        expectedRoad = road; expectedSequence = request; shownSequence = -1; loadedPreview = null;
        RenderingCache.removeBlueprint(CACHE_KEY);
    }

    public static boolean isVisible() {
        return shownSequence == expectedSequence && expectedRoad != null && RenderingCache.hasBlueprint(CACHE_KEY);
    }

    /**
     * Sequence to send with the next preview request. Only ever increases, which
     * is what makes the staleness check in {@link #accept} meaningful.
     */
    public static long nextSequence() {
        return ++sequence;
    }

    /**
     * Shows the blueprint pushed by the server as a local ghost preview.
     *
     * @return true when the preview is now on screen
     */
    public static boolean accept(final RoadPreviewPayload payload) {
        if (payload == null || payload.blueprint() == null) {
            return false;
        }
        if (payload.sequence() < staleBelow || payload.sequence() != expectedSequence
                || !payload.roadId().equals(expectedRoad)
                || !"wand".equals(ClientRoadPreview.mode()) || ClientNativeBuildTracker.isActive()) {
            return false;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.dimension().location().toString().equals(payload.dimension())) {
            return false;
        }
        final Blueprint blueprint = BlueprintUtil.readBlueprintFromNBT(
                payload.blueprint(), minecraft.level.registryAccess());
        if (blueprint == null) {
            return false;
        }
        // A fresh instance every time: the constructor is the only place
        // serverSyncEnabled can be turned off, and reusing the build tool's
        // cached instance would inherit whatever sync setting it had.
        final BlueprintPreviewData data = new BlueprintPreviewData(false);
        data.setBlueprint(blueprint);
        data.setPos(payload.origin());
        // Show the blueprint as authored. "Nice" rendering swaps solid
        // substitutions for the surrounding terrain and paints placeholders as
        // air, which is exactly wrong for a road: the ghost has to show the
        // filler and the reserved controller cell, not a smooth guess of them.
        data.setRenderBlocksNice(false);
        // Structurize caches one rendered mesh per (rotation, blueprint) and
        // Blueprint.equals only compares name/fileName/filePath/pack/volume -
        // never the blocks. A rebuilt road keeps all of those, so the cache
        // handed back the PREVIOUS mesh and the ghost only moved instead of
        // changing. Dropping the meshes forces a rebuild from the new blocks.
        com.ldtteam.structurize.client.BlueprintHandler.getInstance().clearCache();
        loadedPreview = data;
        shownSequence = payload.sequence();
        updateRange();
        // Matches the server-side log line: the ghost is drawn from
        // origin - primaryOffset, so these two numbers are all it takes to tell
        // "the blueprint content is wrong" from "it is drawn in the wrong spot".
        com.YLFXM.rp.crossroads.Crossroads.LOGGER.info(
                "Road preview ghost: pos={} size={}x{}x{} primaryOffset={} start={}",
                payload.origin(), blueprint.getSizeX(), blueprint.getSizeY(), blueprint.getSizeZ(),
                blueprint.getPrimaryBlockOffset(),
                com.YLFXM.rp.crossroads.road.common.BlueprintCells.origin(payload.origin(), blueprint));
        staleBelow = payload.sequence();
        shownSequence = payload.sequence();
        return true;
    }

    /**
     * Removes the planning preview. Called when the selection is dropped, when the
     * native build tool takes over (it draws its own ghost), and on world exit.
     */
    public static void clear() {
        final boolean hadPreview = RenderingCache.hasBlueprint(CACHE_KEY);
        RenderingCache.removeBlueprint(CACHE_KEY);
        // Anything still in flight answers a request the player has abandoned.
        if (expectedRoad != null || hadPreview) staleBelow = ++sequence;
        expectedRoad = null; shownSequence = -1; loadedPreview = null;
        // The selection has to be re-requested the next time it is shown; the
        // preview is gone, so the de-duplication must not treat it as current.
        ClientRoadPreview.invalidateRequest();
    }

    private ClientRoadPreviewRenderer() {
    }
}
