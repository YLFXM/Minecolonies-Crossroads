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

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/** Persistent identity of one native demolition; world progress belongs to the native worker/queue. */
public final class RoadRemovalSession {
    private final UUID token;
    private final UUID owner;
    private final boolean instant;
    private final int colonyId;
    private int orderId;
    public RoadRemovalSession(UUID owner, boolean instant, int colonyId) {
        this(UUID.randomUUID(),owner,instant,colonyId,0);
    }
    private RoadRemovalSession(UUID token, UUID owner, boolean instant, int colonyId, int orderId) {
        this.token=token; this.owner=owner; this.instant=instant; this.colonyId=colonyId; this.orderId=orderId;
    }
    public UUID token() { return token; }
    public UUID owner() { return owner; }
    public boolean instant() { return instant; }
    public int colonyId() { return colonyId; }
    public int orderId() { return orderId; }
    public void setOrderId(int id) { orderId=id; }
    public String path(UUID road) { return "removal/"+road+"_"+token+".blueprint"; }
    public CompoundTag save() {
        var tag=new CompoundTag(); tag.putUUID("token",token); tag.putUUID("owner",owner);
        tag.putBoolean("instant",instant); tag.putInt("colony",colonyId); tag.putInt("order",orderId); return tag;
    }
    public static RoadRemovalSession load(CompoundTag tag) {
        return new RoadRemovalSession(tag.getUUID("token"),tag.getUUID("owner"),tag.getBoolean("instant"),tag.getInt("colony"),tag.getInt("order"));
    }
}
