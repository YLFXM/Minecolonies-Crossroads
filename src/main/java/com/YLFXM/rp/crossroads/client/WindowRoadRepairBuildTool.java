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

import java.util.ArrayList;
import java.util.List;
import com.YLFXM.rp.crossroads.CustomRoadPack;
import com.YLFXM.rp.crossroads.network.ConfirmRoadRepairPayload;
import com.YLFXM.rp.crossroads.network.OpenBuildToolPayload;
import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.ButtonImage;
import com.ldtteam.blockui.views.ScrollingList;
import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.api.constants.Constants;
import com.ldtteam.structurize.client.gui.WindowExtendedBuildTool;
import com.ldtteam.structurize.network.messages.BuildToolPlacementMessage;
import com.ldtteam.structurize.storage.StructurePacks;
import com.ldtteam.structurize.storage.SurvivalBlueprintHandlers;
import com.ldtteam.structurize.storage.rendering.RenderingCache;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Shared authorized tool for first construction and Repair, using native layout/controls. */
public final class WindowRoadRepairBuildTool {
    private final OpenBuildToolPayload repair;
    private final WindowExtendedBuildTool window;
    private boolean submitted;
    private boolean jointApproved;
    private record Choice(Component label, BuildToolPlacementMessage.HandlerType type, String handler) { }

    public WindowRoadRepairBuildTool(final OpenBuildToolPayload repair, final HolderLookup.Provider registries) {
        this.repair=repair;
        this.window=new WindowExtendedBuildTool(repair.anchorPos(),Constants.GROUNDSTYLE_RELATIVE,
                (nativeWindow,blueprint) -> showChoices(nativeWindow),
                blueprint -> repair.blueprintPath().endsWith("/"+blueprint.getFileName()+".blueprint"),registries);
    }
    public void open() { window.open(); }

    private void showChoices(final WindowExtendedBuildTool activeWindow) {
        if(submitted) return;
        // Refuse at the first confirmation click, even when moving the anchor
        // leaves no matching survival handler or before the player picks a mode.
        if (!validateSelection()) return;
        final var mc=Minecraft.getInstance();
        final var data=RenderingCache.getBlueprintPreviewData("blueprint");
        if(mc.player==null || mc.level==null || data==null || data.getBlueprint()==null) return;
        if(!jointApproved && !repair.jointImpact().isEmpty()) {
            activeWindow.updatePlacementOptions();
            final ScrollingList consent=activeWindow.findPaneOfTypeByID("placement",ScrollingList.class);
            consent.setDataProvider(new ScrollingList.DataProvider() {
                @Override public int getElementCount() {return repair.jointImpact().size()+1;}
                @Override public void updateElement(int index,Pane row) {
                    var button=row.findPaneOfTypeByID("type",ButtonImage.class);
                    if(index<repair.jointImpact().size()) {
                        // Impact lines are either a reason key or plain data (road
                        // name, coordinate range); RoadNotices.issue tells them apart.
                        button.setText(com.YLFXM.rp.crossroads.road.RoadNotices.issue(repair.jointImpact().get(index)));
                        button.setEnabled(false);
                        button.setColors(0x000000,0x444444,0x000000);
                    } else {
                        button.setEnabled(true);button.setColors(0x000000,0x9A9A9A,0x000000);
                        button.setText(Component.translatable("rp_crossroads.crossing.approve"));
                        button.setHandler(b -> {jointApproved=true;showChoices(activeWindow);});
                    }
                }
            });consent.refreshElementPanes();return;
        }
        final List<Choice> choices=new ArrayList<>();
        if(mc.player.isCreative()) {
            choices.add(new Choice(Component.translatable("structurize.gui.buildtool.complete"),BuildToolPlacementMessage.HandlerType.Complete,""));
            choices.add(new Choice(Component.translatable("structurize.gui.buildtool.pretty"),BuildToolPlacementMessage.HandlerType.Pretty,""));
        }
        for(final var handler:SurvivalBlueprintHandlers.getMatchingHandlers(data.getBlueprint(),mc.level,mc.player,data.getPos(),data.getRotationMirror())) {
            if("minecolonies".equals(handler.getId())) choices.add(new Choice(handler.getDisplayName(),BuildToolPlacementMessage.HandlerType.Survival,handler.getId()));
        }
        if(choices.isEmpty()) { com.YLFXM.rp.crossroads.road.RoadNotices.chat(mc.player,null,"no_handler","rp_crossroads.repair.no_handler"); return; }
        if(!mc.player.isCreative() && choices.size()==1) { submit(choices.get(0),activeWindow); return; }
        activeWindow.updatePlacementOptions();
        final ScrollingList list=activeWindow.findPaneOfTypeByID("placement",ScrollingList.class);
        list.setDataProvider(new ScrollingList.DataProvider() {
            @Override public int getElementCount() { return choices.size(); }
            @Override public void updateElement(final int index, final Pane row) {
                final ButtonImage button=row.findPaneOfTypeByID("type",ButtonImage.class);
                button.setEnabled(true);button.setColors(0x000000,0x9A9A9A,0x000000);
                button.setText(choices.get(index).label());
                button.setHandler(ignored -> submit(choices.get(index),activeWindow));
            }
        });
        list.refreshElementPanes();
    }

    private void submit(final Choice choice, final WindowExtendedBuildTool activeWindow) {
        if(submitted) return;
        if(!validateSelection()) return;
        final var data=RenderingCache.getBlueprintPreviewData("blueprint");
        final var pack=StructurePacks.getSelectedPack();
        if(data==null || data.getBlueprint()==null || pack==null) return;
        final var blueprint=data.getBlueprint();
        final String path=com.YLFXM.rp.crossroads.road.RoadRepairSelection.path(pack.getSubPath(blueprint.getFilePath().resolve(blueprint.getFileName()+".blueprint")));
        PacketDistributor.sendToServer(new ConfirmRoadRepairPayload(repair.planId(),repair.repairToken(),choice.type().ordinal(),
                choice.handler(),pack.getName(),path,data.getPos(),data.getRotationMirror().ordinal(),jointApproved));
        submitted=true;
        if(choice.type()==BuildToolPlacementMessage.HandlerType.Survival) activeWindow.close();
    }

    private boolean validateSelection() {
        final var mc=Minecraft.getInstance();
        if(mc.player==null || mc.level==null) return false;
        final var road=com.YLFXM.rp.crossroads.network.ClientRoadCache.roads().stream()
                .filter(plan -> plan.id().equals(repair.planId()) && plan.dimension().equals(mc.level.dimension()))
                .findFirst().orElse(null);
        // Deletion can clear the native preview before this callback; report
        // expiration before inspecting preview data, instead of returning silently.
        if(road==null || !road.isRepairBusy()) {
            com.YLFXM.rp.crossroads.road.RoadNotices.chat(mc.player,null,"expired","rp_crossroads.construction.expired");
            return false;
        }
        final var data=RenderingCache.getBlueprintPreviewData("blueprint");
        final var pack=StructurePacks.getSelectedPack();
        if(data==null || data.getBlueprint()==null || pack==null) return false;
        final var blueprint=data.getBlueprint();
        final String path=com.YLFXM.rp.crossroads.road.RoadRepairSelection.path(pack.getSubPath(blueprint.getFilePath().resolve(blueprint.getFileName()+".blueprint")));
        final var mismatch=com.YLFXM.rp.crossroads.road.RoadRepairSelection.validate(
                CustomRoadPack.PACK_NAME,repair.blueprintPath(),repair.anchorPos(),RotationMirror.NONE.ordinal(),
                pack.getName(),path,data.getPos(),data.getRotationMirror().ordinal());
        if(mismatch!=com.YLFXM.rp.crossroads.road.RoadRepairSelection.Mismatch.NONE) {
            com.YLFXM.rp.crossroads.Crossroads.LOGGER.warn(
                    "Repair selection rejected: reason={} expectedPath={} actualPath={} expectedAnchor={} actualAnchor={} expectedPack={} actualPack={} rotation={}",
                    mismatch,repair.blueprintPath(),path,repair.anchorPos(),data.getPos(),CustomRoadPack.PACK_NAME,pack.getName(),data.getRotationMirror());
            final var player=Minecraft.getInstance().player;
            if(player!=null) com.YLFXM.rp.crossroads.road.RoadNotices.chat(player,null,"selection","rp_crossroads.repair.selection_"+mismatch.name().toLowerCase(java.util.Locale.ROOT));
            return false;
        }
        return true;
    }
}
