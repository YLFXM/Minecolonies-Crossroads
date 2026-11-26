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
 * One controller action.
 *
 * <p>{@code confirmed} says the player already answered the demolition method
 * prompt. Without it the developer switch would answer every {@code REMOVE}
 * with the same prompt again, and the choice could never be carried out; every
 * other action ignores the flag.</p>
 */
public record RoadControllerActionPayload(BlockPos controllerPos, Action action, boolean confirmed)
        implements CustomPacketPayload {
    public enum Action {
        UPGRADE,
        REPAIR,
        REMOVE,
        REMOVE_INSTANT,
        /** Drop every hand-edited row and re-sample the terrain. */
        REDRAW
    }

    public RoadControllerActionPayload(final BlockPos controllerPos, final Action action) {
        this(controllerPos, action, false);
    }

    public static final Type<RoadControllerActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Crossroads.MODID, "road_controller_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RoadControllerActionPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.controllerPos());
                buf.writeByte(payload.action().ordinal());
                buf.writeBoolean(payload.confirmed());
            },
            buf -> {
                final BlockPos pos=buf.readBlockPos();
                final int action=buf.readUnsignedByte();
                if(action>=Action.values().length) throw new IllegalArgumentException("Unknown road action");
                return new RoadControllerActionPayload(pos,Action.values()[action],buf.readBoolean());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
