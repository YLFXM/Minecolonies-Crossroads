/*
 * Copyright (C) 2026 ZhuanZ (YLFXM)
 *
 * MineColonies: Reconstruction Plan - a feature expansion addon for MineColonies.
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
package com.YLFXM.mcreconstruction.road;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.blueprints.v1.BlueprintUtil;
import com.YLFXM.mcreconstruction.MCReconstruction;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Per-save snapshot of a generated road blueprint. RoadPlan can regenerate a
 * blueprint. Proposed snapshots may change during editing; applied snapshots are
 * saved only after actual completion and are the only basis for world removal.
 */
public final class RoadBlueprintStore {
    public static Path directory(final ServerLevel level) {
        final MinecraftServer server = level.getServer();
        return server.getWorldPath(LevelResource.ROOT).resolve("mcreconstruction").resolve("blueprints");
    }

    public static Path file(final ServerLevel level, final RoadPlan plan) {
        return directory(level).resolve("road_" + plan.id() + ".blueprint");
    }

    private static Path appliedFile(final ServerLevel level, final RoadPlan plan) {
        return directory(level).resolve("applied_" + plan.id() + ".blueprint");
    }

    public static void saveApplied(final ServerLevel level, final RoadPlan plan, final Blueprint blueprint) {
        saveAppliedChecked(level,plan,blueprint);
    }

    public static boolean saveAppliedChecked(final ServerLevel level, final RoadPlan plan, final Blueprint blueprint) {
        if(blueprint==null || !write(appliedFile(level,plan),plan,blueprint)) return false;
        plan.setAppliedBounds(occupiedBounds(plan,blueprint)); return true;
    }

    public static Blueprint loadApplied(final ServerLevel level, final RoadPlan plan) {
        final Blueprint blueprint=read(level,plan,appliedFile(level,plan));
        if(blueprint!=null) plan.setAppliedBounds(occupiedBounds(plan,blueprint));
        return blueprint;
    }

    public static void ensureAppliedBounds(final ServerLevel level, final RoadPlan plan) {
        if(plan.appliedBounds()==null) {
            preserveBuilt(level,plan);
            if(plan.appliedBounds()==null) loadApplied(level,plan);
        }
    }

    private static RoadBounds occupiedBounds(final RoadPlan plan,final Blueprint blueprint) {
        final var origin=RoadBlueprintOps.origin(plan,blueprint);
        int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE;
        int maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        for(final var info:blueprint.getBlockInfoAsList()) {
            if(!RoadBlueprintOps.isConcrete(info.getState()) && !RoadBlueprintOps.isSolidFill(info.getState())) continue;
            final var p=origin.offset(info.getPos());
            minX=Math.min(minX,p.getX());minY=Math.min(minY,p.getY());minZ=Math.min(minZ,p.getZ());
            maxX=Math.max(maxX,p.getX());maxY=Math.max(maxY,p.getY());maxZ=Math.max(maxZ,p.getZ());
        }
        return minX==Integer.MAX_VALUE ? null : new RoadBounds(minX,minY,minZ,maxX,maxY,maxZ);
    }

    public static void preserveBuilt(final ServerLevel level, final RoadPlan plan) {
        if (plan.state() == RoadState.BUILT && !Files.isRegularFile(appliedFile(level, plan))) {
            saveApplied(level, plan, load(level, plan));
        }
    }

    public static void save(final ServerLevel level, final RoadPlan plan, final Blueprint blueprint) {
        write(file(level, plan), plan, blueprint);
    }

    public static boolean saveChecked(final ServerLevel level, final RoadPlan plan, final Blueprint blueprint) {
        return write(file(level,plan),plan,blueprint);
    }

    private static boolean write(final Path path, final RoadPlan plan, final Blueprint blueprint) {
        try {
            Files.createDirectories(path.getParent());
            final CompoundTag tag = BlueprintUtil.writeBlueprintToNBT(blueprint);
            NbtIo.writeCompressed(tag, path);
            return true;
        } catch (final IOException ex) {
            MCReconstruction.LOGGER.error("Could not save road blueprint for {}", plan.id(), ex);
            return false;
        }
    }

    public static Blueprint load(final ServerLevel level, final RoadPlan plan) {
        return read(level, plan, file(level, plan));
    }

    private static Blueprint read(final ServerLevel level, final RoadPlan plan, final Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try (InputStream input = Files.newInputStream(path)) {
            final CompoundTag tag = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
            return BlueprintUtil.readBlueprintFromNBT(tag, level.registryAccess());
        } catch (final IOException ex) {
            MCReconstruction.LOGGER.error("Could not load road blueprint for {}", plan.id(), ex);
            return null;
        }
    }

    private static Path removalFile(ServerLevel level,RoadPlan plan,RoadRemovalSession session) {
        return directory(level).resolve("removal_"+plan.id()+"_"+session.token()+".blueprint");
    }
    public static boolean saveRemoval(ServerLevel level,RoadPlan plan,RoadRemovalSession session,Blueprint blueprint) {
        return write(removalFile(level,plan,session),plan,blueprint);
    }
    public static Blueprint loadRemoval(ServerLevel level,RoadPlan plan,RoadRemovalSession session) {
        return read(level,plan,removalFile(level,plan,session));
    }
    public static boolean publishRemoval(ServerLevel level,RoadPlan plan,RoadRemovalSession session,Blueprint blueprint) {
        Path path=com.YLFXM.mcreconstruction.CustomRoadPack.packRoot(level.getServer()).resolve(session.path(plan.id()));
        if (!write(path,plan,blueprint)) return false;
        com.YLFXM.mcreconstruction.CustomRoadPack.install(level.getServer()); return true;
    }
    public static void deleteRemoval(ServerLevel level,RoadPlan plan,RoadRemovalSession session) {
        try {
            Files.deleteIfExists(removalFile(level,plan,session));
            Files.deleteIfExists(com.YLFXM.mcreconstruction.CustomRoadPack.packRoot(level.getServer()).resolve(session.path(plan.id())));
        } catch(IOException ex) { MCReconstruction.LOGGER.warn("Could not delete removal snapshot {}",plan.id(),ex); }
    }
    public static void delete(final ServerLevel level, final RoadPlan plan) {
        try {
            Files.deleteIfExists(file(level, plan));
            Files.deleteIfExists(appliedFile(level, plan));
        } catch (final IOException ex) {
            MCReconstruction.LOGGER.warn("Could not delete road blueprint for {}", plan.id(), ex);
        }
    }

    private RoadBlueprintStore() {
    }
}
