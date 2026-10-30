/* SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 ZhuanZ (YLFXM) */
package com.YLFXM.mcreconstruction.road;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.YLFXM.mcreconstruction.*;
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

/** Per-operation consent. No general colony permission is changed, and no world work is dispatched here. */
@EventBusSubscriber(modid=MCReconstruction.MODID)
public final class RoadCrossingConsent {
    public enum Decision { PENDING, APPROVED, REJECTED, INVALID }
    public static Decision timeout(Decision current,long now,long deadline,boolean sourceValid,boolean recipientValid) {
        if(current!=Decision.PENDING)return current;
        if(!sourceValid || !recipientValid)return Decision.INVALID;
        return now>=deadline ? Decision.APPROVED : Decision.PENDING;
    }
    private static final Map<UUID,Request> REQUESTS=new LinkedHashMap<>();
    private static final Set<UUID> CHANGES=new LinkedHashSet<>();
    private static final Map<String,Long> NOTICES=new HashMap<>();
    public static boolean debounce(long now,long previous) {return now-previous<2_000;}
    /** Shapes and actual peer contents define planning scope; derived own heights do not replace requests. */
    public static CompoundTag planningBoundary(ServerLevel level,RoadPlan main,RoadPlan peer) {
        var out=new CompoundTag();out.put("main",shape(main));out.put("peer",shape(peer));
        var actual=RoadBlueprintStore.appliedPlan(level,peer);
        if(actual!=null){var physical=intent(actual);physical.remove("planning");out.put("actual",physical);}
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
        return RoadPermissions.canManage(level,actor,peer) || own.planning().policy(peer.id())==1 && approved(level,actor.getUUID(),own,peer)!=null
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
        var grant=own.planning().grants().get(peer.id());
        return grant!=null && grant.boundary().equals(pairDigest(level,own,peer));
    }
    private static void grantBoth(Request request) {
        var level=request.level;var main=RoadManager.get(level).get(request.main);var peer=RoadManager.get(level).get(request.peer);
        if(main==null || peer==null)return;
        String boundary=pairDigest(level,main,peer);long now=System.currentTimeMillis();
        main.planning().grant(peer.id(),new RoadPlanningState.Grant(request.token,request.actor,now,boundary));
        peer.planning().grant(main.id(),new RoadPlanningState.Grant(request.token,request.actor,now,boundary));
        RoadManager.get(level).add(main);RoadManager.get(level).add(peer);
        MCReconstruction.LOGGER.info("Crossing consent granted both ways: request={} main={} peer={}",request.token,request.main,request.peer);
    }
    /** After a successful commit both sides are re-checked; only a fully built pair loses its grants. */
    public static void settle(ServerLevel level,RoadPlan road,Set<UUID> appliedPeers) {
        boolean changed=false;
        for(var entry:new ArrayList<>(road.planning().grants().entrySet())) {
            RoadPlan peer=RoadManager.get(level).get(entry.getKey());
            if(peer==null) {road.planning().clearGrant(entry.getKey());changed=true;continue;}
            // Only a peer whose actual snapshot was committed by THIS transaction is finished;
            // "state == BUILT" alone would release the grant while its change is still pending.
            boolean selfBuilt=road.appliedBounds()!=null;
            boolean peerBuilt=appliedPeers.contains(peer.id());
            if(selfBuilt && peerBuilt) {
                road.planning().clearGrant(peer.id());peer.planning().clearGrant(road.id());
                RoadManager.get(level).add(peer);changed=true;
                MCReconstruction.LOGGER.info("Crossing grants released: both roads built pair={} {}",road.id(),peer.id());
            }
        }
        if(changed)RoadManager.get(level).add(road);
    }
    public static boolean canPlan(ServerLevel level,RoadPlan own,RoadPlan peer) {
        UUID planner=own.planning().planner()!=null ? own.planning().planner() : own.creator();
        var actor=planner==null ? null : level.getServer().getPlayerList().getPlayer(planner);
        if(actor!=null)return canPlan(level,actor,own,peer);
        return planner!=null && approverStillManages(level,planner,peer) || validGrant(level,own,peer);
    }
    public static void inspect(ServerLevel level,ServerPlayer actor,RoadPlan own) {
        own.planning().planner(actor.getUUID());
        for(var n:RoadCrossings.neighbors(level,own))if(!RoadPermissions.canManage(level,actor,n.live()) && own.planning().policy(n.live().id())==0
                && !RoadCrossingRestoration.geometry(level,own,n.live())) {
            String key=actor.getUUID()+":"+digest(planningBoundary(level,own,n.live()));
            if(NOTICES.putIfAbsent(key,System.currentTimeMillis())==null)actor.displayClientMessage(Component.translatable("mcreconstruction.consent.choose",RoadNames.label(n.live())),false);
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
                actor.displayClientMessage(Component.translatable(r.decision==Decision.APPROVED ? "mcreconstruction.consent.approved" : "mcreconstruction.consent.waiting"),false);return;
            }
            if(r.decision==Decision.PENDING || r.decision==Decision.APPROVED)r.decision=Decision.INVALID;
        }
        var chosen=recipient(level,peer);if(chosen==null){actor.displayClientMessage(Component.translatable("mcreconstruction.consent.offline",RoadNames.label(peer)),false);return;}
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
        boolean automaticApproval;
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
    /** Stable geometric intent, excluding UI diagnostics, state, timestamps and render cache revisions. */
    public static CompoundTag intent(RoadPlan road) {
        RoadPlan p=RoadPlan.load(road.geometrySnapshot());p.applyPendingRows();var full=p.save();CompoundTag out=new CompoundTag();
        for(String key:List.of("id","dimension","dim","anchor","controller","facing","pack","variant","mirror","width","walkFrom","walkTo","widthSide","lampSpacing","travelDir","segments","rows"))
            if(full.contains(key))out.put(key,full.get(key).copy());
        CompoundTag planning=p.planning().save();planning.remove("revision");planning.remove("sampleKey");planning.remove("issue");planning.remove("crossingIssue");planning.remove("terrain");planning.remove("baseRows");planning.remove("hasBase");planning.remove("manual");planning.remove("refreshNeeded");planning.remove("externalChanges");
        planning.remove("relationshipKey");
        ListTag pairs=new ListTag();for(var id:p.planning().crossings().keySet()) {
            var rule=p.planning().crossings().get(id);
            if(!rule.layered() && rule.platform()==null && rule.version()==0)continue;
            CompoundTag pair=new CompoundTag();pair.putUUID("road",id);pair.putBoolean("layered",rule.layered());pair.putInt("order",rule.order());if(rule.platform()!=null)pair.putInt("platform",rule.platform());pairs.add(pair);
        }
        planning.put("pairs",pairs);out.put("planning",planning);return out;
    }
    public static CompoundTag pairIntent(RoadPlan own,RoadPlan other) {
        var rule=RoadCrossings.setting(own,other);var tag=new CompoundTag();tag.putBoolean("layered",rule.layered());tag.putInt("order",rule.order());tag.putLong("version",rule.version());if(rule.platform()!=null)tag.putInt("platform",rule.platform());return tag;
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
    private static boolean approverStillManages(ServerLevel level,UUID id,RoadPlan peer) {
        var online=level.getServer().getPlayerList().getPlayer(id);
        if(online!=null)return RoadPermissions.canManage(level,online,peer);
        var colony=IColonyManager.getInstance().getColonyByPosFromWorld(level,peer.controllerPos());
        return colony==null ? peer.creator()==null || peer.creator().equals(id)
                : colony.getPermissions().hasPermission(colony.getPermissions().getRank(id),Action.MANAGE_HUTS);
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
        var origin=RoadBlueprintOps.origin(primary,work);scope.putString("mainName",RoadNames.label(primary));
        scope.putIntArray("bounds",new int[]{origin.getX(),origin.getY(),origin.getZ(),origin.getX()+work.getSizeX()-1,origin.getY()+work.getSizeY()-1,origin.getZ()+work.getSizeZ()-1});
        scope.put("peer",intent(RoadCrossings.reference(level,peer)));scope.put("pair",pairIntent(primary,peer));scope.putString("work",digest(BlueprintUtil.writeBlueprintToNBT(work)));return scope;
    }
    private static Map<UUID,RoadPlan> related(ServerLevel level,RoadPlan main,RoadJointWork.Prepared proposed) {
        Map<UUID,RoadPlan> result=new TreeMap<>();
        for(var n:RoadCrossings.neighbors(level,main))result.put(n.live().id(),n.live());
        for(String key:proposed.updates().getAllKeys()) {
            var model=RoadPlan.load(proposed.updates().getCompound(key).getCompound("model"));
            var live=RoadManager.get(level).get(model.id());if(live!=null)result.put(live.id(),live);
        }
        return result;
    }
    /** Must succeed before publishing a native work blueprint or creating any construction session. */
    public static Authorized prepare(ServerLevel level,ServerPlayer actor,RoadPlan primary) {
        primary.planning().planner(actor.getUUID());
        boolean preserve=false;
        for(var n:RoadCrossings.neighbors(level,primary))if(!canPlan(level,actor,primary,n.live())) {
            boolean restoration=RoadCrossingRestoration.geometry(level,primary,n.live());
            if(!restoration && (primary.planning().policy(n.live().id())!=2 || !n.setting().layered())) {
                actor.displayClientMessage(Component.translatable("mcreconstruction.consent.choose",RoadNames.label(n.live())),false);return null;
            }
            preserve=true;
        }
        var proposed=RoadJointWork.propose(level,actor,primary,preserve);var grants=new CompoundTag();
        for(var peer:related(level,primary,proposed).values()) {
            if(RoadPermissions.canManage(level,actor,peer)) {
                var nativeProof=nativeReceipt(level,actor,primary,peer,proposed.work());if(nativeProof!=null)grants.put(peer.id().toString(),nativeProof);continue;
            }
            var receipt=scope(primary,peer,proposed.work(),actor.getUUID(),level);
            var request=primary.planning().policy(peer.id())==1 ? approved(level,actor.getUUID(),primary,peer):null;
            if(request!=null) {
                receipt.putUUID("approver",request.recipient);receipt.putUUID("request",request.token);
            } else if(!proposed.updates().contains(peer.id().toString()) && RoadCrossingRestoration.permits(level,primary,peer,proposed.work())) {
                receipt.putBoolean("restore",true);
            } else if(primary.planning().policy(peer.id())==2 && !proposed.updates().contains(peer.id().toString()) && nonInterfering(level,primary,peer,proposed.work())) {
                receipt.putBoolean("safe",true);
                MCReconstruction.LOGGER.info("Crossing no-touch route authorized: main={} actor={} neighbor={}",primary.id(),actor.getUUID(),peer.id());
            } else if(validGrant(level,primary,peer)) {
                // A mutual pair grant covers this junction both ways; the receipt keeps the same
                // work/session binding so execution still cannot widen the scope.
                receipt.putBoolean("grant",true);
                MCReconstruction.LOGGER.info("Crossing pair grant authorized: main={} actor={} neighbor={}",primary.id(),actor.getUUID(),peer.id());
            } else {
                actor.displayClientMessage(Component.translatable("mcreconstruction.consent.choose",RoadNames.label(peer)),false);return null;
            }
            grants.put(peer.id().toString(),receipt);
        }
        var mainProof=nativeReceipt(level,actor,primary,RoadManager.get(level).get(primary.id()),proposed.work());if(mainProof!=null)grants.put(primary.id().toString(),mainProof);
        return new Authorized(proposed,grants);
    }
    private static void sendRequest(Request request,ServerPlayer actor,RoadPlan peer,ServerPlayer recipient) {
        var message=Component.translatable("mcreconstruction.consent.request",actor.getName(),RoadNames.label(peer),120);
        for(String action:List.of("accept","reject"))message.append(Component.literal(" ")).append(Component.translatable("mcreconstruction.consent."+action)
                .withStyle(style -> style.withUnderlined(true).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/mcrcross "+request.token+" "+action))));
        recipient.displayClientMessage(message,false);
        MCReconstruction.LOGGER.info("Crossing consent requested: token={} main={} neighbor={} actor={} recipient={} rank={} work={}",request.token,request.main,request.peer,request.actor,request.recipient,priority(request.level,recipient,peer),request.scope.getString("work"));
        int[] bounds=request.scope.getIntArray("bounds");
        if(bounds.length==6)recipient.displayClientMessage(Component.translatable("mcreconstruction.consent.scope",request.scope.getString("mainName"),
                bounds[0]+".."+bounds[3],bounds[1]+".."+bounds[4],bounds[2]+".."+bounds[5]),false);
        actor.displayClientMessage(Component.translatable("mcreconstruction.consent.sent",RoadNames.label(peer),recipient.getName()).append(Component.literal(" "))
                .append(Component.translatable("mcreconstruction.consent.cancel").withStyle(s -> s.withUnderlined(true).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/mcrcross "+request.token+" cancel")))),false);
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
            : main.planning().policy(peer.id())!=1 ? "strategy_changed" : !request.source.equals(planningBoundary(request.level,main,peer)) ? "scope_changed"
            : recipient==null ? "recipient_offline" : !RoadPermissions.canManage(request.level,recipient,peer) ? "recipient_permission" : "";
        if(!reason.isEmpty()){request.invalidReason=reason;request.decision=Decision.INVALID;return;}
        var next=timeout(request.decision,System.currentTimeMillis(),request.deadline,true,true);
        boolean approvedNow=request.decision==Decision.PENDING && next==Decision.APPROVED;
        if(approvedNow)request.automaticApproval=true;
        request.decision=next;
        if(approvedNow)grantBoth(request);
    }
    public static void validate(ServerLevel level,UUID actor,RoadRepairSession session) {
        var own=RoadManager.get(level).get(session.target().id());if(own==null)throw new IllegalArgumentException("Road removed.");
        var initiatingPlayer=level.getServer().getPlayerList().getPlayer(actor);
        if(initiatingPlayer!=null && !RoadPermissions.canManage(level,initiatingPlayer,own))throw new IllegalArgumentException("Main road management permission was revoked.");
        var grants=session.crossingGrants();String work=session.workDigest();
        if(initiatingPlayer==null && !approverStillManages(level,actor,own)) {
            var mainProof=grants.getCompound(own.id().toString());
            if(!mainProof.hasUUID("actor") || !mainProof.getUUID("actor").equals(actor) || !mainProof.hasUUID("session") || !mainProof.getUUID("session").equals(session.token())
                    || !mainProof.getString("work").equals(work) || !mainProof.getString("dimension").equals(level.dimension().location().toString())
                    || !NbtUtils.readBlockPos(mainProof,"anchor").orElse(BlockPos.ZERO).equals(session.target().anchorPos()) || !nativeOffline(level,actor,mainProof))
                throw new IllegalArgumentException("Main road management permission was revoked.");
        }
        for(String key:session.dependencies().getAllKeys()) {
            var peer=RoadManager.get(level).get(UUID.fromString(key));if(peer==null)throw new IllegalArgumentException("Related road removed.");
            // Read-only third-road constraints are still verified by verifyFrozen and
            // final geometry/clearance validation; they are never permission grants.
            if(session.dependencies().getCompound(key).getBoolean("constraintOnly"))continue;
            var player=level.getServer().getPlayerList().getPlayer(actor);
            if(player!=null && RoadPermissions.canManage(level,player,peer))continue;
            if(player==null && approverStillManages(level,actor,peer))continue;
            var grant=grants.getCompound(key);
            if(!grant.hasUUID("actor") || !grant.getUUID("actor").equals(actor) || !grant.getUUID("main").equals(own.id())
                    || !grant.hasUUID("session") || !grant.getUUID("session").equals(session.token())
                    || !grant.getString("work").equals(work) || !grant.getString("dimension").equals(level.dimension().location().toString())
                    || !NbtUtils.readBlockPos(grant,"anchor").orElse(BlockPos.ZERO).equals(session.target().anchorPos())
                    || !grant.getCompound("peer").equals(intent(RoadCrossings.reference(level,peer)))
                    || !grant.getCompound("pair").equals(pairIntent(session.target(),peer)))throw new IllegalArgumentException("Crossing consent expired; prepare again.");
            if(grant.getBoolean("native")) {
                // Preserve an already-confirmed native creative-OP operation during logout. This is
                // scoped to the frozen work; the live OP level/config are rechecked and no rank is granted.
                if(!nativeOffline(level,actor,grant))
                    throw new IllegalArgumentException("Native management permission was revoked.");
            } else if(grant.getBoolean("restore")) {
                var image=BlueprintUtil.readBlueprintFromNBT(session.workBlueprint(),level.registryAccess());
                if(session.neighborUpdates().contains(key) || !RoadCrossingRestoration.permits(level,session.target(),peer,image))
                    throw new IllegalArgumentException("The repair changes the neighboring road; request approval again.");
            } else if(grant.getBoolean("safe")) {
                var image=BlueprintUtil.readBlueprintFromNBT(session.workBlueprint(),level.registryAccess());
                if(session.neighborUpdates().contains(key) || !nonInterfering(level,session.target(),peer,image))throw new IllegalArgumentException("The foreign road cannot be modified without consent.");
            } else {
                if(!grant.hasUUID("approver") || !approverStillManages(level,grant.getUUID("approver"),peer))throw new IllegalArgumentException("The crossing approver no longer has management permission.");
                if(session.phase()==RoadRepairSession.Phase.PREPARED && !grant.getBoolean("confirmed")) {
                    var request=grant.hasUUID("request") ? REQUESTS.get(grant.getUUID("request")):null;
                    if(request==null)throw new IllegalArgumentException("Crossing planning approval is no longer available.");
                    update(request);if(request.decision!=Decision.APPROVED)throw new IllegalArgumentException("Crossing planning approval was cancelled or expired.");
                }
            }
        }
    }
    /** Every foreign occupied target must be untouched, and its complete lamps/foundation must not obstruct this route. */
    public static boolean nonInterfering(ServerLevel level,RoadPlan main,RoadPlan foreign,Blueprint work) {
        var reference=RoadCrossings.reference(level,foreign);var setting=RoadCrossings.setting(main,foreign);if(!setting.layered() || !RoadCrossingSafety.permits(level,main,reference,setting.order()))return false;
        var actual=RoadBlueprintStore.loadApplied(level,foreign);if(actual==null)return true;
        var origin=RoadBlueprintOps.origin(reference,actual);var workOrigin=RoadBlueprintOps.origin(main,work);
        var own=RoadHeightProfile.of(main);var other=RoadHeightProfile.of(reference);
        for(var info:actual.getBlockInfoAsList()) {
            if(!RoadBlueprintOps.isConcrete(info.getState()) && !RoadBlueprintOps.isSolidFill(info.getState()))continue;
            var pos=origin.offset(info.getPos());var local=pos.subtract(workOrigin);
            if(RoadCrossings.inside(work,local) && !work.getBlockState(local).is(com.ldtteam.structurize.blocks.ModBlocks.blockSubstitution.get()))return false;
            if(!RoadCrossingGeometry.body(main).contains(pos.getX(),pos.getZ()))continue;
            int floor=own.row(main.axis()==net.minecraft.core.Direction.Axis.X ? pos.getX() : pos.getZ()).baseY();
            int otherFloor=other.row(reference.axis()==net.minecraft.core.Direction.Axis.X ? pos.getX() : pos.getZ()).baseY();
            if(floor>otherFloor && pos.getY()>=floor || floor<otherFloor && pos.getY()>=floor && pos.getY()<=floor+2)return false;
        }
        return true;
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mcrcross").then(Commands.argument("request",StringArgumentType.word())
                .then(Commands.literal("accept").executes(c -> reply(c.getSource().getPlayerOrException(),StringArgumentType.getString(c,"request"),true)))
                .then(Commands.literal("reject").executes(c -> reply(c.getSource().getPlayerOrException(),StringArgumentType.getString(c,"request"),false)))
                .then(Commands.literal("cancel").executes(c -> cancelRequest(c.getSource().getPlayerOrException(),StringArgumentType.getString(c,"request"))))));
    }
    private static int reply(ServerPlayer player,String token,boolean accepted) {
        Request request;try {request=REQUESTS.get(UUID.fromString(token));}catch(IllegalArgumentException ex){return 0;}
        if(request==null || !request.recipient.equals(player.getUUID()) || request.decision!=Decision.PENDING){player.displayClientMessage(Component.translatable("mcreconstruction.consent.reply_invalid"),false);return 0;}
        update(request);
        if(request.decision!=Decision.PENDING){player.displayClientMessage(Component.translatable("mcreconstruction.consent.reply_invalid"),false);return 0;}
        request.decision=accepted ? Decision.APPROVED : Decision.REJECTED;
        if(accepted)grantBoth(request);
        player.displayClientMessage(Component.translatable("mcreconstruction.consent.reply_recorded",Component.translatable(accepted ? "mcreconstruction.consent.accept":"mcreconstruction.consent.reject")),false);
        notifyActor(request);if(accepted)resume(request);return 1;
    }
    public static void consume(RoadRepairSession session) {
        var grants=session.crossingGrants();for(String key:grants.getAllKeys()) {
            var grant=grants.getCompound(key);if(!grant.hasUUID("request"))continue;
            var request=REQUESTS.get(grant.getUUID("request"));if(request!=null && request.actor.equals(session.owner())) {
                request.decision=Decision.INVALID;MCReconstruction.LOGGER.info("Crossing consent consumed: request={} session={} phase={}",request.token,session.token(),session.phase());
            }
        }
    }
    private static int cancelRequest(ServerPlayer actor,String token) {
        Request request;try{request=REQUESTS.get(UUID.fromString(token));}catch(IllegalArgumentException ex){return 0;}
        if(request==null || !request.actor.equals(actor.getUUID()))return 0;
        if(request.decision!=Decision.PENDING && request.decision!=Decision.APPROVED){actor.displayClientMessage(Component.translatable("mcreconstruction.consent.reply_invalid"),false);return 0;}
        for(var current:REQUESTS.values())if(current.actor.equals(request.actor) && current.main.equals(request.main) && current.source.getCompound("main").equals(request.source.getCompound("main"))
                && (current.decision==Decision.PENDING || current.decision==Decision.APPROVED)) {
            current.decision=Decision.INVALID;var recipient=current.level.getServer().getPlayerList().getPlayer(current.recipient);
            if(recipient!=null)recipient.displayClientMessage(Component.translatable("mcreconstruction.consent.actor_cancelled"),false);
        }
        actor.displayClientMessage(Component.translatable("mcreconstruction.consent.actor_cancelled"),false);actor.serverLevel().players().forEach(com.YLFXM.mcreconstruction.network.Network::sendRoads);return 1;
    }
    private static void notifyActor(Request request) {
        MCReconstruction.LOGGER.info("Crossing consent resolved: token={} main={} neighbor={} recipient={} decision={} reason={}",request.token,request.main,request.peer,request.recipient,request.decision,request.invalidReason);
        var actor=request.level.getServer().getPlayerList().getPlayer(request.actor);
        var peer=RoadManager.get(request.level).get(request.peer);
        var automatic=Component.translatable("mcreconstruction.consent.auto_approved",request.scope.getString("mainName"),peer==null ? "?" : RoadNames.label(peer));
        if(actor!=null && request.decision==Decision.INVALID && !request.invalidReason.isEmpty())actor.displayClientMessage(Component.translatable("mcreconstruction.consent.invalid_reason",Component.translatable("mcreconstruction.consent.reason_"+request.invalidReason)),false);
        else if(actor!=null)actor.displayClientMessage(notifyTimeoutRecipient(request.decision,request.automaticApproval) ? automatic : Component.translatable(request.decision==Decision.APPROVED ? "mcreconstruction.consent.approved" : request.decision==Decision.REJECTED ? "mcreconstruction.consent.rejected" : "mcreconstruction.consent.invalid"),false);
        if(notifyTimeoutRecipient(request.decision,request.automaticApproval)) {
            var recipient=request.level.getServer().getPlayerList().getPlayer(request.recipient);
            if(recipient!=null)recipient.displayClientMessage(automatic,false);
        }
        request.level.players().forEach(com.YLFXM.mcreconstruction.network.Network::sendRoads);
    }
    static boolean notifyTimeoutRecipient(Decision decision,boolean automatic) {return automatic && decision==Decision.APPROVED;}
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
