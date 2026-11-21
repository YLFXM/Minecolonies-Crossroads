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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-authoritative answer to {@link RequestUnitStylesPayload}: which unit
 * styles the save's structure pack actually holds, and which series each style
 * ships.
 *
 * <p>The client may not scan the pack itself. The pack lives on the server, and
 * in multiplayer the two sides do not necessarily hold the same copy; a list
 * built locally would offer styles that cannot be built there. This payload is
 * a plain projection of the server's scan, and the style switch re-validates it
 * before writing anything.</p>
 *
 * <p>Both levels are tiny (one style, two series today), so there is no paging.
 * The counts are bounded on decode so a broken sender cannot allocate.</p>
 */
public record UnitStyleListPayload(List<String> styles, Map<String, List<String>> series)
        implements CustomPacketPayload {
    /** Style folders one packet may carry. */
    private static final int MAX_STYLES = 64;
    /** Series folders one style may carry. */
    private static final int MAX_SERIES = 64;

    public static final Type<UnitStyleListPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Crossroads.MODID, "unit_style_list"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UnitStyleListPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeVarInt(payload.styles().size());
                for (final String style : payload.styles()) {
                    buf.writeUtf(style);
                    final List<String> entries = payload.series().getOrDefault(style, List.of());
                    buf.writeVarInt(entries.size());
                    for (final String entry : entries) {
                        buf.writeUtf(entry);
                    }
                }
            },
            buf -> {
                final int count = buf.readVarInt();
                if (count < 0 || count > MAX_STYLES) {
                    throw new IllegalArgumentException("Invalid unit style count " + count);
                }
                final List<String> styles = new ArrayList<>(count);
                final Map<String, List<String>> series = new LinkedHashMap<>();
                for (int i = 0; i < count; i++) {
                    final String style = buf.readUtf();
                    final int entries = buf.readVarInt();
                    if (entries < 0 || entries > MAX_SERIES) {
                        throw new IllegalArgumentException("Invalid unit series count " + entries);
                    }
                    final List<String> names = new ArrayList<>(entries);
                    for (int j = 0; j < entries; j++) {
                        names.add(buf.readUtf());
                    }
                    styles.add(style);
                    series.put(style, List.copyOf(names));
                }
                return new UnitStyleListPayload(List.copyOf(styles), Map.copyOf(series));
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
