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

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import net.minecraft.nbt.*;

/** Write, flush, then replace. A failed write never truncates the previous snapshot. */
public final class RoadAtomicNbt {
    public static void write(Path destination,CompoundTag data) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary=Files.createTempFile(destination.getParent(),".road-",".tmp");
        try {
            NbtIo.writeCompressed(data,temporary);
            try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)) {channel.force(true);}
            try {Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException ex) {Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING);}
        } finally {Files.deleteIfExists(temporary);}
    }
    public static CompoundTag read(Path path) throws IOException {return NbtIo.readCompressed(path,NbtAccounter.unlimitedHeap());}
    private RoadAtomicNbt() { }
}
