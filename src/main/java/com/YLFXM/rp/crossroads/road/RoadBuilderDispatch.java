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

import com.YLFXM.rp.crossroads.CustomRoadPack;
import com.YLFXM.rp.crossroads.RoadPermissions;
import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.workorders.IServerWorkOrder;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.core.colony.workorders.WorkOrderDecoration;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Trusted admission for one already validated frozen road, never for ordinary buildings. */
final class RoadBuilderDispatch {
    static boolean dispatch(ServerLevel level,ServerPlayer actor,RoadPlan plan,Blueprint frozen) {
        IColony colony=IColonyManager.getInstance().getColonyByPosFromWorld(level,plan.controllerPos());
        if(colony==null || !RoadPermissions.canManage(level,actor,plan) || !RoadPermissions.canManage(actor,colony))
            throw new IllegalArgumentException("A road builder order requires management permission in its controller's colony.");
        var order=WorkOrderDecoration.create(WorkOrderType.BUILD,CustomRoadPack.PACK_NAME,RoadTemporaryPack.pathFor(plan),
                RoadNames.label(plan),plan.anchorPos(),RotationMirror.NONE,0);
        order.setBlueprint(frozen,level);order.setColony(colony);
        boolean accepted=admit(colony,order);
        if(accepted)try {RoadProtectedIterator.protectOrders(plan);}
        catch(RuntimeException ex) {colony.getWorkManager().removeWorkOrder(order.getID());throw ex;}
        return accepted;
    }
    static boolean admit(IColony colony,IServerWorkOrder order) {
        var manager=colony.getWorkManager();
        try {
            // Same restoration admission as road demolition: bypass only colony-space rejection,
            // then run the native new-order hook for tape/notifications.
            manager.addWorkOrder(order,true);
            if(manager.getWorkOrder(order.getID())!=order)return false;
            order.onAdded(colony,false);
            return true;
        } catch(RuntimeException ex) {
            if(manager.getWorkOrder(order.getID())==order)manager.removeWorkOrder(order.getID());
            throw ex;
        }
    }
    private RoadBuilderDispatch() { }
}
