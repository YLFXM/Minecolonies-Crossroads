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
 * styles the save's structure pack holds, and which series each style ships.
 *
 * <p>The client may not scan the pack itself. The pack lives on the server, and
 * in multiplayer the two sides do not necessarily hold the same copy; a list
 * built locally would offer styles that cannot be built there. This payload is
 * a plain projection of the server's scan, and the style switch re-validates it
 * before writing anything.</p>
 *
 * <p>Each style carries the display data its own folder declares in
 * {@code style.json}; empty fields mean "not declared", and the window falls
 * back to a language key and then to the folder name. Each series carries the
 * highest tier it ships, which is what the picker labels its rows with.</p>
 */
public record UnitStyleListPayload(Map<String, StyleEntry> styles, Map<String, List<SeriesEntry>> series)
        implements CustomPacketPayload {
    /** One style folder: display data plus the cover file name inside it. */
    public record StyleEntry(String id, String name, String desc, List<String> authors, String cover) { }

    /** One series folder and how many tiers it has (0 when its manifest says nothing). */
    public record SeriesEntry(String id, int maxLevel) { }

    /** Style folders one packet may carry. */
    private static final int MAX_STYLES = 64;
    /** Authors one style may declare. */
    private static final int MAX_AUTHORS = 16;
    /** Series folders one style may carry. */
    private static final int MAX_SERIES = 64;

    public static final Type<UnitStyleListPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Crossroads.MODID, "unit_style_list"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UnitStyleListPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeVarInt(payload.styles().size());
                for (final Map.Entry<String, StyleEntry> entry : payload.styles().entrySet()) {
                    final StyleEntry style = entry.getValue();
                    buf.writeUtf(style.id());
                    buf.writeUtf(style.name());
                    buf.writeUtf(style.desc());
                    buf.writeVarInt(style.authors().size());
                    for (final String author : style.authors()) {
                        buf.writeUtf(author);
                    }
                    buf.writeUtf(style.cover());
                    final List<SeriesEntry> entries = payload.series().getOrDefault(entry.getKey(), List.of());
                    buf.writeVarInt(entries.size());
                    for (final SeriesEntry series : entries) {
                        buf.writeUtf(series.id());
                        buf.writeVarInt(series.maxLevel());
                    }
                }
            },
            buf -> {
                final int count = buf.readVarInt();
                if (count < 0 || count > MAX_STYLES) {
                    throw new IllegalArgumentException("Invalid unit style count " + count);
                }
                final Map<String, StyleEntry> styles = new LinkedHashMap<>();
                final Map<String, List<SeriesEntry>> series = new LinkedHashMap<>();
                for (int i = 0; i < count; i++) {
                    final String id = buf.readUtf();
                    final String name = buf.readUtf();
                    final String desc = buf.readUtf();
                    final int authors = buf.readVarInt();
                    if (authors < 0 || authors > MAX_AUTHORS) {
                        throw new IllegalArgumentException("Invalid unit style author count " + authors);
                    }
                    final List<String> names = new ArrayList<>(authors);
                    for (int j = 0; j < authors; j++) {
                        names.add(buf.readUtf());
                    }
                    final String cover = buf.readUtf();
                    styles.put(id, new StyleEntry(id, name, desc, List.copyOf(names), cover));
                    final int entries = buf.readVarInt();
                    if (entries < 0 || entries > MAX_SERIES) {
                        throw new IllegalArgumentException("Invalid unit series count " + entries);
                    }
                    final List<SeriesEntry> rows = new ArrayList<>(entries);
                    for (int j = 0; j < entries; j++) {
                        final String seriesId = buf.readUtf();
                        rows.add(new SeriesEntry(seriesId, buf.readVarInt()));
                    }
                    series.put(id, List.copyOf(rows));
                }
                return new UnitStyleListPayload(Map.copyOf(styles), Map.copyOf(series));
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
