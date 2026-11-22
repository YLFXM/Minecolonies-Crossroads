/*
 * Copyright (C) 2026 YLFXM
 *
 * MineColonies: Crossroads - a feature expansion addon for MineColonies.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.YLFXM.rp.crossroads.road;

import com.YLFXM.rp.crossroads.Crossroads;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ldtteam.structurize.blueprints.v1.BlueprintUtil;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Road unit templates, keyed by series, role and level.
 *
 * <p>The pack copy under {@code units/<风格包>/<系列>/} wins, the bundled jar
 * resources are the fallback and the seed. The pack copy is what the native
 * build tool shows as a preview category, so the same file a player can look at
 * is the one the generator places.</p>
 *
 * <p>Every unit is authored along +x with the road width on z, whichever axis
 * the style pack happened to use; {@code tools/export_road_units.py} does the
 * transposition offline and records the result in the manifest.</p>
 */
@EventBusSubscriber(modid=Crossroads.MODID)
public final class RoadUnitLibrary {
    /** Series ids. The plan's {@code variant} stores the same spelling. */
    public static final String STREETS="streets";
    public static final String MAIN="main";
    public static final String ROAD="road";
    public static final String STAIR="stair";
    public static final String LAMP="lamp";

    private static final String ROOT="/assets/rp_crossroads/blueprints/units/";
    /** Manifest file name inside one style (and inside one series folder). */
    private static final String MANIFEST="units.json";
    private static final Map<HolderLookup.Provider,Map<String,Manifest>> MANIFESTS=new IdentityHashMap<>();
    private static final Map<HolderLookup.Provider,Map<String,UnitTemplate>> CACHE=new IdentityHashMap<>();

    /**
     * The spelling a plan may hold for its series.
     *
     * <p>Roads planned before the second series existed store {@code "street"}.
     * Mapping it here, instead of in a save fixer, keeps old plans loadable
     * without rewriting their NBT.</p>
     */
    public static String normalizeSeries(final String variant) {
        if(variant==null || variant.isEmpty() || "street".equals(variant)) return STREETS;
        return variant;
    }

    /** A style folder name; empty means the shipped set. */
    public static String normalizeStyle(final String style) {
        return style==null || style.isEmpty()
                ? com.YLFXM.rp.crossroads.CustomRoadPack.UNIT_STYLE_ORIGINAL : style;
    }

    public static boolean isKnownSeries(final String variant) {
        final String series=normalizeSeries(variant);
        return STREETS.equals(series) || MAIN.equals(series);
    }

    private static boolean isKnownPack(final String pack) {
        return "minecolonies".equals(pack)
                || com.YLFXM.rp.crossroads.CustomRoadPack.STYLE_PACK.equals(pack)
                || com.YLFXM.rp.crossroads.CustomRoadPack.isOurs(pack);
    }

    /**
     * Whether this mod ships units for the series, pack and road width.
     *
     * <p>The width comes from the manifest rather than a literal: the manifest
     * already declares how wide each series is, and {@link #load} verifies the
     * blueprint against it. A constant here would be a second source of truth.</p>
     */
    public static synchronized boolean supports(final String pack,final String style,final String series,final int width) {
        if(!isKnownPack(pack) || !isKnownSeries(series)) return false;
        final Integer declared=manifestWidth(style,normalizeSeries(series));
        return declared!=null && declared==width;
    }

    /** Declared width of a series, or -1 when the manifest has none. */
    public static synchronized int seriesWidth(final String style,final String series) {
        final Integer width=manifestWidth(style,normalizeSeries(series));
        return width==null ? -1 : width;
    }

    /** Same, from the save's pack manifest first. */
    public static synchronized int seriesWidth(final MinecraftServer server,final String style,final String series) {
        final JsonObject manifest=manifestJson(server,style);
        if(manifest==null || !manifest.has("series")) return -1;
        final JsonObject node=manifest.getAsJsonObject("series").getAsJsonObject(normalizeSeries(series));
        return node==null || !node.has("width") ? -1 : node.get("width").getAsInt();
    }

    /** Walkable columns the series declares, or {@code null} when the manifest has none. */
    public static synchronized WalkRange seriesWalkRange(final String style,final String series) {
        return walkRangeOf(safeBundledManifest(style),normalizeSeries(series));
    }

    /** Same, from the save's pack manifest first — the style may not be one the jar ships. */
    public static synchronized WalkRange seriesWalkRange(final MinecraftServer server,final String style,
                                                         final String series) {
        return walkRangeOf(manifestJson(server,style),normalizeSeries(series));
    }

    private static WalkRange walkRangeOf(final JsonObject manifest,final String series) {
        if(manifest==null || !manifest.has("series")) return null;
        final JsonObject node=manifest.getAsJsonObject("series").getAsJsonObject(series);
        if(node==null || !node.has("walkRange")) return null;
        final var range=node.getAsJsonArray("walkRange");
        return WalkRange.of(range.get(0).getAsInt(),range.get(1).getAsInt());
    }

    /**
     * Highest tier the series ships, or 0 when the manifest has no road unit.
     *
     * <p>The road role decides: every tier has a road unit, while the stair role
     * is allowed to point at a road file and the lamp role can stop earlier. The
     * client reads this to label the upgrade button, so it only touches the jar
     * manifest and never loads a blueprint.</p>
     */
    public static synchronized int maxLevel(final String style,final String series) {
        return maxLevelOf(safeBundledManifest(style),normalizeSeries(series));
    }

    /**
     * Highest tier the series ships, read from the save's pack manifest first.
     *
     * <p>A style that only exists in the save's pack has no jar manifest at all,
     * so anything that answers "how many tiers" for a style the player can pick
     * has to look at the pack copy — the same file {@link #load} reads.</p>
     */
    public static synchronized int maxLevel(final MinecraftServer server,final String style,final String series) {
        return maxLevelOf(manifestJson(server,style),normalizeSeries(series));
    }

    private static int maxLevelOf(final JsonObject manifest,final String series) {
        if(manifest==null || !manifest.has("series")) return 0;
        final JsonObject node=manifest.getAsJsonObject("series").getAsJsonObject(series);
        if(node==null || !node.has("units")) return 0;
        final JsonObject roads=node.getAsJsonObject("units").getAsJsonObject(ROAD);
        if(roads==null) return 0;
        int max=0;
        for(final String key:roads.keySet()) {
            try { max=Math.max(max,Integer.parseInt(key)); }
            catch(final NumberFormatException ignored) { }
        }
        return max;
    }

    /**
     * Series ids one style holds, read from the save's pack first.
     *
     * <p>A series is a folder with its own manifest: {@code units/<style>/<series>/units.json}.
     * A third-party style pack that drops a new series in is therefore selectable
     * without a code change, which is the whole point of the scan. The jar
     * manifest is merged in as a fallback so a save whose pack has not been
     * seeded yet still offers what the jar ships; the folder names are the join
     * key, so nothing is listed twice.</p>
     */
    public static java.util.List<String> seriesIds(final MinecraftServer server,final String style) {
        final java.util.Set<String> result=new java.util.TreeSet<>();
        final Path root=styleUnitRoot(server,style);
        if(root!=null) try(final var dirs=Files.list(root)) {
            dirs.filter(Files::isDirectory)
                    .filter(dir -> Files.isRegularFile(dir.resolve(MANIFEST)))
                    .forEach(dir -> result.add(dir.getFileName().toString()));
        } catch(final java.io.IOException ex) {
            Crossroads.LOGGER.warn("Could not list road unit series under {}",root,ex);
        }
        final JsonObject bundled=safeBundledManifest(style);
        if(bundled!=null && bundled.has("series")) {
            for(final var entry:bundled.getAsJsonObject("series").entrySet()) result.add(entry.getKey());
        }
        return java.util.List.copyOf(result);
    }

    /**
     * Unit style folders that hold units in this save.
     *
     * <p>A style is a first-level folder of {@code units/}, i.e. a whole separate
     * set of units ({@code units/<style>/<series>/}). The jar list is merged in as
     * a fallback for a save that has not been seeded yet, and both sides are
     * sorted by folder name so the panel order is stable. Scanning here rather
     * than hard-coding is what lets a third-party style folder show up at all.</p>
     */
    public static java.util.List<String> styles(final MinecraftServer server) {
        final java.util.Set<String> result=new java.util.TreeSet<>(com.YLFXM.rp.crossroads.CustomRoadPack.availableStyles());
        final Path root=packUnits(server);
        if(root!=null) try(final var dirs=Files.list(root)) {
            dirs.filter(Files::isDirectory).forEach(dir -> result.add(dir.getFileName().toString()));
        } catch(final java.io.IOException ex) {
            Crossroads.LOGGER.warn("Could not list road unit styles under {}",root,ex);
        }
        return java.util.List.copyOf(result);
    }

    /** {@code <pack>/units/}, or {@code null} when this save has no pack copy yet. */
    private static Path packUnits(final MinecraftServer server) {
        if(server==null) return null;
        try {
            final Path dir=com.YLFXM.rp.crossroads.CustomRoadPack.unitRoot(server);
            return Files.isDirectory(dir) ? dir : null;
        } catch(final RuntimeException ex) {
            return null;
        }
    }

    /** One style folder of the save's pack, or {@code null} when it does not exist. */
    private static Path styleUnitRoot(final MinecraftServer server,final String style) {
        final Path root=packUnits(server);
        if(root==null) return null;
        final Path dir=root.resolve(normalizeStyle(style));
        return Files.isDirectory(dir) ? dir : null;
    }

    private static final Map<String,Map<String,Integer>> bundledWidthCache=new HashMap<>();

    private static Integer manifestWidth(final String style,final String series) {
        final Map<String,Integer> widths=bundledWidthCache.computeIfAbsent(style,key -> {
            final Map<String,Integer> parsed=new HashMap<>();
            final JsonObject manifest=safeBundledManifest(key);
            if(manifest==null || !manifest.has("series")) return Map.copyOf(parsed);
            for(final var entry:manifest.getAsJsonObject("series").entrySet()) {
                final JsonObject node=entry.getValue().getAsJsonObject();
                parsed.put(entry.getKey(),node.get("width").getAsInt());
            }
            return Map.copyOf(parsed);
        });
        return widths.get(series);
    }

    public static synchronized UnitTemplate get(final String style,final String series,final String role,final int level,final ServerLevel world) {
        final String key=normalizeStyle(style)+"/"+normalizeSeries(series)+"/"+role+"/"+level;
        final HolderLookup.Provider registries=world.registryAccess();
        return CACHE.computeIfAbsent(registries,ignored -> new HashMap<>())
                .computeIfAbsent(key,ignored -> load(key,world,registries));
    }

    private static InputStream resource(final String name) {
        final InputStream stream=RoadUnitLibrary.class.getResourceAsStream(ROOT+name);
        if(stream==null) throw new IllegalStateException("Missing road unit resource: "+name);
        return stream;
    }

    /** Root of the pack copy of the unit tree, or {@code null} when this world has none yet. */
    private static Path packUnitRoot(final ServerLevel world) {
        return packUnits(world.getServer());
    }

    /**
     * Parsed jar manifests, one per style.
     *
     * <p>Jar resources cannot change while the game runs, and the controller
     * panel asks for the highest tier on every frame; parsing the JSON there
     * would be a per-frame allocation for a constant answer.</p>
     */
    private static final Map<String,JsonObject> BUNDLED=new java.util.concurrent.ConcurrentHashMap<>();

    /** Bundled manifest of one style; the pack copy is authoritative for file names but not for reading units. */
    private static JsonObject bundledManifest(final String style) {
        final String key=normalizeStyle(style);
        final JsonObject cached=BUNDLED.get(key);
        if(cached!=null) return cached;
        try(final var input=new InputStreamReader(resource(key+"/units.json"),StandardCharsets.UTF_8)) {
            final JsonObject parsed=JsonParser.parseReader(input).getAsJsonObject();
            BUNDLED.put(key,parsed);
            return parsed;
        } catch(final Exception ex) {
            throw new IllegalStateException("Could not read the bundled road unit manifest",ex);
        }
    }

    /**
     * Same manifest, or {@code null} when this build ships no units for the style.
     *
     * <p>Style folders come from the save's pack, so a third-party style has no
     * jar manifest at all. Listing its series must not fail the whole scan just
     * because the fallback does not exist.</p>
     */
    private static JsonObject safeBundledManifest(final String style) {
        final String key=normalizeStyle(style);
        if(BUNDLED.containsKey(key)) return BUNDLED.get(key);
        if(RoadUnitLibrary.class.getResourceAsStream(ROOT+key+"/"+MANIFEST)==null) return null;
        try {
            return bundledManifest(key);
        } catch(final RuntimeException ex) {
            return null;
        }
    }

    /**
     * Parsed manifest for one style, the save's pack copy first.
     *
     * <p>Everything that answers a question about a style the player can pick has
     * to read this and not the jar: a style folder dropped into the pack has no
     * jar manifest, and answering "0 tiers / no width" for it would list a
     * choice that cannot be applied.</p>
     */
    private static JsonObject manifestJson(final MinecraftServer server,final String style) {
        final Path dir=styleUnitRoot(server,normalizeStyle(style));
        if(dir!=null) {
            final Path file=dir.resolve(MANIFEST);
            final JsonObject parsed=readJson(file);
            if(parsed!=null && parsed.has("schemaVersion") && parsed.get("schemaVersion").getAsInt()==2) return parsed;
        }
        return safeBundledManifest(style);
    }

    /** One style's self-description, as declared by its own {@code style.json}. */
    public record StyleMeta(String name,String desc,List<String> authors,String cover) {
        /** Nothing declared: the window falls back to a language key, then the folder name. */
        public static final StyleMeta EMPTY=new StyleMeta("","",List.of(),"");
    }

    /** File a style may use to describe itself; every field inside is optional. */
    private static final String STYLE_JSON="style.json";

    /**
     * Display data of one style: the pack copy of {@code style.json} wins, the
     * jar copy is the seed. Missing or broken means "nothing declared" — a style
     * folder alone is enough to be listed.
     */
    public static StyleMeta styleMeta(final MinecraftServer server,final String style) {
        final String id=normalizeStyle(style);
        final Path dir=styleUnitRoot(server,id);
        JsonObject json=dir==null ? null : readJson(dir.resolve(STYLE_JSON));
        if(json==null) json=readJson(ROOT+id+"/"+STYLE_JSON);
        if(json==null) return StyleMeta.EMPTY;
        try {
            final String name=json.has("name") ? json.get("name").getAsString() : "";
            final String desc=json.has("desc") ? json.get("desc").getAsString() : "";
            final List<String> authors=new java.util.ArrayList<>();
            if(json.has("authors")) for(final var author:json.getAsJsonArray("authors")) authors.add(author.getAsString());
            final String cover=json.has("cover") ? coverName(json.get("cover").getAsString()) : "";
            return new StyleMeta(name,desc,List.copyOf(authors),cover);
        } catch(final RuntimeException ex) {
            Crossroads.LOGGER.warn("Ignoring unreadable style description for {}",id,ex);
            return StyleMeta.EMPTY;
        }
    }

    /**
     * Cover file name, reduced to a leaf this mod will look for.
     *
     * <p>{@code style.json} comes from a pack, i.e. from whoever wrote that pack,
     * and the value ends up in a filesystem path. Anything that is not a plain
     * {@code *.png} leaf is dropped and the window uses its fallback icon.</p>
     */
    private static String coverName(final String declared) {
        final String leaf=declared.replace('\\','/');
        final String name=leaf.substring(leaf.lastIndexOf('/')+1);
        return name.endsWith(".png") && name.indexOf("..")<0 && name.length()>4 ? name : "";
    }

    /** Parsed JSON from a jar resource under the unit root, or {@code null}. */
    private static JsonObject readJson(final String resource) {
        try(var in=RoadUnitLibrary.class.getResourceAsStream(resource)) {
            if(in==null) return null;
            try(final var reader=new InputStreamReader(in,StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch(final Exception ex) {
            return null;
        }
    }

    /** Parsed JSON file, or {@code null} when it is absent or broken. */
    private static JsonObject readJson(final Path file) {
        if(file==null || !Files.isRegularFile(file)) return null;
        try(final var reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch(final Exception ex) {
            Crossroads.LOGGER.warn("Ignoring unreadable JSON at {}",file,ex);
            return null;
        }
    }

    private static UnitTemplate load(final String key,final ServerLevel world,final HolderLookup.Provider registries) {
        final String[] parts=key.split("/");
        if(parts.length!=4) throw new IllegalStateException("Invalid road unit key: "+key);
        final String style=parts[0];
        final String series=parts[1];
        final String role=parts[2];
        final int level;
        try {
            level=Integer.parseInt(parts[3]);
        } catch(final NumberFormatException ex) {
            throw new IllegalStateException("Invalid road unit level: "+key,ex);
        }
        final Path root=packUnitRoot(world);
        final Path styleRoot=root==null ? null : root.resolve(style);
        final Manifest manifest=MANIFESTS.computeIfAbsent(registries,ignored -> new HashMap<>())
                .computeIfAbsent(style,ignored -> Manifest.read(styleRoot,style));
        final Manifest.Spec spec=manifest.specs().get(series+"/"+role+"/"+level);
        if(spec==null) throw new IllegalStateException("Unknown road unit: "+key);
        final Path dir=styleRoot==null ? null : styleRoot.resolve(series);
        try {
            final InputStream chosen=dir!=null && Files.isRegularFile(dir.resolve(spec.file()))
                    ? Files.newInputStream(dir.resolve(spec.file())) : resource(style+"/"+series+"/"+spec.file());
            try(final var stream=chosen) {
                final var data=NbtIo.readCompressed(stream,NbtAccounter.unlimitedHeap());
                final var required=data.getList("required_mods",8);
                for(int i=0;i<required.size();i++) if(!"minecraft".equals(required.getString(i))
                        && !net.neoforged.fml.ModList.get().isLoaded(required.getString(i)))
                    throw new IllegalStateException("Missing unit dependency: "+required.getString(i));
                final var blueprint=BlueprintUtil.readBlueprintFromNBT(data,registries);
                if(blueprint==null || blueprint.getSizeX()!=1 || blueprint.getSizeZ()!=spec.width())
                    throw new IllegalStateException("Invalid road unit dimensions: "+key);
                final int surface=spec.surfaceY();
                if(surface<0 || surface>=blueprint.getSizeY()) throw new IllegalStateException("Invalid surface height");
                final var unit=new UnitTemplate(1,blueprint.getSizeY(),blueprint.getSizeZ(),surface,
                        spec.anchorColumn(),Direction.Axis.X);
                final var occupied=new HashSet<BlockPos>();
                for(final var cell:spec.writeCells()) {
                    final var pos=coordinate(cell,blueprint.getSizeY(),blueprint.getSizeZ());
                    if(!occupied.add(pos)) throw new IllegalStateException("Duplicate unit write cell");
                    unit.add(pos,blueprint.getBlockState(pos),blueprint.getTileEntities()[pos.getY()][pos.getZ()][pos.getX()]);
                }
                for(final var cell:spec.skipCells()) {
                    final var pos=coordinate(cell,blueprint.getSizeY(),blueprint.getSizeZ());
                    if(!occupied.add(pos)) throw new IllegalStateException("Conflicting unit write/skip cell");
                    unit.addPlaceholder(pos);
                }
                return unit.freeze();
            }
        } catch(final Exception ex) {
            throw new IllegalStateException("Could not load road unit "+key,ex);
        }
    }

    private static BlockPos coordinate(final int[] cell,final int height,final int width) {
        if(cell.length!=3) throw new IllegalStateException("Invalid unit coordinate");
        final var pos=new BlockPos(cell[0],cell[1],cell[2]);
        if(pos.getX()!=0 || pos.getY()<0 || pos.getY()>=height || pos.getZ()<0 || pos.getZ()>=width)
            throw new IllegalStateException("Unit cell out of bounds: "+pos);
        return pos;
    }

    /**
     * The manifest, parsed once per server: series width plus one spec per unit.
     *
     * <p>Read from the pack copy so a player who edits a preview file gets the
     * edited dimensions honoured, falling back to the jar when the world has no
     * copy yet.</p>
     */
    private record Manifest(Map<String,Spec> specs, Map<String,Integer> widths) {
        record Spec(String file,int width,int surfaceY,int anchorColumn,List<int[]> writeCells,List<int[]> skipCells) { }

        static Manifest read(final Path root,final String style) {
            JsonObject json=null;
            if(root!=null && Files.isRegularFile(root.resolve("units.json"))) {
                try(final var reader=Files.newBufferedReader(root.resolve("units.json"),StandardCharsets.UTF_8)) {
                    final JsonObject parsed=JsonParser.parseReader(reader).getAsJsonObject();
                    if(parsed.has("schemaVersion") && parsed.get("schemaVersion").getAsInt()==2) json=parsed;
                } catch(final Exception ex) {
                    Crossroads.LOGGER.warn("Ignoring unreadable road unit manifest in the save, using the bundled one",ex);
                }
            }
            if(json==null) json=bundledManifest(style);
            if(json.get("schemaVersion").getAsInt()!=2)
                throw new IllegalStateException("Unsupported road unit schema");
            final Map<String,Spec> specs=new HashMap<>();
            final Map<String,Integer> widths=new HashMap<>();
            for(final var seriesEntry:json.getAsJsonObject("series").entrySet()) {
                final String series=seriesEntry.getKey();
                final JsonObject node=seriesEntry.getValue().getAsJsonObject();
                final int width=node.get("width").getAsInt();
                widths.put(series,width);
                for(final var roleEntry:node.getAsJsonObject("units").entrySet()) {
                    final String role=roleEntry.getKey();
                    for(final var levelEntry:roleEntry.getValue().getAsJsonObject().entrySet()) {
                        final JsonObject unit=levelEntry.getValue().getAsJsonObject();
                        final String file=unit.get("file").getAsString();
                        // The manifest may name the series folder; the loader
                        // already resolves against it, so keep only the leaf.
                        final String leaf=file.contains("/") ? file.substring(file.lastIndexOf('/')+1) : file;
                        if(!leaf.endsWith(".blueprint") || leaf.contains("\\"))
                            throw new IllegalStateException("Invalid road unit file name: "+file);
                        specs.put(series+"/"+role+"/"+levelEntry.getKey(),new Spec(
                                leaf,
                                unit.get("width").getAsInt(),
                                unit.get("surfaceY").getAsInt(),
                                unit.get("anchorColumn").getAsInt(),
                                cells(unit,"writeCells"),
                                cells(unit,"skipCells")));
                    }
                }
            }
            return new Manifest(Map.copyOf(specs),Map.copyOf(widths));
        }

        private static List<int[]> cells(final JsonObject unit,final String name) {
            final var array=unit.getAsJsonArray(name);
            if(array==null) return List.of();
            final List<int[]> result=new java.util.ArrayList<>(array.size());
            for(final var element:array) {
                final var cell=element.getAsJsonArray();
                result.add(new int[]{cell.get(0).getAsInt(),cell.get(1).getAsInt(),cell.get(2).getAsInt()});
            }
            return List.copyOf(result);
        }
    }

    @SubscribeEvent public static synchronized void stopped(final ServerStoppedEvent event) {
        CACHE.clear();
        MANIFESTS.clear();
    }

    private RoadUnitLibrary() { }
}
