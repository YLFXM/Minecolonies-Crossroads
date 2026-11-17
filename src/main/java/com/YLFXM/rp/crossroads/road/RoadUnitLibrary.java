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

import com.YLFXM.rp.crossroads.Crossroads;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ldtteam.structurize.blueprints.v1.BlueprintUtil;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Road unit templates: the pack copy under {@code units/<风格包>/<系列>/} wins, the
 * bundled jar resources are the fallback and the seed.
 *
 * <p>The pack copy is what the native build tool shows as a preview category, so the
 * same file a player can look at is the one the generator places.</p>
 */
@EventBusSubscriber(modid=Crossroads.MODID)
public final class RoadUnitLibrary {
    private static final String ROOT="/assets/rp_crossroads/blueprints/units/original/";
    private static final Map<HolderLookup.Provider,Map<String,UnitTemplate>> CACHE=new IdentityHashMap<>();

    private static Integer declaredWidth;

    /** Width the bundled manifest declares for the road unit; the only declaration of road width. */
    private static synchronized int declaredWidth() {
        if(declaredWidth==null) {
            try(final var input=new InputStreamReader(resource("units.json"),StandardCharsets.UTF_8)) {
                declaredWidth=JsonParser.parseReader(input).getAsJsonObject()
                        .getAsJsonObject("units").getAsJsonObject("road").get("width").getAsInt();
            } catch(final Exception ex) {
                throw new IllegalStateException("Could not read the bundled road unit manifest",ex);
            }
        }
        return declaredWidth;
    }

    private static boolean isKnownPack(final String pack) {
        return "minecolonies".equals(pack)
                || com.YLFXM.rp.crossroads.CustomRoadPack.STYLE_PACK.equals(pack)
                || com.YLFXM.rp.crossroads.CustomRoadPack.isOurs(pack);
    }

    /**
     * Whether this mod ships a unit for the given pack alias, variant and road width.
     *
     * <p>The width comes from the bundled manifest, not a literal: units.json already
     * declares how wide the road unit is and {@link #load} verifies the blueprint
     * against it, so a constant here would be a second source of truth that can
     * silently disagree with the file.</p>
     */
    public static boolean supports(final String pack,final String variant,final int width) {
        return "street".equals(variant) && isKnownPack(pack) && width==declaredWidth();
    }

    public static synchronized UnitTemplate get(final String kind,final ServerLevel level) {
        final HolderLookup.Provider registries=level.registryAccess();
        return CACHE.computeIfAbsent(registries,ignored -> new HashMap<>())
                .computeIfAbsent(kind,ignored -> load(kind,level,registries));
    }
    private static InputStream resource(final String name) {
        final InputStream stream=RoadUnitLibrary.class.getResourceAsStream(ROOT+name);
        if(stream==null) throw new IllegalStateException("Missing road unit resource: "+name);
        return stream;
    }
    /** Pack copy of the unit directory, or {@code null} when this world has none yet. */
    private static Path packUnitDir(final ServerLevel level) {
        try {
            final Path dir=com.YLFXM.rp.crossroads.CustomRoadPack.unitDir(level.getServer());
            return Files.isDirectory(dir) ? dir : null;
        } catch(final RuntimeException ex) {
            return null;
        }
    }

    private static UnitTemplate load(final String kind,final ServerLevel level,final HolderLookup.Provider registries) {
        final Path dir=packUnitDir(level);
        try(final var input=new InputStreamReader(dir==null ? resource("units.json")
                : Files.newInputStream(dir.resolve("units.json")),StandardCharsets.UTF_8)) {
            final JsonObject manifest=JsonParser.parseReader(input).getAsJsonObject();
            if(manifest.get("schemaVersion").getAsInt()!=1) throw new IllegalStateException("Unsupported road unit schema");
            final var spec=manifest.getAsJsonObject("units").getAsJsonObject(kind);
            if(spec==null || !"x".equals(spec.get("axis").getAsString())) throw new IllegalStateException("Unsupported road unit: "+kind);
            final String file=spec.get("file").getAsString();
            if(!file.endsWith(".blueprint") || file.contains("/") || file.contains("\\"))
                throw new IllegalStateException("Invalid road unit file name: "+file);
            final InputStream chosen=dir!=null && Files.isRegularFile(dir.resolve(file))
                    ? Files.newInputStream(dir.resolve(file)) : resource(file);
            try(final var stream=chosen) {
                final var data=NbtIo.readCompressed(stream,NbtAccounter.unlimitedHeap());
                final var required=data.getList("required_mods",8);
                for(int i=0;i<required.size();i++) if(!"minecraft".equals(required.getString(i))
                        && !net.neoforged.fml.ModList.get().isLoaded(required.getString(i)))
                    throw new IllegalStateException("Missing unit dependency: "+required.getString(i));
                final var blueprint=BlueprintUtil.readBlueprintFromNBT(data,registries);
                if(blueprint==null || blueprint.getSizeX()!=1 || blueprint.getSizeZ()!=spec.get("width").getAsInt())
                    throw new IllegalStateException("Invalid road unit dimensions: "+kind);
                final int surface=spec.get("surfaceY").getAsInt();
                if(surface<0 || surface>=blueprint.getSizeY()) throw new IllegalStateException("Invalid surface height");
                final var unit=new UnitTemplate(1,blueprint.getSizeY(),blueprint.getSizeZ(),surface,Direction.Axis.X);
                final var occupied=new HashSet<BlockPos>();
                for(final var cell:spec.getAsJsonArray("writeCells")) {
                    final var pos=coordinate(cell.getAsJsonArray(),blueprint.getSizeY(),blueprint.getSizeZ());
                    if(!occupied.add(pos)) throw new IllegalStateException("Duplicate unit write cell");
                    unit.add(pos,blueprint.getBlockState(pos),blueprint.getTileEntities()[pos.getY()][pos.getZ()][pos.getX()]);
                }
                for(final var cell:spec.getAsJsonArray("skipCells")) {
                    final var pos=coordinate(cell.getAsJsonArray(),blueprint.getSizeY(),blueprint.getSizeZ());
                    if(!occupied.add(pos)) throw new IllegalStateException("Conflicting unit write/skip cell");
                    unit.addPlaceholder(pos);
                }
                return unit.freeze();
            }
        } catch(final Exception ex) {
            throw new IllegalStateException("Could not load bundled road unit "+kind,ex);
        }
    }
    private static BlockPos coordinate(final com.google.gson.JsonArray cell,final int height,final int width) {
        if(cell.size()!=3) throw new IllegalStateException("Invalid unit coordinate");
        final var pos=new BlockPos(cell.get(0).getAsInt(),cell.get(1).getAsInt(),cell.get(2).getAsInt());
        if(pos.getX()!=0 || pos.getY()<0 || pos.getY()>=height || pos.getZ()<0 || pos.getZ()>=width)
            throw new IllegalStateException("Unit cell out of bounds: "+pos);
        return pos;
    }
    @SubscribeEvent public static synchronized void stopped(final ServerStoppedEvent event) { CACHE.clear(); }
    private RoadUnitLibrary() { }
}
