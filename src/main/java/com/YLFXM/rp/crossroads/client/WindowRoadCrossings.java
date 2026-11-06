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
package com.YLFXM.rp.crossroads.client;

import java.util.*;
import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.*;
import com.ldtteam.blockui.views.ScrollingList;
import com.minecolonies.core.client.gui.AbstractWindowSkeleton;
import com.YLFXM.rp.crossroads.Crossroads;
import com.YLFXM.rp.crossroads.network.*;
import com.YLFXM.rp.crossroads.road.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/** Functional pair settings; visual redesign is intentionally independent of road geometry. */
public final class WindowRoadCrossings extends AbstractWindowSkeleton {
    private final UUID road;
    private final ScrollingList list;
    /** A registered junction record, or a geometric candidate that has not formed a relation yet. */
    private record Entry(RoadPlan road,boolean registered) { }
    private List<Entry> candidates=List.of();
    private RoadPlan own;
    private RoadPlan geometry;
    public WindowRoadCrossings(UUID road) {
        super(ResourceLocation.fromNamespaceAndPath(Crossroads.MODID,"gui/windowroadcrossings.xml"));
        this.road=road;this.list=findPaneOfTypeByID("roads",ScrollingList.class);
        this.registerButton("back",b -> {close();new WindowRoadWand().open();});
        this.registerButton("reset",b -> send(1,new UUID(0,0),0));
        this.list.setDataProvider(new ScrollingList.DataProvider() {
            @Override public int getElementCount() { return candidates.size(); }
            @Override public void updateElement(int index,Pane row) {
                if(own==null || index<0 || index>=candidates.size()) return;
                var entry=candidates.get(index);var other=entry.road();var setting=RoadCrossings.setting(own,other);
                int mode=setting==null || !setting.layered() ? 0 : setting.order()>=0 ? 1 : 2;
                boolean overlap=geometry!=null && !geometry.segments().isEmpty() && !other.segments().isEmpty()
                        && RoadCrossingGeometry.body(geometry).intersect(RoadCrossingGeometry.body(other))!=null;
                // A recorded pair whose current proposal no longer intersects is displayed read-only.
                boolean readOnly=entry.registered() && !overlap;
                Text name=row.findPaneOfTypeByID("name",Text.class);
                name.setText(readOnly ? Component.literal(RoadNames.label(other)).append(Component.translatable("rp_crossroads.gui.crossing_read_only"))
                        : Component.literal(RoadNames.label(other)));
                Button type=row.findPaneOfTypeByID("mode",Button.class),strategy=row.findPaneOfTypeByID("strategy",Button.class);
                type.setText(Component.translatable(mode==0 ? "rp_crossroads.gui.same_level" : mode==1 ? "rp_crossroads.gui.overpass" : "rp_crossroads.gui.underpass"));
                int access=own.planning().access(other.id());
                boolean nativePeer=ClientRoadCache.canManage(other.id()),authorized=nativePeer || access==2,pending=access==1;
                strategy.setText(Component.translatable("rp_crossroads.gui.request_junction").append(pending ? " …":""));
                boolean allowed=RoadRules.canEdit(own) && ClientRoadCache.canManage(own.id()) && !readOnly && !other.isRepairBusy() && other.state()!=RoadState.REMOVING;
                for(Button button:new Button[]{type,strategy}) button.setColors(0x000000,0x9A9A9A,0x000000);
                // The switch itself is always usable; without authorization the cycle skips
                // same-level and only offers strict over/underpass.
                type.setEnabled(allowed);
                strategy.setEnabled(allowed && overlap && !nativePeer && !authorized && !pending);
                type.setHandler(b -> send(2,other.id(),RoadRules.nextRelationMode(mode,authorized)));
                strategy.setHandler(b -> send(5,other.id(),mode));
                Text height=row.findPaneOfTypeByID("height",Text.class);Integer floor=mode==0 ? setting.platform() : null;
                if(mode!=0 && geometry!=null && !geometry.segments().isEmpty()) {
                    var span=RoadCrossingGeometry.body(geometry).intersect(RoadCrossingGeometry.body(other));
                    if(span!=null){int firstIndex=Math.min(RoadAutoPlanning.index(geometry,span.first(geometry)),RoadAutoPlanning.index(geometry,span.last(geometry)));floor=RoadHeightProfile.of(geometry).row(RoadAutoPlanning.along(geometry,firstIndex)).baseY();}
                }
                if(floor==null && geometry!=null && !geometry.segments().isEmpty()) {
                    var intersection=RoadCrossingGeometry.walk(geometry).intersect(RoadCrossingGeometry.walk(other));
                    if(intersection!=null)try {floor=RoadCrossingGeometry.platform(geometry,intersection);}catch(IllegalArgumentException ignored) { }
                }
                height.setText(Component.translatable(mode==0 ? "rp_crossroads.gui.shared_height" : "rp_crossroads.gui.own_height",floor==null ? "?" : floor));
                // The server opens a closed neighbour edit switch on authorization, so a shared
                // platform stays editable even when the peer switch was previously off.
                boolean common=allowed && mode==0 && authorized;
                Button lower=row.findPaneOfTypeByID("lower",Button.class),raise=row.findPaneOfTypeByID("raise",Button.class),preview=row.findPaneOfTypeByID("preview",Button.class);
                lower.setEnabled(common || allowed && mode!=0 && authorized);raise.setEnabled(common || allowed && mode!=0 && authorized);
                lower.setHandler(b -> height(other,-1));raise.setHandler(b -> height(other,1));
                preview.setEnabled(allowed && authorized);
                preview.setText(Component.translatable(ClientRoadPreview.jointSelected(road,other.id()) ? "rp_crossroads.gui.single_preview" : "rp_crossroads.gui.joint_preview"));
                preview.setHandler(b -> ClientRoadPreview.joint(road,ClientRoadPreview.jointSelected(road,other.id()) ? null : other.id()));
                for(Button button:new Button[]{lower,raise,preview})button.setColors(0x000000,0x9A9A9A,0x000000);
            }
        });
    }
    @Override public void onOpened() { super.onOpened();PacketDistributor.sendToServer(new RequestRoadsPayload()); }
    @Override public void onUpdate() {
        super.onUpdate();own=ClientRoadCache.roads().stream().filter(p -> p.id().equals(road)).findFirst().orElse(null);
        var proposed=ClientRoadPreview.plan();geometry=proposed!=null && proposed.id().equals(road) ? proposed : own;
        if(geometry==null || geometry.segments().isEmpty()) candidates=List.of();
        else {
            var relationSource=own!=null ? own:geometry;
            var rows=new LinkedHashMap<UUID,Entry>();
            for(var projection:relationSource.syncedJunctions().values())
                ClientRoadCache.roads().stream().filter(p -> p.id().equals(projection.peer()) && p.dimension().equals(geometry.dimension()) && !p.segments().isEmpty())
                        .findFirst().ifPresent(p -> rows.put(p.id(),new Entry(p,true)));
            for(var p:ClientRoadCache.roads()) {
                if(p.id().equals(road) || !p.dimension().equals(geometry.dimension()) || p.segments().isEmpty())continue;
                if(RoadCrossingGeometry.body(geometry).intersect(RoadCrossingGeometry.body(p))==null)continue;
                rows.putIfAbsent(p.id(),new Entry(p,false));
            }
            candidates=rows.values().stream().sorted(Comparator.comparing(e -> RoadNames.label(e.road()))).toList();
        }
        list.refreshElementPanes();
        findPaneOfTypeByID("status",Text.class).setText(own==null ? Component.literal("") : RoadNotices.issue(own.planning().issue()));
        findPaneOfTypeByID("reset",Button.class).setEnabled(own!=null && RoadRules.canEdit(own) && ClientRoadCache.canManage(road));
    }
    private void send(int action,UUID other,int mode) {
        if(own==null || !RoadSelectionClient.hasBoth()) return;
        PacketDistributor.sendToServer(new RoadPlanningPayload(road,own.controllerPos(),RoadSelectionClient.a(),RoadSelectionClient.b(),RoadSelectionClient.mirror(),action,other,mode));
    }
    private void height(RoadPlan other,int delta) {
        if(own==null || !RoadSelectionClient.hasBoth())return;
        ClientRoadPreview.joint(road,other.id());
        PacketDistributor.sendToServer(new RoadPlanningPayload(road,own.controllerPos(),RoadSelectionClient.a(),RoadSelectionClient.b(),RoadSelectionClient.mirror(),3,other.id(),0,delta));
    }
}
