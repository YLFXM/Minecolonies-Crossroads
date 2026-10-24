package com.YLFXM.mcreconstruction.road;

import com.ldtteam.structurize.blueprints.v1.BlueprintUtil;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

/** Exercises the production volume factory against native Blueprint/NBT, without a game world. */
public final class RoadStage6FixRegression {
    private static int checks;
    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        net.neoforged.fml.loading.LoadingModList.of(java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.Map.of());
        Bootstrap.bootStrap();
        final var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        final var blueprint = RoadBlueprintGenerator.emptyBlueprint((short) 4, (short) 6, (short) 4, registries);
        for (int y = 0; y < 6; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++)
            check(blueprint.getBlockState(new BlockPos(x, y, z)).is(Blocks.AIR), "unwritten volume must be native AIR");
        final var surface = new BlockPos(1, 1, 1);
        blueprint.addBlockState(surface, Blocks.STONE.defaultBlockState());
        blueprint.setCachePrimaryOffset(BlockPos.ZERO);
        final var restored = BlueprintUtil.readBlueprintFromNBT(BlueprintUtil.writeBlueprintToNBT(blueprint), registries);
        check(restored != null, "production volume serializes through the native blueprint format");
        for (int y = 0; y < 6; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
            final var pos = new BlockPos(x, y, z);
            check(restored.getBlockState(pos).is(pos.equals(surface) ? Blocks.STONE : Blocks.AIR),
                    "overlay and every untouched AIR cell survive NBT");
        }
        System.out.println("PASS " + checks + " native AIR volume assertions");
    }
}
