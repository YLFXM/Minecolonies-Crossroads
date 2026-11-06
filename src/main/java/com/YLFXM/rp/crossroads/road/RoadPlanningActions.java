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

import com.YLFXM.rp.crossroads.*;
import com.YLFXM.rp.crossroads.network.*;
import com.YLFXM.rp.crossroads.road.common.RoadProposal;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

/** Explicit auto planning/settings changes validate a copy before replacing a proposal. */
public final class RoadPlanningActions {
    /** Players confirm a manual-row override by pressing the same relation button again. */
    private record Offer(String boundary,int mode,int rows,long expires) { }
    private static final java.util.Map<String,Offer> OFFERS=new java.util.HashMap<>();
    private static final long OFFER_TTL=60_000L;
    /** Height changes wait for the player: override the hand rows, or keep the automatic height. */
    private record PendingHeight(java.util.UUID player,java.util.UUID road,java.util.UUID peer,int delta,String boundary,long expires) { }
    private static final java.util.Map<String,PendingHeight> HEIGHTS=new java.util.HashMap<>();
    private static String heightKey(java.util.UUID player,java.util.UUID road,java.util.UUID peer) {return player+":"+road+":"+peer;}
    /** Nothing was applied while waiting, so expiry simply drops the offer and keeps the automatic height. */
    static void expireHeights(long now) {HEIGHTS.values().removeIf(p -> p.expires()<=now);}
    private static String offerKey(ServerPlayer player,RoadPlan road,RoadPlan other) {return player.getUUID()+":"+road.id()+":"+other.id();}

    public static void handle(ServerPlayer player,RoadPlanningPayload request) {
        var level=player.serverLevel();var old=RoadManager.get(level).get(request.road());
        if(old!=null && RoadRules.canEdit(old))RoadLockReconcile.ensure(level,old);
        if(old==null || !old.controllerPos().equals(request.controller()) || !RoadRules.canEdit(old)
                || RoadRules.busy(old,RoadRules.EDIT) || !RoadPermissions.canManage(level,player,old)
                || RoadWandBinding.findBound(player.getInventory(),request.controller())==null) return;
        if(request.action()<0 || request.action()>5 || request.mode()<0 || request.mode()>2) return;
        if(request.action()==4) {
            if(old.state()!=RoadState.DRAFT)return;
            old.clearSegments();old.setEndpoints(null,null);old.clearRowOverrides();RoadManager.get(level).add(old);
            RoadJunctions.removeRoad(level,old.id());
            level.players().forEach(Network::sendRoads);return;
        }
        try(var edit=RoadJunctions.begin(level)) {
            RoadBlueprintStore.preserveBuilt(level,old);
            RoadReplanning.validate(old,request.a(),request.b());
            var ends=RoadReplanning.orient(old,request.a(),request.b());
            var plan=RoadPlanBuilder.build(level,old.anchorPos(),old.controllerPos(),old.controllerFacing(),ends[0],ends[1],
                    old.pack(),old.variant(),old.width(),old.walkRange().width(),request.mirror() ? -1 : 1,old.lampSpacing()).plan();
            RoadReplanning.inherit(old,plan,player.getUUID(),true);plan.setMirror(request.mirror());
            plan.planning().planner(player.getUUID());
            RoadPlan peerUpdate=null;
            RoadReplanning.clearRowsOnAxisChange(old,plan);
            if(request.action()==5) {
                var peer=RoadManager.get(level).get(request.other());
                if(peer==null || peer.id().equals(plan.id()) || peer.segments().isEmpty() || RoadPermissions.canManage(level,player,peer)
                        || RoadCrossingGeometry.body(plan).intersect(RoadCrossingGeometry.body(peer))==null)return;
                RoadLockReconcile.apply(level,plan);
                RoadManager.get(level).add(plan);
                RoadCrossingConsent.request(level,player,plan,peer);
                refreshAfterConsent(player,plan);return;
            }
            if(request.action()==3) {adjustPlatform(level,player,plan,request);RoadJunctions.flush(level);return;}
            if(request.action()==1) new java.util.ArrayList<>(plan.manualRows().keySet()).forEach(plan::clearManual);
            if(request.action()==2) {
                var other=RoadManager.get(level).get(request.other());
                if(other==null || other.id().equals(plan.id()) || other.segments().isEmpty()
                        || RoadRules.busy(other,RoadRules.MUTATION))
                    throw new IllegalArgumentException("The other road is unavailable or cannot be managed.");
                if(RoadCrossingGeometry.body(plan).intersect(RoadCrossingGeometry.body(other))==null)
                    throw new IllegalArgumentException("These roads do not overlap.");
                RoadCrossingConsent.ensureEditable(level,player,other,plan);
                // This edits only the managed primary's proposal. Foreign patches cannot publish or run
                // until RoadCrossingConsent.prepare/validate succeeds in RoadRepair, including native confirmation.
                var rule=RoadCrossings.setting(level,plan,other);int current=!rule.layered() ? 0:rule.order()>0 ? 1:2;
                final RoadPlan source=plan;
                String key=offerKey(player,old,other);
                String boundary=RoadCrossingConsent.pairDigest(level,old,other);
                Offer live=OFFERS.get(key);
                boolean override=live!=null && live.mode()==request.mode() && live.boundary().equals(boundary) && live.expires()>System.currentTimeMillis();
                if(override)OFFERS.remove(key);
                ModeChoice<ModeAttempt> selected;
                try {selected=chooseMode(current,request.mode(),mode -> modeAttempt(player,source,other,mode,override));}
                catch(IllegalArgumentException ex) {
                    if(!override) {
                        int rows=manualRowsInSpan(plan,other);
                        if(rows>0 && overrideSucceeds(player,source,other,request.mode())) {
                            OFFERS.put(key,new Offer(boundary,request.mode(),rows,System.currentTimeMillis()+OFFER_TTL));
                            RoadNotices.send(player,old.id(),"manual:"+boundary,Component.translatable("rp_crossroads.planning.manual_conflict",rows));
                            return;
                        }
                    }
                    RoadNotices.send(player,old.id(),"modes:"+RoadNotices.reasonKey(ex),Component.translatable("rp_crossroads.planning.modes_unavailable",RoadNotices.reason(ex)));return;
                }
                plan=selected.value().plan();peerUpdate=selected.value().peer();
                if(override) {
                    int restored=restoreUnchangedManual(old,plan,other);boolean peerRestored=peerUpdate!=null && restoreUnchangedManual(RoadManager.get(level).get(peerUpdate.id()),peerUpdate,plan)>0;
                    RoadNotices.send(player,old.id(),"manual_applied",Component.translatable("rp_crossroads.planning.manual_overridden",Math.max(1,manualRowsInSpan(old,other)-restored)));
                    Crossroads.LOGGER.info("Manual rows overridden on {} for junction with {} (kept {} peer={})",old.id(),other.id(),restored,peerRestored);
                }
                if(selected.fallback())RoadNotices.actionBar(player,"rp_crossroads.planning.mode_fallback");
            }
            stageAutomaticAvoidance(level,plan);
            try(var scope=RoadCrossings.scope(peerUpdate==null ? java.util.Map.of(plan.id(),plan):java.util.Map.of(plan.id(),plan,peerUpdate.id(),peerUpdate),java.util.Map.of())) {
                var retry=RoadPlan.load(plan.save());
                try { solve(level,plan,request.action()==2,request.action()==0 || request.action()==1); }
                catch(IllegalArgumentException ex) {
                    if(ex.getMessage()!=null && ex.getMessage().contains("budget"))throw ex;
                    if(RoadJunctions.flipStagedStrict(level,plan.id())) {
                        plan.adoptRows(retry.rows());plan.adoptPlanning(retry.planning());
                        solve(level,plan,request.action()==2,request.action()==0 || request.action()==1);
                    } else throw ex;
                }
                if(plan.state()!=RoadState.DRAFT)plan.setState(RoadState.PLANNED);
                var blueprint=RoadBlueprintGenerator.generate(plan,level,Config.defaultLampReserveHeight(),player);
                if(blueprint==null || !RoadProposal.save(level,plan,blueprint,RoadProposal.Save.CHECKED)) return;
                if(peerUpdate!=null) {
                    var peerImage=RoadBlueprintGenerator.generate(peerUpdate,level,Config.defaultLampReserveHeight(),player);
                    if(peerImage==null || !RoadProposal.save(level,peerUpdate,peerImage,RoadProposal.Save.CHECKED))return;
                }
                if(!RoadProposal.pack(level,old,plan,blueprint)) return;
                RoadLockReconcile.apply(level,plan);if(peerUpdate!=null)RoadLockReconcile.apply(level,peerUpdate);
                RoadProposal.index(level,plan,RoadProposal.Audience.NONE,player,null);
                if(peerUpdate!=null)RoadProposal.index(level,peerUpdate,RoadProposal.Audience.NONE,player,null);
                RoadProposal.sync(level,RoadProposal.Audience.ALL,player,null);
                // A proposal change refreshes the authoritative rect of every recorded pair.
                RoadJunctions.recomputeRoad(level,plan.id());
                if(peerUpdate!=null)RoadJunctions.recomputeRoad(level,peerUpdate.id());
                RoadJunctions.flush(level);
                RoadNotices.send(player,plan.id(),"planning:"+plan.planning().issue(),Component.translatable(plan.planning().issue().isEmpty()
                        ? "rp_crossroads.planning.updated" : "rp_crossroads.planning.conflict",RoadNotices.issue(plan.planning().issue())));
            }
        } catch(IllegalArgumentException ex) { RoadNotices.send(player,request.road(),"conflict:"+RoadNotices.reasonKey(ex),Component.translatable("rp_crossroads.planning.conflict",RoadNotices.reason(ex))); }
    }

    private static void solve(ServerLevel level,RoadPlan plan,boolean restore,boolean resample) {
        if(restore && plan.hasBase())RoadAutoPlanning.restoreBase(level,plan);
        else RoadAutoPlanning.initialize(level,plan,true,resample);
    }

    /**
     * Unauthorized neighbours default to strict layered avoidance. Their relation is staged in the
     * operation overlay and only becomes global when the planning save succeeds.
     */
    private static void stageAutomaticAvoidance(ServerLevel level,RoadPlan plan) {
        for(var n:RoadCrossings.neighbors(level,plan)) {
            if(n.live().id().equals(plan.id()))continue;
            if(RoadJunctions.find(level,plan.id(),n.live().id())!=null)continue;
            if(RoadCrossingConsent.canPlan(level,plan,n.live()))continue;
            if(RoadCrossingRestoration.geometry(level,plan,n.live()))continue;
            var walk=RoadCrossingGeometry.walk(plan).intersect(RoadCrossingGeometry.walk(n.model()));
            if(walk==null)continue;
            int ownY=RoadHeightProfile.of(plan).row(walk.first(plan)).baseY();
            int otherY=RoadHeightProfile.of(n.model()).row(walk.first(n.model())).baseY();
            stageRelation(level,plan,n.live(),new RoadPlanningState.Crossing(true,ownY>=otherY ? 1:-1,0,null));
        }
    }

    private static void stageRelation(ServerLevel level,RoadPlan own,RoadPlan peer,RoadPlanningState.Crossing rule) {
        var rect=RoadCrossingGeometry.body(own).intersect(RoadCrossingGeometry.body(peer));
        if(rect==null) {RoadJunctions.stageRemove(level,own.id(),peer.id());return;}
        var existing=RoadJunctions.find(level,own.id(),peer.id());
        long version=rule.version()==0 ? RoadJunctions.nextVersion(level) : rule.version();
        var normalized=new RoadPlanningState.Crossing(rule.layered(),rule.order(),version,rule.platform());
        RoadJunctions.stage(level,new RoadJunctions.Junction(own.id(),peer.id(),rect,normalized,
                existing==null ? null:existing.grant(),built(own),built(peer),existing==null ? 1:existing.rev()+1));
    }

    private static boolean built(RoadPlan plan) {
        return plan.appliedBounds()!=null || plan.appliedGeometry()!=null;
    }

    record ModeChoice<T>(int mode,T value,boolean fallback) { }
    static <T> ModeChoice<T> chooseMode(int current,int next,java.util.function.IntFunction<T> attempt) {
        if(current<0 || current>2 || next<0 || next>2 || current==next)throw new IllegalArgumentException("Invalid relation change.");
        String failure="";
        for(int mode:new int[]{next,3-current-next})try {return new ModeChoice<>(mode,attempt.apply(mode),mode!=next);}
        catch(IllegalArgumentException ex) {failure=ex.getMessage();}
        throw new IllegalArgumentException(failure);
    }
    private record ModeAttempt(RoadPlan plan,RoadPlan peer) { }
    private static ModeAttempt modeAttempt(ServerPlayer player,RoadPlan source,RoadPlan other,int mode,boolean override) {
        var level=player.serverLevel();RoadPlan plan=RoadPlan.load(source.save());
        boolean authorized=RoadCrossingConsent.canPlan(level,player,plan,other);
        if(!authorized && mode==0)throw new IllegalArgumentException("Same-level junction changes require the neighbor's approval.");
        int order=mode==1 ? 1:mode==2 ? -1:0;
        plan.planning().spanFloor(other.id(),null);
        stageRelation(level,plan,other,new RoadPlanningState.Crossing(mode!=0,order,0,null));
        if(override)clearManualInSpan(plan,other);
        var peer=rebuildPeer(player,plan,other,override);
        try(var scope=RoadCrossings.scope(peer==null ? java.util.Map.of(plan.id(),plan):java.util.Map.of(plan.id(),plan,peer.id(),peer),java.util.Map.of(),plan.id())) {
            RoadAutoPlanning.restoreBase(level,plan);RoadCrossings.validateGeometry(level,plan);
        }
        return new ModeAttempt(plan,peer);
    }
    public static void acceptedJunction(ServerPlayer player,RoadPlan live,RoadPlan neighbor) {
        if(neighbor==null || !RoadCrossingConsent.canPlan(player.serverLevel(),player,live,neighbor))return;
        var level=player.serverLevel();
        RoadPlan plan=RoadPlan.load(live.save());plan.planning().planner(player.getUUID());
        var selected=new RoadPlanningState.Crossing(false,0,RoadJunctions.nextVersion(level),null);
        plan.planning().spanFloor(neighbor.id(),null);
        var rect=RoadCrossingGeometry.body(plan).intersect(RoadCrossingGeometry.body(neighbor));
        if(rect!=null) {
            var existing=RoadJunctions.find(level,plan.id(),neighbor.id());
            RoadJunctions.put(level,new RoadJunctions.Junction(plan.id(),neighbor.id(),rect,selected,
                    existing==null ? null:existing.grant(),built(plan),built(neighbor),existing==null ? 1:existing.rev()+1));
        }
        RoadCrossingConsent.ensureEditable(level,player,neighbor,plan);
        RoadPlan peer=null;
        try {peer=rebuildPeer(player,plan,neighbor,false);}
        catch(IllegalArgumentException ex){plan.planning().issue(RoadNotices.reasonKey(ex));}
        // Approval records formation intent even when further manual balancing is necessary.
        var metadata=peer==null ? RoadPlan.load(neighbor.save()):peer;
        RoadLockReconcile.apply(level,plan);RoadLockReconcile.apply(level,metadata);
        RoadManager.get(level).add(plan);RoadManager.get(level).add(metadata);
        RoadJunctions.recomputeRoad(level,plan.id());
        if(peer!=null)RoadCrossingUpdates.schedule(level,peer);
        refreshAfterConsent(player,plan);
        RoadNotices.chat(player,plan.id(),"same_level","rp_crossroads.consent.same_level");
    }
    public static void refreshAfterConsent(ServerPlayer player,RoadPlan live) {
        var level=player.serverLevel();if(!RoadRules.canEdit(live))return;
        RoadPlan plan=RoadPlan.load(live.save());plan.planning().planner(player.getUUID());
        try {RoadAutoPlanning.restoreBase(level,plan);}
        catch(IllegalArgumentException ex){plan.planning().issue(RoadNotices.reasonKey(ex));}
        var image=RoadBlueprintGenerator.generate(plan,level,Config.defaultLampReserveHeight(),player);
        // Approval refresh only rewrites the canonical snapshot; the pack copy is intentionally left alone.
        if(image!=null)RoadProposal.publish(level,plan,plan,image,false,RoadProposal.Audience.ALL,player,null);
    }
    private static RoadPlan rebuildPeer(ServerPlayer player,RoadPlan plan,RoadPlan peer,boolean override) {
        var level=player.serverLevel();
        if(!RoadCrossingConsent.canPlan(level,player,plan,peer))return null;
        if(RoadRules.busy(peer,RoadRules.MUTATION))return null;
        if(override && !RoadRules.canEdit(peer))return null;
        var next=RoadPlan.load(peer.save());next.planning().spanFloor(plan.id(),null);
        if(override)clearManualInSpan(next,plan);
        RoadPlan base=RoadAutoPlanning.baseSource(next);base.pruneOutOfRangeRows();next.adoptPendingRows(java.util.Map.of());next.adoptCommittedRows(base.rowOverrides());
        try(var scope=RoadCrossings.scope(java.util.Map.of(plan.id(),plan,peer.id(),next),java.util.Map.of())) {
            RoadAutoPlanning.restoreBase(level,plan);plan.pruneOutOfRangeRows();
            if(!RoadCrossings.setting(level,plan,peer).layered())RoadJointWork.alignUnbuilt(level,plan,next,RoadCrossings.setting(level,plan,peer).platform());
        }
        if(next.state()!=RoadState.DRAFT)next.setState(RoadState.PLANNED);
        return next;
    }
    private static void adjustPlatform(ServerLevel level,ServerPlayer player,RoadPlan plan,RoadPlanningPayload request) {
        var peer=RoadManager.get(level).get(request.other());
        if(peer!=null && RoadCrossings.setting(level,plan,peer).layered()) {adjustLayered(level,player,plan,peer,request.heightDelta());return;}
        if(Math.abs(request.heightDelta())!=1 || peer==null || peer.id().equals(plan.id()) || peer.segments().isEmpty()
                || RoadRules.busy(peer,RoadRules.MUTATION) || !RoadCrossingConsent.canPlan(level,player,plan,peer))
            throw new IllegalArgumentException("Both roads must be editable and authorized to change their shared platform.");
        // Authorization opens the neighbour switch, so the platform stays editable even for two built roads.
        RoadCrossingConsent.ensureEditable(level,player,peer,plan);
        if(!RoadRules.canChangePlatform(plan,peer))
            throw new IllegalArgumentException("Both roads must be editable to change their shared platform.");
        var rule=RoadCrossings.setting(level,plan,peer);var intersection=RoadCrossingGeometry.walk(plan).intersect(RoadCrossingGeometry.walk(peer));
        if(rule.layered() || intersection==null || plan.axis()==peer.axis())throw new IllegalArgumentException("A shared platform requires a same-level junction.");
        RoadPlan autoA=RoadAutoPlanning.baseSource(plan),autoB=RoadAutoPlanning.baseSource(peer);
        autoA.pruneOutOfRangeRows();autoB.pruneOutOfRangeRows();
        RoadJointWork.alignUnbuilt(level,autoA,autoB,null);
        int automatic=RoadCrossingGeometry.platform(autoA,intersection);
        int requested=Math.addExact(rule.platform()==null ? automatic : rule.platform(),request.heightDelta());
        String key=heightKey(player.getUUID(),plan.id(),peer.id());
        String boundary=RoadCrossingConsent.pairDigest(level,plan,peer);
        PendingHeight waiting=HEIGHTS.get(key);
        boolean valid=waiting!=null && waiting.expires()>System.currentTimeMillis() && waiting.boundary().equals(boundary);
        // The other height button means "do not override": drop the offer, then treat this press as a new request.
        if(valid && waiting.delta()!=request.heightDelta()) {HEIGHTS.remove(key);valid=false;}
        boolean confirmed=valid;
        if(confirmed)HEIGHTS.remove(key);
        RoadPlan a=RoadPlan.load(autoA.geometrySnapshot()),b=RoadPlan.load(autoB.geometrySnapshot());boolean fallback=false;
        try {RoadJointWork.alignUnbuilt(level,a,b,requested);HEIGHTS.remove(key);}
        catch(IllegalArgumentException ex) {
            if(ex.getMessage()!=null && ex.getMessage().contains("budget"))throw ex;
            var retry=overrideHeight(level,plan,peer,requested);
            if(confirmed && retry!=null) {
                a=retry[0];b=retry[1];
                RoadNotices.send(player,plan.id(),"height_applied",Component.translatable("rp_crossroads.planning.manual_overridden",manualRowsInSpan(plan,peer)));
            } else if(!confirmed && retry!=null) {
                int rows=manualRowsInSpan(plan,peer)+(RoadRules.canEdit(peer) ? manualRowsInSpan(peer,plan) : 0);
                if(rows>0) {
                    HEIGHTS.put(key,new PendingHeight(player.getUUID(),plan.id(),peer.id(),request.heightDelta(),boundary,System.currentTimeMillis()+OFFER_TTL));
                    RoadNotices.send(player,plan.id(),"height:"+boundary+":"+request.heightDelta(),Component.translatable("rp_crossroads.planning.manual_conflict",rows));
                    return;
                }
                a=autoA;b=autoB;requested=automatic;fallback=true;
            } else {
                a=autoA;b=autoB;requested=automatic;fallback=true;
            }
        }
        var selected=new RoadPlanningState.Crossing(false,0,RoadJunctions.nextVersion(level),requested);
        stageRelation(level,plan,peer,selected);
        copyProposalRows(plan,a);copyProposalRows(peer,b);
        if(plan.state()!=RoadState.DRAFT)plan.setState(RoadState.PLANNED);
        if(peer.state()!=RoadState.DRAFT)peer.setState(RoadState.PLANNED);
        try(var scope=RoadCrossings.scope(java.util.Map.of(plan.id(),plan,peer.id(),peer),java.util.Map.of())) {
            var first=RoadBlueprintGenerator.generate(plan,level,Config.defaultLampReserveHeight(),player);
            var second=RoadBlueprintGenerator.generate(peer,level,Config.defaultLampReserveHeight(),player);
            if(first==null || second==null
                    || !RoadProposal.save(level,plan,first,RoadProposal.Save.CHECKED)
                    || !RoadProposal.save(level,peer,second,RoadProposal.Save.CHECKED))return;
            if(!RoadProposal.pack(level,plan,plan,first) || !RoadProposal.pack(level,peer,peer,second))return;
        }
        RoadLockReconcile.apply(level,plan);RoadLockReconcile.apply(level,peer);
        RoadProposal.index(level,plan,RoadProposal.Audience.NONE,player,null);
        RoadProposal.index(level,peer,RoadProposal.Audience.NONE,player,null);
        RoadProposal.sync(level,RoadProposal.Audience.ALL,player,null);
        if(fallback)RoadNotices.chat(player,plan.id(),"height_fallback","rp_crossroads.planning.height_fallback",requested);
        else RoadNotices.actionBar(player,"rp_crossroads.planning.height_updated",requested);
    }
    private static void adjustLayered(ServerLevel level,ServerPlayer player,RoadPlan plan,RoadPlan peer,int delta) {
        if(Math.abs(delta)!=1 || RoadRules.busy(peer,Set.of(RoadRules.Busy.SESSION,RoadRules.Busy.REMOVING)))
            throw new IllegalArgumentException("The crossing height cannot be edited now.");
        var other=RoadCrossings.reference(level,peer);var span=RoadCrossingGeometry.body(plan).intersect(RoadCrossingGeometry.body(other));
        if(span==null)throw new IllegalArgumentException("The roads no longer overlap.");
        int index=Math.min(RoadAutoPlanning.index(plan,span.first(plan)),RoadAutoPlanning.index(plan,span.last(plan)));
        int current=RoadHeightProfile.of(plan).row(RoadAutoPlanning.along(plan,index)).baseY();
        plan.planning().spanFloor(peer.id(),Math.addExact(current,delta));boolean fallback=false;
        try {RoadAutoPlanning.restoreBase(level,plan);for(var n:RoadCrossings.neighbors(level,plan))RoadCrossings.validate(plan,n);}
        catch(IllegalArgumentException ex) {plan.planning().spanFloor(peer.id(),null);RoadAutoPlanning.restoreBase(level,plan);fallback=true;}
        if(plan.state()!=RoadState.DRAFT)plan.setState(RoadState.PLANNED);
        var image=RoadBlueprintGenerator.generate(plan,level,Config.defaultLampReserveHeight(),player);
        if(image==null || !RoadProposal.publish(level,plan,plan,image,true,RoadProposal.Audience.ALL,player,null))return;
        int chosen=RoadHeightProfile.of(plan).row(RoadAutoPlanning.along(plan,index)).baseY();
        if(fallback)RoadNotices.chat(player,plan.id(),"height_fallback","rp_crossroads.planning.height_fallback",chosen);
        else RoadNotices.actionBar(player,"rp_crossroads.planning.span_updated",chosen);
    }
    /** Solver output writes automatic rows only; a manual or locked row keeps its own value. */
    private static void copyProposalRows(RoadPlan live,RoadPlan desired) {
        for(int row=live.minAlong();row<=live.maxAlong();row++)live.setAutoType(row,desired.cellType(row));
    }
    /** Hand-edited rows inside the span the solver is allowed to bend. */
    private static int manualRowsInSpan(RoadPlan road,RoadPlan other) {
        var walk=RoadCrossingGeometry.walk(road).intersect(RoadCrossingGeometry.walk(other));
        if(walk==null)return 0;
        int[] span=RoadLockReconcile.span(road,walk.first(road),walk.last(road));int count=0;
        for(int row:road.manualRows().keySet())if(row>=span[0] && row<=span[1])count++;
        return count;
    }
    /** Height retry with the hand rows inside the bend span cleared; null when still impossible. */
    private static RoadPlan[] overrideHeight(ServerLevel level,RoadPlan plan,RoadPlan peer,int requested) {
        try {
            var a=RoadAutoPlanning.baseSource(plan);var b=RoadAutoPlanning.baseSource(peer);
            a.pruneOutOfRangeRows();b.pruneOutOfRangeRows();
            clearManualInSpan(a,peer);
            if(RoadRules.canEdit(peer))clearManualInSpan(b,plan);
            RoadJointWork.alignUnbuilt(level,a,b,requested);
            return new RoadPlan[]{a,b};
        } catch(IllegalArgumentException ex) {return null;}
    }
    private static boolean overrideSucceeds(ServerPlayer player,RoadPlan source,RoadPlan other,int mode) {
        try {modeAttempt(player,source,other,mode,true);return true;}
        catch(IllegalArgumentException ex) {return false;}
    }
    private static void clearManualInSpan(RoadPlan road,RoadPlan other) {
        var walk=RoadCrossingGeometry.walk(road).intersect(RoadCrossingGeometry.walk(other));
        if(walk==null)return;
        int[] span=RoadLockReconcile.span(road,walk.first(road),walk.last(road));
        var kept=new java.util.TreeMap<>(road.manualRows());
        kept.keySet().removeIf(row -> row>=span[0] && row<=span[1]);
        new java.util.ArrayList<>(road.manualRows().keySet()).forEach(road::clearManual);
        kept.forEach((row,type) -> road.editRow(row,type));
    }
    /** Restores hand edits that the solved result happened to keep, so only real changes are dropped. */
    private static int restoreUnchangedManual(RoadPlan before,RoadPlan after,RoadPlan other) {
        if(before==null || after==null)return 0;
        var walk=RoadCrossingGeometry.walk(before).intersect(RoadCrossingGeometry.walk(other));
        if(walk==null)return 0;
        int[] span=RoadLockReconcile.span(before,walk.first(before),walk.last(before));int restored=0;
        for(var entry:before.manualRows().entrySet()) {
            int row=entry.getKey();if(row<span[0] || row>span[1])continue;
            try {if(after.cellType(row)==entry.getValue()){after.editRow(row,entry.getValue());restored++;}}
            catch(RuntimeException ignored) { }
        }
        return restored;
    }
    private RoadPlanningActions() { }
}
