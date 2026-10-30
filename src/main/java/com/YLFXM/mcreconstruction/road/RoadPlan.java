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
package com.YLFXM.mcreconstruction.road;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * Per-row overrides, keyed by the world along-coordinate of the row. A row
     * with no entry is {@link CellType#FLAT}.
     *
     * <p>Keyed by row, not by cell: a stair raises the whole walkable width of
     * that row, so a per-column state could only ever describe a physically
     * broken road. Which columns a row affects is decided by {@code walkRange}.</p>
     */
    private final Map<Integer, CellType> rowOverrides = new LinkedHashMap<>();
    /**
     * Unapplied changes, stored as a row-level diff against {@link #rowOverrides}.
     *
     * <p>Only rows whose type differs from the committed table live here, so
     * {@link #editedRowCount()} is literally the size of the pending change.
     * Repair applies them; nothing else writes the committed table.</p>
     *
     * <p>Persisted too: the client has to be able to show how much is waiting for
     * Repair, and a restart in the middle of an edit session must not silently
     * throw the changes away. Only Repair / Clear remove them.</p>
     */
    private Map<Integer, CellType> pendingRows;
    private RoadState state;
    private int workOrderId;
    /** Rows dropped by the most recent re-plan; shown in the controller panel. */
    private int lastDroppedRows;
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

    public static RoadPlan createDraft(final ResourceKey<Level> dimension,
                                       final BlockPos controllerPos,
                                       final Direction facing,
                                       final String pack,
                                       final String variant,
                                       final int width,
                                       final WalkRange walkRange,
                                       final int widthSide,
                                       final int lampSpacing) {
        return new RoadPlan(UUID.randomUUID(), dimension, controllerPos, facing, false, pack, variant,
                width, walkRange, widthSide, lampSpacing);
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

    public boolean isCreatedBy(final UUID player) {
        return creator != null && creator.equals(player);
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

    public void setPack(final String pack) {
        this.pack = pack;
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

    public void setVariant(final String variant) {
        this.variant = variant;
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
            final int last = segment.axis() == Direction.Axis.X ? segment.endX() : segment.endZ();
            min = Math.min(min, Math.min(first, last));
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    /** Highest along-coordinate covered by the segments. */
    public int maxAlong() {
        int max = Integer.MIN_VALUE;
        for (final RoadSegment segment : segments) {
            final int first = alongOf(segment.start());
            final int last = segment.axis() == Direction.Axis.X ? segment.endX() : segment.endZ();
            max = Math.max(max, Math.max(first, last));
        }
        return max == Integer.MIN_VALUE ? 0 : max;
    }

    /** Along-coordinate of endpoint A, derived from the travel direction. */
    public int alongOfA() {
        return travelDir >= 0 ? minAlong() : maxAlong();
    }

    /** Along-coordinate of endpoint B, derived from the travel direction. */
    public int alongOfB() {
        return travelDir >= 0 ? maxAlong() : minAlong();
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
        final CellType staged = pendingRows == null ? null : pendingRows.get(along);
        // A staged FLAT entry means "the player cleared this row", which reads the
        // same as having no entry at all.
        return staged != null ? staged : rowOverrides.getOrDefault(along, CellType.FLAT);
    }

    /**
     * Records a row's unit type in the newest proposal; {@code FLAT} clears it.
     *
     * <p>The proposal is a <b>diff</b>, not a copy: it only holds rows whose type
     * differs from the committed table. Two things follow — "edited" is literally
     * the size of that diff, and a row set back to its committed value simply
     * leaves it again.</p>
     */
    public void setRowType(final int along, final CellType type) {
        final CellType wanted = type == null ? CellType.FLAT : type;
        if (rowOverrides.getOrDefault(along, CellType.FLAT) == wanted) {
            if (pendingRows != null) {
                pendingRows.remove(along);
                if (pendingRows.isEmpty()) {
                    pendingRows = null;
                }
            }
            return;
        }
        if (pendingRows == null) {
            pendingRows = new LinkedHashMap<>();
        }
        pendingRows.put(along, wanted);
    }

    /**
     * Takes over another plan's committed table when the road is rebuilt.
     *
     * <p>Rebuilding (a re-pick) produces a fresh {@link RoadPlan}, so the table the
     * world was built from has to be carried across explicitly — it is the base
     * every proposal is computed against.</p>
     */
    public void adoptCommittedRows(final Map<Integer, CellType> rows) {
        rowOverrides.clear();
        rowOverrides.putAll(rows);
    }

    private static void writeRow(final Map<Integer, CellType> rows, final int along,
                                 final CellType type) {
        if (type == null || type == CellType.FLAT) {
            rows.remove(along);
        } else {
            rows.put(along, type);
        }
    }

    /** The table Repair has committed — what the world was built from. */
    public Map<Integer, CellType> rowOverrides() {
        return Collections.unmodifiableMap(rowOverrides);
    }

    public int confirmedRowCount() {
        return rowOverrides.size();
    }

    /** True while a proposal is waiting for Repair. */
    public boolean hasPendingRows() {
        return pendingRows != null;
    }

    /** The newest unapplied proposal; empty when there is none. */
    public Map<Integer, CellType> pendingRows() {
        return pendingRows == null ? Map.of() : Collections.unmodifiableMap(pendingRows);
    }

    /**
     * Carries an unapplied proposal across when the road is rebuilt.
     *
     * <p>Rebuilding (a re-pick, a lamp-spacing change) produces a fresh
     * {@link RoadPlan}; without this the proposal would be dropped and every
     * unapplied edit would silently revert to the committed values — which also
     * made the panel's diff read 0 right after any re-pick.</p>
     */
    public void adoptPendingRows(final Map<Integer, CellType> rows) {
        if (rows == null || rows.isEmpty()) {
            pendingRows = null;
        } else {
            pendingRows = new LinkedHashMap<>(rows);
        }
    }

    /**
     * How many rows the newest proposal changes relative to the committed table.
     *
     * <p>This is the per-row diff (`dropped` is counted separately). A re-pick that
     * only extends or shortens the road changes no row's type, so it reports 0 —
     * and after Repair the diff is empty by definition.</p>
     */
    public int editedRowCount() {
        if (pendingRows == null) {
            return 0;
        }
        int changed = 0;
        for (final Map.Entry<Integer, CellType> entry : pendingRows.entrySet()) {
            if (rowOverrides.getOrDefault(entry.getKey(), CellType.FLAT) != entry.getValue()) {
                changed++;
            }
        }
        return changed;
    }

    /** Rows dropped by the most recent re-plan; shown in the controller panel. */
    public int lastDroppedRows() {
        return lastDroppedRows;
    }

    public void setLastDroppedRows(final int lastDroppedRows) {
        this.lastDroppedRows = Math.max(0, lastDroppedRows);
    }

    /**
     * Filters the proposal down to the new endpoint range.
     *
     * <p>Rows are keyed by their world along-coordinate, so extending or shrinking
     * a road leaves every surviving row exactly where it was — there is nothing to
     * shift, flip or mirror. Direction can never invert either: before the first
     * build the table is only a proposal, and after it the pick is snapped to the
     * stored orientation. Filtering out-of-range rows is the entire migration.</p>
     *
     * <p>Rows the road no longer covers are counted from the <b>committed</b> table
     * every time, so re-picking repeatedly cannot accumulate losses: a row that
     * comes back into range is still in the committed table with its old value.</p>
     *
     * @return how many committed rows fall outside the new range
     */
    public int stageRemap(final int newAlongA, final int newAlongB) {
        final int newMin = Math.min(newAlongA, newAlongB);
        final int newMax = Math.max(newAlongA, newAlongB);
        if (pendingRows != null) {
            // Unapplied edits outside the new road no longer apply to anything;
            // everything still inside it stays a pending change.
            pendingRows.keySet().removeIf(along -> along < newMin || along > newMax);
            if (pendingRows.isEmpty()) {
                pendingRows = null;
            }
        }
        int dropped = 0;
        for (final int along : rowOverrides.keySet()) {
            if (along < newMin || along > newMax) {
                dropped++;
            }
        }
        lastDroppedRows = dropped;
        planning.prune(newMin,newMax);
        return dropped;
    }

    /**
     * Commits the proposal onto the committed table and prunes rows the road no
     * longer covers.
     *
     * @return true when the table actually changed
     */
    public boolean applyPendingRows() {
        boolean changed = false;
        if (pendingRows != null) {
            for (final Map.Entry<Integer, CellType> entry : pendingRows.entrySet()) {
                writeRow(rowOverrides, entry.getKey(), entry.getValue());
            }
            pendingRows = null;
            changed = true;
        }
        // A shrink only becomes permanent here: until Repair runs, the rows outside
        // the new range are kept so that re-extending the road restores them.
        // Guarded on segments: a plan without geometry reports 0/0 as its range and
        // would prune everything.
        if (!segments.isEmpty()) {
            final int min = minAlong();
            final int max = maxAlong();
            if (rowOverrides.keySet().removeIf(along -> along < min || along > max)) {
                changed = true;
            }
        }
        if (changed) {
            lastDroppedRows = 0;
        }
        return changed;
    }

    public void clearRowOverrides() {
        planning.clearRows();
        rowOverrides.clear();
        pendingRows = null;
        lastDroppedRows = 0;
    }

    private CompoundTag interruptedConstruction;
    private boolean interrupted;
    public boolean hasInterruptedConstruction() {return interruptedConstruction!=null || interrupted;}
    public CompoundTag interruptedConstruction() {return interruptedConstruction==null ? null : interruptedConstruction.copy();}
    public void setInterruptedConstruction(CompoundTag value) {interruptedConstruction=value==null ? null : value.copy();interrupted=false;}
    private CompoundTag appliedGeometry;
    public CompoundTag appliedGeometry() { return appliedGeometry==null ? null : appliedGeometry.copy(); }
    public void setAppliedGeometry(CompoundTag value) { appliedGeometry=value==null ? null : value.copy(); }
    public CompoundTag geometrySnapshot() {
        CompoundTag tag=save(); tag.remove("appliedGeometry");tag.remove("interruptedConstruction");tag.remove("repairSession");tag.remove("removalSession");
        return tag;
    }
    private RoadPlanningState planning=new RoadPlanningState();
    public RoadPlanningState planning() { return planning; }
    public void adoptPlanning(RoadPlanningState value) { planning=RoadPlanningState.load(value.save()); }

    private int alongOf(final BlockPos pos) {
        return axis() == Direction.Axis.X ? pos.getX() : pos.getZ();
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

        if (!rowOverrides.isEmpty()) {
            final ListTag rows = new ListTag();
            for (final Map.Entry<Integer, CellType> entry : rowOverrides.entrySet()) {
                final CompoundTag row = new CompoundTag();
                row.putInt("along", entry.getKey());
                row.putString("type", entry.getValue().name());
                rows.add(row);
            }
            tag.put("rows", rows);
        }
        // Persisted as well: a proposal has to survive a restart and to reach the
        // client, otherwise the controller panel could not show that Repair is
        // still waiting for something. Repair (or Clear) is what removes it, so a
        // stale proposal is never silently applied by anything else.
        if (pendingRows != null) {
            final ListTag pending = new ListTag();
            for (final Map.Entry<Integer, CellType> entry : pendingRows.entrySet()) {
                final CompoundTag row = new CompoundTag();
                row.putInt("along", entry.getKey());
                row.putString("type", entry.getValue().name());
                pending.add(row);
            }
            tag.put("pendingRows", pending);
        }
        tag.putInt("lastDroppedRows", lastDroppedRows);
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

        // Current format: one entry per edited row. Written straight into the
        // committed table — setRowType stages into the proposal instead.
        final ListTag rows = tag.getList("rows", 10);
        for (int i = 0; i < rows.size(); i++) {
            final CompoundTag row = rows.getCompound(i);
            writeRow(plan.rowOverrides, row.getInt("along"), parseCellType(row.getString("type")));
        }
        // Older saves stored one entry per CELL. Collapse them onto their row;
        // a row that disagrees with itself keeps the last entry, which is the only
        // thing a per-row model can do.
        final ListTag cells = tag.getList("cells", 10);
        for (int i = 0; i < cells.size(); i++) {
            final CompoundTag cell = cells.getCompound(i);
            writeRow(plan.rowOverrides, cell.getInt("along"), parseCellType(cell.getString("type")));
        }
        plan.setLastDroppedRows(tag.getInt("lastDroppedRows"));
        // Staged proposal, if the player has edits Repair has not applied yet.
        // Read after the committed rows: the two tables are loaded separately and
        // assigning the field directly keeps them from mixing.
        if (tag.contains("pendingRows", 9)) {
            final ListTag pending = tag.getList("pendingRows", 10);
            final Map<Integer, CellType> staged = new LinkedHashMap<>();
            for (int i = 0; i < pending.size(); i++) {
                final CompoundTag row = pending.getCompound(i);
                staged.put(row.getInt("along"), parseCellType(row.getString("type")));
            }
            plan.pendingRows = staged;
        }
        if(tag.contains("appliedBounds",10)) plan.appliedBounds=RoadBounds.load(tag.getCompound("appliedBounds"));
        if (tag.contains("removalSession",10)) plan.removalSession=RoadRemovalSession.load(tag.getCompound("removalSession"));
        plan.repairBusy=tag.getBoolean("repairBusy");
        plan.interrupted=tag.getBoolean("interrupted");
        if (tag.contains("repairSession",10)) plan.repairSession=RoadRepairSession.load(tag.getCompound("repairSession"));
        if(tag.contains("interruptedConstruction",10))plan.interruptedConstruction=tag.getCompound("interruptedConstruction").copy();
        if(tag.contains("appliedGeometry",10)) plan.appliedGeometry=tag.getCompound("appliedGeometry").copy();
        if(tag.contains("planning",10)) plan.planning=RoadPlanningState.load(tag.getCompound("planning"));
        return plan;
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
