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

import com.YLFXM.mcreconstruction.road.RoadPlan;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * Client-side cache of the server road index. Only loaded on the client; it
 * contains no client-only types so the common Network class can reference it.
 */
public final class ClientRoadCache {
    private static volatile List<RoadPlan> roads = Collections.emptyList();
    private static volatile Set<UUID> manageable = Collections.emptySet();
    private static volatile String dimension = "";
    private static volatile boolean bound;
    private static volatile String colonyName = "";
    private static volatile Set<UUID> surveyIds = Collections.emptySet();
    private static String expectedSurvey = "";

    public static void expectSurvey(final String context) {
        if (!context.equals(expectedSurvey)) { clearSurvey(); expectedSurvey = context; }
    }

    public static void set(final CompoundTag tag) {
        final List<RoadPlan> loaded = new ArrayList<>();
        final ListTag list = tag.getList("roads", 10);
        for (int i = 0; i < list.size(); i++) {
            loaded.add(RoadPlan.load(list.getCompound(i)));
        }
        roads = List.copyOf(loaded);

        // Roads the server says this player may manage; drives button state only,
        // the server re-checks on every action.
        final Set<UUID> ids = new HashSet<>();
        final ListTag allowed = tag.getList("manageable", 8);
        for (int i = 0; i < allowed.size(); i++) {
            try {
                ids.add(UUID.fromString(allowed.getString(i)));
            } catch (final IllegalArgumentException ignored) {
                // Malformed id, ignore.
            }
        }
        manageable = Set.copyOf(ids);
        dimension = tag.getString("dimension");
        if (tag.getBoolean("surveyView") && expectedSurvey.equals(tag.getString("surveyContext"))) {
            bound = tag.getBoolean("bound");
            colonyName = tag.getString("colony");
            final Set<UUID> scope = new HashSet<>();
            final ListTag idsInView = tag.getList("surveyIds", 8);
            for (int i = 0; i < idsInView.size(); i++) {
                try { scope.add(UUID.fromString(idsInView.getString(i))); } catch (IllegalArgumentException ignored) { }
            }
            surveyIds = Set.copyOf(scope);
        }
    }

    public static void rememberAuthorized(RoadPlan plan) {
        final List<RoadPlan> updated=new ArrayList<>(roads);
        updated.removeIf(p -> p.id().equals(plan.id()) || p.controllerPos().equals(plan.controllerPos()));
        updated.add(plan); roads=List.copyOf(updated);
        final Set<UUID> allowed=new HashSet<>(manageable);allowed.add(plan.id());manageable=Set.copyOf(allowed);
    }

    public static RoadPlan findByController(final net.minecraft.core.BlockPos controller) {
        if (controller == null) return null;
        return roads.stream().filter(plan -> controller.equals(plan.controllerPos())).findFirst().orElse(null);
    }

    public static List<RoadPlan> surveyRoads() {
        return roads.stream().filter(plan -> surveyIds.contains(plan.id())).toList();
    }

    public static void clearSurvey() { surveyIds = Set.of(); bound = false; colonyName = ""; }

    public static void remove(final UUID id) {
        roads = roads.stream().filter(plan -> !plan.id().equals(id)).toList();
    }

    public static List<RoadPlan> roads() {
        return roads;
    }

    /** Whether the server allows this player to manage that road. */
    public static boolean canManage(final UUID roadId) {
        return manageable.contains(roadId);
    }

    public static String dimension() {
        return dimension;
    }

    /** Whether the view is scoped to one colony (surveyor bound to a town hall). */
    public static boolean bound() {
        return bound;
    }

    /** Name of the bound colony, empty when unbound. */
    public static String colonyName() {
        return colonyName;
    }

    public static void clear() {
        clearSurvey();
        expectedSurvey = "";
        roads = Collections.emptyList();
        manageable = Collections.emptySet();
        dimension = "";
        bound = false;
        colonyName = "";
    }

    private ClientRoadCache() {
    }
}
