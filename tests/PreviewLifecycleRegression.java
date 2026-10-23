import com.YLFXM.mcreconstruction.road.*;
import com.YLFXM.mcreconstruction.network.*;
import com.YLFXM.mcreconstruction.client.*;
import net.minecraft.nbt.*;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import java.util.*;
public class PreviewLifecycleRegression {
    static int n;
    static void check(boolean ok,String name){n++;if(!ok)throw new AssertionError(name);}
    static RoadPlan plan(){return new RoadPlan(UUID.randomUUID(),Level.OVERWORLD,new BlockPos(10,65,20),Direction.NORTH,false,"minecolonies","street",4,WalkRange.of(1,2),1,4);}
    static CompoundTag sync(RoadPlan a,RoadPlan b,boolean scoped){
        CompoundTag t=new CompoundTag();ListTag roads=new ListTag();roads.add(a.save());roads.add(b.save());t.put("roads",roads);
        t.putString("dimension","minecraft:overworld");t.putBoolean("surveyView",scoped);t.putBoolean("bound",true);t.putString("colony","Test");
        ListTag scope=new ListTag();scope.add(StringTag.valueOf(b.id().toString()));t.put("surveyIds",scope);return t;
    }
    public static void main(String[] args){
        RoadPlan a=plan(),b=plan();b.setControllerPos(new BlockPos(20,65,20));
        ClientRoadCache.set(sync(a,b,true));
        check(ClientRoadCache.roads().size()==2,"full index survives scoped surveyor response");
        check(ClientRoadCache.surveyRoads().size()==1 && ClientRoadCache.surveyRoads().get(0).id().equals(b.id()),"surveyor scope is separate");
        ClientRoadCache.set(sync(a,b,false));
        check(ClientRoadCache.bound() && ClientRoadCache.surveyRoads().size()==1,"wand refresh preserves survey scope");
        ClientRoadCache.clearSurvey();check(ClientRoadCache.roads().size()==2,"clearing survey view does not erase Wand lookup");
        check(ClientRoadCache.findByController(a.controllerPos())!=null,"Wand can still resolve bound road");
        RoadSelectionClient.bindTo(a);RoadSelectionClient.setA(new BlockPos(5,64,20));RoadSelectionClient.setB(new BlockPos(15,64,20));
        RoadSelectionClient.bindTo(a);check(RoadSelectionClient.hasBoth(),"same identity retains selection");
        RoadSelectionClient.bindTo(b);check(!RoadSelectionClient.hasBoth(),"another road cannot inherit old endpoints");
        RoadSelectionClient.setA(new BlockPos(5,64,20));RoadSelectionClient.setB(new BlockPos(15,64,20));
        RoadSelectionClient.removed(a.id());check(RoadSelectionClient.hasBoth(),"deleting other road preserves active selection");
        RoadSelectionClient.removed(b.id());check(!RoadSelectionClient.hasBoth() && RoadSelectionClient.roadId()==null,"deletion clears matching selection");
        ClientRoadCache.remove(b.id());check(ClientRoadCache.roads().size()==1,"explicit deletion evicts only identified road");
        long seq=ClientRoadPreviewRenderer.nextSequence();ClientRoadPreviewRenderer.expect(a.id(),seq);
        check(!ClientRoadPreviewRenderer.accept(new RoadPreviewPayload(new CompoundTag(),BlockPos.ZERO,seq,b.id(),"minecraft:overworld")),"wrong identity reply rejected");
        ClientRoadPreviewRenderer.clear();
        check(!ClientRoadPreviewRenderer.accept(new RoadPreviewPayload(new CompoundTag(),BlockPos.ZERO,seq,a.id(),"minecraft:overworld")),"reply after hide/delete rejected");
        check(!ClientRoadPreviewRenderer.isVisible(),"missing ghost never suppresses fallback");
        ClientRoadCache.expectSurvey("slot-A");
        CompoundTag first=sync(a,b,true);first.putString("surveyContext","slot-A");ClientRoadCache.set(first);
        check(ClientRoadCache.surveyRoads().size()==1,"first surveyor context accepted");
        ClientRoadCache.expectSurvey("slot-B");
        check(ClientRoadCache.surveyRoads().isEmpty(),"direct item switch clears old scope");
        ClientRoadCache.set(first);check(ClientRoadCache.surveyRoads().isEmpty(),"late old-slot scope rejected");
        CompoundTag second=sync(a,b,true);second.putString("surveyContext","slot-B");ClientRoadCache.set(second);
        check(ClientRoadCache.surveyRoads().size()==1,"new-slot scope accepted");
        net.minecraft.world.phys.AABB range=new net.minecraft.world.phys.AABB(0,0,0,1000,10,4);
        check(RoadRenderRange.contains(range,new net.minecraft.world.phys.Vec3(-1,1,1)),"long-road near endpoint visible");
        check(RoadRenderRange.contains(range,new net.minecraft.world.phys.Vec3(-64,1,1)),"distance threshold inclusive");
        check(!RoadRenderRange.contains(range,new net.minecraft.world.phys.Vec3(-65,1,1)),"beyond nearest-boundary threshold hidden");
        check(!RoadRenderRange.contains(range,new net.minecraft.world.phys.Vec3(500,1,300)),"lateral distance gated");
        ClientRoadCache.clear();check(ClientRoadCache.roads().isEmpty() && ClientRoadCache.surveyRoads().isEmpty(),"logout cache reset");
        System.out.println("PASS "+n+" preview lifecycle assertions");
    }
}
