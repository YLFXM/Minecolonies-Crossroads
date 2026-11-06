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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import com.YLFXM.rp.crossroads.road.common.RoadAxisMath;

/**
 * Persistent road plan owned by the per-dimension RoadManager SavedData.
 * Row overrides use the world's along-coordinate, independent of height and lateral column.
 */
public final class RoadPlan {
    private UUID id;
    private ResourceKey<Level> dimension;
    private BlockPos anchorPos;
    private BlockPos controllerPos;
    private Direction controllerFacing;
    private boolean mirror;
    private String pack;
    private String variant;
    private String displayName = "";
    /**
     * Road name without its ownership prefix. The prefix is not stored because
     * ownership is derived from position on every check: when a colony grows over
     * a wilderness road the label has to change with it, and keeping the prefix
     * inside {@link #displayName} is what makes that rewrite possible without
     * guessing where the base begins.
     */
    private String roadName = "";
    private int width;
    private WalkRange walkRange;
    private int widthSide;
    private int lampSpacing;
    /**
     * Endpoints the geometry was built from, or null for roads that were laid
     * out from the controller instead.
     *
     * <p>They are part of the saved plan because the ghost preview is driven by
     * them: the selection otherwise lives in client memory only, so it dies on
     * every restart and the player drops back to the plain outline box even
     * though the plan is still PLANNED. Keeping them here also lets the server
     * notice that a player re-picked A/B on an already-planned road and rebuild
     * the temporary blueprint, instead of leaving the natively opened blueprint
     * on the old geometry.</p>
     */
    private BlockPos endpointA;
    private BlockPos endpointB;
    /**
     * Controller-panel switch: when on, a BUILT road unlocks cell editing and
     * re-selecting A/B. Off (default) keeps the old behaviour, where a finished
     * road is frozen.
     */
    private boolean allowBlueprintEdit;
    /**
     * Direction of travel along {@link #axis()}, {@code +1} or {@code -1}: with
     * {@code +1} the player clicked A at the low end of the axis. Cell stair
     * directions are stored relative to this, so it has to survive a save.
     */
    private int travelDir = 1;
    private final List<RoadSegment> segments = new ArrayList<>();
    /**
     * Per-row state, keyed by the world along-coordinate. A row with no entry is
     * flat, automatic and free of junction constraints.
     *
     * <p>Keyed by row, not by cell: a stair raises the whole walkable width of
     * that row, so a per-column state could only ever describe a physically
     * broken road. Which columns a row affects is decided by {@code walkRange}.</p>
     */
    private final Map<Integer, RowState> rows = new TreeMap<>();
    /** Parsed once from {@link #appliedGeometry}; null until first asked. */
    private Map<Integer, RowState> appliedRowsCache;
    /**
     * Applied row types as sent to clients. The full snapshot is too large to sync, but the
     * panel's "N edited" is a difference against the applied table, so the client needs at
     * least this projection. Null when the sender had no snapshot to project.
     */
    private Map<Integer, RowState> syncedApplied;
    /** Applied row count for clients; the projection only carries non-flat rows. */
    private int syncedAppliedCount;
    /**
     * Junction records as sent to the client. The server never reads these; the shared and
     * authoritative copy lives in {@link RoadJunctions}.
     */
    private Map<UUID, RoadJunctions.Projection> syncedJunctions = Map.of();
    public Map<UUID, RoadJunctions.Projection> syncedJunctions() { return syncedJunctions; }
    private RoadState state;
    private int workOrderId;
    /** Rows dropped by the most recent re-plan; shown in the controller panel. */

    /**
     * Player who planned this road. Wilderness roads are only visible to their
     * creator, so this has to survive a save/load. Older saves have no such tag
     * and load as {@code null}, which means "nobody" — only colony managers can
     * see the road then.
     */
    private UUID creator;
    /** Derived from the actual applied snapshot, never from unapplied stair rows. */
    private RoadBounds appliedBounds;
    public RoadBounds appliedBounds() { return appliedBounds; }
    public void setAppliedBounds(final RoadBounds bounds) { appliedBounds=bounds; }
    private RoadRemovalSession removalSession;
    public RoadRemovalSession removalSession() { return removalSession; }
    public void setRemovalSession(RoadRemovalSession session) { removalSession=session; }
    private RoadRepairSession repairSession;
    private boolean repairBusy;
    public RoadRepairSession repairSession() { return repairSession; }
    public void setRepairSession(final RoadRepairSession session) { repairSession=session; repairBusy=false; }
    public boolean isRepairBusy() { return repairSession != null || repairBusy; }
    public CompoundTag saveForSync() {
        final CompoundTag tag=save();
        tag.remove("removalSession"); tag.remove("repairSession"); tag.remove("appliedGeometry"); tag.remove("interruptedConstruction");tag.putBoolean("interrupted",hasInterruptedConstruction()); tag.putBoolean("repairBusy",isRepairBusy());
        // The full snapshot is too large to ship, but the panel's "N edited" is a
        // difference against the applied table. Send the projection plus the two facts
        // that cannot be derived from it: whether a snapshot exists, and how many rows it has.
        tag.putBoolean("appliedBuilt", appliedGeometry != null);
        if (appliedGeometry != null) {
            final Map<Integer, RowState> applied = appliedRows();
            final ListTag appliedTypes = new ListTag();
            for (final Map.Entry<Integer, RowState> entry : applied.entrySet()) {
                if (entry.getValue().type() == CellType.FLAT) continue;
                final CompoundTag row = new CompoundTag();
                row.putInt("along", entry.getKey());
                row.putString("type", entry.getValue().type().name());
                row.putString("level", entry.getValue().level().name());
                appliedTypes.add(row);
            }
            tag.put("appliedTypes", appliedTypes);
            tag.putInt("appliedCount", applied.size());
        }
        return tag;
    }

    public RoadPlan(final UUID id,
                    final ResourceKey<Level> dimension,
                    final BlockPos controllerPos,
                    final Direction controllerFacing,
                    final boolean mirror,
                    final String pack,
                    final String variant,
                    final int width,
                    final WalkRange walkRange,
                    final int widthSide,
                    final int lampSpacing) {
        this(id, dimension, controllerPos, controllerPos, controllerFacing, mirror, pack, variant,
                width, walkRange, widthSide, lampSpacing);
    }

    public RoadPlan(final UUID id,
                    final ResourceKey<Level> dimension,
                    final BlockPos anchorPos,
                    final BlockPos controllerPos,
                    final Direction controllerFacing,
                    final boolean mirror,
                    final String pack,
                    final String variant,
                    final int width,
                    final WalkRange walkRange,
                    final int widthSide,
                    final int lampSpacing) {
        this.id = id;
        this.dimension = dimension;
        this.anchorPos = anchorPos;
        this.controllerPos = controllerPos;
        this.controllerFacing = controllerFacing;
        this.mirror = mirror;
        this.pack = pack;
        this.variant = variant;
        this.width = Math.max(1, width);
        this.walkRange = clampWalkRange(walkRange, this.width);
        this.widthSide = widthSide >= 0 ? 1 : -1;
        this.lampSpacing = Math.max(0, lampSpacing);
        this.state = RoadState.DRAFT;
    }

    private static WalkRange clampWalkRange(final WalkRange requested, final int width) {
        if (requested == null) {
            return WalkRange.centered(width, width);
        }
        final int from = Math.max(0, Math.min(requested.from(), width - 1));
        final int to = Math.max(from, Math.min(requested.to(), width - 1));
        return WalkRange.of(from, to);
    }

    public UUID id() {
        return id;
    }

    /** Player who planned this road, or {@code null} for pre-existing saves. */
    public UUID creator() {
        return creator;
    }

    public void setCreator(final UUID creator) {
        this.creator = creator;
    }

    public void setId(final UUID id) {
        this.id = id;
    }

    public void setControllerPos(final BlockPos controllerPos) {
        this.controllerPos = controllerPos;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    /** Visible marker block that carries the road anchor. */
    public BlockPos anchorPos() {
        return anchorPos;
    }

    public void setAnchorPos(final BlockPos anchorPos) {
        this.anchorPos = anchorPos;
    }

    /**
     * Moves the reference frame to another row of this road, without touching the world.
     *
     * <p>Everything geometric is derived from the anchor row: row heights are integrated
     * from {@code anchorPos.getY()}, the anchor row is the one row that must stay flat, and
     * a blueprint's world origin is {@code anchorPos - primaryOffset}. So the move is legal
     * only when the target row is flat and carries the height it already had: passing
     * {@code entryY} of that row leaves every row height unchanged.</p>
     *
     * <p>The caller must shift the stored blueprint frames by the returned delta
     * ({@link RoadBlueprintStore#shiftFrame}) and re-derive the row locks
     * ({@link RoadLockReconcile#apply}); both are about the frame, not about blocks.</p>
     *
     * @return the block delta the frame moved by, or {@code null} when the move is refused
     */
    public BlockPos reanchorTo(final int along, final int entryY) {
        if (segments.isEmpty() || along < minAlong() || along > maxAlong()) return null;
        if (along == RoadHeightProfile.anchorAlong(this)) return null;
        // The anchor row has to be a flat row: the height profile refuses anything else,
        // and a stair there would put the pavement one block off this anchor height.
        if (row(along).type() != CellType.FLAT) return null;
        final BlockPos before = anchorPos;
        final BlockPos after = RoadHeightProfile.position(this, along, 0, entryY);
        if (after.equals(before)) return null;
        setAnchorPos(after);
        // The applied snapshot carries its own copy of the anchor; it has to move with the
        // frame, or interpreting the applied blueprint would land a delta away from the world.
        if (appliedGeometry != null) {
            final CompoundTag patched = appliedGeometry.copy();
            patched.put("anchor", NbtUtils.writeBlockPos(after));
            setAppliedGeometry(patched);
        }
        return after.subtract(before);
    }

    public BlockPos controllerPos() {
        return controllerPos;
    }

    public Direction controllerFacing() {
        return controllerFacing;
    }

    public boolean mirror() {
        return mirror;
    }

    public void setMirror(final boolean mirror) {
        this.mirror = mirror;
        this.widthSide = mirror ? -Math.abs(widthSide) : Math.abs(widthSide);
    }

    public String pack() {
        return pack;
    }

    public String variant() {
        return variant;
    }

    public String displayName() {
        return displayName;
    }

    public void setDisplayName(final String displayName) {
        this.displayName = displayName == null ? "" : displayName;
    }

    /** Road name without the ownership prefix. */
    public String roadName() {
        return roadName;
    }

    public void setRoadName(final String roadName) {
        this.roadName = roadName == null ? "" : roadName;
    }

    public int width() {
        return width;
    }

    public WalkRange walkRange() {
        return walkRange;
    }

    public void setWalkRange(final WalkRange range) {
        this.walkRange = clampWalkRange(range, width);
    }

    public int widthSide() {
        return widthSide;
    }

    public int lampSpacing() {
        return lampSpacing;
    }

    public void setLampSpacing(final int lampSpacing) {
        this.lampSpacing = Math.max(0, lampSpacing);
    }

    /** First endpoint the geometry was built from, or null if there was none. */
    public BlockPos endpointA() {
        return endpointA;
    }

    /** Second endpoint the geometry was built from, or null if there was none. */
    public BlockPos endpointB() {
        return endpointB;
    }

    public void setEndpoints(final BlockPos a, final BlockPos b) {
        this.endpointA = a == null ? null : a.immutable();
        this.endpointB = b == null ? null : b.immutable();
    }

    /** True when this plan remembers the two endpoints it was planned from. */
    public boolean hasEndpoints() {
        return endpointA != null && endpointB != null;
    }

    /** {@code +1} when A sits at the low end of the axis, {@code -1} otherwise. */
    public int travelDir() {
        return travelDir;
    }

    public void setTravelDir(final int travelDir) {
        this.travelDir = travelDir >= 0 ? 1 : -1;
    }

    public List<RoadSegment> segments() {
        return Collections.unmodifiableList(segments);
    }

    public void addSegment(final RoadSegment segment) {
        if (!segments.isEmpty()) {
            final RoadSegment last = segments.get(segments.size() - 1);
            if (last.axis() != segment.axis()) {
                throw new IllegalArgumentException("road segments must share one axis");
            }
        }
        segments.add(segment);
    }

    public void clearSegments() {
        segments.clear();
    }

    public RoadState state() {
        return state;
    }

    public void setState(final RoadState state) {
        this.state = state;
    }

    public int workOrderId() {
        return workOrderId;
    }

    public void setWorkOrderId(final int workOrderId) {
        this.workOrderId = workOrderId;
    }

    public int length() {
        int length = 0;
        for (final RoadSegment segment : segments) {
            length += segment.length();
        }
        return length;
    }

    public Direction.Axis axis() {
        return segments.isEmpty() ? Direction.Axis.X : segments.get(0).axis();
    }

    public RoadBounds bounds(final int lampReserveHeight) {
        return RoadBounds.of(this, lampReserveHeight);
    }

    // ---------------------------------------------------------------- cells

    /** Lowest along-coordinate covered by the segments. */
    public int minAlong() {
        int min = Integer.MAX_VALUE;
        for (final RoadSegment segment : segments) {
            final int first = alongOf(segment.start());
            final int last = RoadAxisMath.along(segment.axis(),segment.endX(),segment.endZ());
            min = Math.min(min, Math.min(first, last));
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    /** Highest along-coordinate covered by the segments. */
    public int maxAlong() {
        int max = Integer.MIN_VALUE;
        for (final RoadSegment segment : segments) {
            final int first = alongOf(segment.start());
            final int last = RoadAxisMath.along(segment.axis(),segment.endX(),segment.endZ());
            max = Math.max(max, Math.max(first, last));
        }
        return max == Integer.MIN_VALUE ? 0 : max;
    }

    /** Along-coordinate of endpoint A, derived from the travel direction. */
    public int alongOfA() {
        return travelDir >= 0 ? minAlong() : maxAlong();
    }

    /** Controller-panel switch; see the {@code allowBlueprintEdit} field. */
    public boolean allowBlueprintEdit() {
        return allowBlueprintEdit;
    }

    public void setAllowBlueprintEdit(final boolean allow) {
        this.allowBlueprintEdit = allow;
    }

    /**
     * Offset across the road for a world position: {@code 0} is the anchor
     * column and the index grows towards {@link #widthSide}, so it runs
     * {@code 0..width-1} while the position is on the paved body.
     */
    public int lateralOf(final BlockPos pos) {
        final boolean alongX = axis() == Direction.Axis.X;
        final int base = alongX ? anchorPos.getZ() : anchorPos.getX();
        final int delta = (alongX ? pos.getZ() : pos.getX()) - base;
        return delta * (widthSide >= 0 ? 1 : -1);
    }

    /**
     * Row index for a world position, or null when the position is not on this
     * road.
     *
     * <p>Only the paved rectangle is accepted (along inside the segments, lateral
     * inside the width); the surrounding ground is not. A ROW is returned, not a
     * column: editing a row changes the whole walkable width, so which column was
     * clicked carries no information.</p>
     */
    public Integer rowAt(final BlockPos pos) {
        if (segments.isEmpty()) {
            return null;
        }
        final int along = alongOf(pos);
        if (along < minAlong() || along > maxAlong()) {
            return null;
        }
        final int lateral = lateralOf(pos);
        if (lateral < 0 || lateral >= width) {
            return null;
        }
        return along;
    }

    public CellType cellType(final int along) {
        return row(along).type();
    }

    /** Full state of one row; a row never touched reads as flat and automatic. */
    public RowState row(final int along) {
        return rows.getOrDefault(along, RowState.FLAT_ROW);
    }

    /** Every stored row, in along order. */
    public Map<Integer, RowState> rows() {
        return Collections.unmodifiableMap(rows);
    }

    /** Records a row's unit type without touching its authority level. */
    public void setRowType(final int along, final CellType type) {
        storeRow(along, row(along).withType(type == null ? CellType.FLAT : type));
    }

    /** Solver-side write: refuses to touch a manual or locked row. */
    public boolean setAutoType(final int along, final CellType type) {
        final RowState current = row(along);
        if (current.level() != RowState.RowLevel.AUTO) return false;
        storeRow(along, current.withType(type == null ? CellType.FLAT : type));
        return true;
    }

    /** Player edit: marks the row manual. Locked rows cannot be edited. */
    public boolean editRow(final int along, final CellType type) {
        final RowState current = row(along);
        if (current.locked()) return false;
        final CellType wanted = type == null ? CellType.FLAT : type;
        storeRow(along, new RowState(wanted, RowState.RowLevel.MANUAL, null, current.lamp(), current.base()));
        return true;
    }

    /** Drops a row back to automatic, keeping its type. */
    public boolean clearManual(final int along) {
        final RowState current = row(along);
        if (!current.manual()) return false;
        storeRow(along, current.withLevel(RowState.RowLevel.AUTO));
        return true;
    }

    /**
     * Locks a row to a surface height; a manual row is upgraded, not preserved.
     *
     * <p>Locking also forces the row to {@code FLAT} (see {@link RowState#withLock}):
     * a locked row is a platform, so leaving a stair type behind would contradict the
     * height the lock stores.</p>
     */
    public void lock(final int along, final int surfaceY) {
        final RowState current = row(along);
        if (current.locked() && Integer.valueOf(surfaceY).equals(current.lockY())) return;
        storeRow(along, current.withLock(surfaceY));
    }

    /** Unlocks a row: back to automatic, height released. */
    public void unlock(final int along) {
        final RowState current = row(along);
        if (!current.locked()) return;
        storeRow(along, new RowState(current.type(), RowState.RowLevel.AUTO, null, current.lamp(), current.base()));
    }

    /** Records the lamp outcome the generator decided for this row. */
    public void setLamp(final int along, final RowState.Lamp lamp) {
        final RowState current = row(along);
        if (lamp == current.lamp()) return;
        storeRow(along, current.withLamp(lamp));
    }

    /** Writes the junction-free baseline of one row (solver output). */
    public void setBase(final int along, final CellType type) {
        storeRow(along, row(along).withBase(type));
        if (type != null) planning.hasBase(true);
    }

    /**
     * Replaces the whole baseline. A null map clears it entirely (the road has no
     * solved baseline any more); an empty map records "solved, and it is flat".
     */
    public void setBaseRows(final Map<Integer, CellType> base) {
        final Set<Integer> touched = new TreeSet<>(rows.keySet());
        if (base != null) touched.addAll(base.keySet());
        for (final int along : touched) {
            storeRow(along, rows.getOrDefault(along, RowState.FLAT_ROW)
                    .withBase(base == null ? null : base.get(along)));
        }
        planning.hasBase(base != null);
    }

    private void storeRow(final int along, final RowState next) {
        if (next == null || next.isDefault()) rows.remove(along);
        else rows.put(along, next);
    }

    /** The junction-free baseline, as a type map. */
    public Map<Integer, CellType> baseRows() {
        final Map<Integer, CellType> out = new TreeMap<>();
        rows.forEach((along, state) -> {
            if (state.base() != null) out.put(along, state.base());
        });
        return Collections.unmodifiableMap(out);
    }

    /**
     * Whether a junction-free baseline was ever solved.
     *
     * <p>Not derivable from the rows: a road with no slope has an empty baseline, which
     * is still a solved baseline. Keeping the flag on the plan level is what tells
     * {@code baseSource} apart from "never solved, fall back to the current rows".</p>
     */
    public boolean hasBase() {
        return planning.hasBase();
    }

    /** Rows the player edited by hand. */
    public Map<Integer, CellType> manualRows() {
        final Map<Integer, CellType> out = new TreeMap<>();
        rows.forEach((along, state) -> {
            if (state.manual()) out.put(along, state.type());
        });
        return Collections.unmodifiableMap(out);
    }

    /** Locked rows and the surface height each is pinned to. */
    public Map<Integer, Integer> lockedRows() {
        final Map<Integer, Integer> out = new TreeMap<>();
        rows.forEach((along, state) -> {
            if (state.locked() && state.lockY() != null) out.put(along, state.lockY());
        });
        return Collections.unmodifiableMap(out);
    }

    /**
     * Lamp outcome per row, for rows the generator actually decided: true when a post
     * stands there, whichever kind. Callers that must tell a junction corner lamp from
     * a regular one read {@link #row(int)} and ask for {@link RowState.Lamp}.
     */
    public Map<Integer, Boolean> lampRows() {
        final Map<Integer, Boolean> out = new TreeMap<>();
        rows.forEach((along, state) -> {
            if (state.lamp() != null) out.put(along, state.hasLamp());
        });
        return Collections.unmodifiableMap(out);
    }

    /**
     * Takes over another plan's row types when the road is rebuilt.
     *
     * <p>Rebuilding (a re-pick) produces a fresh {@link RoadPlan}, so the table the
     * world was built from has to be carried across explicitly. Levels are reset to
     * automatic: the rebuilt plan is a new proposal, not a new authority.</p>
     */
    public void adoptCommittedRows(final Map<Integer, CellType> incoming) {
        // Only the types are replaced. Level, lock height, lamp and baseline belong
        // to this road's own history and must survive a type transplant: clearing the
        // whole table here silently dropped the solver baseline.
        final Set<Integer> touched = new TreeSet<>(rows.keySet());
        if (incoming != null) touched.addAll(incoming.keySet());
        for (final int along : touched) {
            final CellType type = incoming == null
                    ? CellType.FLAT : incoming.getOrDefault(along, CellType.FLAT);
            storeRow(along, rows.getOrDefault(along, RowState.FLAT_ROW).withType(type));
        }
    }

    /** Carries a whole row table across verbatim, levels and all. */
    public void adoptRows(final Map<Integer, RowState> incoming) {
        rows.clear();
        if (incoming != null) incoming.forEach((along, state) -> storeRow(along, state));
    }

    /** Row types as a map, for callers that only care about the shape of the road. */
    public Map<Integer, CellType> rowOverrides() {
        final Map<Integer, CellType> out = new TreeMap<>();
        rows.forEach((along, state) -> {
            if (state.type() != CellType.FLAT) out.put(along, state.type());
        });
        return Collections.unmodifiableMap(out);
    }

    /** How many rows the world was built with; zero before the first build. */
    public int confirmedRowCount() {
        if (appliedGeometry == null && syncedApplied != null) return syncedAppliedCount;
        return appliedRows().size();
    }

    /** Row states as last built. Empty for a road that has never been built. */
    public Map<Integer, RowState> appliedRows() {
        if (appliedRowsCache == null) {
            if (appliedGeometry != null) appliedRowsCache = RoadPlan.load(appliedGeometry).rows();
            else appliedRowsCache = syncedApplied == null ? Map.of() : syncedApplied;
        }
        return appliedRowsCache;
    }

    /**
     * Whether an applied snapshot exists at all.
     *
     * <p>Not the same question as "the applied table is empty": a road built dead flat has
     * no stored rows, and treating that as "never built" would hide every later edit.</p>
     */
    public boolean hasApplied() {
        return appliedGeometry != null || syncedApplied != null;
    }

    /**
     * Difference between the current rows and the applied ones.
     *
     * <p>Empty when nothing was ever built: there is no "applied" to differ from,
     * and treating the whole table as pending would report every auto-solved slope
     * row as waiting for Repair.</p>
     */
    public Map<Integer, CellType> pendingRows() {
        if (!hasApplied()) return Map.of();
        final Map<Integer, RowState> applied = appliedRows();
        final Map<Integer, CellType> out = new TreeMap<>();
        // Only rows the road still covers count as a change. A row dropped by shortening
        // the road is reported through {@link #lostRowCount()} instead: counting it here
        // would report the same row twice and inflate the panel's number.
        final int lo = segments.isEmpty() ? Integer.MIN_VALUE : minAlong();
        final int hi = segments.isEmpty() ? Integer.MAX_VALUE : maxAlong();
        for (final Map.Entry<Integer, RowState> entry : rows.entrySet()) {
            if (entry.getKey() < lo || entry.getKey() > hi) continue;
            final CellType before = applied.containsKey(entry.getKey())
                    ? applied.get(entry.getKey()).type() : CellType.FLAT;
            if (before != entry.getValue().type()) out.put(entry.getKey(), entry.getValue().type());
        }
        for (final Map.Entry<Integer, RowState> entry : applied.entrySet()) {
            if (entry.getKey() < lo || entry.getKey() > hi) continue;
            if (entry.getValue().type() != CellType.FLAT && !rows.containsKey(entry.getKey())) {
                out.put(entry.getKey(), CellType.FLAT);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * Carries an unapplied intent across a rebuild by writing the staged types.
     * Levels are untouched: a staged change is not by itself a decision about who
     * owns the row.
     */
    public void adoptPendingRows(final Map<Integer, CellType> staged) {
        if (staged == null) return;
        staged.forEach(this::setRowType);
    }

    /** How many rows differ from the applied table. */
    public int editedRowCount() {
        return pendingRows().size();
    }

    /**
     * Manual and locked rows the proposal no longer has.
     *
     * <p>Like {@link #pendingRows()} this is a difference between the same two tables —
     * the proposal and the applied one — not a running counter: a row counts as lost when
     * the applied table has it as {@code MANUAL} or {@code LOCK} and the proposal does not
     * carry that level any more (dropped by shortening, unlocked by a relation change, or
     * downgraded by an approved override). Rebuilding the road makes the proposal the new
     * applied table, and the number falls back to zero by itself.</p>
     */
    public int lostRowCount() {
        if (!hasApplied()) return 0;
        int lost = 0;
        for (final Map.Entry<Integer, RowState> entry : appliedRows().entrySet()) {
            if (entry.getValue().level() == RowState.RowLevel.AUTO) continue;
            if (row(entry.getKey()).level() != entry.getValue().level()) lost++;
        }
        return lost;
    }

    /**
     * Re-fits the rows to a new range. There is nothing to shift, flip or mirror:
     * a road only ever grows or shortens along one axis, so filtering is the whole
     * migration.
     *
     * <p>Rows outside the new range are kept (re-extending restores them) but
     * counted from the current table every time, so repeated re-picks cannot
     * accumulate losses.</p>
     *
     * @return how many rows fall outside the new range
     */
    public int stageRemap(final int newAlongA, final int newAlongB) {
        final int newMin = Math.min(newAlongA, newAlongB);
        final int newMax = Math.max(newAlongA, newAlongB);
        // A manual intent outside the new road no longer applies to anything.
        rows.entrySet().removeIf(entry -> entry.getValue().manual()
                && (entry.getKey() < newMin || entry.getKey() > newMax));
        int outside = 0;
        for (final int along : rows.keySet()) {
            if (along < newMin || along > newMax) outside++;
        }
        return outside;
    }

    /**
     * Prunes rows the road no longer covers, once a build succeeds.
     *
     * <p>There is no separate proposal table to merge any more: the current rows
     * are the intent, and "applied" lives in the snapshot. Only the prune remains,
     * because a shrink must not keep rows the world no longer has.</p>
     *
     * @return true when rows were actually dropped
     */
    public boolean pruneOutOfRangeRows() {
        if (segments.isEmpty()) return false;
        final int min = minAlong(), max = maxAlong();
        return rows.keySet().removeIf(along -> along < min || along > max);
    }

    /** Build completed: the current rows become the applied rows. */
    public void commitRows() {
        setAppliedGeometry(geometrySnapshot());
    }

    /** Clears the row table and the terrain sample it was solved from. */
    public void clearRowOverrides() {
        rows.clear();
        planning.clearRows();
    }

    private CompoundTag interruptedConstruction;
    private boolean interrupted;
    public boolean hasInterruptedConstruction() {return interruptedConstruction!=null || interrupted;}
    public CompoundTag interruptedConstruction() {return interruptedConstruction==null ? null : interruptedConstruction.copy();}
    public void setInterruptedConstruction(CompoundTag value) {interruptedConstruction=value==null ? null : value.copy();interrupted=false;}
    private CompoundTag appliedGeometry;
    public CompoundTag appliedGeometry() { return appliedGeometry==null ? null : appliedGeometry.copy(); }
    public void setAppliedGeometry(CompoundTag value) {
        appliedGeometry=value==null ? null : value.copy();
        appliedRowsCache=null;
    }
    public CompoundTag geometrySnapshot() {
        CompoundTag tag=save(); tag.remove("appliedGeometry");tag.remove("interruptedConstruction");tag.remove("repairSession");tag.remove("removalSession");
        return tag;
    }
    private RoadPlanningState planning=new RoadPlanningState();
    public RoadPlanningState planning() { return planning; }
    public void adoptPlanning(RoadPlanningState value) { planning=RoadPlanningState.load(value.save()); }

    private int alongOf(final BlockPos pos) {
        return RoadAxisMath.alongOf(axis(),pos);
    }

    // ------------------------------------------------------------ persistent

    public CompoundTag save() {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putString("dimension", dimension.location().toString());
        tag.put("anchor", NbtUtils.writeBlockPos(anchorPos));
        tag.put("controller", NbtUtils.writeBlockPos(controllerPos));
        tag.putInt("facing", controllerFacing.get3DDataValue());
        tag.putBoolean("mirror", mirror);
        tag.putString("pack", pack);
        tag.putString("variant", variant);
        tag.putString("displayName", displayName);
        tag.putString("roadName", roadName);
        tag.putInt("width", width);
        tag.putInt("walkFrom", walkRange.from());
        tag.putInt("walkTo", walkRange.to());
        tag.putInt("widthSide", widthSide);
        tag.putInt("lampSpacing", lampSpacing);
        tag.putBoolean("allowBlueprintEdit", allowBlueprintEdit);
        if (hasEndpoints()) {
            tag.put("endpointA", NbtUtils.writeBlockPos(endpointA));
            tag.put("endpointB", NbtUtils.writeBlockPos(endpointB));
        }
        tag.putInt("travelDir", travelDir);
        tag.putString("state", state.name());
        tag.putInt("workOrderId", workOrderId);
        if (creator != null) {
            tag.putUUID("creator", creator);
        }

        final ListTag list = new ListTag();
        for (final RoadSegment segment : segments) {
            list.add(segment.save());
        }
        tag.put("segments", list);

        if (!rows.isEmpty()) {
            final ListTag rowList = new ListTag();
            for (final Map.Entry<Integer, RowState> entry : rows.entrySet()) {
                final RowState state = entry.getValue();
                final CompoundTag row = new CompoundTag();
                row.putInt("along", entry.getKey());
                row.putString("type", state.type().name());
                row.putString("level", state.level().name());
                if (state.lockY() != null) row.putInt("y", state.lockY());
                if (state.lamp() != null) row.putString("lamp", state.lamp().name());
                if (state.base() != null) row.putString("base", state.base().name());
                rowList.add(row);
            }
            tag.put("rows", rowList);
        }

        tag.put("planning",planning.save());
        if(appliedGeometry!=null) tag.put("appliedGeometry",appliedGeometry.copy());
        if(interruptedConstruction!=null)tag.put("interruptedConstruction",interruptedConstruction.copy());
        if(appliedBounds!=null) tag.put("appliedBounds",appliedBounds.save());
        if (removalSession != null) tag.put("removalSession",removalSession.save());
        if (repairSession != null) tag.put("repairSession",repairSession.save());
        return tag;
    }

    public static RoadPlan load(final CompoundTag tag) {
        final UUID id = tag.hasUUID("id") ? tag.getUUID("id") : UUID.randomUUID();
        final ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse(tag.getString("dimension")));
        final BlockPos controllerPos = NbtUtils.readBlockPos(tag, "controller").orElse(BlockPos.ZERO);
        final BlockPos anchorPos = NbtUtils.readBlockPos(tag, "anchor").orElse(controllerPos);
        final Direction facing = Direction.from3DDataValue(tag.getInt("facing"));
        final int width = Math.max(1, tag.getInt("width"));
        // Saves from before the walk range existed only carried a width; centre
        // it, which reproduces every symmetric style pack exactly.
        final WalkRange walkRange = tag.contains("walkTo")
                ? WalkRange.of(Math.max(0, tag.getInt("walkFrom")),
                        Math.max(0, tag.getInt("walkTo")))
                : WalkRange.centered(width, Math.max(1, tag.getInt("walkWidth")));
        final RoadPlan plan = new RoadPlan(id, dimension, anchorPos, controllerPos, facing,
                tag.getBoolean("mirror"),
                tag.getString("pack"),
                tag.getString("variant"),
                width,
                walkRange,
                tag.getInt("widthSide"),
                tag.getInt("lampSpacing"));
        plan.setTravelDir(tag.contains("travelDir") ? tag.getInt("travelDir") : 1);
        // Older saves predate the switch; a finished road stays frozen until the
        // player turns it on in the controller panel.
        plan.setAllowBlueprintEdit(tag.getBoolean("allowBlueprintEdit"));
        // Older saves predate the stored endpoints; they simply fall back to the
        // outline box until the player re-picks A/B once.
        plan.setEndpoints(NbtUtils.readBlockPos(tag, "endpointA").orElse(null),
                NbtUtils.readBlockPos(tag, "endpointB").orElse(null));
        try {
            plan.setState(RoadState.valueOf(tag.getString("state")));
        } catch (final IllegalArgumentException ex) {
            plan.setState(RoadState.DRAFT);
        }
        plan.setWorkOrderId(tag.getInt("workOrderId"));
        plan.setDisplayName(tag.getString("displayName"));
        // Saves from before the base name was split off only carry the full label;
        // the base never contains a dash, so it can be recovered exactly.
        plan.setRoadName(tag.contains("roadName")
                ? tag.getString("roadName")
                : RoadNames.baseNameOf(tag.getString("displayName")));
        if (tag.hasUUID("creator")) {
            plan.setCreator(tag.getUUID("creator"));
        }

        final ListTag list = tag.getList("segments", 10);
        for (int i = 0; i < list.size(); i++) {
            plan.addSegment(RoadSegment.load(list.getCompound(i)));
        }

        // Row state: one entry per row, carrying the type, the authority level and
        // the optional lock height, lamp outcome and junction-free baseline.
        //
        // A save without a level predates the level model. It is rejected rather
        // than read as automatic: a stair row would silently become flat, and the
        // next Repair would generate a flat route over the existing steps.
        final CompoundTag planningTag = tag.getCompound("planning");
        for (final String retired : new String[]{"manual", "baseRows", "locks", "lamps"}) {
            if (planningTag.contains(retired)) {
                throw new IllegalArgumentException("Road row state predates the row-level model");
            }
        }
        final ListTag rowList = tag.getList("rows", 10);
        for (int i = 0; i < rowList.size(); i++) {
            final CompoundTag row = rowList.getCompound(i);
            if (!row.contains("level", 8)) {
                throw new IllegalArgumentException("Road row state predates the level model");
            }
            final RowState state = new RowState(
                    parseCellType(row.getString("type")),
                    RowState.RowLevel.valueOf(row.getString("level")),
                    row.contains("y", 3) ? row.getInt("y") : null,
                    parseLamp(row),
                    row.contains("base", 8) ? parseCellType(row.getString("base")) : null);
            plan.storeRow(row.getInt("along"), state);
        }
        if(tag.contains("appliedBounds",10)) plan.appliedBounds=RoadBounds.load(tag.getCompound("appliedBounds"));
        if (tag.contains("removalSession",10)) plan.removalSession=RoadRemovalSession.load(tag.getCompound("removalSession"));
        plan.repairBusy=tag.getBoolean("repairBusy");
        plan.interrupted=tag.getBoolean("interrupted");
        if (tag.contains("repairSession",10)) plan.repairSession=RoadRepairSession.load(tag.getCompound("repairSession"));
        if(tag.contains("interruptedConstruction",10))plan.interruptedConstruction=tag.getCompound("interruptedConstruction").copy();
        if(tag.contains("appliedGeometry",10)) plan.appliedGeometry=tag.getCompound("appliedGeometry").copy();
        if(tag.contains("planning",10)) plan.planning=RoadPlanningState.load(tag.getCompound("planning"));
        // Synced plans carry the junction projection only; the server keeps the real table.
        final Map<UUID,RoadJunctions.Projection> projections=new TreeMap<>();
        final ListTag junctionPairs=planningTag.getList("pairs",10);
        for(int i=0;i<junctionPairs.size();i++) {
            final CompoundTag pair=junctionPairs.getCompound(i);
            final int[] rect=pair.getIntArray("rect");
            final var rule=new RoadPlanningState.Crossing(pair.getBoolean("layered"),pair.getInt("order"),
                    pair.getLong("version"),pair.contains("platform",3) ? pair.getInt("platform") : null);
            projections.put(pair.getUUID("road"),new RoadJunctions.Projection(pair.getUUID("road"),rule,
                    rect.length==4 ? new com.YLFXM.rp.crossroads.road.common.RoadGeometry.Rect(rect[0],rect[1],rect[2],rect[3]) : null,
                    pair.getBoolean("builtPeer")));
        }
        plan.syncedJunctions=Map.copyOf(projections);
        // Synced plans carry no geometry, so the applied table arrives as a projection.
        if (tag.getBoolean("appliedBuilt")) {
            final Map<Integer, RowState> applied = new TreeMap<>();
            final ListTag appliedTypes = tag.getList("appliedTypes", 10);
            for (int i = 0; i < appliedTypes.size(); i++) {
                final CompoundTag row = appliedTypes.getCompound(i);
                applied.put(row.getInt("along"),
                        new RowState(parseCellType(row.getString("type")),
                                row.contains("level", 8) ? RowState.RowLevel.valueOf(row.getString("level"))
                                        : RowState.RowLevel.AUTO, null, null, null));
            }
            plan.syncedApplied = Collections.unmodifiableMap(applied);
            plan.syncedAppliedCount = tag.getInt("appliedCount");
            plan.appliedRowsCache = null;
        }
        return plan;
    }

    /** Reads the lamp outcome; earlier R4 saves stored it as a byte, newer ones as a name. */
    private static RowState.Lamp parseLamp(final CompoundTag row) {
        if (row.contains("lamp", 8)) {
            try {
                return RowState.Lamp.valueOf(row.getString("lamp"));
            } catch (final IllegalArgumentException ex) {
                return null;
            }
        }
        if (row.contains("lamp", 1)) return row.getBoolean("lamp") ? RowState.Lamp.PLAIN : RowState.Lamp.NONE;
        return null;
    }

    private static CellType parseCellType(final String name) {
        try {
            return CellType.valueOf(name);
        } catch (final IllegalArgumentException ex) {
            // Unknown type from a newer save: fall back to flat.
            return CellType.FLAT;
        }
    }
}
