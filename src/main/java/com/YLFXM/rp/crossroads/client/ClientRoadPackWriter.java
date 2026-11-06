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

import com.YLFXM.rp.crossroads.CustomRoadPack;
import com.YLFXM.rp.crossroads.Crossroads;
import com.ldtteam.structurize.storage.StructurePacks;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

/**
 * Client side of the generated road blueprint.
 *
 * <p>Structurize only syncs structure packs when a client joins, so a blueprint
 * generated afterwards never reaches the client and the native build tool
 * cannot load it. The blueprint is therefore pushed with
 * {@code OpenBuildToolPayload} and written into the client's local copy of the
 * pack here, then the pack is re-registered so the build tool sees the new
 * file immediately.</p>
 */
public final class ClientRoadPackWriter {
    /**
     * Writes the pushed blueprint into {@code blueprints/<pack>/<path>} and
     * re-registers the pack.
     *
     * @return true when the file is in place and the pack was registered
     */
    public static boolean ensure(final String blueprintPath, final CompoundTag blueprint,
                                 final double packVersion) {
        if (blueprint == null || blueprintPath == null || blueprintPath.isBlank()) {
            return false;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        final Path root = minecraft.gameDirectory.toPath()
                .resolve("blueprints").resolve(CustomRoadPack.PACK_NAME);
        try {
            // Keep pack.json in step with the server: a matching version keeps
            // the next join from discarding this local copy.
            Files.createDirectories(root);
            if (packVersion > 0) {
                Files.writeString(root.resolve("pack.json"), packJson((long) packVersion), StandardCharsets.UTF_8);
            }

            final Path target = root.resolve(blueprintPath);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            try (var out = Files.newOutputStream(target)) {
                NbtIo.writeCompressed(blueprint, out);
            }

            final List<String> mods = ModList.get().getMods().stream()
                    .map(IModInfo::getModId).toList();
            StructurePacks.discoverPackAtPath(root, false, mods, false, "rp_crossroads");
            return true;
        } catch (final IOException ex) {
            Crossroads.LOGGER.error("Could not write road blueprint {} to the client pack",
                    blueprintPath, ex);
            return false;
        }
    }

    private static String packJson(final long version) {
        return """
                {
                  "icon": "",
                  "name": "%s",
                  "authors": ["YLFXM"],
                  "desc": "Crossroads: initial anchor and generated road blueprints.",
                  "mods": ["structurize", "minecolonies"],
                  "version": %d,
                  "pack-format": 1
                }
                """.formatted(CustomRoadPack.PACK_NAME, version);
    }

    /**
     * Deletes the local copy of the pre-rename pack.
     *
     * <p>That directory is only ever a cache of blueprints the server pushed, but
     * Structurize scans {@code blueprints/} at startup and would keep listing a
     * pack named after the old product. Removing the files and the registered
     * meta keeps the pack list to the current name. Nothing else refers to that
     * old name: it is deleted, never accepted as an identity.</p>
     */
    public static void purgeStalePack() {
        final Path stale = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("blueprints").resolve(CustomRoadPack.STALE_PACK_NAME);
        if (Files.exists(stale)) {
            try (var paths = Files.walk(stale)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (final IOException ignored) {
                    }
                });
                Crossroads.LOGGER.info("Removed the stale road pack copy at {}", stale);
            } catch (final IOException ex) {
                Crossroads.LOGGER.warn("Could not remove the stale road pack copy", ex);
            }
        }
        if (StructurePacks.hasPack(CustomRoadPack.STALE_PACK_NAME)) {
            StructurePacks.removePack(CustomRoadPack.STALE_PACK_NAME);
        }
    }

    /**
     * Removes blueprint files this client no longer has any right to see.
     *
     * <p>Blueprints are pushed per player, so whatever was written during a
     * previous session (or against a different save) stays on disk and would show
     * up in the build tool for a road the player is not involved with. Called
     * with the set of names the server currently vouches for.</p>
     *
     * <p>Only the {@code generated/} subtree is touched — {@code initial/} comes
     * from the mod itself and must stay.</p>
     */
    public static void retainOnly(final java.util.Set<String> keepFileNames) {
        final Minecraft minecraft = Minecraft.getInstance();
        final Path generated = minecraft.gameDirectory.toPath()
                .resolve("blueprints").resolve(CustomRoadPack.PACK_NAME).resolve("generated");
        if (!Files.isDirectory(generated)) {
            return;
        }
        boolean removed = false;
        try (var paths = Files.list(generated)) {
            for (final Path path : paths.toList()) {
                final String name = path.getFileName().toString();
                if (!keepFileNames.contains(name.replace(".blueprint", ""))) {
                    Files.deleteIfExists(path);
                    removed = true;
                }
            }
        } catch (final IOException ex) {
            Crossroads.LOGGER.warn("Could not prune stale client road blueprints", ex);
            return;
        }
        if (removed && StructurePacks.hasPack(CustomRoadPack.PACK_NAME)) {
            // Only re-register when the pack already exists: during login the
            // client has not synced the pack yet, and registering it here would
            // race the structure-pack sync (a pack registered mid-sync makes
            // ClientStructurePackLoader's version comparison fail, which leaves
            // the build tool with no packs at all).
            final List<String> mods = ModList.get().getMods().stream()
                    .map(IModInfo::getModId).toList();
            StructurePacks.discoverPackAtPath(
                    minecraft.gameDirectory.toPath().resolve("blueprints").resolve(CustomRoadPack.PACK_NAME),
                    false, mods, false, "rp_crossroads");
        }
    }

    private ClientRoadPackWriter() {
    }
}
