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

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.blueprints.v1.BlueprintUtil;
import com.YLFXM.rp.crossroads.CustomRoadPack;
import com.YLFXM.rp.crossroads.Crossroads;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Keeps a road blueprint in the server structure pack <b>only while the road is
 * being built</b>.
 *
 * <h2>Why the file has to be there at all</h2>
 *
 * <p>Structurize's build tool sends only {@code pack + path} to the server
 * ({@code BuildToolPlacementMessage}); the server then reads the blueprint from
 * disk itself ({@code BlueprintPlacementHandling#handlePlacement} →
 * {@code StructurePacks.getBlueprintFuture(packId, blueprintPath, ...)}).
 * Complete / Pretty / Paste therefore <b>require</b> the blueprint to exist in a
 * registered structure pack on the serving side — pushing the NBT to the client
 * is not enough. (Getting this wrong is silent: every placement fails with
 * {@code NoSuchFileException} / "Couldn't retrieve blueprint" and the road simply
 * never appears.)</p>
 *
 * <h2>Why it must not stay there</h2>
 *
 * <p>{@code ServerStructurePackLoader.handleClientUpdate} answers a joining client
 * by zipping up <em>the whole pack directory</em> ({@code zipPack} walks the tree;
 * no per-file filter, no hook without a mixin). Anything in the pack directory is
 * handed to <em>every</em> player who joins. A finished road is nobody else's
 * business, so its blueprint is removed as soon as the road is
 * {@link RoadState#BUILT}.</p>
 *
 * <h2>Resulting invariant</h2>
 *
 * <p>{@code generated/} mirrors exactly "roads that are still being built".
 * {@link #rebuildFromStore} re-establishes that from the
 * {@link RoadBlueprintStore} snapshots on every server start, so the directory
 * cannot drift away from the real state even after a crash mid-build — and a
 * finished blueprint never survives a restart.</p>
 */
public final class RoadTemporaryPack {
    public static final String PACK_NAME = CustomRoadPack.PACK_NAME;
    /** Sub-path under the pack used for road blueprints (server and client). */
    private static final String ROOT = "generated";
    /** Directory under the pack that holds blueprints of roads still under construction. */
    public static final String ROOT_DIR = ROOT;

    /**
     * Path of the blueprint inside the pack. Meaningful on both sides: the server
     * keeps the file while building, the client receives it point-to-point.
     */
    public static String pathFor(final RoadPlan plan) {
        return pathFor(plan, plan.unitLevel());
    }

    /** Pack path of one tier of this road; every tier is published while the tool is open. */
    public static String pathFor(final RoadPlan plan, final int tier) {
        return ROOT + "/" + RoadNames.fileName(plan, tier) + ".blueprint";
    }

    /**
     * Writes the blueprint for {@code plan} into the pack, removing the file of
     * the plan it replaces when the derived file name changed.
     *
     * <p>Re-selecting A/B keeps the plan's UUID and display name, so the file
     * name is stable and the existing file is overwritten in place.</p>
     *
     * @return the pack's content version after the write, or {@code -1} on error
     */
    public static long replace(final ServerLevel level, final RoadPlan previous, final RoadPlan plan,
                               final Blueprint blueprint) {
        if (previous != null && !RoadNames.blueprintBase(previous).equals(RoadNames.blueprintBase(plan))) {
            deleteTierFiles(level, previous, java.util.Set.of());
        }
        return publishTiers(level, plan, java.util.Map.of(plan.unitLevel(), blueprint));
    }

    /**
     * Publishes every generated tier and removes any other tier file of the same road.
     *
     * <p>The directory is shared by all roads, so cleanup is scoped by the road's
     * digit-free base: only files that parse as {@code <thisBase><1..9>} are touched.</p>
     *
     * @return the pack's content version after the write, or {@code -1} on error
     */
    public static long publishTiers(final ServerLevel level, final RoadPlan plan,
                                    final java.util.Map<Integer, Blueprint> tiers) {
        final java.util.Set<String> keep = new java.util.HashSet<>();
        try {
            for (final java.util.Map.Entry<Integer, Blueprint> tier : tiers.entrySet()) {
                final Path path = blueprintPath(level, plan, tier.getKey());
                Files.createDirectories(path.getParent());
                NbtIo.writeCompressed(BlueprintUtil.writeBlueprintToNBT(tier.getValue()), path);
                keep.add(path.getFileName().toString());
            }
        } catch (final IOException ex) {
            Crossroads.LOGGER.error("Could not write road blueprints for {}", plan.id(), ex);
            return -1;
        }
        deleteTierFiles(level, plan, keep);
        return CustomRoadPack.install(level.getServer());
    }

    /** Removes every published tier of this road except the file names in {@code keep}. */
    public static void deleteTierFiles(final ServerLevel level, final RoadPlan plan,
                                       final java.util.Set<String> keep) {
        final Path dir = packRoot(level).resolve(ROOT);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (final String name : staleTierFiles(files.map(p -> p.getFileName().toString()).toList(),
                    RoadNames.blueprintBase(plan), keep)) {
                Files.deleteIfExists(dir.resolve(name));
            }
        } catch (final IOException ex) {
            Crossroads.LOGGER.warn("Could not clean road blueprints for {}", plan.id(), ex);
        }
    }

    /**
     * File names this road's cleanup must delete.
     *
     * <p>Only files that parse as {@code <base><1..9>} belong to the road; everything
     * else in the shared directory belongs to another road and stays. Kept separate
     * from the listing so the rule can be exercised without a filesystem.</p>
     */
    static java.util.List<String> staleTierFiles(final java.util.Collection<String> names,
                                                 final String base, final java.util.Set<String> keep) {
        final java.util.List<String> stale = new java.util.ArrayList<>();
        for (final String name : names) {
            if (!name.endsWith(".blueprint") || keep.contains(name)) {
                continue;
            }
            final String stem = name.substring(0, name.length() - ".blueprint".length());
            if (RoadNames.tierOf(stem, base) > 0) {
                stale.add(name);
            }
        }
        return java.util.List.copyOf(stale);
    }

    /**
     * Removes the blueprint from the pack. Called when the road finishes — the
     * original build tool no longer needs it, and Repair / Remove read the
     * {@link RoadBlueprintStore} snapshot instead — and when the plan is released.
     */
    public static void delete(final ServerLevel level, final RoadPlan plan) {
        deleteTierFiles(level, plan, java.util.Set.of());
        CustomRoadPack.install(level.getServer());
    }

    /**
     * Rewrites {@code generated/} from the snapshots so it contains exactly the
     * blueprints of roads that are not {@link RoadState#BUILT} yet.
     *
     * <p>Runs on every server start. Without it, a crash between "road finished"
     * and "file removed" would leave that blueprint in the pack permanently — and
     * a blueprint in the pack is broadcast to every player who joins.</p>
     */
    public static void rebuildFromStore(final MinecraftServer server) {
        clearGenerated(server);
        for (final ServerLevel level : server.getAllLevels()) {
            for (final RoadPlan plan : RoadManager.get(level).all()) {
                if (!RoadRules.canPublish(plan)) {
                    continue;
                }
                final Blueprint blueprint = RoadBlueprintStore.load(level, plan);
                if (blueprint == null) {
                    // DRAFT plans have no snapshot yet; nothing to publish.
                    continue;
                }
                try {
                    final Path path = blueprintPath(level, plan, plan.unitLevel());
                    Files.createDirectories(path.getParent());
                    NbtIo.writeCompressed(BlueprintUtil.writeBlueprintToNBT(blueprint), path);
                } catch (final IOException ex) {
                    Crossroads.LOGGER.warn("Could not restore road blueprint {}", plan.id(), ex);
                }
            }
        }
        CustomRoadPack.install(server);
        Crossroads.LOGGER.info("Road structure pack restored: only in-progress blueprints are published");
    }

    private static void clearGenerated(final MinecraftServer server) {
        final Path dir = CustomRoadPack.packRoot(server).resolve(ROOT);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (final Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (final IOException ex) {
            Crossroads.LOGGER.warn("Could not clear generated road blueprints", ex);
        }
    }

    private static Path packRoot(final ServerLevel level) {
        return CustomRoadPack.packRoot(level.getServer());
    }

    private static Path blueprintPath(final ServerLevel level, final RoadPlan plan, final int tier) {
        return packRoot(level).resolve(ROOT).resolve(RoadNames.fileName(plan, tier) + ".blueprint");
    }

    private RoadTemporaryPack() {
    }
}
