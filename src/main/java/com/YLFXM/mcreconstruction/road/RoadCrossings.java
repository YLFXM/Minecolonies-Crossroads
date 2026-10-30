/* SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 ZhuanZ (YLFXM) */
package com.YLFXM.mcreconstruction.road;

import java.util.*;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.blueprints.v1.BlueprintUtil;
import com.YLFXM.mcreconstruction.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Exact junction geometry and per-world-position native write/removal protection. */
public final class RoadCrossings {
    private record Context(Map<UUID,RoadPlan> models,Map<UUID,Blueprint> blueprints,UUID operation) { }
    private static final ThreadLocal<Context> CONTEXT=new ThreadLocal<>();
    public static final class Scope implements AutoCloseable {
        private final Context before;
        private Scope(Context next) { before=CONTEXT.get();CONTEXT.set(next); }
        @Override public void close() { if(before==null)CONTEXT.remove();else CONTEXT.set(before); }
    }
    public static Scope scope(Map<UUID,RoadPlan> models,Map<UUID,Blueprint> blueprints) {return new Scope(new Context(Map.copyOf(models),Map.copyOf(blueprints),null));}
    public static Scope scope(Map<UUID,RoadPlan> models,Map<UUID,Blueprint> blueprints,UUID operation) {
        return new Scope(new Context(Map.copyOf(models),Map.copyOf(blueprints),operation));
    }
    public record Neighbor(RoadPlan live,RoadPlan model,Blueprint applied,RoadCrossingGeometry.Rect body,
                           RoadCrossingGeometry.Rect walk,RoadPlanningState.Crossing setting) { }
    public static RoadPlan reference(ServerLevel level,RoadPlan road) {
        var context=CONTEXT.get();if(context!=null && context.models().containsKey(road.id()))return context.models().get(road.id());
        var applied=RoadBlueprintStore.appliedPlan(level,road);return applied==null ? road : applied;
    }
    public static RoadPlan planningReference(ServerLevel level,RoadPlan own,RoadPlan other) {
        var reference=reference(level,other);
        return planningReference(own,other,reference);
    }
    static RoadPlan planningReference(RoadPlan own,RoadPlan live,RoadPlan actual) {
        return RoadCrossingGeometry.body(own).intersect(RoadCrossingGeometry.body(actual))==null
                && RoadCrossingGeometry.body(own).intersect(RoadCrossingGeometry.body(live))!=null ? live:actual;
    }
    static Neighbor neighbor(RoadPlan own,RoadPlan live,RoadPlan actual,Blueprint actualImage) {
        var context=CONTEXT.get();RoadPlan override=context==null ? null:context.models().get(live.id());
        RoadPlan model=override!=null ? override:planningReference(own,live,actual==null ? live:actual);
        var body=RoadCrossingGeometry.body(own).intersect(RoadCrossingGeometry.body(model));if(body==null)return null;
        Blueprint image=context==null ? null:context.blueprints().get(live.id());
        boolean physical=actual!=null && RoadCrossingGeometry.body(own).intersect(RoadCrossingGeometry.body(actual))!=null;
        return new Neighbor(live,model,image!=null ? image:physical ? actualImage:null,body,
                RoadCrossingGeometry.walk(own).intersect(RoadCrossingGeometry.walk(model)),setting(own,override==null ? live:override));
    }
    static boolean isScoped(UUID road) {return CONTEXT.get()!=null && CONTEXT.get().models().containsKey(road);}
    static boolean hasScope() {return CONTEXT.get()!=null;}
    public static RoadPlanningState.Crossing setting(RoadPlan own,RoadPlan other) {
        // Derive a reciprocal strict relation only inside this operation. Neither
        // the foreign live proposal nor its applied snapshot is changed.
        var context=CONTEXT.get();
        if(context!=null && other.id().equals(context.operation())) {
            var primary=context.models().get(other.id());
            if(primary!=null && primary.planning().local(own.id()) && primary.planning().policy(own.id())==2) {
                var strict=primary.planning().crossings().get(own.id());
                if(strict!=null && strict.layered())return new RoadPlanningState.Crossing(true,-strict.order(),strict.version(),strict.platform());
            }
        }
        var a=own.planning().crossings().get(other.id());var b=other.planning().crossings().get(own.id());
        if(other.planning().local(own.id()))b=null; // Unapproved/strict planning cannot change the foreign road's relation.
        if(b!=null && (a==null || b.version()>a.version()))
            return new RoadPlanningState.Crossing(b.layered(),-b.order(),b.version(),b.platform());
        return a==null ? new RoadPlanningState.Crossing(false,0) : a;
    }
    public static List<Neighbor> neighbors(ServerLevel level,RoadPlan plan) {
        List<Neighbor> result=new ArrayList<>();
        for(var other:RoadManager.get(level).all()) {
            if(other.id().equals(plan.id()) || !other.dimension().equals(plan.dimension())) continue;
            Context context=CONTEXT.get();RoadPlan override=context==null ? null : context.models().get(other.id());
            RoadPlan proposed=override==null ? other : override;
            if(proposed.segments().isEmpty()) continue;
            var proposedOverlap=RoadCrossingGeometry.body(plan).intersect(RoadCrossingGeometry.body(proposed));
            var actual=other.appliedBounds();var ownBounds=RoadCrossingGeometry.body(plan);
            boolean actualOverlap=actual!=null && ownBounds.minX()<=actual.maxX() && ownBounds.maxX()>=actual.minX()
                    && ownBounds.minZ()<=actual.maxZ() && ownBounds.maxZ()>=actual.minZ();
            if(proposedOverlap==null && !actualOverlap) continue;
            RoadPlan applied=RoadBlueprintStore.appliedPlan(level,other);
            var neighbor=neighbor(plan,other,applied,applied==null ? null:RoadBlueprintStore.loadApplied(level,other));
            if(neighbor!=null)result.add(neighbor);
        }
        result.sort(Comparator.comparing(n -> n.live().id()));return List.copyOf(result);
    }
    public static void validate(RoadPlan plan,Neighbor n) {
        if(plan.axis()==n.model().axis()) throw new IllegalArgumentException("Parallel road overlap is not a junction.");
        if(n.walk()==null) return; // Edge contact is not a connected walking junction.
        if(!n.setting().layered()) {
            for(var controller:List.of(plan.controllerPos(),RoadControllerMigration.target(plan),n.model().controllerPos(),RoadControllerMigration.target(n.model())))
                if(n.walk().contains(controller.getX(),controller.getZ()))
                    throw new IllegalArgumentException("A controller would obstruct the junction; move the crossing away from its anchor.");
            if(RoadCrossingGeometry.platform(plan,n.walk())!=RoadCrossingGeometry.platform(n.model(),n.walk()))
                throw new IllegalArgumentException("Junction height must match "+RoadNames.label(n.model())+" exactly; rebalance the approach.");
        } else {
            var a=RoadHeightProfile.of(plan);var b=RoadHeightProfile.of(n.model());int order=0;
            for(int x=n.body().minX();x<=n.body().maxX();x++) for(int z=n.body().minZ();z<=n.body().maxZ();z++) {
                int ya=a.row(plan.axis()==net.minecraft.core.Direction.Axis.X ? x : z).baseY();
                int yb=b.row(n.model().axis()==net.minecraft.core.Direction.Axis.X ? x : z).baseY();
                int sign=Integer.signum(ya-yb);
                if(Math.abs((long)ya-yb)<3 || order!=0 && sign!=order || n.setting().order()!=0 && sign!=n.setting().order())
                    throw new IllegalArgumentException("Layered crossings need a consistent upper road and at least 3 blocks of surface separation everywhere.");
                order=sign;
            }
        }
    }
    public static void validateFinal(ServerLevel level,ServerPlayer player,RoadPlan plan) {
        validateGeometry(level,plan);
        for(var n:neighbors(level,plan))if(!RoadPermissions.canManage(level,player,n.live()))throw new IllegalArgumentException("Management permission is required for every affected road.");
    }
    static void validateGeometry(ServerLevel level,RoadPlan plan) {
        for(var n:neighbors(level,plan)) {
            validate(plan,n);
            boolean ownOperation=CONTEXT.get()!=null && n.live().id().equals(CONTEXT.get().operation());
            if(!ownOperation && (n.live().isRepairBusy() || n.live().state()==RoadState.BUILDING || n.live().state()==RoadState.REMOVING))
                throw new IllegalArgumentException("A crossing road is busy: "+RoadNames.label(n.live()));
        }
    }
    public static boolean hasWalkingClearance(double lowerTop,double upperBottom) {return upperBottom-lowerTop>=2.0-1.0e-7;}
    /** Inspect the final decorated cells, including real stairs/kerbs, after bridge fill has been removed. */
    public static void validateClearance(ServerLevel level,RoadPlan plan,Blueprint blueprint) {
        for(var n:neighbors(level,plan)) {
            if(!n.setting().layered() || n.applied()==null)continue;
            var own=RoadHeightProfile.of(plan);var peer=RoadHeightProfile.of(n.model());
            for(int x=n.body().minX();x<=n.body().maxX();x++)for(int z=n.body().minZ();z<=n.body().maxZ();z++) {
                int a=own.row(plan.axis()==net.minecraft.core.Direction.Axis.X ? x : z).baseY();
                int b=peer.row(n.model().axis()==net.minecraft.core.Direction.Axis.X ? x : z).baseY();
                RoadPlan lower=a<b ? plan : n.model(),upper=a<b ? n.model() : plan;
                if(!RoadCrossingGeometry.walk(lower).contains(x,z))continue;
                Blueprint lowerImage=a<b ? blueprint : n.applied(),upperImage=a<b ? n.applied() : blueprint;
                int floor=Math.min(a,b);var floorPos=new BlockPos(x,floor,z);
                var shape=stateAt(lowerImage,lower,floorPos).getCollisionShape(level,floorPos);
                double top=floor+(shape.isEmpty() ? 1 : shape.max(net.minecraft.core.Direction.Axis.Y));
                var origin=RoadBlueprintOps.origin(upper,upperImage);
                for(var info:upperImage.getBlockInfoAsList()) {
                    if(!RoadBlueprintOps.isConcrete(info.getState()) && !RoadBlueprintOps.isSolidFill(info.getState()))continue;
                    var pos=origin.offset(info.getPos());if(pos.getX()!=x || pos.getZ()!=z || pos.getY()<floor)continue;
                    var collision=info.getState().getCollisionShape(level,pos);
                    if(collision.isEmpty() && !RoadBlueprintOps.isSolidFill(info.getState()))continue;
                    double bottom=pos.getY()+(collision.isEmpty() ? 0 : collision.min(net.minecraft.core.Direction.Axis.Y));
                    if(!hasWalkingClearance(top,bottom))throw new IllegalArgumentException("The final bridge blocks intrude into the lower road's two-block walking clearance.");
                }
            }
        }
    }
    public static void freeze(ServerLevel level,RoadRepairSession session,RoadPlan target) {
        net.minecraft.nbt.CompoundTag dependencies=new net.minecraft.nbt.CompoundTag();
        for(var n:neighbors(level,target)) {
            target.planning().crossing(n.live().id(),n.setting());
            target.planning().local(n.live().id(),false); // Becomes globally factual only when this frozen target commits.
            var actual=RoadBlueprintStore.appliedPlan(level,n.live());
            dependencies.put(n.live().id().toString(),frozenDependency(n.live(),actual,false));
        }
        session.replacePreparedTarget(target);session.setDependencies(dependencies);
        RoadLockReconcile.apply(level,target);
    }
    static net.minecraft.nbt.CompoundTag frozenDependency(RoadPlan live,RoadPlan actual,boolean constraintOnly) {
        var data=new net.minecraft.nbt.CompoundTag();data.putBoolean("applied",actual!=null);
        data.put("model",(actual==null ? live:actual).geometrySnapshot());
        if(constraintOnly)data.putBoolean("constraintOnly",true);
        return data;
    }
    public static boolean verifyFrozen(ServerLevel level,RoadRepairSession session) {
        var dependencies=session.dependencies();
        for(String key:dependencies.getAllKeys()) {
            var live=RoadManager.get(level).get(UUID.fromString(key));if(live==null) return false;
            var data=dependencies.getCompound(key);RoadPlan actual=RoadBlueprintStore.appliedPlan(level,live);
            if(data.getBoolean("applied")!=(actual!=null)) return false;
            RoadPlan reference=actual==null ? live : actual;
            if(!reference.geometrySnapshot().equals(data.getCompound("model"))) return false;
        }
        return true;
    }
    public static void extendFrozen(ServerLevel level,RoadRepairSession session,net.minecraft.nbt.CompoundTag updates) {
        var dependencies=session.dependencies();
        for(String key:updates.getAllKeys()) {
            RoadPlan model=RoadPlan.load(updates.getCompound(key).getCompound("model"));
            for(var n:neighbors(level,model)) {
                if(n.live().id().equals(session.target().id()) || dependencies.contains(n.live().id().toString()))continue;
                var actual=RoadBlueprintStore.appliedPlan(level,n.live());
                dependencies.put(n.live().id().toString(),frozenDependency(n.live(),actual,true));
            }
        }
        session.setDependencies(dependencies);
    }
    public static boolean inUse(ServerLevel level,RoadPlan plan) {
        for(var other:RoadManager.get(level).all()) {
            if(other.id().equals(plan.id())) continue;
            if(other.interruptedConstruction()!=null) {
                var interrupted=RoadRepairSession.load(other.interruptedConstruction());
                if(interrupted.dependencies().contains(plan.id().toString()) || interrupted.neighborUpdates().contains(plan.id().toString()))return true;
            }
            boolean busy=other.isRepairBusy() || other.state()==RoadState.BUILDING || other.state()==RoadState.REMOVING;
            if(!busy) continue;
            if(other.repairSession()!=null && (other.repairSession().dependencies().contains(plan.id().toString())
                    || other.repairSession().neighborUpdates().contains(plan.id().toString())))return true;
            if(other.repairSession()!=null && other.repairSession().target().planning().crossings().containsKey(plan.id())) return true;
            if(!plan.segments().isEmpty() && !other.segments().isEmpty()
                    && RoadCrossingGeometry.body(plan).intersect(RoadCrossingGeometry.body(other))!=null) return true;
        }
        return false;
    }
    public static boolean flatLocked(ServerLevel level,RoadPlan plan,int row) {
        var locks=plan.planning().locks();
        if(!locks.isEmpty()) {var lock=locks.get(row);return lock!=null && !lock.anchor();}
        for(var n:neighbors(level,plan)) if(!n.setting().layered() && n.walk()!=null && n.applied()!=null
                && row>=n.walk().first(plan) && row<=n.walk().last(plan)) return true;
        return false;
    }
    public static BlockState stateAt(Blueprint blueprint,RoadPlan model,BlockPos world) {
        var p=world.subtract(RoadBlueprintOps.origin(model,blueprint));
        return inside(blueprint,p) ? blueprint.getBlockState(p) : Blocks.AIR.defaultBlockState();
    }
    public static boolean structuralAt(Blueprint image,RoadPlan model,BlockPos world) {
        var state=stateAt(image,model,world);
        if(RoadBlueprintOps.isConcrete(state))return true;
        return RoadBlueprintOps.isSolidFill(state) && deckCell(model,world);
    }
    /** Real blocks only: a terrain fill marker must never make another road defer. */
    public static boolean concreteAt(Blueprint image,RoadPlan model,BlockPos world) {
        return RoadBlueprintOps.isConcrete(stateAt(image,model,world));
    }
    public static boolean deckCell(RoadPlan model,BlockPos world) {
        if(!RoadCrossingGeometry.body(model).contains(world.getX(),world.getZ()))return false;
        int along=model.axis()==net.minecraft.core.Direction.Axis.X ? world.getX() : world.getZ();
        return world.getY()==RoadHeightProfile.of(model).row(along).baseY();
    }
    public static boolean inside(Blueprint b,BlockPos p) {
        return p.getX()>=0 && p.getY()>=0 && p.getZ()>=0 && p.getX()<b.getSizeX() && p.getY()<b.getSizeY() && p.getZ()<b.getSizeZ();
    }
    public static void write(Blueprint b,BlockPos p,BlockState state) {
        if(!inside(b,p)) return;b.addBlockState(p,state);b.getTileEntities()[p.getY()][p.getZ()][p.getX()]=null;
    }
    public static void decorate(ServerLevel level,RoadPlan plan,Blueprint out) {
        var origin=RoadBlueprintOps.origin(plan,out);var own=RoadHeightProfile.of(plan);List<String> issues=new ArrayList<>();
        final BlockState skip=com.ldtteam.structurize.blocks.ModBlocks.blockSubstitution.get().defaultBlockState();
        BlockState pavement=RoadUnitLibrary.get("road",level.registryAccess()).entries().stream()
                .filter(e -> e.pos().getY()==1 && e.pos().getZ()==plan.walkRange().from()).findFirst().orElseThrow().state();
        for(var n:neighbors(level,plan)) {
            try { validate(plan,n); } catch(IllegalArgumentException ex) {issues.add(ex.getMessage());}
            var profile=RoadHeightProfile.of(n.model());
            for(var info:out.getBlockInfoAsList()) {
                var world=origin.offset(info.getPos());if(!n.body().contains(world.getX(),world.getZ())) continue;
                int y=own.row(plan.axis()==net.minecraft.core.Direction.Axis.X ? world.getX() : world.getZ()).baseY();
                int otherY=profile.row(n.model().axis()==net.minecraft.core.Direction.Axis.X ? world.getX() : world.getZ()).baseY();
                if(n.setting().layered()) {
                    if(y>otherY && world.getY()<y && RoadBlueprintOps.isSolidFill(info.getState()))
                        write(out,info.getPos(),world.getY()>otherY ? Blocks.AIR.defaultBlockState() : skip);
                    if(n.applied()!=null && structuralAt(n.applied(),n.model(),world))
                        write(out,info.getPos(),skip);
                } else {
                    boolean walkCell=RoadCrossingGeometry.walk(n.model()).contains(world.getX(),world.getZ());
                    if(y==otherY && world.getY()==y && walkCell)
                        write(out,info.getPos(),pavement);
                    else if(n.applied()!=null && (info.getState().isAir() || RoadBlueprintOps.isSolidFill(info.getState()))
                            && concreteAt(n.applied(),n.model(),world)) write(out,info.getPos(),skip);
                }
            }
        }
        for(var n:neighbors(level,plan)) {
            write(out,n.live().controllerPos().subtract(origin),skip);
            write(out,n.live().anchorPos().subtract(origin),skip);
        }
        // addBlockState invalidates the offset cache, so restore it after all masking.
        write(out,RoadControllerMigration.target(plan).subtract(origin),skip);
        out.setCachePrimaryOffset(plan.anchorPos().subtract(origin));
        plan.planning().crossingIssue(String.join(" ",new LinkedHashSet<>(issues)));
    }
    public static Set<Integer> suppressedLamps(ServerLevel level,RoadPlan plan,UnitTemplate lamp,RoadHeightProfile profile) {
        Set<Integer> suppressed=new HashSet<>();var neighbors=neighbors(level,plan);
        for(var row:profile.rows()) for(var n:neighbors) {
            if(!n.setting().layered() && publicLamp(plan,n,row.along()))continue;
            var other=RoadHeightProfile.of(n.model());
            for(var e:lamp.entries()) {
                if(e.placeholder() || e.state().isAir()) continue;
                var p=RoadHeightProfile.position(plan,row.along(),e.pos().getZ(),row.baseY()+e.pos().getY()-lamp.surfaceY());
                if(!RoadCrossingGeometry.body(n.model()).contains(p.getX(),p.getZ())) continue;
                int floor=other.row(n.model().axis()==net.minecraft.core.Direction.Axis.X ? p.getX() : p.getZ()).baseY();
                boolean blocksWalk=RoadCrossingGeometry.walk(n.model()).contains(p.getX(),p.getZ()) && p.getY()>=floor && p.getY()<=floor+2;
                boolean hitsDeck=n.setting().layered() && row.baseY()<floor;
                boolean junctionCorner=!n.setting().layered() && n.walk()!=null;
                if(blocksWalk || hitsDeck || junctionCorner) {suppressed.add(row.along());break;}
            }
        }
        // Corner candidates come from the original spacing/overlap rules, not
        // from this suppression result, so removing an ordinary lamp cannot
        // recursively create another replacement lamp.
        Map<UUID,RoadPlan> sources=new HashMap<>();sources.put(plan.id(),plan);
        for(var n:neighbors)sources.put(n.model().id(),n.model());
        Set<Integer> ownCorners=forcedLamps(level,plan);
        for(var source:sources.values())for(int corner:forcedLamps(level,source)) {
            var cornerProfile=RoadHeightProfile.of(source);
            for(var row:profile.rows())if(regularLamp(plan,row.along()) && !ownCorners.contains(row.along())
                    && adjacentLamp(plan,row.along(),row.baseY(),source,corner,cornerProfile.row(corner).baseY(),lamp))
                suppressed.add(row.along());
        }
        return Set.copyOf(suppressed);
    }
    static boolean adjacentLamp(RoadPlan own,int row,int floor,RoadPlan corner,int cornerRow,int cornerFloor,UnitTemplate lamp) {
        var a=RoadHeightProfile.position(own,row,0,0);var b=RoadHeightProfile.position(corner,cornerRow,0,0);
        int low=Integer.MAX_VALUE,high=Integer.MIN_VALUE;
        for(var e:lamp.entries())if(!e.placeholder() && !e.state().isAir()) {
            low=Math.min(low,e.pos().getY()-lamp.surfaceY());high=Math.max(high,e.pos().getY()-lamp.surfaceY());
        }
        return low<=high && adjacentLamp(a,b,floor+low,floor+high,cornerFloor+low,cornerFloor+high);
    }
    static boolean adjacentLamp(BlockPos a,BlockPos b,int low,int high,int cornerLow,int cornerHigh) {
        return Math.abs((long)a.getX()-b.getX())+Math.abs((long)a.getZ()-b.getZ())==1
                && low<=cornerHigh && cornerLow<=high;
    }
    private static boolean publicLamp(RoadPlan own,Neighbor n,int row) {
        Integer shared=sharedLampRow(own,n.model(),n.setting());return shared!=null && shared==row;
    }
    public static Integer sharedLampRow(RoadPlan own,RoadPlan other,RoadPlanningState.Crossing setting) {
        if(setting.layered() || own.axis()==other.axis() || own.lampSpacing()<=1 || RoadCrossingGeometry.walk(own).intersect(RoadCrossingGeometry.walk(other))==null)return null;
        RoadPlan x=own.axis()==net.minecraft.core.Direction.Axis.X ? own : other,z=x==own ? other : own;
        int cx=z.anchorPos().getX(),cz=x.anchorPos().getZ();
        boolean preferred=own.axis()==net.minecraft.core.Direction.Axis.X
                || other.axis()!=net.minecraft.core.Direction.Axis.X && own.id().compareTo(other.id())<0;
        return RoadCrossingGeometry.body(own).contains(cx,cz) && RoadCrossingGeometry.body(other).contains(cx,cz)
                && (other.lampSpacing()<=1 || preferred)
                ? own.axis()==net.minecraft.core.Direction.Axis.X ? cx : cz : null;
    }
    public static boolean regularLamp(RoadPlan road,int row) {
        return road.lampSpacing()>1 && Math.floorMod(row-RoadHeightProfile.anchorAlong(road),road.lampSpacing())==0;
    }
    private static boolean needsCornerLamp(RoadPlan own,Neighbor n) {
        Integer corner=sharedLampRow(own,n.model(),n.setting());if(corner==null)return false;
        int otherCorner=own.axis()==net.minecraft.core.Direction.Axis.X ? own.anchorPos().getZ():own.anchorPos().getX();
        if(regularLamp(own,corner) || regularLamp(n.model(),otherCorner))return true;
        var overlap=n.body();
        for(var road:List.of(own,n.model())) {
            int first=overlap.first(road),last=overlap.last(road);
            for(int row=first;row<=last;row++)if(regularLamp(road,row) && row!=(road==own ? corner:otherCorner))return true;
        }
        return false;
    }
    public static Set<Integer> forcedLamps(ServerLevel level,RoadPlan plan) {
        Set<Integer> result=new HashSet<>();
        for(var n:neighbors(level,plan)) {
            int row=plan.axis()==net.minecraft.core.Direction.Axis.X ? n.model().anchorPos().getX() : n.model().anchorPos().getZ();
            if(publicLamp(plan,n,row) && needsCornerLamp(plan,n))result.add(row);
        }
        return Set.copyOf(result);
    }
    /** Native REMOVE consumes only private targets; shared floor and other layers become no-ops. */
    record RemovalNeighbor(RoadPlan live,RoadPlan model,Blueprint image) { }
    public static Blueprint removalMask(ServerLevel level,RoadPlan plan,Blueprint applied) {
        RoadPlan actualModel=RoadBlueprintStore.appliedPlan(level,plan);if(actualModel==null)actualModel=plan;
        List<RemovalNeighbor> peers=new ArrayList<>();
        for(var other:RoadManager.get(level).all()) {
            if(other.id().equals(plan.id()))continue;
            var image=RoadBlueprintStore.loadApplied(level,other);if(image==null)continue;
            var model=RoadBlueprintStore.appliedPlan(level,other);
            peers.add(new RemovalNeighbor(other,model==null ? other:model,image));
        }
        return removalMask(actualModel,applied,peers,level.registryAccess());
    }
    /** Native removal copy and protection, without substituting environmental fill with world blocks. */
    static Blueprint removalMask(RoadPlan actualModel,Blueprint applied,List<RemovalNeighbor> peers,net.minecraft.core.HolderLookup.Provider registries) {
        var result=BlueprintUtil.readBlueprintFromNBT(BlueprintUtil.writeBlueprintToNBT(applied),registries);
        var origin=RoadBlueprintOps.origin(actualModel,result);var offset=result.getPrimaryBlockOffset();
        for(var peer:peers) {
            var other=peer.live();var model=peer.model();var snapshot=peer.image();
            var rule=setting(actualModel,model);var profile=RoadHeightProfile.of(model);var body=RoadCrossingGeometry.body(model);
            for(var info:result.getBlockInfoAsList()) {
                if(!RoadBlueprintOps.isConcrete(info.getState()))continue;
                var world=origin.offset(info.getPos());
                boolean column=!rule.layered() && removalColumn(actualModel,model,world);
                int floor=body.contains(world.getX(),world.getZ())
                        ? profile.row(model.axis()==net.minecraft.core.Direction.Axis.X ? world.getX():world.getZ()).baseY():Integer.MIN_VALUE;
                boolean reserved=rule.layered() && floor!=Integer.MIN_VALUE && world.getY()>=floor-1 && world.getY()<=floor+2;
                if(column || reserved || structuralAt(snapshot,model,world) || world.equals(other.controllerPos()) || world.equals(other.anchorPos()))
                    write(result,info.getPos(),com.ldtteam.structurize.blocks.ModBlocks.blockSubstitution.get().defaultBlockState());
            }
        }
        result.setCachePrimaryOffset(offset);return result;
    }
    public static boolean removalColumn(RoadPlan own,RoadPlan other,BlockPos world) {
        var overlap=RoadCrossingGeometry.body(own).intersect(RoadCrossingGeometry.body(other));
        return own.axis()!=other.axis() && overlap!=null && overlap.contains(world.getX(),world.getZ());
    }
    private RoadCrossings() { }
}
