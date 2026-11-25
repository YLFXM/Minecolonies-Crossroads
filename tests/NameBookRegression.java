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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;

/**
 * Road name book: bundled defaults, the server override, and the shape of a
 * generated name.
 *
 * <p>Everything here runs without a level: the book is plain data, and a name is
 * a function of a random source. The only world-facing part — "is this name
 * already taken" — is exercised through {@link RoadNames#blueprintBase}, which
 * is the step that has to survive a name the word lists can produce.</p>
 */
public final class NameBookRegression {
    private static int checks;

    private static void check(final boolean value, final String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static RoadPlan plan() {
        return new RoadPlan(UUID.randomUUID(), Level.OVERWORLD,
                new BlockPos(10, 65, 20), new BlockPos(10, 65, 19),
                Direction.SOUTH, false, "minecolonies", "street", 4, WalkRange.of(1, 2), 1, 4);
    }

    public static void main(final String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
        Bootstrap.bootStrap();
        NameBook.resetWarningsForTests();

        // ---- 1. the bundled book parses and carries usable weights.
        final NameBook bundled = NameBook.load(null).book();
        check(NameBook.load(null).source() == NameBook.Source.BUNDLED,
                "a missing override means the bundled book");
        check(!bundled.zh().stemGroups().isEmpty() && !bundled.en().stemGroups().isEmpty(),
                "both languages carry stem categories");
        check(bundled.zh().suffixes().stream().mapToInt(NameBook.Suffix::weight).sum() == 100,
                "Chinese suffix weights are a percentage");
        check(bundled.en().suffixes().stream().mapToInt(NameBook.Suffix::weight).sum() == 100,
                "English suffix weights are a percentage");
        int zhWords = bundled.zh().stemGroups().stream().mapToInt(List::size).sum();
        int enWords = bundled.en().stemGroups().stream().mapToInt(List::size).sum();
        check(zhWords >= 60 && enWords >= 60,
                "the bundled book offers 60+ stems per language: " + zhWords + "/" + enWords);
        check(bundled.zh().suffixes().size() == 3,
                "Chinese keeps exactly the three official suffixes (road/street/avenue)");
        check(bundled.zh().suffixes().stream().noneMatch(s -> Set.of("巷", "胡同", "里").contains(s.name())),
                "no dialect or regional suffix in the bundled book");

        // ---- 2. an override replaces the book wholesale.
        final Path dir = Files.createTempDirectory("rp_crossroads_names");
        final Path override = dir.resolve("road_names.json");
        Files.writeString(override, """
                {
                  "zh": {
                    "stems": {"only": ["甲一"]},
                    "suffix": {"road": 1},
                    "suffixNames": {"road": "径"},
                    "direction": ["上"],
                    "ordinal": ["首"]
                  },
                  "en": {
                    "stems": {"only": ["Zeta"]},
                    "suffix": {"road": 1},
                    "suffixNames": {"road": "Path"},
                    "direction": ["Upper"],
                    "ordinal": ["First"]
                  }
                }
                """, StandardCharsets.UTF_8);
        final NameBook.Loaded replaced = NameBook.load(override);
        check(replaced.source() == NameBook.Source.OVERRIDE, "a valid override is used");
        final NameBook custom = replaced.book();
        check(custom.zh().stemGroups().size() == 1 && custom.zh().suffixes().size() == 1,
                "the override does not merge with the bundled categories");
        Set<String> customZh = new HashSet<>();
        Set<String> customEn = new HashSet<>();
        final RandomSource random = RandomSource.create(20261124L);
        for (int i = 0; i < 50; i++) {
            customZh.add(custom.zh().name(random));
            customEn.add(custom.en().name(random));
        }
        check(customZh.stream().allMatch(name -> name.startsWith("甲一") || name.startsWith("上") || name.startsWith("首")),
                "every Chinese name comes from the override: " + customZh);
        check(customZh.stream().allMatch(name -> name.endsWith("径")), "the override's own suffix is used");
        check(customEn.stream().allMatch(name -> name.endsWith("Path")), "the override's English suffix is used");

        // ---- 3. a broken override falls back, and says so exactly once.
        NameBook.resetWarningsForTests();
        final Path broken = dir.resolve("broken.json");
        Files.writeString(broken, "{\"zh\": {\"stems\": {}}, \"en\": {\"stems\": {\"x\": [\"Y\"]}}}",
                StandardCharsets.UTF_8);
        final NameBook.Loaded first = NameBook.load(broken);
        final NameBook.Loaded second = NameBook.load(broken);
        check(first.source() == NameBook.Source.FALLBACK && second.source() == NameBook.Source.FALLBACK,
                "an unusable override falls back to the bundled book");
        check(first.book() == second.book(), "the fallback keeps serving the same bundled book");
        check(NameBook.warningsForTests() == 1, "the fallback is logged once, not per call");
        check(NameBook.load(dir.resolve("absent.json")).source() == NameBook.Source.BUNDLED,
                "an absent override is not a failure");

        // ---- 4. 200 draws per language cover all three shapes and never blank.
        final RandomSource generator = RandomSource.create(7L);
        final Set<String> zhNames = new HashSet<>();
        final Set<String> enNames = new HashSet<>();
        boolean zhDirection = false;
        boolean zhOrdinal = false;
        boolean enDirection = false;
        boolean enOrdinal = false;
        for (int i = 0; i < 200; i++) {
            final String zh = bundled.zh().name(generator);
            final String en = bundled.en().name(generator);
            check(!zh.isBlank() && !en.isBlank(), "a generated name is never blank");
            zhNames.add(zh);
            enNames.add(en);
            zhDirection |= zh.matches("^[东南西北中].*");
            zhOrdinal |= zh.startsWith("第");
            enDirection |= en.matches("^(North|South|East|West|Central|Upper|Lower) .*");
            enOrdinal |= en.matches("^(First|Second|Third|Fourth|Fifth|Sixth|Seventh|Eighth|Ninth|Tenth) .*");
        }
        check(zhDirection && zhOrdinal, "Chinese draws cover the direction and ordinal shapes");
        check(enDirection && enOrdinal, "English draws cover the direction and ordinal shapes");
        check(zhNames.size() > 120 && enNames.size() > 120,
                "the stock word lists are varied enough to avoid repeats: " + zhNames.size() + "/" + enNames.size());
        check(customZh.size() >= 3, "a one-word override can still produce every shape");

        // ---- 5. suffix distribution follows the weights (2000 draws, ±10 points).
        final int[] zhCounts = new int[3];
        final String[] zhSuffixes = {"大道", "街", "路"};
        final int[] enCounts = new int[8];
        final String[] enSuffixes = {"Street", "Avenue", "Road", "Lane", "Drive", "Court", "Place", "Way"};
        final int[] enWeights = {30, 20, 20, 10, 8, 4, 4, 4};
        final int samples = 2000;
        final RandomSource share = RandomSource.create(11L);
        for (int i = 0; i < samples; i++) {
            final String zh = bundled.zh().name(share);
            final String en = bundled.en().name(share);
            for (int s = 0; s < zhSuffixes.length; s++) {
                if (zh.endsWith(zhSuffixes[s])) {
                    zhCounts[s]++;
                    break;
                }
            }
            for (int s = 0; s < enSuffixes.length; s++) {
                if (en.endsWith(enSuffixes[s])) {
                    enCounts[s]++;
                    break;
                }
            }
        }
        final int[] zhWeights = {15, 30, 55};
        for (int s = 0; s < zhSuffixes.length; s++) {
            final double actual = zhCounts[s] * 100.0 / samples;
            check(Math.abs(actual - zhWeights[s]) < 10,
                    "Chinese suffix share within 10 points: " + zhSuffixes[s] + "=" + actual);
        }
        for (int s = 0; s < enSuffixes.length; s++) {
            final double actual = enCounts[s] * 100.0 / samples;
            check(Math.abs(actual - enWeights[s]) < 10,
                    "English suffix share within 10 points: " + enSuffixes[s] + "=" + actual);
        }

        // ---- 6. a generated name survives the file-stem rules.
        final RoadPlan sample = plan();
        sample.setRoadName("东梧桐路");
        sample.setDisplayName(RoadNames.compose("colony", "东梧桐路"));
        final String stem = RoadNames.blueprintBase(sample);
        check(!stem.matches(".*[0-9].*"), "the stem carries no digits: " + stem);
        check(stem.matches("[^\\\\/:*?\"<>|]+"), "the stem carries no illegal characters: " + stem);
        check(stem.length() <= 37, "the stem stays inside the 32-character base plus tag: " + stem);
        check(RoadNames.tierOf(stem + "3", stem) == 3, "the tier still reads back from the stem");

        final StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            longName.append("长");
        }
        sample.setRoadName(longName.toString());
        sample.setDisplayName(RoadNames.compose("colony", longName.toString()));
        check(RoadNames.blueprintBase(sample).length() <= 37,
                "an over-long name is clamped before the tag");

        System.out.println("PASS " + checks + " name book assertions");
    }

    private NameBookRegression() {
    }
}
