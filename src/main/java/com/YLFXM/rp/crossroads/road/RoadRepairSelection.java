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

import net.minecraft.core.BlockPos;

/** Shared native selection checks; Windows pack paths retain backslashes in upstream getSubPath. */
public final class RoadRepairSelection {
    public enum Mismatch { NONE, PACK, PATH, TIER, ANCHOR, ROTATION }
    public static String path(String value) { return value==null ? "" : value.replace('\\','/'); }

    /** Directory part of a pack path, separators normalised; empty when the path has none. */
    public static String directory(final String path) {
        final String normalised = path(path);
        final int slash = normalised.lastIndexOf('/');
        return slash < 0 ? "" : normalised.substring(0, slash);
    }

    /** File stem of a pack path, without directory and {@code .blueprint}; tier included. */
    public static String stem(final String path) {
        final String normalised = path(path);
        final int slash = normalised.lastIndexOf('/');
        String leaf = slash < 0 ? normalised : normalised.substring(slash + 1);
        if (leaf.endsWith(".blueprint")) leaf = leaf.substring(0, leaf.length() - ".blueprint".length());
        return leaf;
    }

    /** Whether two pack paths name the same road, ignoring which level each one is. */
    public static boolean sameRoad(final String pathA, final String pathB) {
        return RoadNames.stripTier(stem(pathA)).equals(RoadNames.stripTier(stem(pathB)));
    }

    /** Level encoded in the selected path, or {@code -1} when it is not one of {@code base}'s tiers. */
    public static int tierOf(final String path, final String base) {
        return RoadNames.tierOf(stem(path), base);
    }

    /**
     * Checks one confirmed selection against the session that opened the tool.
     *
     * <p>The tool lists every level of the road, so the chosen file is not one fixed
     * path: the directory must be this session's, the stem must be
     * {@code <base><level>} for the session's base, and the level must be one that
     * was generated. Anything else — another road's file, another directory, a level
     * outside the list — is refused before any blueprint is read.</p>
     */
    public static Mismatch validateTier(String expectedPack,String expectedDir,String expectedBase,int maxTier,
            BlockPos expectedAnchor,int expectedRotation,
            String pack,String selectedPath,BlockPos anchor,int rotation) {
        if(!expectedPack.equals(pack)) return Mismatch.PACK;
        if(!directory(selectedPath).equals(path(expectedDir))) return Mismatch.PATH;
        final String stem=stem(selectedPath);
        if(!stem.startsWith(expectedBase)) return Mismatch.PATH;
        final int tier=RoadNames.tierOf(stem,expectedBase);
        if(tier<1 || tier>maxTier) return Mismatch.TIER;
        if(!expectedAnchor.equals(anchor)) return Mismatch.ANCHOR;
        if(expectedRotation!=rotation) return Mismatch.ROTATION;
        return Mismatch.NONE;
    }
    private RoadRepairSelection() { }
}
