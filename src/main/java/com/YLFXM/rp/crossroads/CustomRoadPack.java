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
     * Owner string this pack is registered under, on both sides.
     *
     * <p>Structurize's "switch pack" window groups the list by
     * {@code StructurePackMeta#getOwner()} into a {@code TreeMap} and shows the
     * owner as the category heading; that field is read nowhere else (verified
     * against Structurize 1.0.835). So the owner doubles as a sort weight:
     * upper-case names sort before every lower-case mod id, and
     * {@code "Crossroads"} also beats {@code "Local"} — the road pack stays in the
     * first row instead of being buried under the style packs, which is what
     * happened while it was registered as {@code "rp_crossroads"}.</p>
     */
    public static final String PACK_OWNER = PACK_NAME;
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

    /** Folder chain under the world root that holds this save's road pack. */
    private static final String PACK_DIR = "rp_crossroads";
    private static final String PACK_FOLDER = "structurepack";

    public static Path packRoot(final MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve(PACK_DIR).resolve(PACK_FOLDER);
    }

    /** Unit preview: `<pack>/units/<风格包>/<系列>/`, browsable in the native build tool. */
    private static final String UNIT_ROOT = "units";
    /**
     * Folder names are ASCII even though the build tool shows them verbatim: a
     * localized path breaks on zip encoding and cannot be translated back. Display
     * names come from the window's own lang keys; the folder only needs to be
     * stable.
     */
    private static final String UNIT_STYLE = "original";
    private static final String UNIT_SERIES = "streets";
    /** Localized names an earlier build shipped; the tree is moved on sight. */
    private static final String LEGACY_UNIT_STYLE = "原版";
    private static final String LEGACY_UNIT_SERIES = "街道";
    /** Classpath root of the bundled unit blueprints and manifest. */
    private static final String UNIT_RESOURCE_ROOT = "/assets/rp_crossroads/blueprints/units/original/";
    /** Role -> bundled resource name; the pack copy carries the level suffix instead. */
    private static final String[][] UNIT_FILES = {
            {"road", "street_road.blueprint"},
            {"stair", "street_stair.blueprint"},
            {"lamp", "street_lamp.blueprint"},
    };
    /** Level shipped as unit level 1; higher levels arrive with the multi-series task. */
    private static final int UNIT_LEVEL = 1;

    /** Where this save's unit previews live. */
    public static Path unitDir(final MinecraftServer server) {
        return packRoot(server).resolve(UNIT_ROOT).resolve(UNIT_STYLE).resolve(UNIT_SERIES);
    }

    /**
     * Preview file name of one unit: {@code <role>_<level>.blueprint}.
     *
     * <p>The separator matters. The native build tool takes the file name as the
     * entry label and as the grouping key, so {@code road_1}/{@code road_2} would
     * show up twice and never group. {@code RoadUnitNames} strips the trailing
     * {@code _<digits>} before the name reaches the list, which restores the
     * vanilla behaviour: one entry, one {@code Level:} row.</p>
     */
    public static String unitFileName(final String role, final int level) {
        return role + "_" + level + ".blueprint";
    }

    /**
     * True when {@code path} is this save's road pack directory.
     *
     * <p>Used by the pack-privacy filter, which only exists in the path argument
     * of structurize's zip walk. Comparing the whole path would need a server
     * instance; the two folder names are unique to us inside a save.</p>
     */
    public static boolean isPackRoot(final Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        final Path parent = path.getParent();
        return parent != null && parent.getFileName() != null
                && PACK_DIR.equals(parent.getFileName().toString())
                && PACK_FOLDER.equals(path.getFileName().toString());
    }

    /**
     * Result of {@link #ensureUnits}: whether the preview assets are usable.
     */
    public enum UnitCheck {
        /** Everything readable; nothing to do. */
        OK,
        /** Something was missing or damaged and has been restored from the jar. */
        REPAIRED,
        /** Still unreadable after a restore attempt — the jar seed itself is unusable. */
        FAILED
    }

    /**
     * Checks the unit previews and restores whatever is missing or damaged.
     *
     * <p>Runs before every native build tool open (initial build and repair, which
     * share {@code RoadNativeBuildTool.open}) and at server start. Deleting a file
     * or being killed mid-write therefore heals on the next open instead of waiting
     * for a restart — and a repaired pack bumps its version, which is what makes
     * clients drop their own broken copy instead of keeping it forever.</p>
     *
     * @return {@link UnitCheck}
     */
    public static UnitCheck ensureUnits(final MinecraftServer server) {
        final Path packRoot = packRoot(server);
        if (!Files.isDirectory(packRoot)) {
            // World not initialised yet; install() will seed it at startup.
            return UnitCheck.OK;
        }
        final boolean repaired = seedUnits(packRoot);
        final Path dir = unitDir(server);
        for (final String[] entry : UNIT_FILES) {
            if (!isReadableBlueprint(dir.resolve(unitFileName(entry[0], UNIT_LEVEL)))) {
                return UnitCheck.FAILED;
            }
        }
        final Path manifest = dir.resolve("units.json");
        try {
            if (!Files.isRegularFile(manifest) || Files.size(manifest) <= 0) {
                return UnitCheck.FAILED;
            }
        } catch (final IOException ex) {
            return UnitCheck.FAILED;
        }
        return repaired ? UnitCheck.REPAIRED : UnitCheck.OK;
    }

    /**
     * Moves the unit previews out of the old localized folders.
     *
     * <p>An earlier build shipped {@code units/原版/街道/}. Those names cannot be
     * translated by the tool (it prints them verbatim) and travel badly through zip,
     * so the tree now uses ASCII and the display names come from the window. Files
     * are moved rather than rewritten so a player-adjusted preview survives.</p>
     *
     * @return true when something was moved
     */
    private static boolean migrateLegacyUnitTree(final Path packRoot) {
        final Path legacyRoot = packRoot.resolve(UNIT_ROOT).resolve(LEGACY_UNIT_STYLE);
        final Path legacy = legacyRoot.resolve(LEGACY_UNIT_SERIES);
        if (!Files.isDirectory(legacy)) {
            return false;
        }
        final Path target = packRoot.resolve(UNIT_ROOT).resolve(UNIT_STYLE).resolve(UNIT_SERIES);
        try {
            Files.createDirectories(target);
            try (var files = Files.list(legacy)) {
                for (final Path file : files.toList()) {
                    final Path dest = target.resolve(file.getFileName().toString());
                    if (!Files.exists(dest)) {
                        Files.move(file, dest);
                    }
                }
            }
            deleteRecursively(legacyRoot);
            Crossroads.LOGGER.info("Moved road unit previews to {}/{}", UNIT_STYLE, UNIT_SERIES);
            return true;
        } catch (final IOException ex) {
            Crossroads.LOGGER.warn("Could not move the localized road unit previews", ex);
            return false;
        }
    }

    /**
     * Seeds {@code units/<风格包>/<系列>/} from the jar when the pack copy is missing.
     *
     * <p>Files are written as {@code <role>_1.blueprint} (see {@link #unitFileName}).
     * The older {@code <role>1.blueprint} spelling is renamed in place rather than
     * rewritten, so a player's edited preview survives the change. The manifest is
     * patched field by field — only the {@code file} entry is touched, everything
     * else in it stays as the player left it.</p>
     *
     * @return true when at least one file was created, renamed or rewritten
     */
    public static boolean seedUnits(final Path packRoot) {
        final Path dir = packRoot.resolve(UNIT_ROOT).resolve(UNIT_STYLE).resolve(UNIT_SERIES);
        boolean changed = migrateLegacyUnitTree(packRoot);
        try {
            Files.createDirectories(dir);
            for (final String[] entry : UNIT_FILES) {
                final String role = entry[0];
                final Path target = dir.resolve(unitFileName(role, UNIT_LEVEL));
                final Path legacy = dir.resolve(role + UNIT_LEVEL + ".blueprint");
                if (!Files.isRegularFile(target) && Files.isRegularFile(legacy)) {
                    Files.move(legacy, target);
                    changed = true;
                    Crossroads.LOGGER.info("Renamed road unit preview {} to level suffix naming", role);
                }
                if (isReadableBlueprint(target)) {
                    continue;
                }
                final byte[] data = bundled(entry[1]);
                if (data == null) {
                    Crossroads.LOGGER.warn("Missing bundled road unit {}", entry[1]);
                    continue;
                }
                Files.write(target, data);
                changed = true;
            }
            changed |= normalizeUnitManifest(dir.resolve("units.json"));
            if (changed) {
                Crossroads.LOGGER.info("Seeded road unit previews into {}", dir);
            }
        } catch (final IOException ex) {
            Crossroads.LOGGER.warn("Could not seed road unit previews", ex);
        }
        return changed;
    }

    /** Bundled copy of a resource under the unit blueprint root, or {@code null}. */
    private static byte[] bundled(final String name) {
        try (var in = CustomRoadPack.class.getResourceAsStream(UNIT_RESOURCE_ROOT + name)) {
            return in == null ? null : in.readAllBytes();
        } catch (final IOException ex) {
            return null;
        }
    }

    /** True when the file exists, is non-empty and parses as a compressed blueprint. */
    private static boolean isReadableBlueprint(final Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) <= 0) {
                return false;
            }
            NbtIo.readCompressed(file, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            return true;
        } catch (final IOException | RuntimeException ex) {
            return false;
        }
    }

    /**
     * Points the manifest at the current preview file names.
     *
     * <p>Only the {@code file} field of each unit is rewritten: the rest of the
     * manifest (widths, write cells, source hashes) is authored data and may have
     * been adjusted by hand. A missing manifest is created from the bundled copy.</p>
     *
     * @return true when the manifest was written
     */
    private static boolean normalizeUnitManifest(final Path manifest) throws IOException {
        final com.google.gson.JsonObject json;
        if (Files.isRegularFile(manifest) && Files.size(manifest) > 0) {
            try (var reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
                json = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
            } catch (final RuntimeException ex) {
                // Corrupt manifest: fall through to the bundled one.
                return writeBundledManifest(manifest);
            }
        } else {
            return writeBundledManifest(manifest);
        }
        boolean changed = false;
        final var units = json.getAsJsonObject("units");
        if (units == null) {
            return writeBundledManifest(manifest);
        }
        for (final String[] entry : UNIT_FILES) {
            final var unit = units.getAsJsonObject(entry[0]);
            if (unit == null) {
                return writeBundledManifest(manifest);
            }
            final String expected = unitFileName(entry[0], UNIT_LEVEL);
            final var file = unit.get("file");
            if (file == null || !expected.equals(file.getAsString())) {
                unit.addProperty("file", expected);
                changed = true;
            }
        }
        if (changed) {
            try (var writer = Files.newBufferedWriter(manifest, StandardCharsets.UTF_8)) {
                new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(json, writer);
            }
        }
        return changed;
    }

    /** Writes the bundled manifest, with the preview file names applied. */
    private static boolean writeBundledManifest(final Path manifest) throws IOException {
        final byte[] raw = bundled("units.json");
        if (raw == null) {
            Crossroads.LOGGER.warn("Missing bundled road unit manifest");
            return false;
        }
        final var json = com.google.gson.JsonParser
                .parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
        final var units = json.getAsJsonObject("units");
        for (final String[] entry : UNIT_FILES) {
            units.getAsJsonObject(entry[0]).addProperty("file", unitFileName(entry[0], UNIT_LEVEL));
        }
        try (var writer = Files.newBufferedWriter(manifest, StandardCharsets.UTF_8)) {
            new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(json, writer);
        }
        return true;
    }

    /**
     * Rebuilds and re-registers the per-save pack.
     *
     * @return the content version written into pack.json, or {@code -1} on error
     */
    public static long install(final MinecraftServer server) {
        return install(server, false);
    }

    /**
     * Rebuilds and re-registers the pack, optionally bumping its revision.
     *
     * <p>{@code revisionBump} exists for exactly one case: preview assets were
     * repaired. The content hash is unchanged then (the repaired file equals what
     * the pack already declared), so without a bump every client that holds a
     * damaged copy would compare versions, see a match and keep it. Bumping makes
     * the version differ, which is structurize's own trigger for re-sending.</p>
     */
    public static long install(final MinecraftServer server, final boolean revisionBump) {
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
            seedUnits(packRoot);

            // The pack version follows only the shipped blueprint (see
            // contentVersion): generated/ is per-road and must not move it, or
            // every road completion would invalidate every client's copy.
            final Path packJson = packRoot.resolve("pack.json");
            final int revision = storedRevision(packJson) + (revisionBump ? 1 : 0);
            final long version = packVersion(contentVersion(packRoot), revision);
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
                          "revision": %d,
                          "pack-format": 1
                        }
                        """.formatted(PACK_NAME, version, revision), StandardCharsets.UTF_8);
            }

            // Registration is only needed when the meta is missing or its version
            // changed; re-registering on every publish replaced the meta object and
            // spammed "Registered structure pack" once per blueprint write.
            // Single-player: re-register when the registered entry points somewhere
            // else. Structurize's meta map is keyed by pack name, and the client also
            // scans blueprints/ at startup, so a "Crossroads" copy under the game
            // directory would replace this entry and the build tool would browse a
            // folder without unit previews.
            final boolean pointsElsewhere = server.isSingleplayer()
                    && StructurePacks.getPackMetas().stream()
                            .anyMatch(meta -> PACK_NAME.equals(meta.getName())
                                    && !packRoot.equals(meta.getPath()));
            if (!unchanged || !StructurePacks.hasPack(PACK_NAME) || pointsElsewhere) {
                // immutable=false is mandatory: ServerStructurePackLoader only ships
                // packs to clients when !pack.isImmutable(). Registering this pack as
                // immutable leaves every client waiting for a pack the server will
                // never send, so the client never finishes StructurePacks loading and
                // the build tool shows no packs at all.
                StructurePacks.discoverPackAtPath(packRoot, false,
                        List.of("structurize", "minecolonies"), false, PACK_OWNER);
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
    /**
     * Content version of the pack as clients see it: {@code initial/} plus the unit
     * previews under {@code units/}.
     *
     * <p>{@code generated/} stays out on purpose — it is per-road and changes on every
     * build, so hashing it would make every client drop and re-download the pack. The
     * units are the opposite: static per mod version, and a change must reach clients,
     * otherwise the build tool keeps showing the old previews.</p>
     */
    public static long contentVersion(final Path packRoot) {
        long hash = 17L;
        hash = hashTree(packRoot.resolve("initial"), hash);
        hash = hashTree(packRoot.resolve(UNIT_ROOT), hash);
        return Math.abs(hash % 1_000_000_000L);
    }

    /** Folds every regular file under {@code dir} into {@code hash}, path and bytes. */
    private static long hashTree(final Path dir, final long seed) {
        if (!Files.isDirectory(dir)) {
            return seed;
        }
        long hash = seed;
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
        return hash;
    }

    /**
     * Folds the revision into the content hash.
     *
     * <p>The revision only moves when assets had to be repaired, so a normal
     * install stays stable across restarts — but a repair changes the number every
     * client compares against, and structurize re-sends the pack on a mismatch.</p>
     */
    private static long packVersion(final long contentHash, final int revision) {
        return Math.abs((contentHash * 128L + revision) % 1_000_000_000L);
    }

    /** {@code revision} on disk, or {@code 0} when the file predates the field. */
    private static int storedRevision(final Path packJson) {
        try {
            if (!Files.exists(packJson)) {
                return 0;
            }
            final var json = com.google.gson.JsonParser
                    .parseString(Files.readString(packJson, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return json.has("revision") ? json.get("revision").getAsInt() : 0;
        } catch (final Exception ex) {
            return 0;
        }
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
