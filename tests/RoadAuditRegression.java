import com.YLFXM.mcreconstruction.road.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import java.util.UUID;
import java.util.Map;

public class RoadAuditRegression {
    private static int checks;
    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
    private static RoadPlan plan(int first, int length) {
        RoadPlan p = new RoadPlan(UUID.randomUUID(), Level.OVERWORLD, new BlockPos(10,65,20),
                new BlockPos(10,65,19), Direction.SOUTH, false, "minecolonies", "street",
                4, WalkRange.of(0,2), 1, 4);
        p.addSegment(new RoadSegment(new BlockPos(first,64,20), length, Direction.Axis.X, 0, SegmentKind.ROAD));
        return p;
    }
    private static com.minecolonies.api.colony.workorders.IWorkOrder order(String pack, String path) {
        return (com.minecolonies.api.colony.workorders.IWorkOrder) java.lang.reflect.Proxy.newProxyInstance(
                RoadAuditRegression.class.getClassLoader(),
                new Class[]{com.minecolonies.api.colony.workorders.IWorkOrder.class},
                (proxy,method,args) -> switch(method.getName()) {
                    case "getStructurePath" -> path;
                    case "getStructurePack" -> pack;
                    default -> null;
                });
    }
    public static void main(String[] args) {
        for (RoadState state : RoadState.values()) {
            for (boolean edit : new boolean[]{false,true}) {
                RoadPlan p=plan(5,11); p.setState(state); p.setAllowBlueprintEdit(edit);
                boolean editable=state==RoadState.DRAFT || state==RoadState.PLANNED || state==RoadState.BUILT && edit;
                check(RoadRules.canEdit(p)==editable,"edit state boundary "+state+edit);
                check(RoadRules.canCreate(p)==(state==RoadState.DRAFT),"create DRAFT only "+state);
                if(state==RoadState.BUILDING || state==RoadState.REMOVING || state==RoadState.REMOVED) {
                    check(!RoadRules.canRepair(p) && !RoadRules.canToggleEdit(p),"construction exclusivity "+state);
                }
            }
        }
        UUID owner=UUID.randomUUID();
        RoadPlan old=plan(5,11); old.setCreator(owner); old.setDisplayName("Colony - River Road"); old.setRoadName("River Road");
        old.setRowType(6,CellType.STAIR_UP); old.setRowType(14,CellType.STAIR_DOWN);
        check(old.confirmedRowCount()==0 && old.editedRowCount()==2,"empty committed table fixture");
        RoadPlan draft=plan(5,11); RoadReplanning.inherit(old,draft,UUID.randomUUID(),true);
        check(draft.state()==RoadState.DRAFT && RoadRules.canCreate(draft),"settings do not consume draft");
        old.setState(RoadState.PLANNED);
        RoadPlan small=plan(5,6); RoadReplanning.inherit(old,small,UUID.randomUUID(),false);
        check(small.editedRowCount()==1 && !small.pendingRows().containsKey(14),"clip pending with empty committed table");
        check(small.id().equals(old.id()) && small.creator().equals(owner),"identity retained");
        check(small.roadName().equals(old.roadName()) && small.walkRange().equals(old.walkRange()),"name and asymmetric walk interval retained");
        RoadPlan wide=plan(5,11); RoadReplanning.inherit(small,wide,UUID.randomUUID(),false);
        check(wide.editedRowCount()==1 && wide.cellType(14)==CellType.FLAT,"discarded pending edit stays discarded");
        old.clearRowOverrides(); old.adoptCommittedRows(Map.of(14,CellType.STAIR_UP));
        RoadPlan shrink=plan(5,6); RoadReplanning.inherit(old,shrink,owner,false);
        check(shrink.lastDroppedRows()==1 && shrink.confirmedRowCount()==1,"shrink preserves committed row until Repair");
        RoadPlan restore=plan(5,11); RoadReplanning.inherit(shrink,restore,owner,false);
        check(restore.lastDroppedRows()==0 && restore.cellType(14)==CellType.STAIR_UP,"reextend restores committed row");
        shrink.applyPendingRows(); check(shrink.confirmedRowCount()==0,"Repair prunes committed row");
        try { RoadReplanning.validate(old,new BlockPos(10,64,15),new BlockPos(10,64,25)); throw new AssertionError("axis change accepted"); }
        catch(IllegalArgumentException expected) { checks++; }
        try { RoadReplanning.validate(old,new BlockPos(5,64,21),new BlockPos(15,64,21)); throw new AssertionError("sideways change accepted"); }
        catch(IllegalArgumentException expected) { checks++; }
        RoadReplanning.validate(old,new BlockPos(4,0,20),new BlockPos(16,99,20)); checks++;
        BlockPos[] oriented=RoadReplanning.orient(old,new BlockPos(15,64,20),new BlockPos(5,64,20));
        check(oriented[0].getX()==5,"endpoint order preserved by anchor side");
        String nativePath=RoadTemporaryPack.pathFor(old);
        check(RoadWorkOrders.matches(old,order(RoadTemporaryPack.PACK_NAME,nativePath)),"exact native order matches");
        check(!RoadWorkOrders.matches(old,order("wrong-pack",nativePath)),"same path different pack rejected");
        check(!RoadWorkOrders.matches(old,order(RoadTemporaryPack.PACK_NAME,"other/"+nativePath)),"substring path rejected");
        check(RoadWorkOrders.matches(old,order("legacy","mcreconstruction:road/"+old.id())),"legacy UUID supported");
        check(RoadWorkOrders.matches(old,order("legacy","mcreconstruction:road/"+old.displayName())),"legacy name supported");
        check(!RoadWorkOrders.matches(old,order("legacy","mcreconstruction:road/"+UUID.randomUUID())),"other road rejected");
        System.out.println("PASS "+checks+" regression assertions");
    }
}
