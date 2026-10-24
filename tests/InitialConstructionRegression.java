import com.YLFXM.mcreconstruction.road.*;
import com.YLFXM.mcreconstruction.client.RoadSelectionClient;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import java.util.*;

/** Rebinding, explicit clearing and the first-build transaction use real production models. */
public final class InitialConstructionRegression {
    private static int checks;
    private static void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
    private static RoadPlan road() {
        return new RoadPlan(UUID.randomUUID(), Level.OVERWORLD, new BlockPos(10,65,20),
                new BlockPos(10,65,19), Direction.SOUTH, false, "minecolonies", "street",4,WalkRange.of(1,2),1,4);
    }
    public static void main(String[] args) {
        final BlockPos a=new BlockPos(5,64,20), b=new BlockPos(15,64,20);
        final RoadPlan first=road(), other=road();
        RoadSelectionClient.clearAll();
        RoadSelectionClient.bindTo(first); RoadSelectionClient.setA(a);
        RoadSelectionClient.bindTo(other);
        check(!RoadSelectionClient.hasBoth() && RoadSelectionClient.a().equals(BlockPos.ZERO),"new road does not inherit partial selection");
        RoadSelectionClient.setB(b);
        RoadSelectionClient.bindTo(first);
        check(RoadSelectionClient.a().equals(a) && RoadSelectionClient.b().equals(BlockPos.ZERO),"partial draft A restored after rebinding");
        RoadSelectionClient.setB(b); RoadSelectionClient.setMirror(true);
        RoadSelectionClient.bindTo(other); RoadSelectionClient.bindTo(first);
        check(RoadSelectionClient.hasBoth() && RoadSelectionClient.a().equals(a) && RoadSelectionClient.b().equals(b),"raw DRAFT endpoints survive switching without server geometry");
        check(RoadSelectionClient.mirror(),"draft mirror restored together with endpoints");
        RoadSelectionClient.clearAll(); first.setEndpoints(a,b); first.setMirror(true);
        RoadSelectionClient.bindTo(first);
        check(RoadSelectionClient.hasBoth() && RoadSelectionClient.mirror(),"server-saved DRAFT geometry restores on first bind");
        RoadSelectionClient.clear(); RoadSelectionClient.bindTo(first);
        check(!RoadSelectionClient.hasBoth(),"Clear is not undone on next preview tick");
        RoadSelectionClient.bindTo(other); RoadSelectionClient.bindTo(first);
        check(!RoadSelectionClient.hasBoth(),"Clear is not undone by rebinding saved server geometry");
        RoadSelectionClient.setA(a); RoadSelectionClient.setB(b);
        RoadSelectionClient.bindTo(other); RoadSelectionClient.removed(first.id());
        first.setEndpoints(null,null); RoadSelectionClient.bindTo(first);
        check(!RoadSelectionClient.hasBoth(),"deleting inactive road discards its cached selection");
        RoadSelectionClient.setA(a); RoadSelectionClient.setB(b);
        RoadSelectionClient.retain(List.of(first,other), Set.of(other.id()),"minecraft:overworld");
        check(RoadSelectionClient.roadId()==null && !RoadSelectionClient.hasBoth(),"permission loss invalidates active selection");
        RoadSelectionClient.bindTo(first);
        check(!RoadSelectionClient.hasBoth(),"permission loss also prunes saved endpoints");
        RoadSelectionClient.setA(a); RoadSelectionClient.setB(b);
        RoadSelectionClient.clearAll(); RoadSelectionClient.bindTo(first);
        check(!RoadSelectionClient.hasBoth(),"logout clears local drafts");
        RoadSelectionClient.setA(a); RoadSelectionClient.setB(b);
        RoadSelectionClient.retain(List.of(other),Set.of(other.id()),"minecraft:overworld");
        check(RoadSelectionClient.roadId()==null,"complete server index evicts missing road");
        RoadSelectionClient.clearAll();

        first.setState(RoadState.PLANNED); first.setEndpoints(a,b);
        first.addSegment(new RoadSegment(a,11,Direction.Axis.X,0,SegmentKind.ROAD));
        first.setRowType(8,CellType.STAIR_UP);
        final UUID owner=UUID.randomUUID();
        final RoadRepairSession initial=RoadRepairSession.prepareInitial(first,owner,123L);
        check(initial.previousState()==RoadState.DRAFT,"unconfirmed first build returns to DRAFT");
        check(initial.phase()==RoadRepairSession.Phase.PREPARED && !initial.token().equals(RoadNativeBuildTool.NO_SESSION),"first build requires a real confirmation token");
        check(first.editedRowCount()==1 && first.confirmedRowCount()==0,"opening first build does not commit edits");
        check(initial.target().cellType(8)==CellType.STAIR_UP && initial.target().pendingRows().isEmpty(),"first build freezes effective rows");
        first.setRepairSession(initial);
        check(!RoadRules.canEdit(first) && !RoadRules.canCreate(first) && !RoadRules.canRepair(first),"first-build session blocks all concurrent edit entries");
        final RoadPlan restored=RoadPlan.load(first.save());
        check(restored.repairSession().previousState()==RoadState.DRAFT && restored.repairSession().ownedBy(owner,initial.token()),"restart preserves first-build origin and authorization");
        check(!initial.ownedBy(UUID.randomUUID(),initial.token()) && !initial.ownedBy(owner,UUID.randomUUID()),"foreign player and stale confirmations are rejected");
        try { initial.commitRows(first); throw new AssertionError("unconfirmed first build committed rows"); }
        catch (IllegalStateException expected) { checks++; }
        check(initial.start(RoadRepairSession.Phase.SURVIVAL,456L),"first accepted mode starts native worker session");
        check(!initial.start(RoadRepairSession.Phase.NATIVE,789L),"replayed confirmation cannot start a second construction");
        check(first.editedRowCount()==1,"confirming construction still does not commit edits");
        initial.observeOrder(); final RoadRepairSession worker=RoadRepairSession.load(initial.save());
        check(worker.orderObserved() && worker.phase()==RoadRepairSession.Phase.SURVIVAL,"initial worker session survives restart");
        worker.commitRows(first); first.setRepairSession(null);
        check(first.editedRowCount()==0 && first.cellType(8)==CellType.STAIR_UP,"successful first construction commits the exact frozen rows");
        check(RoadRepairSelection.validate("Reconstruction Plan","generated/first.blueprint",first.anchorPos(),0,
                "Reconstruction Plan","generated/wrong.blueprint",first.anchorPos(),0)==RoadRepairSelection.Mismatch.PATH,"wrong initial blueprint rejected even in the same pack");
        check(RoadRepairSelection.validate("Reconstruction Plan","generated/first.blueprint",first.anchorPos(),0,
                "Reconstruction Plan","generated/first.blueprint",first.anchorPos().east(),0)==RoadRepairSelection.Mismatch.ANCHOR,"moved initial blueprint rejected at confirmation");
        System.out.println("PASS "+checks+" initial construction assertions");
    }
}
