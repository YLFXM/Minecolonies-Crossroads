import com.YLFXM.rp.crossroads.road.*;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import java.util.*;
import com.YLFXM.rp.crossroads.road.common.RoadGeometry;

public final class RoadPlanningRegression {
    private static int checks;
    private static void check(boolean ok,String name) { checks++;if(!ok) throw new AssertionError(name); }
    private static RoadPlan road(Direction.Axis axis,int x,int z,int side) {
        var p=new RoadPlan(UUID.randomUUID(),Level.OVERWORLD,new BlockPos(x,65,z),new BlockPos(x,65,z-1),
                Direction.SOUTH,false,"minecolonies","street",4,WalkRange.of(1,2),side,4);
        p.addSegment(new RoadSegment(new BlockPos(x-5,64,z-5),11,axis,0,SegmentKind.ROAD)); return p;
    }
    public static void main(String[] args) {
        int[] terrain=new int[17];Arrays.fill(terrain,65);
        var plain=RoadSlopeSolver.solve(terrain,8,65,Map.of(),Set.of(),Map.of(),0,100);
        for(var type:plain.rows())check(type==CellType.FLAT,"flat ground has no unnecessary slopes");
        check(plain.heights()[8]==65,"anchor fixed");
        var ascent=RoadSlopeSolver.balance(plain,8,65,Map.of(14,68,15,68),Set.of(14,15),Map.of(),0,100);
        check(ascent.heights()[14]==68 && ascent.heights()[15]==68,"exact high junction platform");
        check(ascent.rows()[14]==CellType.FLAT && ascent.rows()[15]==CellType.FLAT,"junction cells stay flat");
        check(ascent.heights()[8]==65,"balancing cannot raise anchor");
        var backwards=RoadSlopeSolver.balance(plain,8,65,Map.of(2,62),Set.of(2),Map.of(),0,100);
        check(backwards.heights()[2]==62 && backwards.heights()[8]==65,"balance on A side of middle anchor");
        var both=RoadSlopeSolver.balance(plain,8,65,Map.of(2,62,14,68),Set.of(2,14),Map.of(),0,100);
        check(both.heights()[2]==62 && both.heights()[14]==68,"independent platform heights on same road");
        try {RoadSlopeSolver.solve(terrain,8,65,Map.of(9,69),Set.of(9),Map.of(),0,100);throw new AssertionError("impossible jump accepted");}
        catch(IllegalArgumentException expected){checks++;}
        try {RoadSlopeSolver.solve(terrain,8,65,Map.of(8,66),Set.of(),Map.of(),0,100);throw new AssertionError("anchor moved");}
        catch(IllegalArgumentException expected){checks++;}
        try {RoadSlopeSolver.solve(terrain,8,65,Map.of(),Set.of(5),Map.of(5,CellType.STAIR_UP),0,100);throw new AssertionError("manual stair on flat platform");}
        catch(IllegalArgumentException expected){checks++;}
        var manual=RoadSlopeSolver.solve(terrain,8,65,Map.of(),Set.of(),Map.of(9,CellType.FLAT,10,CellType.STAIR_UP),0,100);
        check(manual.rows()[9]==CellType.FLAT && manual.rows()[10]==CellType.STAIR_UP,"explicit manual choices retained");
        int[] exposed=manual.heights();exposed[8]=999;check(manual.heights()[8]==65,"solver result protects arrays");
        var layered=RoadSlopeSolver.balance(plain,8,65,Map.of(),Set.of(),Map.of(13,CellType.STAIR_UP),Map.of(12,68,13,68),Map.of(),0,100);
        check(layered.heights()[12]>=68 && layered.heights()[13]>=68,"layered clearance uses per-row lower bounds");
        check(layered.rows()[13]==CellType.STAIR_UP,"layered crossing need not be flat");
        try {RoadSlopeSolver.constrained(terrain,8,65,Map.of(),Set.of(),Map.of(),Map.of(8,69),Map.of(),0,100);throw new AssertionError("anchor clearance conflict");}
        catch(IllegalArgumentException expected){checks++;}
        var x=road(Direction.Axis.X,0,0,1);var z=road(Direction.Axis.Z,0,0,1);
        var intersection=RoadCrossingGeometry.walk(x).intersect(RoadCrossingGeometry.walk(z));
        check(intersection.equals(new RoadGeometry.Rect(1,1,2,2)),"walk intersection excludes kerbs");
        var mirrored=road(Direction.Axis.X,0,0,-1);
        check(RoadCrossingGeometry.walk(mirrored).minZ()==-2 && RoadCrossingGeometry.walk(mirrored).maxZ()==-1,"mirrored walk interval correct");
        var junction=new RoadCrossings.Neighbor(z,z,null,RoadCrossingGeometry.body(x).intersect(RoadCrossingGeometry.body(z)),intersection,new RoadPlanningState.Crossing(false,0));
        RoadCrossings.validate(x,junction);check(true,"same-level intersection validates the exact walk platform");
        var low=new RoadPlan(UUID.randomUUID(),Level.OVERWORLD,new BlockPos(0,62,0),new BlockPos(0,62,-1),Direction.SOUTH,false,"minecolonies","street",4,WalkRange.of(1,2),1,4);
        low.addSegment(new RoadSegment(new BlockPos(0,61,-5),11,Direction.Axis.Z,0,SegmentKind.ROAD));
        var bridge=new RoadCrossings.Neighbor(low,low,null,RoadCrossingGeometry.body(x).intersect(RoadCrossingGeometry.body(low)),intersection,new RoadPlanningState.Crossing(true,1));
        RoadCrossings.validate(x,bridge);check(true,"three-block floor separation is accepted");
        low.setAnchorPos(new BlockPos(0,63,0));
        try {RoadCrossings.validate(x,bridge);throw new AssertionError("two-block clearance accepted");}
        catch(IllegalArgumentException expected){checks++;}
        low.setAnchorPos(new BlockPos(0,62,0));
        var junctionRect=RoadCrossingGeometry.body(x).intersect(RoadCrossingGeometry.body(z));
        var lowerOrdered=x.id().compareTo(z.id())<0 ? x:z;
        var upperOrdered=lowerOrdered==x ? z:x;
        var layeredJunction=new RoadJunctions.Junction(x.id(),z.id(),junctionRect,
                new RoadPlanningState.Crossing(true,lowerOrdered==x ? -1:1,10),null,false,false,1);
        var stored=RoadJunctions.loadAll(RoadJunctions.saveAll(List.of(layeredJunction))).values().iterator().next();
        check(RoadJunctions.ruleFor(stored,lowerOrdered.id()).order()==-1
                && RoadJunctions.ruleFor(stored,upperOrdered.id()).order()==1,
                "one stored order is interpreted from both directions without a second mirror");
        check(stored.rule().layered() && stored.rule().version()==10,"layered intent and version survive the junction table NBT");
        var replaced=new RoadJunctions.Junction(x.id(),z.id(),junctionRect,new RoadPlanningState.Crossing(false,0,11),null,false,false,2);
        var newer=RoadJunctions.loadAll(RoadJunctions.saveAll(List.of(replaced))).values().iterator().next();
        check(!newer.rule().layered() && newer.rule().version()==11,"a newer same-level rule replaces the layered rule in the single record");
        x.editRow(0,CellType.FLAT);x.editRow(2,CellType.STAIR_UP);
        x.planning().sample("fixture",new int[]{65,66});
        var restored=RoadPlan.load(x.save());
        check(restored.manualRows().containsKey(0) && restored.manualRows().get(0)==CellType.FLAT,"explicit flat survives NBT even without a row diff");
        check(restored.planning().terrain()[1]==66,"terrain sample persists");
        restored.stageRemap(1,3);check(!restored.manualRows().containsKey(0),"draft shrink prunes manual intent too");
        x.setAppliedGeometry(x.geometrySnapshot());
        check(!x.geometrySnapshot().contains("appliedGeometry"),"applied geometry snapshots cannot recursively nest");
        check(!x.saveForSync().contains("appliedGeometry"),"public sync excludes private applied model");
        System.out.println("PASS "+checks+" planning/geometry assertions");
    }
}
