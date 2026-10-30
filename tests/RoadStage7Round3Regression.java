package com.YLFXM.mcreconstruction.road;

import com.YLFXM.mcreconstruction.RoadPermissions;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/** Joint target solver, scoped symmetry, third-road boundary and lamp geometry. */
public final class RoadStage7Round3Regression {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static RoadPlan road(Direction.Axis axis,int x,int z) {
        var p=new RoadPlan(UUID.randomUUID(),Level.OVERWORLD,new BlockPos(x,65,z),new BlockPos(x,65,z-1),Direction.SOUTH,false,"minecolonies","street",4,WalkRange.of(1,2),1,4);
        p.addSegment(new RoadSegment(new BlockPos(axis==Direction.Axis.X ? -20:x,64,axis==Direction.Axis.Z ? -20:z),51,axis,0,SegmentKind.ROAD));p.setTravelDir(1);
        p.setState(RoadState.BUILT);p.setAllowBlueprintEdit(true);return p;
    }
    private static RoadCrossings.Neighbor neighbor(RoadPlan own,RoadPlan other) {
        return new RoadCrossings.Neighbor(other,other,null,RoadCrossingGeometry.body(own).intersect(RoadCrossingGeometry.body(other)),
                RoadCrossingGeometry.walk(own).intersect(RoadCrossingGeometry.walk(other)),RoadCrossings.setting(own,other));
    }
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());Bootstrap.bootStrap();
        var a=road(Direction.Axis.X,0,0);var b=road(Direction.Axis.Z,10,-15);var c=road(Direction.Axis.X,0,15);
        var oldA=RoadPlan.load(a.save());var oldB=RoadPlan.load(b.save());var oldC=c.save();
        var overlap=RoadCrossingGeometry.walk(a).intersect(RoadCrossingGeometry.walk(b));
        var remote=RoadCrossingGeometry.walk(b).intersect(RoadCrossingGeometry.walk(c));
        a.planning().crossing(b.id(),new RoadPlanningState.Crossing(false,0,12,65));
        b.planning().crossing(a.id(),new RoadPlanningState.Crossing(false,0,12,65));
        b.planning().crossing(c.id(),new RoadPlanningState.Crossing(false,0,13,64));
        RoadLockReconcile.apply(a,id -> id.equals(b.id()) ? b : null);
        RoadLockReconcile.apply(b,id -> id.equals(a.id()) ? a : id.equals(c.id()) ? c : null);
        check(RoadCrossingGeometry.platform(b,remote)==64,"an existing junction platform is recorded as a lock");
        check(b.planning().locks().values().stream().anyMatch(lock -> !lock.anchor() && lock.height()==65),"the lock keeps the agreed platform height");
        var locksBefore=b.planning().save().getCompound("planning").getList("locks",10).size();
        var stranger=road(Direction.Axis.X,0,15);
        RoadLockReconcile.apply(b,id -> id.equals(a.id()) ? a : id.equals(c.id()) ? c : id.equals(stranger.id()) ? stranger : null);
        check(b.planning().save().getCompound("planning").getList("locks",10).size()==locksBefore,"an overlapping road without a relation record never becomes a constraint");
        RoadJointWork.alignTargets(a,b,65,2,110);
        check(RoadCrossingGeometry.platform(a,overlap)==65,"primary new platform replaces its old applied height");
        check(RoadCrossingGeometry.platform(b,overlap)==65,"peer new target uses the same requested platform");
        check(RoadCrossingGeometry.platform(b,remote)==64,"another recorded junction stays at its locked platform");
        check(RoadHeightProfile.of(a).row(0).baseY()==64 && RoadHeightProfile.of(b).row(-15).baseY()==64,"both anchors remain fixed");
        check(c.save().equals(oldC),"read-only neighbour constraints never mutate its proposal");
        RoadCrossings.validate(a,neighbor(a,b));check(true,"final equality checks the two new targets");
        boolean refused=false;try{RoadCrossings.validate(a,neighbor(a,oldB));}catch(IllegalArgumentException ex){refused=true;}
        check(refused,"ordinary validation still rejects a mismatched old platform");
        refused=false;try{RoadJointWork.alignTargets(a,b,110,2,110);}catch(IllegalArgumentException ex){refused=true;}
        check(refused,"unreachable joint height is still refused");
        check(RoadCrossingGeometry.platform(a,overlap)==65,"failed solving keeps the accepted proposal intact");
        a.planning().policy(b.id(),2);a.planning().local(b.id(),true);a.planning().crossing(b.id(),new RoadPlanningState.Crossing(true,1,20));
        var bBefore=b.save();check(!RoadCrossings.setting(b,a).layered(),"private strict intent cannot change the foreign global relation");
        try(var scope=RoadCrossings.scope(Map.of(a.id(),a),Map.of(),a.id())) {
            check(RoadCrossings.setting(b,a).layered() && RoadCrossings.setting(b,a).order()==-1,"this operation derives the inverse underpass");
            check(RoadCrossings.setting(a,b).order()==1,"the primary keeps its overpass");
            a.planning().crossing(b.id(),new RoadPlanningState.Crossing(true,-1,21));
            check(RoadCrossings.setting(b,a).order()==1,"strict underpass derives the inverse overpass");
            check(!RoadCrossings.setting(c,a).layered(),"unrelated pairs do not inherit the strict rule");
        }
        check(b.save().equals(bBefore),"scoped reciprocal calculation never edits peer NBT");
        check(!RoadCrossings.setting(b,a).layered(),"scope close restores the foreign relationship");
        var flatA=road(Direction.Axis.X,0,0);var flatB=road(Direction.Axis.Z,10,-15);
        check(RoadCrossings.sharedLampRow(flatA,flatB,RoadCrossings.setting(flatA,flatB))!=null,"the east-west road provides the shared corner lamp");
        check(RoadCrossings.sharedLampRow(flatB,flatA,RoadCrossings.setting(flatB,flatA))==null,"the north-south road yields the corner lamp");
        check(!RoadCrossings.deckCell(c,new BlockPos(10,80,17)),"height outside the deck is not a structural deferral");
        var origin=new BlockPos(10,64,0);
        check(RoadCrossings.adjacentLamp(origin,new BlockPos(11,90,0),64,70,65,71),"horizontal neighbors with overlapping lamp intervals conflict");
        check(!RoadCrossings.adjacentLamp(origin,new BlockPos(11,64,1),64,70,64,70),"diagonal lamps remain");
        check(!RoadCrossings.adjacentLamp(origin,new BlockPos(12,64,0),64,70,64,70),"two-block-separated lamps remain");
        check(!RoadCrossings.adjacentLamp(origin,new BlockPos(11,64,0),64,70,71,77),"nonoverlapping bridge lamp heights remain");
        check(RoadCrossings.adjacentLamp(origin,new BlockPos(9,64,0),64,70,70,76),"a shared occupied height is an overlap");
        var lamp=new UnitTemplate(1,8,2,1,Direction.Axis.X);
        lamp.add(new BlockPos(0,1,0),Blocks.OAK_FENCE.defaultBlockState(),null);
        lamp.add(new BlockPos(0,7,0),Blocks.LANTERN.defaultBlockState(),null);
        lamp.add(new BlockPos(0,20,0),Blocks.AIR.defaultBlockState(),null);
        check(RoadCrossings.adjacentLamp(a,10,64,a,11,64,lamp),"real unit entries suppress a same-height adjacent whole lamp");
        check(!RoadCrossings.adjacentLamp(a,10,64,a,11,71,lamp),"unused AIR height never enlarges a lamp's collision interval");
        check(RoadCrossingConsent.notifyTimeoutRecipient(RoadCrossingConsent.Decision.APPROVED,true),"timeout approval informs the recipient");
        check(!RoadCrossingConsent.notifyTimeoutRecipient(RoadCrossingConsent.Decision.APPROVED,false),"manual answer uses its existing receipt");
        check(!RoadCrossingConsent.notifyTimeoutRecipient(RoadCrossingConsent.Decision.INVALID,true),"later invalidation cannot announce automatic approval");
        UUID owner=UUID.randomUUID(),actor=UUID.randomUUID();
        for(boolean creative:new boolean[]{false,true})for(boolean op:new boolean[]{false,true}) {
            boolean bypass=RoadPermissions.creativeAdmin(creative,op);
            check(bypass==(creative && op),"native-configured threshold requires creative mode and operator permission");
            check(RoadPermissions.canAccessWilderness(owner,actor,bypass)==bypass,"foreign wilderness uses the same view/blueprint/manage rule");
        }
        check(RoadPermissions.canAccessWilderness(owner,owner,false),"creator keeps wilderness access");
        check(RoadPermissions.canAccessWilderness(null,actor,false),"unowned roads remain public");
        System.out.println("PASS "+checks+" stage7 round3 assertions");
    }
}
