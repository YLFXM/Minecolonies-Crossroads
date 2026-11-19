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

import com.YLFXM.rp.crossroads.Config;
import com.YLFXM.rp.crossroads.CustomRoadPack;
import com.YLFXM.rp.crossroads.Crossroads;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.blueprints.v1.BlueprintUtil;
import com.minecolonies.api.colony.IColony;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Terrain dig and fill, expressed as a normal colony work order.
 *
 * <p>The player never digs or places anything. A selection becomes a blueprint
 * and a {@link WorkOrderDecoration}; a builder runs the native CLEAR/PLACE
 * stages on it. Two consequences worth stating, because both are load-bearing:</p>
 *
 * <ul>
 *   <li>Air in a blueprint means "this cell has to become air", so an all-air
 *       blueprint is a dig order. That is the same rule the native fixed-size
 *       digging works by, and the same one our road repair relies on.</li>
 *   <li>Materials for a fill come out of the colony's inventory through the
 *       native resource stage; this class never touches items.</li>
 * </ul>
 */
public final class TerrainOrders {
    /** Sub-path inside the structure pack, next to the road blueprints. */
    private static final String ROOT = "generated";
    private static final String PREFIX = "terrain_";

    /**
     * Blueprints this player created and that are still on disk.
     *
     * <p>Per player on purpose: applying a road or binding the wand to another
     * controller only clears the files of the player who did it, never a
     * neighbour's pending order.</p>
     */
    private static final Map<UUID, List<String>> CREATED = new ConcurrentHashMap<>();

    /**
     * One prepared selection waiting for the player to place it in the native tool.
     *
     * <p>{@code anchor} is the blueprint origin (the box's minimum corner) and is
     * also what the tool marks, so the marker sits on the first cell of the
     * volume.</p>
     */
    public record Prepared(UUID id, String relative, BlockPos anchor,
                           int sizeX, int sizeY, int sizeZ, boolean fill, double packVersion,
                           net.minecraft.nbt.CompoundTag blueprint) {
    }

    /**
     * Players with a prepared selection, by player.
     *
     * <p>Session-scoped on purpose: the blueprint is regenerated from the
     * selection, so a restart simply means picking the corners again. A new
     * preparation replaces the previous one and deletes its file.</p>
     */
    private static final Map<UUID, Prepared> PENDING = new ConcurrentHashMap<>();

    /**
     * Builds the blueprint for a selection; does not place any order.
     *
     * <p>The player has to see the volume first. The blueprint goes into the
     * pack and the preview data goes back to the client, which draws the box and
     * only then offers "open build tool". Placement itself is the native flow —
     * the tool confirms through Structurize, which creates the decoration order.</p>
     *
     * @return the prepared selection, or {@code null} when the request was refused
     */
    public static Prepared prepare(final ServerLevel level, final ServerPlayer player,
                                   final BlockPos a, final BlockPos b, final boolean fill) {
        final BlockPos min = anchor(a, b);
        final BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()),
                Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        final int sizeX = max.getX() - min.getX() + 1;
        final int sizeY = max.getY() - min.getY() + 1;
        final int sizeZ = max.getZ() - min.getZ() + 1;
        // A side longer than a short cannot be expressed in the native
        // blueprint at all; the practical limit is far below that anyway.
        if (sizeX > 256 || sizeY > 256 || sizeZ > 256
                || level.isOutsideBuildHeight(min) || level.isOutsideBuildHeight(max)) {
            RoadNotices.chat(player, null, "terrain_size", "rp_crossroads.terrain.too_large",
                    (long) sizeX * sizeY * sizeZ, Config.maxTerrainOrderVolume());
            return null;
        }
        final long volume = (long) sizeX * sizeY * sizeZ;
        if (volume > Config.maxTerrainOrderVolume()) {
            RoadNotices.chat(player, null, "terrain_size", "rp_crossroads.terrain.too_large",
                    volume, Config.maxTerrainOrderVolume());
            return null;
        }

        // Fill writes the placeholder, not a concrete block: the builder resolves
        // `blocksolidsubstitution` through getSolidSubstitution(), which is the
        // colony's own "filling block" setting. Reading that setting here would
        // duplicate the native rule and freeze today's value into the blueprint.
        final BlockState filler = fill
                ? com.ldtteam.structurize.blocks.ModBlocks.blockSolidSubstitution.get().defaultBlockState()
                : Blocks.AIR.defaultBlockState();

        // The blueprint origin is the anchor, so the marker and the first cell are
        // the same point. This has to be written explicitly: with no anchor block
        // in the blueprint, Structurize's default primary offset is
        // (sizeX/2, 0, sizeZ/2), which would place the volume half a box off in
        // X/Z. The corners are ordered above (A = minimum), so zero is correct;
        // the subtraction keeps it right even if that ordering ever goes away.
        final Blueprint blueprint = solidBlueprint(sizeX, sizeY, sizeZ, filler, level);
        blueprint.setCachePrimaryOffset(a.subtract(min));
        final String file = PREFIX + UUID.randomUUID() + ".blueprint";
        final String relative = ROOT + "/" + file;
        final Path path = CustomRoadPack.packRoot(level.getServer()).resolve(ROOT).resolve(file);
        try {
            Files.createDirectories(path.getParent());
            NbtIo.writeCompressed(BlueprintUtil.writeBlueprintToNBT(blueprint), path);
        } catch (final IOException ex) {
            Crossroads.LOGGER.error("Could not write terrain blueprint {}", path, ex);
            RoadNotices.chat(player, null, "terrain_write", "rp_crossroads.terrain.failed");
            return null;
        }
        // The pack has to list the new file before the tool can load it.
        final double version = CustomRoadPack.install(level.getServer());

        final Prepared prepared = new Prepared(UUID.randomUUID(), relative, min,
                sizeX, sizeY, sizeZ, fill, version, BlueprintUtil.writeBlueprintToNBT(blueprint));
        final Prepared previous = PENDING.put(player.getUUID(), prepared);
        if (previous != null) {
            deleteQuietly(CustomRoadPack.packRoot(level.getServer()).resolve(previous.relative()));
        }
        CREATED.computeIfAbsent(player.getUUID(), ignored -> new ArrayList<>()).add(relative);
        Crossroads.LOGGER.info("Terrain {} prepared: {}x{}x{} at {} by {}",
                fill ? "fill" : "dig", sizeX, sizeY, sizeZ, min, player.getName().getString());
        return prepared;
    }

    /** The selection this player prepared most recently, or {@code null}. */
    public static Prepared pending(final UUID player) {
        return PENDING.get(player);
    }

    /**
     * Sends the prepared blueprint to the player and opens the native tool.
     *
     * <p>The tool runs with no selection callback, so confirming it goes through
     * Structurize's own placement message and MineColonies creates the order.
     * This mod does not place anything and does not admit the order.</p>
     */
    public static boolean openTool(final ServerLevel level, final ServerPlayer player) {
        final Prepared prepared = PENDING.get(player.getUUID());
        if (prepared == null) {
            RoadNotices.chat(player, null, "terrain_missing", "rp_crossroads.terrain.not_prepared");
            return false;
        }
        RoadNativeBuildTool.openTerrain(player, prepared);
        return true;
    }

    /**
     * Deletes terrain blueprints whose work order already exists.
     *
     * <p>The order carries its own copy of the blueprint, so the file only has to
     * survive until a builder is assigned. Cancelling never creates an order, so a
     * cancelled selection keeps its file — which is what "cancel keeps the
     * blueprint" means here.</p>
     *
     * <p>Creative placement creates no order at all; those files are cleared when
     * the player prepares another selection, applies a road blueprint, or rebinds
     * the wand.</p>
     */
    public static void sweep(final MinecraftServer server) {
        if (CREATED.isEmpty()) {
            return;
        }
        for (final Map.Entry<UUID, List<String>> entry : CREATED.entrySet()) {
            final List<String> kept = new ArrayList<>();
            for (final String relative : entry.getValue()) {
                if (ordered(relative)) {
                    deleteQuietly(CustomRoadPack.packRoot(server).resolve(relative));
                } else {
                    kept.add(relative);
                }
            }
            if (kept.isEmpty()) {
                CREATED.remove(entry.getKey());
            } else {
                CREATED.put(entry.getKey(), kept);
            }
        }
    }

    /** True when some colony holds an order pointing at this pack path. */
    private static boolean ordered(final String relative) {
        for (final IColony colony : com.minecolonies.api.colony.IColonyManager.getInstance().getAllColonies()) {
            for (final var order : colony.getWorkManager().getWorkOrders().values()) {
                final String path = order.getStructurePath() == null ? ""
                        : order.getStructurePath().replace('\\', '/');
                if (path.equals(relative)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Removes this player's terrain blueprints.
     *
     * <p>Called when a road blueprint is applied and when the wand is bound to
     * another road: at that point the player has moved on, and the file is no
     * longer needed — the order already carries its own copy.</p>
     */
    public static void clearFor(final MinecraftServer server, final UUID player) {
        PENDING.remove(player);
        final List<String> files = CREATED.remove(player);
        if (files == null || files.isEmpty()) {
            return;
        }
        final Path root = CustomRoadPack.packRoot(server).resolve(ROOT);
        int removed = 0;
        for (final String relative : files) {
            final Path path = root.resolve(relative.substring(ROOT.length() + 1));
            if (deleteQuietly(path)) {
                removed++;
            }
        }
        if (removed > 0) {
            Crossroads.LOGGER.info("Cleared {} terrain blueprint(s) for {}", removed, player);
        }
    }

    /** Minimum corner of the selection: the blueprint origin and the order location. */
    public static BlockPos anchor(final BlockPos a, final BlockPos b) {
        return new BlockPos(Math.min(a.getX(), b.getX()),
                Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
    }

    private static Blueprint solidBlueprint(final int sizeX, final int sizeY, final int sizeZ,
                                            final BlockState state, final ServerLevel level) {
        // One palette entry and an all-zero index array: every cell is that
        // block. For a dig that is air, which the builder's CLEAR stage reads as
        // "remove whatever is here".
        return new Blueprint((short) sizeX, (short) sizeY, (short) sizeZ, (short) 1,
                new ArrayList<>(List.of(state)),
                new short[sizeY][sizeZ][sizeX], new CompoundTag[0], new ArrayList<>(),
                level.registryAccess());
    }

    private static boolean deleteQuietly(final Path path) {
        try {
            return Files.deleteIfExists(path);
        } catch (final IOException ex) {
            Crossroads.LOGGER.warn("Could not delete terrain blueprint {}", path, ex);
            return false;
        }
    }

    private TerrainOrders() { }
}
