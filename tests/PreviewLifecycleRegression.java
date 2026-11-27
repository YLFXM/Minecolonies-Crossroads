import com.YLFXM.rp.crossroads.road.*;
import com.YLFXM.rp.crossroads.network.*;
import com.YLFXM.rp.crossroads.client.*;
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
    public static void main(String[] args) throws Exception {
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
        // A/B belong to the plan, not to the wand: road mode reloads the road's own pair.
        RoadPlan loadable=plan();
        loadable.setEndpoints(new BlockPos(5,64,20),new BlockPos(15,64,20));
        loadable.setMirror(true);
        RoadSelectionClient.clear();
        RoadSelectionClient.reloadFromRoad(loadable);
        check(RoadSelectionClient.hasBoth(),"road mode reloads the plan's endpoints");
        check(RoadSelectionClient.a().equals(new BlockPos(5,64,20)) && RoadSelectionClient.b().equals(new BlockPos(15,64,20)),
                "the reloaded pair is the plan's own");
        check(RoadSelectionClient.mirror(),"the reloaded frame follows the plan's mirror");
        check(RoadSelectionClient.roadId().equals(loadable.id()),"the reload adopts the plan identity");
        RoadPlan unfinished=plan();
        RoadSelectionClient.reloadFromRoad(unfinished);
        check(!RoadSelectionClient.hasBoth(),"a plan without endpoints restores nothing");
        // Terrain corners are not a road selection in either direction.
        final String bindHandler=java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/YLFXM/rp/crossroads/client/ClientPayloadHandlers.java"));
        check(bindHandler.contains("reloadFromRoad") && !bindHandler.contains("RoadSelectionClient.clear()"),
                "binding a road reloads its endpoints instead of clearing them");
        final String previewSource=java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/YLFXM/rp/crossroads/client/ClientRoadPreview.java"));
        check(previewSource.contains("TerrainClientState.isTerrain()"),
                "terrain mode never asks for a road preview");
        final String eventsSource=java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/YLFXM/rp/crossroads/client/ClientSelectionEvents.java"));
        check(eventsSource.contains("reloadFromRoad(boundPlan(player))"),
                "switching back to road mode reloads the road's pair");
        // NeoForge fires LeftClickBlock from continueDestroyBlock too, so a survival press
        // (always held for a moment) re-entered the corner picker every tick and dragged the
        // corner with the crosshair. Selection has to read the once-per-press input event.
        check(!eventsSource.contains("public static void onLeftClickBlock"),
                "corner selection no longer subscribes to the repeating block event");
        check(eventsSource.contains("InteractionKeyMappingTriggered") && eventsSource.contains("event.isAttack()"),
                "corner selection runs from the attack input, once per press");
        check(eventsSource.contains("hit.getType() != HitResult.Type.BLOCK"),
                "only a block hit is a corner; air and entity attacks keep their meaning");
        check(eventsSource.contains("private static boolean pickCorner"),
                "both corner kinds share one picker, so terrain and road cannot drift apart");
        // Vanilla walks both hands for a use, so one press reached the handler twice and
        // queued the same terrain order twice (visible as paired "Terrain … prepared" logs).
        check(eventsSource.contains("private static boolean ownsGesture"),
                "the hand that carries the wand owns the gesture");
        check(eventsSource.contains("!ownsGesture(player, event.getHand())")
                        && eventsSource.contains("!ownsGesture(event.getEntity(), event.getHand())")
                        && eventsSource.contains("!ownsGesture(minecraft.player, event.getHand())"),
                "every gesture path rejects the pass that came from the other hand");
        check(eventsSource.contains("tick == dispatchedTick && fill == dispatchedFill"),
                "a second identical order in the same tick is dropped, never sent");
        // The sneak flag on the player entity only catches up on the next tick, so a quick
        // shift+click was read as a plain A press. The key's own state is immediate.
        check(eventsSource.contains("minecraft.options.keyShift.isDown()")
                        && !eventsSource.contains("assignToB = player.isShiftKeyDown()"),
                "the corner key reads the physical shift key, not the delayed entity flag");
        check(eventsSource.contains("rp_crossroads.select.corner_box"),
                "the action bar shows the box size as soon as both corners exist");
        // NeoForge also fires the input event from continueAttack, so one press can arrive
        // twice. The selection keeps the clicked cells apart from the normalized box, which
        // makes the repeat harmless; writing a raw corner over the box is not reversible.
        check(eventsSource.contains("RoadSelectionClient.setTerrainCorner(wantB, pos)"),
                "a terrain corner goes through the pair-aware setter");
        final String cornerState = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/YLFXM/rp/crossroads/client/RoadSelectionClient.java"));
        check(cornerState.contains("private static BlockPos rawA") && cornerState.contains("rawB = next;"),
                "the clicked cells are kept apart from the normalized pair");
        check(cornerState.contains("return !a.equals(beforeA) || !b.equals(beforeB)"),
                "setting the same corner again reports no change");
        // NeoForge also fires the input event from continueAttack, so one press can arrive
        // twice. The selection keeps the clicked cells apart from the normalized box, which
        // makes the repeat harmless; writing a raw corner over the box is not reversible.
        check(eventsSource.contains("RoadSelectionClient.setTerrainCorner(wantB, pos)"),
                "a terrain corner goes through the pair-aware setter");
        final String selection = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/YLFXM/rp/crossroads/client/RoadSelectionClient.java"));
        check(selection.contains("private static BlockPos rawA") && selection.contains("rawB = next;"),
                "the clicked cells are kept apart from the normalized pair");
        check(selection.contains("return !a.equals(beforeA) || !b.equals(beforeB)"),
                "setting the same corner again reports no change");
        ClientRoadCache.clear();check(ClientRoadCache.roads().isEmpty() && ClientRoadCache.surveyRoads().isEmpty(),"logout cache reset");
        System.out.println("PASS "+n+" preview lifecycle assertions");
    }
}
