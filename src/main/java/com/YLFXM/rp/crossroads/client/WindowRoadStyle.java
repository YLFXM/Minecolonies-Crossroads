/*
 * Copyright (C) 2026 YLFXM
 *
 * MineColonies: Crossroads - a feature expansion addon for MineColonies.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.YLFXM.rp.crossroads.client;

import com.YLFXM.rp.crossroads.Crossroads;
import com.YLFXM.rp.crossroads.network.ClientRoadCache;
import com.YLFXM.rp.crossroads.network.SetRoadStylePayload;
import com.YLFXM.rp.crossroads.road.RoadPlan;
import com.YLFXM.rp.crossroads.road.RoadUnitLibrary;
import com.ldtteam.blockui.controls.Button;
import com.minecolonies.core.client.gui.AbstractWindowSkeleton;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Unit style, series and tier picker for one road.
 *
 * <p>The lists are what the jar actually ships ({@link RoadUnitLibrary#styles()},
 * {@link RoadUnitLibrary#seriesIds(String)} and {@link RoadUnitLibrary#maxLevel}),
 * not a hard-coded menu: a style that has no unit folder would be a choice that
 * cannot be built, and a series with three tiers must not offer a fourth. Empty
 * slots in the XML are hidden, so adding a series needs no layout change.</p>
 */
public final class WindowRoadStyle extends AbstractWindowSkeleton {
    /** Slots reserved in the XML for the style and series lists. */
    private static final int SLOTS = 3;
    /** Slots reserved for the tier list; the longest series ships four. */
    private static final int LEVEL_SLOTS = 4;

    private final BlockPos controllerPos;
    private final List<String> styles = RoadUnitLibrary.styles();
    private final List<String> series =
            RoadUnitLibrary.seriesIds(com.YLFXM.rp.crossroads.CustomRoadPack.UNIT_STYLE_ORIGINAL);
    private final Button[] styleButtons = new Button[SLOTS];
    private final Button[] seriesButtons = new Button[SLOTS];
    private final Button[] levelButtons = new Button[LEVEL_SLOTS];
    private String chosenStyle;
    private String chosenSeries;
    private int chosenLevel;

    public static void open(final AbstractWindowSkeleton parent, final BlockPos controllerPos) {
        new WindowRoadStyle(parent, controllerPos).open();
    }

    private WindowRoadStyle(final AbstractWindowSkeleton parent, final BlockPos controllerPos) {
        super(parent, ResourceLocation.fromNamespaceAndPath(Crossroads.MODID, "gui/windowroadstyle.xml"));
        this.controllerPos = controllerPos;
        final RoadPlan plan = plan();
        this.chosenStyle = com.YLFXM.rp.crossroads.CustomRoadPack.UNIT_STYLE_ORIGINAL;
        this.chosenSeries = plan == null ? RoadUnitLibrary.STREETS : plan.series();
        this.chosenLevel = plan == null ? 1 : plan.unitLevel();

        for (int i = 0; i < SLOTS; i++) {
            final int slot = i;
            this.styleButtons[i] = this.findPaneOfTypeByID("style" + i, Button.class);
            this.styleButtons[i].setColors(0x000000, 0x9A9A9A, 0x000000);
            if (i < this.styles.size()) {
                final String style = this.styles.get(i);
                this.styleButtons[i].setHandler(button -> {
                    this.chosenStyle = style;
                    this.refresh();
                });
            } else {
                this.styleButtons[i].hide();
            }
            this.seriesButtons[i] = this.findPaneOfTypeByID("series" + i, Button.class);
            this.seriesButtons[i].setColors(0x000000, 0x9A9A9A, 0x000000);
            if (i < this.series.size()) {
                final String entry = this.series.get(i);
                this.seriesButtons[i].setHandler(button -> {
                    this.chosenSeries = entry;
                    // The new series may not have the tier that was selected;
                    // refresh() clamps it, so nothing out of range is submitted.
                    this.refresh();
                });
            } else {
                this.seriesButtons[i].hide();
            }
        }
        for (int i = 0; i < LEVEL_SLOTS; i++) {
            final int level = i + 1;
            this.levelButtons[i] = this.findPaneOfTypeByID("level" + i, Button.class);
            this.levelButtons[i].setColors(0x000000, 0x9A9A9A, 0x000000);
            this.levelButtons[i].setHandler(button -> {
                this.chosenLevel = level;
                this.refresh();
            });
        }
        this.registerButton("confirm", button -> this.submit());
        this.registerButton("cancel", button -> this.close());
        this.refresh();
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        this.refresh();
    }

    private void refresh() {
        for (int i = 0; i < this.styles.size() && i < SLOTS; i++) {
            final String style = this.styles.get(i);
            this.styleButtons[i].setText(Component.translatable(
                    "rp_crossroads.style." + style)
                    .append(style.equals(this.chosenStyle) ? "  ✔" : ""));
        }
        for (int i = 0; i < this.series.size() && i < SLOTS; i++) {
            final String entry = this.series.get(i);
            this.seriesButtons[i].setText(Component.translatable(
                    "rp_crossroads.series." + entry)
                    .append(entry.equals(this.chosenSeries) ? "  ✔" : ""));
        }
        final int maxLevel = this.maxLevel();
        if (this.chosenLevel > maxLevel) {
            this.chosenLevel = maxLevel;
        }
        // Slots past the series' highest tier are hidden rather than greyed: an
        // empty slot reads as "this series has three tiers", a greyed one reads
        // as "locked", and only one of those is true.
        for (int i = 0; i < LEVEL_SLOTS; i++) {
            final int level = i + 1;
            if (level > maxLevel) {
                this.levelButtons[i].hide();
                continue;
            }
            this.levelButtons[i].show();
            this.levelButtons[i].setText(Component.translatable(
                    "rp_crossroads.gui.level_value", level)
                    .append(level == this.chosenLevel ? "  ✔" : ""));
        }
        this.findPaneOfTypeByID("confirm", Button.class)
                .setEnabled(this.chosenStyle != null && this.chosenSeries != null
                        && this.chosenLevel >= 1 && this.chosenLevel <= maxLevel);
    }

    private int maxLevel() {
        if (this.chosenStyle == null || this.chosenSeries == null) {
            return 0;
        }
        final int max = RoadUnitLibrary.maxLevel(this.chosenStyle, this.chosenSeries);
        return Math.max(1, max);
    }

    private void submit() {
        if (this.chosenStyle == null || this.chosenSeries == null) {
            return;
        }
        PacketDistributor.sendToServer(
                new SetRoadStylePayload(this.controllerPos, this.chosenStyle, this.chosenSeries, this.chosenLevel));
        this.close();
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
