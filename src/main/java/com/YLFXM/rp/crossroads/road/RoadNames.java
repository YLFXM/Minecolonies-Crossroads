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

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Road display-name generator. Chinese/English word lists are bundled; they can
 * be replaced by a larger pack later without changing the plan format.
 */
public final class RoadNames {
    private static final List<String> ZH_NAMES = List.of(
            "梧桐", "青云", "望江", "长乐", "安宁", "白桦", "枫林", "松涛",
            "朝阳", "明月", "清溪", "玉兰", "桂花", "银杏", "春晖", "锦绣",
            "广安", "永宁", "文华", "和风", "远山", "星河", "翠竹", "清河");

    private static final List<String> EN_NAMES = List.of(
            "Oak", "Willow", "Maple", "Pine", "Birch", "Cedar", "Elm", "Aspen",
            "River", "Hill", "Meadow", "Sunset", "Dawn", "Harbor", "Kings",
            "Queens", "Market", "Mill", "Stone", "Green", "Rose", "Lark",
            "Foxglove", "Amber");

    /** Prefix used for roads that stand outside every colony. */
    public static final String WILDERNESS_ZH = "野外";
    public static final String WILDERNESS_EN = "Wilderness";

    /** A generated name: the base on its own, and the labelled form with prefix. */
    public record Name(String base, String display) {
    }

    /** Road name without the ownership prefix, for narrow native-tool buttons. */
    public static String shortName(final RoadPlan plan) {
        return baseNameOf(label(plan));
    }

    /**
     * Strips the ownership prefix from a full road label.
     *
     * <p>Prefixes are "&lt;owner&gt;-&lt;base&gt;" or "&lt;owner&gt; - &lt;base&gt;";
     * base names never contain a dash, so the last separator is the split point.
     * That is also why renaming a road's owner does not change its blueprint file
     * name — {@link #fileName} is built from the base alone.</p>
     */
    public static String baseNameOf(final String display) {
        if (display == null) {
            return "";
        }
        final int english = display.lastIndexOf(" - ");
        if (english >= 0) {
            return display.substring(english + 3);
        }
        final int chinese = display.lastIndexOf('-');
        if (chinese >= 0) {
            return display.substring(chinese + 1);
        }
        return display;
    }

    /** Whether a road name is Chinese, which also picks the separator. */
    public static boolean isChinese(final String text) {
        return text != null && text.codePoints()
                .anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    /** Joins an ownership prefix and a base name. */
    public static String compose(final String prefix, final String base) {
        return isChinese(base) ? prefix + "-" + base : prefix + " - " + base;
    }

    /**
     * Prefix for a road at {@code pos}: the colony that <em>claims</em> it, or the
     * wilderness marker.
     *
     * <p>Deliberately {@code getColonyByPosFromWorld} and not
     * {@code getClosestColony}: the latter returns a colony for any position in
     * the world, so a road built out in the wild used to be signed with the
     * player's own colony just because that colony happened to be the nearest
     * one.</p>
     */
    public static String ownerPrefix(final ServerLevel level, final BlockPos pos, final boolean chinese) {
        final IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(level, pos);
        if (colony != null) {
            return colony.getName();
        }
        return chinese ? WILDERNESS_ZH : WILDERNESS_EN;
    }

    /**
     * Re-applies the ownership prefix if it no longer matches who owns the road.
     *
     * <p>Ownership is derived from the controller's position on every check, so a
     * colony growing over a wilderness road changes the owner without touching
     * the plan. The label has to follow, otherwise a road would keep claiming to
     * belong to whoever drew it.</p>
     *
     * @return true when the label changed and the plan needs saving
     */
    public static boolean reconcile(final ServerLevel level, final RoadPlan plan) {
        final String display = plan.displayName();
        if (display == null || display.isBlank()) {
            return false;
        }
        final String base = plan.roadName().isBlank() ? baseNameOf(display) : plan.roadName();
        if (base.isBlank()) {
            return false;
        }
        if (plan.roadName().isBlank()) {
            plan.setRoadName(base);
        }
        // Ownership is resolved from the controller position everywhere else, so
        // the label uses the same coordinate and cannot disagree at a border.
        final String wanted = compose(ownerPrefix(level, plan.controllerPos(), isChinese(base)), base);
        if (wanted.equals(display)) {
            return false;
        }
        plan.setDisplayName(wanted);
        return true;
    }

    /**
     * Short, unique native-tool label. Native build-tool buttons are narrow and
     * truncate at the first long prefix; a compact road name + short hash stays
     * readable and unique.
     */
    public static String fileName(final RoadPlan plan) {
        return fileName(plan, plan.unitLevel());
    }

    /**
     * Blueprint file stem of one tier: {@code <base><tier>}.
     *
     * <p>The tier has to be the <b>only</b> digit in the name. The decoration
     * controller's {@code getLevel} reads every digit in the schematic name and
     * parses it as one number, and the native tool groups files by removing that
     * number from the file name ({@code replace(level,"")}) — a digit anywhere in
     * the base would join the number or dodge the removal.</p>
     */
    public static String fileName(final RoadPlan plan, final int tier) {
        return blueprintBase(plan) + tier;
    }

    /**
     * Digit-free, per-road file stem shared by every tier of a road.
     *
     * <p>Bases come from generated display names, which are only unique together
     * with their owner prefix; the {@code generated/} pack directory is shared by
     * every road in the save, so the stem carries a four-letter tag derived from
     * the plan id. Four letters keep the uniqueness of the old {@code -<id4>}
     * suffix (one letter per hex digit, a-j for 0-9 and k-p for a-f) without
     * introducing a second number.</p>
     */
    public static String blueprintBase(final RoadPlan plan) {
        String name = shortName(plan).replaceAll("[0-9]", "");
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (name.isBlank()) {
            name = "road";
        }
        if (name.length() > 32) {
            name = name.substring(0, 32).trim();
        }
        return name + "-" + idTag(plan);
    }

    /** Four letters, one per hex digit of the id prefix. */
    private static String idTag(final RoadPlan plan) {
        final String id = plan.id().toString().replace("-", "");
        final StringBuilder tag = new StringBuilder(4);
        for (int i = 0; i < 4 && i < id.length(); i++) {
            tag.append((char) ('a' + Character.digit(id.charAt(i), 16)));
        }
        return tag.toString();
    }

    /** Tier encoded in a file stem, or {@code -1} when the stem is not exactly {@code base<1..9>}. */
    public static int tierOf(final String stem, final String base) {
        if (stem == null || base == null || !stem.startsWith(base)) {
            return -1;
        }
        final String suffix = stem.substring(base.length());
        if (!suffix.matches("[1-9]")) {
            return -1;
        }
        return Integer.parseInt(suffix);
    }

    /** File stem with its trailing tier digits removed, for "same road" comparisons. */
    public static String stripTier(final String stem) {
        return stem == null ? "" : stem.replaceAll("[0-9]+$", "");
    }

    public static String label(final RoadPlan plan) {
        return plan.displayName() == null || plan.displayName().isBlank()
                ? plan.id().toString() : plan.displayName();
    }

    public static Name generate(final ServerLevel level,
                                final BlockPos pos,
                                final ServerPlayer player) {
        final boolean chinese = player.getLanguage() != null
                && player.getLanguage().toLowerCase(java.util.Locale.ROOT).startsWith("zh");
        final String prefix = ownerPrefix(level, pos, chinese);

        for (int attempt = 0; attempt < 64; attempt++) {
            final String base;
            if (chinese) {
                final String word = ZH_NAMES.get(level.getRandom().nextInt(ZH_NAMES.size()));
                base = word + (level.getRandom().nextInt(4) == 0 ? "大道" : "路");
            } else {
                final String word = EN_NAMES.get(level.getRandom().nextInt(EN_NAMES.size()));
                base = word + (level.getRandom().nextInt(4) == 0 ? " Avenue" : " Road");
            }
            final String candidate = compose(prefix, base);
            if (isUnique(level, candidate)) {
                return new Name(base, candidate);
            }
        }

        // Extremely unlikely fallback: suffix until unique.
        final String base = chinese ? "道路" : "Road";
        int suffix = 2;
        while (!isUnique(level, compose(prefix, base + " " + suffix))) {
            suffix++;
        }
        final String numbered = base + " " + suffix;
        return new Name(numbered, compose(prefix, numbered));
    }

    private static boolean isUnique(final ServerLevel level, final String name) {
        for (final RoadPlan plan : RoadManager.get(level).all()) {
            if (name.equalsIgnoreCase(plan.displayName())) {
                return false;
            }
        }
        return true;
    }

    private RoadNames() {
    }
}
