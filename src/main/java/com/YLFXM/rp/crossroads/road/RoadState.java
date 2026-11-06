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
package com.YLFXM.rp.crossroads.road;

/**
 * Lifecycle of a road plan, as shown in every panel.
 *
 * <p>The enum name stays the persisted/compared form; only the panel text is
 * translated, so {@link #toString()} must not be shown to a player.</p>
 */
public enum RoadState {
    DRAFT,
    PLANNED,
    BUILDING,
    BUILT,
    REMOVING,
    REMOVED;

    /** Language key for this state; one key per constant, in both lang files. */
    public String translationKey() {
        return "rp_crossroads.state." + this.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Translated panel text. */
    public net.minecraft.network.chat.Component display() {
        return net.minecraft.network.chat.Component.translatable(this.translationKey());
    }
}
