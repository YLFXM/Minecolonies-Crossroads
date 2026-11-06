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

import java.io.IOException;
import java.nio.file.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import com.YLFXM.rp.crossroads.Crossroads;

/** Durable redo record, created only after native work and full world validation succeed. */
public final class RoadJointJournal {
    private static Path directory(ServerLevel level) {
        var root=level.getServer().getWorldPath(LevelResource.ROOT);
        return net.minecraft.world.level.dimension.DimensionType.getStorageFolder(level.dimension(),root)
                .resolve("rp_crossroads").resolve("commits");
    }
    private static Path file(ServerLevel level,RoadRepairSession session) {return directory(level).resolve(session.token()+".nbt");}
    public static void prepare(ServerLevel level,RoadPlan road,RoadRepairSession session) throws IOException {
        if(Files.isRegularFile(file(level,session)))return;
        var tag=new CompoundTag();tag.putUUID("road",road.id());tag.put("roadBefore",road.save());tag.put("session",session.save());
        RoadAtomicNbt.write(file(level,session),tag);
    }
    public static void finish(ServerLevel level,RoadRepairSession session) throws IOException {
        RoadManager.get(level).checkpoint(level);
        Files.deleteIfExists(file(level,session));
    }
    /** Runs before active sessions are polled. Missing roads are never resurrected. */
    public static void recover(ServerLevel level) {
        if(!Files.isDirectory(directory(level)))return;
        try(var files=Files.list(directory(level))) {
            for(var path:files.filter(p -> p.getFileName().toString().endsWith(".nbt")).sorted().toList()) {
                try {
                    var record=RoadAtomicNbt.read(path);var id=record.getUUID("road");var live=RoadManager.get(level).get(id);
                    if(live==null) {Crossroads.LOGGER.error("Joint commit {} references a missing road {}; retained for inspection",path,id);continue;}
                    var session=RoadRepairSession.load(record.getCompound("session"));
                    if(live.repairSession()==null && live.state()==RoadState.BUILT && session.target().geometrySnapshot().equals(live.appliedGeometry())) {
                        Files.deleteIfExists(path);continue; // checkpoint succeeded before the final file deletion
                    }
                    if(live.repairSession()!=null && !live.repairSession().token().equals(session.token()))
                        throw new IOException("A newer session conflicts with the retained commit");
                    live=RoadPlan.load(record.getCompound("roadBefore"));
                    live.setRepairSession(session);live.setState(RoadState.BUILDING);RoadManager.get(level).add(live);
                    Crossroads.LOGGER.info("Recovered frozen joint snapshot commit for {}",id);
                } catch(IOException | RuntimeException ex) {Crossroads.LOGGER.error("Could not recover joint commit {}",path,ex);}
            }
        } catch(IOException ex) {Crossroads.LOGGER.error("Could not read joint commit records",ex);}
    }
    private RoadJointJournal() { }
}
