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

import com.YLFXM.mcreconstruction.MCReconstruction;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Flips the controller panel's "edit blueprint" switch for one plan.
 *
 * <p>While it is on, a BUILT road unlocks cell editing and re-selecting A/B;
 * turning it off restores the locked behaviour. The value is stored per plan.</p>
 */
public record ToggleBlueprintEditPayload(BlockPos controllerPos, boolean allow)
        implements CustomPacketPayload {
    public static final Type<ToggleBlueprintEditPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MCReconstruction.MODID, "toggle_blueprint_edit"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ToggleBlueprintEditPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeBlockPos(payload.controllerPos());
                        buf.writeBoolean(payload.allow());
                    },
                    buf -> new ToggleBlueprintEditPayload(buf.readBlockPos(), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
