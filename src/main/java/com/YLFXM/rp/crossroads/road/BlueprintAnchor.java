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
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Binds a plan's blueprint primary to the controller's reserved cell.
 *
 * <p>Before 0.3.3 the primary was always {@code plan.anchorPos()} - the visible
 * marker block, which is not a road cell. A plan that records
 * {@code blueprintAnchor} instead puts the build tool's red frame on the cell the
 * blueprint reserves for the controller. Old plans keep the previous reading
 * until they are regenerated once; that switch is the only migration this needs,
 * and it happens on first use rather than while loading a save.</p>
 *
 * <p>The switch is legal only because a blueprint's world origin is
 * {@code primary - primaryOffset}: moving the primary has to move every stored
 * offset by the same delta, or the same file would be interpreted a cell away
 * from the blocks it describes. {@link RoadBlueprintStore#shiftFrame} does that;
 * if it cannot do all of it, the whole switch is abandoned and the plan keeps the
 * old frame.</p>
 */
public final class BlueprintAnchor {
    /** What one binding call decided. */
    public enum Outcome {
        /** A non-draft plan already records its primary; nothing was touched. */
        KEPT,
        /** The recorded primary now points at {@code target}. */
        MIGRATED,
        /** Stored frames could not be rewritten, so the old primary stays. */
        REFUSED
    }

    /** The cell the generation must use, and how it was decided. */
    public record Binding(BlockPos anchor, Outcome outcome) {
        public boolean migrated() {
            return outcome == Outcome.MIGRATED;
        }
    }

    /**
     * Pure decision, with the stored-frame rewrite supplied by the caller.
     *
     * <p>Split out so the regression can drive every branch - first bind, stable
     * geometry, a reserved cell that moved, failed rewrite - without a level or
     * files.</p>
     *
     * <p>The invariant is "the primary is the cell this geometry reserves for the
     * controller", not "the primary stops moving once the plan leaves DRAFT". A
     * mirror flips the body to the other side of the anchor, so a built road's
     * reserved cell moves at a time when the plan is long past DRAFT; freezing the
     * field then leaves the primary outside the new image, which both inflates the
     * joint work volume into a slab of placeholders and breaks completion checks.
     * Geometry that did not change yields the same target, so nothing moves.</p>
     *
     * @param plan         plan being bound; its recorded field is updated on success
     * @param legacyAnchor anchor the stored blueprints were authored against
     * @param target       cell the controller reserves for this geometry
     * @param shift        rewrites every stored frame by a delta; false when that was not possible
     */
    public static Binding decide(final RoadPlan plan, final BlockPos legacyAnchor, final BlockPos target,
                                 final Predicate<BlockPos> shift) {
        final BlockPos recorded = plan.blueprintAnchor();
        if (recorded != null && recorded.equals(target)) {
            return new Binding(recorded, Outcome.KEPT);
        }
        // First bind: the images in the store were authored against the geometry
        // anchor. Every later bind: they were authored against the previous target.
        final BlockPos from = recorded != null ? recorded : legacyAnchor;
        final BlockPos delta = target.subtract(from);
        if (!delta.equals(BlockPos.ZERO) && !shift.test(delta)) {
            return new Binding(from, Outcome.REFUSED);
        }
        plan.setBlueprintAnchor(target);
        return new Binding(target, Outcome.MIGRATED);
    }

    /**
     * Makes sure {@code plan} records the primary cell its blueprint is about to
     * be generated with, and returns that cell.
     *
     * <p>Generation usually runs on a copy - a repair session hands out a fresh
     * {@code target()} every call - so the decision is written back to the plan the
     * manager holds under the same id. Without that write the blueprint would
     * carry a primary on the reserved cell while every later reader still derived
     * the origin from the anchor, i.e. one cell off.</p>
     */
    public static Binding bind(final ServerLevel level, final RoadPlan plan, final BlockPos legacyAnchor) {
        final RoadPlan live = RoadManager.get(level).get(plan.id());
        if (live != null && live != plan && live.blueprintAnchor() != null) {
            // The plan the manager holds owns the frame. A copy can predate the last move
            // (a frozen repair target, a joint model, a rebuilt candidate); deciding from
            // its stale cell would shift both stored images a second time for a move that
            // already happened, and nothing would report it. The result is an image whose
            // origin is a whole delta away from the blocks it describes.
            plan.setBlueprintAnchor(live.blueprintAnchor());
        }
        final BlockPos target = RoadControllerMigration.candidates(plan).get(0);
        final Binding binding = decide(plan, legacyAnchor, target,
                delta -> RoadBlueprintStore.shiftFrame(level, plan, delta));
        if (binding.migrated()) {
            if (live != null && live != plan) {
                live.setBlueprintAnchor(binding.anchor());
                RoadManager.get(level).add(live);
            }
            Crossroads.LOGGER.info("Road {}: blueprint primary is now {}", plan.id(), binding.anchor());
        } else if (binding.outcome() == Outcome.REFUSED) {
            Crossroads.LOGGER.warn("Road {}: kept blueprint primary {} (moving it to {} failed)",
                    plan.id(), binding.anchor(), target);
        }
        return binding;
    }

    private BlueprintAnchor() {
    }
}
