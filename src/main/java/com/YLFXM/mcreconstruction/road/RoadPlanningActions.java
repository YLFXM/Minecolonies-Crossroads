/* SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 ZhuanZ (YLFXM) */
package com.YLFXM.mcreconstruction.road;

import com.YLFXM.mcreconstruction.*;
import com.YLFXM.mcreconstruction.network.*;
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
                || RoadCrossings.inUse(level,old) || !RoadPermissions.canManage(level,player,old)
                || RoadWandBinding.findBound(player.getInventory(),request.controller())==null) return;
        if(request.action()<0 || request.action()>7 || request.mode()<0 || request.mode()>2) return;
        if(request.action()==4) {
            if(old.state()!=RoadState.DRAFT)return;
            old.clearSegments();old.setEndpoints(null,null);old.clearRowOverrides();RoadManager.get(level).add(old);level.players().forEach(Network::sendRoads);return;
        }
        try {
            RoadBlueprintStore.preserveBuilt(level,old);
            RoadReplanning.validate(old,request.a(),request.b());
            var ends=RoadReplanning.orient(old,request.a(),request.b());
            var plan=RoadPlanBuilder.build(level,old.anchorPos(),old.controllerPos(),old.controllerFacing(),ends[0],ends[1],
                    old.pack(),old.variant(),old.width(),old.walkRange().width(),request.mirror() ? -1 : 1,old.lampSpacing()).plan();
            RoadReplanning.inherit(old,plan,player.getUUID(),true);plan.setMirror(request.mirror());
            plan.planning().planner(player.getUUID());
            RoadPlan peerUpdate=null,relativeUpdate=null;
            if(!old.segments().isEmpty() && old.axis()!=plan.axis()) plan.clearRowOverrides();
            if(request.action()==5 || request.action()==6) {
                var peer=RoadManager.get(level).get(request.other());
                if(peer==null || peer.id().equals(plan.id()) || peer.segments().isEmpty() || RoadPermissions.canManage(level,player,peer)
                        || RoadCrossingGeometry.body(plan).intersect(RoadCrossingGeometry.body(peer))==null)return;
                plan.planning().policy(peer.id(),request.action()==5 ? 1:2);
                if(request.action()==6) {
                    if(peer.isRepairBusy() || peer.hasInterruptedConstruction() || peer.state()==RoadState.BUILDING || peer.state()==RoadState.REMOVING || RoadCrossings.inUse(level,peer))
                        throw new IllegalArgumentException("The other road has an active operation.");
                    var rule=RoadCrossings.setting(plan,peer);int order=rule.layered() ? rule.order():1;
                    plan.planning().crossing(peer.id(),new RoadPlanningState.Crossing(true,order,nextPairVersion(level)));
                    plan.planning().local(peer.id(),true);
                    plan.planning().spanFloor(peer.id(),null);
                    syncRelativeRule(plan,peer);RoadManager.get(level).add(peer);
                }
                RoadLockReconcile.apply(level,plan);
                RoadManager.get(level).add(plan);
                if(request.action()==5)RoadCrossingConsent.request(level,player,plan,peer);
                refreshAfterConsent(player,plan);return;
            }
            if(request.action()==3) {adjustPlatform(player,plan,request);return;}
            if(request.action()==1) plan.planning().clearManual();
            if(request.action()==2 || request.action()==7) {
                var other=RoadManager.get(level).get(request.other());
                if(other==null || other.id().equals(plan.id()) || other.segments().isEmpty()
                        || other.isRepairBusy() || other.hasInterruptedConstruction()
                        || other.state()==RoadState.BUILDING || other.state()==RoadState.REMOVING)
                    throw new IllegalArgumentException("The other road is unavailable or cannot be managed.");
                if(RoadCrossingGeometry.body(plan).intersect(RoadCrossingGeometry.body(other))==null)
                    throw new IllegalArgumentException("These roads do not overlap.");
                // This edits only the managed primary's proposal. Foreign patches cannot publish or run
                // until RoadCrossingConsent.prepare/validate succeeds in RoadRepair, including native confirmation.
                var rule=RoadCrossings.setting(plan,other);int current=!rule.layered() ? 0:rule.order()>0 ? 1:2;
                final RoadPlan source=plan;
                String key=offerKey(player,old,other);
                String boundary=RoadCrossingConsent.pairDigest(level,old,other);
                Offer live=OFFERS.get(key);
                boolean override=request.action()==7 || live!=null && live.mode()==request.mode() && live.boundary().equals(boundary) && live.expires()>System.currentTimeMillis();
                if(override)OFFERS.remove(key);
                ModeChoice<ModeAttempt> selected;
                try {selected=chooseMode(current,request.mode(),mode -> modeAttempt(player,source,other,mode,override));}
                catch(IllegalArgumentException ex) {
                    if(!override) {
                        int rows=manualRowsInSpan(plan,other);
                        if(rows>0 && overrideSucceeds(player,source,other,request.mode())) {
                            OFFERS.put(key,new Offer(boundary,request.mode(),rows,System.currentTimeMillis()+OFFER_TTL));
                            RoadNotices.send(player,old.id(),"manual:"+boundary,Component.translatable("mcreconstruction.planning.manual_conflict",rows));
                            return;
                        }
                    }
                    RoadNotices.send(player,old.id(),"modes:"+ex.getMessage(),Component.translatable("mcreconstruction.planning.modes_unavailable",ex.getMessage()));return;
                }
                plan=selected.value().plan();peerUpdate=selected.value().peer();relativeUpdate=selected.value().relative();
                if(override) {
                    int restored=restoreUnchangedManual(old,plan,other);boolean peerRestored=peerUpdate!=null && restoreUnchangedManual(RoadManager.get(level).get(peerUpdate.id()),peerUpdate,plan)>0;
                    RoadNotices.send(player,old.id(),"manual_applied",Component.translatable("mcreconstruction.planning.manual_overridden",Math.max(1,manualRowsInSpan(old,other)-restored)));
                    MCReconstruction.LOGGER.info("Manual rows overridden on {} for junction with {} (kept {} peer={})",old.id(),other.id(),restored,peerRestored);
                }
                if(selected.fallback())player.displayClientMessage(Component.translatable("mcreconstruction.planning.mode_fallback"),false);
            }
            try(var scope=RoadCrossings.scope(peerUpdate==null ? java.util.Map.of(plan.id(),plan):java.util.Map.of(plan.id(),plan,peerUpdate.id(),peerUpdate),java.util.Map.of())) {
            try {
                if(request.action()==2 && plan.planning().hasBase())RoadAutoPlanning.restoreBase(level,plan);
                else RoadAutoPlanning.initialize(level,plan,true,request.action()==0 || request.action()==1);
            }
            catch(IllegalArgumentException ex) { plan.planning().issue(ex.getMessage()); }
            if(plan.state()!=RoadState.DRAFT)plan.setState(RoadState.PLANNED);
            var blueprint=RoadBlueprintGenerator.generate(plan,level,Config.defaultLampReserveHeight(),player);
            if(blueprint==null || !RoadBlueprintStore.saveChecked(level,plan,blueprint)) return;
            if(peerUpdate!=null) {
                var peerImage=RoadBlueprintGenerator.generate(peerUpdate,level,Config.defaultLampReserveHeight(),player);
                if(peerImage==null || !RoadBlueprintStore.saveChecked(level,peerUpdate,peerImage))return;
            }
            if(RoadTemporaryPack.replace(level,old,plan,blueprint)<0) return;
            RoadLockReconcile.apply(level,plan);if(peerUpdate!=null)RoadLockReconcile.apply(level,peerUpdate);
            if(relativeUpdate!=null)RoadLockReconcile.apply(level,relativeUpdate);
            RoadManager.get(level).add(plan);if(peerUpdate!=null)RoadManager.get(level).add(peerUpdate);
            if(relativeUpdate!=null)RoadManager.get(level).add(relativeUpdate);level.players().forEach(Network::sendRoads);
            RoadNotices.send(player,plan.id(),"planning:"+plan.planning().issue(),Component.translatable(plan.planning().issue().isEmpty()
                    ? "mcreconstruction.planning.updated" : "mcreconstruction.planning.conflict",plan.planning().issue()));
            }
        } catch(IllegalArgumentException ex) { RoadNotices.send(player,request.road(),"conflict:"+ex.getMessage(),Component.translatable("mcreconstruction.planning.conflict",ex.getMessage())); }
    }
    record ModeChoice<T>(int mode,T value,boolean fallback) { }
    static <T> ModeChoice<T> chooseMode(int current,int next,java.util.function.IntFunction<T> attempt) {
        if(current<0 || current>2 || next<0 || next>2 || current==next)throw new IllegalArgumentException("Invalid relation change.");
        String failure="";
        for(int mode:new int[]{next,3-current-next})try {return new ModeChoice<>(mode,attempt.apply(mode),mode!=next);}
        catch(IllegalArgumentException ex) {failure=ex.getMessage();}
        throw new IllegalArgumentException(failure);
    }
    private record ModeAttempt(RoadPlan plan,RoadPlan peer,RoadPlan relative) { }
    private static ModeAttempt modeAttempt(ServerPlayer player,RoadPlan source,RoadPlan other,int mode,boolean override) {
        var level=player.serverLevel();RoadPlan plan=RoadPlan.load(source.save());
        boolean authorized=RoadCrossingConsent.canPlan(level,player,plan,other);
        if(!authorized && mode==0)throw new IllegalArgumentException("Same-level junction changes require the neighbor's approval.");
        int order=mode==1 ? 1:mode==2 ? -1:0;
        plan.planning().spanFloor(other.id(),null);
        plan.planning().crossing(other.id(),new RoadPlanningState.Crossing(mode!=0,order,nextPairVersion(level)));
        plan.planning().local(other.id(),!authorized);
        if(override)clearManualInSpan(plan,other);
        var peer=rebuildPeer(player,plan,other,override);RoadPlan relative=null;
        if(peer==null && mode!=0 && plan.planning().policy(other.id())==2) {relative=RoadPlan.load(other.save());syncRelativeRule(plan,relative);}
        try(var scope=RoadCrossings.scope(peer==null ? java.util.Map.of(plan.id(),plan):java.util.Map.of(plan.id(),plan,peer.id(),peer),java.util.Map.of(),plan.id())) {
            RoadAutoPlanning.restoreBase(level,plan);RoadCrossings.validateGeometry(level,plan);
        }
        return new ModeAttempt(plan,peer,relative);
    }
    public static void acceptedJunction(ServerPlayer player,RoadPlan live,RoadPlan neighbor) {
        if(neighbor==null || live.planning().policy(neighbor.id())!=1 || !RoadCrossingConsent.canPlan(player.serverLevel(),player,live,neighbor))return;
        RoadPlan plan=RoadPlan.load(live.save());plan.planning().planner(player.getUUID());
        var selected=new RoadPlanningState.Crossing(false,0,nextPairVersion(player.serverLevel()),null);
        plan.planning().crossing(neighbor.id(),selected);plan.planning().local(neighbor.id(),false);plan.planning().spanFloor(neighbor.id(),null);
        RoadPlan peer=null;
        try {peer=rebuildPeer(player,plan,neighbor,false);}
        catch(IllegalArgumentException ex){plan.planning().issue(ex.getMessage());}
        // Approval records formation intent even when further manual balancing is necessary.
        var metadata=peer==null ? RoadPlan.load(neighbor.save()):peer;metadata.planning().crossing(plan.id(),selected);
        RoadLockReconcile.apply(player.serverLevel(),plan);RoadLockReconcile.apply(player.serverLevel(),metadata);
        RoadManager.get(player.serverLevel()).add(plan);RoadManager.get(player.serverLevel()).add(metadata);
        refreshAfterConsent(player,plan);
        player.displayClientMessage(Component.translatable("mcreconstruction.consent.same_level"),false);
    }
    public static void refreshAfterConsent(ServerPlayer player,RoadPlan live) {
        var level=player.serverLevel();if(!RoadRules.canEdit(live))return;
        RoadPlan plan=RoadPlan.load(live.save());plan.planning().planner(player.getUUID());
        for(var n:RoadCrossings.neighbors(level,plan))if(plan.planning().policy(n.live().id())==1 && RoadCrossingConsent.canPlan(level,player,plan,n.live()))plan.planning().local(n.live().id(),false);
        try {RoadAutoPlanning.restoreBase(level,plan);}
        catch(IllegalArgumentException ex){plan.planning().issue(ex.getMessage());}
        var image=RoadBlueprintGenerator.generate(plan,level,Config.defaultLampReserveHeight(),player);
        if(image!=null && RoadBlueprintStore.saveChecked(level,plan,image)) {
            RoadManager.get(level).add(plan);level.players().forEach(Network::sendRoads);
        }
    }
    private static RoadPlan rebuildPeer(ServerPlayer player,RoadPlan plan,RoadPlan peer,boolean override) {
        var level=player.serverLevel();if(!RoadCrossingConsent.canPlan(level,player,plan,peer) || !RoadRules.canEdit(peer) || RoadCrossings.inUse(level,peer))return null;
        var rule=RoadCrossings.setting(plan,peer);var next=RoadPlan.load(peer.save());next.planning().spanFloor(plan.id(),null);
        if(override)clearManualInSpan(next,plan);
        next.planning().crossing(plan.id(),new RoadPlanningState.Crossing(rule.layered(),-rule.order(),rule.version(),rule.platform()));
        RoadPlan base=RoadAutoPlanning.baseSource(next);base.applyPendingRows();next.adoptPendingRows(java.util.Map.of());next.adoptCommittedRows(base.rowOverrides());
        try(var scope=RoadCrossings.scope(java.util.Map.of(plan.id(),plan,peer.id(),next),java.util.Map.of())) {
            RoadAutoPlanning.restoreBase(level,plan);plan.applyPendingRows();
            if(!rule.layered())RoadJointWork.alignUnbuilt(level,plan,next,rule.platform());
        }
        if(next.state()!=RoadState.DRAFT)next.setState(RoadState.PLANNED);
        return next;
    }
    /** Relative metadata only. Permission bypass is limited to this inverse layered pair field. */
    static void syncRelativeRule(RoadPlan primary,RoadPlan peer) {
        var rule=primary.planning().crossings().get(peer.id());
        if(rule==null || !rule.layered() || Math.abs(rule.order())!=1 || primary.id().equals(peer.id())
                || primary.axis()==peer.axis() || RoadCrossingGeometry.body(primary).intersect(RoadCrossingGeometry.body(peer))==null)
            throw new IllegalArgumentException("A reciprocal relation requires a real layered crossing.");
        peer.planning().crossing(primary.id(),new RoadPlanningState.Crossing(true,-rule.order(),rule.version(),null));
    }
    private static void adjustPlatform(ServerPlayer player,RoadPlan plan,RoadPlanningPayload request) {
        var level=player.serverLevel();var peer=RoadManager.get(level).get(request.other());
        if(peer!=null && RoadCrossings.setting(plan,peer).layered()) {adjustLayered(player,plan,peer,request.heightDelta());return;}
        if(Math.abs(request.heightDelta())!=1 || peer==null || peer.id().equals(plan.id()) || peer.segments().isEmpty()
                || !RoadRules.canChangePlatform(plan,peer) || RoadCrossings.inUse(level,peer) || !RoadCrossingConsent.canPlan(level,player,plan,peer))
            throw new IllegalArgumentException("Both roads must be editable and authorized to change their shared platform.");
        var rule=RoadCrossings.setting(plan,peer);var intersection=RoadCrossingGeometry.walk(plan).intersect(RoadCrossingGeometry.walk(peer));
        if(rule.layered() || intersection==null || plan.axis()==peer.axis())throw new IllegalArgumentException("A shared platform requires a same-level junction.");
        RoadPlan autoA=RoadAutoPlanning.baseSource(plan),autoB=RoadAutoPlanning.baseSource(peer);
        autoA.applyPendingRows();autoB.applyPendingRows();long version=nextPairVersion(level);
        var automaticRule=new RoadPlanningState.Crossing(false,0,version,null);
        autoA.planning().crossing(peer.id(),automaticRule);autoB.planning().crossing(plan.id(),automaticRule);
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
                RoadNotices.send(player,plan.id(),"height_applied",Component.translatable("mcreconstruction.planning.manual_overridden",manualRowsInSpan(plan,peer)));
            } else if(!confirmed && retry!=null) {
                int rows=manualRowsInSpan(plan,peer)+(RoadRules.canEdit(peer) ? manualRowsInSpan(peer,plan) : 0);
                if(rows>0) {
                    HEIGHTS.put(key,new PendingHeight(player.getUUID(),plan.id(),peer.id(),request.heightDelta(),boundary,System.currentTimeMillis()+OFFER_TTL));
                    RoadNotices.send(player,plan.id(),"height:"+boundary+":"+request.heightDelta(),Component.translatable("mcreconstruction.planning.height_manual_conflict",rows,requested));
                    return;
                }
                a=autoA;b=autoB;requested=automatic;fallback=true;
            } else {
                a=autoA;b=autoB;requested=automatic;fallback=true;
            }
        }
        var selected=new RoadPlanningState.Crossing(false,0,version,requested);
        plan.planning().crossing(peer.id(),selected);RoadPlan peerProposal=RoadPlan.load(peer.save());peerProposal.planning().crossing(plan.id(),selected);
        copyProposalRows(plan,a);copyProposalRows(peerProposal,b);
        if(plan.state()!=RoadState.DRAFT)plan.setState(RoadState.PLANNED);
        if(peerProposal.state()!=RoadState.DRAFT)peerProposal.setState(RoadState.PLANNED);
        try(var scope=RoadCrossings.scope(java.util.Map.of(plan.id(),plan,peer.id(),peerProposal),java.util.Map.of())) {
            var first=RoadBlueprintGenerator.generate(plan,level,Config.defaultLampReserveHeight(),player);
            var second=RoadBlueprintGenerator.generate(peerProposal,level,Config.defaultLampReserveHeight(),player);
            if(first==null || second==null || !RoadBlueprintStore.saveChecked(level,plan,first) || !RoadBlueprintStore.saveChecked(level,peerProposal,second))return;
            if(RoadTemporaryPack.replace(level,plan,plan,first)<0 || RoadTemporaryPack.replace(level,peer,peerProposal,second)<0)return;
        }
        RoadLockReconcile.apply(level,plan);RoadLockReconcile.apply(level,peerProposal);
        RoadManager.get(level).add(plan);RoadManager.get(level).add(peerProposal);level.players().forEach(Network::sendRoads);
        player.displayClientMessage(Component.translatable(fallback ? "mcreconstruction.planning.height_fallback" : "mcreconstruction.planning.height_updated",requested),false);
    }
    private static void adjustLayered(ServerPlayer player,RoadPlan plan,RoadPlan peer,int delta) {
        var level=player.serverLevel();if(Math.abs(delta)!=1 || peer.isRepairBusy() || peer.state()==RoadState.REMOVING)throw new IllegalArgumentException("The crossing height cannot be edited now.");
        var other=RoadCrossings.reference(level,peer);var span=RoadCrossingGeometry.body(plan).intersect(RoadCrossingGeometry.body(other));
        if(span==null)throw new IllegalArgumentException("The roads no longer overlap.");
        int index=Math.min(RoadAutoPlanning.index(plan,span.first(plan)),RoadAutoPlanning.index(plan,span.last(plan)));
        int current=RoadHeightProfile.of(plan).row(RoadAutoPlanning.along(plan,index)).baseY();
        plan.planning().spanFloor(peer.id(),Math.addExact(current,delta));boolean fallback=false;
        try {RoadAutoPlanning.restoreBase(level,plan);for(var n:RoadCrossings.neighbors(level,plan))RoadCrossings.validate(plan,n);}
        catch(IllegalArgumentException ex) {plan.planning().spanFloor(peer.id(),null);RoadAutoPlanning.restoreBase(level,plan);fallback=true;}
        if(plan.state()!=RoadState.DRAFT)plan.setState(RoadState.PLANNED);
        var image=RoadBlueprintGenerator.generate(plan,level,Config.defaultLampReserveHeight(),player);
        if(image==null || !RoadBlueprintStore.saveChecked(level,plan,image) || RoadTemporaryPack.replace(level,plan,plan,image)<0)return;
        RoadManager.get(level).add(plan);level.players().forEach(Network::sendRoads);
        int chosen=RoadHeightProfile.of(plan).row(RoadAutoPlanning.along(plan,index)).baseY();
        player.displayClientMessage(Component.translatable(fallback ? "mcreconstruction.planning.height_fallback" : "mcreconstruction.planning.span_updated",chosen),false);
    }
    private static void copyProposalRows(RoadPlan live,RoadPlan desired) {
        for(int row=live.minAlong();row<=live.maxAlong();row++)live.setRowType(row,desired.cellType(row));
    }
    private static long nextPairVersion(net.minecraft.server.level.ServerLevel level) {
        long latest=System.currentTimeMillis();
        for(var road:RoadManager.get(level).all()) for(var pair:road.planning().crossings().values()) latest=Math.max(latest,pair.version());
        return Math.addExact(latest,1);
    }

    /** Hand-edited rows inside the span the solver is allowed to bend. */
    private static int manualRowsInSpan(RoadPlan road,RoadPlan other) {
        var walk=RoadCrossingGeometry.walk(road).intersect(RoadCrossingGeometry.walk(other));
        if(walk==null)return 0;
        int[] span=RoadLockReconcile.span(road,walk.first(road),walk.last(road));int count=0;
        for(int row:road.planning().manual().keySet())if(row>=span[0] && row<=span[1])count++;
        return count;
    }
    /** Height retry with the hand rows inside the bend span cleared; null when still impossible. */
    private static RoadPlan[] overrideHeight(net.minecraft.server.level.ServerLevel level,RoadPlan plan,RoadPlan peer,int requested) {
        try {
            var a=RoadAutoPlanning.baseSource(plan);var b=RoadAutoPlanning.baseSource(peer);
            a.applyPendingRows();b.applyPendingRows();
            var rule=RoadCrossings.setting(plan,peer);
            a.planning().crossing(peer.id(),rule);b.planning().crossing(plan.id(),new RoadPlanningState.Crossing(rule.layered(),-rule.order(),rule.version(),rule.platform()));
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
        var kept=new java.util.TreeMap<>(road.planning().manual());
        kept.keySet().removeIf(row -> row>=span[0] && row<=span[1]);
        road.planning().clearManual();kept.forEach(road.planning()::manual);
    }
    /** Restores hand edits that the solved result happened to keep, so only real changes are dropped. */
    private static int restoreUnchangedManual(RoadPlan before,RoadPlan after,RoadPlan other) {
        if(before==null || after==null)return 0;
        var walk=RoadCrossingGeometry.walk(before).intersect(RoadCrossingGeometry.walk(other));
        if(walk==null)return 0;
        int[] span=RoadLockReconcile.span(before,walk.first(before),walk.last(before));int restored=0;
        for(var entry:before.planning().manual().entrySet()) {
            int row=entry.getKey();if(row<span[0] || row>span[1])continue;
            try {if(after.cellType(row)==entry.getValue()){after.planning().manual(row,entry.getValue());restored++;}}
            catch(RuntimeException ignored) { }
        }
        return restored;
    }
    private RoadPlanningActions() { }
}
