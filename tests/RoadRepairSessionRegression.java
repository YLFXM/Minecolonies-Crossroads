import com.YLFXM.mcreconstruction.road.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import java.util.*;

public final class RoadRepairSessionRegression {
    private static int checks;
    private static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    private static RoadPlan plan(RoadState state){
        RoadPlan p=new RoadPlan(UUID.randomUUID(),Level.OVERWORLD,new BlockPos(10,65,20),new BlockPos(10,65,19),
                Direction.SOUTH,false,"minecolonies","street",4,WalkRange.of(1,2),1,4);
        p.addSegment(new RoadSegment(new BlockPos(5,64,20),6,Direction.Axis.X,0,SegmentKind.ROAD));
        p.setState(state);return p;
    }
    public static void main(String[] args){
        UUID owner=UUID.randomUUID();
        RoadPlan p=plan(RoadState.BUILT);p.setAllowBlueprintEdit(true);
        p.adoptCommittedRows(Map.of(7,CellType.STAIR_UP,14,CellType.STAIR_DOWN));
        p.setRowType(7,CellType.FLAT);p.setRowType(8,CellType.STAIR_DOWN);p.setLastDroppedRows(1);
        RoadRepairSession session=RoadRepairSession.prepare(p,owner,1234L);
        check(p.confirmedRowCount()==2 && p.editedRowCount()==2,"prepare leaves live tables intact");
        check(p.allowBlueprintEdit() && p.lastDroppedRows()==1,"prepare does not lock edit switch or clear dropped");
        check(session.target().confirmedRowCount()==1 && session.target().cellType(8)==CellType.STAIR_DOWN,"target merges pending and clips out-of-range rows");
        check(session.target().cellType(7)==CellType.FLAT && session.target().pendingRows().isEmpty(),"frozen target clears pending on copy only");
        check(session.target().id().equals(p.id()) && session.target().anchorPos().equals(p.anchorPos()),"target identity and anchor retained");
        check(session.previousState()==RoadState.BUILT && session.changedAt()==1234L,"original state and timestamp retained");
        check(session.ownedBy(owner,session.token()),"owner and token accepted");
        check(!session.ownedBy(UUID.randomUUID(),session.token()),"other player rejected");
        check(!session.ownedBy(owner,UUID.randomUUID()),"stale session rejected");
        try{session.commitRows(p);throw new AssertionError("unconfirmed commit");}catch(IllegalStateException expected){checks++;}
        p.setRepairSession(session);
        check(p.isRepairBusy() && !RoadRules.canEdit(p) && !RoadRules.canRepair(p) && !RoadRules.canToggleEdit(p),"all repair editing paths locked");
        var sync=p.saveForSync();
        check(sync.getBoolean("repairBusy") && !sync.contains("repairSession"),"sync exposes lock without frozen private target");
        RoadPlan client=RoadPlan.load(sync);check(client.isRepairBusy() && !RoadRules.canEdit(client),"client lock policy matches server");
        RoadPlan restored=RoadPlan.load(p.save());
        check(restored.repairSession()!=null && restored.isRepairBusy(),"operation survives save/load");
        check(restored.repairSession().token().equals(session.token()) && restored.repairSession().owner().equals(owner),"persist token and owner");
        check(restored.repairSession().target().confirmedRowCount()==1,"persist frozen proposal");
        check(!restored.repairSession().target().isRepairBusy(),"target contains no recursive operation");
        RoadPlan changed=session.target();changed.setRowType(8,CellType.FLAT);
        check(session.target().cellType(8)==CellType.STAIR_DOWN,"returned targets cannot mutate frozen operation");
        check(!session.start(RoadRepairSession.Phase.PREPARED,2000L),"prepared is not a placement confirmation");
        check(session.start(RoadRepairSession.Phase.NATIVE,2000L),"first native confirmation accepted");
        check(!session.start(RoadRepairSession.Phase.SURVIVAL,3000L),"replay cannot change placement mode");
        check(session.changedAt()==2000L && !session.nativeDone(),"native start is not completion");
        session.finishNative();check(session.nativeDone(),"native queue completion recorded");
        RoadRepairSession crash=RoadRepairSession.load(session.save());
        check(crash.phase()==RoadRepairSession.Phase.NATIVE && !crash.nativeDone(),"restart cannot trust in-memory queue completion");
        check(!crash.orderObserved(),"no worker progress fabricated");
        crash.commitRows(p);p.setRepairSession(null);
        check(p.confirmedRowCount()==1 && p.cellType(8)==CellType.STAIR_DOWN,"completion commits exactly frozen rows");
        check(p.pendingRows().isEmpty() && p.lastDroppedRows()==0 && !p.isRepairBusy(),"completion clears diff and releases derived lock");
        RoadPlan cancel=plan(RoadState.PLANNED);cancel.setRowType(6,CellType.STAIR_UP);
        RoadRepairSession abandoned=RoadRepairSession.prepare(cancel,owner,4000L);cancel.setRepairSession(abandoned);cancel.setRepairSession(null);
        check(cancel.editedRowCount()==1 && cancel.confirmedRowCount()==0,"unconfirmed cancellation preserves pending diff");
        RoadRepairSession worker=RoadRepairSession.prepare(cancel,owner,5000L);worker.start(RoadRepairSession.Phase.SURVIVAL,6000L);worker.observeOrder();
        RoadRepairSession restart=RoadRepairSession.load(worker.save());
        check(restart.phase()==RoadRepairSession.Phase.SURVIVAL && restart.orderObserved(),"worker observation survives restart");
        check(cancel.editedRowCount()==1,"worker session does not precommit pending diff");
        for(RoadState state:RoadState.values()){
            RoadPlan road=plan(state);
            check(RoadRules.canRepair(road)==(state==RoadState.PLANNED || state==RoadState.BUILT),"repair state boundary "+state);
            road.setRepairSession(RoadRepairSession.prepare(road,owner,0));
            check(!RoadRules.canCreate(road) && !RoadRules.canRepair(road),"session excludes duplicate entry "+state);
        }
        var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY);
        try {
            var data=new net.minecraft.nbt.CompoundTag();data.putString("test","frozen");
            var open=new com.YLFXM.mcreconstruction.network.OpenBuildToolPayload(p.id(),"generated/test.blueprint",data,123,p.anchorPos(),session.token());
            com.YLFXM.mcreconstruction.network.OpenBuildToolPayload.CODEC.encode(buffer,open);
            var opened=com.YLFXM.mcreconstruction.network.OpenBuildToolPayload.CODEC.decode(buffer);
            check(open.equals(opened),"open packet carries frozen blueprint and token");
            check(!buffer.isReadable(),"open packet decoder consumes all fields");
            buffer.clear();
            var closed=new com.YLFXM.mcreconstruction.network.NativeBuildClosedPayload(p.id(),session.token());
            com.YLFXM.mcreconstruction.network.NativeBuildClosedPayload.CODEC.encode(buffer,closed);
            check(closed.equals(com.YLFXM.mcreconstruction.network.NativeBuildClosedPayload.CODEC.decode(buffer)),"close packet carries matching session identity");
            check(!buffer.isReadable(),"close packet decoder consumes all fields");
            buffer.clear();
            var confirm=new com.YLFXM.mcreconstruction.network.ConfirmRoadRepairPayload(p.id(),session.token(),2,"minecolonies","Reconstruction Plan","generated/test.blueprint",p.anchorPos(),0);
            com.YLFXM.mcreconstruction.network.ConfirmRoadRepairPayload.CODEC.encode(buffer,confirm);
            check(confirm.equals(com.YLFXM.mcreconstruction.network.ConfirmRoadRepairPayload.CODEC.decode(buffer)),"confirm packet preserves mode, pack, path and anchor");
            check(!buffer.isReadable(),"confirm packet decoder consumes all fields");
            check(new com.YLFXM.mcreconstruction.network.NativeBuildClosedPayload(p.id()).repairToken().equals(RoadNativeBuildTool.NO_SESSION),"legacy close constructor has no authorized session");
            check(new com.YLFXM.mcreconstruction.network.OpenBuildToolPayload(p.id(),"generated/test.blueprint",data,1,p.anchorPos()).repairToken().equals(RoadNativeBuildTool.NO_SESSION),"legacy open constructor supplies no authorization for first construction");
        } finally { buffer.release(); }
        System.out.println("PASS "+checks+" repair session assertions");
    }
}
