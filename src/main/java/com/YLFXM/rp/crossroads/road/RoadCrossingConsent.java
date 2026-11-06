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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.YLFXM.rp.crossroads.*;
import com.ldtteam.structurize.blueprints.v1.*;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.permissions.*;
import net.minecraft.commands.Commands;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.*;
import net.minecraft.core.BlockPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import com.YLFXM.rp.crossroads.road.common.RoadBlocks;
import com.YLFXM.rp.crossroads.road.common.BlueprintCells;
import com.YLFXM.rp.crossroads.road.common.RoadAxisMath;

/** Per-operation consent. No general colony permission is changed, and no world work is dispatched here. */
@EventBusSubscriber(modid=Crossroads.MODID)
public final class RoadCrossingConsent {
    public enum Decision { PENDING, APPROVED, REJECTED, INVALID }
    public static Decision timeout(Decision current,long now,long deadline,boolean sourceValid,boolean recipientValid) {
        if(current!=Decision.PENDING)return current;
        if(!sourceValid || !recipientValid)return Decision.INVALID;
        return now>=deadline ? Decision.REJECTED : Decision.PENDING;
    }
    private static final Map<UUID,Request> REQUESTS=new LinkedHashMap<>();
    private static final Set<UUID> CHANGES=new LinkedHashSet<>();
    private static final Map<String,Long> NOTICES=new HashMap<>();
    public static boolean debounce(long now,long previous) {return now-previous<2_000;}
    /** Shapes and actual peer contents define planning scope; derived own heights do not replace requests. */
    public static CompoundTag planningBoundary(ServerLevel level,RoadPlan main,RoadPlan peer) {
        var out=new CompoundTag();out.put("main",shape(main));out.put("peer",shape(peer));
        var actual=RoadBlueprintStore.appliedPlan(level,peer);
        if(actual!=null){var physical=intent(level,actual);physical.remove("planning");out.put("actual",physical);}
        for(var road:List.of(main,peer)) {
            var colony=IColonyManager.getInstance().getColonyByPosFromWorld(level,road.controllerPos());
            var owner=new CompoundTag();if(colony!=null)owner.putInt("colony",colony.getID());else if(road.creator()!=null)owner.putUUID("creator",road.creator());
            out.put(road.id().toString(),owner);
        }
        return out;
    }
    public static CompoundTag shape(RoadPlan road) {
        var full=road.geometrySnapshot();var result=new CompoundTag();
        for(var key:List.of("id","dimension","dim","anchor","controller","facing","mirror","width","walkFrom","walkTo","widthSide","travelDir","segments"))
            if(full.contains(key))result.put(key,full.get(key).copy());
        return result;
    }
    private static Request approved(ServerLevel level,UUID actor,RoadPlan own,RoadPlan peer) {
        for(var r:REQUESTS.values())if(r.level==level && r.actor.equals(actor) && r.main.equals(own.id()) && r.peer.equals(peer.id()) && r.decision==Decision.APPROVED) {
            update(r);if(r.decision==Decision.APPROVED && r.source.equals(planningBoundary(level,own,peer)))return r;
        }
        return null;
    }
    public static boolean canPlan(ServerLevel level,ServerPlayer actor,RoadPlan own,RoadPlan peer) {
        return RoadPermissions.canManage(level,actor,peer) || approved(level,actor.getUUID(),own,peer)!=null
                || validGrant(level,own,peer);
    }
    /** Symmetric boundary so both directions of a pair agree on the same digest. */
    public static CompoundTag pairBoundary(ServerLevel level,RoadPlan a,RoadPlan b) {
        RoadPlan first=a.id().compareTo(b.id())<0 ? a : b,second=first==a ? b : a;
        var out=new CompoundTag();out.put("first",shape(first));out.put("second",shape(second));
        for(var road:List.of(first,second)) {
            // Deliberately excludes applied content: a grant must survive the very completion it
            // authorises. Only shape, ownership and the recorded relation can invalidate it.
            var colony=IColonyManager.getInstance().getColonyByPosFromWorld(level,road.controllerPos());
            var owner=new CompoundTag();if(colony!=null)owner.putInt("colony",colony.getID());else if(road.creator()!=null)owner.putUUID("creator",road.creator());
            out.put("owner:"+road.id(),owner);
        }
        return out;
    }
    public static String pairDigest(ServerLevel level,RoadPlan a,RoadPlan b) {return digest(pairBoundary(level,a,b));}
    /** A pair grant is permission, not ownership: it never unlocks editing or colony rights. */
    public static boolean validGrant(ServerLevel level,RoadPlan own,RoadPlan peer) {
        var junction=RoadJunctions.find(level,own.id(),peer.id());
        return junction!=null && junction.grant()!=null && junction.grant().boundary().equals(pairDigest(level,own,peer));
    }
    private static void grantBoth(Request request) {
        var level=request.level;var main=RoadManager.get(level).get(request.main);var peer=RoadManager.get(level).get(request.peer);
        if(main==null || peer==null)return;
        String boundary=pairDigest(level,main,peer);long now=System.currentTimeMillis();
        var rect=RoadCrossingGeometry.body(main).intersect(RoadCrossingGeometry.body(peer));
        if(rect!=null) {
            var existing=RoadJunctions.find(level,main.id(),peer.id());
            var rule=existing==null ? new RoadPlanningState.Crossing(false,0,RoadJunctions.nextVersion(level)) : existing.rule();
            var flags=builtForNewGrant(built(main),built(peer));
            RoadJunctions.put(level,new RoadJunctions.Junction(main.id(),peer.id(),rect,rule,
                    new RoadPlanningState.Grant(request.token,request.actor,now,boundary),
                    flags[0],flags[1],existing==null ? 1 : existing.rev()+1));
        }
        // The approved neighbour becomes editable for the solver; approval-time permission is
        // the only place where the approver's rank is checked.
        var recipient=level.getServer().getPlayerList().getPlayer(request.recipient);
        ensureEditable(level,recipient,peer,main);
        RoadManager.get(level).add(main);RoadManager.get(level).add(peer);
        Crossroads.LOGGER.info("Crossing consent granted: request={} main={} peer={}",request.token,request.main,request.peer);
    }
    /** After a successful commit both sides are re-checked; only a fully built pair loses its grant. */
    public static void settle(ServerLevel level,RoadPlan road,Set<UUID> appliedPeers) {
        for(var junction:RoadJunctions.forRoad(level,road.id())) {
            UUID otherId=junction.a().equals(road.id()) ? junction.b() : junction.a();
            boolean aBuilt=junction.builtA(),bBuilt=junction.builtB();
            if(junction.a().equals(road.id())) aBuilt|=road.appliedBounds()!=null;
            else bBuilt|=road.appliedBounds()!=null;
            if(junction.a().equals(otherId)) aBuilt|=appliedPeers.contains(otherId);
            else bBuilt|=appliedPeers.contains(otherId);
            var grant=(aBuilt && bBuilt) ? null : junction.grant();
            if(aBuilt!=junction.builtA() || bBuilt!=junction.builtB() || (junction.grant()!=null && grant==null)) {
                RoadJunctions.put(level,new RoadJunctions.Junction(junction.a(),junction.b(),junction.rect(),
                        junction.rule(),grant,aBuilt,bBuilt,junction.rev()+1));
                if(grant==null)Crossroads.LOGGER.info("Crossing grant released: both roads built pair={} {}",junction.a(),junction.b());
            }
        }
    }
    /** Opens a built/planned neighbour's edit switch for an authorized junction operation. */
    public static void ensureEditable(ServerLevel level,ServerPlayer actor,RoadPlan target,RoadPlan counterpart) {
        if(target==null || target.segments().isEmpty()) return;
        if(target.state()!=RoadState.PLANNED && target.state()!=RoadState.BUILT) return;
        if(target.allowBlueprintEdit()) return;
        boolean allowed=(actor!=null && RoadPermissions.canManage(level,actor,target)) || validGrant(level,counterpart,target);
        if(!allowed) return;
        target.setAllowBlueprintEdit(true);
        RoadManager.get(level).add(target);
        Crossroads.LOGGER.info("Road {} edit switch opened by junction authorization with {}",target.id(),counterpart.id());
    }
    private static boolean built(RoadPlan plan) {
        return plan.appliedBounds()!=null || plan.appliedGeometry()!=null;
    }
    /**
     * A new grant on an already fully built pair restarts the completion ledger: otherwise the
     * old "both built" flags would release the fresh grant as soon as the first side repairs,
     * forcing the second side to request again.
     */
    static boolean[] builtForNewGrant(boolean mainBuilt,boolean peerBuilt) {
        return mainBuilt && peerBuilt ? new boolean[]{false,false}:new boolean[]{mainBuilt,peerBuilt};
    }
    public static boolean canPlan(ServerLevel level,RoadPlan own,RoadPlan peer) {
        return validGrant(level,own,peer);
    }
    /**
     * Planning authority for height solving, where no actor is carried through.
     * <p>
     * The grant-only overload above also decides whether a new crossing is staged onto the
     * automatic strict layered rule, so it must stay narrow. Height solving must not: a pair
     * the planner can already manage is authorized by definition, and treating it as foreign
     * reserves the neighbour's lamp height on top of the normal three-block clearance even
     * though construction will edit that neighbour as a normal joint.
     */
    public static boolean canPlanAsPlanner(ServerLevel level,RoadPlan own,RoadPlan peer) {
        if(canPlan(level,own,peer))return true;
        if(sameAuthority(level,own,peer))return true;
        var id=own.planning().planner();
        if(id==null)return false;
        var player=level.getServer().getPlayerList().getPlayer(id);
        return player!=null && RoadPermissions.canManage(level,player,peer);
    }
    /** One colony, or two wilderness roads of one creator: authority exists without an approval record. */
    static boolean sameAuthority(ServerLevel level,RoadPlan a,RoadPlan b) {
        var colonyA=IColonyManager.getInstance().getColonyByPosFromWorld(level,a.controllerPos());
        var colonyB=IColonyManager.getInstance().getColonyByPosFromWorld(level,b.controllerPos());
        if(colonyA!=null || colonyB!=null)return colonyA!=null && colonyB!=null && colonyA.getID()==colonyB.getID();
        return a.creator()!=null && a.creator().equals(b.creator());
    }
    public static void inspect(ServerLevel level,ServerPlayer actor,RoadPlan own) {
        own.planning().planner(actor.getUUID());
        for(var n:RoadCrossings.neighbors(level,own))if(!RoadPermissions.canManage(level,actor,n.live())
                && !RoadCrossingRestoration.geometry(level,own,n.live())) {
            String key=actor.getUUID()+":"+digest(planningBoundary(level,own,n.live()));
            if(NOTICES.putIfAbsent(key,System.currentTimeMillis())==null)RoadNotices.chatMessage(actor,null,Component.translatable("rp_crossroads.consent.choose",RoadNames.label(n.live())));
        }
    }
    public static void addAccessSync(ServerLevel level,ServerPlayer actor,RoadPlan own,CompoundTag sync) {
        var access=new ListTag();
        for(var n:RoadCrossings.neighbors(level,own)) {
            int status=0;
            if(canPlan(level,actor,own,n.live()))status=2;
            else for(var r:REQUESTS.values())if(r.level==level && r.actor.equals(actor.getUUID()) && r.main.equals(own.id()) && r.peer.equals(n.live().id())) {
                update(r);if(r.decision==Decision.PENDING)status=1;else if(r.decision==Decision.REJECTED)status=-1;
            }
            var e=new CompoundTag();e.putUUID("road",n.live().id());e.putInt("status",status);access.add(e);
        }
        sync.getCompound("planning").put("access",access);
    }
    public static void request(ServerLevel level,ServerPlayer actor,RoadPlan own,RoadPlan peer) {
        if(RoadPermissions.canManage(level,actor,peer))return;
        var boundary=planningBoundary(level,own,peer);long now=System.currentTimeMillis();
        for(var r:REQUESTS.values())if(r.level==level && r.actor.equals(actor.getUUID()) && r.main.equals(own.id()) && r.peer.equals(peer.id())) {
            update(r);
            if(r.source.equals(boundary) && (r.decision==Decision.PENDING || r.decision==Decision.APPROVED || debounce(now,r.created))) {
                RoadNotices.chatMessage(actor,null,Component.translatable(r.decision==Decision.APPROVED ? "rp_crossroads.consent.approved" : "rp_crossroads.consent.waiting"));return;
            }
            if(r.decision==Decision.PENDING || r.decision==Decision.APPROVED)r.decision=Decision.INVALID;
        }
        var chosen=recipient(level,peer);if(chosen==null){RoadNotices.chatMessage(actor,null,Component.translatable("rp_crossroads.consent.offline",RoadNames.label(peer)));return;}
        var scope=new CompoundTag();scope.putString("mainName",RoadNames.label(own));
        var a=RoadCrossingGeometry.body(own);var b=RoadCrossingGeometry.body(peer);
        scope.putIntArray("bounds",new int[]{Math.min(a.minX(),b.minX()),level.getMinBuildHeight(),Math.min(a.minZ(),b.minZ()),Math.max(a.maxX(),b.maxX()),level.getMaxBuildHeight()-1,Math.max(a.maxZ(),b.maxZ())});
        scope.putString("work",digest(boundary));
        var request=new Request(level,actor,own,peer,chosen,boundary,scope);REQUESTS.put(request.token,request);sendRequest(request,actor,peer,chosen);
    }
    private static final class Request {
        final UUID token=UUID.randomUUID(),actor,main,peer,recipient;
        final ServerLevel level;
        final CompoundTag source,scope;
        final long created=System.currentTimeMillis(),deadline=created+120_000;
        String invalidReason="";
        boolean timedOut;
        Decision decision=Decision.PENDING;
        Request(ServerLevel level,ServerPlayer actor,RoadPlan main,RoadPlan peer,ServerPlayer recipient,CompoundTag source,CompoundTag scope) {
            this.level=level;this.actor=actor.getUUID();this.main=main.id();this.peer=peer.id();this.recipient=recipient.getUUID();this.source=source.copy();this.scope=scope.copy();
        }
    }
    public record Authorized(RoadJointWork.Prepared work,CompoundTag grants) { }
    public static String digest(CompoundTag tag) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(tag.toString().getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException ex) {throw new IllegalStateException(ex);}
    }
    /** Level-free form: no junction records are attached. Used by the standalone regressions. */
    public static CompoundTag intent(RoadPlan road) {return intent(null,road);}
    /** Stable geometric intent, excluding UI diagnostics, state, timestamps and render cache revisions. */
    public static CompoundTag intent(ServerLevel level,RoadPlan road) {
        RoadPlan p=RoadPlan.load(road.geometrySnapshot());p.pruneOutOfRangeRows();var full=p.save();CompoundTag out=new CompoundTag();
        for(String key:List.of("id","dimension","dim","anchor","controller","facing","pack","variant","mirror","width","walkFrom","walkTo","widthSide","lampSpacing","travelDir","segments"))
            if(full.contains(key))out.put(key,full.get(key).copy());
        // Only the row types belong to the consented shape. Authority level, lock height,
        // lamp outcome and the junction-free baseline all move while the road is built
        // (the generator records lamps, the lock reconcile pins junction rows), so a
        // fingerprint carrying them would silently drop an already approved grant.
        ListTag rowTypes=new ListTag();
        p.rows().forEach((along,state) -> {
            if(state.type()==CellType.FLAT)return;
            CompoundTag row=new CompoundTag();row.putInt("along",along);row.putString("type",state.type().name());rowTypes.add(row);
        });
        if(!rowTypes.isEmpty())out.put("rows",rowTypes);
        CompoundTag planning=p.planning().save();planning.remove("revision");planning.remove("sampleKey");planning.remove("issue");planning.remove("crossingIssue");planning.remove("terrain");planning.remove("hasBase");planning.remove("refreshNeeded");planning.remove("externalChanges");
        planning.remove("relationshipKey");
        ListTag pairs=new ListTag();
        for(var junction:level==null ? List.<RoadJunctions.Junction>of():RoadJunctions.forRoad(level,road.id())) {
            var rule=RoadJunctions.ruleFor(junction,road.id());
            if(!rule.layered() && rule.platform()==null && rule.version()==0)continue;
            UUID other=junction.a().equals(road.id()) ? junction.b() : junction.a();
            CompoundTag pair=new CompoundTag();pair.putUUID("road",other);pair.putBoolean("layered",rule.layered());pair.putInt("order",rule.order());if(rule.platform()!=null)pair.putInt("platform",rule.platform());pairs.add(pair);
        }
        planning.put("pairs",pairs);out.put("planning",planning);return out;
    }
    public static CompoundTag pairIntent(ServerLevel level,RoadPlan own,RoadPlan other) {
        var rule=RoadCrossings.setting(level,own,other);var tag=new CompoundTag();tag.putBoolean("layered",rule.layered());tag.putInt("order",rule.order());tag.putLong("version",rule.version());if(rule.platform()!=null)tag.putInt("platform",rule.platform());return tag;
    }
    public static int priority(boolean colony,int rankId,boolean canManage) {
        if(!canManage)return 0;return !colony ? 1 : rankId==IPermissions.OWNER_RANK_ID ? 3 : 2;
    }
    public static boolean nativeContinuation(boolean online,boolean acceptedCreative,int currentPermission,int requiredPermission) {
        return !online && acceptedCreative && currentPermission>=requiredPermission;
    }
    private static int priority(ServerLevel level,ServerPlayer player,RoadPlan road) {
        if(!RoadPermissions.canManage(level,player,road))return 0;
        var colony=IColonyManager.getInstance().getColonyByPosFromWorld(level,road.controllerPos());
        return priority(colony!=null,colony==null ? -1 : colony.getPermissions().getRank(player).getId(),true);
    }
    private static ServerPlayer recipient(ServerLevel level,RoadPlan road) {
        return level.getServer().getPlayerList().getPlayers().stream().filter(p -> priority(level,p,road)>0)
                .sorted(Comparator.<ServerPlayer>comparingInt(p -> priority(level,p,road)).reversed().thenComparing(ServerPlayer::getUUID)).findFirst().orElse(null);
    }
    private static CompoundTag nativeReceipt(ServerLevel level,ServerPlayer actor,RoadPlan main,RoadPlan peer,Blueprint work) {
        int minimum=com.minecolonies.api.IMinecoloniesAPI.getInstance().getConfig().getServer().permissionEventMinBypassPermLevel.get();
        if(!actor.isCreative() || !actor.hasPermissions(minimum))return null;
        var receipt=scope(main,peer,work,actor.getUUID(),level);receipt.putBoolean("native",true);receipt.putBoolean("creative",true);receipt.putString("actorName",actor.getGameProfile().getName());return receipt;
    }
    private static boolean nativeOffline(ServerLevel level,UUID actor,CompoundTag grant) {
        int minimum=com.minecolonies.api.IMinecoloniesAPI.getInstance().getConfig().getServer().permissionEventMinBypassPermLevel.get();
        var profile=new com.mojang.authlib.GameProfile(actor,grant.getString("actorName"));
        return grant.getBoolean("native") && nativeContinuation(level.getServer().getPlayerList().getPlayer(actor)!=null,grant.getBoolean("creative"),level.getServer().getProfilePermissions(profile),minimum);
    }
    private static CompoundTag scope(RoadPlan primary,RoadPlan peer,Blueprint work,UUID actor,ServerLevel level) {
        CompoundTag scope=new CompoundTag();scope.putUUID("actor",actor);scope.putUUID("main",primary.id());
        scope.putString("dimension",level.dimension().location().toString());scope.put("anchor",NbtUtils.writeBlockPos(primary.anchorPos()));
        var origin=BlueprintCells.origin(primary,work);scope.putString("mainName",RoadNames.label(primary));
        scope.putIntArray("bounds",new int[]{origin.getX(),origin.getY(),origin.getZ(),origin.getX()+work.getSizeX()-1,origin.getY()+work.getSizeY()-1,origin.getZ()+work.getSizeZ()-1});
        var reference=RoadCrossings.reference(level,peer);
        var footprint=RoadCrossings.footprint(primary,reference);
        scope.put("peer",footprint==null ? new CompoundTag() : footprint);
        scope.put("pair",pairIntent(level,primary,peer));
        scope.putString("work",digest(sharedWork(primary,reference,work)));return scope;
    }
    /** Work cells inside the shared footprint, keyed by world position so the digest is stable. */
    private static CompoundTag sharedWork(RoadPlan main,RoadPlan reference,Blueprint work) {
        var body=RoadCrossingGeometry.body(main).intersect(RoadCrossingGeometry.body(reference));
        var out=new CompoundTag();
        if(body==null) {out.putBoolean("none",true);return out;}
        out.putIntArray("rect",new int[]{body.minX(),body.minZ(),body.maxX(),body.maxZ()});
        var origin=BlueprintCells.origin(main,work);
        var cells=new java.util.TreeMap<String,String>();
        for(var info:work.getBlockInfoAsList()) {
            var pos=origin.offset(info.getPos());
            if(!body.contains(pos.getX(),pos.getZ()))continue;
            cells.put(pos.getX()+","+pos.getY()+","+pos.getZ(),info.getState().toString());
        }
        var list=new ListTag();
        cells.forEach((key,state) -> {var cell=new CompoundTag();cell.putString("at",key);cell.putString("state",state);list.add(cell);});
        out.put("cells",list);return out;
    }
    /** Only neighbours whose shared footprint actually changed need authorization. */
    private static List<RoadPlan> changedPeers(ServerLevel level,RoadJointWork.Prepared proposed) {
        var result=new ArrayList<RoadPlan>();
        for(String key:proposed.updates().getAllKeys()) {
            var live=RoadManager.get(level).get(UUID.fromString(key));
            if(live!=null)result.add(live);
        }
        return result;
    }
    /** Must succeed before publishing a native work blueprint or creating any construction session. */
    public static Authorized prepare(ServerLevel level,ServerPlayer actor,RoadPlan primary) {
        primary.planning().planner(actor.getUUID());
        var proposed=RoadJointWork.propose(level,actor,primary,false);
        boolean preserve=false;
        for(var peer:changedPeers(level,proposed)) if(!canPlan(level,actor,primary,peer)) {
            boolean restoration=RoadCrossingRestoration.geometry(level,primary,peer);
            // Unauthorized pairs must already be on the automatic strict layered rule staged by planning.
            if(!restoration && !RoadCrossings.setting(level,primary,peer).layered()) {
                RoadNotices.chatMessage(actor,null,Component.translatable("rp_crossroads.consent.choose",RoadNames.label(peer)));return null;
            }
            preserve=true;
        }
        if(preserve) proposed=RoadJointWork.propose(level,actor,primary,true);
        var grants=new CompoundTag();
        for(var peer:changedPeers(level,proposed)) {
            // An effective pair grant is the durable proof. A creative OP's live permission is
            // only a fallback: using it while a grant exists replaces it with a receipt that
            // can lapse when the OP leaves creative mode or logs off.
            boolean granted=validGrant(level,primary,peer);
            if(!granted && RoadPermissions.canManage(level,actor,peer)) {
                var nativeProof=nativeReceipt(level,actor,primary,peer,proposed.work());if(nativeProof!=null)grants.put(peer.id().toString(),nativeProof);continue;
            }
            var receipt=scope(primary,peer,proposed.work(),actor.getUUID(),level);
            var request=approved(level,actor.getUUID(),primary,peer);
            if(granted) {
                // A mutual pair grant covers this junction both ways; the receipt keeps the same
                // work/session binding so execution still cannot widen the scope.
                receipt.putBoolean("grant",true);
                Crossroads.LOGGER.info("Crossing pair grant authorized: main={} actor={} neighbor={}",primary.id(),actor.getUUID(),peer.id());
            } else if(request!=null) {
                receipt.putUUID("approver",request.recipient);receipt.putUUID("request",request.token);
            } else {
                RoadNotices.chatMessage(actor,null,Component.translatable("rp_crossroads.consent.choose",RoadNames.label(peer)));return null;
            }
            grants.put(peer.id().toString(),receipt);
        }
        var mainProof=nativeReceipt(level,actor,primary,RoadManager.get(level).get(primary.id()),proposed.work());if(mainProof!=null)grants.put(primary.id().toString(),mainProof);
        return new Authorized(proposed,grants);
    }
    private static void sendRequest(Request request,ServerPlayer actor,RoadPlan peer,ServerPlayer recipient) {
        var message=Component.translatable("rp_crossroads.consent.request",actor.getName(),RoadNames.label(peer),120);
        for(String action:List.of("accept","reject"))message.append(Component.literal(" ")).append(Component.translatable("rp_crossroads.consent."+action)
                .withStyle(style -> style.withUnderlined(true).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/mcrcross "+request.token+" "+action))));
        RoadNotices.chatMessage(recipient,null,message);
        Crossroads.LOGGER.info("Crossing consent requested: token={} main={} neighbor={} actor={} recipient={} rank={} work={}",request.token,request.main,request.peer,request.actor,request.recipient,priority(request.level,recipient,peer),request.scope.getString("work"));
        int[] bounds=request.scope.getIntArray("bounds");
        if(bounds.length==6)RoadNotices.chatMessage(recipient,null,Component.translatable("rp_crossroads.consent.scope",request.scope.getString("mainName"),
                bounds[0]+".."+bounds[3],bounds[1]+".."+bounds[4],bounds[2]+".."+bounds[5]));
        RoadNotices.chatMessage(actor,null,Component.translatable("rp_crossroads.consent.sent",RoadNames.label(peer),recipient.getName()));
    }
    private static void update(Request request) {
        var previous=request.decision;refresh(request);
        // An index query may discover expiry. Publish after the query, never recursively
        // send an older index after a nested proposal refresh has already sent the new one.
        if(request.decision!=previous)CHANGES.add(request.token);
    }
    private static void refresh(Request request) {
        if(request.decision!=Decision.PENDING && request.decision!=Decision.APPROVED)return;
        var actor=request.level.getServer().getPlayerList().getPlayer(request.actor);
        var peer=RoadManager.get(request.level).get(request.peer);var main=RoadManager.get(request.level).get(request.main);
        var recipient=request.level.getServer().getPlayerList().getPlayer(request.recipient);
        String reason=actor==null ? "actor_offline" : peer==null || main==null ? "road_removed"
            : actor.serverLevel()!=request.level ? "actor_dimension" : !RoadPermissions.canManage(request.level,actor,main) ? "actor_permission"
            : !request.source.equals(planningBoundary(request.level,main,peer)) ? "scope_changed"
            : recipient==null ? "recipient_offline" : !RoadPermissions.canManage(request.level,recipient,peer) ? "recipient_permission" : "";
        if(!reason.isEmpty()){request.invalidReason=reason;request.decision=Decision.INVALID;return;}
        var next=timeout(request.decision,System.currentTimeMillis(),request.deadline,true,true);
        if(request.decision==Decision.PENDING && next==Decision.REJECTED) request.timedOut=true;
        request.decision=next;
    }
    public static void validate(ServerLevel level,UUID actor,RoadRepairSession session) {
        var own=RoadManager.get(level).get(session.target().id());if(own==null)throw new IllegalArgumentException("Road removed.");
        var initiatingPlayer=level.getServer().getPlayerList().getPlayer(actor);
        if(initiatingPlayer!=null && !RoadPermissions.canManage(level,initiatingPlayer,own))throw new IllegalArgumentException("Main road management permission was revoked.");
        var grants=session.crossingGrants();
        var image=session.workBlueprint().isEmpty() ? null : BlueprintUtil.readBlueprintFromNBT(session.workBlueprint(),level.registryAccess());
        for(String key:session.neighborUpdates().getAllKeys()) {
            var peer=RoadManager.get(level).get(UUID.fromString(key));if(peer==null)throw new IllegalArgumentException("Related road removed.");
            var player=level.getServer().getPlayerList().getPlayer(actor);
            if(player!=null && RoadPermissions.canManage(level,player,peer))continue;
            var reference=RoadCrossings.reference(level,peer);
            var footprint=RoadCrossings.footprint(session.target(),reference);
            var sharedDigest=image==null ? "" : digest(sharedWork(session.target(),reference,image));
            var grant=grants.getCompound(key);
            if(!grant.hasUUID("actor") || !grant.getUUID("actor").equals(actor) || !grant.getUUID("main").equals(own.id())
                    || !grant.hasUUID("session") || !grant.getUUID("session").equals(session.token())
                    || !grant.getString("work").equals(sharedDigest) || !grant.getString("dimension").equals(level.dimension().location().toString())
                    || !NbtUtils.readBlockPos(grant,"anchor").orElse(BlockPos.ZERO).equals(session.target().anchorPos())
                    || !grant.getCompound("peer").equals(footprint==null ? new CompoundTag() : footprint)
                    || !grant.getCompound("pair").equals(pairIntent(level,session.target(),peer)))throw new IllegalArgumentException("Crossing consent expired; prepare again.");
            var bounds=grant.getIntArray("bounds");
            var workOrigin=bounds.length==6 ? new BlockPos(bounds[0],bounds[1],bounds[2]) : image==null ? null : BlueprintCells.origin(session.target(),image);
            if(grant.getBoolean("native")) {
                // Preserve an already-confirmed native creative-OP operation during logout. This is
                // scoped to the frozen work; the live OP level/config are rechecked and no rank is granted.
                if(!nativeOffline(level,actor,grant))
                    throw new IllegalArgumentException("Native management permission was revoked.");
            } else if(grant.getBoolean("restore")) {
                if(image==null || workOrigin==null || session.neighborUpdates().contains(key) || !RoadCrossingRestoration.permits(level,session.target(),peer,image,workOrigin))
                    throw new IllegalArgumentException("The repair changes the neighboring road; request approval again.");
            } else if(grant.getBoolean("safe")) {
                if(image==null || workOrigin==null || session.neighborUpdates().contains(key) || !nonInterfering(level,session.target(),peer,image,workOrigin))throw new IllegalArgumentException("The foreign road cannot be modified without consent.");
            }
            // Approved pair grants are checked at approval time; the session receipt binds the
            // frozen work, so no live approver rank or in-memory request is consulted here.
        }
    }
    /** Every foreign occupied target must be untouched, and its complete lamps/foundation must not obstruct this route. */
    public static boolean nonInterfering(ServerLevel level,RoadPlan main,RoadPlan foreign,Blueprint work) {
        return nonInterfering(level,main,foreign,work,BlueprintCells.origin(main,work));
    }
    public static boolean nonInterfering(ServerLevel level,RoadPlan main,RoadPlan foreign,Blueprint work,BlockPos workOrigin) {
        var reference=RoadCrossings.reference(level,foreign);var setting=RoadCrossings.setting(level,main,foreign);if(!setting.layered() || !RoadCrossingSafety.permits(level,main,reference,setting.order()))return false;
        var actual=RoadBlueprintStore.loadApplied(level,foreign);if(actual==null)return true;
        var origin=BlueprintCells.origin(reference,actual);
        // No real change: every foreign solid cell covered by the work already matches it.
        boolean changed=false;
        for(var info:actual.getBlockInfoAsList()) {
            if(!RoadBlocks.isConcrete(info.getState()) && !RoadBlocks.isSolidFill(info.getState()))continue;
            var pos=origin.offset(info.getPos());var local=pos.subtract(workOrigin);
            if(!RoadCrossings.inside(work,local))continue;
            var target=work.getBlockState(local);
            if(target.is(com.ldtteam.structurize.blocks.ModBlocks.blockSubstitution.get()))continue;
            if(!target.equals(info.getState())) {changed=true;break;}
        }
        if(!changed)return true;
        var own=RoadHeightProfile.of(main);var other=RoadHeightProfile.of(reference);
        for(var info:actual.getBlockInfoAsList()) {
            if(!RoadBlocks.isConcrete(info.getState()) && !RoadBlocks.isSolidFill(info.getState()))continue;
            var pos=origin.offset(info.getPos());var local=pos.subtract(workOrigin);
            if(RoadCrossings.inside(work,local) && !work.getBlockState(local).is(com.ldtteam.structurize.blocks.ModBlocks.blockSubstitution.get()))return false;
            if(!RoadCrossingGeometry.body(main).contains(pos.getX(),pos.getZ()))continue;
            int floor=own.row(RoadAxisMath.alongOf(main.axis(),pos)).baseY();
            int otherFloor=other.row(RoadAxisMath.alongOf(reference.axis(),pos)).baseY();
            if(floor>otherFloor && pos.getY()>=floor || floor<otherFloor && pos.getY()>=floor && pos.getY()<=floor+2)return false;
        }
        return true;
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mcrcross").then(Commands.argument("request",StringArgumentType.word())
                .then(Commands.literal("accept").executes(c -> reply(c.getSource().getPlayerOrException(),StringArgumentType.getString(c,"request"),true)))
                .then(Commands.literal("reject").executes(c -> reply(c.getSource().getPlayerOrException(),StringArgumentType.getString(c,"request"),false)))));
    }
    private static int reply(ServerPlayer player,String token,boolean accepted) {
        Request request;try {request=REQUESTS.get(UUID.fromString(token));}catch(IllegalArgumentException ex){return 0;}
        if(request==null || !request.recipient.equals(player.getUUID()) || request.decision!=Decision.PENDING){RoadNotices.chatMessage(player,null,Component.translatable("rp_crossroads.consent.reply_invalid"));return 0;}
        update(request);
        if(request.decision!=Decision.PENDING){RoadNotices.chatMessage(player,null,Component.translatable("rp_crossroads.consent.reply_invalid"));return 0;}
        request.decision=accepted ? Decision.APPROVED : Decision.REJECTED;
        if(accepted)grantBoth(request);
        RoadNotices.chatMessage(player,null,Component.translatable("rp_crossroads.consent.reply_recorded",Component.translatable(accepted ? "rp_crossroads.consent.accept":"rp_crossroads.consent.reject")));
        notifyActor(request);if(accepted)resume(request);return 1;
    }
    public static void consume(RoadRepairSession session) {
        var grants=session.crossingGrants();for(String key:grants.getAllKeys()) {
            var grant=grants.getCompound(key);if(!grant.hasUUID("request"))continue;
            var request=REQUESTS.get(grant.getUUID("request"));if(request!=null && request.actor.equals(session.owner())) {
                request.invalidReason="consumed";request.decision=Decision.INVALID;Crossroads.LOGGER.info("Crossing consent consumed: request={} session={} phase={}",request.token,session.token(),session.phase());
            }
        }
    }
    private static void notifyActor(Request request) {
        Crossroads.LOGGER.info("Crossing consent resolved: token={} main={} neighbor={} recipient={} decision={} reason={}",request.token,request.main,request.peer,request.recipient,request.decision,request.invalidReason);
        var actor=request.level.getServer().getPlayerList().getPlayer(request.actor);
        if(actor!=null) {
            // One shape for "no longer valid": a reason is always available, so the same
            // event can no longer fall back to a second, vaguer sentence.
            if(request.decision==Decision.INVALID || request.decision==Decision.PENDING)
                RoadNotices.chatMessage(actor,null,Component.translatable("rp_crossroads.consent.invalid",
                        Component.translatable("rp_crossroads.consent.reason_"
                                +(request.invalidReason.isEmpty() ? "scope_changed" : request.invalidReason))));
            else RoadNotices.chatMessage(actor,null,Component.translatable(request.timedOut ? "rp_crossroads.consent.expired"
                    : request.decision==Decision.APPROVED ? "rp_crossroads.consent.approved" : "rp_crossroads.consent.rejected"));
        }
        if(request.timedOut) {
            var recipient=request.level.getServer().getPlayerList().getPlayer(request.recipient);
            if(recipient!=null)RoadNotices.chatMessage(recipient,null,Component.translatable("rp_crossroads.consent.expired"));
        }
        request.level.players().forEach(com.YLFXM.rp.crossroads.network.Network::sendRoads);
    }
    private static void resume(Request request) {
        var actor=request.level.getServer().getPlayerList().getPlayer(request.actor);var main=RoadManager.get(request.level).get(request.main);
        if(actor==null || main==null || !RoadRules.canEdit(main))return;
        RoadPlanningActions.acceptedJunction(actor,main,RoadManager.get(request.level).get(request.peer));
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        RoadPlanningActions.expireHeights(System.currentTimeMillis());
        if(event.getServer().getTickCount()%20!=0)return;
        for(var request:List.copyOf(REQUESTS.values()))if(request.decision==Decision.PENDING || request.decision==Decision.APPROVED) {
            update(request);
        }
        while(!CHANGES.isEmpty()) {
            var token=CHANGES.iterator().next();CHANGES.remove(token);var request=REQUESTS.get(token);if(request==null)continue;
            notifyActor(request);if(request.decision==Decision.APPROVED)resume(request);
        }
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {REQUESTS.clear();NOTICES.clear();CHANGES.clear();}
    private RoadCrossingConsent() { }
}
