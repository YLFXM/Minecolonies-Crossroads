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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ConfirmRoadRepairPayload(UUID planId, UUID token, int mode, String handler,
                                      String pack, String path, BlockPos pos, int rotation,boolean jointApproved) implements CustomPacketPayload {
    public static final Type<ConfirmRoadRepairPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("mcreconstruction","confirm_road_repair"));
    public static final StreamCodec<RegistryFriendlyByteBuf,ConfirmRoadRepairPayload> CODEC=StreamCodec.of(
        (b,p)->{b.writeUUID(p.planId());b.writeUUID(p.token());b.writeVarInt(p.mode());b.writeUtf(p.handler());
            b.writeUtf(p.pack());b.writeUtf(p.path());b.writeBlockPos(p.pos());b.writeVarInt(p.rotation());b.writeBoolean(p.jointApproved());},
        b->new ConfirmRoadRepairPayload(b.readUUID(),b.readUUID(),b.readVarInt(),b.readUtf(),b.readUtf(),b.readUtf(),b.readBlockPos(),b.readVarInt(),b.readBoolean()));
    public ConfirmRoadRepairPayload(UUID plan,UUID token,int mode,String handler,String pack,String path,BlockPos pos,int rotation) {
        this(plan,token,mode,handler,pack,path,pos,rotation,false);
    }
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
