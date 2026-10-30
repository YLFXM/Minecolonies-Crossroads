import com.YLFXM.mcreconstruction.road.*;
import com.YLFXM.mcreconstruction.network.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.Level;

/** Operation state, source intent and durable NBT redo boundaries; no game process. */
public final class RoadJointRegression {
    private static int checks;
    private static void check(boolean value,String label) {checks++;if(!value)throw new AssertionError(label);}
    private static RoadPlan road() {
        var road=new RoadPlan(UUID.randomUUID(),Level.OVERWORLD,new BlockPos(0,65,0),new BlockPos(0,65,-1),Direction.SOUTH,false,"minecolonies","street",4,WalkRange.of(1,2),1,4);
        road.addSegment(new RoadSegment(new BlockPos(-12,64,0),25,Direction.Axis.X,0,SegmentKind.ROAD));road.setState(RoadState.PLANNED);return road;
    }
    public static void main(String[] args) throws Exception {
        var p=road();p.planning().baseRows(Map.of(-6,CellType.STAIR_UP,-5,CellType.STAIR_DOWN));
        p.adoptCommittedRows(Map.of(-6,CellType.STAIR_UP,-5,CellType.STAIR_DOWN,7,CellType.STAIR_UP,10,CellType.STAIR_DOWN));
        p.planning().manual(-6,CellType.FLAT);p.setRowType(-6,CellType.FLAT);
        var source=RoadAutoPlanning.baseSource(p);
        check(!source.rowOverrides().containsKey(7),"derived arch removed from source curve");
        check(source.cellType(-6)==CellType.FLAT,"explicit manual FLAT overlays original base");
        check(source.cellType(-5)==CellType.STAIR_DOWN,"unrelated original slope survives");
        check(p.rowOverrides().containsKey(7),"restoration builds a proposal without mutating actual rows");
        var restored=RoadPlan.load(p.save());
        check(restored.planning().hasBase() && restored.planning().baseRows().equals(p.planning().baseRows()),"source curve survives restart");
        var flat=road();flat.planning().baseRows(Map.of());flat.adoptCommittedRows(Map.of(4,CellType.STAIR_UP));
        check(RoadAutoPlanning.baseSource(RoadPlan.load(flat.save())).rowOverrides().isEmpty(),"empty flat base distinct from missing source");
        var peer=road();peer.adoptCommittedRows(Map.of(2,CellType.STAIR_UP));peer.setRowType(8,CellType.STAIR_DOWN);peer.setRowType(2,CellType.FLAT);
        var actual=road();actual.adoptCommittedRows(Map.of(2,CellType.STAIR_DOWN,4,CellType.STAIR_UP));
        RoadJointWork.commitNeighborRows(peer,actual);
        check(peer.rowOverrides().equals(actual.rowOverrides()),"peer committed rows describe local actual work");
        check(peer.cellType(8)==CellType.STAIR_DOWN && peer.cellType(2)==CellType.FLAT,"unrelated pending edits and explicit flat survive rebase");
        var once=peer.save();RoadJointWork.commitNeighborRows(peer,actual);
        check(peer.pendingRows().equals(RoadPlan.load(once).pendingRows()),"repeated commit does not consume pending edits");
        var session=RoadRepairSession.prepare(p,UUID.randomUUID(),100);
        CompoundTag work=new CompoundTag();work.putString("fixture","frozen work, not a new terrain sample");
        session.setWorkBlueprint(work);session.setJointBlueprints(work,new CompoundTag());
        CompoundTag dependencies=new CompoundTag();dependencies.putUUID("fixture",UUID.randomUUID());session.setDependencies(dependencies);
        session.setClearTargets(List.of(new BlockPos(3,70,2)));session.start(RoadRepairSession.Phase.NATIVE,101);
        session.finishNative();var loaded=RoadRepairSession.load(session.save());
        check(!loaded.nativeDone(),"queue completion never becomes durable proof");
        check(loaded.workBlueprint().equals(work),"exact composite blueprint survives restart");
        check(loaded.clearTargets().equals(session.clearTargets()),"frozen clear targets survive restart");
        var owner=UUID.randomUUID();var retry=RoadRepairSession.retry(loaded,owner,200);
        check(!retry.token().equals(loaded.token()) && retry.owner().equals(owner),"retry has fresh owner-bound token");
        check(retry.phase()==RoadRepairSession.Phase.PREPARED,"retry requires new explicit native confirmation");
        check(retry.target().save().equals(loaded.target().save()),"retry uses exact frozen road profile");
        check(retry.workBlueprint().equals(work) && retry.dependencies().equals(dependencies),"retry preserves target and dependencies");
        work.putString("fixture","changed externally");check(!loaded.workBlueprint().equals(work),"session owns its blueprint data");
        retry.start(RoadRepairSession.Phase.SURVIVAL,201);retry.markCommitting();
        check(RoadRepairSession.load(retry.save()).committing(),"redo phase persists independently of native completion");
        try {RoadRepairSession.retry(retry,owner,300);throw new AssertionError("commit replaced by new work");}catch(IllegalArgumentException expected){checks++;}
        p.setInterruptedConstruction(loaded.save());
        check(!RoadRules.canEdit(p) && !RoadRules.canToggleEdit(p),"unfinished work blocks proposal edits");
        check(RoadRules.canRepair(p) && RoadRules.canRemove(p),"unfinished work retains retry and demolition exits");
        var sync=p.saveForSync();check(!sync.contains("interruptedConstruction") && sync.getBoolean("interrupted"),"client receives only unfinished-work marker");
        check(!RoadRules.canEdit(RoadPlan.load(sync)),"client edit policy honors unfinished-work marker");
        p.setInterruptedConstruction(null);check(RoadRules.canEdit(p),"successful recovery releases edit lock");
        check(RoadCrossings.hasWalkingClearance(65,67),"exact two-block collision clearance accepted");
        check(!RoadCrossings.hasWalkingClearance(65,66.5),"half-block intrusion rejected");
        check(!RoadCrossings.hasWalkingClearance(65.5,67),"raised lower walking shape cannot be ignored");
        var partner=road();p.planning().crossing(partner.id(),new RoadPlanningState.Crossing(false,0,99,-12));
        var heightRestored=RoadPlan.load(p.save());
        check(heightRestored.planning().crossings().get(partner.id()).platform()==-12,"negative custom platform height survives NBT");
        check(RoadCrossings.setting(partner,heightRestored).platform()==-12,"reverse pair keeps the same absolute platform height");
        var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),RegistryAccess.EMPTY);
        try {
            var adjustment=new RoadPlanningPayload(p.id(),p.controllerPos(),new BlockPos(-12,64,0),new BlockPos(12,64,0),false,3,partner.id(),0,-1);
            RoadPlanningPayload.CODEC.encode(buffer,adjustment);
            check(adjustment.equals(RoadPlanningPayload.CODEC.decode(buffer)) && !buffer.isReadable(),"height adjustment packet preserves pair and signed delta");buffer.clear();
            var previewRequest=new RequestRoadPreviewPayload(p.controllerPos(),p.anchorPos(),new BlockPos(-12,64,0),new BlockPos(12,64,0),false,4,4,81,p.id(),partner.id());
            RequestRoadPreviewPayload.CODEC.encode(buffer,previewRequest);
            check(previewRequest.equals(RequestRoadPreviewPayload.CODEC.decode(buffer)) && !buffer.isReadable(),"joint preview packet preserves peer, road and request identity");buffer.clear();
            var confirm=new ConfirmRoadRepairPayload(p.id(),loaded.token(),0,"","pack","path",p.anchorPos(),0,true);
            ConfirmRoadRepairPayload.CODEC.encode(buffer,confirm);
            check(confirm.equals(ConfirmRoadRepairPayload.CODEC.decode(buffer)) && !buffer.isReadable(),"explicit joint consent survives network encoding");buffer.clear();
            var open=new OpenBuildToolPayload(p.id(),"path",new CompoundTag(),1,p.anchorPos(),loaded.token(),List.of("peer", "Y 64..67"));
            OpenBuildToolPayload.CODEC.encode(buffer,open);var decoded=OpenBuildToolPayload.CODEC.decode(buffer);
            check(decoded.jointImpact().equals(open.jointImpact()) && !buffer.isReadable(),"affected road ranges survive the native-tool packet");
        } finally {buffer.release();}
        Path directory=Files.createTempDirectory(Path.of("build"),"road-joint-regression-");Path file=directory.resolve("commit.nbt");
        try {
            CompoundTag record=new CompoundTag();record.put("session",loaded.save());record.putUUID("road",p.id());
            RoadAtomicNbt.write(file,record);check(record.equals(RoadAtomicNbt.read(file)),"durable record round-trips frozen session");
            CompoundTag next=record.copy();next.putBoolean("second",true);RoadAtomicNbt.write(file,next);
            check(next.equals(RoadAtomicNbt.read(file)),"atomic replacement commits complete next record");
            Path blocked=directory.resolve("blocked");Files.createDirectories(blocked);Files.writeString(blocked.resolve("keep"),"retained");
            try {RoadAtomicNbt.write(blocked,record);throw new AssertionError("nonempty directory overwritten");}catch(java.io.IOException expected){checks++;}
            check(next.equals(RoadAtomicNbt.read(file)),"failed unrelated write leaves previous commit intact");
            try(var files=Files.list(directory)){check(files.noneMatch(path -> path.toString().endsWith(".tmp")),"failed write removes its temporary file");}
            Files.delete(blocked.resolve("keep"));Files.delete(blocked);
        } finally {Files.deleteIfExists(file);Files.deleteIfExists(directory);}
        var random=new Random(1717);
        for(int fixture=0;fixture<12;fixture++) {
            int[] terrain=new int[21];int h=65;Map<Integer,Integer> pins=new HashMap<>();
            for(int i=10;i<21;i++){terrain[i]=h;if(i==10 || i==16)pins.put(i,h);h+=i==10 ? 0 : random.nextInt(3)-1;}
            h=65;for(int i=9;i>=0;i--){h+=random.nextInt(3)-1;terrain[i]=h;}
            var result=RoadSlopeSolver.solve(terrain,10,65,pins,Set.of(),Map.of(),40,90);int[] heights=result.heights();var rows=result.rows();
            check(heights[10]==65 && rows[10]==CellType.FLAT,"generated profile retains fixed anchor "+fixture);
            check(heights[16]==pins.get(16),"generated profile reaches independent fixed platform "+fixture);
            boolean continuous=true;for(int i=0;i<20;i++)continuous&=heights[i]+rows[i].rise()==heights[i+1];
            check(continuous,"generated profile is continuous in both directions "+fixture);
        }
        System.out.println("PASS "+checks+" joint/recovery assertions");
    }
}
