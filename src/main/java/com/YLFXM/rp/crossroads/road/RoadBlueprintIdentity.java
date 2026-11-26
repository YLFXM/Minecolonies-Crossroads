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

import com.YLFXM.rp.crossroads.CustomRoadPack;
import com.ldtteam.structurize.blueprints.v1.Blueprint;

/**
 * The pack identity a road blueprint has to carry into a native work order.
 *
 * <p>Native completion ({@code AbstractWorkOrder.onCompleted}) walks the blueprint's
 * block entities and writes these three strings into every decoration controller it
 * finds — including the one on a road's primary cell. A blueprint object built in
 * memory has none of them, so the write throws a {@code NullPointerException} inside
 * {@code COMPLETE_BUILD}: the blocks are done, the order never finishes, the builder is
 * paused and retried, and the plan stays in its in-flight state forever.</p>
 *
 * <p>One place, because both entry points (build and demolition) hand the native flow a
 * blueprint this mod generated.</p>
 */
public final class RoadBlueprintIdentity {
    /** Stamps the pack, directory and file name the native completion expects. */
    public static void stamp(final Blueprint blueprint, final RoadPlan plan) {
        if (blueprint == null || plan == null) return;
        blueprint.setPackName(CustomRoadPack.PACK_NAME);
        blueprint.setFilePath(java.nio.file.Path.of(RoadTemporaryPack.ROOT_DIR));
        blueprint.setFileName(RoadNames.fileName(plan, plan.unitLevel()));
    }

    private RoadBlueprintIdentity() { }
}
