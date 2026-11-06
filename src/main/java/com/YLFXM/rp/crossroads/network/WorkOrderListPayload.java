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
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record WorkOrderListPayload(int colonyId, String colonyName, List<Entry> entries,
                                   boolean bound, boolean canManage) implements CustomPacketPayload {
    /**
     * One row of the work-order list.
     *
     * <p>Everything except the coordinates and the player-authored colony/order
     * name travels as a language-independent descriptor: {@code type} and
     * {@code stage} are enum names, {@code claimed} is a flag, and
     * {@code nameJson} is the serialized component the server built. Resolving
     * any of these on the server would freeze the server's language into the
     * client's screen — the list used to read English on a Chinese client.</p>
     */
    public record Entry(int id, String type, String nameJson, String location,
                        boolean claimed, String stage,
                        int priority, int currentLevel, int targetLevel) {
    }

    public static final Type<WorkOrderListPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Crossroads.MODID, "work_order_list"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WorkOrderListPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBoolean(payload.bound());
                buf.writeBoolean(payload.canManage());
                buf.writeVarInt(payload.colonyId());
                buf.writeUtf(payload.colonyName());
                buf.writeVarInt(payload.entries().size());
                for (final Entry entry : payload.entries()) {
                    buf.writeVarInt(entry.id());
                    buf.writeUtf(entry.type());
                    buf.writeUtf(entry.nameJson());
                    buf.writeUtf(entry.location());
                    buf.writeBoolean(entry.claimed());
                    buf.writeUtf(entry.stage() == null ? "" : entry.stage());
                    buf.writeVarInt(entry.priority());
                    buf.writeVarInt(entry.currentLevel());
                    buf.writeVarInt(entry.targetLevel());
                }
            },
            buf -> {
                final boolean bound = buf.readBoolean();
                final boolean canManage = buf.readBoolean();
                final int colonyId = buf.readVarInt();
                final String colonyName = buf.readUtf();
                final int size = buf.readVarInt();
                final List<Entry> entries = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    final int id = buf.readVarInt();
                    final String type = buf.readUtf();
                    final String nameJson = buf.readUtf();
                    final String location = buf.readUtf();
                    final boolean claimed = buf.readBoolean();
                    final String stage = buf.readUtf();
                    entries.add(new Entry(id, type, nameJson, location, claimed,
                            stage.isEmpty() ? null : stage,
                            buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
                }
                return new WorkOrderListPayload(colonyId, colonyName, entries, bound, canManage);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
