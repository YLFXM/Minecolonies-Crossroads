import java.util.*;
import com.YLFXM.mcreconstruction.road.*;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import net.minecraft.nbt.CompoundTag;

/** Reproductions of round-one candidate, bridge, public-lamp and scoped-consent defects. */
public final class RoadStage7Round1Regression {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static RoadPlan road(Direction.Axis axis,int x,int y,int z,int side) {
        var p=new RoadPlan(UUID.randomUUID(),Level.OVERWORLD,new BlockPos(x,y,z),new BlockPos(x,y,z-1),Direction.SOUTH,false,"minecolonies","street",4,WalkRange.of(1,2),side,4);
        p.addSegment(new RoadSegment(new BlockPos(axis==Direction.Axis.X ? -20 : x,y-1,axis==Direction.Axis.Z ? -20 : z),51,axis,0,SegmentKind.ROAD));
        p.setEndpoints(new BlockPos(axis==Direction.Axis.X ? -20 : x,0,axis==Direction.Axis.Z ? -20 : z),new BlockPos(axis==Direction.Axis.X ? 30 : x,0,axis==Direction.Axis.Z ? 30 : z));return p;
    }
    private static RoadSlopeSolver.Profile bridge(int length,int anchor,int first,int last,int height,Integer selected) {
        int[] values=new int[length];Arrays.fill(values,height);CellType[] rows=new CellType[length];Arrays.fill(rows,CellType.FLAT);
        return bridge(new RoadSlopeSolver.Profile(values,rows),anchor,first,last,64,64,1,selected);
    }
    private static RoadSlopeSolver.Profile bridge(RoadSlopeSolver.Profile base,int anchor,int first,int last,int low,int high,int order,Integer selected) {
        Map<Integer,Integer> pins=new HashMap<>(),lower=new HashMap<>(),upper=new HashMap<>();Map<Integer,CellType> manual=new HashMap<>();
        RoadBridgeConstraints.add(base,anchor,first,last,low,high,order,selected,pins,lower,upper,manual);
        return RoadSlopeSolver.balance(RoadBridgeConstraints.prefer(base,anchor,base.heights()[anchor],pins),anchor,base.heights()[anchor],pins,Set.of(),manual,lower,upper,-62,310);
    }
    public static void main(String[] args) {
        RoadPlan initial=road(Direction.Axis.X,0,65,0,1);initial.clearSegments();initial.setEndpoints(null,null);
        RoadPlan candidate=road(Direction.Axis.X,0,65,0,1);candidate.setId(initial.id());candidate.setRowType(8,CellType.STAIR_UP);
        check(RoadReplanning.acceptDraftRows(initial,candidate),"S7 valid draft candidate is stored on server");
        check(initial.state()==RoadState.DRAFT && initial.hasEndpoints() && initial.length()==51,"candidate is referenceable without consuming initial planning");
        check(initial.rowOverrides().isEmpty() && initial.pendingRows().equals(candidate.pendingRows()),"candidate storage never applies edited rows");
        check(!RoadReplanning.acceptDraftRows(initial,candidate),"same candidate does not create an endless sync/request loop");
        var loaded=RoadPlan.load(initial.save());check(loaded.segments().equals(initial.segments()) && loaded.hasEndpoints(),"candidate survives restart");
        var stable=RoadCrossingConsent.intent(initial);initial.planning().issue("diagnostic");initial.planning().crossingIssue("warning");initial.setState(RoadState.PLANNED);
        check(stable.equals(RoadCrossingConsent.intent(initial)),"consent identity ignores UI diagnostics and lifecycle changes");
        initial.planning().relationshipKey("new-neighbor-cache");check(stable.equals(RoadCrossingConsent.intent(initial)),"reference-cache bookkeeping cannot recursively invalidate geometry consent");
        initial.setLampSpacing(8);check(!stable.equals(RoadCrossingConsent.intent(initial)),"lamp changes invalidate consent scope");initial.setLampSpacing(4);
        initial.setAnchorPos(new BlockPos(0,66,0));check(!stable.equals(RoadCrossingConsent.intent(initial)),"anchor changes invalidate consent scope");initial.setAnchorPos(new BlockPos(0,65,0));
        initial.setRowType(9,CellType.STAIR_DOWN);check(!stable.equals(RoadCrossingConsent.intent(initial)),"row geometry changes invalidate consent scope");
        var owner=UUID.randomUUID();var session=RoadRepairSession.prepare(initial,owner,1);var grants=new CompoundTag();var receipt=new CompoundTag();receipt.putUUID("actor",owner);receipt.putString("work","proof");grants.put("peer",receipt);session.setCrossingGrants(grants);
        grants.getCompound("peer").putString("work","tampered");check(session.crossingGrants().getCompound("peer").getString("work").equals("proof"),"authorization owns a defensive receipt copy");
        session.start(RoadRepairSession.Phase.NATIVE,2);var restored=RoadRepairSession.load(session.save());check(restored.crossingGrants().equals(session.crossingGrants()),"scoped receipts survive frozen-session restart");
        check(restored.workDigest().equals(session.workDigest()),"frozen work digest is stable across restart and defensive copies");
        var retried=RoadRepairSession.retry(restored,owner,3);var priorScope=session.crossingGrants().getCompound("peer");var retryScope=retried.crossingGrants().getCompound("peer");
        check(retryScope.getUUID("session").equals(retried.token()),"frozen retry receipt binds to the fresh native session token");
        priorScope.remove("session");retryScope.remove("session");check(priorScope.equals(retryScope),"retry retains exactly the prior scope, no global grant");
        initial.setRepairSession(session);check(!initial.saveForSync().contains("repairSession"),"private authorization proofs never enter the client road index");
        initial.planning().spanFloor(candidate.id(),70);check(RoadPlan.load(initial.save()).planning().spanFloor(candidate.id())==70,"independent layered height is persisted");
        check(candidate.planning().spanFloor(initial.id())==null,"own layered height does not become the opposite road's shared height");
        var pending=RoadCrossingConsent.Decision.PENDING;
        check(RoadCrossingConsent.timeout(pending,119999,120000,true,true)==pending,"online consent waits the full 120 seconds");
        check(RoadCrossingConsent.timeout(pending,120000,120000,true,true)==RoadCrossingConsent.Decision.APPROVED,"valid delivered consent defaults to approval at timeout");
        check(RoadCrossingConsent.timeout(pending,120001,120000,true,false)==RoadCrossingConsent.Decision.INVALID,"offline recipient never silently approves");
        check(RoadCrossingConsent.timeout(pending,120001,120000,false,true)==RoadCrossingConsent.Decision.INVALID,"changed proposal invalidates timeout");
        check(RoadCrossingConsent.timeout(RoadCrossingConsent.Decision.REJECTED,999999,120000,true,true)==RoadCrossingConsent.Decision.REJECTED,"explicit refusal is never replaced by timeout approval");
        check(RoadCrossingConsent.priority(true,0,true)>RoadCrossingConsent.priority(true,1,true) && RoadCrossingConsent.priority(true,1,true)>RoadCrossingConsent.priority(false,-1,true),"online choice prioritizes lord over officer over wilderness owner");
        check(RoadCrossingConsent.priority(true,0,false)==0,"rank label without management permission is ineligible");
        check(RoadCrossingConsent.nativeContinuation(false,true,2,2),"accepted native creative-OP work can continue during logout");
        check(!RoadCrossingConsent.nativeContinuation(false,true,1,2),"offline continuation rechecks current OP permission level");
        check(!RoadCrossingConsent.nativeContinuation(false,false,4,2),"offline continuation never invents an original creative bypass");
        check(!RoadCrossingConsent.nativeContinuation(true,true,4,2),"online players must pass the live native permission check");
        var x=road(Direction.Axis.X,0,65,0,1);var z=road(Direction.Axis.Z,10,65,-15,1);x.setId(new UUID(0,1));z.setId(new UUID(0,2));var same=new RoadPlanningState.Crossing(false,0);
        check(RoadCrossings.sharedLampRow(x,z,same)==10 && RoadCrossings.sharedLampRow(z,x,same)==null,"one public lamp at the lamp-side corner with deterministic ownership");
        x.setLampSpacing(1);check(RoadCrossings.sharedLampRow(x,z,same)==null && RoadCrossings.sharedLampRow(z,x,same)==0,"public lamp respects disabled lighting on one side");
        z.setLampSpacing(1);check(RoadCrossings.sharedLampRow(z,x,same)==null,"both disabled roads generate no public lamp");
        x.setLampSpacing(4);z.setLampSpacing(4);
        for(boolean mx:new boolean[]{false,true})for(boolean mz:new boolean[]{false,true}) {
            x.setMirror(mx);z.setMirror(mz);
            check(RoadCrossings.sharedLampRow(x,z,same)==10 && RoadCrossings.sharedLampRow(z,x,same)==null,"public lamp ownership is stable under all mirror combinations");
        }
        check(RoadCrossings.sharedLampRow(x,z,new RoadPlanningState.Crossing(true,1))==null,"layered crossings do not acquire a common lamp");
        x.setMirror(false);check(RoadCrossings.deckCell(x,new BlockPos(10,64,3)),"solid-fill kerb is part of the protected deck");
        check(!RoadCrossings.deckCell(x,new BlockPos(10,63,3)),"foundation below the deck is classified separately");
        var noWalk=new RoadCrossings.Neighbor(z,z,null,RoadCrossingGeometry.body(x),null,same);RoadCrossings.validate(x,noWalk);check(true,"edge contact alone does not block construction as a junction");
        var shortTail=bridge(21,1,10,13,65,null);
        check(shortTail.heights()[10]==68 && shortTail.heights()[20]==68,"S13 short three-row return tail stays raised to endpoint");
        check(shortTail.heights()[1]==65 && shortTail.rows()[1]==CellType.FLAT,"bridge never moves fixed anchor");
        for(int i=10;i<=13;i++)check(shortTail.rows()[i]==CellType.FLAT,"new bridge lift does not insert unsupported steps in the span");
        var longTail=bridge(31,1,10,13,65,null);check(longTail.heights()[17]==65 && longTail.heights()[10]==68,"long supported approach returns to original curve");
        var again=bridge(longTail,1,10,13,64,64,1,null);
        check(Arrays.equals(longTail.heights(),again.heights()),"already-raised bridge does not receive another forced +1");
        var manualHeight=bridge(31,1,10,13,65,69);check(manualHeight.heights()[10]==70,"layered own-height selection overrides automatic height");
        int[] flatTerrain=new int[31];Arrays.fill(flatTerrain,65);CellType[] flatRows=new CellType[31];Arrays.fill(flatRows,CellType.FLAT);
        var base=new RoadSlopeSolver.Profile(flatTerrain,flatRows);Map<Integer,Integer> multiple=new HashMap<>(),lower=new HashMap<>(),upper=new HashMap<>();Map<Integer,CellType> manual=new HashMap<>();
        RoadBridgeConstraints.add(base,1,10,12,64,64,1,67,multiple,lower,upper,manual);
        RoadBridgeConstraints.add(base,1,18,20,64,64,1,68,multiple,lower,upper,manual);
        var smooth=RoadSlopeSolver.balance(RoadBridgeConstraints.prefer(base,1,65,multiple),1,65,multiple,Set.of(),manual,lower,upper,-62,310);
        check(smooth.heights()[10]==68 && smooth.heights()[18]==69,"multiple raised spans retain independent reachable heights");
        check(Arrays.stream(smooth.heights(),12,19).min().orElse(0)>=68,"short gap between raised crossings does not force a dip to anchor terrain");
        try {bridge(31,1,10,13,65,66);throw new AssertionError("unsafe selected clearance accepted");}catch(IllegalArgumentException expected){checks++;}
        try {bridge(12,1,2,4,65,90);throw new AssertionError("bridge moved anchor to reach impossible height");}catch(IllegalArgumentException expected){checks++;}
        System.out.println("PASS "+checks+" stage7 round1/consent assertions");
    }
}
