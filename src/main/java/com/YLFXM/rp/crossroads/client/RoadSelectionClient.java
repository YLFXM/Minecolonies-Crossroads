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

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Client-only endpoint selection for the Road Wand.
 */
public final class RoadSelectionClient {
    private static BlockPos a = BlockPos.ZERO;
    private static BlockPos b = BlockPos.ZERO;
    private static BlockPos controllerPos = BlockPos.ZERO;
    private static boolean mirror;
    private static java.util.UUID roadId;
    private static String dimension = "";
    private record Key(String dimension, java.util.UUID road) { }
    private record Selection(BlockPos a, BlockPos b, boolean mirror) { }
    private static final java.util.Map<Key, Selection> saved = new java.util.HashMap<>();

    private static void remember() {
        if (roadId != null) saved.put(new Key(dimension, roadId), new Selection(a, b, mirror));
    }

    public static java.util.UUID roadId() { return roadId; }

    public static void bindTo(final com.YLFXM.rp.crossroads.road.RoadPlan plan) {
        final String dim = plan.dimension().location().toString();
        if (!plan.id().equals(roadId) || !dim.equals(dimension)) {
            remember();
            a = BlockPos.ZERO; b = BlockPos.ZERO;
            roadId = plan.id(); dimension = dim;
            mirror = plan.mirror();
            final Selection previous = saved.get(new Key(dim, roadId));
            if (previous != null) {
                a = previous.a(); b = previous.b(); mirror = previous.mirror();
            } else if (plan.hasEndpoints()) {
                // DRAFT geometry saved by settings/row edits is just as real as
                // confirmed geometry. A local partial/cleared selection wins.
                a = plan.endpointA(); b = plan.endpointB();
            }
            remember();
            ClientRoadPreviewRenderer.clear();
        }
        controllerPos = plan.controllerPos();
    }

    public static void removed(final java.util.UUID id) {
        saved.keySet().removeIf(key -> key.road().equals(id));
        if (id.equals(roadId)) { resetActive(); ClientRoadPreviewRenderer.clear(); }
    }

    /** The server sends the complete authorized index, independent of survey scope. */
    public static void retain(final java.util.Collection<com.YLFXM.rp.crossroads.road.RoadPlan> roads,
                              final java.util.Set<java.util.UUID> manageable, final String dim) {
        final java.util.Set<Key> allowed = new java.util.HashSet<>();
        for (final var plan : roads) if (manageable.contains(plan.id()))
            allowed.add(new Key(plan.dimension().location().toString(), plan.id()));
        saved.keySet().retainAll(allowed);
        if (roadId != null && (!dimension.equals(dim) || !allowed.contains(new Key(dimension, roadId)))) {
            resetActive(); ClientRoadPreviewRenderer.clear();
        }
    }

    public static void setA(final BlockPos pos) {
        a = pos.immutable();
        remember();
    }

    public static void setB(final BlockPos pos) {
        b = pos.immutable();
        remember();
    }

    public static void setControllerPos(final BlockPos pos) {
        controllerPos = pos.immutable();
    }

    public static BlockPos controllerPos() {
        return controllerPos;
    }

    public static void setMirror(final boolean mirrored) {
        mirror = mirrored;
        remember();
    }

    public static boolean mirror() {
        return mirror;
    }

    public static BlockPos a() {
        return a;
    }

    public static BlockPos b() {
        return b;
    }

    public static boolean hasBoth() {
        return !a.equals(BlockPos.ZERO) && !b.equals(BlockPos.ZERO);
    }

    public static void clear() {
        a = BlockPos.ZERO;
        b = BlockPos.ZERO;
        remember();
        ClientRoadPreviewRenderer.clear();
    }

    public static void clearAll() {
        saved.clear();
        resetActive();
    }

    private static void resetActive() {
        a = BlockPos.ZERO; b = BlockPos.ZERO;
        controllerPos = BlockPos.ZERO;
        mirror = false;
        roadId = null; dimension = "";
    }

    public static Component summary() {
        return Component.literal("A=" + shortPos(a) + "  B=" + shortPos(b));
    }

    private static String shortPos(final BlockPos pos) {
        return pos.equals(BlockPos.ZERO) ? "-" : pos.getX() + "," + pos.getZ();
    }

    private RoadSelectionClient() {
    }
}
