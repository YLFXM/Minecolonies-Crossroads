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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RoadBulkActionPayload(List<UUID> roadIds, boolean removeBlocks, boolean instant)
        implements CustomPacketPayload {
    public RoadBulkActionPayload(List<UUID> roadIds, boolean removeBlocks) { this(roadIds,removeBlocks,false); }
    public static final Type<RoadBulkActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Crossroads.MODID, "road_bulk_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RoadBulkActionPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeVarInt(payload.roadIds().size());
                for (final UUID id : payload.roadIds()) {
                    buf.writeUUID(id);
                }
                buf.writeBoolean(payload.removeBlocks());
                buf.writeBoolean(payload.instant());
            },
            buf -> {
                final int count = buf.readVarInt();
                if (count<0 || count>256) throw new IllegalArgumentException("Invalid road action count");
                final List<UUID> ids = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    ids.add(buf.readUUID());
                }
                return new RoadBulkActionPayload(ids, buf.readBoolean(),buf.readBoolean());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
