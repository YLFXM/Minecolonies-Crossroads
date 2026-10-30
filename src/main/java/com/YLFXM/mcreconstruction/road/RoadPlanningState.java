/* SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 ZhuanZ (YLFXM) */
package com.YLFXM.mcreconstruction.road;

import java.util.*;
import net.minecraft.nbt.*;

/** Proposal intent, separate from committed rows and actual applied geometry. */
public final class RoadPlanningState {
    public record Crossing(boolean layered,int order,long version,Integer platform) {
        public Crossing(boolean layered,int order) { this(layered,order,0,null); }
        public Crossing(boolean layered,int order,long version) {this(layered,order,version,null);}
        public Crossing { if(order < -1 || order > 1) throw new IllegalArgumentException("Invalid crossing order"); }
    }
    private String sampleKey="";
    private UUID planner;
    private final Map<UUID,Integer> policies=new TreeMap<>();
    private final Set<UUID> localPairs=new HashSet<>();
    public boolean local(UUID peer) {return localPairs.contains(peer);}
    public void local(UUID peer,boolean local) {if(local)localPairs.add(peer);else localPairs.remove(peer);revision++;}
    private final Map<UUID,Integer> access=new TreeMap<>(); // Display only; never an authority proof.
    public UUID planner() {return planner;}
    public void planner(UUID id) {planner=id;}
    public int policy(UUID peer) {return policies.getOrDefault(peer,0);}
    public void policy(UUID peer,int value) {if(value<0 || value>2)throw new IllegalArgumentException("Invalid crossing strategy");policies.put(peer,value);revision++;}
    public int access(UUID peer) {return access.getOrDefault(peer,0);}
    private String relationshipKey="";
    public String relationshipKey() {return relationshipKey;}
    public void relationshipKey(String value) {if(!relationshipKey.equals(value)){relationshipKey=value;revision++;}}
    private int[] terrain=new int[0];
    private final Map<Integer,CellType> manual=new TreeMap<>();
    private final Map<Integer,CellType> baseRows=new TreeMap<>();
    private boolean hasBase;
    public boolean hasBase() {return hasBase;}
    public Map<Integer,CellType> baseRows() {return Collections.unmodifiableMap(baseRows);}
    public void baseRows(Map<Integer,CellType> rows) {baseRows.clear();rows.forEach((i,t) -> {if(t!=CellType.FLAT)baseRows.put(i,t);});hasBase=true;revision++;}
    private final Map<UUID,Crossing> crossings=new TreeMap<>();
    private final Map<UUID,Integer> spanFloors=new TreeMap<>();
    /** Rows the automatic planner may never move: anchor rows and same-level junction platforms. */
    public record Lock(boolean anchor,int height) { }
    private final Map<Integer,Lock> locks=new TreeMap<>();
    public Map<Integer,Lock> locks() {return Collections.unmodifiableMap(locks);}
    public void locks(Map<Integer,Lock> rows) {if(locks.equals(rows))return;locks.clear();locks.putAll(rows);revision++;}
    public boolean locked(int along) {return locks.containsKey(along);}
    /** Per-row lamp outcome recorded at generation: true = lamp placed, false = suppressed by a junction. */
    private final Map<Integer,Boolean> lamps=new TreeMap<>();
    public Map<Integer,Boolean> lamps() {return Collections.unmodifiableMap(lamps);}
    public void lamps(Map<Integer,Boolean> rows) {if(lamps.equals(rows))return;lamps.clear();lamps.putAll(rows);revision++;}
    /** Pair-scoped permission to change a junction: written to both roads when a request is approved. */
    public record Grant(UUID token,UUID issuer,long issuedAt,String boundary) { }
    private final Map<UUID,Grant> grants=new TreeMap<>();
    public Map<UUID,Grant> grants() {return Collections.unmodifiableMap(grants);}
    public void grant(UUID peer,Grant value) {grants.put(peer,value);revision++;}
    public void clearGrant(UUID peer) {if(grants.remove(peer)!=null)revision++;}
    public void clearGrants() {if(!grants.isEmpty()){grants.clear();revision++;}}
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
    public Map<Integer,CellType> manual() { return Collections.unmodifiableMap(manual); }
    public Map<UUID,Crossing> crossings() { return Collections.unmodifiableMap(crossings); }
    public int revision() { return revision; }
    public void sample(String key,int[] values) { sampleKey=key; terrain=values.clone(); revision++; }
    public void manual(int along,CellType type) { manual.put(along,type); revision++; }
    public void clearManual() { manual.clear(); revision++; }
    public void clearRows() { sampleKey=""; terrain=new int[0]; manual.clear();baseRows.clear();hasBase=false;locks.clear();grants.clear();lamps.clear(); revision++; }
    public void prune(int min,int max) { if(manual.keySet().removeIf(i -> i<min || i>max)) revision++; }
    public void crossing(UUID other,Crossing value) { crossings.put(other,value); revision++; }
    public void remove(UUID other) { if(crossings.remove(other)!=null) revision++;spanFloors.remove(other);policies.remove(other);access.remove(other);localPairs.remove(other); }
    public CompoundTag save() {
        CompoundTag t=new CompoundTag(); t.putString("sampleKey",sampleKey); t.putString("issue",issue); t.putString("crossingIssue",crossingIssue); t.putIntArray("terrain",terrain); t.putInt("revision",revision);t.putBoolean("refreshNeeded",refreshNeeded);
        if(planner!=null)t.putUUID("planner",planner);
        ListTag local=new ListTag();localPairs.stream().sorted().forEach(id->{var e=new CompoundTag();e.putUUID("road",id);local.add(e);});t.put("localPairs",local);
        ListTag choices=new ListTag();policies.forEach((id,choice)->{var e=new CompoundTag();e.putUUID("road",id);e.putInt("choice",choice);choices.add(e);});t.put("policies",choices);
        ListTag edits=new ListTag(); manual.forEach((i,v) -> { var e=new CompoundTag();e.putInt("along",i);e.putString("type",v.name());edits.add(e); }); t.put("manual",edits);
        t.putBoolean("hasBase",hasBase);ListTag base=new ListTag();baseRows.forEach((i,v) -> {var e=new CompoundTag();e.putInt("along",i);e.putString("type",v.name());base.add(e);});t.put("baseRows",base);
        t.putBoolean("externalChanges",externalChanges);
        t.putString("relationshipKey",relationshipKey);
        ListTag heights=new ListTag();spanFloors.forEach((id,y) -> {var e=new CompoundTag();e.putUUID("road",id);e.putInt("y",y);heights.add(e);});t.put("spanFloors",heights);
        ListTag pairs=new ListTag(); crossings.forEach((id,v) -> { var e=new CompoundTag();e.putUUID("road",id);e.putBoolean("layered",v.layered());e.putInt("order",v.order());e.putLong("version",v.version());if(v.platform()!=null)e.putInt("platform",v.platform());pairs.add(e); }); t.put("pairs",pairs);
        ListTag lockRows=new ListTag(); locks.forEach((i,v) -> {var e=new CompoundTag();e.putInt("along",i);e.putBoolean("anchor",v.anchor());e.putInt("y",v.height());lockRows.add(e);});t.put("locks",lockRows);
        ListTag lampRows=new ListTag(); lamps.forEach((i,v) -> {var e=new CompoundTag();e.putInt("along",i);e.putBoolean("lamp",v);lampRows.add(e);});t.put("lamps",lampRows);
        ListTag grantsTag=new ListTag(); grants.forEach((id,v) -> {var e=new CompoundTag();e.putUUID("road",id);e.putUUID("token",v.token());e.putUUID("issuer",v.issuer());e.putLong("issued",v.issuedAt());e.putString("boundary",v.boundary());grantsTag.add(e);});t.put("grants",grantsTag);
        return t;
    }
    public static RoadPlanningState load(CompoundTag t) {
        RoadPlanningState s=new RoadPlanningState();s.sampleKey=t.getString("sampleKey");s.issue=t.getString("issue");s.crossingIssue=t.getString("crossingIssue");s.terrain=t.getIntArray("terrain");s.revision=t.getInt("revision");s.refreshNeeded=t.getBoolean("refreshNeeded");
        if(t.hasUUID("planner"))s.planner=t.getUUID("planner");
        var local=t.getList("localPairs",10);for(int i=0;i<local.size();i++)s.localPairs.add(local.getCompound(i).getUUID("road"));
        var choices=t.getList("policies",10);for(int i=0;i<choices.size();i++){var e=choices.getCompound(i);int choice=e.getInt("choice");if(choice>=0 && choice<=2)s.policies.put(e.getUUID("road"),choice);}
        var visible=t.getList("access",10);for(int i=0;i<visible.size();i++){var e=visible.getCompound(i);s.access.put(e.getUUID("road"),e.getInt("status"));}
        if(s.terrain.length>2048) throw new IllegalArgumentException("Oversized terrain profile");
        ListTag edits=t.getList("manual",10);for(int i=0;i<edits.size();i++) {var e=edits.getCompound(i);s.manual.put(e.getInt("along"),CellType.valueOf(e.getString("type")));}
        s.hasBase=t.getBoolean("hasBase");ListTag base=t.getList("baseRows",10);for(int i=0;i<base.size();i++){var e=base.getCompound(i);s.baseRows.put(e.getInt("along"),CellType.valueOf(e.getString("type")));}
        s.externalChanges=t.getBoolean("externalChanges");
        s.relationshipKey=t.getString("relationshipKey");
        ListTag heights=t.getList("spanFloors",10);for(int i=0;i<heights.size();i++){var e=heights.getCompound(i);s.spanFloors.put(e.getUUID("road"),e.getInt("y"));}
        ListTag pairs=t.getList("pairs",10);for(int i=0;i<pairs.size();i++) {var e=pairs.getCompound(i);s.crossings.put(e.getUUID("road"),new Crossing(e.getBoolean("layered"),e.getInt("order"),e.getLong("version"),e.contains("platform",3) ? e.getInt("platform") : null));}
        ListTag lockRows=t.getList("locks",10);for(int i=0;i<lockRows.size();i++){var e=lockRows.getCompound(i);s.locks.put(e.getInt("along"),new Lock(e.getBoolean("anchor"),e.getInt("y")));}
        ListTag lampRows=t.getList("lamps",10);for(int i=0;i<lampRows.size();i++){var e=lampRows.getCompound(i);s.lamps.put(e.getInt("along"),e.getBoolean("lamp"));}
        ListTag grantsTag=t.getList("grants",10);for(int i=0;i<grantsTag.size();i++){var e=grantsTag.getCompound(i);s.grants.put(e.getUUID("road"),new Grant(e.getUUID("token"),e.getUUID("issuer"),e.getLong("issued"),e.getString("boundary")));}
        return s;
    }
}
