/*
 * Copyright (C) 2026 ZhuanZ (YLFXM)
 *
 * MineColonies: Reconstruction Plan - a feature expansion addon for MineColonies.
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
package com.YLFXM.mcreconstruction.network;

import com.YLFXM.mcreconstruction.MCReconstruction;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server answer to {@link RequestRoadPreviewPayload}: the blueprint the current
 * selection would build, plus the world position of its origin.
 *
 * <p>The blueprint is the very one the build tool will get, so the preview
 * cannot drift from what actually gets placed. The origin is computed here for
 * the same reason — the client must not re-derive it.</p>
 */
public record RoadPreviewPayload(CompoundTag blueprint,
                                 BlockPos origin,
                                 long sequence, java.util.UUID roadId, String dimension) implements CustomPacketPayload {
    public static final Type<RoadPreviewPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MCReconstruction.MODID, "road_preview"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RoadPreviewPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeNbt(payload.blueprint());
                buf.writeBlockPos(payload.origin());
                buf.writeLong(payload.sequence());
                buf.writeUUID(payload.roadId());
                buf.writeUtf(payload.dimension());
            },
            buf -> new RoadPreviewPayload(buf.readNbt(), buf.readBlockPos(), buf.readLong(), buf.readUUID(), buf.readUtf()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
