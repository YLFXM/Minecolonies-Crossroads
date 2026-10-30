/* SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 ZhuanZ (YLFXM) */
package com.YLFXM.mcreconstruction.road;

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
