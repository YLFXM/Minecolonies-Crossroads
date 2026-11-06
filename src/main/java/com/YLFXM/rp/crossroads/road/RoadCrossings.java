/*
 * Copyright (C) 2026 YLFXM
 *
 * MineColonies: Crossroads - a feature expansion addon for MineColonies.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.YLFXM.rp.crossroads.road;

import java.util.*;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.blueprints.v1.BlueprintUtil;
import com.YLFXM.rp.crossroads.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import com.YLFXM.rp.crossroads.road.common.RoadBlocks;
import com.YLFXM.rp.crossroads.road.common.BlueprintCells;
import com.YLFXM.rp.crossroads.road.common.RoadGeometry;
import com.YLFXM.rp.crossroads.road.common.RoadAxisMath;

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
    public record Neighbor(RoadPlan live,RoadPlan model,Blueprint applied,RoadGeometry.Rect body,
                           RoadGeometry.Rect walk,RoadPlanningState.Crossing setting) { }
    public static RoadPlan reference(ServerLevel level,RoadPlan road) {
        var context=CONTEXT.get();if(context!=null && context.models().containsKey(road.id()))return context.models().get(road.id());
        return RoadBlueprintStore.referencePlan(level,road);
    }
    public static RoadPlan planningReference(ServerLevel level,RoadPlan own,RoadPlan other) {
        var reference=reference(level,other);
        return planningReference(own,other,reference);
    }
    static RoadPlan planningReference(RoadPlan own,RoadPlan live,RoadPlan actual) {
        return RoadCrossingGeometry.body(own).intersect(RoadCrossingGeometry.body(actual))==null
                && RoadCrossingGeometry.body(own).intersect(RoadCrossingGeometry.body(live))!=null ? live:actual;
    }
    static Neighbor neighbor(ServerLevel level,RoadPlan own,RoadPlan live,RoadPlan actual,Blueprint actualImage) {
        var context=CONTEXT.get();RoadPlan override=context==null ? null:context.models().get(live.id());
        RoadPlan model=override!=null ? override:planningReference(own,live,actual==null ? live:actual);
        var body=RoadCrossingGeometry.body(own).intersect(RoadCrossingGeometry.body(model));if(body==null)return null;
        Blueprint image=context==null ? null:context.blueprints().get(live.id());
        boolean physical=actual!=null && RoadCrossingGeometry.body(own).intersect(RoadCrossingGeometry.body(actual))!=null;
        return new Neighbor(live,model,image!=null ? image:physical ? actualImage:null,body,
                RoadCrossingGeometry.walk(own).intersect(RoadCrossingGeometry.walk(model)),setting(level,own,override==null ? live:override));
    }
    static boolean isScoped(UUID road) {return CONTEXT.get()!=null && CONTEXT.get().models().containsKey(road);}
    static boolean hasScope() {return CONTEXT.get()!=null;}
    /** Server-side authoritative relation read: the per-dimension table, then the edit overlay. */
    public static RoadPlanningState.Crossing setting(ServerLevel level,RoadPlan own,RoadPlan other) {
        var junction=RoadJunctions.find(level,own.id(),other.id());
        return junction==null ? new RoadPlanningState.Crossing(false,0) : RoadJunctions.ruleFor(junction,own.id());
    }
    /** Client-side projection read; never used on the server. */
    public static RoadPlanningState.Crossing setting(RoadPlan own,RoadPlan other) {
        var projection=own.syncedJunctions().get(other.id());
        return projection==null ? new RoadPlanningState.Crossing(false,0) : projection.rule();
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
            boolean recorded=RoadJunctions.find(level,plan.id(),other.id())!=null;
            if(proposedOverlap==null && !actualOverlap && !recorded) continue;
            // A road under construction is treated as unbuilt: its proposal is the model and
            // its old applied snapshot must not masquerade as current physical content.
            boolean applied=RoadBlueprintStore.referenceIsApplied(level,other);
            RoadPlan reference=applied ? RoadBlueprintStore.referencePlan(level,other) : null;
            Blueprint image=applied ? RoadBlueprintStore.loadApplied(level,other) : null;
            var neighbor=neighbor(level,plan,other,reference,image);
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
                int ya=a.row(RoadAxisMath.along(plan.axis(),x,z)).baseY();
                int yb=b.row(RoadAxisMath.along(n.model().axis(),x,z)).baseY();
                int sign=Integer.signum(ya-yb);
                if(Math.abs((long)ya-yb)<3 || order!=0 && sign!=order || n.setting().order()!=0 && sign!=n.setting().order())
                    throw new IllegalArgumentException("Layered crossings need a consistent upper road and at least 3 blocks of surface separation everywhere.");
                order=sign;
            }
        }
    }
    static void validateGeometry(ServerLevel level,RoadPlan plan) {
        var context=CONTEXT.get();
        for(var n:neighbors(level,plan)) {
            validate(plan,n);
            // Only roads taking part in this operation may block it. A third road's effect is
            // carried by the participant's locked rows, never by a cross-road busy scan.
            boolean participating=context!=null && context.models().containsKey(n.live().id());
            boolean ownOperation=context!=null && n.live().id().equals(context.operation());
            if(participating && !ownOperation && RoadRules.busy(n.live(),RoadRules.MUTATION))
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
                int a=own.row(RoadAxisMath.along(plan.axis(),x,z)).baseY();
                int b=peer.row(RoadAxisMath.along(n.model().axis(),x,z)).baseY();
                RoadPlan lower=a<b ? plan : n.model(),upper=a<b ? n.model() : plan;
                if(!RoadCrossingGeometry.walk(lower).contains(x,z))continue;
                Blueprint lowerImage=a<b ? blueprint : n.applied(),upperImage=a<b ? n.applied() : blueprint;
                int floor=Math.min(a,b);var floorPos=new BlockPos(x,floor,z);
                var shape=stateAt(lowerImage,lower,floorPos).getCollisionShape(level,floorPos);
                double top=floor+(shape.isEmpty() ? 1 : shape.max(net.minecraft.core.Direction.Axis.Y));
                var origin=BlueprintCells.origin(upper,upperImage);
                for(var info:upperImage.getBlockInfoAsList()) {
                    if(!RoadBlocks.isConcrete(info.getState()) && !RoadBlocks.isSolidFill(info.getState()))continue;
                    var pos=origin.offset(info.getPos());if(pos.getX()!=x || pos.getZ()!=z || pos.getY()<floor)continue;
                    var collision=info.getState().getCollisionShape(level,pos);
                    if(collision.isEmpty() && !RoadBlocks.isSolidFill(info.getState()))continue;
                    double bottom=pos.getY()+(collision.isEmpty() ? 0 : collision.min(net.minecraft.core.Direction.Axis.Y));
                    if(!hasWalkingClearance(top,bottom))throw new IllegalArgumentException("The final bridge blocks intrude into the lower road's two-block walking clearance.");
                }
            }
        }
    }
    public static void freeze(ServerLevel level,RoadRepairSession session,RoadPlan target) {
        net.minecraft.nbt.CompoundTag dependencies=new net.minecraft.nbt.CompoundTag();
        for(var n:neighbors(level,target)) {
            var junction=RoadJunctions.find(level,target.id(),n.live().id());
            var reference=RoadBlueprintStore.referencePlan(level,n.live());
            var dependency=frozenDependency(target,reference);
            dependency.putLong("junctionRev",junction==null ? 0 : junction.rev());
            dependencies.put(n.live().id().toString(),dependency);
        }
        session.replacePreparedTarget(target);session.setDependencies(dependencies);
        RoadLockReconcile.apply(level,target);
    }
    /**
     * Narrow dependency for a participating neighbour: only the rows inside the shared
     * footprint matter. A third road changing the same neighbour elsewhere must not
     * invalidate this frozen work.
     */
    static net.minecraft.nbt.CompoundTag frozenDependency(RoadPlan target,RoadPlan reference) {
        var data=new net.minecraft.nbt.CompoundTag();data.putBoolean("constraintOnly",false);
        var shared=footprint(target,reference);
        if(shared==null)data.putBoolean("degenerate",true);else data.put("footprint",shared);
        return data;
    }
    /** Row types and surface heights of {@code reference} inside {@code target}'s shared footprint. */
    static net.minecraft.nbt.CompoundTag footprint(RoadPlan target,RoadPlan reference) {
        var body=RoadCrossingGeometry.body(target).intersect(RoadCrossingGeometry.body(reference));
        if(body==null)return null;
        var axis=reference.axis();
        final int from=axis==net.minecraft.core.Direction.Axis.X ? body.minX() : body.minZ();
        final int to=axis==net.minecraft.core.Direction.Axis.X ? body.maxX() : body.maxZ();
        var tag=new net.minecraft.nbt.CompoundTag();tag.putInt("from",from);tag.putInt("to",to);
        final int min=Math.max(from,reference.minAlong()),max=Math.min(to,reference.maxAlong());
        if(reference.segments().isEmpty()||max<min) {tag.putBoolean("degenerate",true);return tag;}
        final RoadHeightProfile profile;
        try {profile=RoadHeightProfile.of(reference);}
        catch(RuntimeException ex) {tag.putBoolean("degenerate",true);return tag;}
        var rows=new net.minecraft.nbt.ListTag();
        for(int along=min;along<=max;along++) {
            var row=profile.row(along);
            var entry=new net.minecraft.nbt.CompoundTag();
            entry.putInt("along",along);entry.putString("type",row.type().name());entry.putInt("y",row.baseY());
            rows.add(entry);
        }
        tag.put("rows",rows);
        if(min!=from||max!=to)tag.putBoolean("clipped",true);
        return tag;
    }
    static net.minecraft.nbt.CompoundTag frozenDependency(RoadPlan live,RoadPlan actual,boolean constraintOnly) {
        return frozenDependency(live,actual==null ? live:actual,actual!=null,constraintOnly);
    }
    static net.minecraft.nbt.CompoundTag frozenDependency(RoadPlan live,RoadPlan reference,boolean applied,boolean constraintOnly) {
        var data=new net.minecraft.nbt.CompoundTag();data.putBoolean("applied",applied);
        data.put("model",reference.geometrySnapshot());
        if(constraintOnly)data.putBoolean("constraintOnly",true);
        return data;
    }
    public static boolean verifyFrozen(ServerLevel level,RoadRepairSession session) {
        var dependencies=session.dependencies();
        for(String key:dependencies.getAllKeys()) {
            var live=RoadManager.get(level).get(UUID.fromString(key));if(live==null) return false;
            var data=dependencies.getCompound(key);
            var reference=RoadBlueprintStore.referencePlan(level,live);
            if(data.contains("footprint",10)) {
                var now=footprint(session.target(),reference);
                if(now==null||!now.equals(data.getCompound("footprint"))) return false;
            } else {
                boolean applied=RoadBlueprintStore.referenceIsApplied(level,live);
                if(data.getBoolean("applied")!=applied) return false;
                if(!reference.geometrySnapshot().equals(data.getCompound("model"))) return false;
            }
            if(data.contains("junctionRev",3)) {
                var junction=RoadJunctions.find(level,session.target().id(),live.id());
                long rev=junction==null ? 0 : junction.rev();
                if(rev!=data.getLong("junctionRev")) return false;
            }
        }
        return true;
    }
    /**
     * Human-readable reasons why a frozen record no longer matches. Used only when the
     * player retries an interrupted repair: the record is then released and prepared
     * again instead of blocking the road.
     */
    public static List<String> frozenChanges(ServerLevel level,RoadRepairSession session) {
        var out=new ArrayList<String>();
        var dependencies=session.dependencies();
        for(String key:dependencies.getAllKeys()) {
            var live=RoadManager.get(level).get(UUID.fromString(key));
            if(live==null) {out.add("邻路已被删除");continue;}
            var data=dependencies.getCompound(key);
            var reference=RoadBlueprintStore.referencePlan(level,live);
            var label=RoadNames.label(live);
            if(data.contains("footprint",10)) {
                var before=data.getCompound("footprint");
                var now=footprint(session.target(),reference);
                if(now==null)out.add(label+" 与本次路口已不相交");
                else if(before.getInt("from")!=now.getInt("from")||before.getInt("to")!=now.getInt("to"))
                    out.add(label+" 共享足迹 "+before.getInt("from")+".."+before.getInt("to")
                            +" → "+now.getInt("from")+".."+now.getInt("to"));
                else {
                    var diffs=rowDiffs(before,now);
                    if(!diffs.isEmpty())out.add(label+" 共享足迹行变化："+String.join("、",diffs));
                }
            } else if(!reference.geometrySnapshot().equals(data.getCompound("model"))) {
                out.add(label+" 整图已变化");
            }
            if(data.contains("junctionRev",3)) {
                var junction=RoadJunctions.find(level,session.target().id(),live.id());
                long rev=junction==null ? 0 : junction.rev();
                if(rev!=data.getLong("junctionRev"))out.add(label+" 的交叉关系已被修改");
            }
        }
        if(out.isEmpty())out.add("相关道路的共享足迹或交叉关系已变化");
        return out;
    }
    static List<String> rowDiffs(net.minecraft.nbt.CompoundTag before,net.minecraft.nbt.CompoundTag after) {
        var result=new ArrayList<String>();
        var old=before.getList("rows",10);var current=new TreeMap<Integer,net.minecraft.nbt.CompoundTag>();
        var now=after.getList("rows",10);
        for(int i=0;i<now.size();i++) {var row=now.getCompound(i);current.put(row.getInt("along"),row);}
        for(int i=0;i<old.size();i++) {
            var row=old.getCompound(i);int along=row.getInt("along");var next=current.get(along);
            if(next==null) {result.add("along="+along+" 被裁掉");}
            else if(row.getString("type").equals(next.getString("type"))&&row.getInt("y")==next.getInt("y"))continue;
            else result.add("along="+along+" "+row.getString("type")+"/Y"+row.getInt("y")
                        +"→"+next.getString("type")+"/Y"+next.getInt("y"));
            if(result.size()>=4) {result.add("…");break;}
        }
        return result;
    }
    public static void extendFrozen(ServerLevel level,RoadRepairSession session,net.minecraft.nbt.CompoundTag updates) {
        var dependencies=session.dependencies();
        for(String key:updates.getAllKeys()) {
            RoadPlan model=RoadPlan.load(updates.getCompound(key).getCompound("model"));
            for(var n:neighbors(level,model)) {
                if(n.live().id().equals(session.target().id()) || dependencies.contains(n.live().id().toString()))continue;
                boolean applied=RoadBlueprintStore.referenceIsApplied(level,n.live());
                var reference=RoadBlueprintStore.referencePlan(level,n.live());
                dependencies.put(n.live().id().toString(),frozenDependency(n.live(),reference,applied,true));
            }
        }
        session.setDependencies(dependencies);
    }
    public static boolean flatLocked(ServerLevel level,RoadPlan plan,int row) {
        var locks=plan.lockedRows();
        if(!locks.isEmpty()) {var surface=locks.get(row);return surface!=null && row!=RoadHeightProfile.anchorAlong(plan);}
        for(var n:neighbors(level,plan)) if(!n.setting().layered() && n.walk()!=null && n.applied()!=null
                && row>=n.walk().first(plan) && row<=n.walk().last(plan)) return true;
        return false;
    }
    public static BlockState stateAt(Blueprint blueprint,RoadPlan model,BlockPos world) {
        var p=world.subtract(BlueprintCells.origin(model,blueprint));
        return inside(blueprint,p) ? blueprint.getBlockState(p) : Blocks.AIR.defaultBlockState();
    }
    public static boolean structuralAt(Blueprint image,RoadPlan model,BlockPos world) {
        var state=stateAt(image,model,world);
        if(RoadBlocks.isConcrete(state))return true;
        return RoadBlocks.isSolidFill(state) && deckCell(model,world);
    }
    /** Real blocks only: a terrain fill marker must never make another road defer. */
    public static boolean concreteAt(Blueprint image,RoadPlan model,BlockPos world) {
        return RoadBlocks.isConcrete(stateAt(image,model,world));
    }
    public static boolean deckCell(RoadPlan model,BlockPos world) {
        if(!RoadCrossingGeometry.body(model).contains(world.getX(),world.getZ()))return false;
        int along=RoadAxisMath.alongOf(model.axis(),world);
        return world.getY()==RoadHeightProfile.of(model).row(along).baseY();
    }
    /** Inside the image? Delegates to {@link BlueprintCells}, which owns the write policy. */
    public static boolean inside(final Blueprint b,final BlockPos p) {
        return BlueprintCells.inside(b,p);
    }
    /** Writes when inside, skipping silently otherwise — a junction often decorates cells outside this road. */
    public static void write(final Blueprint b,final BlockPos p,final BlockState state) {
        BlueprintCells.write(b,p,state);
    }
    public static void decorate(ServerLevel level,RoadPlan plan,Blueprint out) {
        var origin=BlueprintCells.origin(plan,out);var own=RoadHeightProfile.of(plan);List<String> issues=new ArrayList<>();
        final BlockState skip=com.ldtteam.structurize.blocks.ModBlocks.blockSubstitution.get().defaultBlockState();
        BlockState pavement=RoadUnitLibrary.get("road",level.registryAccess()).entries().stream()
                .filter(e -> e.pos().getY()==1 && e.pos().getZ()==plan.walkRange().from()).findFirst().orElseThrow().state();
        for(var n:neighbors(level,plan)) {
            try { validate(plan,n); } catch(IllegalArgumentException ex) {issues.add(RoadNotices.reasonKey(ex));}
            var profile=RoadHeightProfile.of(n.model());
            for(var info:out.getBlockInfoAsList()) {
                var world=origin.offset(info.getPos());if(!n.body().contains(world.getX(),world.getZ())) continue;
                int y=own.row(RoadAxisMath.alongOf(plan.axis(),world)).baseY();
                int otherY=profile.row(RoadAxisMath.alongOf(n.model().axis(),world)).baseY();
                if(n.setting().layered()) {
                    if(y>otherY && world.getY()<y && RoadBlocks.isSolidFill(info.getState()))
                        write(out,info.getPos(),world.getY()>otherY ? Blocks.AIR.defaultBlockState() : skip);
                    if(n.applied()!=null && structuralAt(n.applied(),n.model(),world))
                        write(out,info.getPos(),skip);
                } else {
                    boolean walkCell=RoadCrossingGeometry.walk(n.model()).contains(world.getX(),world.getZ());
                    if(y==otherY && world.getY()==y && walkCell)
                        write(out,info.getPos(),pavement);
                    else if(n.applied()!=null && (info.getState().isAir() || RoadBlocks.isSolidFill(info.getState()))
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
                int floor=other.row(RoadAxisMath.alongOf(n.model().axis(),p)).baseY();
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
                ? RoadAxisMath.crossBase(other) : null;
    }
    public static boolean regularLamp(RoadPlan road,int row) {
        return road.lampSpacing()>1 && Math.floorMod(row-RoadHeightProfile.anchorAlong(road),road.lampSpacing())==0;
    }
    private static boolean needsCornerLamp(RoadPlan own,Neighbor n) {
        Integer corner=sharedLampRow(own,n.model(),n.setting());if(corner==null)return false;
        int otherCorner=RoadAxisMath.crossBase(own);
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
            int row=RoadAxisMath.along(plan.axis(),n.model().anchorPos().getX(),n.model().anchorPos().getZ());
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
        return removalMask(level,actualModel,applied,peers,level.registryAccess());
    }
    /** Native removal copy and protection, without substituting environmental fill with world blocks. */
    static Blueprint removalMask(ServerLevel level,RoadPlan actualModel,Blueprint applied,List<RemovalNeighbor> peers,net.minecraft.core.HolderLookup.Provider registries) {
        return removalMask(actualModel,applied,peers,registries,(own,model) -> setting(level,own,model));
    }
    /** Level-free form: no junction record is consulted, so every pair is treated as same-level. */
    static Blueprint removalMask(RoadPlan actualModel,Blueprint applied,List<RemovalNeighbor> peers,net.minecraft.core.HolderLookup.Provider registries) {
        return removalMask(actualModel,applied,peers,registries,(own,model) -> new RoadPlanningState.Crossing(false,0));
    }
    private static Blueprint removalMask(RoadPlan actualModel,Blueprint applied,List<RemovalNeighbor> peers,
                                         net.minecraft.core.HolderLookup.Provider registries,
                                         java.util.function.BiFunction<RoadPlan,RoadPlan,RoadPlanningState.Crossing> ruleFor) {
        var result=BlueprintUtil.readBlueprintFromNBT(BlueprintUtil.writeBlueprintToNBT(applied),registries);
        var origin=BlueprintCells.origin(actualModel,result);var offset=result.getPrimaryBlockOffset();
        for(var peer:peers) {
            var other=peer.live();var model=peer.model();var snapshot=peer.image();
            var rule=ruleFor.apply(actualModel,model);var profile=RoadHeightProfile.of(model);var body=RoadCrossingGeometry.body(model);
            for(var info:result.getBlockInfoAsList()) {
                if(!RoadBlocks.isConcrete(info.getState()))continue;
                var world=origin.offset(info.getPos());
                boolean column=!rule.layered() && removalColumn(actualModel,model,world);
                int floor=body.contains(world.getX(),world.getZ())
                        ? profile.row(RoadAxisMath.alongOf(model.axis(),world)).baseY():Integer.MIN_VALUE;
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
