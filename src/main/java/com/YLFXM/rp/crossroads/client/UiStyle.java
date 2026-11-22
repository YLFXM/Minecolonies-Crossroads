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

import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.Text;

/**
 * The one place the mod's windows take their look from.
 *
 * <p>Every window is drawn with vanilla button textures at their native sizes,
 * so "smaller" means a smaller texture tier, a smaller font scale and less
 * padding — never a stretched or squashed texture. Keeping those numbers here
 * keeps seven XML files and seven window classes from each inventing their own
 * (which is how the panels drifted apart in the first place).</p>
 */
public final class UiStyle {
    /** Safe area every control keeps from the window edge (paper has a ~4 px border). */
    public static final int PAD = 8;
    /** Height of one list row; a list is a whole number of these. */
    public static final int ROW = 20;

    /** Window title. */
    public static final double TITLE = 0.9;
    /** Body text and button labels. */
    public static final double TEXT = 0.8;
    /** Secondary line: status, coordinates, counts. */
    public static final double SECONDARY = 0.7;

    public static final int INK = 0x000000;
    /** Disabled label; BlockUI reuses "color" for the disabled state unless told otherwise. */
    public static final int INK_DISABLED = 0x9A9A9A;
    /** Readable but clearly not actionable (a listed road this player may not manage). */
    public static final int INK_MUTED = 0x8A8A8A;

    /** Paper background for every window that has one; the style picker deliberately has none. */
    public static final String PAPER = "minecolonies:textures/gui/builderhut/builder_paper_wide2.png";

    /** Vanilla texture tiers, at their native heights. A button never leaves its tier. */
    public static final String BUTTON_LARGE = "minecolonies:textures/gui/builderhut/builder_button_large.png";
    public static final String BUTTON_MEDIUM = "minecolonies:textures/gui/builderhut/builder_button_medium.png";
    public static final String BUTTON_MEDIUM_SMALL = "minecolonies:textures/gui/builderhut/builder_button_medium_small.png";
    public static final String BUTTON_SMALL = "minecolonies:textures/gui/builderhut/builder_button_small.png";
    public static final String BUTTON_QUITE_SMALL = "minecolonies:textures/gui/builderhut/builder_button_quite_small.png";
    public static final String BUTTON_VERY_SMALL = "minecolonies:textures/gui/builderhut/builder_button_very_small.png";
    public static final String BUTTON_MINI = "minecolonies:textures/gui/builderhut/builder_button_mini.png";

    /** Disabled buttons must not look enabled: a single grey, applied everywhere. */
    public static void disable(final Button button) {
        button.setColors(INK, INK_DISABLED, INK);
    }

    /** A button whose hover is a shade darker than its normal state (a selected option). */
    public static void selectable(final Button button) {
        button.setColors(INK, INK_DISABLED, 0x444444);
    }

    /** Body text at one of the three scales. */
    public static void text(final Text text, final double scale) {
        text.setTextScale(scale);
        text.setColors(INK);
    }

    /** Body text that stays readable but marks "listed, not yours". */
    public static void muted(final Text text, final double scale) {
        text.setTextScale(scale);
        text.setColors(INK_MUTED);
    }

    private UiStyle() {
    }
}
