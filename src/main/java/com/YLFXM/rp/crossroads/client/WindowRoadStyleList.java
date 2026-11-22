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
package com.YLFXM.rp.crossroads.client;

import com.YLFXM.rp.crossroads.Crossroads;
import com.YLFXM.rp.crossroads.CustomRoadPack;
import com.YLFXM.rp.crossroads.network.RequestUnitStylesPayload;
import com.YLFXM.rp.crossroads.network.SetRoadStylePayload;
import com.YLFXM.rp.crossroads.network.UnitStyleListPayload;
import com.YLFXM.rp.crossroads.road.RoadUnitLibrary;
import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.Image;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.controls.TextField;
import com.ldtteam.blockui.util.resloc.OutOfJarResourceLocation;
import com.ldtteam.blockui.views.Box;
import com.ldtteam.blockui.views.ScrollingList;
import com.ldtteam.structurize.storage.StructurePackMeta;
import com.ldtteam.structurize.storage.StructurePacks;
import com.minecolonies.core.client.gui.AbstractWindowSkeleton;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Style and series picker for one road, in the vanilla pack picker's visual language.
 *
 * <p>Two levels in one window: a card grid of the unit styles this save's pack
 * holds (cover, name, description, authors), then a compact single-column list
 * of that style's series with their tier count. Picking a series applies it at
 * tier 1 — the panel's Upgrade walks it up from there.</p>
 *
 * <p>Deliberately not a paper window: the vanilla "switch pack" screen this
 * mirrors is a lightbox over the world with white text, and a paper background
 * with black text would read as a different kind of control. It draws no frame
 * of its own, so nothing here uses {@link UiStyle#PAPER}.</p>
 *
 * <p>All data comes from the server's scan ({@link UnitStyleListPayload}); this
 * class never reads the pack itself except for the cover image, which lives on
 * the client's copy of the pack and has to be loaded as a file.</p>
 */
public final class WindowRoadStyleList extends AbstractWindowSkeleton {
    /** Icon used when a style declares no cover, or its file has not reached this client yet. */
    private static final String FALLBACK_ICON = "structurize:textures/item/sceptersteel.png";

    /** Last answer the server gave. Static: the reply may arrive before the window opens. */
    private static volatile Map<String, UnitStyleListPayload.StyleEntry> cachedStyles = Map.of();
    private static volatile Map<String, List<UnitStyleListPayload.SeriesEntry>> cachedSeries = Map.of();

    private enum Level { STYLE, SERIES }

    private final BlockPos controllerPos;
    private final ScrollingList styleList;
    private final ScrollingList seriesList;
    private final TextField filter;
    private final Text filterHint;
    private final Text header;
    private final Text status;
    private final Button cancel;

    private Level level = Level.STYLE;
    private String chosenStyle;
    /** Styles after the search box, in display order. */
    private List<String> visible = List.of();
    private String lastFilter = "\u0000";
    /** The content the panes were last built from, so a reply rebuilds them once. */
    private String rendered = "";

    /** Called from the payload handler; the window picks it up on its next update. */
    public static void accept(final Map<String, UnitStyleListPayload.StyleEntry> styles,
                              final Map<String, List<UnitStyleListPayload.SeriesEntry>> series) {
        cachedStyles = Map.copyOf(styles);
        cachedSeries = Map.copyOf(series);
    }

    /**
     * Tiers a series ships, from the server's list; the jar manifest is the fallback.
     *
     * <p>The panel and the creative tier prompt label themselves with this. They
     * cannot read the pack manifest themselves (it is server-side), and a style
     * that exists only in the pack would otherwise report 0 tiers and disable
     * the upgrade button for a road that can be upgraded.</p>
     */
    public static int maxLevelOf(final String style, final String series) {
        for (final UnitStyleListPayload.SeriesEntry entry : cachedSeries.getOrDefault(style, List.of())) {
            if (entry.id().equals(RoadUnitLibrary.normalizeSeries(series))) {
                return entry.maxLevel();
            }
        }
        return RoadUnitLibrary.maxLevel(style, series);
    }

    public static void open(final AbstractWindowSkeleton parent, final BlockPos controllerPos) {
        new WindowRoadStyleList(parent, controllerPos).open();
    }

    private WindowRoadStyleList(final AbstractWindowSkeleton parent, final BlockPos controllerPos) {
        super(parent, ResourceLocation.fromNamespaceAndPath(Crossroads.MODID, "gui/windowroadstylelist.xml"));
        this.controllerPos = controllerPos;
        this.styleList = this.findPaneOfTypeByID("styles", ScrollingList.class);
        this.seriesList = this.findPaneOfTypeByID("series", ScrollingList.class);
        this.filter = this.findPaneOfTypeByID("filter", TextField.class);
        this.filterHint = this.findPaneOfTypeByID("filterHint", Text.class);
        this.header = this.findPaneOfTypeByID("header", Text.class);
        this.status = this.findPaneOfTypeByID("status", Text.class);
        this.cancel = this.findPaneOfTypeByID("cancel", Button.class);
        UiStyle.disable(this.cancel);

        this.filter.setHandler(field -> this.rebuildVisible(true));
        this.registerButton("cancel", button -> {
            // One level back, not out: the series list is a step inside this window.
            if (this.level == Level.SERIES) {
                this.level = Level.STYLE;
                this.chosenStyle = null;
                this.refresh();
                return;
            }
            this.close();
        });
        this.styleList.setDataProvider(new ScrollingList.DataProvider() {
            @Override
            public int getElementCount() {
                return (visible.size() + 1) / 2;
            }

            @Override
            public void updateElement(final int index, final Pane row) {
                final Box left = row.findPaneOfTypeByID("box0", Box.class);
                final Box right = row.findPaneOfTypeByID("box1", Box.class);
                left.show();
                renderCard(left, index * 2);
                if (index * 2 + 1 < visible.size()) {
                    right.show();
                    renderCard(right, index * 2 + 1);
                } else {
                    right.hide();
                }
            }
        });
        this.seriesList.setDataProvider(new ScrollingList.DataProvider() {
            @Override
            public int getElementCount() {
                return seriesOf(chosenStyle).size();
            }

            @Override
            public void updateElement(final int index, final Pane row) {
                final List<UnitStyleListPayload.SeriesEntry> rows = seriesOf(chosenStyle);
                if (index < 0 || index >= rows.size()) {
                    return;
                }
                final UnitStyleListPayload.SeriesEntry entry = rows.get(index);
                final Button button = row.findPaneOfTypeByID("entry", Button.class);
                UiStyle.disable(button);
                button.setText(seriesName(entry.id())
                        .append(Component.translatable("rp_crossroads.gui.series_levels", entry.maxLevel())));
                button.setHandler(clicked -> submit(entry.id()));
            }
        });
        if (cachedStyles.isEmpty()) {
            PacketDistributor.sendToServer(new RequestUnitStylesPayload());
        }
        this.rebuildVisible(false);
        this.refresh();
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        // Polled rather than pushed: TextField only exposes an input handler, and
        // rebuilding a two-column list on a keystroke is the cheap direction.
        if (!this.lastFilter.equals(this.filter.getText())) {
            this.rebuildVisible(true);
        }
        this.refresh();
    }

    /** Applies the search box to the style list. */
    private void rebuildVisible(final boolean refreshPanes) {
        this.lastFilter = this.filter.getText();
        final String needle = this.lastFilter.trim().toLowerCase(Locale.ROOT);
        final List<String> result = new ArrayList<>();
        for (final String style : cachedStyles.keySet()) {
            if (needle.isEmpty() || displayName(style).getString().toLowerCase(Locale.ROOT).contains(needle)) {
                result.add(style);
            }
        }
        this.visible = List.copyOf(result);
        if (refreshPanes) {
            this.styleList.refreshElementPanes();
        }
    }

    private void refresh() {
        final String signature = this.level + "|" + this.chosenStyle + "|" + cachedStyles + "|" + cachedSeries;
        if (!signature.equals(this.rendered)) {
            this.rendered = signature;
            this.styleList.refreshElementPanes();
            this.seriesList.refreshElementPanes();
        }
        final boolean styles = this.level == Level.STYLE;
        // Both lists share the same rect: only one of them may be visible, or the
        // series rows would be drawn on top of the cards.
        this.styleList.setVisible(styles);
        this.seriesList.setVisible(!styles);
        this.filter.setVisible(styles);
        this.filterHint.setVisible(styles && this.filter.getText().isEmpty());
        this.header.setText(styles
                ? Component.translatable("rp_crossroads.gui.style_pick")
                : Component.translatable("rp_crossroads.gui.series_pick", displayName(this.chosenStyle)));

        if (styles) {
            this.status.setText(cachedStyles.isEmpty()
                    ? Component.translatable("rp_crossroads.gui.style_none")
                    : this.visible.isEmpty() ? Component.translatable("rp_crossroads.gui.style_no_match")
                    : Component.empty());
        } else {
            this.status.setText(seriesOf(this.chosenStyle).isEmpty()
                    ? Component.translatable("rp_crossroads.gui.series_none") : Component.empty());
        }
        this.cancel.setText(Component.translatable(styles
                ? "rp_crossroads.gui.cancel" : "rp_crossroads.gui.styles_back"));
    }

    /** One card: cover, name, description, authors, and the button that opens its series list. */
    private void renderCard(final Box card, final int index) {
        if (index >= this.visible.size()) {
            return;
        }
        final String id = this.visible.get(index);
        final UnitStyleListPayload.StyleEntry entry = cachedStyles.get(id);
        card.findPaneOfTypeByID("name", Text.class).setText(displayName(id));
        card.findPaneOfTypeByID("desc", Text.class)
                .setText(entry == null ? Component.empty() : displayDesc(id, entry));
        card.findPaneOfTypeByID("authors", Text.class)
                .setText(entry == null || entry.authors().isEmpty() ? Component.empty()
                        : Component.translatable("rp_crossroads.gui.style_authors",
                                String.join(", ", entry.authors())));
        card.findPaneOfTypeByID("icon", Image.class)
                .setImage(coverOf(id, entry == null ? "" : entry.cover()), false);
        final Button select = card.findPaneOfTypeByID("select", Button.class);
        UiStyle.disable(select);
        select.setText(Component.translatable("rp_crossroads.gui.style_open"));
        select.setHandler(clicked -> {
            this.chosenStyle = id;
            this.level = Level.SERIES;
            this.refresh();
        });
    }

    /** Applying a series: always tier 1, the panel upgrades from there. */
    private void submit(final String series) {
        if (this.chosenStyle == null) {
            return;
        }
        PacketDistributor.sendToServer(
                new SetRoadStylePayload(this.controllerPos, this.chosenStyle, series, 1));
        this.close();
    }

    /** Style name: the pack's own description first, then a language key, then the folder name. */
    private static Component displayName(final String style) {
        final UnitStyleListPayload.StyleEntry entry = cachedStyles.get(style);
        if (entry != null && !entry.name().isEmpty()) {
            return Component.literal(entry.name());
        }
        return Component.translatableWithFallback("rp_crossroads.style." + style, style);
    }

    /** Description: the pack's own, else a language key, else nothing. */
    private static Component displayDesc(final String style, final UnitStyleListPayload.StyleEntry entry) {
        if (!entry.desc().isEmpty()) {
            return Component.literal(entry.desc());
        }
        return Component.translatableWithFallback("rp_crossroads.style.desc." + style, "");
    }

    /** Series name: language key first (the shipped set is translated), then the folder name. */
    private static net.minecraft.network.chat.MutableComponent seriesName(final String series) {
        return Component.translatableWithFallback("rp_crossroads.series." + series, series);
    }

    /**
     * Cover of one style, as a file on this client's copy of the pack.
     *
     * <p>BlockUI reads out-of-jar images straight from disk, which is the same
     * mechanism the vanilla pack picker uses for its icons. A style the server
     * added during this session is not on this client yet, and a style without a
     * cover file gets the fallback icon — neither may fail the window.</p>
     */
    private static ResourceLocation coverOf(final String style, final String cover) {
        if (cover.isEmpty()) {
            return ResourceLocation.parse(FALLBACK_ICON);
        }
        try {
            final StructurePackMeta meta = StructurePacks.getStructurePack(CustomRoadPack.PACK_NAME);
            if (meta != null) {
                final Path file = meta.getPath().resolve("units").resolve(style).resolve(cover);
                if (Files.isRegularFile(file)) {
                    return OutOfJarResourceLocation.of(Crossroads.MODID, file);
                }
            }
        } catch (final RuntimeException ex) {
            Crossroads.LOGGER.warn("Could not resolve cover {} of style {}", cover, style, ex);
        }
        return ResourceLocation.parse(FALLBACK_ICON);
    }

    private static List<UnitStyleListPayload.SeriesEntry> seriesOf(final String style) {
        return style == null ? List.of() : cachedSeries.getOrDefault(style, List.of());
    }
}
