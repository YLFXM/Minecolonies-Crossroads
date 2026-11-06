package com.YLFXM.rp.crossroads.road;

import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;

/** Round 6: row locks, their release rule, the override span and pair grants. */
public final class RoadStage7Round6Regression {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static RoadPlan road(Direction.Axis axis,int x,int z) {
        var p=new RoadPlan(UUID.randomUUID(),Level.OVERWORLD,new BlockPos(x,65,z),new BlockPos(x,65,z-1),Direction.SOUTH,false,"minecolonies","street",4,WalkRange.of(1,2),1,4);
        p.addSegment(new RoadSegment(new BlockPos(axis==Direction.Axis.X ? -20:x,64,axis==Direction.Axis.Z ? -20:z),51,axis,0,SegmentKind.ROAD));p.setTravelDir(1);
        p.setState(RoadState.BUILT);p.setAllowBlueprintEdit(true);return p;
    }
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());Bootstrap.bootStrap();
        var a=road(Direction.Axis.X,0,0);var b=road(Direction.Axis.Z,10,-15);
        var junction=new RoadJunctions.Junction(a.id(),b.id(),RoadCrossingGeometry.body(a).intersect(RoadCrossingGeometry.body(b)),
                new RoadPlanningState.Crossing(false,0,5,64),null,false,false,1);
        RoadLockReconcile.apply(a,List.of(junction),id -> id.equals(b.id()) ? b : null,id -> false);
        check(a.lockedRows().containsKey(RoadHeightProfile.anchorAlong(a)),"the anchor row is locked");
        check(a.lockedRows().entrySet().stream().anyMatch(lock -> lock.getKey()!=RoadHeightProfile.anchorAlong(a) && lock.getValue()==64),"a same-level junction locks its rows at the shared platform surface");
        check(a.lockedRows().keySet().stream().filter(row -> row!=RoadHeightProfile.anchorAlong(a)).count()>1,"every row of the junction span is locked");
        var saved=RoadPlan.load(a.save());
        check(saved.lockedRows().equals(a.lockedRows()),"locks survive a save/load round trip");
        int lockedRows=a.lockedRows().size();
        RoadLockReconcile.apply(a,List.of(),id -> id.equals(b.id()) ? b : null,id -> false);
        check(a.lockedRows().size()<lockedRows
                && a.lockedRows().keySet().stream().allMatch(row -> row==RoadHeightProfile.anchorAlong(a)),
                "removing the relation releases the rows immediately, back to automatic planning");
        check(!a.lockedRows().isEmpty() && a.lockedRows().size()==1,"only the anchor stays locked");

        var spanRoad=road(Direction.Axis.X,0,0);
        spanRoad.lock(0,64);spanRoad.lock(20,65);
        int[] span=RoadLockReconcile.span(spanRoad,8,12);
        check(span[0]==0 && span[1]==20,"the override span stops at the nearest locks on both sides");
        int[] open=RoadLockReconcile.span(spanRoad,-18,28);
        check(open[0]==spanRoad.minAlong() && open[1]==spanRoad.maxAlong(),"without nearby locks the span covers the whole road");

        var token=UUID.randomUUID();var issuer=UUID.randomUUID();var rect=RoadCrossingGeometry.body(a).intersect(RoadCrossingGeometry.body(b));
        var granted=new RoadJunctions.Junction(a.id(),b.id(),rect,new RoadPlanningState.Crossing(false,0,5,64),
                new RoadPlanningState.Grant(token,issuer,1234L,"abc"),false,false,2);
        var stored=RoadJunctions.loadAll(RoadJunctions.saveAll(List.of(granted))).values().iterator().next();
        var grant=stored.grant();
        check(grant!=null && grant.token().equals(token) && grant.issuer().equals(issuer) && grant.issuedAt()==1234L && grant.boundary().equals("abc"),
                "a pair grant survives a junction-table save/load round trip");
        var released=new RoadJunctions.Junction(a.id(),b.id(),rect,new RoadPlanningState.Crossing(false,0,5,64),null,false,false,3);
        check(RoadJunctions.loadAll(RoadJunctions.saveAll(List.of(released))).values().iterator().next().grant()==null,
                "releasing a pair grant removes it from the single record");
        var reset=RoadCrossingConsent.builtForNewGrant(true,true);
        check(!reset[0] && !reset[1],"a new grant on a fully built pair restarts both completion flags");
        var partial=RoadCrossingConsent.builtForNewGrant(true,false);
        check(partial[0] && !partial[1],"a half-built pair keeps its existing side on a new grant");
        a.clearRowOverrides();
        check(a.lockedRows().isEmpty(),"clearing a draft plan drops its locks");

        var idle=road(Direction.Axis.X,0,0);
        check(!RoadRules.busy(idle,RoadRules.EDIT) && !RoadRules.busy(idle,RoadRules.MUTATION),"an idle road is not busy");
        var sessionPlan=road(Direction.Axis.X,0,0);
        sessionPlan.setRepairSession(RoadRepairSession.prepare(sessionPlan,UUID.randomUUID(),1));
        check(RoadRules.busy(sessionPlan,RoadRules.EDIT) && RoadRules.busy(sessionPlan,RoadRules.PUBLISH),"an open session blocks edits and publishing");
        var building=road(Direction.Axis.X,0,0);building.setState(RoadState.BUILDING);
        check(RoadRules.busy(building,RoadRules.MUTATION) && !RoadRules.busy(building,RoadRules.EDIT),"building blocks mutation but not the edit mask");
        var removing=road(Direction.Axis.X,0,0);removing.setState(RoadState.REMOVING);
        check(RoadRules.busy(removing,RoadRules.MUTATION) && RoadRules.busy(removing,RoadRules.PUBLISH),"removing blocks mutation and publishing");

        var proposal=road(Direction.Axis.X,0,0);var applied=road(Direction.Axis.X,0,0);
        proposal.setState(RoadState.BUILDING);
        check(RoadBlueprintStore.referencePlan(proposal,applied)==proposal,"a building road contributes its proposal");
        check(!RoadBlueprintStore.referenceIsApplied(proposal,true),"a building road is not applied");
        proposal.setState(RoadState.BUILT);
        check(RoadBlueprintStore.referencePlan(proposal,applied)==applied,"a built road contributes its applied snapshot");
        check(RoadBlueprintStore.referenceIsApplied(proposal,true),"a built road is applied");
        proposal.setState(RoadState.PLANNED);proposal.setInterruptedConstruction(new CompoundTag());
        check(RoadBlueprintStore.referencePlan(proposal,applied)==proposal,"an interrupted road contributes its proposal");
        check(!RoadBlueprintStore.referenceIsApplied(proposal,true),"an interrupted road is not applied");
        System.out.println("PASS "+checks+" stage7 round6 lock/grant assertions");
    }
}
