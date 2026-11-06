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
import net.minecraft.nbt.*;

/** Proposal intent, separate from committed rows and actual applied geometry. */
public final class RoadPlanningState {
    public record Crossing(boolean layered,int order,long version,Integer platform) {
        public Crossing(boolean layered,int order) { this(layered,order,0,null); }
        public Crossing(boolean layered,int order,long version) {this(layered,order,version,null);}
        public Crossing { if(order < -1 || order > 1) throw new IllegalArgumentException("Invalid crossing order"); }
    }
    /** Pair-scoped authorization; stored on the per-dimension junction record, not here. */
    public record Grant(UUID token,UUID issuer,long issuedAt,String boundary) { }
    private String sampleKey="";
    private UUID planner;
    private final Map<UUID,Integer> access=new TreeMap<>(); // Display only; never an authority proof.
    public UUID planner() {return planner;}
    public void planner(UUID id) {planner=id;}
    public int access(UUID peer) {return access.getOrDefault(peer,0);}
    private String relationshipKey="";
    public String relationshipKey() {return relationshipKey;}
    public void relationshipKey(String value) {if(!relationshipKey.equals(value)){relationshipKey=value;revision++;}}
    private int[] terrain=new int[0];
    /** Whole-plan flag: a junction-free baseline was solved at least once, even if it is empty. */
    private boolean hasBase;
    public boolean hasBase() {return hasBase;}
    public void hasBase(boolean value) {if(hasBase!=value){hasBase=value;revision++;}}
    private final Map<UUID,Integer> spanFloors=new TreeMap<>();
    public Integer spanFloor(UUID road) {return spanFloors.get(road);}
    public void spanFloor(UUID road,Integer value) {if(value==null)spanFloors.remove(road);else spanFloors.put(road,value);revision++;}
    private int revision;
    private boolean refreshNeeded;
    private boolean externalChanges;
    public boolean externalChanges() {return externalChanges;}
    public void externalChanges(boolean value) {if(externalChanges!=value){externalChanges=value;revision++;}}
    public boolean refreshNeeded() { return refreshNeeded; }
    public void refreshNeeded(boolean value) {if(refreshNeeded!=value){refreshNeeded=value;revision++;}}
    private String issue="";
    private String crossingIssue="";
    public void crossingIssue(String value) { if(!crossingIssue.equals(value)) {crossingIssue=value;revision++;} }
    public String crossingIssue() {return crossingIssue;}
    public String issue() { return issue+(issue.isEmpty() || crossingIssue.isEmpty() ? "" : " ")+crossingIssue; }
    public void issue(String value) { if(!issue.equals(value)) { issue=value; revision++; } }
    public String sampleKey() { return sampleKey; }
    public int[] terrain() { return terrain.clone(); }
    public int revision() { return revision; }
    public void sample(String key,int[] values) { sampleKey=key; terrain=values.clone(); revision++; }
    /** Clears the terrain sample only: row state lives on the plan, not here. */
    public void clearRows() { sampleKey=""; terrain=new int[0]; revision++; }
    public void remove(UUID other) { spanFloors.remove(other);access.remove(other);revision++; }
    public CompoundTag save() {
        CompoundTag t=new CompoundTag(); t.putString("sampleKey",sampleKey); t.putString("issue",issue); t.putString("crossingIssue",crossingIssue); t.putIntArray("terrain",terrain); t.putInt("revision",revision);t.putBoolean("refreshNeeded",refreshNeeded);
        if(planner!=null)t.putUUID("planner",planner);
        t.putBoolean("externalChanges",externalChanges);
        t.putString("relationshipKey",relationshipKey);
        t.putBoolean("hasBase",hasBase);
        ListTag heights=new ListTag();spanFloors.forEach((id,y) -> {var e=new CompoundTag();e.putUUID("road",id);e.putInt("y",y);heights.add(e);});t.put("spanFloors",heights);
        return t;
    }
    public static RoadPlanningState load(CompoundTag t) {
        RoadPlanningState s=new RoadPlanningState();s.sampleKey=t.getString("sampleKey");s.issue=t.getString("issue");s.crossingIssue=t.getString("crossingIssue");s.terrain=t.getIntArray("terrain");s.revision=t.getInt("revision");s.refreshNeeded=t.getBoolean("refreshNeeded");
        if(t.hasUUID("planner"))s.planner=t.getUUID("planner");
        // Legacy "policies" entries are ignored: request state lives in REQUESTS, long-term proof in grants.
        var visible=t.getList("access",10);for(int i=0;i<visible.size();i++){var e=visible.getCompound(i);s.access.put(e.getUUID("road"),e.getInt("status"));}
        if(s.terrain.length>2048) throw new IllegalArgumentException("Oversized terrain profile");
        s.externalChanges=t.getBoolean("externalChanges");
        s.relationshipKey=t.getString("relationshipKey");
        s.hasBase=t.getBoolean("hasBase");
        ListTag heights=t.getList("spanFloors",10);for(int i=0;i<heights.size();i++){var e=heights.getCompound(i);s.spanFloors.put(e.getUUID("road"),e.getInt("y"));}
        return s;
    }
}
