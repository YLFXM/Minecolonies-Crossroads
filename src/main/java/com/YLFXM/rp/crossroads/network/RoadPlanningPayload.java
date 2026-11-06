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
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server-owned planning commands; no row table or permission bit is accepted from the client. */
public record RoadPlanningPayload(UUID road,BlockPos controller,BlockPos a,BlockPos b,boolean mirror,
                                   int action,UUID other,int mode,int heightDelta) implements CustomPacketPayload {
    public RoadPlanningPayload(UUID road,BlockPos controller,BlockPos a,BlockPos b,boolean mirror,int action,UUID other,int mode) {this(road,controller,a,b,mirror,action,other,mode,0);}
    public static final Type<RoadPlanningPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(Crossroads.MODID,"road_planning"));
    public static final StreamCodec<RegistryFriendlyByteBuf,RoadPlanningPayload> CODEC=StreamCodec.of((buf,p) -> {
        buf.writeUUID(p.road());buf.writeBlockPos(p.controller());buf.writeBlockPos(p.a());buf.writeBlockPos(p.b());buf.writeBoolean(p.mirror());
        buf.writeVarInt(p.action());buf.writeUUID(p.other());buf.writeVarInt(p.mode());buf.writeVarInt(p.heightDelta());
    },buf -> new RoadPlanningPayload(buf.readUUID(),buf.readBlockPos(),buf.readBlockPos(),buf.readBlockPos(),buf.readBoolean(),
            buf.readVarInt(),buf.readUUID(),buf.readVarInt(),buf.readVarInt()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
