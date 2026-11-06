import com.YLFXM.rp.crossroads.road.*;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import java.util.*;

/** Production geometry and persisted transaction checks, independent of game registries. */
public final class RoadHeightRegression {
    private static int checks;
    private static void check(boolean value,String label) { checks++; if(!value) throw new AssertionError(label); }
    private static RoadPlan plan(Direction.Axis axis,int direction,int side) {
        var p=new RoadPlan(UUID.randomUUID(),Level.OVERWORLD,new BlockPos(10,65,10),new BlockPos(10,65,9),
                Direction.SOUTH,side<0,"minecolonies","street",4,WalkRange.of(1,2),side,4);
        p.addSegment(new RoadSegment(new BlockPos(6,64,6),9,axis,0,SegmentKind.ROAD));
        p.setTravelDir(direction); p.setState(RoadState.PLANNED); return p;
    }
    public static void main(String[] args) {
        check(RoadUnitLibrary.supports("minecolonies","street",4),"historical logical pack resolves bundled classic unit");
        check(RoadUnitLibrary.supports("Crossroads","street",4),"generated pack alias resolves bundled classic unit");
        check(RoadUnitLibrary.supports("Minecolonies Original","street",4),"native classic pack resolves bundled unit");
        check(!RoadUnitLibrary.supports("foreign","street",4) && !RoadUnitLibrary.supports("minecolonies","other",4)
                && !RoadUnitLibrary.supports("minecolonies","street",3),"unsupported style or dimensions do not silently select a different unit");
        for(var axis:List.of(Direction.Axis.X,Direction.Axis.Z)) for(int direction:List.of(-1,1)) for(int side:List.of(-1,1)) {
            var p=plan(axis,direction,side); int a=RoadHeightProfile.anchorAlong(p);
            var flat=RoadHeightProfile.of(p);
            check(flat.minBase()==64 && flat.maxBase()==64,"flat baseline remains unchanged");
            p.setRowType(a+direction,CellType.STAIR_UP);
            p.setRowType(a+3*direction,CellType.STAIR_DOWN);
            var profile=RoadHeightProfile.of(p);
            check(profile.row(a).entryY()==65 && profile.row(a).baseY()==64,"anchor remains fixed and flat");
            check(profile.row(a+direction).entryY()==65 && profile.row(a+direction).baseY()==65,"ascent uses the higher stair block");
            check(profile.row(a+2*direction).baseY()==65,"following platform remains one block higher");
            check(profile.row(a+3*direction).entryY()==66 && profile.row(a+3*direction).baseY()==65,"descent uses the lower stair block");
            check(profile.row(a+4*direction).baseY()==64,"up then down returns to the same level");
            var up=RoadHeightProfile.uphill(p,CellType.STAIR_UP);
            check(up.getAxis()==axis && up.getAxisDirection().getStep()==direction,"facing follows travel, not width mirror");
            check(RoadHeightProfile.uphill(p,CellType.STAIR_DOWN)==up.getOpposite(),"descending stair is faced backwards, not upside down");
            var lateral=RoadHeightProfile.position(p,a,2,64);
            check(p.lateralOf(lateral)==2,"row world positions respect width mirror");
            var bounds=RoadBounds.ofRoadOnly(p,6);
            check(bounds.minY()==64 && bounds.maxY()==71,"bounds follow the highest row plus reservation");
            p.setRowType(a-direction,CellType.STAIR_UP);
            profile=RoadHeightProfile.of(p);
            check(profile.row(a-direction).entryY()==64 && profile.row(a).entryY()==65,"backward integration is anchored, not accumulated from A");
            check(profile.row(a-2*direction).baseY()==63,"the far side follows the slope without shifting the anchor");
        }
        var p=plan(Direction.Axis.X,1,1);
        p.setAppliedGeometry(p.geometrySnapshot());
        p.setRowType(11,CellType.STAIR_UP);
        String up=RoadHeightProfile.editSignature(p);
        p.setRowType(11,CellType.STAIR_DOWN);
        check(p.editedRowCount()==1 && !up.equals(RoadHeightProfile.editSignature(p)),"same edited count but different type invalidates preview");
        p.setRowType(10,CellType.STAIR_UP);
        try { RoadHeightProfile.of(p); throw new AssertionError("anchor stair accepted"); }
        catch(IllegalArgumentException expected) { checks++; }
        check(RoadBounds.ofRoadOnly(p,6)!=null,"legacy invalid anchor row still has a display fallback");
        p.setRowType(10,CellType.FLAT); check(RoadHeightProfile.of(p).row(10).baseY()==64,"explicitly clearing a legacy anchor restores validity");
        p.setAppliedBounds(new RoadBounds(6,63,10,14,70,13));
        var restored=RoadPlan.load(p.saveForSync());
        check(restored.appliedBounds().equals(p.appliedBounds()),"actual occupied bounds survive sync independently of proposal slopes");
        check(RoadBounds.load(new net.minecraft.nbt.CompoundTag())==null,"malformed derived range is ignored");
        var owner=UUID.randomUUID(); var session=RoadRepairSession.prepare(p,owner,1);
        var source=new ArrayList<BlockPos>(List.of(new BlockPos(12,80,10),new BlockPos(12,80,10)));
        session.setClearTargets(source); source.clear();
        check(session.clearTargets().size()==1,"clearing targets are copied and deduplicated");
        var saved=RoadRepairSession.load(session.save());
        check(saved.clearTargets().equals(List.of(new BlockPos(12,80,10))),"critical old high targets persist across restart");
        saved.start(RoadRepairSession.Phase.SURVIVAL,2);
        try { saved.setClearTargets(List.of()); throw new AssertionError("confirmed targets changed"); }
        catch(IllegalStateException expected) { checks++; }
        var legacy=session.save(); legacy.remove("clearTargets");
        check(RoadRepairSession.load(legacy).clearTargets().isEmpty(),"legacy frozen builds gain no new clearing operations");
        check(session.target().cellType(11)==CellType.STAIR_DOWN && p.editedRowCount()==1,"geometry preparation leaves live diff intact");
        var target=session.target(); target.setRowType(11,CellType.FLAT);
        check(session.target().cellType(11)==CellType.STAIR_DOWN,"frozen target cannot be mutated by later edits");
        System.out.println("PASS "+checks+" road height/session assertions");
    }
}
