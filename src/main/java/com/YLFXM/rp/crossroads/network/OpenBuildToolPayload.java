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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Opens the native build tool on a generated road blueprint.
 *
 * <p>Structurize syncs structure packs from the server only when a client joins,
 * so a blueprint generated later in the session does not exist on the client and
 * the build tool cannot load it. The blueprint NBT therefore travels with this
 * payload and the client writes it into its local copy of the pack first. The
 * pack version is sent along so the client's pack.json matches the server's and
 * the pack is not discarded by the next sync.</p>
 *
 * <p>{@code anchorPos} is the blueprint anchor on the server. The native window
 * needs it explicitly: given {@code null} it falls back to "ten blocks in front
 * of the player", which makes the preview box drift in whatever direction the
 * player happens to face instead of sitting on the road controller.</p>
 */
public record OpenBuildToolPayload(UUID planId, String blueprintPath,
                                   CompoundTag blueprint, java.util.Map<String, CompoundTag> tiers,
                                   double packVersion,
                                   BlockPos anchorPos, UUID repairToken, java.util.List<String> jointImpact)
        implements CustomPacketPayload {
    public static final Type<OpenBuildToolPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Crossroads.MODID, "open_build_tool"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenBuildToolPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeUUID(payload.planId());
                buf.writeUtf(payload.blueprintPath());
                buf.writeDouble(payload.packVersion());
                buf.writeBlockPos(payload.anchorPos());
                buf.writeNbt(payload.blueprint());
                buf.writeVarInt(payload.tiers().size());
                for (final var tier : payload.tiers().entrySet()) {
                    buf.writeUtf(tier.getKey());
                    buf.writeNbt(tier.getValue());
                }
                buf.writeUUID(payload.repairToken());
                buf.writeCollection(payload.jointImpact(),(b,line) -> b.writeUtf(line,512));
            },
            buf -> {
                final UUID planId = buf.readUUID();
                final String path = buf.readUtf();
                final double version = buf.readDouble();
                final BlockPos anchor = buf.readBlockPos();
                final CompoundTag blueprint = buf.readNbt();
                final int tierCount = buf.readVarInt();
                if (tierCount < 0 || tierCount > 9) throw new IllegalArgumentException("Invalid road tier count");
                final java.util.Map<String, CompoundTag> tiers = new java.util.LinkedHashMap<>();
                for (int i = 0; i < tierCount; i++) {
                    final String tierPath = buf.readUtf();
                    final CompoundTag tierBlueprint = buf.readNbt();
                    if (tierBlueprint != null) tiers.put(tierPath, tierBlueprint);
                }
                return new OpenBuildToolPayload(planId, path, blueprint, java.util.Map.copyOf(tiers), version, anchor,
                        buf.readUUID(), buf.readList(b -> b.readUtf(512)));
            });

    public OpenBuildToolPayload(UUID planId,String path,CompoundTag blueprint,double version,BlockPos anchor,UUID token) {
        this(planId,path,blueprint,java.util.Map.of(),version,anchor,token,java.util.List.of());
    }
    public OpenBuildToolPayload(UUID planId,String path,CompoundTag blueprint,double version,BlockPos anchor,UUID token,
                               java.util.List<String> jointImpact) {
        this(planId,path,blueprint,java.util.Map.of(),version,anchor,token,jointImpact);
    }
    public OpenBuildToolPayload(UUID planId, String path, CompoundTag blueprint, double version, BlockPos anchor) {
        this(planId,path,blueprint,version,anchor,com.YLFXM.rp.crossroads.road.RoadNativeBuildTool.NO_SESSION);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
