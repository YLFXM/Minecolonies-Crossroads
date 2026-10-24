import com.YLFXM.mcreconstruction.road.*;
import com.YLFXM.mcreconstruction.network.*;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import java.util.*;

public final class Round1Regression {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args) {
        String pack="Reconstruction Plan",path="generated/River-abcd.blueprint";
        BlockPos anchor=new BlockPos(10,65,20);
        check(RoadRepairSelection.validate(pack,path,anchor,0,pack,"generated\\River-abcd.blueprint",anchor,0)==RoadRepairSelection.Mismatch.NONE,"Windows native relative path is accepted");
        check(RoadRepairSelection.validate(pack,path,anchor,0,pack,path,anchor,0)==RoadRepairSelection.Mismatch.NONE,"Unix relative path is accepted");
        check(RoadRepairSelection.validate(pack,path,anchor,0,"foreign",path,anchor,0)==RoadRepairSelection.Mismatch.PACK,"pack restriction retained");
        check(RoadRepairSelection.validate(pack,path,anchor,0,pack,"generated/Other.blueprint",anchor,0)==RoadRepairSelection.Mismatch.PATH,"different road cannot repair this session");
        check(RoadRepairSelection.validate(pack,path,anchor,0,pack,path,anchor.above(),0)==RoadRepairSelection.Mismatch.ANCHOR,"height offset is not silently forgiven");
        check(RoadRepairSelection.validate(pack,path,anchor,0,pack,path,anchor,1)==RoadRepairSelection.Mismatch.ROTATION,"rotation restriction retained");
        check(RoadRepairSelection.validate(pack,path,anchor,0,pack,"generated/../generated/River-abcd.blueprint",anchor,0)==RoadRepairSelection.Mismatch.PATH,"path traversal is not normalised into a match");
        var json=com.google.gson.JsonParser.parseString("{\"name\":\"Reconstruction Plan\",\"icon\":\"\",\"authors\":[],\"desc\":\"\",\"mods\":[],\"version\":1,\"pack-format\":1}").getAsJsonObject();
        var root=java.nio.file.Path.of("D:/packs/Reconstruction Plan");
        var meta=new com.ldtteam.structurize.storage.StructurePackMeta(json,root,"Local");
        String nativePath=meta.getSubPath(root.resolve("generated").resolve("River-abcd.blueprint"));
        check(RoadRepairSelection.path(nativePath).equals(path),"installed native pack path matches wire path after separator conversion");
        var road=new RoadPlan(UUID.randomUUID(),Level.OVERWORLD,anchor,anchor.north(),Direction.SOUTH,false,pack,"street",4,WalkRange.of(1,2),1,4);
        road.setState(RoadState.PLANNED);
        road.setRepairSession(RoadRepairSession.prepare(road,UUID.randomUUID(),0));
        var authorized=new OpenRoadPlanPayload(road.controllerPos(),road.saveForSync(),false);
        var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),RegistryAccess.EMPTY);
        try {
            OpenRoadPlanPayload.CODEC.encode(buffer,authorized);
            var decoded=OpenRoadPlanPayload.CODEC.decode(buffer);
            check(decoded.equals(authorized) && !buffer.isReadable(),"authorization reply carries plan snapshot atomically");
            check(!decoded.plan().contains("repairSession") && decoded.plan().getBoolean("repairBusy"),"window reply retains lock without private target");
            ClientRoadCache.clear();ClientRoadCache.rememberAuthorized(RoadPlan.load(decoded.plan()));
            check(ClientRoadCache.findByController(road.controllerPos())!=null && ClientRoadCache.canManage(road.id()),"authorized snapshot provides panel data before list arrives");
            var moved=RoadPlan.load(road.saveForSync());moved.setControllerPos(anchor.east());ClientRoadCache.rememberAuthorized(moved);
            check(ClientRoadCache.roads().size()==1 && ClientRoadCache.findByController(road.controllerPos())==null,"repeated authorized snapshot replaces the old identity");
            buffer.clear();
            var nativeWindow=new OpenRoadPlanPayload(anchor,new net.minecraft.nbt.CompoundTag(),true);
            OpenRoadPlanPayload.CODEC.encode(buffer,nativeWindow);
            check(nativeWindow.equals(OpenRoadPlanPayload.CODEC.decode(buffer)) && !buffer.isReadable(),"ordinary decoration window has explicit server-authorized route");
            buffer.clear();
            var request=new RequestControllerWindowPayload(anchor);
            RequestControllerWindowPayload.CODEC.encode(buffer,request);
            check(request.equals(RequestControllerWindowPayload.CODEC.decode(buffer)) && !buffer.isReadable(),"controller request round trip is distinct from window approval");
        } finally {buffer.release();ClientRoadCache.clear();}
        System.out.println("PASS "+checks+" round1 regression assertions");
    }
}
