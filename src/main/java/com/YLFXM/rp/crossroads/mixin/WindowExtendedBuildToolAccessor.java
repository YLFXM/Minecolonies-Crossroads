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
package com.YLFXM.rp.crossroads.mixin;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.client.gui.WindowExtendedBuildTool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Access to the native build tool's private selection entry point.
 *
 * <p>{@code setBlueprint} is where the window writes the preview cache, the tree
 * label, the ground offset and {@code selectedBlueprint}, and its list marks an
 * entry as selected by comparing against that cache. Re-implementing those four
 * steps would drift, so the mod calls the original instead.</p>
 *
 * <p>Client-only: listed in the {@code client} bucket, never applied on a
 * dedicated server, and never referenced from common code.</p>
 */
@Mixin(WindowExtendedBuildTool.class)
public interface WindowExtendedBuildToolAccessor {
    @Invoker("setBlueprint")
    void rp$setBlueprint(Blueprint blueprint);
}
