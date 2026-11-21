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
import com.YLFXM.rp.crossroads.network.ClientRoadCache;
import com.YLFXM.rp.crossroads.network.RequestUnitStylesPayload;
import com.YLFXM.rp.crossroads.network.SetRoadStylePayload;
import com.YLFXM.rp.crossroads.road.RoadPlan;
import com.ldtteam.blockui.controls.Button;
import com.minecolonies.core.client.gui.AbstractWindowSkeleton;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Style and series picker for one road: two columns, one confirm.
 *
 * <p>The lists are whatever the server scanned out of this save's structure
 * pack ({@code units/<style>/<series>/}), so a style folder someone drops in is
 * selectable without a code change and a style the pack does not hold is not
 * offered. The tier is not part of this window: a switch starts at level 1 and
 * the road is upgraded from the panel afterwards.</p>
 *
 * <p>Functional form for 0.3.1 — paper background, plain buttons. The card
 * layout with covers and a search box is the visual task's job and replaces
 * this window's XML without touching this logic.</p>
 */
public final class WindowRoadStyleList extends AbstractWindowSkeleton {
    /** Slots reserved in the XML per column; the shipped set is one style with two series. */
    private static final int SLOTS = 5;

    /** Last list the server sent. Static because the reply may arrive before the window opens. */
    private static volatile List<String> cachedStyles = List.of();
    private static volatile Map<String, List<String>> cachedSeries = Map.of();

    private final BlockPos controllerPos;
    private final Button[] styleButtons = new Button[SLOTS];
    private final Button[] seriesButtons = new Button[SLOTS];
    private String chosenStyle;
    private String chosenSeries;
    /** Columns the current labels were built from, so a reply only rebuilds on a real change. */
    private String rendered = "";

    /** Called from the payload handler; the window picks it up on its next update. */
    public static void accept(final List<String> styles, final Map<String, List<String>> series) {
        cachedStyles = List.copyOf(styles);
        cachedSeries = Map.copyOf(series);
    }

    public static void open(final AbstractWindowSkeleton parent, final BlockPos controllerPos) {
        new WindowRoadStyleList(parent, controllerPos).open();
    }

    private WindowRoadStyleList(final AbstractWindowSkeleton parent, final BlockPos controllerPos) {
        super(parent, ResourceLocation.fromNamespaceAndPath(Crossroads.MODID, "gui/windowroadstylelist.xml"));
        this.controllerPos = controllerPos;
        for (int i = 0; i < SLOTS; i++) {
            final int slot = i;
            this.styleButtons[i] = this.findPaneOfTypeByID("style" + i, Button.class);
            this.styleButtons[i].setColors(0x000000, 0x9A9A9A, 0x000000);
            // Resolved at click time: the list arrives after the window is built.
            this.styleButtons[i].setHandler(button -> {
                final List<String> styles = cachedStyles;
                if (slot < styles.size()) {
                    this.chosenStyle = styles.get(slot);
                    final List<String> series = seriesOf(this.chosenStyle);
                    this.chosenSeries = series.isEmpty() ? null : series.get(0);
                    this.refresh();
                }
            });
            this.seriesButtons[i] = this.findPaneOfTypeByID("series" + i, Button.class);
            this.seriesButtons[i].setColors(0x000000, 0x9A9A9A, 0x000000);
            this.seriesButtons[i].setHandler(button -> {
                final List<String> series = seriesOf(this.chosenStyle);
                if (slot < series.size()) {
                    this.chosenSeries = series.get(slot);
                    this.refresh();
                }
            });
        }
        this.registerButton("confirm", button -> this.submit());
        this.registerButton("cancel", button -> this.close());
        if (cachedStyles.isEmpty()) {
            PacketDistributor.sendToServer(new RequestUnitStylesPayload());
        }
        this.seedFromPlan();
        this.refresh();
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        this.refresh();
    }

    /** Opens on the road's current style and series, so confirming without a change is a visible no-op. */
    private void seedFromPlan() {
        final RoadPlan plan = this.plan();
        final List<String> styles = cachedStyles;
        if (plan != null && styles.contains(plan.unitStyle())) {
            this.chosenStyle = plan.unitStyle();
        } else if (!styles.isEmpty()) {
            this.chosenStyle = styles.get(0);
        }
        final List<String> series = seriesOf(this.chosenStyle);
        if (plan != null && series.contains(plan.series())) {
            this.chosenSeries = plan.series();
        } else if (!series.isEmpty()) {
            this.chosenSeries = series.get(0);
        }
    }

    private void refresh() {
        final List<String> styles = cachedStyles;
        final String signature = styles.size() + "|" + styles + "|" + cachedSeries;
        if (!signature.equals(this.rendered)) {
            this.rendered = signature;
            // A reply may land after the window opened, and it may not contain
            // what was preselected (another player deleted the folder, or the
            // pack was rebuilt).
            if (this.chosenStyle != null && !styles.contains(this.chosenStyle)) {
                this.chosenStyle = styles.isEmpty() ? null : styles.get(0);
            }
            if (this.chosenStyle == null && !styles.isEmpty()) {
                this.chosenStyle = styles.get(0);
            }
            if (this.chosenSeries == null || !seriesOf(this.chosenStyle).contains(this.chosenSeries)) {
                final List<String> available = seriesOf(this.chosenStyle);
                this.chosenSeries = available.isEmpty() ? null : available.get(0);
            }
        }
        for (int i = 0; i < SLOTS; i++) {
            if (i < styles.size()) {
                final String style = styles.get(i);
                this.styleButtons[i].show();
                this.styleButtons[i].setText(Component.translatableWithFallback(
                        "rp_crossroads.style." + style, style)
                        .append(style.equals(this.chosenStyle) ? "  ✔" : ""));
            } else {
                this.styleButtons[i].hide();
            }
            final List<String> series = seriesOf(this.chosenStyle);
            if (i < series.size()) {
                final String entry = series.get(i);
                this.seriesButtons[i].show();
                this.seriesButtons[i].setText(Component.translatableWithFallback(
                        "rp_crossroads.series." + entry, entry)
                        .append(entry.equals(this.chosenSeries) ? "  ✔" : ""));
            } else {
                this.seriesButtons[i].hide();
            }
        }
        this.findPaneOfTypeByID("confirm", Button.class)
                .setEnabled(this.chosenStyle != null && this.chosenSeries != null);
        // No list yet (or the pack holds none): say so instead of showing two
        // empty columns with a dead confirm button.
        this.findPaneOfTypeByID("status", com.ldtteam.blockui.controls.Text.class)
                .setText(styles.isEmpty()
                        ? Component.translatable("rp_crossroads.gui.style_none")
                        : Component.empty());
    }

    /** Always level 1: a switch re-lays the road from its base unit and the panel upgrades from there. */
    private void submit() {
        if (this.chosenStyle == null || this.chosenSeries == null) {
            return;
        }
        PacketDistributor.sendToServer(
                new SetRoadStylePayload(this.controllerPos, this.chosenStyle, this.chosenSeries, 1));
        this.close();
    }

    private static List<String> seriesOf(final String style) {
        return style == null ? List.of() : cachedSeries.getOrDefault(style, List.of());
    }

    private RoadPlan plan() {
        for (final RoadPlan plan : ClientRoadCache.roads()) {
            if (plan.controllerPos().equals(this.controllerPos)) {
                return plan;
            }
        }
        return null;
    }
}
