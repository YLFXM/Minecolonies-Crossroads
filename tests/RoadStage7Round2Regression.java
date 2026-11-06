package com.YLFXM.rp.crossroads.road;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.Level;

/** Actual policy/scope/profile reproductions for the second crossing repair. */
public final class RoadStage7Round2Regression {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static RoadPlan road(Direction.Axis axis,int x,int z,int width) {
        var p=new RoadPlan(UUID.randomUUID(),Level.OVERWORLD,new BlockPos(x,65,z),new BlockPos(x,65,z-1),Direction.SOUTH,false,"minecolonies","street",width,WalkRange.of(1,2),1,4);
        p.addSegment(new RoadSegment(new BlockPos(axis==Direction.Axis.X ? -20:x,64,axis==Direction.Axis.Z ? -20:z),51,axis,0,SegmentKind.ROAD));
        p.setTravelDir(1);return p;
    }
    public static void main(String[] args) {
        check(RoadPlacementMode.values().length==2 && RoadPlacementMode.values()[0]==RoadPlacementMode.PLAN
                && RoadPlacementMode.values()[1]==RoadPlacementMode.CONFIGURE,
                "only PLAN and CONFIGURE remain; legacy COMPLETE/PRETTY/SURVIVAL are gone");
        check(com.YLFXM.rp.crossroads.network.ConfigureRoadPayload.modeOf(0)==RoadPlacementMode.PLAN
                && com.YLFXM.rp.crossroads.network.ConfigureRoadPayload.modeOf(1)==RoadPlacementMode.CONFIGURE,
                "known mode ordinals still decode");
        check(com.YLFXM.rp.crossroads.network.ConfigureRoadPayload.modeOf(2)==null
                && com.YLFXM.rp.crossroads.network.ConfigureRoadPayload.modeOf(4)==null,
                "removed modes are rejected instead of being clamped onto CONFIGURE");
        check(RoadCrossingConsent.debounce(11_999,10_000),"duplicate request is protected for two seconds");
        check(!RoadCrossingConsent.debounce(12_000,10_000),"debounce ends without extending the request deadline");
        check(RoadCrossingConsent.timeout(RoadCrossingConsent.Decision.PENDING,120_000,120_001,true,true)==RoadCrossingConsent.Decision.PENDING,"debounce never substitutes for timeout consent");
        var own=road(Direction.Axis.X,0,0,4);var peer=road(Direction.Axis.Z,10,-15,7);
        var scope=RoadCrossingConsent.shape(own);own.setRowType(8,CellType.STAIR_UP);
        check(scope.equals(RoadCrossingConsent.shape(own)),"ordinary own height edits retain the planning request identity");
        own.setAnchorPos(own.anchorPos().above());check(!scope.equals(RoadCrossingConsent.shape(own)),"moving the anchor invalidates scope");own.setAnchorPos(own.anchorPos().below());
        own.setMirror(true);check(!scope.equals(RoadCrossingConsent.shape(own)),"mirroring the footprint is not the old approved scope");own.setMirror(false);
        own.planning().planner(UUID.randomUUID());
        var restored=RoadPlan.load(own.save());check(restored.planning().planner().equals(own.planning().planner()),"planner identity persists");
        var legacyPolicy=own.save();var policyList=new ListTag();var policyEntry=new CompoundTag();
        policyEntry.putUUID("road",peer.id());policyEntry.putInt("choice",1);policyList.add(policyEntry);
        legacyPolicy.getCompound("planning").put("policies",policyList);
        check(!RoadPlan.load(legacyPolicy).save().getCompound("planning").contains("policies"),
                "legacy policies are ignored and never persisted");
        var fake=own.save();var display=new ListTag();var e=new CompoundTag();e.putUUID("road",peer.id());e.putInt("status",2);display.add(e);fake.getCompound("planning").put("access",display);
        var displayed=RoadPlan.load(fake);check(displayed.planning().access(peer.id())==2,"client can display its scoped approval state");
        check(!displayed.save().getCompound("planning").contains("access"),"display state is not persisted as permission");
        var pairs=new ListTag();var legacy=new CompoundTag();legacy.putUUID("road",peer.id());legacy.putBoolean("layered",true);legacy.putBoolean("arch",true);legacy.putInt("order",1);pairs.add(legacy);fake.getCompound("planning").put("pairs",pairs);
        check(RoadPlan.load(fake).save().getCompound("planning").getList("pairs",10).isEmpty(),
                "legacy pair mirrors are ignored and never persisted into a road plan");
        check(RoadCrossingSafety.upperFloor(64,6)==71,"upper route clears the highest lamp, not just three blocks above pavement");
        check(RoadCrossingSafety.upperFloor(64,0)==67,"baseline walking clearance remains mandatory");
        check(RoadCrossingSafety.lowerFloor(64,6)==56,"underpass reserves one foundation layer and its own full highest lamp");
        check(RoadCrossingSafety.lowerFloor(64,0)==61,"underpass preserves baseline surface separation");
        check(RoadCrossings.removalColumn(own,peer,new BlockPos(16,-50,2)),"same-level demolition protects the actual seven-wide peer, at the bottom");
        check(RoadCrossings.removalColumn(own,peer,new BlockPos(16,200,2)),"same-level demolition protects the entire crossing column");
        check(!RoadCrossings.removalColumn(own,peer,new BlockPos(17,64,2)),"the rest of the main road remains demolishable");
        check(!RoadCrossings.removalColumn(own,peer,new BlockPos(10,64,4)),"protection does not expand beyond the actual crossing");
        check(!RoadCrossings.removalColumn(own,road(Direction.Axis.X,0,0,4),new BlockPos(10,64,2)),"parallel geometry does not invent a junction column");
        var junctionRect=RoadCrossingGeometry.body(own).intersect(RoadCrossingGeometry.body(peer));
        var junction=new RoadJunctions.Junction(own.id(),peer.id(),junctionRect,new RoadPlanningState.Crossing(true,1,10),null,false,false,1);
        check(RoadJunctions.ruleFor(junction,own.id()).order()==1 && RoadJunctions.ruleFor(junction,peer.id()).order()==-1,
                "one record yields opposite orders without a mirrored second copy");
        check(RoadRules.nextRelationMode(0,false)==1 && RoadRules.nextRelationMode(1,false)==2 && RoadRules.nextRelationMode(2,false)==1,
                "unauthorized relation cycling only offers overpass and underpass");
        check(RoadRules.nextRelationMode(0,true)==1 && RoadRules.nextRelationMode(2,true)==0,
                "authorized relation cycling still reaches same-level");
        own.setAppliedGeometry(own.geometrySnapshot());own.setState(RoadState.PLANNED);
        check(!RoadRules.canEdit(own),"a built road staged as PLANNED still locks when blueprint editing is off");
        own.setAllowBlueprintEdit(true);check(RoadRules.canEdit(own),"the same staged built road unlocks explicitly");own.setAllowBlueprintEdit(false);
        own.setState(RoadState.BUILT);peer.setState(RoadState.BUILT);
        check(!RoadRules.canChangePlatform(own,peer),"two locked built roads are read-only");own.setAllowBlueprintEdit(true);
        check(!RoadRules.canChangePlatform(own,peer),"opening just one built road is insufficient");peer.setAllowBlueprintEdit(true);
        check(RoadRules.canChangePlatform(own,peer),"two editable built roads may reconstruct their common platform");peer.setState(RoadState.BUILDING);
        check(!RoadRules.canChangePlatform(own,peer),"an active native job still blocks shared-height editing");peer.setState(RoadState.BUILT);
        peer.setBaseRows(Map.of());peer.setRowType(-5,CellType.STAIR_UP);peer.pruneOutOfRangeRows();
        var applied=RoadPlan.load(peer.save());peer.setRowType(20,CellType.STAIR_DOWN);
        var layeredTarget=RoadJointWork.peerTarget(peer,applied,own,new RoadPlanningState.Crossing(true,1,10));
        check(layeredTarget.rowOverrides().isEmpty(),"a layered record restores the peer base without keeping the old lift");
        var sameLevelTarget=RoadJointWork.peerTarget(peer,applied,own,new RoadPlanningState.Crossing(false,0,11));
        check(sameLevelTarget.rowOverrides().containsKey(-5),"a same-level record keeps the applied platform rows");
        check(peer.row(20).type()==CellType.STAIR_DOWN && !sameLevelTarget.rowOverrides().containsKey(20),"an unrelated edit stays outside the joint target");
        check(applied.rowOverrides().containsKey(-5),"proposal reconstruction never changes the applied road");
        var actor=UUID.randomUUID();var session=RoadRepairSession.prepare(own,actor,1);var grants=new CompoundTag();var grant=new CompoundTag();grant.putUUID("actor",actor);grants.put(peer.id().toString(),grant);session.setCrossingGrants(grants);
        check(!session.crossingGrants().getCompound(peer.id().toString()).getBoolean("confirmed"),"a prepared tool is not a confirmed-operation proof");
        session.start(RoadRepairSession.Phase.NATIVE,2);var retry=RoadRepairSession.retry(RoadRepairSession.load(session.save()),actor,3);
        var proof=retry.crossingGrants().getCompound(peer.id().toString());
        check(proof.getBoolean("confirmed") && proof.getUUID("session").equals(retry.token()),"frozen retry retains confirmed scope and binds a fresh tool token");
        check(!retry.ownedBy(actor,session.token()),"closing or retrying never revives the old tool token");
        int[] natural=new int[21];Arrays.fill(natural,65);CellType[] rows=new CellType[21];Arrays.fill(rows,CellType.FLAT);
        check(Arrays.equals(natural,RoadBridgeConstraints.prefer(new RoadSlopeSolver.Profile(natural,rows),1,65,Map.of()).heights()),"ordinary same-level platforms do not inherit bridge-only no-dip preferences");
        System.out.println("PASS "+checks+" stage7 round2 assertions");
    }
}
