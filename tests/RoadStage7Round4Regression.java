package com.YLFXM.mcreconstruction.road;

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
        oldPeer.adoptCommittedRows(Map.of(9,CellType.STAIR_UP,11,CellType.STAIR_UP,14,CellType.STAIR_UP));
        var peer=road(Direction.Axis.Z,8,36,15,84);peer.setId(oldPeer.id());peer.adoptCommittedRows(oldPeer.rowOverrides());peer.setAppliedGeometry(oldPeer.geometrySnapshot());
        var oldPrimary=RoadPlan.load(primary.save());var oldPeerTag=oldPeer.save();
        primary.planning().crossing(peer.id(),new RoadPlanningState.Crossing(false,0,100,-60));
        peer.planning().crossing(primary.id(),new RoadPlanningState.Crossing(false,0,100,-60));
        var target=RoadJointWork.prospectivePeer(peer,oldPeer,primary);
        RoadLockReconcile.apply(primary,id -> id.equals(peer.id()) ? oldPeer : null);
        RoadLockReconcile.apply(target,id -> id.equals(primary.id()) ? oldPrimary : null);
        RoadJointWork.alignTargets(primary,target,-60,-62,310);
        boolean refused=false;try{RoadCrossings.validate(primary,RoadCrossings.neighbor(primary,peer,oldPeer,null));}catch(IllegalArgumentException ex){refused=ex.getMessage().contains("Junction height must match");}
        check(refused,"reproduces the screenshot with the stale default physical platform");
        try(var scope=RoadCrossings.scope(Map.of(primary.id(),primary,peer.id(),target),Map.of(),primary.id())) {
            RoadCrossings.validate(primary,RoadCrossings.neighbor(primary,peer,oldPeer,null));
            check(RoadCrossings.neighbor(primary,peer,oldPeer,null).model()==target,"ordinary/joint/frozen scopes select the exact new target rather than old applied geometry");
            check(RoadCrossings.reference(null,peer)==target,"shared scoped reference is used without another world lookup");
        }
        check(oldPeer.save().equals(oldPeerTag),"new platform selection does not edit the applied source");
        var extendingMain=road(Direction.Axis.X,47,44,52,36);
        check(RoadCrossingGeometry.body(extendingMain).intersect(RoadCrossingGeometry.body(oldPeer))==null,"old physical road ends before the new crossing");
        check(RoadCrossings.planningReference(extendingMain,peer,oldPeer)==peer,"new crossing lookup keeps the current candidate instead of falsely reporting no overlap");
        var extension=RoadJointWork.prospectivePeer(peer,oldPeer,extendingMain);
        check(extension.maxAlong()==43,"prospective preview retains the current proposed end");
        check(RoadJointWork.potentialOverlap(extendingMain,peer),"proposal overlap reaches the resolver even when outside its applied range");
        var far=road(Direction.Axis.Z,100,12,105,100);check(!RoadJointWork.potentialOverlap(primary,far),"unrelated roads are skipped before loading their physical blueprints");
        far.setAppliedGeometry(oldPeer.geometrySnapshot());check(RoadJointWork.potentialOverlap(primary,far),"a changed proposal cannot hide an existing physical crossing from the minimal relation index");
        check(RoadCrossings.neighbor(extendingMain,peer,oldPeer,null)!=null,"the new relation is referenceable before neighbor extension construction");
        var dependency=RoadCrossings.frozenDependency(peer,oldPeer,false);
        check(dependency.getBoolean("applied") && RoadPlan.load(dependency.getCompound("model")).maxAlong()==27,"frozen verification still records the physical source, not unbuilt extension bounds");
        check(RoadCrossings.frozenDependency(peer,oldPeer,true).getBoolean("constraintOnly"),"remote dependency remains read-only");
        peer.setRowType(40,CellType.STAIR_DOWN);var proposal=RoadPlan.load(peer.geometrySnapshot());proposal.adoptPendingRows(Map.of());proposal.setRowType(24,CellType.STAIR_DOWN);proposal.applyPendingRows();
        RoadJointWork.stageProposalRows(peer,proposal,oldPeer);
        check(peer.pendingRows().containsKey(40),"updating an unbuilt extension does not erase unrelated pending edits");
        check(peer.pendingRows().containsKey(24),"the local platform adjustment remains a proposal, not an applied commit");

        var peerRows=Map.copyOf(peer.rowOverrides());var peerPending=Map.copyOf(peer.pendingRows());var applied=peer.appliedGeometry().copy();var geometry=List.copyOf(peer.segments());
        primary.planning().policy(peer.id(),2);primary.planning().local(peer.id(),true);
        primary.planning().crossing(peer.id(),new RoadPlanningState.Crossing(true,1,200));RoadPlanningActions.syncRelativeRule(primary,peer);
        var inverse=peer.planning().crossings().get(primary.id());
        check(inverse.layered() && inverse.order()==-1 && inverse.version()==200,"strict overpass actually persists inverse underpass metadata");
        var restored=RoadPlan.load(peer.save());check(restored.planning().crossings().get(primary.id()).order()==-1,"inverse field survives NBT, not only thread-local calculation");
        primary.planning().crossing(peer.id(),new RoadPlanningState.Crossing(true,-1,201));RoadPlanningActions.syncRelativeRule(primary,peer);
        check(peer.planning().crossings().get(primary.id()).order()==1,"strict underpass actually persists inverse overpass metadata");
        check(peer.rowOverrides().equals(peerRows) && peer.pendingRows().equals(peerPending),"field-only sync does not rewrite committed or pending row tables");
        check(peer.appliedGeometry().equals(applied) && peer.segments().equals(geometry),"field-only sync cannot change applied geometry or endpoints");

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
        check(RoadCrossings.deckCell(primary,RoadBlueprintOps.origin(primary,mask)),"fixture fill is at actual deck height, where the previous conversion wrongly removed it");
        check(RoadBlueprintOps.isSolidFill(mask.getBlockState(BlockPos.ZERO)),"production removal mask preserves native environmental fill marker at deck height");
        check(!RoadNativeRemoval.target(mask.getBlockState(BlockPos.ZERO)),"creative scan and native removal do not treat fill as a concrete demolition target");
        check(RoadNativeRemoval.target(mask.getBlockState(new BlockPos(1,0,0))),"explicit cobblestone remains demolishable");
        var roundtrip=BlueprintUtil.readBlueprintFromNBT(BlueprintUtil.writeBlueprintToNBT(mask),registries);
        check(RoadBlueprintOps.isSolidFill(roundtrip.getBlockState(BlockPos.ZERO)),"fill retention survives the actual saved native demolition blueprint");
        check(maskSource.getBlockState(BlockPos.ZERO).is(fill),"masking leaves the applied source immutable");
        System.out.println("PASS "+checks+" stage7 round4 target/admission/removal assertions");
    }
}
