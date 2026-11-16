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
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/**
 * Keeps per-player road blueprints out of the structure pack every client receives.
 *
 * <p>Structurize syncs a structure pack by zipping the whole directory
 * ({@code ServerStructurePackLoader.zipPack}), so a pack that carries the road being
 * built also carries that blueprint to <em>everyone</em> who joins — including players
 * who are not allowed to see it. The mod's own clean-up only deletes the files
 * afterwards; by then the bytes have been delivered.</p>
 *
 * <p>So the walk is filtered instead: two folders never leave the server.</p>
 * <ul>
 *   <li>{@code generated/} — the blueprint of every road currently under construction.
 *       The player who is actually building it receives the same NBT point-to-point in
 *       {@code OpenBuildToolPayload}, so filtering costs them nothing.</li>
 *   <li>{@code removal/} — the demolition snapshot. Only the server-side builder AI
 *       reads it; a client never does.</li>
 * </ul>
 *
 * <p>{@code initial/} stays: it is the public unit template and it is what the pack
 * version is hashed from.</p>
 *
 * <p>Only this mod's pack is touched ({@link CustomRoadPack#isPackRoot}); any other
 * pack — including ones a user dropped into {@code blueprints/} — is passed through
 * unchanged.</p>
 */
public final class RoadPackPrivacy {
    /** Folders under the pack root that must never be transferred. */
    private static final Set<String> PRIVATE = Set.of("generated", "removal");

    /**
     * Wraps the visitor structurize is about to walk a pack with.
     *
     * @param root     the pack directory being zipped
     * @param delegate the original visitor; called for everything else, unchanged
     * @return a visitor that skips the private folders, or {@code delegate} when this
     *         is not our pack
     */
    public static FileVisitor<? super Path> filter(final Path root, final FileVisitor<? super Path> delegate) {
        if (!CustomRoadPack.isPackRoot(root)) {
            return delegate;
        }
        return new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs)
                    throws IOException {
                if (isPrivate(root, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return delegate.preVisitDirectory(dir, attrs);
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs)
                    throws IOException {
                return delegate.visitFile(file, attrs);
            }

            @Override
            public FileVisitResult visitFileFailed(final Path file, final IOException failure)
                    throws IOException {
                return delegate.visitFileFailed(file, failure);
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path dir, final IOException failure)
                    throws IOException {
                return delegate.postVisitDirectory(dir, failure);
            }
        };
    }

    /** Only the pack root's own children count; a {@code generated/} deeper down is left alone. */
    private static boolean isPrivate(final Path root, final Path dir) {
        if (dir.equals(root) || dir.getFileName() == null || !root.equals(dir.getParent())) {
            return false;
        }
        return PRIVATE.contains(dir.getFileName().toString());
    }

    private RoadPackPrivacy() {
    }
}
