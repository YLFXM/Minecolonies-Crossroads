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

import com.YLFXM.rp.crossroads.road.common.RoadGeometry;
import com.YLFXM.rp.crossroads.road.common.RoadGeometry.Rect;

/**
 * Junction geometry shared by planning, decoration and validation.
 *
 * <p>The footprint itself lives in {@link RoadGeometry}; this class keeps the junction
 * rules that need road semantics (a platform must be one flat level).</p>
 */
public final class RoadCrossingGeometry {
    public static Rect body(final RoadPlan plan) {
        return RoadGeometry.body(plan);
    }

    public static Rect walk(final RoadPlan plan) {
        return RoadGeometry.walk(plan);
    }

    public static Rect footprint(final RoadPlan plan, final int firstLateral, final int lastLateral) {
        return RoadGeometry.footprint(plan, firstLateral, lastLateral);
    }

    public static int platform(final RoadPlan p, final Rect intersection) {
        final RoadHeightProfile profile = RoadHeightProfile.of(p);
        Integer height = null;
        for (int i = intersection.first(p); i <= intersection.last(p); i++) {
            final var row = profile.row(i);
            if (row.type() != CellType.FLAT) {
                throw new IllegalArgumentException("The crossing on " + p.displayName()
                        + " contains stairs; prepare a joint flat platform first.");
            }
            if (height != null && height != row.baseY()) throw new IllegalArgumentException("A junction must be one level platform.");
            height = row.baseY();
        }
        if (height == null) throw new IllegalArgumentException("Empty junction");
        return height;
    }

    private RoadCrossingGeometry() {
    }
}
