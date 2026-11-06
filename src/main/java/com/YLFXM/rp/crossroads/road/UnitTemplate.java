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

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A one-block-long slice extracted from a style-pack blueprint. All positions
 * are local to the slice; the controller block is removed from the template.
 */
public final class UnitTemplate {
    /**
     * One cell of the slice.
     *
     * <p>{@code placeholder} marks a cell the unit must <em>not</em> write: it
     * only says "the ground here belongs to the road, whatever it happens to
     * be". A lamp uses this for the pavement column it hangs over, which may be
     * flat path or a stair, so the overlay never overwrites edited pavement.</p>
     */
    public record Entry(BlockPos pos, BlockState state, CompoundTag tileEntity, boolean placeholder) {
        public Entry(final BlockPos pos, final BlockState state, final CompoundTag tileEntity) {
            this(pos, state, tileEntity, false);
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final int surfaceY;
    private final Direction.Axis axis;
    private boolean frozen;

    public UnitTemplate(final int sizeX, final int sizeY, final int sizeZ,
                        final int surfaceY, final Direction.Axis axis) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.surfaceY = surfaceY;
        this.axis = axis;
    }

    public void add(final BlockPos pos, final BlockState state, final CompoundTag tileEntity) {
        if(frozen) throw new IllegalStateException("Immutable unit template");
        entries.add(new Entry(pos.immutable(), state, tileEntity==null ? null : tileEntity.copy()));
    }

    /**
     * Adds a cell that only claims "road owns this ground": overlays skip it, so
     * whatever is really there (flat pavement, stair, edited block) survives.
     */
    public void addPlaceholder(final BlockPos pos) {
        if(frozen) throw new IllegalStateException("Immutable unit template");
        entries.add(new Entry(pos, null, null, true));
    }

    public List<Entry> entries() {
        return entries.stream().map(e -> new Entry(e.pos(),e.state(),e.tileEntity()==null ? null : e.tileEntity().copy(),e.placeholder())).toList();
    }

    public UnitTemplate freeze() { frozen=true; return this; }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public int surfaceY() {
        return surfaceY;
    }

    public Direction.Axis axis() {
        return axis;
    }
}
