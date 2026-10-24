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
package com.YLFXM.mcreconstruction.client;

import java.util.ArrayList;
import java.util.List;
import com.YLFXM.mcreconstruction.CustomRoadPack;
import com.YLFXM.mcreconstruction.network.ConfirmRoadRepairPayload;
import com.YLFXM.mcreconstruction.network.OpenBuildToolPayload;
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
        final List<Choice> choices=new ArrayList<>();
        if(mc.player.isCreative()) {
            choices.add(new Choice(Component.translatable("structurize.gui.buildtool.complete"),BuildToolPlacementMessage.HandlerType.Complete,""));
            choices.add(new Choice(Component.translatable("structurize.gui.buildtool.pretty"),BuildToolPlacementMessage.HandlerType.Pretty,""));
        }
        for(final var handler:SurvivalBlueprintHandlers.getMatchingHandlers(data.getBlueprint(),mc.level,mc.player,data.getPos(),data.getRotationMirror())) {
            if("minecolonies".equals(handler.getId())) choices.add(new Choice(handler.getDisplayName(),BuildToolPlacementMessage.HandlerType.Survival,handler.getId()));
        }
        if(choices.isEmpty()) { mc.player.displayClientMessage(Component.literal("No native builder handler can repair this road here."),false); return; }
        if(!mc.player.isCreative() && choices.size()==1) { submit(choices.get(0),activeWindow); return; }
        activeWindow.updatePlacementOptions();
        final ScrollingList list=activeWindow.findPaneOfTypeByID("placement",ScrollingList.class);
        list.setDataProvider(new ScrollingList.DataProvider() {
            @Override public int getElementCount() { return choices.size(); }
            @Override public void updateElement(final int index, final Pane row) {
                final ButtonImage button=row.findPaneOfTypeByID("type",ButtonImage.class);
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
        final String path=com.YLFXM.mcreconstruction.road.RoadRepairSelection.path(pack.getSubPath(blueprint.getFilePath().resolve(blueprint.getFileName()+".blueprint")));
        PacketDistributor.sendToServer(new ConfirmRoadRepairPayload(repair.planId(),repair.repairToken(),choice.type().ordinal(),
                choice.handler(),pack.getName(),path,data.getPos(),data.getRotationMirror().ordinal()));
        submitted=true;
        if(choice.type()==BuildToolPlacementMessage.HandlerType.Survival) activeWindow.close();
    }

    private boolean validateSelection() {
        final var mc=Minecraft.getInstance();
        if(mc.player==null || mc.level==null) return false;
        final var road=com.YLFXM.mcreconstruction.network.ClientRoadCache.roads().stream()
                .filter(plan -> plan.id().equals(repair.planId()) && plan.dimension().equals(mc.level.dimension()))
                .findFirst().orElse(null);
        // Deletion can clear the native preview before this callback; report
        // expiration before inspecting preview data, instead of returning silently.
        if(road==null || !road.isRepairBusy()) {
            mc.player.displayClientMessage(Component.translatable("mcreconstruction.construction.expired"),false);
            return false;
        }
        final var data=RenderingCache.getBlueprintPreviewData("blueprint");
        final var pack=StructurePacks.getSelectedPack();
        if(data==null || data.getBlueprint()==null || pack==null) return false;
        final var blueprint=data.getBlueprint();
        final String path=com.YLFXM.mcreconstruction.road.RoadRepairSelection.path(pack.getSubPath(blueprint.getFilePath().resolve(blueprint.getFileName()+".blueprint")));
        final var mismatch=com.YLFXM.mcreconstruction.road.RoadRepairSelection.validate(
                CustomRoadPack.PACK_NAME,repair.blueprintPath(),repair.anchorPos(),RotationMirror.NONE.ordinal(),
                pack.getName(),path,data.getPos(),data.getRotationMirror().ordinal());
        if(mismatch!=com.YLFXM.mcreconstruction.road.RoadRepairSelection.Mismatch.NONE) {
            com.YLFXM.mcreconstruction.MCReconstruction.LOGGER.warn(
                    "Repair selection rejected: reason={} expectedPath={} actualPath={} expectedAnchor={} actualAnchor={} expectedPack={} actualPack={} rotation={}",
                    mismatch,repair.blueprintPath(),path,repair.anchorPos(),data.getPos(),CustomRoadPack.PACK_NAME,pack.getName(),data.getRotationMirror());
            final var player=Minecraft.getInstance().player;
            if(player!=null) player.displayClientMessage(Component.translatable("mcreconstruction.repair.selection_"+mismatch.name().toLowerCase(java.util.Locale.ROOT)),false);
            return false;
        }
        return true;
    }
}
