/* SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 ZhuanZ (YLFXM) */
package com.YLFXM.mcreconstruction.client;

import java.util.*;
import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.*;
import com.ldtteam.blockui.views.ScrollingList;
import com.minecolonies.core.client.gui.AbstractWindowSkeleton;
import com.YLFXM.mcreconstruction.MCReconstruction;
import com.YLFXM.mcreconstruction.network.*;
import com.YLFXM.mcreconstruction.road.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/** Functional pair settings; visual redesign is intentionally independent of road geometry. */
public final class WindowRoadCrossings extends AbstractWindowSkeleton {
    private final UUID road;
    private final ScrollingList list;
    private List<RoadPlan> candidates=List.of();
    private RoadPlan own;
    private RoadPlan geometry;
    public WindowRoadCrossings(UUID road) {
        super(ResourceLocation.fromNamespaceAndPath(MCReconstruction.MODID,"gui/windowroadcrossings.xml"));
        this.road=road;this.list=findPaneOfTypeByID("roads",ScrollingList.class);
        this.registerButton("back",b -> {close();new WindowRoadWand().open();});
        this.registerButton("reset",b -> send(1,new UUID(0,0),0));
        this.list.setDataProvider(new ScrollingList.DataProvider() {
            @Override public int getElementCount() { return candidates.size(); }
            @Override public void updateElement(int index,Pane row) {
                if(own==null || index<0 || index>=candidates.size()) return;
                var other=candidates.get(index);var setting=RoadCrossings.setting(own,other);
                int mode=setting==null || !setting.layered() ? 0 : setting.order()>=0 ? 1 : 2;
                row.findPaneOfTypeByID("name",Text.class).setText(Component.literal(RoadNames.label(other)));
                Button type=row.findPaneOfTypeByID("mode",Button.class),strategy=row.findPaneOfTypeByID("strategy",Button.class);
                type.setText(Component.translatable(mode==0 ? "mcreconstruction.gui.same_level" : mode==1 ? "mcreconstruction.gui.overpass" : "mcreconstruction.gui.underpass"));
                int policy=own.planning().policy(other.id()),access=own.planning().access(other.id());
                boolean nativePeer=ClientRoadCache.canManage(other.id()),authorized=nativePeer || access==2;
                strategy.setText(Component.translatable(policy==1 ? "mcreconstruction.gui.avoid_road":"mcreconstruction.gui.request_junction")
                        .append(access==1 ? " …":""));
                boolean allowed=RoadRules.canEdit(own) && ClientRoadCache.canManage(own.id()) && !other.isRepairBusy() && other.state()!=RoadState.REMOVING;
                for(Button button:new Button[]{type,strategy}) button.setColors(0x000000,0x9A9A9A,0x000000);
                type.setEnabled(allowed && (authorized || policy==2));strategy.setEnabled(allowed && !nativePeer);
                type.setHandler(b -> send(2,other.id(),(mode+1)%3));
                strategy.setHandler(b -> send(policy==1 ? 6:5,other.id(),mode));
                Text height=row.findPaneOfTypeByID("height",Text.class);Integer floor=mode==0 ? setting.platform() : null;
                if(mode!=0 && geometry!=null && !geometry.segments().isEmpty()) {
                    var span=RoadCrossingGeometry.body(geometry).intersect(RoadCrossingGeometry.body(other));
                    if(span!=null){int firstIndex=Math.min(RoadAutoPlanning.index(geometry,span.first(geometry)),RoadAutoPlanning.index(geometry,span.last(geometry)));floor=RoadHeightProfile.of(geometry).row(RoadAutoPlanning.along(geometry,firstIndex)).baseY();}
                }
                if(floor==null && geometry!=null && !geometry.segments().isEmpty()) {
                    var intersection=RoadCrossingGeometry.walk(geometry).intersect(RoadCrossingGeometry.walk(other));
                    if(intersection!=null)try {floor=RoadCrossingGeometry.platform(geometry,intersection);}catch(IllegalArgumentException ignored) { }
                }
                height.setText(Component.translatable(mode==0 ? "mcreconstruction.gui.shared_height" : "mcreconstruction.gui.own_height",floor==null ? "?" : floor));
                boolean common=allowed && mode==0 && authorized && RoadRules.canChangePlatform(own,other);
                Button lower=row.findPaneOfTypeByID("lower",Button.class),raise=row.findPaneOfTypeByID("raise",Button.class),preview=row.findPaneOfTypeByID("preview",Button.class);
                lower.setEnabled(common || allowed && mode!=0 && (authorized || policy==2));raise.setEnabled(common || allowed && mode!=0 && (authorized || policy==2));
                lower.setHandler(b -> height(other,-1));raise.setHandler(b -> height(other,1));
                preview.setEnabled(allowed && authorized);
                preview.setText(Component.translatable(ClientRoadPreview.jointSelected(road,other.id()) ? "mcreconstruction.gui.single_preview" : "mcreconstruction.gui.joint_preview"));
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
        else candidates=ClientRoadCache.roads().stream().filter(p -> !p.id().equals(road) && p.dimension().equals(geometry.dimension()) && !p.segments().isEmpty()
                && RoadCrossingGeometry.body(geometry).intersect(RoadCrossingGeometry.body(p))!=null)
                .sorted(Comparator.comparing(RoadNames::label)).toList();
        list.refreshElementPanes();
        findPaneOfTypeByID("status",Text.class).setText(Component.literal(own==null ? "" : own.planning().issue()));
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
