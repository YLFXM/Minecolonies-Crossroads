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

import com.YLFXM.rp.crossroads.ModRegistry;
import com.YLFXM.rp.crossroads.network.ClientRoadCache;
import com.YLFXM.rp.crossroads.road.CellType;
import com.YLFXM.rp.crossroads.road.RoadPlan;
import com.YLFXM.rp.crossroads.road.RoadRules;
import com.YLFXM.rp.crossroads.road.RowState;
import com.YLFXM.rp.crossroads.road.RoadWandBinding;
import com.YLFXM.rp.crossroads.road.common.RoadGeometry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * The row the player is currently aiming at with the road wand.
 *
 * <p>Refreshed once per world-render frame and read by both the world outline and
 * the on-screen label, so the two can never disagree. Everything here is derived
 * from state the client already has: the bound controller comes from the wand
 * (same lookup as the edit gesture), the row from {@link RoadPlan#rowAt}, and the
 * type from {@link RoadPlan#cellType}.</p>
 *
 * <p>This is presentation only. The server still decides whether an edit is
 * allowed; a stale or spoofed client cannot change a row by drawing a box.</p>
 */
public final class RoadAim {
    private static RoadPlan plan;
    /** The aimed row as players count it: 1-based from endpoint A, used by the label. */
    private static int rowNumber;
    private static CellType type = CellType.FLAT;
    /** How the row is owned: the player's, the solver's, or fixed by a junction. */
    private static RowState.RowLevel level = RowState.RowLevel.AUTO;
    private static AABB box;

    /** Recomputes the snapshot for this frame; clears it when nothing qualifies. */
    public static void update(final Minecraft minecraft) {
        clear();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (!(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        if (!holdingWand(minecraft)) {
            return;
        }
        final RoadPlan bound = ClientRoadCache.findByController(
                RoadWandBinding.heldController(minecraft.player.getInventory()));
        if (bound == null || !bound.dimension().equals(minecraft.level.dimension())) {
            return;
        }
        final BlockPos pos = hit.getBlockPos();
        // The controller keeps the open-panel gesture, never the row hint.
        if (minecraft.level.getBlockState(pos).getBlock()
                instanceof com.minecolonies.core.blocks.BlockDecorationController) {
            return;
        }
        final Integer row = bound.rowAt(pos);
        if (row == null || !RoadRules.canEdit(bound)) {
            return;
        }
        final RoadGeometry.Rect body = RoadGeometry.body(bound);
        final double minX;
        final double maxX;
        final double minZ;
        final double maxZ;
        if (bound.axis() == Direction.Axis.X) {
            minX = row;
            maxX = row + 1.0;
            minZ = body.minZ();
            maxZ = body.maxZ() + 1.0;
        } else {
            minZ = row;
            maxZ = row + 1.0;
            minX = body.minX();
            maxX = body.maxX() + 1.0;
        }
        // Vertical span follows the aimed block so a stair row lights up at the
        // step the player is actually looking at, without re-deriving baseY.
        final double y = pos.getY();
        plan = bound;
        rowNumber = bound.rowNumber(row);
        type = bound.cellType(row);
        level = bound.row(row).level();
        box = new AABB(minX, y, minZ, maxX, y + 1.0, maxZ);
    }

    public static void clear() {
        plan = null;
        box = null;
        rowNumber = 0;
        type = CellType.FLAT;
        level = RowState.RowLevel.AUTO;
    }

    public static boolean visible() {
        return plan != null && box != null;
    }

    public static AABB box() {
        return box;
    }

    /** Row as the player counts it: 1 at endpoint A, growing along the travel direction. */
    public static int rowNumber() {
        return rowNumber;
    }

    public static CellType type() {
        return type;
    }

    /**
     * Ownership of the aimed row.
     *
     * <p>Shown next to the row type because the two are otherwise indistinguishable:
     * a hand edit and a solved row look the same on screen, and only the level says
     * whether clearing hand edits will change this row at all.</p>
     */
    public static RowState.RowLevel level() {
        return level;
    }

    /** Same cycle the Shift + right-click gesture applies. */
    public static CellType next() {
        return switch (type) {
            case FLAT -> CellType.STAIR_UP;
            case STAIR_UP -> CellType.STAIR_DOWN;
            case STAIR_DOWN -> CellType.FLAT;
        };
    }

    /** RGB used for the aimed row's outline. */
    public static float[] rgb() {
        return switch (type) {
            case FLAT -> new float[] {1.0F, 1.0F, 1.0F};
            case STAIR_UP -> new float[] {0.25F, 0.9F, 1.0F};
            case STAIR_DOWN -> new float[] {1.0F, 0.65F, 0.2F};
        };
    }

    /** Translation key for a row type, used by the aim label. */
    public static String key(final CellType cell) {
        return switch (cell) {
            case FLAT -> "rp_crossroads.cell.flat";
            case STAIR_UP -> "rp_crossroads.cell.stair_up";
            case STAIR_DOWN -> "rp_crossroads.cell.stair_down";
        };
    }

    private static boolean holdingWand(final Minecraft minecraft) {
        return minecraft.player.getMainHandItem().is(ModRegistry.ROAD_WAND.get())
                || minecraft.player.getOffhandItem().is(ModRegistry.ROAD_WAND.get());
    }

    private RoadAim() {
    }
}
