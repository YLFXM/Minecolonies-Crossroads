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

import com.YLFXM.rp.crossroads.road.RoadRules;

import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.Text;
import com.minecolonies.core.client.gui.AbstractWindowSkeleton;
import com.YLFXM.rp.crossroads.Crossroads;
import com.YLFXM.rp.crossroads.network.ClientRoadCache;
import com.YLFXM.rp.crossroads.network.RoadControllerActionPayload;
import com.YLFXM.rp.crossroads.road.RoadNames;
import com.YLFXM.rp.crossroads.road.RoadPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Controller panel: Upgrade / Repair / Remove, plus the per-plan "edit
 * blueprint" switch. Construction options belong to the Road Wand / native build
 * tool, not this window.
 */
public class WindowRoadController extends AbstractWindowSkeleton {
    private final BlockPos controllerPos;
    private final Text nameText;
    private final Text stateText;
    private final Text detailText;
    private final Button upgradeButton;
    private final Button repairButton;
    private final Button removeButton;
    private final Button editButton;

    public WindowRoadController(final BlockPos controllerPos) {
        super(ResourceLocation.fromNamespaceAndPath(Crossroads.MODID, "gui/windowroadcontroller.xml"));
        this.controllerPos = controllerPos;
        this.nameText = this.findPaneOfTypeByID("name", Text.class);
        this.stateText = this.findPaneOfTypeByID("state", Text.class);
        this.detailText = this.findPaneOfTypeByID("detail", Text.class);
        this.upgradeButton = this.findPaneOfTypeByID("upgrade", Button.class);
        this.repairButton = this.findPaneOfTypeByID("repair", Button.class);
        this.removeButton = this.findPaneOfTypeByID("remove", Button.class);
        this.editButton = this.findPaneOfTypeByID("edit", Button.class);
        // BlockUI derives the disabled colour from "color" when only that is set,
        // so disabled buttons would look identical to enabled ones. Force a grey.
        for (final Button button : new Button[]{this.upgradeButton, this.repairButton,
                this.removeButton, this.editButton}) {
            button.setColors(0x000000, 0x9A9A9A, 0x000000);
        }

        this.registerButton("upgrade", button -> this.send(RoadControllerActionPayload.Action.UPGRADE));
        this.registerButton("repair", button -> this.send(RoadControllerActionPayload.Action.REPAIR));
        this.registerButton("remove", button -> this.chooseRemoval());
        // Deliberately not closing the window: the player usually flips it to
        // edit a few cells right after, and the panel is where they came from.
        this.registerButton("edit", button -> {
            final RoadPlan plan = this.plan();
            if (plan == null) {
                return;
            }
            PacketDistributor.sendToServer(
                    new com.YLFXM.rp.crossroads.network.ToggleBlueprintEditPayload(
                            this.controllerPos, !plan.allowBlueprintEdit()));
        });
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        final RoadPlan plan = this.plan();
        this.nameText.setText(plan == null
                ? Component.translatable("rp_crossroads.gui.no_plan")
                : Component.literal(RoadNames.label(plan)));
        this.stateText.setText(plan == null ? Component.empty() : plan.state().display());
        // Second line: the edit switch plus a per-row diff between the newest
        // proposal and the table the world was built from. A re-pick that only
        // shortens the road changes no row's type, so it reports 0 edited and
        // counts the lost rows as dropped instead.
        final net.minecraft.network.chat.MutableComponent detail = Component.empty();
        if (plan != null) {
            if (plan.isRepairBusy()) {
                detail.append(Component.translatable("rp_crossroads.gui.repair_running")).append("  ·  ");
            }
            detail.append(Component.translatable(plan.allowBlueprintEdit()
                            ? "rp_crossroads.gui.edit_on" : "rp_crossroads.gui.edit_off"))
                    .append("  ·  ")
                    .append(Component.translatable("rp_crossroads.gui.rows_edited", plan.editedRowCount()));
            if (plan.lostRowCount() > 0) {
                detail.append("  ·  ").append(Component.translatable(
                        "rp_crossroads.gui.rows_dropped", plan.lostRowCount()));
            }
        }
        this.detailText.setText(detail);
        final boolean canManage = plan != null && ClientRoadCache.canManage(plan.id());
        this.upgradeButton.setEnabled(false);
        this.repairButton.setEnabled(canManage && RoadRules.canRepair(plan));
        this.removeButton.setEnabled(canManage && RoadRules.canRemove(plan));
        this.editButton.setEnabled(canManage && RoadRules.canToggleEdit(plan));
        this.editButton.setText(Component.translatable(plan != null && plan.allowBlueprintEdit()
                ? "rp_crossroads.gui.edit_blueprint_on" : "rp_crossroads.gui.edit_blueprint_off"));
    }

    private void chooseRemoval() {
        final RoadPlan original=this.plan();
        if(original==null || !ClientRoadCache.canManage(original.id()) || !RoadRules.canRemove(original)) return;
        if(original.state()==com.YLFXM.rp.crossroads.road.RoadState.DRAFT) {
            this.send(RoadControllerActionPayload.Action.REMOVE); return;
        }
        final var id=original.id();
        WindowRoadRemovalChoice.choose(this,instant -> {
            final RoadPlan current=this.plan();
            if(current!=null && current.id().equals(id) && ClientRoadCache.canManage(id) && RoadRules.canRemove(current))
                this.send(instant ? RoadControllerActionPayload.Action.REMOVE_INSTANT : RoadControllerActionPayload.Action.REMOVE);
        });
    }

    private void send(final RoadControllerActionPayload.Action action) {
        PacketDistributor.sendToServer(new RoadControllerActionPayload(this.controllerPos, action));
        this.close();
    }

    private RoadPlan plan() {
        for (final RoadPlan plan : ClientRoadCache.roads()) {
            if (plan.controllerPos().equals(this.controllerPos)) {
                return plan;
            }
        }
        return null;
    }
}
