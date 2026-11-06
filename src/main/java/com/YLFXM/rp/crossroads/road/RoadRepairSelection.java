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
    public enum Mismatch { NONE, PACK, PATH, ANCHOR, ROTATION }
    public static String path(String value) { return value==null ? "" : value.replace('\\','/'); }

    public static Mismatch validate(String expectedPack,String expectedPath,BlockPos expectedAnchor,int expectedRotation,
            String pack,String selectedPath,BlockPos anchor,int rotation) {
        if(!expectedPack.equals(pack)) return Mismatch.PACK;
        if(!path(expectedPath).equals(path(selectedPath))) return Mismatch.PATH;
        if(!expectedAnchor.equals(anchor)) return Mismatch.ANCHOR;
        if(expectedRotation!=rotation) return Mismatch.ROTATION;
        return Mismatch.NONE;
    }
    private RoadRepairSelection() { }
}
