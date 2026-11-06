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
package com.YLFXM.rp.crossroads.road;

import java.util.*;
import com.YLFXM.rp.crossroads.road.common.RoadGeometry;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;

/**
 * Per-dimension junction table: one record per road pair, living next to the road index
 * in {@code rp_crossroads_roads.dat}. A record's {@code rect} is the authoritative
 * answer to "are these two roads a junction, and where"; geometry for decoration and
 * clearance is still computed from the road models by {@link RoadCrossings}.
 */
public final class RoadJunctions {
    /** Relation quadruple plus the pair-scoped grant and the two "was built" markers. */
    public record Junction(UUID a, UUID b, RoadGeometry.Rect rect, RoadPlanningState.Crossing rule,
                           RoadPlanningState.Grant grant, boolean builtA, boolean builtB, int rev) { }

    /** Client-only projection of a record, parsed from the road sync packet. */
    public record Projection(UUID peer, RoadPlanningState.Crossing rule, RoadGeometry.Rect rect, boolean builtPeer) { }

    private static final ThreadLocal<Edit> EDIT = new ThreadLocal<>();

    /** Copy-on-write layer for one planning operation. Writes stay here until {@link #flush}. */
    public static final class Edit implements AutoCloseable {
        private final ServerLevel level;
        private final Map<String,Junction> overlay = new LinkedHashMap<>();
        private boolean closed;
        private Edit(final ServerLevel level) { this.level = level; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            overlay.clear();
            EDIT.remove();
        }
    }

    public static Edit begin(final ServerLevel level) {
        if (EDIT.get() != null) throw new IllegalStateException("A junction edit is already active");
        final Edit edit = new Edit(level);
        EDIT.set(edit);
        return edit;
    }

    public static boolean editing() { return EDIT.get() != null; }

    public static String key(final UUID a, final UUID b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    public static Junction find(final ServerLevel level, final UUID a, final UUID b) {
        final String key = key(a, b);
        final Edit edit = EDIT.get();
        if (edit != null && edit.overlay.containsKey(key)) return edit.overlay.get(key);
        return RoadManager.get(level).junctionTable().get(key);
    }

    public static List<Junction> all(final ServerLevel level) {
        final Map<String,Junction> merged = merged(level);
        return List.copyOf(merged.values());
    }

    public static List<Junction> forRoad(final ServerLevel level, final UUID road) {
        final List<Junction> out = new ArrayList<>();
        for (final Junction junction : merged(level).values()) {
            if (junction.a().equals(road) || junction.b().equals(road)) out.add(junction);
        }
        return List.copyOf(out);
    }

    /** Relation as seen from {@code own}: the stored order is relative to the lower UUID. */
    public static RoadPlanningState.Crossing ruleFor(final Junction junction, final UUID own) {
        final RoadPlanningState.Crossing rule = junction.rule();
        if (own.equals(junction.a())) return rule;
        return new RoadPlanningState.Crossing(rule.layered(), -rule.order(), rule.version(), rule.platform());
    }

    public static void stage(final ServerLevel level, final Junction next) {
        final Edit edit = requireEdit();
        final Junction normalized = normalize(next);
        edit.overlay.put(key(normalized.a(), normalized.b()), normalized);
    }

    public static void stageRemove(final ServerLevel level, final UUID a, final UUID b) {
        requireEdit().overlay.put(key(a, b), null);
    }

    /** Writes the overlay into the dimension table. Only called at the five global moments. */
    public static void flush(final ServerLevel level) {
        final Edit edit = requireEdit();
        final RoadManager manager = RoadManager.get(level);
        for (final Map.Entry<String,Junction> entry : edit.overlay.entrySet()) {
            if (entry.getValue() == null) manager.junctionTable().remove(entry.getKey());
            else manager.junctionTable().put(entry.getKey(), entry.getValue());
        }
        edit.overlay.clear();
        manager.junctionsChanged();
    }

    /** Direct write for a global moment (approval, commit, removal, startup repair). */
    public static void put(final ServerLevel level, final Junction junction) {
        final Junction normalized = normalize(junction);
        final String key = key(normalized.a(), normalized.b());
        final RoadManager manager = RoadManager.get(level);
        final Junction previous = manager.junctionTable().get(key);
        final Junction next = new Junction(normalized.a(), normalized.b(), normalized.rect(), normalized.rule(),
                normalized.grant(), normalized.builtA(), normalized.builtB(),
                previous == null ? Math.max(1, normalized.rev()) : previous.rev() + 1);
        manager.junctionTable().put(key, next);
        manager.junctionsChanged();
        final Edit edit = EDIT.get();
        if (edit != null) edit.overlay.remove(key);
    }

    public static void removePair(final ServerLevel level, final UUID a, final UUID b) {
        final String key = key(a, b);
        RoadManager.get(level).junctionTable().remove(key);
        RoadManager.get(level).junctionsChanged();
        final Edit edit = EDIT.get();
        if (edit != null) edit.overlay.put(key, null);
    }

    public static void removeRoad(final ServerLevel level, final UUID road) {
        final RoadManager manager = RoadManager.get(level);
        final List<String> keys = new ArrayList<>();
        for (final Map.Entry<String,Junction> entry : manager.junctionTable().entrySet()) {
            if (entry.getValue().a().equals(road) || entry.getValue().b().equals(road)) keys.add(entry.getKey());
        }
        for (final String key : keys) manager.junctionTable().remove(key);
        final Edit edit = EDIT.get();
        if (edit != null) {
            for (final Map.Entry<String,Junction> entry : new ArrayList<>(edit.overlay.entrySet())) {
                final Junction value = entry.getValue();
                if (value != null && (value.a().equals(road) || value.b().equals(road))) edit.overlay.remove(entry.getKey());
            }
        }
        manager.junctionsChanged();
    }

    /** Longest-known rule version plus one, for relation changes. */
    public static long nextVersion(final ServerLevel level) {
        long latest = System.currentTimeMillis();
        for (final Junction junction : merged(level).values()) latest = Math.max(latest, junction.rule().version());
        return Math.addExact(latest, 1);
    }

    /** Recomputes {@code rect} and built flags; null means the pair is no longer a junction. */
    public static Junction recompute(final ServerLevel level, final Junction junction) {
        final RoadPlan a = RoadManager.get(level).get(junction.a());
        final RoadPlan b = RoadManager.get(level).get(junction.b());
        if (a == null || b == null) return null;
        return recompute(junction, reference(level, a), reference(level, b),
                RoadBlueprintStore.referenceIsApplied(level, a), RoadBlueprintStore.referenceIsApplied(level, b));
    }

    /**
     * Pure form of {@link #recompute(ServerLevel,Junction)}: callers supply each side's
     * authoritative model (applied when built, otherwise the latest proposal).
     */
    static Junction recompute(final Junction junction, final RoadPlan a, final RoadPlan b,
                              final boolean builtA, final boolean builtB) {
        if (a == null || b == null || a.segments().isEmpty() || b.segments().isEmpty()) return null;
        final RoadGeometry.Rect rect = RoadGeometry.body(a).intersect(RoadGeometry.body(b));
        if (rect == null) return null;
        if (rect.equals(junction.rect()) && builtA == junction.builtA() && builtB == junction.builtB()) return junction;
        return new Junction(junction.a(), junction.b(), rect, junction.rule(), junction.grant(), builtA, builtB, junction.rev() + 1);
    }

    /** Recomputes every record touching {@code road}; deletes records whose rect vanished. */
    public static void recomputeRoad(final ServerLevel level, final UUID road) {
        for (final Junction junction : forRoad(level, road)) {
            final Junction next = recompute(level, junction);
            if (next == null) removePair(level, junction.a(), junction.b());
            else if (!next.equals(junction)) write(level, next);
        }
    }

    /** Flips the staged strict (grant-less layered) relations of one road; used for an automatic retry. */
    public static boolean flipStagedStrict(final ServerLevel level, final UUID road) {
        final Edit edit = EDIT.get();
        if (edit == null) return false;
        boolean flipped = false;
        for (final Map.Entry<String,Junction> entry : edit.overlay.entrySet()) {
            final Junction junction = entry.getValue();
            if (junction == null || junction.grant() != null || !junction.rule().layered()) continue;
            if (!junction.a().equals(road) && !junction.b().equals(road)) continue;
            final RoadPlanningState.Crossing rule = junction.rule();
            if (rule.order() == 0) continue;
            entry.setValue(new Junction(junction.a(), junction.b(), junction.rect(),
                    new RoadPlanningState.Crossing(true, -rule.order(), rule.version(), rule.platform()),
                    junction.grant(), junction.builtA(), junction.builtB(), junction.rev() + 1));
            flipped = true;
        }
        return flipped;
    }

    public static ListTag saveAll(final Collection<Junction> junctions) {
        final ListTag list = new ListTag();
        for (final Junction junction : junctions) {
            final CompoundTag tag = new CompoundTag();
            tag.putUUID("a", junction.a());
            tag.putUUID("b", junction.b());
            tag.putIntArray("rect", new int[]{junction.rect().minX(), junction.rect().minZ(), junction.rect().maxX(), junction.rect().maxZ()});
            tag.putBoolean("layered", junction.rule().layered());
            tag.putInt("order", junction.rule().order());
            tag.putLong("version", junction.rule().version());
            if (junction.rule().platform() != null) tag.putInt("platform", junction.rule().platform());
            if (junction.grant() != null) {
                final CompoundTag grant = new CompoundTag();
                grant.putUUID("token", junction.grant().token());
                grant.putUUID("issuer", junction.grant().issuer());
                grant.putLong("issued", junction.grant().issuedAt());
                grant.putString("boundary", junction.grant().boundary());
                tag.put("grant", grant);
            }
            tag.putBoolean("builtA", junction.builtA());
            tag.putBoolean("builtB", junction.builtB());
            tag.putInt("rev", junction.rev());
            list.add(tag);
        }
        return list;
    }

    public static Map<String,Junction> loadAll(final ListTag list) {
        final Map<String,Junction> out = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            final CompoundTag tag = list.getCompound(i);
            final UUID a = tag.getUUID("a"), b = tag.getUUID("b");
            final int[] rect = tag.getIntArray("rect");
            if (rect.length != 4) continue;
            final var rule = new RoadPlanningState.Crossing(tag.getBoolean("layered"), tag.getInt("order"),
                    tag.getLong("version"), tag.contains("platform", 3) ? tag.getInt("platform") : null);
            final RoadPlanningState.Grant grant;
            if (tag.contains("grant", 10)) {
                final CompoundTag g = tag.getCompound("grant");
                grant = new RoadPlanningState.Grant(g.getUUID("token"), g.getUUID("issuer"),
                        g.getLong("issued"), g.getString("boundary"));
            } else grant = null;
            final Junction junction = normalize(new Junction(a, b, new RoadGeometry.Rect(rect[0], rect[1], rect[2], rect[3]),
                    rule, grant, tag.getBoolean("builtA"), tag.getBoolean("builtB"), tag.getInt("rev")));
            out.put(key(junction.a(), junction.b()), junction);
        }
        return out;
    }

    private static void write(final ServerLevel level, final Junction junction) {
        final Edit edit = EDIT.get();
        if (edit != null) stage(level, junction);
        else put(level, junction);
    }

    private static Edit requireEdit() {
        final Edit edit = EDIT.get();
        if (edit == null) throw new IllegalStateException("No active junction edit");
        return edit;
    }

    private static Map<String,Junction> merged(final ServerLevel level) {
        final Map<String,Junction> out = new TreeMap<>(RoadManager.get(level).junctionTable());
        final Edit edit = EDIT.get();
        if (edit != null) {
            for (final Map.Entry<String,Junction> entry : edit.overlay.entrySet()) {
                if (entry.getValue() == null) out.remove(entry.getKey());
                else out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    private static Junction normalize(final Junction junction) {
        if (junction.a().compareTo(junction.b()) < 0) return junction;
        final RoadPlanningState.Crossing rule = junction.rule();
        return new Junction(junction.b(), junction.a(), junction.rect(),
                new RoadPlanningState.Crossing(rule.layered(), -rule.order(), rule.version(), rule.platform()),
                junction.grant(), junction.builtB(), junction.builtA(), junction.rev());
    }

    private static RoadPlan reference(final ServerLevel level, final RoadPlan plan) {
        return RoadBlueprintStore.referencePlan(level, plan);
    }

    private RoadJunctions() { }
}
