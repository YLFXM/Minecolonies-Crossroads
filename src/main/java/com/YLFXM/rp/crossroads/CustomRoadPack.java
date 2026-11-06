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
package com.YLFXM.rp.crossroads;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.blueprints.v1.BlueprintUtil;
import com.ldtteam.structurize.storage.StructurePackMeta;
import com.ldtteam.structurize.storage.StructurePacks;
import com.minecolonies.core.blocks.BlockDecorationController;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Materializes a small native structure pack containing the initial
 * infrastructure/custom/road blueprint (visible anchor + decoration controller).
 */
@EventBusSubscriber(modid = Crossroads.MODID)
public final class CustomRoadPack {
    public static final String PACK_NAME = "Crossroads";
    /**
     * Pack name shipped before 0.2.0-beta.1, kept for one purpose only: deleting
     * the stale client-side copy. It is never an accepted identity, so an old
     * blueprint or controller is simply foreign.
     */
    public static final String STALE_PACK_NAME = "Reconstruction Plan";
    /** Whether the pack name is this mod's pack. */
    public static boolean isOurs(final String pack) {
        return PACK_NAME.equals(pack);
    }
    /** Style pack the road units are authored against, and the safe default pick. */
    public static final String STYLE_PACK = "Minecolonies Original";
    private static final String BLUEPRINT_PATH = "initial/road.blueprint";

    @SubscribeEvent
    public static void onServerStarted(final ServerStartedEvent event) {
        install(event.getServer());
        // Re-derive generated/ from the snapshots: it must contain exactly the
        // blueprints of roads that are still being built. Without this a crash
        // between "road finished" and "file removed" would leave that blueprint
        // in the pack permanently, and the pack is broadcast to every joining
        // player.
        com.YLFXM.rp.crossroads.road.RoadTemporaryPack.rebuildFromStore(event.getServer());
    }

    public static Path packRoot(final MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("rp_crossroads").resolve("structurepack");
    }

    /**
     * Rebuilds and re-registers the per-save pack.
     *
     * @return the content version written into pack.json, or {@code -1} on error
     */
    public static long install(final MinecraftServer server) {
        try {
            final Path packRoot = packRoot(server);
            Files.createDirectories(packRoot.resolve("initial"));
            // Remove the old two/three-level layout so the native pack list
            // shows only the new first-level categories.
            deleteRecursively(packRoot.resolve("infrastructure"));

            final Path blueprintPath = packRoot.resolve(BLUEPRINT_PATH);
            // Always refresh the tiny generated blueprint so older tests do not
            // keep the previous top-mounted controller layout.
            writeControllerBlueprint(blueprintPath, server.registryAccess());

            // The pack version follows only the shipped blueprint (see
            // contentVersion): generated/ is per-road and must not move it, or
            // every road completion would invalidate every client's copy.
            final long version = contentVersion(packRoot);

            final Path packJson = packRoot.resolve("pack.json");
            final boolean unchanged = storedVersion(packJson) == version;
            if (!unchanged) {
                Files.writeString(packJson, """
                        {
                          "icon": "",
                          "name": "%s",
                          "authors": ["YLFXM"],
                          "desc": "Crossroads: initial anchor and generated road blueprints.",
                          "mods": ["structurize", "minecolonies"],
                          "version": %d,
                          "pack-format": 1
                        }
                        """.formatted(PACK_NAME, version), StandardCharsets.UTF_8);
            }

            // Registration is only needed when the meta is missing or its version
            // changed; re-registering on every publish replaced the meta object and
            // spammed "Registered structure pack" once per blueprint write.
            if (!unchanged || !StructurePacks.hasPack(PACK_NAME)) {
                // immutable=false is mandatory: ServerStructurePackLoader only ships
                // packs to clients when !pack.isImmutable(). Registering this pack as
                // immutable leaves every client waiting for a pack the server will
                // never send, so the client never finishes StructurePacks loading and
                // the build tool shows no packs at all.
                StructurePacks.discoverPackAtPath(packRoot, false,
                        List.of("structurize", "minecolonies"), false, "rp_crossroads");
                keepDefaultPackOffRoadPack();
            }
            return version;
        } catch (final IOException ex) {
            Crossroads.LOGGER.error("Could not create custom road pack", ex);
            return -1L;
        }
    }

    /**
     * Keeps Structurize's "currently selected pack" pointing at a real style pack.
     *
     * <p>{@code StructurePacks.ensureSelectedPack} picks a default at <b>random</b>
     * from every non-disabled pack, and any code that resolves a blueprint by
     * relative path goes through that choice. Registering this pack therefore
     * puts it in the draw, and roughly one in N such lookups then asks
     * <em>us</em> for a style-pack blueprint — MineColonies loads
     * {@code fundamentals/townhall1.blueprint} that way when a colony is created
     * or upgraded, and it dies with {@code NoSuchFileException} inside our pack.
     * Restoring a real pack right after registering keeps the road pack out of
     * that default.</p>
     *
     * <p>Deliberately does nothing when a real pack is already selected: a player
     * who picked a colony style must keep it.</p>
     */
    private static void keepDefaultPackOffRoadPack() {
        final StructurePackMeta selected = StructurePacks.getSelectedPack();
        if (selected != null && !selected.isDisabled() && !PACK_NAME.equals(selected.getName())) {
            return;
        }
        final List<StructurePackMeta> candidates = StructurePacks.getPackMetas().stream()
                .filter(meta -> !meta.isDisabled())
                .filter(meta -> !PACK_NAME.equals(meta.getName()))
                .toList();
        if (candidates.isEmpty()) {
            return;
        }
        for (final StructurePackMeta meta : candidates) {
            // Prefer the pack the road units are authored against, so a default
            // pick still resolves the blueprints MineColonies asks for.
            if (STYLE_PACK.equals(meta.getName())) {
                StructurePacks.switchSelectedPack(meta.getName());
                return;
            }
        }
        StructurePacks.switchSelectedPack(candidates.get(0).getName());
    }

    /**
     * Stable numeric version of the pack the server ships to clients.
     *
     * <p>Only {@code initial/} is hashed — the blueprint that belongs to the mod
     * itself. {@code generated/} is deliberately excluded: it holds the
     * <em>in-progress</em> road blueprints, which are delivered point to point
     * through {@code OpenBuildToolPayload} and pruned per player. Counting them
     * here made the version move on every publish/completion, and Structurize
     * answers a version mismatch by dropping the client's copy
     * ({@code StructurePacks.removePack}), so the pack silently vanished from the
     * build tool's pack list until the whole pack was re-sent.</p>
     *
     * <p>Hashes file names and <em>contents</em>, not timestamps: {@link #install}
     * rewrites {@code initial/road.blueprint} on every call, so a timestamp-based
     * version would change on every install. {@code pack.json} is excluded so
     * writing it cannot feed back into the hash.</p>
     */
    public static long contentVersion(final Path packRoot) {
        final Path dir = packRoot.resolve("initial");
        long hash = 17L;
        if (!Files.isDirectory(dir)) {
            return 17L;
        }
        try (var paths = Files.walk(dir)) {
            for (final Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                hash = hash * 31L + dir.relativize(path).toString().hashCode();
                try {
                    hash = hash * 31L + java.util.Arrays.hashCode(Files.readAllBytes(path));
                } catch (final IOException ex) {
                    // Fall back to size so an unreadable file still shifts
                    // the version instead of silently keeping the old one.
                    hash = hash * 31L + Files.size(path);
                }
            }
        } catch (final IOException ex) {
            Crossroads.LOGGER.warn("Could not hash structure pack content", ex);
        }
        return Math.abs(hash % 1_000_000_000L);
    }

    /** {@code version} as currently written on disk, or {@link Long#MIN_VALUE} when absent/unreadable. */
    private static long storedVersion(final Path packJson) {
        try {
            if (!Files.exists(packJson)) {
                return Long.MIN_VALUE;
            }
            final var json = com.google.gson.JsonParser
                    .parseString(Files.readString(packJson, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return json.has("version") ? json.get("version").getAsLong() : Long.MIN_VALUE;
        } catch (final Exception ex) {
            return Long.MIN_VALUE;
        }
    }

    private static void deleteRecursively(final Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (final IOException ignored) {
                }
            });
        } catch (final IOException ignored) {
        }
    }

    private static void writeControllerBlueprint(final Path path, final RegistryAccess registryAccess)
            throws IOException {
        final BlockState anchor = com.YLFXM.rp.crossroads.road.common.RoadBlocks
                .blockState(Config.roadAnchorBlock());
        final Block controllerBlock = BuiltInRegistries.BLOCK.get(
                ResourceLocation.parse("minecolonies:decorationcontroller"));
        if (!(controllerBlock instanceof BlockDecorationController)) {
            Crossroads.LOGGER.error("Decoration controller block missing; custom road pack not created.");
            return;
        }
        // Controller sits on the east side and faces west toward the anchor.
        final BlockState controller = controllerBlock.defaultBlockState()
                .setValue(DirectionalBlock.FACING, Direction.WEST);

        // The initial controller blueprint is a single layer: anchor and
        // controller side by side. Its placement height is chosen by the
        // player/build tool; the road blueprint handles the +1 lamp position.
        final Blueprint blueprint = new Blueprint((short) 2, (short) 1, (short) 1, registryAccess);
        blueprint.addBlockState(new BlockPos(0, 0, 0), anchor);
        blueprint.addBlockState(new BlockPos(1, 0, 0), controller);
        blueprint.setCachePrimaryOffset(BlockPos.ZERO);
        blueprint.setPackName(PACK_NAME);
        blueprint.setFilePath(Path.of("initial"));
        blueprint.setFileName("road");

        final CompoundTag te = new CompoundTag();
        te.putString("id", "minecolonies:decorationcontroller");
        te.putInt("x", 1);
        te.putInt("y", 0);
        te.putInt("z", 0);
        final CompoundTag provider = new CompoundTag();
        provider.putString("pack", PACK_NAME);
        provider.putString("path", BLUEPRINT_PATH);
        provider.putString("schematicName", "road");
        provider.put("corner1", NbtUtils.writeBlockPos(new BlockPos(0, 0, 0)));
        provider.put("corner2", NbtUtils.writeBlockPos(new BlockPos(1, 0, 0)));
        te.put("blueprintDataProvider", provider);
        blueprint.getTileEntities()[0][0][1] = te;

        final CompoundTag tag = BlueprintUtil.writeBlueprintToNBT(blueprint);
        Files.createDirectories(path.getParent());
        NbtIo.writeCompressed(tag, path);
    }

    private CustomRoadPack() {
    }
}
