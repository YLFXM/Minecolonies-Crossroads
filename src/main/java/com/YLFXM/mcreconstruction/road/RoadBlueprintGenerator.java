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

import com.ldtteam.structurize.blocks.ModBlocks;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.storage.StructurePacks;
import com.minecolonies.core.blocks.BlockDecorationController;
import com.YLFXM.mcreconstruction.Config;
import com.YLFXM.mcreconstruction.MCReconstruction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Builds an in-memory blueprint from a RoadPlan and a one-block slice template.
 * The blueprint is regenerated after a server restart from the controller's
 * RoadPlan; it is never written to a structure pack.
 */
public final class RoadBlueprintGenerator {
    /**
     * Default tile unit: one slice of the original style pack's long street.
     * Four columns wide, pavement on columns 1 and 2, lamp verge on column 0.
     */
    private static final RoadUnit DEFAULT_UNIT = new RoadUnit(
            "minecolonies/street",
            RoadUnit.UnitType.ROAD,
            com.YLFXM.mcreconstruction.CustomRoadPack.STYLE_PACK,
            "infrastructure/roads/streets",
            "infrastructure/roads/streets/longroad1.blueprint",
            Direction.Axis.X,
            0,
            0,
            3,
            WalkRange.of(1, 2),
            1,
            1);

    /**
     * Lamp unit, cut from the lamp column of the same street blueprint so the
     * material always matches the pavement of its style pack.
     *
     * <p>It owns only two columns — the lamp column and the one the lantern hangs
     * over — and declares no walk range, because a lamp is not pavement. When it
     * is overlaid, the plan's own walk range decides which surface cells it must
     * leave alone, so one unit works on a flat verge and on a stair verge
     * alike.</p>
     */
    private static final RoadUnit LAMP_UNIT = new RoadUnit(
            "minecolonies/street_lamp",
            RoadUnit.UnitType.LAMP,
            com.YLFXM.mcreconstruction.CustomRoadPack.STYLE_PACK,
            "infrastructure/roads/streets",
            "infrastructure/roads/streets/longroad1.blueprint",
            Direction.Axis.X,
            3,
            0,
            1,
            null,
            1,
            0);

    /** The lamp unit the generator overlays every {@code lampSpacing} cells. */
    public static RoadUnit lampUnit() {
        return LAMP_UNIT;
    }

    public static Blueprint generate(final RoadPlan plan, final ServerLevel level, final int lampReserveHeight) {
        final RoadUnit unit = resolveUnit(plan);
        final var packMeta = StructurePacks.getStructurePack(unit.pack());
        final Blueprint source = StructurePacks.getBlueprint(unit.pack(), unit.source(), level.registryAccess());
        if (source == null) {
            MCReconstruction.LOGGER.error("Road source blueprint not found: pack='{}' path='{}' packMeta={} packPath={}",
                    unit.pack(), unit.source(), packMeta != null, packMeta == null ? "null" : packMeta.getPath());
            return null;
        }

        final UnitTemplate template = RoadUnitExtractor.extract(source, unit);
        if (template == null) {
            return null;
        }

        final BlockPos anchorPos = resolveAnchor(level, plan);
        final RoadBounds bounds = RoadBounds.ofRoadOnly(plan, lampReserveHeight);
        final int sizeX = bounds.sizeX();
        // The blueprint origin is the road surface minus surfaceY so the
        // template's foundation is preserved and the visible surface stays at
        // the road's Y level. The anchor/controller may be one block above it.
        final int originY = bounds.minY() - template.surfaceY();
        final int sizeY = bounds.maxY() - originY + 1;
        final int sizeZ = bounds.sizeZ();
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            return null;
        }

        final Blueprint out = new Blueprint((short) sizeX, (short) sizeY, (short) sizeZ, level.registryAccess());

        // Keep the Structurize solid-substitution placeholder for support fill.
        // The native builder/build-tool resolves it using the colony/build-tool
        // fill settings instead of a hardcoded block.
        final BlockState filler = ModBlocks.blockSolidSubstitution.get().defaultBlockState();

        // Clear only the reserved volume above the road footprint. Filling the
        // whole AABB with air would carve holes around an off-road controller.
        final BlockState air = Blocks.AIR.defaultBlockState();
        for (final RoadSegment segment : plan.segments()) {
            final int surfaceY = segment.start().getY();
            for (int y = surfaceY + 1; y <= bounds.maxY(); y++) {
                fillSegmentSlice(out, plan, bounds, originY, segment, y, air, anchorPos);
            }
        }

        // Fill the supporting volume below every higher road cell. Flat roads
        // have no extra blocks here.
        for (final RoadSegment segment : plan.segments()) {
            final int surfaceY = segment.start().getY();
            for (int y = bounds.minY(); y < surfaceY; y++) {
                fillSegmentSlice(out, plan, bounds, originY, segment, y, filler, anchorPos);
            }
        }

        // Place the actual road template blocks.
        for (final RoadSegment segment : plan.segments()) {
            final int length = segment.length();
            for (int i = 0; i < length; i++) {
                // Along the road the cell follows the segment; across it the cell
                // is measured from the anchor column, so a mirrored plan flips
                // the road around the anchor instead of sliding it.
                final int baseX = segment.axis() == Direction.Axis.X
                        ? segment.start().getX() + i : anchorPos.getX();
                final int baseZ = segment.axis() == Direction.Axis.X
                        ? anchorPos.getZ() : segment.start().getZ() + i;
                final int baseY = segment.start().getY();

                for (final UnitTemplate.Entry entry : template.entries()) {
                    final BlockPos local = entry.pos();
                    final int worldX;
                    final int worldZ;
                    final int side = plan.widthSide() >= 0 ? 1 : -1;
                    if (segment.axis() == template.axis()) {
                        if (segment.axis() == Direction.Axis.X) {
                            worldX = baseX;
                            worldZ = baseZ + side * local.getZ();
                        } else {
                            worldZ = baseZ;
                            worldX = baseX + side * local.getX();
                        }
                    } else if (template.axis() == Direction.Axis.X) {
                        // Rotate the X-authored slice so its width runs along Z.
                        worldZ = baseZ;
                        worldX = baseX + side * local.getZ();
                    } else {
                        // Rotate the Z-authored slice so its width runs along X.
                        worldX = baseX;
                        worldZ = baseZ + side * local.getX();
                    }
                    // The source slice has its road surface at local surfaceY;
                    // shift it down so the built surface is exactly A's Y.
                    final int worldY = baseY + local.getY() - template.surfaceY();
                    put(out, bounds, originY, new BlockPos(worldX, worldY, worldZ),
                            entry.state(), entry.tileEntity());
                }
            }
        }

        // Overlay the road lamps. The style pack bakes a lamp column into its own
        // street tile; cutting that column out as a separate unit keeps the
        // material identical while making the spacing ours to choose.
        final int spacing = plan.lampSpacing();
        if (spacing > 1 && !plan.segments().isEmpty()) {
            final UnitTemplate lamp = RoadUnitExtractor.extract(source, LAMP_UNIT);
            if (lamp != null) {
                overlayLamps(out, plan, bounds, originY, lamp, spacing, anchorPos, level);
            }
        }

        // The anchor block is only a temporary marker: the player places it by
        // hand so the controller has something to sit against, and the road is
        // supposed to take that cell over (pavement, or the lamp base when the
        // anchor sits on the lamp column). Writing it into the blueprint would
        // leave a stray anchor block standing in the finished road.

        // Fences and walls store their connections in block state, and the state
        // copied from the source pack was authored for that pack's own axis.
        // Rotating it is fragile (the result depends on exactly how each block
        // class maps mirror/rotation), so instead every connecting block is told
        // to look at its neighbours and wire itself up. That is also what the
        // game does when a fence is placed by hand.
        fixConnections(out);

        // Reserve the inner controller cell with a Structurize placeholder, not
        // air. The builder ignores placeholders, so it will neither place a road
        // block nor dig out the future controller cell.
        //
        // This has to stay the LAST write to the blueprint volume. The lamp unit
        // carves the source pack's own decoration controller out as air, and the
        // lamp sits right next to the anchor; writing the reserve earlier let
        // that air erase the placeholder, so the builder then dug the cell out
        // and no controller could be migrated into it.
        final BlockPos controllerReserve = RoadControllerMigration.target(plan);
        put(out, bounds, originY, controllerReserve,
                com.ldtteam.structurize.blocks.ModBlocks.blockSubstitution.get().defaultBlockState(), null);

        out.setPackName(unit.pack());
        out.setFilePath(java.nio.file.Path.of(unit.source()));
        out.setFileName(RoadNames.fileName(plan));
        // The work order anchor is the controller position. Without this the
        // Blueprint falls back to the centre of the box, which turns the
        // selected edge point into the middle of the road and shifts the
        // surface by surfaceY.
        out.setCachePrimaryOffset(new BlockPos(
                anchorPos.getX() - bounds.minX(),
                anchorPos.getY() - originY,
                anchorPos.getZ() - bounds.minZ()));
        return out;
    }

    /**
     * Lays the lamp unit every {@code spacing} cells.
     *
     * <p>Spacing is measured from the anchor rather than from endpoint A: the
     * anchor is the controller the plan is attached to, so re-picking A/B to make
     * the road longer or shorter leaves the existing lamps where they are instead
     * of sliding the whole row along.</p>
     *
     * <p>Placeholder cells in the unit are skipped on purpose — they only claim
     * that the road owns that ground, which may be flat pavement or a stair whose
     * real block must survive.</p>
     */
    private static void overlayLamps(final Blueprint out, final RoadPlan plan, final RoadBounds bounds,
                                     final int originY, final UnitTemplate lamp,
                                     final int spacing, final BlockPos anchorPos,
                                     final ServerLevel level) {
        final Direction.Axis roadAxis = plan.segments().get(0).axis();
        int minAlong = Integer.MAX_VALUE;
        for (final RoadSegment segment : plan.segments()) {
            final int along = roadAxis == Direction.Axis.X
                    ? segment.start().getX() : segment.start().getZ();
            minAlong = Math.min(minAlong, along);
        }
        if (minAlong == Integer.MAX_VALUE) {
            return;
        }
        final int anchorAlong = (roadAxis == Direction.Axis.X
                ? anchorPos.getX() : anchorPos.getZ()) - minAlong;
        final int side = plan.widthSide() >= 0 ? 1 : -1;
        int dropped = 0;
        BlockPos firstLamp = null;

        for (final RoadSegment segment : plan.segments()) {
            final int length = segment.length();
            for (int i = 0; i < length; i++) {
                // Lateral base is the anchor column (see the road block loop):
                // the lamp pole is lateral 0, so it lands exactly on the anchor.
                final int baseX = segment.axis() == Direction.Axis.X
                        ? segment.start().getX() + i : anchorPos.getX();
                final int baseZ = segment.axis() == Direction.Axis.X
                        ? anchorPos.getZ() : segment.start().getZ() + i;
                final int baseY = segment.start().getY();
                final int along = (roadAxis == Direction.Axis.X ? baseX : baseZ) - minAlong;
                // floorMod, not %: the offset to the anchor is negative for the
                // half of the road before it, and % would return a negative
                // remainder and silently skip every lamp there.
                if (Math.floorMod(along - anchorAlong, spacing) != 0) {
                    continue;
                }
                if (firstLamp == null) {
                    firstLamp = new BlockPos(baseX, baseY, baseZ);
                }
                for (final UnitTemplate.Entry entry : lamp.entries()) {
                    if (entry.placeholder()) {
                        continue;
                    }
                    final BlockPos local = entry.pos();
                    final int worldX;
                    final int worldZ;
                    if (segment.axis() == lamp.axis()) {
                        if (segment.axis() == Direction.Axis.X) {
                            worldX = baseX;
                            worldZ = baseZ + side * local.getZ();
                        } else {
                            worldZ = baseZ;
                            worldX = baseX + side * local.getX();
                        }
                    } else if (lamp.axis() == Direction.Axis.X) {
                        worldZ = baseZ;
                        worldX = baseX + side * local.getZ();
                    } else {
                        worldX = baseX;
                        worldZ = baseZ + side * local.getX();
                    }
                    final int worldY = baseY + local.getY() - lamp.surfaceY();

                    // put() drops out-of-range cells silently; counting them here
                    // is what turns "the lamp is cut in half" into a log line.
                    final int lx = worldX - bounds.minX();
                    final int ly = worldY - originY;
                    final int lz = worldZ - bounds.minZ();
                    if (lx < 0 || ly < 0 || lz < 0
                            || lx >= out.getSizeX() || ly >= out.getSizeY() || lz >= out.getSizeZ()) {
                        dropped++;
                        continue;
                    }
                    put(out, bounds, originY, new BlockPos(worldX, worldY, worldZ),
                            orient(entry.state(), lamp.axis(), segment.axis() == lamp.axis(), side, level,
                                    new BlockPos(worldX, worldY, worldZ)),
                            entry.tileEntity());
                }
            }
        }
        if (dropped > 0) {
            MCReconstruction.LOGGER.warn(
                    "Road {}: {} lamp block(s) fell outside the reserved height/size and were dropped"
                            + " (lampReserveHeight={}). Raise defaultLampReserveHeight if lamps look cut.",
                    plan.id(), dropped, bounds.maxY() - bounds.minY());
        }
        // Mirrored plans put the road on the other side of the anchor; when the
        // result looks shifted rather than flipped this line says which cell the
        // lamps actually landed on. firstLamp is the lamp pole column: it must
        // equal anchorPos in x/z, mirrored or not.
        MCReconstruction.LOGGER.info(
                "Road {} lamps: anchor={} side={} spacing={} width={} firstLamp={} bounds=[{},{},{}]-[{},{},{}]",
                plan.id(), anchorPos, side, spacing, plan.width(), firstLamp,
                bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }

    /**
     * Turns one cell of a unit so its facing follows the road it is laid on.
     *
     * <p>Units are authored along +X. A road that runs along Z turns the whole
     * slice 90 degrees, and a mirrored width side reflects it as well. Fences,
     * walls and posts keep their connections and facing in block state
     * (north/south/east/west, facing), so without this a lamp pole on a
     * north-south road keeps the east-west connections it was authored with and
     * the two overhanging fences render broken.</p>
     *
     * <p>Order matters: rotate first, mirror second. Reflecting before the
     * rotation lands the shape in the wrong quadrant.</p>
     */
    private static BlockState orient(final BlockState state, final Direction.Axis templateAxis,
                                     final boolean sameAxis, final int side,
                                     final ServerLevel level, final BlockPos pos) {
        if (sameAxis) {
            if (side > 0) {
                return state;
            }
            // Reflect across the road line: the width axis is Z for an X-authored
            // unit and X for a Z-authored one.
            return state.mirror(templateAxis == Direction.Axis.X ? Mirror.FRONT_BACK : Mirror.LEFT_RIGHT);
        }
        if (templateAxis == Direction.Axis.X) {
            // Authored +X (along the road) has to become world +Z, authored +Z
            // (across the road) becomes world +-X.
            final BlockState rotated = state.rotate(level, pos, Rotation.CLOCKWISE_90);
            return side > 0 ? rotated.mirror(Mirror.LEFT_RIGHT) : rotated;
        }
        // Authored +Z (along the road) has to become world +X.
        final BlockState rotated = state.rotate(level, pos, Rotation.COUNTERCLOCKWISE_90);
        return side > 0 ? rotated.mirror(Mirror.FRONT_BACK) : rotated;
    }

    private static void fillSegmentSlice(final Blueprint out,
                                         final RoadPlan plan,
                                         final RoadBounds bounds,
                                         final int originY,
                                         final RoadSegment segment,
                                         final int y,
                                         final BlockState filler,
                                         final BlockPos anchorPos) {
        final int length = segment.length();
        for (int i = 0; i < length; i++) {
            // Same lateral base as the road blocks and the lamps: the anchor
            // column. Fill must line up with what it is filling.
            final int baseX = segment.axis() == Direction.Axis.X
                    ? segment.start().getX() + i : anchorPos.getX();
            final int baseZ = segment.axis() == Direction.Axis.X
                    ? anchorPos.getZ() : segment.start().getZ() + i;
            for (int w = 0; w < plan.width(); w++) {
                final int worldX;
                final int worldZ;
                if (segment.axis() == Direction.Axis.X) {
                    worldX = baseX;
                    worldZ = plan.widthSide() >= 0 ? baseZ + w : baseZ - w;
                } else {
                    worldZ = baseZ;
                    worldX = plan.widthSide() >= 0 ? baseX + w : baseX - w;
                }
                put(out, bounds, originY, new BlockPos(worldX, y, worldZ), filler, null);
            }
        }
    }

    private static void put(final Blueprint out,
                            final RoadBounds bounds,
                            final int originY,
                            final BlockPos world,
                            final BlockState state,
                            final CompoundTag tileEntity) {
        final int x = world.getX() - bounds.minX();
        final int y = world.getY() - originY;
        final int z = world.getZ() - bounds.minZ();
        if (x < 0 || y < 0 || z < 0 || x >= out.getSizeX() || y >= out.getSizeY() || z >= out.getSizeZ()) {
            return;
        }

        out.addBlockState(new BlockPos(x, y, z), state);
        if (tileEntity != null) {
            final CompoundTag te = tileEntity.copy();
            te.putInt("x", x);
            te.putInt("y", y);
            te.putInt("z", z);
            out.getTileEntities()[y][z][x] = te;
        }
    }

    private static BlockPos resolveAnchor(final ServerLevel level, final RoadPlan plan) {
        final BlockPos stored = plan.anchorPos();
        if (!stored.equals(plan.controllerPos())) {
            return stored;
        }
        final ResourceLocation anchorId = ResourceLocation.tryParse(Config.roadAnchorBlock());
        if (anchorId == null) {
            return stored;
        }

        final BlockState controllerState = level.getBlockState(plan.controllerPos());
        if (controllerState.hasProperty(DirectionalBlock.FACING)) {
            final Direction support = controllerState.getValue(DirectionalBlock.FACING);
            final BlockPos candidate = plan.controllerPos().relative(support);
            final ResourceLocation placed = BuiltInRegistries.BLOCK.getKey(
                    level.getBlockState(candidate).getBlock());
            if (anchorId.equals(placed)) {
                plan.setAnchorPos(candidate);
                RoadManager.get(level).add(plan);
                return candidate;
            }
        }

        BlockPos found = null;
        int matches = 0;
        for (final Direction direction : Direction.values()) {
            final BlockPos candidate = plan.controllerPos().relative(direction);
            final ResourceLocation placed = BuiltInRegistries.BLOCK.getKey(
                    level.getBlockState(candidate).getBlock());
            if (anchorId.equals(placed)) {
                found = candidate;
                matches++;
            }
        }
        if (matches == 1) {
            plan.setAnchorPos(found);
            RoadManager.get(level).add(plan);
            return found;
        }
        return stored;
    }

    /**
     * Rebuilds the horizontal connections of every fence/wall inside the
     * blueprint from what is actually next to it.
     *
     * <p>Blueprint states are stored as authored by the style pack. Once a unit
     * is laid on a road running in another direction, the copied connections
     * point the wrong way and the pole renders broken. Rather than transform the
     * state (which depends on each block class's own mirror/rotate rules), each
     * connecting cell is rewritten from its neighbours — the same rule the game
     * applies when a fence is placed by hand.</p>
     */
    private static void fixConnections(final Blueprint out) {
        final int sx = out.getSizeX();
        final int sy = out.getSizeY();
        final int sz = out.getSizeZ();
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    final BlockPos pos = new BlockPos(x, y, z);
                    final BlockState state = out.getBlockState(pos);
                    if (!connectsSideways(state)) {
                        continue;
                    }
                    BlockState updated = state;
                    for (final Direction direction : Direction.Plane.HORIZONTAL) {
                        final Property<Boolean> property = sideProperty(updated, direction);
                        if (property == null) {
                            continue;
                        }
                        final BlockPos neighbour = pos.relative(direction);
                        final boolean inside = neighbour.getX() >= 0 && neighbour.getX() < sx
                                && neighbour.getY() >= 0 && neighbour.getY() < sy
                                && neighbour.getZ() >= 0 && neighbour.getZ() < sz;
                        final boolean link = inside && connectsTo(out.getBlockState(neighbour));
                        updated = updated.setValue(property, link);
                    }
                    if (updated != state) {
                        out.addBlockState(pos, updated);
                    }
                }
            }
        }
    }

    /** True when the block keeps north/south/east/west booleans (fence, wall). */
    private static boolean connectsSideways(final BlockState state) {
        return state != null
                && sideProperty(state, Direction.NORTH) != null
                && sideProperty(state, Direction.SOUTH) != null
                && sideProperty(state, Direction.EAST) != null
                && sideProperty(state, Direction.WEST) != null;
    }

    @SuppressWarnings("unchecked")
    private static Property<Boolean> sideProperty(final BlockState state, final Direction direction) {
        final Property<?> property = switch (direction) {
            case NORTH -> state.getProperties().contains(net.minecraft.world.level.block.state.properties.BlockStateProperties.NORTH)
                    ? net.minecraft.world.level.block.state.properties.BlockStateProperties.NORTH : null;
            case SOUTH -> state.getProperties().contains(net.minecraft.world.level.block.state.properties.BlockStateProperties.SOUTH)
                    ? net.minecraft.world.level.block.state.properties.BlockStateProperties.SOUTH : null;
            case EAST -> state.getProperties().contains(net.minecraft.world.level.block.state.properties.BlockStateProperties.EAST)
                    ? net.minecraft.world.level.block.state.properties.BlockStateProperties.EAST : null;
            case WEST -> state.getProperties().contains(net.minecraft.world.level.block.state.properties.BlockStateProperties.WEST)
                    ? net.minecraft.world.level.block.state.properties.BlockStateProperties.WEST : null;
            default -> null;
        };
        return property instanceof final Property<?> p && p.getValueClass() == Boolean.class
                ? (Property<Boolean>) p : null;
    }

    /** Something a fence or wall would visually connect to. */
    private static boolean connectsTo(final BlockState neighbour) {
        if (neighbour == null) {
            return false;
        }
        return connectsSideways(neighbour) || neighbour.isSolid();
    }

    private static RoadUnit resolveUnit(final RoadPlan plan) {
        final RoadUnit registered = RoadUnit.Registry.get(plan.pack() + "/" + plan.variant());
        return registered == null ? DEFAULT_UNIT : registered;
    }

    private RoadBlueprintGenerator() {
    }
}
