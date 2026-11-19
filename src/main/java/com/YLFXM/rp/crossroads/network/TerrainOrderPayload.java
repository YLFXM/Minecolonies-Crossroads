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
package com.YLFXM.rp.crossroads.network;

import com.YLFXM.rp.crossroads.Crossroads;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Asks the server to turn a selection into a terrain dig or fill work order.
 *
 * <p>The two corners are the cube's opposite corners; the server recomputes the
 * box, so a client cannot hand over a volume it would refuse locally.</p>
 */
public record TerrainOrderPayload(BlockPos a, BlockPos b, boolean fill) implements CustomPacketPayload {
    public static final Type<TerrainOrderPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Crossroads.MODID, "terrain_order"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TerrainOrderPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.a());
                buf.writeBlockPos(payload.b());
                buf.writeBoolean(payload.fill());
            },
            buf -> new TerrainOrderPayload(buf.readBlockPos(), buf.readBlockPos(), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
