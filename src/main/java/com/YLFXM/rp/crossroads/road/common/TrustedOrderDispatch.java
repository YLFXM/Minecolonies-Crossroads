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
package com.YLFXM.rp.crossroads.road.common;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.workorders.IServerWorkOrder;
import com.minecolonies.core.colony.workorders.WorkOrderDecoration;

/**
 * Colony admission for orders this mod builds from generated blueprints.
 *
 * <p>MineColonies rejects a new order through the normal path when it decides
 * the target has to be a colony building; both the road repair/upgrade order and
 * the terrain dig/fill order are {@link WorkOrderDecoration} with a blueprint we
 * already validated, so they share this one admission instead of each keeping a
 * near-identical copy.</p>
 *
 * <p>{@code addWorkOrder(order, true)} bypasses exactly the colony-space
 * rejection, then runs the native "order added" hook so notification and tape
 * behave like any other order. If either step refuses, the order is removed
 * again so a half-admitted order cannot linger.</p>
 */
public final class TrustedOrderDispatch {
    /**
     * @param readingFromNbt {@code true} skips the native colony-space check.
     *                       Road orders pass true because the plan was already
     *                       validated against its own colony; terrain orders pass
     *                       false so the native "outside colony" refusal applies.
     */
    public static boolean admit(final IColony colony, final IServerWorkOrder order,
                                final boolean readingFromNbt) {
        final var manager = colony.getWorkManager();
        try {
            manager.addWorkOrder(order, readingFromNbt);
            if (manager.getWorkOrder(order.getID()) != order) {
                return false;
            }
            order.onAdded(colony, false);
            return true;
        } catch (final RuntimeException ex) {
            if (manager.getWorkOrder(order.getID()) == order) {
                manager.removeWorkOrder(order.getID());
            }
            throw ex;
        }
    }

    private TrustedOrderDispatch() { }
}
