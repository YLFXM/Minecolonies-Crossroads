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

import com.YLFXM.rp.crossroads.road.RoadNotices;
import com.YLFXM.rp.crossroads.road.RoadRules;

import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.Text;
import com.minecolonies.core.client.gui.AbstractWindowSkeleton;
import com.YLFXM.rp.crossroads.Crossroads;
import com.YLFXM.rp.crossroads.network.ClientRoadCache;
import com.YLFXM.rp.crossroads.network.ConfigureRoadPayload;
import com.YLFXM.rp.crossroads.road.RoadNames;
import com.YLFXM.rp.crossroads.road.RoadPlacementMode;
import com.YLFXM.rp.crossroads.road.RoadPlan;
import com.YLFXM.rp.crossroads.road.RoadState;
import com.YLFXM.rp.crossroads.road.RoadWandBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * BlockUI Road Wand editor. Width and lamp spacing come from the bound plan /
 * building pack, not from this window.
 */
public class WindowRoadWand extends AbstractWindowSkeleton {
    /** Lamp spacing the panel can reach; 0 would mean "no lamps at all". */
    private static final int MIN_LAMP_SPACING = 1;
    private static final int MAX_LAMP_SPACING = 8;

    private final Text summaryText;
    private final Text boundText;
    private final Text stateText;
    private final Button mirrorButton;
    private final Button planButton;
    private final Button clearButton;
    private final Button autoButton;
    private final Button crossingButton;
    private final Text planningText;
    /** Read-only display of the current lamp spacing between the -/+ buttons. */
    private final Button lampValueButton;
    private boolean mirror;
    /** Last state printed to the log, so the panel does not spam every tick. */
    private String lastPanelDebug = "";

    public WindowRoadWand() {
        super(ResourceLocation.fromNamespaceAndPath(Crossroads.MODID, "gui/windowroadwand.xml"));
        this.summaryText = this.findPaneOfTypeByID("summary", Text.class);
        this.boundText = this.findPaneOfTypeByID("bound", Text.class);
        this.stateText = this.findPaneOfTypeByID("state", Text.class);
        this.mirrorButton = this.findPaneOfTypeByID("mirror", Button.class);
        this.planButton = this.findPaneOfTypeByID("plan", Button.class);
        this.clearButton = this.findPaneOfTypeByID("clear", Button.class);
        this.autoButton=this.findPaneOfTypeByID("autoPlan",Button.class);
        this.crossingButton=this.findPaneOfTypeByID("crossings",Button.class);
        this.planningText=this.findPaneOfTypeByID("planning",Text.class);
        this.autoButton.setColors(0x000000,0x9A9A9A,0x000000);
        this.crossingButton.setColors(0x000000,0x9A9A9A,0x000000);
        this.registerButton("autoPlan",button -> sendPlanning(0,new java.util.UUID(0,0),0));
        this.registerButton("crossings",button -> { final RoadPlan current=this.boundPlan(this.boundController());
            if(current!=null) {this.close();new WindowRoadCrossings(current.id()).open();} });
        this.lampValueButton = this.findPaneOfTypeByID("lampValue", Button.class);
        final RoadPlan bound = this.boundPlan(this.boundController());
        if (bound != null) RoadSelectionClient.bindTo(bound);
        this.mirror = RoadSelectionClient.mirror();
        this.refreshMirrorButton();
        // "color" alone is reused by BlockUI for the disabled state; set an
        // explicit grey so disabled buttons read as disabled.
        for (final Button button : new Button[]{this.planButton, this.mirrorButton, this.clearButton,
                this.lampValueButton}) {
            button.setColors(0x000000, 0x9A9A9A, 0x000000);
        }

        this.registerButton("plan", button -> this.send(RoadPlacementMode.PLAN, -1));
        this.registerButton("mirror", button -> {
            this.mirror = !this.mirror;
            RoadSelectionClient.setMirror(this.mirror);
            this.refreshMirrorButton();
        });
        this.registerButton("clear", button -> {
            final RoadPlan current=this.boundPlan(this.boundController());
            if(current!=null && current.state()==RoadState.DRAFT)PacketDistributor.sendToServer(new com.YLFXM.rp.crossroads.network.RoadPlanningPayload(
                    current.id(),current.controllerPos(),BlockPos.ZERO,BlockPos.ZERO,RoadSelectionClient.mirror(),4,new java.util.UUID(0,0),0));
            RoadSelectionClient.clear();
        });
        this.registerButton("lampDown", button -> this.adjustLampSpacing(-1));
        this.registerButton("lampUp", button -> this.adjustLampSpacing(1));
    }

    /** Mirror state is shown on the button itself, so both call sites share one key. */
    private void refreshMirrorButton() {
        this.mirrorButton.setText(Component.translatable(
                this.mirror ? "rp_crossroads.gui.mirror_on" : "rp_crossroads.gui.mirror_off"));
    }

    /**
     * Steps the lamp spacing by one and applies it straight away.
     *
     * <p>Range is 1-8. Stepping is used instead of a typed value because a
     * number alone never said when the player was done editing; 0 is left out
     * because a large spacing already means "practically no lamps" and stepping
     * down to it would just cost clicks.</p>
     *
     * <p>The panel stays open and the value updates from the server, so the
     * ghost redraws while the player keeps clicking.</p>
     */
    private void adjustLampSpacing(final int delta) {
        final RoadPlan plan = this.boundPlan(this.boundController());
        if (plan == null) {
            return;
        }
        // BUILT is editable only with the controller panel's switch on; without
        // that the button looked enabled (the panel greys out by state) but the
        // click was silently dropped here.
        final boolean editable = RoadRules.canEdit(plan);
        if (!editable) {
            return;
        }
        final int next = Math.max(MIN_LAMP_SPACING, Math.min(MAX_LAMP_SPACING, plan.lampSpacing() + delta));
        if (next != plan.lampSpacing()) {
            this.send(RoadPlacementMode.CONFIGURE, next);
        }
    }

    @Override
    public void onOpened() {
        super.onOpened();
        // Refresh the road index so the bound plan -- and with it the permission
        // state used for greying out buttons -- is current.
        PacketDistributor.sendToServer(new com.YLFXM.rp.crossroads.network.RequestRoadsPayload());
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        final BlockPos bound = this.boundController();
        final RoadPlan plan = this.boundPlan(bound);
        if (plan != null) RoadSelectionClient.bindTo(plan);
        this.summaryText.setText(plan == null ? Component.literal("A=-  B=-") : RoadSelectionClient.summary());
        this.boundText.setText(plan != null
                ? Component.translatable("rp_crossroads.gui.bound_line", RoadNames.label(plan))
                : bound == null ? Component.translatable("rp_crossroads.gui.unbound_line")
                        : Component.translatable("rp_crossroads.gui.bound_no_plan"));
        // The controller panel's edit switch unlocks a BUILT road: without this
        // the whole panel stays greyed out and the player cannot re-pick A/B at
        // all, even though the server would accept it.
        final boolean locked = plan != null && !RoadRules.canEdit(plan);
        // Grey out planning when the server says this player may not manage the
        // bound road; the server re-checks anyway.
        final boolean allowed = plan != null && ClientRoadCache.canManage(plan.id());
        this.stateText.setText(plan == null
                ? Component.translatable("rp_crossroads.gui.no_plan")
                : Component.translatable("rp_crossroads.gui.plan_line", plan.state().display()));
        this.lampValueButton.setText(Component.literal(plan == null ? "-" : String.valueOf(plan.lampSpacing())));
        this.lampValueButton.setEnabled(!locked && allowed);
        // Creating a blueprint is a first-time action: only a road that has not
        // been confirmed yet (DRAFT) can open the native tool. Everything after
        // that goes through Repair — replanning a built road included, whose
        // state is PLANNED and would otherwise re-open this door.
        this.planButton.setEnabled(plan != null
                && RoadRules.canCreate(plan) && allowed);
        this.mirrorButton.setEnabled(!locked && allowed);
        this.clearButton.setEnabled(!locked);
        this.autoButton.setEnabled(!locked && allowed && RoadSelectionClient.hasBoth());
        this.crossingButton.setEnabled(plan!=null && allowed && !plan.segments().isEmpty());
        this.planningText.setText(plan==null ? Component.literal("") : RoadNotices.issue(plan.planning().issue()));

        // Logged only when the combination changes. When a button stays grey the
        // reason is one of these four values, and guessing which one wasted a
        // whole test round.
        final String debug = "bound=" + bound
                + " plan=" + (plan == null ? "none" : plan.id())
                + " state=" + (plan == null ? "-" : plan.state())
                + " edit=" + (plan != null && plan.allowBlueprintEdit())
                + " locked=" + locked + " canManage=" + allowed;
        if (!debug.equals(this.lastPanelDebug)) {
            this.lastPanelDebug = debug;
            com.YLFXM.rp.crossroads.Crossroads.LOGGER.info("Wand panel: {}", debug);
        }
    }

    private void send(final RoadPlacementMode mode, final int lampSpacingOverride) {
        final BlockPos controller = this.boundController();
        final RoadPlan plan = this.boundPlan(controller);
        if (plan != null) RoadSelectionClient.bindTo(plan);
        if (!RoadSelectionClient.hasBoth()) {
            if (this.mc.player != null) {
                com.YLFXM.rp.crossroads.road.RoadNotices.actionBar(this.mc.player,"rp_crossroads.select.endpoints_first");
            }
            return;
        }
        if (controller == null || plan == null) {
            if (this.mc.player != null) {
                com.YLFXM.rp.crossroads.road.RoadNotices.actionBar(this.mc.player,"rp_crossroads.wand.not_bound_controller");
            }
            return;
        }
        if (!(mode == RoadPlacementMode.PLAN ? RoadRules.canCreate(plan) : RoadRules.canEdit(plan))) {
            if (this.mc.player != null) {
                com.YLFXM.rp.crossroads.road.RoadNotices.actionBar(this.mc.player,"rp_crossroads.road.locked");
            }
            return;
        }
        PacketDistributor.sendToServer(new ConfigureRoadPayload(controller, 1, plan.width(),
                lampSpacingOverride >= 0 ? lampSpacingOverride : plan.lampSpacing(),
                RoadSelectionClient.a(), RoadSelectionClient.b(),
                this.mirror, mode));
        // The selection is deliberately kept. The plan now exists in PLANNED
        // state and the ghost preview is still what the player wants to look
        // at; clearing A/B here dropped them straight back to the plain outline
        // box, and the preview only came back if they re-picked an endpoint.
        // The ghost goes away on its own once the road leaves PLANNED.
        //
        // A settings-only update keeps the panel open: the player is stepping a
        // number and wants to see the ghost follow, not close the window.
        if (mode != RoadPlacementMode.CONFIGURE) {
            this.close();
        }
    }

    private void sendPlanning(int action,java.util.UUID other,int mode) {
        final RoadPlan plan=this.boundPlan(this.boundController());
        if(plan==null || !RoadSelectionClient.hasBoth()) return;
        PacketDistributor.sendToServer(new com.YLFXM.rp.crossroads.network.RoadPlanningPayload(plan.id(),plan.controllerPos(),
                RoadSelectionClient.a(),RoadSelectionClient.b(),RoadSelectionClient.mirror(),action,other,mode));
    }

    private RoadPlan boundPlan(final BlockPos controller) {
        return ClientRoadCache.findByController(controller);
    }

    private BlockPos boundController() {
        // Only the wand in hand decides what this panel edits. Scanning the whole
        // inventory picked up the first *bound* wand, so holding a fresh wand still
        // showed the previous road.
        return this.mc.player == null ? null : RoadWandBinding.heldController(this.mc.player.getInventory());
    }
}
