/*
 * Copyright (c) 2026 YLFXM. All rights reserved.
 */
package com.YLFXM.rp.crossroads.mixin;

import com.YLFXM.rp.crossroads.client.RoadUnitNames;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.client.gui.WindowExtendedBuildTool;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Shows our unit previews under their base name instead of their file name.
 *
 * <p>{@code getCustomName} is the single place the tool derives a blueprint's
 * label, and it feeds both the list entry and the grouping map key — so rewriting
 * its result is enough for {@code road_1}/{@code road_2} to collapse into one
 * "road" entry with a {@code Level:} row, exactly like vanilla roads.</p>
 *
 * <p>Client-only and listed in the {@code client} bucket: the target is a client
 * GUI class that never loads on a dedicated server.</p>
 */
@Mixin(WindowExtendedBuildTool.class)
public abstract class WindowExtendedBuildToolNameMixin {
    @ModifyReturnValue(method = "getCustomName", at = @At("RETURN"))
    private String rp$stripUnitLevel(final String name, final Blueprint blueprint, final String defaultName) {
        return RoadUnitNames.display(name, blueprint.getPackName());
    }
}
