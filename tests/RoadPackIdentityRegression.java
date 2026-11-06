import com.YLFXM.rp.crossroads.CustomRoadPack;
import com.YLFXM.rp.crossroads.road.*;
import com.minecolonies.api.colony.workorders.IWorkOrder;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import java.util.UUID;

/**
 * R10-fix1: the structure pack was renamed to {@code Crossroads} and the old
 * {@code Reconstruction Plan} spelling is gone for good. Only the stale client
 * directory is still deleted by that name; it must never be accepted as an
 * identity again, because a pre-rename save has no road data to attach it to
 * (its SavedData file was renamed too).
 */
public final class RoadPackIdentityRegression {
    private static int checks;
    private static void check(boolean condition,String name) { checks++; if(!condition) throw new AssertionError(name); }

    private static RoadPlan plan(String pack) {
        var p=new RoadPlan(UUID.randomUUID(),Level.OVERWORLD,new BlockPos(10,65,20),new BlockPos(10,65,19),
                Direction.SOUTH,false,pack,"street",4,WalkRange.of(1,2),1,4);
        p.addSegment(new RoadSegment(new BlockPos(5,64,20),10,Direction.Axis.X,0,SegmentKind.ROAD));
        p.setState(RoadState.PLANNED); return p;
    }

    private static IWorkOrder order(String pack,String path,WorkOrderType type) {
        return (IWorkOrder)java.lang.reflect.Proxy.newProxyInstance(RoadPackIdentityRegression.class.getClassLoader(),
                new Class[]{IWorkOrder.class},(p,m,a) -> switch(m.getName()) {
                    case "getStructurePack" -> pack; case "getStructurePath" -> path; case "getWorkOrderType" -> type;
                    default -> null;
                });
    }

    public static void main(String[] args) throws Exception {
        check(CustomRoadPack.isOurs(CustomRoadPack.PACK_NAME),"current pack name is ours");
        check(!CustomRoadPack.isOurs(CustomRoadPack.STALE_PACK_NAME),"the pre-rename name is foreign");
        check(!CustomRoadPack.isOurs("minecolonies"),"a style pack is not ours");
        check(!CustomRoadPack.isOurs(null),"null pack name is not ours");
        check(!CustomRoadPack.PACK_NAME.equals(CustomRoadPack.STALE_PACK_NAME),"the two names stay distinct");

        // Loading no longer rewrites a stored alias: a plan that still says the old
        // name stays untouched and therefore cannot generate blueprints.
        var tag=plan(CustomRoadPack.STALE_PACK_NAME).save();
        var loaded=RoadPlan.load(tag);
        check(CustomRoadPack.STALE_PACK_NAME.equals(loaded.pack()),"load leaves the stale alias untouched");
        check(CustomRoadPack.PACK_NAME.equals(plan(CustomRoadPack.PACK_NAME).save().getString("pack")),"a current plan keeps the current name");

        // Units, work orders and repair selections all compare one spelling now.
        check(RoadUnitLibrary.supports(CustomRoadPack.PACK_NAME,"street",4),"current name supports the bundled unit");
        check(!RoadUnitLibrary.supports(CustomRoadPack.STALE_PACK_NAME,"street",4),"the pre-rename name supports nothing");

        var p=plan(CustomRoadPack.PACK_NAME);
        check(RoadWorkOrders.matches(p,order(CustomRoadPack.PACK_NAME,RoadTemporaryPack.pathFor(p),WorkOrderType.BUILD)),"current build order matches its road");
        check(!RoadWorkOrders.matches(p,order(CustomRoadPack.STALE_PACK_NAME,RoadTemporaryPack.pathFor(p),WorkOrderType.BUILD)),"a pre-rename order no longer matches");
        check(!RoadWorkOrders.matches(p,order("unrelated",RoadTemporaryPack.pathFor(p),WorkOrderType.BUILD)),"foreign pack order is rejected");

        check(RoadRepairSelection.validate(CustomRoadPack.PACK_NAME,"generated/road.blueprint",new BlockPos(0,64,0),0,
                CustomRoadPack.PACK_NAME,"generated/road.blueprint",new BlockPos(0,64,0),0)==RoadRepairSelection.Mismatch.NONE,
                "current pack name passes repair selection");
        check(RoadRepairSelection.validate(CustomRoadPack.PACK_NAME,"generated/road.blueprint",new BlockPos(0,64,0),0,
                CustomRoadPack.STALE_PACK_NAME,"generated/road.blueprint",new BlockPos(0,64,0),0)==RoadRepairSelection.Mismatch.PACK,
                "the pre-rename name is a pack mismatch");
        check(RoadRepairSelection.validate(CustomRoadPack.PACK_NAME,"generated/road.blueprint",new BlockPos(0,64,0),0,
                "unrelated","generated/road.blueprint",new BlockPos(0,64,0),0)==RoadRepairSelection.Mismatch.PACK,
                "a foreign pack name is rejected");

        // R10-fix2: the pack version must follow the shipped blueprint only. A version
        // that also hashed generated/ moved on every road publish, and Structurize
        // answers a mismatch by dropping the client's copy — the pack then vanished
        // from the build tool's pack list.
        final java.nio.file.Path root=java.nio.file.Files.createTempDirectory("rp-pack-version");
        java.nio.file.Files.createDirectories(root.resolve("initial"));
        java.nio.file.Files.writeString(root.resolve("initial").resolve("road.blueprint"),"initial-v1");
        final long base=CustomRoadPack.contentVersion(root);
        java.nio.file.Files.createDirectories(root.resolve("generated"));
        java.nio.file.Files.writeString(root.resolve("generated").resolve("road-aaaa.blueprint"),"road-a");
        check(CustomRoadPack.contentVersion(root)==base,"generated/ does not move the pack version");
        java.nio.file.Files.writeString(root.resolve("generated").resolve("road-bbbb.blueprint"),"road-b");
        check(CustomRoadPack.contentVersion(root)==base,"adding more generated blueprints does not move the pack version");
        java.nio.file.Files.delete(root.resolve("generated").resolve("road-aaaa.blueprint"));
        check(CustomRoadPack.contentVersion(root)==base,"deleting a generated blueprint does not move the pack version");
        java.nio.file.Files.writeString(root.resolve("initial").resolve("road.blueprint"),"initial-v2");
        check(CustomRoadPack.contentVersion(root)!=base,"changing the shipped blueprint moves the pack version");

        // R10-fix2: every gui xml label resolves through the current key prefix.
        final java.nio.file.Path gui=java.nio.file.Path.of("src/main/resources/assets/rp_crossroads/gui");
        final com.google.gson.JsonObject zh=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/assets/rp_crossroads/lang/zh_cn.json"))).getAsJsonObject();
        int labels=0;
        try(var files=java.nio.file.Files.list(gui)){
            for(final java.nio.file.Path file:files.toList()){
                if(!file.toString().endsWith(".xml")) continue;
                final String text=java.nio.file.Files.readString(file);
                var matcher=java.util.regex.Pattern.compile("\\$\\(([^)]+)\\)").matcher(text);
                while(matcher.find()){
                    final String key=matcher.group(1);
                    check(!key.startsWith("mcreconstruction."),"no stale key prefix in "+file.getFileName()+": "+key);
                    if(key.startsWith("gui.")) continue;
                    check(zh.has(key),"gui label key exists: "+key);
                    labels++;
                }
            }
        }
        check(labels>0,"gui xml labels were inspected");
        System.out.println("RoadPackIdentityRegression: "+checks+" checks passed");
    }
}
