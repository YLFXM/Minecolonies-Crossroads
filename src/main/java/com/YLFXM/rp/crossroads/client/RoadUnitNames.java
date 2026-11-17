/*
 * Copyright (c) 2026 YLFXM. All rights reserved.
 */
package com.YLFXM.rp.crossroads.client;

import com.YLFXM.rp.crossroads.CustomRoadPack;

/**
 * Turns a preview file name into the label the native build tool shows.
 *
 * <p>Preview files are named {@code <role>_<level>.blueprint} so a level is part of
 * the file identity, which is what the vanilla level mechanism keys on. The tool
 * itself uses the raw file name as both the entry label and the grouping key, so
 * {@code road_1} would show up as "road_1" and never group with {@code road_2}.
 * Stripping the trailing {@code _<digits>} before the tool sees the name restores
 * vanilla behaviour: one entry called "road" and a {@code Level:} row for the
 * variants.</p>
 *
 * <p>Only this mod's pack is rewritten. Other packs may use digits for genuinely
 * different blueprints, and silently merging those would be worse than an ugly
 * label.</p>
 */
public final class RoadUnitNames {
    /**
     * @param name     the name the tool would show, usually the file name
     * @param packName pack the blueprint belongs to
     * @return {@code name} without a trailing {@code _<digits>} when this is our pack
     */
    public static String display(final String name, final String packName) {
        if (name == null || name.isEmpty() || !CustomRoadPack.PACK_NAME.equals(packName)) {
            return name;
        }
        int digits = name.length();
        while (digits > 0 && Character.isDigit(name.charAt(digits - 1))) {
            digits--;
        }
        if (digits == name.length() || digits < 2 || name.charAt(digits - 1) != '_') {
            return name;
        }
        return name.substring(0, digits - 1);
    }

    private RoadUnitNames() {
    }
}
