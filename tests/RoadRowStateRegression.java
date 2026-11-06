import com.YLFXM.rp.crossroads.road.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;

/** Row-state table: authority levels, lock heights, baselines and derived pending rows. */
public final class RoadRowStateRegression {
    private static int checks;
    private static void check(boolean value,String label) { checks++; if(!value) throw new AssertionError(label); }

    private static RoadPlan plan() {
        var p=new RoadPlan(UUID.randomUUID(),Level.OVERWORLD,new BlockPos(10,65,10),new BlockPos(10,65,9),
                Direction.SOUTH,false,"minecolonies","street",4,WalkRange.of(1,2),1,4);
        p.addSegment(new RoadSegment(new BlockPos(6,64,6),9,Direction.Axis.X,0,SegmentKind.ROAD));
        p.setTravelDir(1); p.setState(RoadState.PLANNED); return p;
    }

    public static void main(String[] args) {
        var p=plan();
        check(p.row(7).type()==CellType.FLAT && p.row(7).level()==RowState.RowLevel.AUTO,
                "a row never touched reads flat and automatic");

        p.setAutoType(7,CellType.STAIR_UP);
        check(p.row(7).type()==CellType.STAIR_UP && p.row(7).level()==RowState.RowLevel.AUTO,
                "an automatic write keeps the row automatic");

        p.editRow(8,CellType.STAIR_DOWN);
        check(p.row(8).manual(),"a player edit marks the row manual");
        check(!p.setAutoType(8,CellType.FLAT),"the solver cannot overwrite a manual row");
        check(p.row(8).type()==CellType.STAIR_DOWN,"a refused solver write leaves the manual type alone");

        p.lock(8,64);
        check(p.row(8).locked() && Integer.valueOf(64).equals(p.row(8).lockY()),
                "locking promotes a manual row and records the surface height");
        check(p.row(8).type()==CellType.FLAT,
                "locking forces the row flat, so the lock height and the type chain agree");
        check(!p.editRow(8,CellType.FLAT),"a locked row cannot be edited");

        p.unlock(8);
        check(p.row(8).level()==RowState.RowLevel.AUTO && p.row(8).lockY()==null,
                "unlocking returns the row to automatic and releases the height");
        check(p.row(8).type()==CellType.FLAT,"unlocking keeps the flat type the platform required");

        p.setLamp(7,RowState.Lamp.PLAIN);
        check(p.row(7).lamp()==RowState.Lamp.PLAIN,"the lamp outcome is stored on the row");
        p.setBase(7,CellType.STAIR_DOWN);
        check(p.baseRows().get(7)==CellType.STAIR_DOWN && p.hasBase(),
                "the junction-free baseline is stored on the row");

        var round=RoadPlan.load(p.save());
        check(round.rows().equals(p.rows()),"row state survives a save/load round trip");
        check(round.row(7).lamp()!=null && round.row(7).base()==CellType.STAIR_DOWN,
                "lamp and base survive the round trip too");

        var never=plan();
        never.setAutoType(7,CellType.STAIR_UP);
        check(never.pendingRows().isEmpty() && never.editedRowCount()==0,
                "a road that was never built has nothing pending");
        never.setAppliedGeometry(never.geometrySnapshot());
        check(never.pendingRows().isEmpty(),"committing the current rows leaves nothing pending");
        never.editRow(7,CellType.FLAT);
        check(never.pendingRows().containsKey(7),"an edit after the build shows up as pending");
        never.editRow(7,CellType.STAIR_UP);
        check(never.pendingRows().isEmpty(),"changing a row back to the applied value clears it from pending");

        var built=plan();
        built.setAutoType(7,CellType.STAIR_UP);
        check(built.confirmedRowCount()==0,"a road that was never built has no confirmed rows");
        built.setAppliedGeometry(built.geometrySnapshot());
        check(built.confirmedRowCount()==1 && built.appliedRows().get(7).type()==CellType.STAIR_UP,
                "committing a build makes the current rows the applied table");
        built.clearRowOverrides();
        check(built.rows().isEmpty() && built.appliedRows().get(7).type()==CellType.STAIR_UP,
                "clearing the rows leaves the applied snapshot alone");

        var consent=plan();
        var consented=RoadCrossingConsent.intent(consent);
        consent.setLamp(7,RowState.Lamp.PLAIN);consent.setBase(7,CellType.STAIR_DOWN);consent.lock(7,64);
        check(consented.equals(RoadCrossingConsent.intent(consent)),
                "lamp, lock height, level and baseline are not part of the consented shape");
        consent.setRowType(9,CellType.STAIR_UP);
        check(!consented.equals(RoadCrossingConsent.intent(consent)),
                "a row type still changes the consented shape");

        // A row the road no longer covers is "dropped", not "edited": counting it as a
        // pending change reported the same row twice (panel + dropped message).
        var shortened=plan();
        shortened.setAppliedGeometry(shortened.geometrySnapshot());
        shortened.setAutoType(12,CellType.STAIR_UP);
        check(shortened.pendingRows().containsKey(12),"the edited row inside the road is pending");
        shortened.clearSegments();
        shortened.addSegment(new RoadSegment(new BlockPos(6,64,6),4,Direction.Axis.X,0,SegmentKind.ROAD));
        check(shortened.stageRemap(shortened.minAlong(),shortened.maxAlong())>0,
                "re-planning reports the rows that fall outside the new range");
        check(shortened.pendingRows().isEmpty(),"a dropped row is not counted as an edit as well");
        check(shortened.pruneOutOfRangeRows(),"the build prunes the dropped rows afterwards");

        // Lost = applied rows whose MANUAL/LOCK level the proposal no longer carries.
        var lossy=plan();
        lossy.editRow(12,CellType.STAIR_UP);
        lossy.setAppliedGeometry(lossy.geometrySnapshot());
        check(lossy.lostRowCount()==0,"a manual row still in the proposal is not lost");
        lossy.clearManual(12);
        check(lossy.lostRowCount()==1,"releasing a manual level counts as lost");
        check(lossy.editedRowCount()==0,"releasing a level is not a type change");
        check(RoadPlan.load(lossy.saveForSync()).lostRowCount()==1,
                "the synced plan can count lost rows too");
        var clippedManual=plan();
        clippedManual.editRow(12,CellType.STAIR_UP);
        clippedManual.setAppliedGeometry(clippedManual.geometrySnapshot());
        clippedManual.clearSegments();
        clippedManual.addSegment(new RoadSegment(new BlockPos(6,64,6),4,Direction.Axis.X,0,SegmentKind.ROAD));
        clippedManual.stageRemap(clippedManual.minAlong(),clippedManual.maxAlong());
        check(clippedManual.lostRowCount()==1,"a manual row outside the new range counts as lost");
        check(clippedManual.pendingRows().isEmpty(),"and it is not an edit at the same time");

        // A road built dead flat has an empty applied table; that is still "built".
        var flatBuilt=plan();
        flatBuilt.setAppliedGeometry(flatBuilt.geometrySnapshot());
        check(flatBuilt.hasApplied() && flatBuilt.pendingRows().isEmpty(),
                "an empty applied table still means the road was built");
        flatBuilt.editRow(7,CellType.STAIR_UP);
        check(flatBuilt.editedRowCount()==1,"an edit on a flat-built road is counted");

        var synced=RoadPlan.load(flatBuilt.saveForSync());
        check(synced.hasApplied() && synced.editedRowCount()==1 && synced.pendingRows().containsKey(7),
                "a synced plan can still count its edits without the full snapshot");
        check(synced.confirmedRowCount()==flatBuilt.confirmedRowCount(),
                "the synced plan keeps the applied row count");

        var lampPlan=plan();
        lampPlan.setLamp(7,RowState.Lamp.CORNER);
        lampPlan.setLamp(8,RowState.Lamp.PLAIN);
        lampPlan.setLamp(9,RowState.Lamp.NONE);
        check(lampPlan.lampRows().get(7) && lampPlan.lampRows().get(8) && !lampPlan.lampRows().get(9),
                "the boolean view still answers whether a post stands there");
        check(lampPlan.row(7).lamp()==RowState.Lamp.CORNER,"a corner lamp keeps its own kind");
        var lampRound=RoadPlan.load(lampPlan.save());
        check(lampRound.row(7).lamp()==RowState.Lamp.CORNER && lampRound.row(9).lamp()==RowState.Lamp.NONE,
                "the lamp kind survives a save/load round trip");
        var legacyLamp=lampPlan.save();
        var legacyRows=legacyLamp.getList("rows",10);
        for(int i=0;i<legacyRows.size();i++) {
            var row=legacyRows.getCompound(i);
            if(row.getInt("along")!=7) continue;
            row.remove("lamp"); row.putBoolean("lamp",true);
        }
        legacyLamp.put("rows",legacyRows);
        check(RoadPlan.load(legacyLamp).row(7).lamp()==RowState.Lamp.PLAIN,
                "an old byte lamp value still reads as a regular lamp");

        // The frame moves; no row height may move with it.
        var framed=plan();
        framed.setAutoType(7,CellType.STAIR_UP);
        var beforeProfile=RoadHeightProfile.of(framed);
        final int targetRow=12;
        final int entryY=beforeProfile.row(targetRow).entryY();
        final BlockPos anchorBefore=framed.anchorPos();
        final BlockPos delta=framed.reanchorTo(targetRow,entryY);
        check(delta!=null && RoadHeightProfile.anchorAlong(framed)==targetRow,"the frame moves to the chosen row");
        var afterProfile=RoadHeightProfile.of(framed);
        boolean heightsKept=true;
        for(int row=framed.minAlong();row<=framed.maxAlong();row++)
            heightsKept&=afterProfile.row(row).entryY()==beforeProfile.row(row).entryY()
                    && afterProfile.row(row).baseY()==beforeProfile.row(row).baseY();
        check(heightsKept,"every row height survives the frame move");
        check(framed.reanchorTo(7,afterProfile.row(7).entryY())==null,"a stair row cannot become the anchor");
        RoadLockReconcile.apply(framed,List.of(),id -> null);
        check(framed.row(targetRow).locked() && framed.row(targetRow).lockY()==entryY-1,
                "the new anchor row is locked at its surface");
        check(!framed.row(10).locked(),"the old anchor row is released");
        var frameOffset=new BlockPos(3,2,1);
        check(framed.anchorPos().subtract(frameOffset.offset(delta)).equals(anchorBefore.subtract(frameOffset)),
                "shifting a blueprint frame keeps its world origin");

        var stale=new net.minecraft.nbt.ListTag();
        var oldRow=new net.minecraft.nbt.CompoundTag();
        oldRow.putInt("along",3); oldRow.putString("type","FLAT");
        stale.add(oldRow);
        var tag=plan().save();
        tag.put("rows",stale);
        boolean threw=false;
        try { RoadPlan.load(tag); } catch(IllegalArgumentException ex) { threw=ex.getMessage().contains("level"); }
        check(threw,"a save without a level is rejected instead of being read as flat");

        var legacy=plan().save();
        var legacyPlanning=legacy.getCompound("planning");
        legacyPlanning.put("locks",new net.minecraft.nbt.ListTag());
        legacy.put("planning",legacyPlanning);
        boolean legacyThrew=false;
        try { RoadPlan.load(legacy); } catch(IllegalArgumentException ex) { legacyThrew=ex.getMessage().contains("row-level"); }
        check(legacyThrew,"a save still carrying the retired row tables is rejected");

        System.out.println("PASS "+checks+" row state assertions");
    }
}
