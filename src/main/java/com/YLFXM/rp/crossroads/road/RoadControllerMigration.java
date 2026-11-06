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

import com.YLFXM.rp.crossroads.Crossroads;
import com.YLFXM.rp.crossroads.network.BindRoadWandPayload;
import com.minecolonies.core.blocks.BlockDecorationController;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Decouples the visible controller from the generated road blueprint. After a
 * strict-width road is built, the old outside controller is removed and a new
 * controller is placed on the inner edge, next to the anchor, with the same
 * RoadPlan id/name.
 *
 * <p>Ordering matters: the replacement is always placed and verified
 * <em>first</em>, and the old controller is only removed afterwards. A failed
 * placement must never leave the road without a controller.</p>
 */
public final class RoadControllerMigration {
    private static final ResourceLocation NATIVE_CONTROLLER =
            ResourceLocation.parse("minecolonies:decorationcontroller");

    /**
     * Controller cell reserved inside the road, next to the anchor. This is also
     * the cell the blueprint reserves with a Structurize placeholder, so both
     * sides must agree on the same choice.
     */
    public static BlockPos target(final RoadPlan plan) {
        return candidates(plan).get(0);
    }

    /**
     * Cells the controller may move into, best candidate first. The first entry
     * is always {@link #target(RoadPlan)}; the second is the opposite side of the
     * anchor and is only used when the preferred cell cannot hold the block.
     */
    public static List<BlockPos> candidates(final RoadPlan plan) {
        final List<BlockPos> result = new ArrayList<>(2);
        if (plan.segments().isEmpty()) {
            result.add(plan.controllerPos());
            return result;
        }

        final RoadSegment segment = plan.segments().get(0);
        final boolean widthAlongZ = segment.axis() == Direction.Axis.X;
        final Direction positive = widthAlongZ ? Direction.SOUTH : Direction.EAST;
        final Direction negative = positive.getOpposite();
        final RoadBounds body = RoadBounds.ofBody(plan);
        final BlockPos anchor = plan.anchorPos();

        final BlockPos plus = anchor.relative(positive);
        final BlockPos minus = anchor.relative(negative);
        // Prefer the cell that really sits inside the paved body; an off-road
        // anchor would otherwise push the controller outside the road.
        if (insideFootprint(body, plus) && !insideFootprint(body, minus)) {
            result.add(plus);
            result.add(minus);
        } else if (insideFootprint(body, minus) && !insideFootprint(body, plus)) {
            result.add(minus);
            result.add(plus);
        } else {
            result.add(plus);
            result.add(minus);
        }
        return result;
    }

    /**
     * Nearest row that can take both the controller and the reference frame.
     *
     * <p>A row qualifies only when it carries a <b>regular</b> lamp and is a flat row:
     * a corner lamp sits at a junction column outside this road's spacing grid, so moving
     * there would shift every lamp on the road, and a stair row cannot be the anchor at
     * all ({@link RoadHeightProfile} integrates heights from it).</p>
     */
    public static Integer nearestLampRow(final RoadPlan plan, final int anchorRow) {
        for (int distance = 1; distance <= 64; distance++) {
            for (final int row : new int[]{anchorRow + distance, anchorRow - distance}) {
                if (row < plan.minAlong() || row > plan.maxAlong()) continue;
                final RowState state = plan.row(row);
                if (state.plainLamp() && state.type() == CellType.FLAT) return row;
            }
        }
        return null;
    }
    /**
     * Moves the plan's reference frame to the row the controller just landed on.
     *
     * <p>Only data moves: the anchor coordinate, the stored blueprints' frame offsets and
     * the row locks. No world block is placed, removed or rewritten — the stone marker the
     * player put down at planning time stays where it is, and so does every built cell.</p>
     */
    private static void moveFrame(final ServerLevel level, final RoadPlan plan, final int row, final int entryY) {
        // A frozen blueprint or an interrupted build still refers to the old frame;
        // moving it under those snapshots would shift what they mean.
        if (plan.hasInterruptedConstruction() || plan.repairSession() != null || plan.removalSession() != null) {
            Crossroads.LOGGER.info("Road {}: frame left at the old anchor while a session is open", plan.id());
            return;
        }
        final BlockPos delta = plan.reanchorTo(row, entryY);
        if (delta == null) return;
        RoadBlueprintStore.shiftFrame(level, plan, delta);
        RoadLockReconcile.apply(level, plan);
        Crossroads.LOGGER.info("Road {}: reference frame moved by {} to row {}", plan.id(), delta, row);
    }

    /**
     * The unified row table decides whether the anchor row can keep the controller.
     *
     * <p>R4 merged six row tables into {@code rows} and made lamps three-way.
     * {@code lampRows()} is the legacy boolean view: {@code RowState.hasLamp()} counts
     * a junction {@code CORNER} lamp as a real post, which would leave the controller
     * on a row whose lamp is not on this road's spacing grid. Only a {@code PLAIN}
     * lamp — or a row the generator never judged — keeps the controller on the anchor.</p>
     */
    public static boolean anchorLampSuppressed(final RoadPlan plan, final int anchorRow) {
        final RowState.Lamp lamp = plan.row(anchorRow).lamp();
        return lamp == RowState.Lamp.NONE || lamp == RowState.Lamp.CORNER;
    }

    public static boolean migrate(final ServerLevel level, final RoadPlan plan) {
        if (plan.segments().isEmpty()) {
            return false;
        }
        // The generator records per-row lamp outcomes in the unified row table; a junction may
        // suppress the anchor lamp or replace it with a corner lamp. In both cases the controller
        // must move to the nearest row that really carries a regular lamp.
        final int anchorRow = RoadHeightProfile.anchorAlong(plan);
        if (anchorLampSuppressed(plan, anchorRow)) {
            final Integer row = nearestLampRow(plan, anchorRow);
            if (row != null) {
                final RoadHeightProfile profile = RoadHeightProfile.of(plan);
                final int baseY = profile.row(row).baseY();
                // Remember the height before the anchor moves: it is what keeps every row
                // of the profile exactly where it is.
                final int entryY = profile.row(row).entryY();
                final BlockPos lamp = RoadHeightProfile.position(plan, row, 0, baseY + 1);
                final BlockPos beside = RoadHeightProfile.position(plan, row, plan.widthSide(), baseY + 1);
                final BlockPos oldPos = plan.controllerPos();
                final BlockState oldState = level.getBlockState(oldPos);
                if (RoadCrossingGeometry.body(plan).contains(beside.getX(), beside.getZ())
                        && place(level, plan, beside, oldPos, oldState, isController(oldState), lamp)) {
                    moveFrame(level, plan, row, entryY);
                    Crossroads.LOGGER.info("Controller moved to lamp row {} for road {}", row, plan.id());
                    return true;
                }
            }
            Crossroads.LOGGER.warn("No surviving lamp row for road {}; keeping the previous controller position", plan.id());
        }

        final List<BlockPos> candidates = candidates(plan);
        final BlockPos preferred = candidates.get(0);
        // Idempotent fast path, restricted to the PREFERRED cell. The old
        // outside controller always sits on the mirror cell of the anchor
        // (anchor + facing), which is candidate #2 -- accepting any candidate
        // would report "already migrated" and skip the move entirely.
        if (preferred.equals(plan.controllerPos()) && isController(level.getBlockState(preferred))) {
            Crossroads.LOGGER.debug("Controller already on the reserved cell for road {}", plan.id());
            return true;
        }

        final BlockPos oldPos = plan.controllerPos();
        final BlockState oldState = level.getBlockState(oldPos);
        final boolean oldRemovable = isController(oldState);

        for (final BlockPos target : candidates) {
            if (place(level, plan, target, oldPos, oldState, oldRemovable, plan.anchorPos())) {
                return true;
            }
        }
        Crossroads.LOGGER.error(
                "Controller migration failed for road {}: no cell near anchor {} accepted the controller",
                plan.id(), plan.anchorPos());
        return false;
    }

    private static boolean place(final ServerLevel level, final RoadPlan plan, final BlockPos target,
                                 final BlockPos oldPos, final BlockState oldState,
                                 final boolean oldRemovable, final BlockPos focus) {
        final BlockState desired = controllerState(plan, target, oldState, focus);
        if (desired == null) {
            return false;
        }

        // The controller wins this cell unconditionally: a leftover structurize
        // substitution placeholder (the reserved cell the blueprint writes) or a
        // road block from an instant placement must not survive here.
        if (!target.equals(oldPos)) {
            final BlockState existing = level.getBlockState(target);
            if (!existing.isAir()) {
                level.removeBlock(target, false);
            }
        }

        level.setBlock(target, desired, 3);
        if (!isController(level.getBlockState(target))) {
            Crossroads.LOGGER.warn(
                    "Road {}: controller placement at {} was rejected by the level (state now {})",
                    plan.id(), target, level.getBlockState(target));
            return false;
        }

        // Only now is it safe to drop the old controller.
        if (!target.equals(oldPos) && oldRemovable && isController(level.getBlockState(oldPos))) {
            level.removeBlock(oldPos, false);
        }

        plan.setControllerPos(target);
        RoadManager.get(level).add(plan);
        rebindWands(level, oldPos, target);

        Crossroads.LOGGER.info("Migrated controller for road {} from {} to {}",
                plan.id(), oldPos, target);
        return true;
    }

    /**
     * Copies the old controller state when possible so tweaks such as MIRROR or
     * WATERLOGGED survive, then points FACING at the anchor (the block the
     * controller hangs on).
     */
    private static BlockState controllerState(final RoadPlan plan, final BlockPos target,
                                              final BlockState oldState, final BlockPos focus) {
        BlockState base;
        if (isController(oldState)) {
            base = oldState;
        } else {
            final Block block = BuiltInRegistries.BLOCK.get(NATIVE_CONTROLLER);
            if (!(block instanceof BlockDecorationController decorationController)) {
                Crossroads.LOGGER.error(
                        "{} is not a decoration controller; cannot migrate road {}",
                        NATIVE_CONTROLLER, plan.id());
                return null;
            }
            base = decorationController.defaultBlockState();
        }

        final Property<Direction> facing = facingProperty(base);
        if (facing == null) {
            return base;
        }
        return base.setValue(facing, directionTo(target, focus));
    }

    private static Property<Direction> facingProperty(final BlockState state) {
        if (state.hasProperty(DirectionalBlock.FACING)) {
            return DirectionalBlock.FACING;
        }
        for (final Property<?> property : state.getProperties()) {
            if (property.getValueClass() == Direction.class) {
                @SuppressWarnings("unchecked")
                final Property<Direction> directionProperty = (Property<Direction>) property;
                return directionProperty;
            }
        }
        return null;
    }

    /** Single-axis direction from {@code from} to {@code to}; NORTH when equal. */
    private static Direction directionTo(final BlockPos from, final BlockPos to) {
        final int dx = to.getX() - from.getX();
        final int dz = to.getZ() - from.getZ();
        if (dx == 0 && dz == 0) {
            return Direction.NORTH;
        }
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /**
     * Horizontal footprint test. Height is deliberately ignored: the anchor may
     * legitimately sit one block above the road surface, and only the width side
     * matters when choosing where the controller goes.
     */
    private static boolean insideFootprint(final RoadBounds bounds, final BlockPos pos) {
        return pos.getX() >= bounds.minX() && pos.getX() <= bounds.maxX()
                && pos.getZ() >= bounds.minZ() && pos.getZ() <= bounds.maxZ();
    }

    static boolean isController(final BlockState state) {
        return state.getBlock() instanceof BlockDecorationController;
    }

    private static void rebindWands(final ServerLevel level, final BlockPos previous, final BlockPos target) {
        for (final ServerPlayer player : List.copyOf(level.players())) {
            // Item binding only; there is no player-level fallback any more.
            if (!RoadWandBinding.rebind(player.getInventory(), previous, target)) continue;
            player.getInventory().setChanged();
            if (player.containerMenu != null) player.containerMenu.broadcastChanges();
            PacketDistributor.sendToPlayer(player, new BindRoadWandPayload(previous, target));
        }
    }

    private RoadControllerMigration() {
    }
}
