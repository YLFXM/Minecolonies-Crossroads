package com.YLFXM.rp.crossroads.road;

import com.YLFXM.rp.crossroads.road.common.BlueprintCells;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Names, anchor block entities, skip predicates and confirmation rules of the
 * per-level road blueprints.
 *
 * <p>Pure decisions only: the level list is built by the native tool from file
 * names plus the controller's {@code schematicName}, so those two have to agree
 * exactly, and the two protection predicates have to skip the controller without
 * ever skipping the terrain fill.</p>
 */
public final class TierBlueprintRegression {
    private static int checks;

    private static void check(final boolean value, final String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static RoadPlan plan(final String name) {
        final RoadPlan plan = new RoadPlan(UUID.fromString("3f2a6c1e-0000-4000-8000-000000000001"),
                Level.OVERWORLD, new BlockPos(10, 65, 20), new BlockPos(10, 65, 19),
                Direction.SOUTH, false, "minecolonies", "street", 4, WalkRange.of(1, 2), 1, 4);
        plan.addSegment(new RoadSegment(new BlockPos(5, 64, 20), 6, Direction.Axis.X, 0, SegmentKind.ROAD));
        plan.setDisplayName(name);
        plan.setUnitLevel(1);
        return plan;
    }

    public static void main(final String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
        // Standalone registration window: Bootstrap freezes the registries before a
        // test can add the mod markers, so register the two substitution blocks under
        // its guard and then run the normal bootstrap.
        final java.lang.reflect.Field guard = Bootstrap.class.getDeclaredField("isBootstrapped");
        guard.setAccessible(true);
        guard.setBoolean(null, true);
        try {
            net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.BLOCK,
                    net.minecraft.resources.ResourceLocation.parse("structurize:blocksubstitution"),
                    new com.ldtteam.structurize.blocks.schematic.BlockSubstitution());
            net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.BLOCK,
                    net.minecraft.resources.ResourceLocation.parse("structurize:blocksolidsubstitution"),
                    new com.ldtteam.structurize.blocks.schematic.BlockSolidSubstitution());
        } finally {
            guard.setBoolean(null, false);
        }
        Bootstrap.bootStrap();

        // ---- 1. file stems: one digit, no other numbers, unique per road.
        final RoadPlan oak = plan("Oak Road");
        final String base = RoadNames.blueprintBase(oak);
        check(base.equals("Oak Road-dpck"), "base keeps the name and maps the id tag to letters: " + base);
        check(!base.matches(".*[0-9].*"), "base carries no digit, so getLevel reads only the tier");
        check(RoadNames.fileName(oak, 3).equals(base + "3"), "tier file stem is base plus the level");
        check(RoadNames.tierOf(base + "3", base) == 3, "tier parses back from the stem");
        check(RoadNames.tierOf(base, base) == -1, "a stem without a level is not a tier");
        check(RoadNames.tierOf(base + "10", base) == -1, "ten is not a single-digit level");
        check(RoadNames.stripTier(base + "4").equals(base), "stripping the level gives the base back");
        final RoadPlan renamed = plan("Oak Road");
        renamed.setId(UUID.fromString("9c8b7a65-0000-4000-8000-000000000002"));
        check(!RoadNames.blueprintBase(renamed).equals(base), "two roads with the same name still get distinct stems");
        final RoadPlan numbered = plan("Oak Road 2");
        check(RoadNames.blueprintBase(numbered).equals(base),
                "a digit in the name is dropped instead of joining the level: " + RoadNames.blueprintBase(numbered));
        final RoadPlan blank = plan("Colony-");
        check(RoadNames.blueprintBase(blank).startsWith("road-"),
                "a name that reduces to nothing falls back to road: " + RoadNames.blueprintBase(blank));
        final RoadPlan longName = plan("x".repeat(80));
        check(RoadNames.blueprintBase(longName).length() <= 32 + 1 + 4, "the name part is capped at 32 characters");

        // ---- 2. anchor block entity carries the tier the native tool reads.
        for (int tier = 1; tier <= 4; tier++) {
            final RoadPlan copy = RoadPlan.load(oak.save());
            copy.setUnitLevel(tier);
            final var tile = RoadBlueprintGenerator.anchorTile(copy);
            check(tile.getCompound("blueprintDataProvider").getString("schematicName")
                            .equals(RoadNames.fileName(oak, tier)),
                    "schematicName names the tier file at level " + tier);
            check(tile.getCompound("blueprintDataProvider").getString("schematicName")
                            .replaceAll("[^0-9]", "").equals(Integer.toString(tier)),
                    "the only number in schematicName is the level");
        }

        // ---- 3. both native protection predicates skip the controller, never the fill.
        final BlockState placeholder = com.ldtteam.structurize.blocks.ModBlocks.blockSubstitution.get().defaultBlockState();
        final BlockState solidFill = com.ldtteam.structurize.blocks.ModBlocks.blockSolidSubstitution.get().defaultBlockState();
        final BlockState stone = Blocks.STONE.defaultBlockState();
        check(RoadProtectedIterator.protectedState(placeholder), "builder skips substitution placeholders");
        check(RoadProtectedStructurePlacer.protectedState(placeholder), "creative placer skips substitution placeholders");
        check(!RoadProtectedIterator.protectedState(solidFill) && !RoadProtectedStructurePlacer.protectedState(solidFill),
                "terrain fill is still placed");
        check(!RoadProtectedIterator.protectedState(stone) && !RoadProtectedStructurePlacer.protectedState(stone),
                "ordinary road blocks are untouched by the predicate");
        final BlockState controller = controllerState();
        if (controller != null) {
            check(RoadProtectedIterator.protectedState(controller), "builder never places the leveled controller");
            check(RoadProtectedStructurePlacer.protectedState(controller), "creative placer never places the leveled controller");
        } else {
            // The block cannot be constructed outside a running game; the predicate
            // source still has to name it, which is what the next check reads.
            final String iterator = source("RoadProtectedIterator.java");
            final String placer = source("RoadProtectedStructurePlacer.java");
            check(iterator.contains("BlockDecorationController") && placer.contains("BlockDecorationController"),
                    "both predicates still name the decoration controller");
        }

        // ---- 4. confirmation: same base, in range, in this directory.
        final BlockPos anchor = new BlockPos(10, 65, 20);
        final String dir = "generated";
        check(RoadRepairSelection.validateTier("Crossroads", dir, base, 4, anchor, 0,
                        "Crossroads", "generated/" + base + "3.blueprint", anchor, 0)
                        == RoadRepairSelection.Mismatch.NONE,
                "any level of this road is accepted");
        check(RoadRepairSelection.validateTier("Crossroads", dir, base, 4, anchor, 0,
                        "Crossroads", "generated/" + base + "5.blueprint", anchor, 0)
                        == RoadRepairSelection.Mismatch.TIER,
                "a level the series does not ship is refused");
        check(RoadRepairSelection.validateTier("Crossroads", dir, base, 4, anchor, 0,
                        "Crossroads", "generated/" + base + ".blueprint", anchor, 0)
                        == RoadRepairSelection.Mismatch.TIER,
                "a file without a level is refused");
        check(RoadRepairSelection.validateTier("Crossroads", dir, base, 4, anchor, 0,
                        "Crossroads", "generated/Other-dtcq1.blueprint", anchor, 0)
                        == RoadRepairSelection.Mismatch.PATH,
                "another road's file is refused");
        check(RoadRepairSelection.validateTier("Crossroads", dir, base, 4, anchor, 0,
                        "Crossroads", "elsewhere/" + base + "1.blueprint", anchor, 0)
                        == RoadRepairSelection.Mismatch.PATH,
                "another directory is refused");
        check(RoadRepairSelection.validateTier("Crossroads", dir, base, 4, anchor, 0,
                        "Crossroads", "generated/../generated/" + base + "1.blueprint", anchor, 0)
                        == RoadRepairSelection.Mismatch.PATH,
                "a traversal that lands in the same directory is still refused");
        check(RoadRepairSelection.validateTier("Crossroads", dir, base, 4, anchor, 0,
                        "Foreign", "generated/" + base + "1.blueprint", anchor, 0)
                        == RoadRepairSelection.Mismatch.PACK,
                "another structure pack is refused");
        check(RoadRepairSelection.validateTier("Crossroads", dir, base, 4, anchor, 0,
                        "Crossroads", "generated\\" + base + "2.blueprint", anchor, 0)
                        == RoadRepairSelection.Mismatch.NONE,
                "Windows separators are normalised before the check");
        check(RoadRepairSelection.sameRoad("generated/" + base + "1.blueprint", base + "4.blueprint"),
                "the tool preselects any level of the same road");
        check(!RoadRepairSelection.sameRoad("generated/" + base + "1.blueprint", "Other-dtcq1.blueprint"),
                "the preselect does not cross roads");

        // ---- 5. cleanup touches only this session's levels of this road.
        final List<String> existing = List.of(
                base + "1.blueprint", base + "3.blueprint", "Other-dtcq2.blueprint",
                "road_123.blueprint", "notes.txt");
        final List<String> stale = RoadTemporaryPack.staleTierFiles(existing, base, Set.of(base + "3.blueprint"));
        check(stale.equals(List.of(base + "1.blueprint")),
                "only unselected levels of this road are removed: " + stale);
        check(RoadTemporaryPack.staleTierFiles(existing, base, Set.of()).size() == 2,
                "a full release clears both levels of this road");
        check(RoadTemporaryPack.staleTierFiles(existing, base, Set.of(base + "1.blueprint", base + "3.blueprint")).isEmpty(),
                "keeping every level leaves nothing to delete");

        // ---- 6. the filled primary stays the primary after the frame is derived.
        check(BlueprintCells.blueprintAnchor(oak).equals(oak.anchorPos()),
                "an unbound plan still derives the primary from its anchor");

        // ---- 7. the blueprint handed to a builder order is a pack blueprint, not a bare
        // object. Native completion writes its pack and path into every decoration
        // controller it contains; a null pack name threw an NPE there and left the
        // builder paused, which is what broke upgrades of a finished road.
        final String dispatch = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/YLFXM/rp/crossroads/road/RoadBuilderDispatch.java"));
        check(dispatch.contains("setPackName(CustomRoadPack.PACK_NAME)"),
                "the dispatched work volume carries the pack name");
        check(dispatch.contains("setFilePath(java.nio.file.Path.of(RoadTemporaryPack.ROOT_DIR))"),
                "the dispatched work volume carries the pack-relative directory");
        check(dispatch.contains("setFileName(RoadNames.fileName(plan,plan.unitLevel()))"),
                "the dispatched work volume carries this level's stem");
        final String frame = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/YLFXM/rp/crossroads/road/BlueprintAnchor.java"));
        check(frame.contains("recorded.equals(target)"),
                "the primary only stays when the reserved cell really is the same");

        System.out.println("PASS " + checks + " tier blueprint assertions");
    }

    /** Real controller state when the game's registries allow constructing it. */
    private static BlockState controllerState() {
        try {
            final var block = new com.minecolonies.core.blocks.BlockDecorationController(
                    net.minecraft.world.level.block.state.BlockBehaviour.Properties.of());
            return block.defaultBlockState();
        } catch (final Throwable unavailable) {
            return null;
        }
    }

    private static String source(final String file) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/java/com/YLFXM/rp/crossroads/road", file));
        } catch (final java.io.IOException ex) {
            throw new AssertionError("cannot read " + file, ex);
        }
    }

    private TierBlueprintRegression() {
    }
}
