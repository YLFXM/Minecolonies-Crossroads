package com.YLFXM.rp.crossroads.road;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.Level;

import com.YLFXM.rp.crossroads.road.common.RoadGeometry;

/** Per-dimension junction record: addressing, rect authority, freeze, grants and projection. */
public final class RoadJunctionTableRegression {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static RoadPlan road(Direction.Axis axis,int x,int z,boolean draft) {
        var p=new RoadPlan(UUID.randomUUID(),Level.OVERWORLD,new BlockPos(x,65,z),new BlockPos(x,65,z-1),Direction.SOUTH,false,"minecolonies","street",4,WalkRange.of(1,2),1,4);
        p.addSegment(new RoadSegment(new BlockPos(axis==Direction.Axis.X ? -20:x,64,axis==Direction.Axis.Z ? -20:z),51,axis,0,SegmentKind.ROAD));
        p.setTravelDir(1);p.setState(draft ? RoadState.DRAFT:RoadState.PLANNED);return p;
    }
    private static RoadGeometry.Rect shared(RoadPlan a,RoadPlan b) {
        return RoadGeometry.body(a).intersect(RoadGeometry.body(b));
    }
    public static void main(String[] args) {
        var a=road(Direction.Axis.X,0,0,false);var b=road(Direction.Axis.Z,10,-15,false);var far=road(Direction.Axis.X,0,60,false);
        var rect=shared(a,b);check(rect!=null,"the two test roads cross");

        check(RoadJunctions.key(a.id(),b.id()).equals(RoadJunctions.key(b.id(),a.id())),"the table key is symmetric");
        var junction=new RoadJunctions.Junction(b.id(),a.id(),rect,new RoadPlanningState.Crossing(false,0,7,64),null,false,false,1);
        var stored=RoadJunctions.loadAll(RoadJunctions.saveAll(List.of(junction))).values().iterator().next();
        check(stored.a().compareTo(stored.b())<0,"a loaded record is normalized to the lower uuid first");
        check((stored.a().equals(a.id())&&stored.b().equals(b.id()))||(stored.a().equals(b.id())&&stored.b().equals(a.id())),
                "normalization keeps both sides on the record");
        check(RoadJunctions.ruleFor(stored,b.id()).order()==0 && RoadJunctions.ruleFor(stored,a.id()).order()==0,"a symmetric rule reads the same from both sides");
        var layered=new RoadJunctions.Junction(a.id(),b.id(),rect,new RoadPlanningState.Crossing(true,1,8,null),null,false,false,1);
        var layeredStored=RoadJunctions.loadAll(RoadJunctions.saveAll(List.of(layered))).values().iterator().next();
        check(RoadJunctions.ruleFor(layeredStored,b.id()).order()==-1,"the stored order is relative to the lower uuid and flipped for the other side");
        check(RoadJunctions.ruleFor(layeredStored,a.id()).order()==1,"the lower uuid keeps the recorded order");

        // An unbuilt pair derives its rect from the latest proposal on both sides.
        var proposalRecord=new RoadJunctions.Junction(a.id(),b.id(),new RoadGeometry.Rect(0,0,1,1),
                new RoadPlanningState.Crossing(false,0),null,false,false,1);
        var proposalRecomputed=RoadJunctions.recompute(proposalRecord,a,b,false,false);
        check(proposalRecomputed.rect().equals(rect),"both unbuilt sides use their latest proposals");
        check(proposalRecomputed.rev()==proposalRecord.rev()+1,"a changed rect advances the record revision");
        var unchanged=RoadJunctions.recompute(proposalRecomputed,a,b,false,false);
        check(unchanged.equals(proposalRecomputed),"an unchanged recomputation is a no-op");

        // A built side is authoritative; the unbuilt side still follows its proposal.
        var longerProposal=road(Direction.Axis.Z,10,-15,false);
        var builtSide=new RoadJunctions.Junction(a.id(),b.id(),rect,new RoadPlanningState.Crossing(false,0),null,false,false,1);
        var builtRecomputed=RoadJunctions.recompute(builtSide,a,b,true,false);
        check(builtRecomputed.builtA()&&!builtRecomputed.builtB(),"built flags follow each side");
        check(builtRecomputed.rect().equals(rect),"a built side's applied body anchors the rect");
        check(RoadJunctions.recompute(builtRecomputed,a,longerProposal,true,false).rect().equals(shared(a,longerProposal)),
                "an unbuilt side follows its proposal even when the other side is built");

        // Both built: the record stays while the proposals still overlap and only the new applied pair decides.
        var bothBuilt=RoadJunctions.recompute(builtRecomputed,a,b,true,true);
        check(bothBuilt.builtA()&&bothBuilt.builtB(),"both built flags are recorded");
        check(bothBuilt.rect().equals(rect),"both built sides keep the recorded footprint");
        check(RoadJunctions.recompute(bothBuilt,a,longerProposal,true,true).rect().equals(rect),
                "both built sides freeze their rect even when a proposal moves");
        check(RoadJunctions.recompute(bothBuilt,a,far,true,true)==null,"an empty applied intersection drops the record");

        // DRAFT proposals participate exactly like other unbuilt proposals.
        var draft=road(Direction.Axis.Z,10,-15,true);
        var draftRecord=new RoadJunctions.Junction(a.id(),draft.id(),rect,new RoadPlanningState.Crossing(false,0),null,false,false,1);
        var draftRecomputed=RoadJunctions.recompute(draftRecord,a,draft,false,false);
        check(draftRecomputed.rect().equals(shared(a,draft)),"a DRAFT side uses the latest proposal");
        check(RoadJunctions.recompute(draftRecord,a,far,false,false)==null,"a DRAFT moved away drops the record");

        // One grant lives on the record, and a released grant stays absent.
        var token=UUID.randomUUID();var issuer=UUID.randomUUID();
        var granted=new RoadJunctions.Junction(a.id(),b.id(),rect,new RoadPlanningState.Crossing(false,0,9,64),
                new RoadPlanningState.Grant(token,issuer,1234L,"boundary"),false,false,1);
        var grantedStored=RoadJunctions.loadAll(RoadJunctions.saveAll(List.of(granted))).values().iterator().next();
        check(grantedStored.grant()!=null && grantedStored.grant().token().equals(token)
                && grantedStored.grant().boundary().equals("boundary"),"the pair grant is stored once on the record");
        var released=grantedStored;
        for(int i=0;i<3;i++) released=new RoadJunctions.Junction(released.a(),released.b(),released.rect(),
                released.rule(),null,released.builtA(),released.builtB(),released.rev()+1);
        check(RoadJunctions.loadAll(RoadJunctions.saveAll(List.of(released))).values().iterator().next().grant()==null,
                "a released grant is absent from the single record");

        // Writes are refused without an active edit scope.
        boolean refused=false;
        try {RoadJunctions.stage(null,junction);}catch(IllegalStateException ex){refused=true;}
        check(refused,"staging without an active junction edit is refused");

        // The client projection is read-only, never persisted back into a plan.
        var tag=a.save();
        var pair=new CompoundTag();pair.putUUID("road",b.id());pair.putBoolean("layered",true);pair.putInt("order",1);
        pair.putLong("version",12);pair.putIntArray("rect",new int[]{rect.minX(),rect.minZ(),rect.maxX(),rect.maxZ()});pair.putBoolean("builtPeer",true);
        var pairs=new ListTag();pairs.add(pair);tag.getCompound("planning").put("pairs",pairs);
        var projected=RoadPlan.load(tag);
        check(projected.syncedJunctions().containsKey(b.id()),"the sync parses the junction projection");
        var other=RoadPlan.load(b.save());
        check(RoadCrossings.setting(projected,other).layered()&&RoadCrossings.setting(projected,other).order()==1,
                "the client reads the relation from the projection only");
        check(RoadPlan.load(projected.save()).syncedJunctions().isEmpty(),"the projection is never persisted into a saved plan");
        var legacy=a.save();var legacyPairs=new ListTag();var mirror=new CompoundTag();
        mirror.putUUID("road",b.id());mirror.putBoolean("layered",true);mirror.putInt("order",1);legacyPairs.add(mirror);
        legacy.getCompound("planning").put("pairs",legacyPairs);
        check(RoadPlan.load(legacy).save().getCompound("planning").getList("pairs",10).isEmpty(),
                "legacy mirror keys are ignored and never rewritten");

        System.out.println("PASS "+checks+" junction table assertions");
    }
}
