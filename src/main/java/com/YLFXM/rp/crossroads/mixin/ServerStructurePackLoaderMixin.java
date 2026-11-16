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

import com.YLFXM.rp.crossroads.road.RoadPackPrivacy;
import com.ldtteam.structurize.storage.ServerStructurePackLoader;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Stops the road pack's private blueprints from being broadcast.
 *
 * <p>Structurize zips a pack by walking its directory with an anonymous
 * {@code FileVisitor}; there is no filter hook. Wrapping that single
 * {@code Files.walkFileTree} call lets {@link RoadPackPrivacy} skip
 * {@code generated/} and {@code removal/} without touching the zip format, the
 * {@code pack.json} entry or the pack version — all of which stay upstream's job.</p>
 *
 * <p>Deliberately no {@code require = 0}: if structurize changes how it walks a pack,
 * this must fail loudly instead of silently shipping other players' blueprints again.</p>
 */
@Mixin(ServerStructurePackLoader.class)
public abstract class ServerStructurePackLoaderMixin {
    @WrapOperation(
            method = "zipPack",
            at = @At(value = "INVOKE",
                     target = "Ljava/nio/file/Files;walkFileTree(Ljava/nio/file/Path;Ljava/nio/file/FileVisitor;)Ljava/nio/file/Path;"))
    private static Path rp$withoutPrivateBlueprints(final Path root, final FileVisitor<? super Path> visitor,
                                                    final Operation<Path> original) {
        return original.call(root, RoadPackPrivacy.filter(root, visitor));
    }
}
