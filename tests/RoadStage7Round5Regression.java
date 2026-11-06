package com.YLFXM.rp.crossroads.road;

import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import com.ldtteam.structurize.blueprints.v1.*;
import com.ldtteam.structurize.blocks.schematic.*;

import com.YLFXM.rp.crossroads.road.common.RoadBlocks;
import com.YLFXM.rp.crossroads.road.common.BlueprintCells;

/** Native shared-cell merge, real ghost projection, draft identity and bounded restoration proofs. */
public final class RoadStage7Round5Regression {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static RoadPlan road(Direction.Axis axis,int x,int z) {
        var p=new RoadPlan(UUID.randomUUID(),Level.OVERWORLD,new BlockPos(x,65,z),new BlockPos(-10,65,-10),Direction.SOUTH,false,"minecolonies","street",4,WalkRange.of(1,2),1,4);
        p.addSegment(new RoadSegment(new BlockPos(x,64,z),6,axis,0,SegmentKind.ROAD));p.setTravelDir(1);p.setState(RoadState.DRAFT);return p;
    }
    private static Blueprint image(net.minecraft.core.HolderLookup.Provider registries) {
        var b=RoadBlueprintGenerator.emptyBlueprint((short)6,(short)4,(short)4,registries);b.setCachePrimaryOffset(new BlockPos(0,1,0));return b;
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        var guard=Bootstrap.class.getDeclaredField("isBootstrapped");guard.setAccessible(true);guard.setBoolean(null,true);
        BlockSubstitution skip;BlockSolidSubstitution fill;
        try {var air=Blocks.AIR;skip=Registry.register(BuiltInRegistries.BLOCK,ResourceLocation.parse("structurize:blocksubstitution"),new BlockSubstitution());
            fill=Registry.register(BuiltInRegistries.BLOCK,ResourceLocation.parse("structurize:blocksolidsubstitution"),new BlockSolidSubstitution());}
        finally{guard.setBoolean(null,false);}Bootstrap.bootStrap();var registry=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var main=road(Direction.Axis.X,0,0);var peer=road(Direction.Axis.Z,1,0);
        var owner=UUID.randomUUID();main.setCreator(owner);main.setDisplayName("Owned draft");
        var candidate=road(Direction.Axis.X,0,0);RoadReplanning.draftPreview(main,candidate,UUID.randomUUID(),false);
        check(candidate.id().equals(main.id()) && candidate.creator().equals(owner),"preview keeps UUID and creator used by approval boundary");
        check(candidate.displayName().equals(main.displayName()) && candidate.state()==RoadState.DRAFT,"preview remains the same named draft");
        check(RoadCrossingConsent.shape(candidate).equals(RoadCrossingConsent.shape(main)),"valid preview and stored draft share the request shape");

        var stair=new RoadJointWork.Cell(Blocks.STONE_STAIRS.defaultBlockState(),null);var kerb=new RoadJointWork.Cell(Blocks.DIRT_PATH.defaultBlockState(),null);
        check(RoadJointWork.sharedCell(stair,kerb).equals(stair),"native shared approach chooses the actual stair over flat kerb");
        check(RoadJointWork.sharedCell(kerb,stair).equals(stair),"merge order does not change shared stairs");
        var material=new net.minecraft.nbt.CompoundTag();material.putString("material","minecraft:dirt");
        var dom=new RoadJointWork.Cell(Blocks.STONE_STAIRS.defaultBlockState(),material);
        check(RoadJointWork.sharedCell(kerb,dom).tile().equals(material),"shared stair selection retains block-entity material data");
        var support=new RoadJointWork.Cell(fill.defaultBlockState(),null);var air=new RoadJointWork.Cell(Blocks.AIR.defaultBlockState(),null);
        check(RoadJointWork.sharedCell(air,support).equals(support),"shared fill is not cleared by the other road's background AIR");
        boolean denied=false;try{RoadJointWork.sharedCell(kerb,new RoadJointWork.Cell(Blocks.GOLD_BLOCK.defaultBlockState(),null));}catch(IllegalArgumentException ex){denied=true;}
        check(denied,"incompatible concrete targets still fail instead of silently overwriting materials");

        var rawMain=image(registry);var nativeMain=image(registry);var rawPeer=image(registry);var nativePeer=image(registry);
        var corner=new BlockPos(2,0,0);var ownFill=new BlockPos(0,0,1);
        rawMain.addBlockState(corner,fill.defaultBlockState());rawMain.addBlockState(ownFill,fill.defaultBlockState());
        nativeMain.addBlockState(corner,skip.defaultBlockState());nativeMain.addBlockState(ownFill,skip.defaultBlockState());
        nativePeer.addBlockState(new BlockPos(1,0,0),Blocks.COBBLESTONE.defaultBlockState());
        for(var b:List.of(rawMain,nativeMain,rawPeer,nativePeer))b.setCachePrimaryOffset(new BlockPos(0,1,0));
        var ghost=RoadJointWork.ghostProjection(main,Map.of(main.id(),main,peer.id(),peer),Map.of(main.id(),rawMain,peer.id(),rawPeer),Map.of(main.id(),nativeMain,peer.id(),nativePeer),registry);
        check(ghost.getBlockState(corner).is(Blocks.COBBLESTONE),"single ghost includes visible shared corner from the same peer target as joint preview");
        check(RoadBlocks.isSolidFill(ghost.getBlockState(ownFill)),"single ghost restores visible fill behind native no-op mask");
        check(nativeMain.getBlockState(corner).is(skip),"display projection never turns native protected cells into world edits");

        var tried=new ArrayList<Integer>();var next=RoadPlanningActions.chooseMode(0,1,m->{tried.add(m);if(m==1)throw new IllegalArgumentException("no room");return "lower";});
        check(tried.equals(List.of(1,2)) && next.mode()==2 && next.fallback(),"same-level can skip infeasible overpass and choose underpass");
        var fallback=RoadPlanningActions.chooseMode(1,2,m->{if(m==2)throw new IllegalArgumentException("no room");return "same";});
        check(fallback.mode()==0,"overpass can skip infeasible underpass and choose same level when authorized");
        var direct=RoadPlanningActions.chooseMode(2,0,m->m);check(direct.mode()==0 && !direct.fallback(),"next feasible relation is preferred");
        denied=false;tried.clear();try{RoadPlanningActions.chooseMode(0,1,m->{tried.add(m);throw new IllegalArgumentException("cannot connect");});}catch(IllegalArgumentException ex){denied=true;}
        check(denied && tried.equals(List.of(1,2)),"both unavailable alternatives reject without selecting another state");

        var actualMain=RoadPlan.load(main.save());var actualPeer=RoadPlan.load(peer.save());
        var sameLevel=new RoadPlanningState.Crossing(false,0);
        check(RoadCrossingRestoration.geometry(main,actualMain,actualPeer,sameLevel,sameLevel),"existing unchanged crossing passes restoration geometry gate");
        var raised=RoadPlan.load(main.save());raised.setRowType(2,CellType.STAIR_UP);check(!RoadCrossingRestoration.geometry(raised,actualMain,actualPeer,sameLevel,sameLevel),"changed crossing height needs approval");
        check(!RoadCrossingRestoration.geometry(main,actualMain,actualPeer,new RoadPlanningState.Crossing(true,1,20),sameLevel),"changed relation needs approval");
        var ownApplied=image(registry);var otherApplied=image(registry);var work=image(registry);
        // The peer anchor is always protected, regardless of background AIR in old snapshots.
        var peerAnchor=peer.anchorPos().subtract(BlueprintCells.origin(main,work));work.addBlockState(peerAnchor,skip.defaultBlockState());
        var shared=new BlockPos(2,0,1);var otherShared=new BlockPos(1,0,1);
        ownApplied.addBlockState(shared,Blocks.COBBLESTONE.defaultBlockState());otherApplied.addBlockState(otherShared,Blocks.COBBLESTONE.defaultBlockState());work.addBlockState(shared,Blocks.COBBLESTONE.defaultBlockState());
        for(var b:List.of(ownApplied,otherApplied,work))b.setCachePrimaryOffset(new BlockPos(0,1,0));
        check(RoadCrossingRestoration.permits(main,actualMain,actualPeer,ownApplied,otherApplied,work),"restoring targets owned by both applied snapshots does not need another grant");
        work.addBlockState(shared,Blocks.GOLD_BLOCK.defaultBlockState());work.setCachePrimaryOffset(new BlockPos(0,1,0));
        check(!RoadCrossingRestoration.permits(main,actualMain,actualPeer,ownApplied,otherApplied,work),"new shared material cannot use restoration permission");
        work.addBlockState(shared,Blocks.COBBLESTONE.defaultBlockState());work.addBlockState(new BlockPos(2,2,1),Blocks.STONE.defaultBlockState());work.setCachePrimaryOffset(new BlockPos(0,1,0));
        check(!RoadCrossingRestoration.permits(main,actualMain,actualPeer,ownApplied,otherApplied,work),"new obstacle in neighbor walking clearance needs approval");
        work.addBlockState(new BlockPos(2,2,1),Blocks.AIR.defaultBlockState());
        for(var b:List.of(ownApplied,work))b.addBlockState(shared,Blocks.STONE_STAIRS.defaultBlockState());
        otherApplied.addBlockState(otherShared,Blocks.STONE_STAIRS.defaultBlockState());
        for(var b:List.of(ownApplied,otherApplied,work))b.setCachePrimaryOffset(new BlockPos(0,1,0));
        check(RoadCrossingRestoration.permits(main,actualMain,actualPeer,ownApplied,otherApplied,work),"unchanged shared approach stairs can be restored without consuming a new neighbor grant");
        System.out.println("PASS "+checks+" stage7 round5 shared/projection/restoration assertions");
    }
}
