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

import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.views.ScrollingList;
import com.minecolonies.core.client.gui.AbstractWindowSkeleton;
import com.YLFXM.rp.crossroads.Crossroads;
import com.YLFXM.rp.crossroads.network.ClientRoadCache;
import com.YLFXM.rp.crossroads.network.RoadBulkActionPayload;
import com.YLFXM.rp.crossroads.road.RoadNames;
import com.YLFXM.rp.crossroads.road.RoadPlan;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * BlockUI version of the Road Surveyor window, matching MineColonies styling.
 */
public class WindowRoadSurveyor extends AbstractWindowSkeleton {
    private final Set<UUID> selected = new HashSet<>();
    private final ScrollingList roadList;
    private final Text titleText;
    private final Text selectedText;
    private final Button releaseButton;
    private final Button removeButton;
    private int lastCount = -1;
    private int lastManageable = -1;
    private boolean lastBound;

    public WindowRoadSurveyor() {
        super(ResourceLocation.fromNamespaceAndPath(Crossroads.MODID, "gui/windowroadsurveyor.xml"));
        this.roadList = this.findPaneOfTypeByID("roads", ScrollingList.class);
        this.titleText = this.findPaneOfTypeByID("title", Text.class);
        this.selectedText = this.findPaneOfTypeByID("selected", Text.class);
        this.releaseButton = this.findPaneOfTypeByID("release", Button.class);
        this.removeButton = this.findPaneOfTypeByID("remove", Button.class);
        // "color" alone makes BlockUI reuse it for the disabled state, so set an
        // explicit grey or a disabled button looks exactly like an enabled one.
        this.releaseButton.setColors(0x000000, 0x9A9A9A, 0x000000);
        this.removeButton.setColors(0x000000, 0x9A9A9A, 0x000000);
        this.roadList.setDataProvider(new ScrollingList.DataProvider() {
            @Override
            public int getElementCount() {
                return ClientRoadCache.surveyRoads().size();
            }

            @Override
            public void updateElement(final int index, final Pane row) {
                final List<RoadPlan> roads = ClientRoadCache.surveyRoads();
                if (index < 0 || index >= roads.size()) {
                    return;
                }
                final RoadPlan plan = roads.get(index);
                final boolean manageable = ClientRoadCache.canManage(plan.id());
                final boolean checked = selected.contains(plan.id());
                final Button select = row.findPaneOfTypeByID("select", Button.class);
                // Per-row permission: roads in colonies the player cannot manage
                // stay listed for information but cannot be selected.
                select.setText(Component.literal(!manageable ? "[-]" : checked ? "[x]" : "[ ]"));
                select.setEnabled(manageable);
                select.setColors(0x000000, 0x9A9A9A, 0x000000);
                select.setHandler(button -> {
                    if (!selected.add(plan.id())) {
                        selected.remove(plan.id());
                    }
                    refreshRows();
                });
                final Text nameText = row.findPaneOfTypeByID("roadName", Text.class);
                nameText.setText(Component.literal(RoadNames.label(plan)));
                nameText.setColors(manageable ? 0x000000 : 0x8A8A8A);
                final Text detailText = row.findPaneOfTypeByID("roadDetail", Text.class);
                detailText.setText(Component.translatable("rp_crossroads.gui.surveyor_row",
                        plan.state().display(), plan.length(), plan.controllerPos().toShortString()));
                detailText.setColors(manageable ? 0x000000 : 0x8A8A8A);
            }
        });

        this.registerButton("selectAll", this::selectAll);
        this.registerButton("clear", this::clearSelection);
        this.registerButton("close", button -> this.close());
        this.registerButton("release", button -> this.send(false));
        this.registerButton("remove", button -> this.send(true));
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        final List<RoadPlan> roads = ClientRoadCache.surveyRoads();
        final int manageable = (int) roads.stream()
                .filter(plan -> ClientRoadCache.canManage(plan.id())).count();
        if (roads.size() != this.lastCount || manageable != this.lastManageable
                || ClientRoadCache.bound() != this.lastBound) {
            this.refreshRows();
        }
    }

    @Override
    public void onOpened() {
        super.onOpened();
        this.refreshRows();
    }

    private void selectAll() {
        this.selected.clear();
        for (final RoadPlan plan : ClientRoadCache.surveyRoads()) {
            // Only roads this player may actually manage can be selected.
            if (ClientRoadCache.canManage(plan.id())) {
                this.selected.add(plan.id());
            }
        }
        this.refreshRows();
    }

    private void clearSelection() {
        this.selected.clear();
        this.refreshRows();
    }

    private void refreshRows() {
        final List<RoadPlan> roads = ClientRoadCache.surveyRoads();
        // Drop selections that vanished or that the player may no longer manage.
        this.selected.removeIf(id -> !ClientRoadCache.canManage(id)
                || roads.stream().noneMatch(plan -> plan.id().equals(id)));
        this.titleText.setText(ClientRoadCache.bound()
                ? Component.translatable("rp_crossroads.gui.surveyor_title_colony", ClientRoadCache.colonyName())
                : Component.translatable("rp_crossroads.gui.surveyor_title"));
        this.selectedText.setText(ClientRoadCache.bound()
                ? Component.translatable("rp_crossroads.gui.selected_count", this.selected.size())
                : Component.translatable("rp_crossroads.gui.surveyor_unbound"));
        // Only offer the actions when at least one selected road is manageable;
        // the server still checks each road individually.
        final boolean canAct = !this.selected.isEmpty()
                && this.selected.stream().anyMatch(ClientRoadCache::canManage);
        this.releaseButton.setEnabled(canAct);
        this.removeButton.setEnabled(canAct);
        this.roadList.refreshElementPanes();
        this.lastCount = roads.size();
        this.lastBound = ClientRoadCache.bound();
        this.lastManageable = (int) roads.stream()
                .filter(plan -> ClientRoadCache.canManage(plan.id())).count();
    }

    private void send(final boolean removeBlocks) {
        if (this.selected.isEmpty()) {
            if (this.mc.player != null) {
                com.YLFXM.rp.crossroads.road.RoadNotices.actionBar(this.mc.player,"rp_crossroads.ui.select_road_first");
            }
            return;
        }
        final List<UUID> targets=List.copyOf(this.selected);
        final boolean draftsOnly=targets.stream().allMatch(id -> ClientRoadCache.roads().stream()
                .anyMatch(p -> p.id().equals(id) && p.state()==com.YLFXM.rp.crossroads.road.RoadState.DRAFT));
        if(removeBlocks && !draftsOnly) WindowRoadRemovalChoice.choose(this,instant ->
                PacketDistributor.sendToServer(new RoadBulkActionPayload(targets,true,instant)));
        else PacketDistributor.sendToServer(new RoadBulkActionPayload(targets,removeBlocks));
    }
}
