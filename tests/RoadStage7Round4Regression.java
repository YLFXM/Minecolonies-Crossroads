package com.YLFXM.rp.crossroads.road;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.workorders.*;
import com.ldtteam.structurize.blueprints.v1.BlueprintUtil;
import java.lang.reflect.Proxy;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockBehaviour;

import com.YLFXM.rp.crossroads.road.common.RoadBlocks;
import com.YLFXM.rp.crossroads.road.common.BlueprintCells;
import com.YLFXM.rp.crossroads.road.common.RoadGeometry;

/** Production reference selection, field-only reciprocal sync, native admission and real removal masks. */
public final class RoadStage7Round4Regression {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static RoadPlan road(Direction.Axis axis,int min,int length,int anchor,int cross) {
        var p=new RoadPlan(UUID.randomUUID(),Level.OVERWORLD,new BlockPos(axis==Direction.Axis.X ? anchor:cross,-60,axis==Direction.Axis.X ? cross:anchor),
                new BlockPos(axis==Direction.Axis.X ? anchor:cross,-60,axis==Direction.Axis.X ? cross-1:anchor-1),Direction.SOUTH,false,"minecolonies","street",4,WalkRange.of(1,2),1,4);
        p.addSegment(new RoadSegment(new BlockPos(axis==Direction.Axis.X ? min:cross,-61,axis==Direction.Axis.X ? cross:min),length,axis,0,SegmentKind.ROAD));
        p.setState(RoadState.PLANNED);p.setAllowBlueprintEdit(true);p.setTravelDir(-1);return p;
    }
    private static RoadCrossings.Neighbor neighbor(RoadPlan own,RoadPlan model,RoadPlanningState.Crossing setting) {
        return new RoadCrossings.Neighbor(model,model,null,RoadCrossingGeometry.body(own).intersect(RoadCrossingGeometry.body(model)),
                RoadCrossingGeometry.walk(own).intersect(RoadCrossingGeometry.walk(model)),setting);
    }
    private static final class NativeAdmission {
        final Map<Integer,IServerWorkOrder> orders=new HashMap<>();boolean accept=true,failHook;
        int nativeCalls,newHooks,removed;boolean trusted;
        final IServerWorkOrder order=(IServerWorkOrder)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{IServerWorkOrder.class},(p,m,a) -> {
            if(m.getName().equals("getID"))return 41;
            if(m.getName().equals("onAdded")){if(!(boolean)a[1])newHooks++;if(failHook)throw new IllegalStateException("hook rejected");return null;}
            if(m.getName().equals("hashCode"))return 41;if(m.getName().equals("equals"))return p==a[0];return null;
        });
        final IWorkManager manager=(IWorkManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{IWorkManager.class},(p,m,a) -> {
            switch(m.getName()) {
                case "addWorkOrder" -> {nativeCalls++;trusted=(boolean)a[1];if(accept)orders.put(41,(IServerWorkOrder)a[0]);return null;}
                case "getWorkOrder" -> {return orders.get((int)a[0]);}
                case "removeWorkOrder" -> {removed++;orders.remove((int)a[0]);return null;}
                default -> {return null;}
            }
        });
        final IColony colony=(IColony)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{IColony.class},(p,m,a) -> m.getName().equals("getWorkManager") ? manager:null);
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        // Standalone-only registration window. Bootstrap normally freezes all vanilla
        // registries before a test can register a mod marker; initialize blocks under
        // its guard, register the real marker ID, then run the complete normal bootstrap.
        var guard=Bootstrap.class.getDeclaredField("isBootstrapped");guard.setAccessible(true);guard.setBoolean(null,true);
        Block fill;
        try {
            var vanillaAir=Blocks.AIR;
            fill=Registry.register(BuiltInRegistries.BLOCK,ResourceLocation.parse("structurize:blocksolidsubstitution"),new Block(BlockBehaviour.Properties.of()));
        } finally {guard.setBoolean(null,false);}
        Bootstrap.bootStrap();
        var registries=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

        // Coordinates/profile from the report: anchor -60, old physical end 27, current proposed end 43.
        var primary=road(Direction.Axis.X,68,34,93,23);var oldPeer=road(Direction.Axis.Z,8,20,15,84);
        oldPeer.adoptCommittedRows(Map.of(9,CellType.STAIR_UP,11,CellType.STAIR_UP,14,CellType.STAIR_UP));oldPeer.setAppliedGeometry(oldPeer.geometrySnapshot());
        var peer=road(Direction.Axis.Z,8,36,15,84);peer.setId(oldPeer.id());peer.adoptCommittedRows(oldPeer.rowOverrides());peer.setAppliedGeometry(peer.geometrySnapshot());peer.setAppliedGeometry(oldPeer.geometrySnapshot());
        var oldPrimary=RoadPlan.load(primary.save());var oldPeerTag=oldPeer.save();
        var relation=new RoadPlanningState.Crossing(false,0,100,-60);
        var recorded=new RoadJunctions.Junction(primary.id(),peer.id(),RoadCrossingGeometry.body(primary).intersect(RoadCrossingGeometry.body(peer)),
                relation,null,true,true,1);
        var target=RoadJointWork.prospectivePeer(peer,oldPeer,primary,relation);
        RoadLockReconcile.apply(primary,List.of(recorded),id -> id.equals(peer.id()) ? oldPeer : null,id -> true);
        RoadLockReconcile.apply(target,List.of(recorded),id -> id.equals(primary.id()) ? oldPrimary : null,id -> true);
        RoadJointWork.alignTargets(primary,target,-60,-62,310);
        boolean refused=false;try{RoadCrossings.validate(primary,neighbor(primary,oldPeer,relation));}catch(IllegalArgumentException ex){refused=ex.getMessage().contains("Junction height must match");}
        check(refused,"reproduces the screenshot with the stale default physical platform");
        try(var scope=RoadCrossings.scope(Map.of(primary.id(),primary,peer.id(),target),Map.of(),primary.id())) {
            RoadCrossings.validate(primary,neighbor(primary,target,relation));
            check(RoadCrossings.reference(null,peer)==target,"ordinary/joint/frozen scopes select the exact new target rather than old applied geometry");
        }
        check(oldPeer.save().equals(oldPeerTag),"new platform selection does not edit the applied source");
        var extendingMain=road(Direction.Axis.X,47,44,52,36);
        check(RoadCrossingGeometry.body(extendingMain).intersect(RoadCrossingGeometry.body(oldPeer))==null,"old physical road ends before the new crossing");
        check(RoadCrossings.planningReference(extendingMain,peer,oldPeer)==peer,"new crossing lookup keeps the current candidate instead of falsely reporting no overlap");
        var extension=RoadJointWork.prospectivePeer(peer,oldPeer,extendingMain,relation);
        check(extension.maxAlong()==43,"prospective preview retains the current proposed end");
        check(RoadJointWork.potentialOverlap(extendingMain,peer),"proposal overlap reaches the resolver even when outside its applied range");
        var far=road(Direction.Axis.Z,100,12,105,100);check(!RoadJointWork.potentialOverlap(primary,far),"unrelated roads are skipped before loading their physical blueprints");
        far.setAppliedGeometry(oldPeer.geometrySnapshot());check(RoadJointWork.potentialOverlap(primary,far),"a changed proposal cannot hide an existing physical crossing from the minimal relation index");
        check(RoadCrossingGeometry.body(extendingMain).intersect(RoadCrossingGeometry.body(peer))!=null,"the new relation is geometrically referenceable before peer extension construction");
        var dependency=RoadCrossings.frozenDependency(peer,oldPeer,false);
        check(dependency.getBoolean("applied") && RoadPlan.load(dependency.getCompound("model")).maxAlong()==27,"frozen verification still records the physical source, not unbuilt extension bounds");
        check(RoadCrossings.frozenDependency(peer,oldPeer,true).getBoolean("constraintOnly"),"remote dependency remains read-only");
        peer.setRowType(40,CellType.STAIR_DOWN);var proposal=RoadPlan.load(peer.geometrySnapshot());proposal.adoptPendingRows(Map.of());proposal.setRowType(24,CellType.STAIR_DOWN);proposal.pruneOutOfRangeRows();
        RoadJointWork.stageProposalRows(peer,proposal,oldPeer);
        check(peer.pendingRows().containsKey(40),"updating an unbuilt extension does not erase unrelated pending edits");
        check(peer.pendingRows().containsKey(24),"the local platform adjustment remains a proposal, not an applied commit");

        var peerRows=Map.copyOf(peer.rowOverrides());var peerPending=Map.copyOf(peer.pendingRows());var applied=peer.appliedGeometry().copy();var geometry=List.copyOf(peer.segments());
        var overpass=new RoadJunctions.Junction(primary.id(),peer.id(),RoadCrossingGeometry.body(primary).intersect(RoadCrossingGeometry.body(peer)),
                new RoadPlanningState.Crossing(true,1,200),null,false,false,2);
        check(RoadJunctions.ruleFor(overpass,primary.id()).order()==1 && RoadJunctions.ruleFor(overpass,peer.id()).order()==-1,
                "one strict overpass record reads as the inverse underpass for the peer");
        var underpass=new RoadJunctions.Junction(primary.id(),peer.id(),RoadCrossingGeometry.body(primary).intersect(RoadCrossingGeometry.body(peer)),
                new RoadPlanningState.Crossing(true,-1,201),null,false,false,3);
        check(RoadJunctions.ruleFor(underpass,peer.id()).order()==1,"one strict underpass record reads as the inverse overpass for the peer");
        check(peer.rowOverrides().equals(peerRows) && peer.pendingRows().equals(peerPending),"relation records never rewrite committed or pending row tables");
        check(peer.appliedGeometry().equals(applied) && peer.segments().equals(geometry),"relation records cannot change applied geometry or endpoints");

        var nativeApi=new NativeAdmission();var session=RoadRepairSession.prepareInitial(primary,UUID.randomUUID(),1);
        nativeApi.accept=false;boolean accepted=RoadBuilderDispatch.admit(nativeApi.colony,nativeApi.order);
        check(!accepted && nativeApi.nativeCalls==1 && nativeApi.trusted,"trusted admission still requires actual native manager acceptance");
        check(!session.acceptBuilder(accepted,2) && session.phase()==RoadRepairSession.Phase.PREPARED,"rejected order cannot enter SURVIVAL/BUILDING");
        check(!session.orderObserved() && session.previousState()==RoadState.DRAFT,"failed first dispatch retains DRAFT recovery and no false observed work");
        nativeApi.accept=true;accepted=RoadBuilderDispatch.admit(nativeApi.colony,nativeApi.order);
        check(accepted && nativeApi.orders.get(41)==nativeApi.order && nativeApi.newHooks==1,"native order exists and receives the normal new-order hook");
        check(session.acceptBuilder(accepted,3) && session.orderObserved() && session.phase()==RoadRepairSession.Phase.SURVIVAL,"accepted native order atomically enters observed construction");
        var restarted=RoadRepairSession.load(session.save());check(restarted.orderObserved(),"accepted builder state survives restart");
        var hookFailure=new NativeAdmission();hookFailure.failHook=true;refused=false;
        try{RoadBuilderDispatch.admit(hookFailure.colony,hookFailure.order);}catch(IllegalStateException ex){refused=true;}
        check(refused && hookFailure.orders.isEmpty() && hookFailure.removed==1,"failure in native admission hook removes its order before caller restores preparation state");

        var maskSource=RoadBlueprintGenerator.emptyBlueprint((short)2,(short)2,(short)1,registries);
        maskSource.addBlockState(BlockPos.ZERO,fill.defaultBlockState());maskSource.addBlockState(new BlockPos(1,0,0),Blocks.COBBLESTONE.defaultBlockState());maskSource.setCachePrimaryOffset(new BlockPos(0,1,0));
        var mask=RoadCrossings.removalMask(primary,maskSource,List.of(),registries);
        check(RoadCrossings.deckCell(primary,BlueprintCells.origin(primary,mask)),"fixture fill is at actual deck height, where the previous conversion wrongly removed it");
        check(RoadBlocks.isSolidFill(mask.getBlockState(BlockPos.ZERO)),"production removal mask preserves native environmental fill marker at deck height");
        check(!RoadNativeRemoval.target(mask.getBlockState(BlockPos.ZERO)),"creative scan and native removal do not treat fill as a concrete demolition target");
        check(RoadNativeRemoval.target(mask.getBlockState(new BlockPos(1,0,0))),"explicit cobblestone remains demolishable");
        var roundtrip=BlueprintUtil.readBlueprintFromNBT(BlueprintUtil.writeBlueprintToNBT(mask),registries);
        check(RoadBlocks.isSolidFill(roundtrip.getBlockState(BlockPos.ZERO)),"fill retention survives the actual saved native demolition blueprint");
        check(maskSource.getBlockState(BlockPos.ZERO).is(fill),"masking leaves the applied source immutable");

        // A raised junction step may sit above the old applied image's top; the applied mask
        // must grow to cover both volumes instead of refusing the commit.
        var small=RoadBlueprintGenerator.emptyBlueprint((short)2,(short)2,(short)2,registries);
        small.addBlockState(new BlockPos(1,0,0),Blocks.COBBLESTONE.defaultBlockState());
        small.setCachePrimaryOffset(new BlockPos(0,1,0));
        var world=peer.anchorPos().subtract(new BlockPos(0,1,0));
        var tall=RoadBlueprintGenerator.emptyBlueprint((short)2,(short)3,(short)2,registries);
        tall.addBlockState(new BlockPos(0,2,0),Blocks.STONE_STAIRS.defaultBlockState());
        tall.addBlockState(new BlockPos(1,0,0),Blocks.GOLD_BLOCK.defaultBlockState());
        tall.setCachePrimaryOffset(new BlockPos(0,1,0));
        var union=RoadJointWork.maskedApplied(peer,small,tall,new RoadGeometry.Rect(world.getX(),world.getZ(),world.getX(),world.getZ()),registries);
        check(RoadCrossings.stateAt(union,peer,world.above(2)).is(Blocks.STONE_STAIRS),"applied union covers a target step above the old image");
        check(RoadCrossings.stateAt(union,peer,world.offset(1,0,0)).is(Blocks.COBBLESTONE),"applied union preserves old cells outside the shared footprint");
        check(!RoadCrossings.stateAt(union,peer,world.offset(1,0,0)).is(Blocks.GOLD_BLOCK),"outside-shared target cells never overlay the old image");
        check(union.getSizeY()>=3,"applied union grows to the union volume");
        System.out.println("PASS "+checks+" stage7 round4 target/admission/removal assertions");
    }
}
