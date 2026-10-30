package com.YLFXM.mcreconstruction.road;

import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
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
        a.planning().crossing(b.id(),new RoadPlanningState.Crossing(false,0,5,64));
        b.planning().crossing(a.id(),new RoadPlanningState.Crossing(false,0,5,64));
        RoadLockReconcile.apply(a,id -> id.equals(b.id()) ? b : null);
        check(a.planning().locks().values().stream().anyMatch(lock -> lock.anchor()),"the anchor row is locked");
        check(a.planning().locks().values().stream().anyMatch(lock -> !lock.anchor() && lock.height()==65),"a same-level junction locks its rows at platform plus one");
        check(a.planning().locks().values().stream().filter(lock -> !lock.anchor()).count()>1,"every row of the junction span is locked");
        var saved=RoadPlan.load(a.save());
        check(saved.planning().locks().equals(a.planning().locks()),"locks survive a save/load round trip");
        int lockedRows=a.planning().locks().size();
        a.planning().remove(b.id());
        RoadLockReconcile.apply(a,id -> id.equals(b.id()) ? b : null);
        check(a.planning().locks().size()<lockedRows && a.planning().locks().values().stream().allMatch(RoadPlanningState.Lock::anchor),
                "removing the relation releases the rows immediately, back to automatic planning");
        check(a.planning().locks().isEmpty()==false && a.planning().locks().size()==1,"only the anchor stays locked");

        var spanRoad=road(Direction.Axis.X,0,0);
        spanRoad.planning().locks(Map.of(0,new RoadPlanningState.Lock(true,65),20,new RoadPlanningState.Lock(false,66)));
        int[] span=RoadLockReconcile.span(spanRoad,8,12);
        check(span[0]==0 && span[1]==20,"the override span stops at the nearest locks on both sides");
        int[] open=RoadLockReconcile.span(spanRoad,-18,28);
        check(open[0]==spanRoad.minAlong() && open[1]==spanRoad.maxAlong(),"without nearby locks the span covers the whole road");

        var token=UUID.randomUUID();var issuer=UUID.randomUUID();
        a.planning().grant(b.id(),new RoadPlanningState.Grant(token,issuer,1234L,"abc"));
        var copy=RoadPlan.load(a.save());
        var grant=copy.planning().grants().get(b.id());
        check(grant!=null && grant.token().equals(token) && grant.issuer().equals(issuer) && grant.issuedAt()==1234L && grant.boundary().equals("abc"),
                "a pair grant survives a save/load round trip");
        copy.planning().clearGrant(b.id());
        check(copy.planning().grants().isEmpty(),"releasing a pair grant removes it from the snapshot");
        a.planning().grant(b.id(),new RoadPlanningState.Grant(token,issuer,1234L,"abc"));
        a.planning().clearRows();
        check(a.planning().grants().isEmpty() && a.planning().locks().isEmpty(),"clearing a draft plan drops locks and grants with it");
        System.out.println("PASS "+checks+" stage7 round6 lock/grant assertions");
    }
}
