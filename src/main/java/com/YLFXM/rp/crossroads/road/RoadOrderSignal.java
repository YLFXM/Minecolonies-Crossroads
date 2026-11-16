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
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.workorders.IWorkManager;
import com.minecolonies.api.colony.workorders.IWorkOrder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Turns "a builder work order completed" into "finish this road now".
 *
 * <p>MineColonies has no completion event, but it does have one place where every work
 * order announces that it finished: {@code AbstractWorkOrder.onCompleted}, right before it
 * removes itself from the work manager. The mod wraps that call, and this class is the only
 * thing the wrapper is allowed to do — decide whether the order belongs to a road and drop
 * the road into a queue.</p>
 *
 * <p><b>Nothing happens inside the wrapper.</b> At that instant the order is still in the
 * manager, so every existing completion check ({@code RoadBuildWatcher.hasWorkOrder},
 * {@code RoadRepair.poll}, {@code RoadNativeRemoval.poll}) would still see it and bail out;
 * and the real finish writes blocks, NBT snapshots and network packets, which must not run
 * on the builder AI's completion stack. So the queue is drained on the next server tick,
 * when the order is gone and the world is a tick older.</p>
 *
 * <p>A road can need a tick or two to settle (the last block may still be on its way to the
 * client), so a queued road is retried for a handful of ticks. A retry never cancels
 * anything: the periodic sweeps keep that verdict, which is also why a slow or unusual
 * build cannot be turned into a failure by this class.</p>
 */
@EventBusSubscriber(modid = Crossroads.MODID)
public final class RoadOrderSignal {
    /** Ticks a queued road may be retried before the periodic sweeps take over again. */
    private static final int FAST_ATTEMPTS = 5;

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private record Pending(ServerLevel level, int attemptsLeft) {
    }

    /**
     * Called from the mixin wrapping {@code AbstractWorkOrder.onCompleted}, just before the
     * order removes itself.
     *
     * <p>Every work order in the game passes through here, so the body must stay cheap and
     * must never throw: a failure would abort the native completion of an unrelated mod's
     * order.</p>
     */
    public static void onOrderCompleted(final IWorkManager manager, final IWorkOrder order) {
        if (manager == null || order == null) {
            return;
        }
        try {
            final IColony colony = manager.getColony();
            if (colony == null || !(colony.getWorld() instanceof ServerLevel level)) {
                return;
            }
            final RoadPlan plan = RoadWorkOrders.find(level, order);
            if (plan == null) {
                return;
            }
            PENDING.put(plan.id(), new Pending(level, FAST_ATTEMPTS));
            if (Crossroads.LOGGER.isDebugEnabled()) {
                Crossroads.LOGGER.debug("Road {} reported complete by work order {}; finishing next tick",
                        plan.id(), order.getID());
            }
        } catch (final RuntimeException ex) {
            Crossroads.LOGGER.warn("Could not route a completed work order to a road", ex);
        }
    }

    /**
     * Drains the queue every tick — the periodic 20-tick sweeps are untouched and stay the
     * fallback for cancellations, restarts and anything a signal missed.
     */
    @SubscribeEvent
    public static void onServerTick(final ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) {
            return;
        }
        for (final Map.Entry<UUID, Pending> entry : List.copyOf(PENDING.entrySet())) {
            final UUID id = entry.getKey();
            final Pending pending = entry.getValue();
            final RoadPlan plan = RoadManager.get(pending.level()).get(id);
            if (plan == null || !dispatch(pending.level(), plan) || pending.attemptsLeft() <= 1) {
                PENDING.remove(id);
                continue;
            }
            PENDING.put(id, new Pending(pending.level(), pending.attemptsLeft() - 1));
        }
    }

    @SubscribeEvent
    public static void onServerStopped(final ServerStoppedEvent event) {
        PENDING.clear();
    }

    /**
     * Sends the road to whichever flow owns it. Demolition first: it keeps its own session
     * and must not be finished as a build.
     *
     * @return {@code true} while that flow is still working on the plan.
     */
    private static boolean dispatch(final ServerLevel level, final RoadPlan plan) {
        if (plan.removalSession() != null) {
            return RoadNativeRemoval.pollNow(level, plan);
        }
        if (plan.repairSession() != null) {
            return RoadRepair.pollNow(level, plan);
        }
        return RoadBuildWatcher.pollNow(level, plan);
    }

    private RoadOrderSignal() {
    }
}
