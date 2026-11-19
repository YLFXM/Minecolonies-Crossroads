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
import com.YLFXM.rp.crossroads.road.RoadPlan;
import com.YLFXM.rp.crossroads.road.RoadUnitLibrary;
import com.ldtteam.blockui.controls.Button;
import com.minecolonies.core.client.gui.AbstractWindowSkeleton;
import java.util.function.IntConsumer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Asks which tier to build before a creative road order is handed over.
 *
 * <p>Creative players place the blocks themselves, so the tier has to be decided
 * before the build tool opens: the native tool has no tier picker for our unit
 * files, and picking afterwards would mean building the road twice. Survival
 * roads are ordered at tier 1 and upgraded by a builder afterwards, which is the
 * native flow.</p>
 *
 * <p>Only slots the series actually ships are shown, so a three-tier series
 * cannot offer a fourth.</p>
 */
public final class WindowRoadTier extends AbstractWindowSkeleton {
    private static final int SLOTS = 4;

    private final Button[] buttons = new Button[SLOTS];
    private final int maxLevel;
    private final IntConsumer onPick;
    private int chosen;

    /**
     * @param onPick receives the chosen tier, already inside the shipped range
     */
    public static void open(final AbstractWindowSkeleton parent, final RoadPlan plan, final IntConsumer onPick) {
        new WindowRoadTier(parent, plan, onPick).open();
    }

    private WindowRoadTier(final AbstractWindowSkeleton parent, final RoadPlan plan, final IntConsumer onPick) {
        super(parent, ResourceLocation.fromNamespaceAndPath(Crossroads.MODID, "gui/windowroadtier.xml"));
        this.onPick = onPick;
        this.maxLevel = Math.max(1, RoadUnitLibrary.maxLevel(plan.unitStyle(), plan.series()));
        this.chosen = Math.max(1, Math.min(plan.unitLevel(), this.maxLevel));

        for (int i = 0; i < SLOTS; i++) {
            final int level = i + 1;
            this.buttons[i] = this.findPaneOfTypeByID("tier" + i, Button.class);
            this.buttons[i].setColors(0x000000, 0x9A9A9A, 0x000000);
            this.buttons[i].setHandler(button -> {
                this.chosen = level;
                this.refresh();
            });
        }
        this.registerButton("confirm", button -> {
            this.onPick.accept(this.chosen);
            this.close();
        });
        this.registerButton("cancel", button -> this.close());
        this.refresh();
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        this.refresh();
    }

    private void refresh() {
        for (int i = 0; i < SLOTS; i++) {
            final int level = i + 1;
            if (level > this.maxLevel) {
                this.buttons[i].hide();
                continue;
            }
            this.buttons[i].show();
            this.buttons[i].setText(Component.translatable("rp_crossroads.gui.level_value", level)
                    .append(level == this.chosen ? "  ✔" : ""));
        }
    }
}
