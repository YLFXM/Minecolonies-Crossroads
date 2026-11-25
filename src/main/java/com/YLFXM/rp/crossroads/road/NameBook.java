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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.YLFXM.rp.crossroads.Crossroads;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.util.RandomSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Word lists behind {@link RoadNames}: stems by category, weighted suffixes,
 * directions and ordinals.
 *
 * <p>The bundled copy is the default; a server can drop
 * {@code config/rp_crossroads/road_names.json} in and have it <em>replace</em> the
 * bundled one wholesale. Replacement rather than merge keeps the result
 * predictable: a pack author can see every word their roads may use, and a
 * half-written file cannot quietly add one category to the stock list.</p>
 *
 * <p>A file that fails validation is discarded as a whole and the bundled copy
 * keeps serving; the failure is logged once per load cycle, not once per road.</p>
 */
@EventBusSubscriber(modid = Crossroads.MODID)
public final class NameBook {
    /** Where the bundled word lists live. */
    private static final String BUNDLED = "/assets/rp_crossroads/roadnames/default.json";
    /** Server override, relative to the game's config directory. */
    private static final String OVERRIDE = "rp_crossroads/road_names.json";

    /** Which copy a loaded book came from. */
    public enum Source {
        /** The jar copy. */
        BUNDLED,
        /** The server's own file. */
        OVERRIDE,
        /** The server's file existed but was unusable. */
        FALLBACK
    }

    /** A loaded book plus where it came from. */
    public record Loaded(NameBook book, Source source) {
    }

    /** One suffix: display word and relative weight. */
    public record Suffix(String name, int weight) {
    }

    /**
     * One language's word lists.
     *
     * <p>{@code stemGroups} keeps the categories apart on purpose: a name is one
     * category plus a suffix, never two stems glued together, so the result reads
     * like a place name instead of a compound.</p>
     */
    public record Section(List<List<String>> stemGroups, List<Suffix> suffixes,
                          List<String> directions, List<String> ordinals, boolean spaced) {
        /** A generated base name, per the 70 / 20 / 10 shape. */
        public String name(final RandomSource random) {
            final Suffix suffix = suffix(random);
            final int roll = random.nextInt(100);
            final String stem = stem(random);
            if (roll < 70 || directions.isEmpty()) {
                return join(stem, suffix.name());
            }
            if (roll < 90) {
                return join(directions.get(random.nextInt(directions.size())), stem, suffix.name());
            }
            if (ordinals.isEmpty()) {
                return join(stem, suffix.name());
            }
            return join(ordinals.get(random.nextInt(ordinals.size())), suffix.name());
        }

        private String stem(final RandomSource random) {
            final List<String> group = stemGroups.get(random.nextInt(stemGroups.size()));
            return group.get(random.nextInt(group.size()));
        }

        private Suffix suffix(final RandomSource random) {
            int total = 0;
            for (final Suffix suffix : suffixes) {
                total += Math.max(0, suffix.weight());
            }
            if (total <= 0) {
                return suffixes.get(random.nextInt(suffixes.size()));
            }
            int roll = random.nextInt(total);
            for (final Suffix suffix : suffixes) {
                roll -= Math.max(0, suffix.weight());
                if (roll < 0) {
                    return suffix;
                }
            }
            return suffixes.get(suffixes.size() - 1);
        }

        private String join(final String... parts) {
            final StringBuilder text = new StringBuilder();
            for (final String part : parts) {
                if (part == null || part.isBlank()) {
                    continue;
                }
                if (text.length() > 0 && spaced) {
                    text.append(' ');
                }
                text.append(part);
            }
            return text.toString();
        }
    }

    private final Section zh;
    private final Section en;

    private NameBook(final Section zh, final Section en) {
        this.zh = zh;
        this.en = en;
    }

    public Section zh() {
        return zh;
    }

    public Section en() {
        return en;
    }

    public Section section(final boolean chinese) {
        return chinese ? zh : en;
    }

    private static volatile NameBook current;
    private static final AtomicBoolean WARNED = new AtomicBoolean();
    private static final AtomicInteger WARNINGS = new AtomicInteger();

    /** The book in use; loaded from the jar once, and from the override at server start. */
    public static NameBook current() {
        NameBook book = current;
        if (book == null) {
            synchronized (NameBook.class) {
                book = current;
                if (book == null) {
                    book = load(overridePath()).book();
                    current = book;
                }
            }
        }
        return book;
    }

    /** Re-reads the override (or the jar copy when there is none). Called at server start. */
    public static void reload() {
        current = load(overridePath()).book();
    }

    /**
     * Loads one book: the override when it exists and validates, the jar copy otherwise.
     *
     * @param override the server's file, or {@code null} to use the jar copy
     */
    public static Loaded load(final Path override) {
        final NameBook bundled = bundled();
        if (override == null || !Files.isRegularFile(override)) {
            return new Loaded(bundled, Source.BUNDLED);
        }
        final NameBook parsed = parse(read(override));
        if (parsed != null) {
            return new Loaded(parsed, Source.OVERRIDE);
        }
        if (WARNED.compareAndSet(false, true)) {
            WARNINGS.incrementAndGet();
            Crossroads.LOGGER.warn(
                    "Ignoring {}: it is not a complete road name book. The bundled word lists stay in use.",
                    override);
        }
        return new Loaded(bundled, Source.FALLBACK);
    }

    /** Test seam: forget the warnings already emitted. */
    static void resetWarningsForTests() {
        WARNED.set(false);
        WARNINGS.set(0);
        current = null;
    }

    /** How many fallback warnings have been emitted since the last reset. */
    static int warningsForTests() {
        return WARNINGS.get();
    }

    private static volatile NameBook bundledCache;

    /** The bundled book; a missing or broken resource is a build error worth throwing on. */
    static NameBook bundled() {
        NameBook cached = bundledCache;
        if (cached != null) {
            return cached;
        }
        cached = readBundled();
        bundledCache = cached;
        return cached;
    }

    private static NameBook readBundled() {
        try (var stream = NameBook.class.getResourceAsStream(BUNDLED)) {
            if (stream == null) {
                throw new IllegalStateException("Bundled road name book is missing: " + BUNDLED);
            }
            final NameBook parsed = parse(JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject());
            if (parsed == null) {
                throw new IllegalStateException("Bundled road name book does not validate: " + BUNDLED);
            }
            return parsed;
        } catch (final java.io.IOException ex) {
            throw new IllegalStateException("Could not read the bundled road name book", ex);
        }
    }

    /** The override path, or {@code null} when the game has no config directory configured. */
    private static Path overridePath() {
        try {
            return FMLPaths.CONFIGDIR.get().resolve(OVERRIDE);
        } catch (final RuntimeException ex) {
            return null;
        }
    }

    private static JsonObject read(final Path file) {
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (final Exception ex) {
            return null;
        }
    }

    /**
     * Parses a whole book; {@code null} when either language is unusable.
     *
     * <p>Partial acceptance would be worse than rejection: a file that only fixes
     * English would silently leave Chinese on the stock words, which is not what
     * "replace the book" says.</p>
     */
    static NameBook parse(final JsonObject json) {
        if (json == null || !json.has("zh") || !json.has("en")) {
            return null;
        }
        final Section zh = section(json.getAsJsonObject("zh"), false);
        final Section en = section(json.getAsJsonObject("en"), true);
        return zh == null || en == null ? null : new NameBook(zh, en);
    }

    private static Section section(final JsonObject json, final boolean spaced) {
        if (json == null) {
            return null;
        }
        final List<List<String>> groups = new ArrayList<>();
        if (json.has("stems") && json.get("stems").isJsonObject()) {
            for (final Map.Entry<String, JsonElement> category
                    : json.getAsJsonObject("stems").entrySet()) {
                if (!category.getValue().isJsonArray()) {
                    continue;
                }
                final List<String> words = strings(category.getValue().getAsJsonArray());
                if (!words.isEmpty()) {
                    groups.add(words);
                }
            }
        }
        final Map<String, String> names = new LinkedHashMap<>();
        if (json.has("suffixNames") && json.get("suffixNames").isJsonObject()) {
            for (final Map.Entry<String, JsonElement> entry
                    : json.getAsJsonObject("suffixNames").entrySet()) {
                names.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        final List<Suffix> suffixes = new ArrayList<>();
        if (json.has("suffix") && json.get("suffix").isJsonObject()) {
            for (final Map.Entry<String, JsonElement> entry : json.getAsJsonObject("suffix").entrySet()) {
                final int weight = Math.max(0, entry.getValue().getAsInt());
                suffixes.add(new Suffix(names.getOrDefault(entry.getKey(), entry.getKey()), weight));
            }
        }
        if (groups.isEmpty() || suffixes.isEmpty()) {
            return null;
        }
        final List<String> directions = json.has("direction") && json.get("direction").isJsonArray()
                ? strings(json.getAsJsonArray("direction")) : List.of();
        final List<String> ordinals = json.has("ordinal") && json.get("ordinal").isJsonArray()
                ? strings(json.getAsJsonArray("ordinal")) : List.of();
        return new Section(List.copyOf(groups), List.copyOf(suffixes),
                directions, ordinals, spaced);
    }

    private static List<String> strings(final JsonArray array) {
        final List<String> out = new ArrayList<>();
        for (final JsonElement element : array) {
            if (!element.isJsonPrimitive()) {
                continue;
            }
            final String text = element.getAsString().trim();
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
        return List.copyOf(out);
    }

    @SubscribeEvent
    public static void onServerStarted(final ServerStartedEvent event) {
        reload();
    }
}
