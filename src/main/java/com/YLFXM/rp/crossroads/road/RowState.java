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

/**
 * One row of a road: what it is now, who is allowed to change it, what it is
 * without junction constraints, and whether it carries a lamp.
 *
 * <p>This replaces six parallel tables ({@code rowOverrides}, {@code pendingRows},
 * {@code planning.manual}, {@code planning.baseRows}, {@code planning.locks},
 * {@code planning.lamps}). Only three of those facts are stored here: the current
 * type, the authority level, and the junction-free baseline. "Already applied"
 * lives in the applied snapshot; "pending" is the difference between the two.</p>
 *
 * <p>{@code lockY} is a <b>surface</b> height, not an entry height: the anchor row's
 * pavement sits one block below the anchor block, and a same-level junction row sits
 * at the shared platform height.</p>
 */
public record RowState(CellType type, RowLevel level, Integer lockY, Lamp lamp, CellType base) {
    /** Who may change this row. Ordered from most to least permissive. */
    public enum RowLevel { AUTO, MANUAL, LOCK }

    /**
     * What the generator did about a lamp on this row.
     *
     * <p>{@code CORNER} exists because a junction corner lamp is <b>not</b> on the
     * regular spacing grid and is owned by the crossing rules, not by the road's own
     * spacing: a controller must never migrate onto one, and the anchor cannot move
     * there without shifting every lamp on the road.</p>
     */
    public enum Lamp { NONE, PLAIN, CORNER }

    public static final RowState FLAT_ROW = new RowState(CellType.FLAT, RowLevel.AUTO, null, null, null);

    public RowState {
        if (type == null) type = CellType.FLAT;
        if (level == null) level = RowLevel.AUTO;
        if (level != RowLevel.LOCK) lockY = null;
    }

    /** True when nothing about this row needs storing. */
    public boolean isDefault() {
        return type == CellType.FLAT && level == RowLevel.AUTO && lockY == null && lamp == null && base == null;
    }

    public RowState withType(final CellType next) {
        return new RowState(next, level, lockY, lamp, base);
    }

    public RowState withLevel(final RowLevel next) {
        return new RowState(type, next, lockY, lamp, base);
    }

    /**
     * Pins this row to a surface height.
     *
     * <p>A locked row is by definition a platform: the anchor row and every same-level
     * junction row must be flat ({@code flattenAt} pins them with both a height and the
     * flat constraint). Forcing the type here is what keeps {@code lockY} and the type
     * chain from disagreeing — the height profile integrates rows, it does not read
     * {@code lockY}, so a stair left on a locked row would put the pavement one block
     * off the height the lock records.</p>
     */
    public RowState withLock(final Integer surfaceY) {
        return new RowState(CellType.FLAT, RowLevel.LOCK, surfaceY, lamp, base);
    }

    public RowState withLamp(final Lamp value) {
        return new RowState(type, level, lockY, value, base);
    }

    public RowState withBase(final CellType value) {
        return new RowState(type, level, lockY, lamp, value);
    }

    /** True when a lamp post really stands on this row (regular or corner). */
    public boolean hasLamp() {
        return lamp == Lamp.PLAIN || lamp == Lamp.CORNER;
    }

    /** True only for a regular lamp on the spacing grid; corner lamps are excluded. */
    public boolean plainLamp() {
        return lamp == Lamp.PLAIN;
    }

    public boolean locked() {
        return level == RowLevel.LOCK;
    }

    public boolean manual() {
        return level == RowLevel.MANUAL;
    }
}
