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
 * Inclusive range of walkable lateral columns of a road unit.
 *
 * <p>Indices are zero-based inside the unit's own lateral span, measured in the
 * same direction as {@code widthSide}. The original street units are
 * {@code [1, 2]} on a four-column footprint: column 0 is the lamp verge, columns
 * 1 and 2 are pavement, column 3 is the far kerb.</p>
 *
 * <p>A range rather than a plain width, because a style pack may author an
 * asymmetric road (for example a wide pavement on one side only) and collapsing
 * that into a single number would lose which side it is on.</p>
 */
public record WalkRange(int from, int to) {
    public WalkRange {
        if (from < 0) {
            throw new IllegalArgumentException("walk range start must be >= 0");
        }
        if (to < from) {
            throw new IllegalArgumentException("walk range end must be >= start");
        }
    }

    public static WalkRange of(final int from, final int to) {
        return new WalkRange(from, to);
    }

    /**
     * Centres a walk width inside a footprint, which is what every symmetric
     * style pack produces. {@code centered(4, 2)} is {@code [1, 2]}.
     */
    public static WalkRange centered(final int width, final int walkWidth) {
        final int effective = Math.max(1, Math.min(walkWidth, Math.max(1, width)));
        final int start = Math.max(0, (width - effective) / 2);
        return new WalkRange(start, start + effective - 1);
    }

    public boolean contains(final int lateral) {
        return lateral >= from && lateral <= to;
    }

    public int width() {
        return to - from + 1;
    }
}
