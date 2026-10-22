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
 * Persistent road plan. The controller block entity owns this data; a
 * WorkOrder only carries the controller position and a synthetic structure
 * path.
 *
 * <p>Cells are addressed by {@code (along, lateral)} rather than by world
 * position: {@code along} is the world coordinate on the road's axis and
 * {@code lateral} is the column index inside the road width, counted in the
 * {@code widthSide} direction. Keeping the vertical coordinate out of the key is
 * what lets a cell be turned into a stair without invalidating every key after
 * it, and it makes re-planning a pure translation along the axis.</p>
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
     * Direction of travel along {@link #axis()}, {@code +1} or {@code -1}: with
     * {@code +1} the player clicked A at the low end of the axis. Cell stair
     * directions are stored relative to this, so it has to survive a save.
     */
    private int travelDir = 1;
    private final List<RoadSegment> segments = new ArrayList<>();
    /** Per-cell overrides; a cell with no entry is {@link CellType#FLAT}. */
    private final Map<CellKey, CellType> cellOverrides = new LinkedHashMap<>();
    private RoadState state;
    private int workOrderId;
    /**
     * Player who planned this road. Wilderness roads are only visible to their
     * creator, so this has to survive a save/load. Older saves have no such tag
     * and load as {@code null}, which means "nobody" — only colony managers can
     * see the road then.
     */
    private UUID creator;

    /** Addressing key of one road cell. */
    public record CellKey(int along, int lateral) {
    }

    /** What a re-plan did to the per-cell overrides: reported to the player. */
    public record Remap(boolean flippedStairs, boolean mirroredSide, int dropped) {
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

    public CellType cellType(final int along, final int lateral) {
        return cellOverrides.getOrDefault(new CellKey(along, lateral), CellType.FLAT);
    }

    public void setCellType(final int along, final int lateral, final CellType type) {
        final CellKey key = new CellKey(along, lateral);
        if (type == null || type == CellType.FLAT) {
            cellOverrides.remove(key);
        } else {
            cellOverrides.put(key, type);
        }
    }

    public Map<CellKey, CellType> cellOverrides() {
        return Collections.unmodifiableMap(cellOverrides);
    }

    public int cellOverrideCount() {
        return cellOverrides.size();
    }

    /**
     * Re-anchors every per-cell override after the player re-selected A and B.
     *
     * <p>Must be called <b>before</b> the segments and the travel direction are
     * replaced, because it reads the old travel direction from this plan.</p>
     *
     * <p>There is deliberately <b>no shift</b>. Cells are keyed by their world
     * coordinate on the axis, so a cell the player customised keeps pointing at
     * the same block no matter how the endpoints move: extending the road, moving
     * an endpoint, or re-clicking the same pair all leave the table where it is.
     * The alternative — measuring each cell's distance from A and replaying it
     * from the new A — looks equivalent but is not: extending a road at the A end
     * would slide the whole table along the road, so a stair the player placed at
     * x=3 would end up marked at x=-2 on ground that was never built. Only two
     * things actually change identity:</p>
     *
     * <ul>
     *   <li><b>Travel direction.</b> Stairs are stored relative to A to B, so if
     *       the endpoints come back in the opposite order every stair is flipped.
     *       The physical slope must not invert just because the clicks did.</li>
     *   <li><b>Width side.</b> Mirroring the road mirrors the lateral index.</li>
     * </ul>
     *
     * @return whether stairs were flipped, whether the sides were mirrored, and
     *         how many cells fell outside the new road and were dropped.
     */
    public Remap remapCellOverrides(final int newAlongA, final int newAlongB, final int newWidthSide) {
        final int newDir = Integer.signum(newAlongB - newAlongA);
        final boolean flipStairs = newDir != 0 && newDir != travelDir;
        final boolean mirrorSide = (newWidthSide >= 0) != (widthSide >= 0);
        final int newMin = Math.min(newAlongA, newAlongB);
        final int newMax = Math.max(newAlongA, newAlongB);

        if (!flipStairs && !mirrorSide) {
            // Common case: the road only grew or shrank, so entries already point
            // at the right cells and only out-of-range ones need removing.
            int dropped = 0;
            final var iterator = cellOverrides.entrySet().iterator();
            while (iterator.hasNext()) {
                final int along = iterator.next().getKey().along();
                if (along < newMin || along > newMax) {
                    iterator.remove();
                    dropped++;
                }
            }
            return new Remap(false, false, dropped);
        }

        final Map<CellKey, CellType> moved = new LinkedHashMap<>();
        int dropped = 0;
        for (final Map.Entry<CellKey, CellType> entry : cellOverrides.entrySet()) {
            final int along = entry.getKey().along();
            if (along < newMin || along > newMax) {
                dropped++;
                continue;
            }
            final int lateral = mirrorSide
                    ? width - 1 - entry.getKey().lateral()
                    : entry.getKey().lateral();
            if (lateral < 0 || lateral >= width) {
                dropped++;
                continue;
            }
            moved.put(new CellKey(along, lateral),
                    flipStairs ? entry.getValue().flipped() : entry.getValue());
        }
        cellOverrides.clear();
        cellOverrides.putAll(moved);
        return new Remap(flipStairs, mirrorSide, dropped);
    }

    public void clearCellOverrides() {
        cellOverrides.clear();
    }

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

        if (!cellOverrides.isEmpty()) {
            final ListTag cells = new ListTag();
            for (final Map.Entry<CellKey, CellType> entry : cellOverrides.entrySet()) {
                final CompoundTag cell = new CompoundTag();
                cell.putInt("along", entry.getKey().along());
                cell.putInt("lateral", entry.getKey().lateral());
                cell.putString("type", entry.getValue().name());
                cells.add(cell);
            }
            tag.put("cells", cells);
        }
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

        final ListTag cells = tag.getList("cells", 10);
        for (int i = 0; i < cells.size(); i++) {
            final CompoundTag cell = cells.getCompound(i);
            try {
                plan.setCellType(cell.getInt("along"), cell.getInt("lateral"),
                        CellType.valueOf(cell.getString("type")));
            } catch (final IllegalArgumentException ex) {
                // Unknown cell type from a newer save: fall back to flat.
                plan.setCellType(cell.getInt("along"), cell.getInt("lateral"), CellType.FLAT);
            }
        }
        return plan;
    }
}
