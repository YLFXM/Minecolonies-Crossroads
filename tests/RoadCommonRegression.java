import com.YLFXM.rp.crossroads.road.*;
import com.YLFXM.rp.crossroads.road.common.BlueprintCells;
import com.YLFXM.rp.crossroads.road.common.RoadAxisMath;
import com.YLFXM.rp.crossroads.road.common.RoadGeometry;
import com.YLFXM.rp.crossroads.road.common.RoadProposal;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;

/** Pure coordinate, footprint and containment rules shared by the whole road system. */
public final class RoadCommonRegression {
    private static int checks;
    private static void check(boolean value,String label) { checks++; if(!value) throw new AssertionError(label); }

    private static RoadPlan plan(Direction.Axis axis,int side) {
        var p=new RoadPlan(UUID.randomUUID(),Level.OVERWORLD,new BlockPos(10,65,10),new BlockPos(10,65,9),
                Direction.SOUTH,side<0,"minecolonies","street",4,WalkRange.of(1,2),side,4);
        p.addSegment(new RoadSegment(new BlockPos(6,64,6),9,axis,0,SegmentKind.ROAD));
        p.setTravelDir(1); p.setState(RoadState.PLANNED); return p;
    }

    public static void main(String[] args) {
        // along / cross are mirror images; both must be pinned for both axes.
        check(RoadAxisMath.along(Direction.Axis.X,3,7)==3,"along on an X road uses x");
        check(RoadAxisMath.along(Direction.Axis.Z,3,7)==7,"along on a Z road uses z");
        check(RoadAxisMath.cross(Direction.Axis.X,3,7)==7,"cross on an X road uses z");
        check(RoadAxisMath.cross(Direction.Axis.Z,3,7)==3,"cross on a Z road uses x");
        check(RoadAxisMath.along(Direction.Axis.X,3,7)!=RoadAxisMath.cross(Direction.Axis.X,3,7),
                "along and cross are not the same value on an asymmetric position");

        // blockPos must invert along/cross for every combination.
        for(var axis:List.of(Direction.Axis.X,Direction.Axis.Z)) {
            check(RoadAxisMath.alongOf(axis,RoadAxisMath.blockPos(axis,4,9,70))==4,"blockPos round-trips along "+axis);
            check(RoadAxisMath.crossOf(axis,RoadAxisMath.blockPos(axis,4,9,70))==9,"blockPos round-trips cross "+axis);
            check(RoadAxisMath.blockPos(axis,4,9,70).getY()==70,"blockPos keeps y "+axis);
        }

        // Lateral offsets measure from the anchor column, never from the segment start.
        for(int side:List.of(-1,1)) for(var axis:List.of(Direction.Axis.X,Direction.Axis.Z)) {
            var p=plan(axis,side);
            check(RoadAxisMath.crossBase(p)==10,"cross base is the anchor column "+axis+side);
            var body=RoadGeometry.body(p);
            var walk=RoadGeometry.walk(p);
            if(axis==Direction.Axis.X) {
                check(body.minX()==6 && body.maxX()==14,"body rows follow the segment on X"+side);
                check(walk.minX()==6 && walk.maxX()==14,"walk rows follow the segment on X"+side);
            } else {
                check(body.minZ()==6 && body.maxZ()==14,"body rows follow the segment on Z"+side);
            }
            int near=10+side*0, far=10+side*3;
            int bodyLow=Math.min(near,far), bodyHigh=Math.max(near,far);
            if(axis==Direction.Axis.X) {
                check(body.minZ()==bodyLow && body.maxZ()==bodyHigh,"body width spans 4 columns from the anchor on X"+side);
            } else {
                check(body.minX()==bodyLow && body.maxX()==bodyHigh,"body width spans 4 columns from the anchor on Z"+side);
            }
            check(walk.minX()>=body.minX() && walk.maxX()<=body.maxX()
                    && walk.minZ()>=body.minZ() && walk.maxZ()<=body.maxZ(),
                    "walk range stays inside the paved body "+axis+side);
        }

        // Rect containment: four edges plus one cell outside each side.
        var r=new RoadGeometry.Rect(1,1,3,3);
        check(r.contains(1,1) && r.contains(3,3) && r.contains(2,2),"rect contains corners and centre");
        check(!r.contains(0,2) && !r.contains(4,2) && !r.contains(2,0) && !r.contains(2,4),
                "rect excludes the cells just outside each edge");

        // Intersection: overlapping, touching, and disjoint.
        check(r.intersect(new RoadGeometry.Rect(2,2,5,5))!=null,"overlapping rects intersect");
        check(r.intersect(new RoadGeometry.Rect(2,2,5,5)).equals(new RoadGeometry.Rect(2,2,3,3)),
                "intersection keeps the shared corner");
        check(r.intersect(new RoadGeometry.Rect(4,4,6,6))==null,"disjoint rects return null");
        check(new RoadGeometry.Rect(1,1,2,2).intersect(new RoadGeometry.Rect(2,1,3,2))!=null,
                "rects sharing one column still intersect");
        boolean threw=false;
        try { new RoadGeometry.Rect(2,2,1,1); } catch(IllegalArgumentException ex) { threw=true; }
        check(threw,"an inverted rect is rejected");
        var rectTag=r.save();
        check(RoadGeometry.Rect.load(rectTag).equals(r),"rect NBT round-trips every bound");

        // The shared construction footprint is the full-width body intersection, which is
        // strictly wider than the walkable intersection whenever the roads have kerbs.
        var horizontal=plan(Direction.Axis.X,1);var vertical=plan(Direction.Axis.Z,1);
        var bodyShared=RoadGeometry.body(horizontal).intersect(RoadGeometry.body(vertical));
        var walkShared=RoadGeometry.walk(horizontal).intersect(RoadGeometry.walk(vertical));
        check(bodyShared!=null && walkShared!=null,"both footprints meet at the crossing");
        check(bodyShared.minX()<=walkShared.minX() && bodyShared.minZ()<=walkShared.minZ()
                && bodyShared.maxX()>=walkShared.maxX() && bodyShared.maxZ()>=walkShared.maxZ(),
                "the full body intersection strictly contains the walk intersection");
        check(!bodyShared.equals(walkShared),"kerb columns are part of the shared body but not the walk");

        // Containment used by the blueprint writer: six faces of a 2x2x2 image.
        check(BlueprintCells.inside(2,2,2,new BlockPos(0,0,0)),"inside accepts the minimum corner");
        check(BlueprintCells.inside(2,2,2,new BlockPos(1,1,1)),"inside accepts the maximum corner");
        check(!BlueprintCells.inside(2,2,2,new BlockPos(2,0,0)),"inside rejects x at the size");
        check(!BlueprintCells.inside(2,2,2,new BlockPos(0,2,0)),"inside rejects y at the size");
        check(!BlueprintCells.inside(2,2,2,new BlockPos(0,0,2)),"inside rejects z at the size");
        check(!BlueprintCells.inside(2,2,2,new BlockPos(-1,0,0)),"inside rejects negative coordinates");

        // R7: the axis-change row reset is one decision, and publish options stay explicit.
        var xRoad=plan(Direction.Axis.X,1);var zRoad=plan(Direction.Axis.Z,1);var xRoad2=plan(Direction.Axis.X,1);
        check(RoadReplanning.clearRowsOnAxisChange(xRoad,zRoad),"axis change is detected");
        check(zRoad.rows().isEmpty(),"axis change leaves no row overrides");
        check(!RoadReplanning.clearRowsOnAxisChange(xRoad,xRoad2),"same axis keeps the row table");
        check(!RoadReplanning.clearRowsOnAxisChange(null,zRoad),"a missing old plan is not an axis change");
        var emptyRoad=new RoadPlan(UUID.randomUUID(),Level.OVERWORLD,new BlockPos(10,65,10),new BlockPos(10,65,9),
                Direction.SOUTH,false,"minecolonies","street",4,WalkRange.of(1,2),1,4);
        check(!RoadReplanning.clearRowsOnAxisChange(emptyRoad,zRoad),"a road without segments is not an axis change");
        check(RoadProposal.Save.values().length==2,"publish save has exactly checked/void semantics");
        check(RoadProposal.Audience.values().length==3,"publish audience has none/player/all");

        // R7-fix1: the unified row table's three-way lamp decides controller migration.
        // plan() puts the anchor at x=10 / z=10 on both axes, so anchorAlong is 10.
        var lampRoad=plan(Direction.Axis.X,1);final int anchorRow=10;
        lampRoad.setLamp(anchorRow,RowState.Lamp.PLAIN);
        check(!RoadControllerMigration.anchorLampSuppressed(lampRoad,anchorRow),"a plain anchor lamp keeps the controller");
        lampRoad.setLamp(anchorRow,RowState.Lamp.NONE);
        check(RoadControllerMigration.anchorLampSuppressed(lampRoad,anchorRow),"a suppressed anchor lamp moves the controller");
        lampRoad.setLamp(anchorRow,RowState.Lamp.CORNER);
        check(RoadControllerMigration.anchorLampSuppressed(lampRoad,anchorRow),"a corner anchor lamp moves the controller");
        lampRoad.clearRowOverrides();
        check(!RoadControllerMigration.anchorLampSuppressed(lampRoad,anchorRow),"an unjudged anchor row stays conservative");
        var cornerRoad=plan(Direction.Axis.X,1);
        cornerRoad.setLamp(anchorRow+1,RowState.Lamp.CORNER);
        cornerRoad.setLamp(anchorRow+4,RowState.Lamp.PLAIN);
        check(RoadControllerMigration.nearestLampRow(cornerRoad,anchorRow)==anchorRow+4,
                "nearestLampRow skips a corner lamp and finds the nearest plain one");

        System.out.println("PASS "+checks+" road common assertions");
    }
}
