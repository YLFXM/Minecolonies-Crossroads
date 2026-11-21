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
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Tells the client it may ask how to demolish: the developer switch is on, so
 * the builder/native choice has to be offered instead of dispatched directly.
 *
 * <p>Only the server decides this. The client used to guess it from "am I in
 * creative", which made the window appear for a player who had no order to give
 * and never appear for a survival road that needed one.</p>
 *
 * <p>{@code roadIds} empty means the single controller at {@code controllerPos};
 * otherwise it is the selection the surveyor asked to demolish, and the client
 * replies with a bulk request. The list is bounded on both sides.</p>
 */
public record OpenRemovalChoicePayload(BlockPos controllerPos, List<UUID> roadIds)
        implements CustomPacketPayload {
    /** Roads one prompt may cover; same ceiling as {@link RoadBulkActionPayload}. */
    private static final int MAX_ROADS = 256;

    public static final Type<OpenRemovalChoicePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Crossroads.MODID, "open_removal_choice"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenRemovalChoicePayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.controllerPos());
                buf.writeVarInt(payload.roadIds().size());
                for (final UUID id : payload.roadIds()) {
                    buf.writeUUID(id);
                }
            },
            buf -> {
                final BlockPos pos = buf.readBlockPos();
                final int count = buf.readVarInt();
                if (count < 0 || count > MAX_ROADS) {
                    throw new IllegalArgumentException("Invalid removal choice count " + count);
                }
                final List<UUID> ids = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    ids.add(buf.readUUID());
                }
                return new OpenRemovalChoicePayload(pos, List.copyOf(ids));
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
