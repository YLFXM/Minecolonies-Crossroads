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

/** A client interaction request, never permission to open a window by itself. */
public record RequestControllerWindowPayload(BlockPos controllerPos) implements CustomPacketPayload {
    public static final Type<RequestControllerWindowPayload> TYPE=new Type<>(
            ResourceLocation.fromNamespaceAndPath(Crossroads.MODID,"request_controller_window"));
    public static final StreamCodec<RegistryFriendlyByteBuf,RequestControllerWindowPayload> CODEC=StreamCodec.of(
            (buf,payload) -> buf.writeBlockPos(payload.controllerPos()),buf -> new RequestControllerWindowPayload(buf.readBlockPos()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
