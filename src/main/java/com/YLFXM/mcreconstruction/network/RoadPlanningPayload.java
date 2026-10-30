/* SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 ZhuanZ (YLFXM) */
package com.YLFXM.mcreconstruction.network;

import com.YLFXM.mcreconstruction.MCReconstruction;
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
    public static final Type<RoadPlanningPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(MCReconstruction.MODID,"road_planning"));
    public static final StreamCodec<RegistryFriendlyByteBuf,RoadPlanningPayload> CODEC=StreamCodec.of((buf,p) -> {
        buf.writeUUID(p.road());buf.writeBlockPos(p.controller());buf.writeBlockPos(p.a());buf.writeBlockPos(p.b());buf.writeBoolean(p.mirror());
        buf.writeVarInt(p.action());buf.writeUUID(p.other());buf.writeVarInt(p.mode());buf.writeVarInt(p.heightDelta());
    },buf -> new RoadPlanningPayload(buf.readUUID(),buf.readBlockPos(),buf.readBlockPos(),buf.readBlockPos(),buf.readBoolean(),
            buf.readVarInt(),buf.readUUID(),buf.readVarInt(),buf.readVarInt()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
