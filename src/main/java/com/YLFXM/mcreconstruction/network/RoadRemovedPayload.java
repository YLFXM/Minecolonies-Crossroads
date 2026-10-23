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

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RoadRemovedPayload(UUID roadId, BlockPos controller, String dimension) implements CustomPacketPayload {
    public static final Type<RoadRemovedPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("mcreconstruction", "road_removed"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RoadRemovedPayload> CODEC = StreamCodec.of(
            (buf,p) -> { buf.writeUUID(p.roadId()); buf.writeBlockPos(p.controller()); buf.writeUtf(p.dimension()); },
            buf -> new RoadRemovedPayload(buf.readUUID(),buf.readBlockPos(),buf.readUtf()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
