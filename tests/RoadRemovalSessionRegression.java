import com.YLFXM.mcreconstruction.road.*;
import com.YLFXM.mcreconstruction.network.*;
import com.minecolonies.api.colony.workorders.*;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import java.util.*;

public final class RoadRemovalSessionRegression {
    private static int checks;
    private static void check(boolean condition,String name) { checks++; if(!condition) throw new AssertionError(name); }
    private static RoadPlan plan() {
        var p=new RoadPlan(UUID.randomUUID(),Level.OVERWORLD,new BlockPos(10,65,20),new BlockPos(10,65,19),
                Direction.SOUTH,false,"minecolonies","street",4,WalkRange.of(1,2),1,4);
        p.addSegment(new RoadSegment(new BlockPos(5,64,20),10,Direction.Axis.X,0,SegmentKind.ROAD));
        p.setState(RoadState.PLANNED); return p;
    }
    private static IWorkOrder order(String pack,String path,WorkOrderType type) {
        return (IWorkOrder)java.lang.reflect.Proxy.newProxyInstance(RoadRemovalSessionRegression.class.getClassLoader(),
                new Class[]{IWorkOrder.class},(p,m,a) -> switch(m.getName()) {
                    case "getStructurePack" -> pack; case "getStructurePath" -> path; case "getWorkOrderType" -> type;
                    default -> null;
                });
    }
    public static void main(String[] args) {
        var owner=UUID.randomUUID(); var p=plan();
        p.adoptCommittedRows(Map.of(7,CellType.STAIR_UP));
        p.setRowType(8,CellType.STAIR_DOWN); p.setLastDroppedRows(2); p.setAllowBlueprintEdit(true);
        var session=new RoadRemovalSession(owner,false,42); session.setOrderId(17);
        var restored=RoadRemovalSession.load(session.save());
        check(restored.token().equals(session.token()) && restored.owner().equals(owner),"persist operation and owner identity");
        check(restored.colonyId()==42 && restored.orderId()==17 && !restored.instant(),"persist worker destination and native order");
        check(restored.path(p.id()).equals(session.path(p.id())),"restart uses identical frozen path");
        check(!new RoadRemovalSession(owner,false,42).path(p.id()).equals(session.path(p.id())),"retry cannot reuse stale token");
        check(!session.path(UUID.randomUUID()).equals(session.path(p.id())),"independent roads do not share demolition files");
        p.setRemovalSession(session); p.setState(RoadState.REMOVING);
        var saved=RoadPlan.load(p.save());
        check(saved.removalSession().token().equals(session.token()) && saved.state()==RoadState.REMOVING,"road persists removal phase");
        check(saved.editedRowCount()==1 && saved.confirmedRowCount()==1 && saved.lastDroppedRows()==2,"demolition preserves proposal and committed rows");
        check(saved.allowBlueprintEdit(),"demolition does not silently change edit preference");
        check(!p.saveForSync().contains("removalSession"),"private removal owner and destination not sent in index");
        check(RoadPlan.load(p.saveForSync()).state()==RoadState.REMOVING,"client gets authoritative removal state");
        check(!RoadRules.canEdit(p) && !RoadRules.canCreate(p) && !RoadRules.canRepair(p) && !RoadRules.canToggleEdit(p),"all construction entries locked while removing");
        check(!RoadRules.canRemove(p) && !RoadRules.canPublish(p),"no duplicate demolition or normal publication");
        var match=order(RoadTemporaryPack.PACK_NAME,session.path(p.id()),WorkOrderType.REMOVE);
        check(RoadNativeRemoval.matches(p,match) && RoadWorkOrders.matches(p,match),"frozen demolition order found for cleanup");
        check(!RoadNativeRemoval.matches(p,order(RoadTemporaryPack.PACK_NAME,session.path(p.id()),WorkOrderType.BUILD)),"build order cannot finish removal");
        check(!RoadNativeRemoval.matches(p,order("foreign",session.path(p.id()),WorkOrderType.REMOVE)),"different pack cannot finish removal");
        check(!RoadNativeRemoval.matches(p,order(RoadTemporaryPack.PACK_NAME,new RoadRemovalSession(owner,false,42).path(p.id()),WorkOrderType.REMOVE)),"stale demolition path rejected");
        check(!RoadNativeRemoval.matches(p,order(RoadTemporaryPack.PACK_NAME,RoadTemporaryPack.pathFor(p),WorkOrderType.REMOVE)),"pending construction blueprint cannot stand in for applied demolition");
        p.setRemovalSession(null);p.setState(RoadState.PLANNED);
        check(p.editedRowCount()==1 && p.confirmedRowCount()==1 && p.lastDroppedRows()==2,"cancel preserves live data");
        check(RoadRules.canRemove(p) && RoadRules.canRepair(p),"cancel returns to retryable state");
        check(!RoadNativeRemoval.matches(p,match),"late order cannot revive cancelled operation");
        var instant=new RoadRemovalSession(owner,true,0);p.setRemovalSession(instant);
        check(RoadRemovalSession.load(instant.save()).instant(),"creative mode survives restart");
        check(!RoadNativeRemoval.matches(p,order(RoadTemporaryPack.PACK_NAME,instant.path(p.id()),WorkOrderType.REMOVE)),"worker cannot complete creative operation");
        var nativeOrder=com.minecolonies.core.colony.workorders.WorkOrderDecoration.create(
                WorkOrderType.REMOVE,RoadTemporaryPack.PACK_NAME,session.path(p.id()),"Road",p.anchorPos(),
                com.ldtteam.structurize.api.RotationMirror.NONE,1);
        var nativeManager=new com.minecolonies.core.colony.workorders.WorkManager(null);
        // A normal admission would dereference the colony to inspect world chunks.
        // This exercises the installed native trusted-admission path without a game.
        nativeManager.addWorkOrder(nativeOrder,true);
        check(nativeOrder.getID()>0 && nativeManager.getWorkOrder(nativeOrder.getID())==nativeOrder,"installed native admission assigns and stores an order without colony-boundary inspection");
        check(nativeOrder.getWorkOrderType()==WorkOrderType.REMOVE && nativeOrder.getTargetLevel()==0,"installed decoration factory retains REMOVE semantics");
        check(nativeOrder.getStructurePath().equals(session.path(p.id())),"native order retains frozen session file instead of construction blueprint");
        check(RoadNativeRemoval.withinRange(BlockPos.ZERO,List.of(new BlockPos(256,0,0)),256),"distance includes exact configured boundary");
        check(!RoadNativeRemoval.withinRange(BlockPos.ZERO,List.of(new BlockPos(0,0,0),new BlockPos(257,0,0)),256),"long road far end cannot bypass distance limit");
        check(!RoadNativeRemoval.withinRange(BlockPos.ZERO,List.of(new BlockPos(0,257,0)),256),"vertical separation counts toward worker travel limit");
        for(var state:RoadState.values()) {
            p.setRemovalSession(null);p.setState(state);
            check(RoadRules.canRemove(p)==(state!=RoadState.REMOVING && state!=RoadState.REMOVED),"removal state boundary "+state);
        }
        var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),RegistryAccess.EMPTY);
        try {
            for(boolean remove:new boolean[]{false,true}) for(boolean creative:new boolean[]{false,true}) {
                var bulk=new RoadBulkActionPayload(List.of(p.id()),remove,creative);
                RoadBulkActionPayload.CODEC.encode(buffer,bulk);
                check(bulk.equals(RoadBulkActionPayload.CODEC.decode(buffer)) && !buffer.isReadable(),"bulk action preserves release/worker/creative distinction");
                buffer.clear();
            }
            check(!new RoadBulkActionPayload(List.of(p.id()),true).instant(),"existing bulk constructor defaults to builder");
            for(var action:RoadControllerActionPayload.Action.values()) {
                var payload=new RoadControllerActionPayload(p.controllerPos(),action);
                RoadControllerActionPayload.CODEC.encode(buffer,payload);
                check(payload.equals(RoadControllerActionPayload.CODEC.decode(buffer)) && !buffer.isReadable(),"controller mode round trip "+action);
                buffer.clear();
            }
            buffer.writeBlockPos(p.controllerPos());buffer.writeByte(255);
            try { RoadControllerActionPayload.CODEC.decode(buffer);throw new AssertionError("invalid action accepted"); }
            catch(IllegalArgumentException expected) { checks++; }
            buffer.clear();buffer.writeVarInt(257);
            try { RoadBulkActionPayload.CODEC.decode(buffer);throw new AssertionError("oversize batch accepted"); }
            catch(IllegalArgumentException expected) { checks++; }
            buffer.clear();buffer.writeVarInt(-1);
            try { RoadBulkActionPayload.CODEC.decode(buffer);throw new AssertionError("negative batch accepted"); }
            catch(IllegalArgumentException expected) { checks++; }
        } finally { buffer.release(); }
        System.out.println("PASS "+checks+" removal session assertions");
    }
}
