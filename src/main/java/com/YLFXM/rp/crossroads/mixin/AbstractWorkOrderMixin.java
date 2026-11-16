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
package com.YLFXM.rp.crossroads.mixin;

import com.YLFXM.rp.crossroads.Crossroads;
import com.YLFXM.rp.crossroads.road.RoadOrderSignal;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.minecolonies.api.colony.workorders.IWorkManager;
import com.minecolonies.api.colony.workorders.IWorkOrder;
import com.minecolonies.core.colony.workorders.AbstractWorkOrder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Completion signal: reports every finished builder work order to the road system.
 *
 * <p>Wraps the single {@code removeWorkOrder} call at the end of
 * {@code AbstractWorkOrder.onCompleted} - the one place in MineColonies where a structure
 * order announces that it is done. The wrapper only hands the order over
 * ({@link RoadOrderSignal}); the road itself is finished on the next server tick, because
 * at this instant the order is still in the work manager and the world must not be touched
 * on the builder AI's completion stack.</p>
 *
 * <p>Deliberately no {@code require = 0}: if minecolonies moves that call, this must fail
 * loudly instead of silently dropping the completion signal.</p>
 */
@Mixin(AbstractWorkOrder.class)
public abstract class AbstractWorkOrderMixin {
    @WrapOperation(
            method = "onCompleted",
            at = @At(value = "INVOKE",
                     target = "Lcom/minecolonies/api/colony/workorders/IWorkManager;removeWorkOrder(I)V"))
    private void rp$onWorkOrderCompleted(final IWorkManager manager, final int orderId,
                                         final Operation<Void> original) {
        // Recorded before the removal so a throwing removal cannot lose the signal; the
        // queue only holds ids, so an order that never actually leaves the manager simply
        // makes the drain a no-op.
        RoadOrderSignal.onOrderCompleted(manager, (IWorkOrder) (Object) this);
        original.call(manager, orderId);
        if (Crossroads.LOGGER.isDebugEnabled()) {
            Crossroads.LOGGER.debug("Work order {} completed", orderId);
        }
    }
}
