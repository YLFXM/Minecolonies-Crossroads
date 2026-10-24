/*
 * Copyright (C) 2026 ZhuanZ (YLFXM)
 *
 * MineColonies: Reconstruction Plan - a feature expansion addon for MineColonies.
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
package com.YLFXM.mcreconstruction.road;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/** One frozen repair operation, not a permanent fact about the road. */
public final class RoadRepairSession {
    public enum Phase { PREPARED, NATIVE, SURVIVAL }
    private final UUID token;
    private final UUID owner;
    private final RoadState previousState;
    private final CompoundTag target;
    private Phase phase;
    private long changedAt;
    private boolean orderObserved;
    private boolean nativeDone;
    private java.util.List<net.minecraft.core.BlockPos> clearTargets=java.util.List.of();
    public java.util.List<net.minecraft.core.BlockPos> clearTargets() { return clearTargets; }
    public void setClearTargets(final java.util.Collection<net.minecraft.core.BlockPos> targets) {
        if(phase!=Phase.PREPARED) throw new IllegalStateException("Cannot change a confirmed clearing target");
        clearTargets=targets.stream().map(net.minecraft.core.BlockPos::immutable).distinct().toList();
    }

    private RoadRepairSession(UUID token, UUID owner, RoadState previousState, CompoundTag target,
                              Phase phase, long changedAt, boolean orderObserved) {
        this.token=token; this.owner=owner; this.previousState=previousState;
        this.target=target.copy(); this.phase=phase; this.changedAt=changedAt; this.orderObserved=orderObserved;
    }
    public static RoadRepairSession prepare(final RoadPlan plan, final UUID player, final long now) {
        return prepare(plan,player,now,plan.state());
    }
    public static RoadRepairSession prepareInitial(final RoadPlan plan, final UUID player, final long now) {
        return prepare(plan,player,now,RoadState.DRAFT);
    }
    private static RoadRepairSession prepare(final RoadPlan plan, final UUID player, final long now,
                                             final RoadState previousState) {
        final RoadPlan frozen = RoadPlan.load(plan.save());
        frozen.setRepairSession(null);
        frozen.applyPendingRows();
        return new RoadRepairSession(UUID.randomUUID(),player,previousState,frozen.save(),Phase.PREPARED,now,false);
    }
    public UUID token() { return token; }
    public UUID owner() { return owner; }
    public RoadState previousState() { return previousState; }
    public Phase phase() { return phase; }
    public long changedAt() { return changedAt; }
    public boolean orderObserved() { return orderObserved; }
    public void observeOrder() { orderObserved=true; }
    public boolean nativeDone() { return nativeDone; }
    public void finishNative() { nativeDone=true; }
    public RoadPlan target() { return RoadPlan.load(target.copy()); }
    public boolean start(final Phase next, final long now) {
        if (phase != Phase.PREPARED || next == Phase.PREPARED) return false;
        phase=next; changedAt=now; return true;
    }
    public boolean ownedBy(final UUID player, final UUID suppliedToken) {
        return owner.equals(player) && token.equals(suppliedToken);
    }
    public void commitRows(final RoadPlan plan) {
        if (phase==Phase.PREPARED) throw new IllegalStateException("Unconfirmed repair cannot commit rows");
        plan.adoptCommittedRows(target().rowOverrides());
        plan.adoptPendingRows(java.util.Map.of());
        plan.setLastDroppedRows(0);
    }
    public CompoundTag save() {
        final CompoundTag tag=new CompoundTag();
        tag.putUUID("token",token); tag.putUUID("owner",owner); tag.putString("previous",previousState.name());
        tag.put("target",target.copy()); tag.putString("phase",phase.name()); tag.putLong("changedAt",changedAt);
        tag.putBoolean("orderObserved",orderObserved);
        if(!clearTargets.isEmpty()) {
            final var list=new net.minecraft.nbt.ListTag();
            for(final var pos:clearTargets) {
                final var entry=new CompoundTag(); entry.put("pos",net.minecraft.nbt.NbtUtils.writeBlockPos(pos)); list.add(entry);
            }
            tag.put("clearTargets",list);
        }
        return tag;
    }
    public static RoadRepairSession load(final CompoundTag tag) {
        final var session=new RoadRepairSession(tag.getUUID("token"),tag.getUUID("owner"),RoadState.valueOf(tag.getString("previous")),
                tag.getCompound("target"),Phase.valueOf(tag.getString("phase")),tag.getLong("changedAt"),tag.getBoolean("orderObserved"));
        final var positions=new java.util.ArrayList<net.minecraft.core.BlockPos>();
        final var list=tag.getList("clearTargets",10);
        for(int i=0;i<list.size();i++) {
            net.minecraft.nbt.NbtUtils.readBlockPos(list.getCompound(i),"pos").ifPresent(positions::add);
        }
        session.clearTargets=java.util.List.copyOf(positions); return session;
    }
}
