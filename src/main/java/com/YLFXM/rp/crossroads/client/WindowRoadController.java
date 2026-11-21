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
import com.YLFXM.rp.crossroads.network.ConfigureRoadPayload;
import com.YLFXM.rp.crossroads.network.RequestUnitStylesPayload;
import com.YLFXM.rp.crossroads.network.RoadControllerActionPayload;
import com.YLFXM.rp.crossroads.road.RoadNames;
import com.YLFXM.rp.crossroads.road.RoadNotices;
import com.YLFXM.rp.crossroads.road.RoadPlacementMode;
import com.YLFXM.rp.crossroads.road.RoadPlan;
import com.YLFXM.rp.crossroads.road.RoadState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The one road panel: everything about this controller's road lives here.
 *
 * <p>Build (or upgrade) / repair / edit blueprint / crossings / remove, plus the
 * unit set and the two construction settings. The wand is a pure gesture tool
 * now, so this window is the only place these actions are offered.</p>
 *
 * <p>It is bound to the controller it was opened from, not to the wand: a
 * player may carry no wand at all, and what they are looking at is this road.
 * The two settings (lamp spacing, mirror) still go through the same
 * {@code ConfigureRoadPayload} the wand used — the server rebuilds the proposal
 * from the road's own endpoints, so nothing here needs a selection of its own.</p>
 */
public class WindowRoadController extends AbstractWindowSkeleton {
    /** Lamp spacing the panel can reach; 0 would mean "no lamps at all". */
    private static final int MIN_LAMP_SPACING = 1;
    private static final int MAX_LAMP_SPACING = 8;

    private final BlockPos controllerPos;
    private final Text nameText;
    private final Text stateText;
    private final Text detailText;
    private final Text issueText;
    private final Button buildButton;
    private final Button repairButton;
    private final Button editButton;
    private final Button crossingButton;
    private final Button removeButton;
    private final Button styleButton;
    private final Button seriesButton;
    private final Button lampValueButton;
    private final Button mirrorButton;

    public WindowRoadController(final BlockPos controllerPos) {
        super(ResourceLocation.fromNamespaceAndPath(Crossroads.MODID, "gui/windowroadcontroller.xml"));
        this.controllerPos = controllerPos;
        this.nameText = this.findPaneOfTypeByID("name", Text.class);
        this.stateText = this.findPaneOfTypeByID("state", Text.class);
        this.detailText = this.findPaneOfTypeByID("detail", Text.class);
        this.issueText = this.findPaneOfTypeByID("issue", Text.class);
        this.buildButton = this.findPaneOfTypeByID("build", Button.class);
        this.repairButton = this.findPaneOfTypeByID("repair", Button.class);
        this.editButton = this.findPaneOfTypeByID("edit", Button.class);
        this.crossingButton = this.findPaneOfTypeByID("crossings", Button.class);
        this.removeButton = this.findPaneOfTypeByID("remove", Button.class);
        this.styleButton = this.findPaneOfTypeByID("style", Button.class);
        this.seriesButton = this.findPaneOfTypeByID("series", Button.class);
        this.lampValueButton = this.findPaneOfTypeByID("lampValue", Button.class);
        this.mirrorButton = this.findPaneOfTypeByID("mirror", Button.class);
        // BlockUI derives the disabled colour from "color" when only that is set,
        // so disabled buttons would look identical to enabled ones. Force a grey.
        for (final Button button : new Button[]{this.buildButton, this.repairButton, this.editButton,
                this.crossingButton, this.removeButton, this.styleButton, this.seriesButton,
                this.lampValueButton, this.mirrorButton,
                this.findPaneOfTypeByID("lampDown", Button.class), this.findPaneOfTypeByID("lampUp", Button.class)}) {
            button.setColors(0x000000, 0x9A9A9A, 0x000000);
        }

        this.registerButton("build", button -> this.buildOrUpgrade());
        this.registerButton("repair", button -> this.send(RoadControllerActionPayload.Action.REPAIR));
        // Style and series are two entry points into one picker: the player is
        // already looking at this road when they decide it should look different.
        this.registerButton("style", button -> WindowRoadStyleList.open(this, this.controllerPos));
        this.registerButton("series", button -> WindowRoadStyleList.open(this, this.controllerPos));
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
        this.registerButton("crossings", button -> {
            final RoadPlan plan = this.plan();
            if (plan == null) {
                return;
            }
            // The picker returns here, so it needs the controller it came from.
            this.close();
            new WindowRoadCrossings(plan.id(), this.controllerPos).open();
        });
        // Demolition is one packet: whether a builder takes it or the native
        // queue runs is the server's call, so no window opens here. In the
        // developer switch the server answers with a method prompt instead.
        this.registerButton("remove", button -> this.send(RoadControllerActionPayload.Action.REMOVE));
        this.registerButton("lampDown", button -> this.adjustLampSpacing(-1));
        this.registerButton("lampUp", button -> this.adjustLampSpacing(1));
        this.registerButton("mirror", button -> this.toggleMirror());
    }

    @Override
    public void onOpened() {
        super.onOpened();
        // The picker's list comes from the server, which is the only side that
        // knows which styles this save's pack holds. Asking here means the list
        // is ready by the time the player presses either entry button.
        PacketDistributor.sendToServer(new RequestUnitStylesPayload());
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        final RoadPlan plan = this.plan();
        if (plan != null) RoadSelectionClient.bindTo(plan);
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
            final Component worker = builderLine(plan);
            if (worker != null) {
                detail.append(worker).append("  ·  ");
            } else if (plan.isRepairBusy()) {
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
        this.issueText.setText(plan == null ? Component.empty() : RoadNotices.issue(plan.planning().issue()));

        final boolean canManage = plan != null && ClientRoadCache.canManage(plan.id());
        // The panel decides both the gate and the label from the series' shipped
        // tiers, so a series with three tiers cannot offer a fourth.
        final int maxLevel = plan == null ? 0
                : com.YLFXM.rp.crossroads.road.RoadUnitLibrary.maxLevel(plan.unitStyle(), plan.series());
        final boolean editable = plan != null && RoadRules.canEdit(plan);
        // Construction in flight: a session, a dispatch that is BUILDING, or a
        // demolition. Build/upgrade is a first-time or tier-only action, so none
        // of those may be started on top of one.
        final boolean working = plan != null && (plan.isRepairBusy()
                || plan.state() == RoadState.BUILDING || plan.state() == RoadState.REMOVING);
        final boolean draft = plan != null && plan.state() == RoadState.DRAFT;

        if (working) {
            this.buildButton.setEnabled(false);
            this.buildButton.setText(Component.translatable("rp_crossroads.gui.working"));
        } else if (draft) {
            this.buildButton.setEnabled(canManage && RoadRules.canCreate(plan));
            this.buildButton.setText(Component.translatable("rp_crossroads.gui.build"));
        } else if (plan != null) {
            this.buildButton.setEnabled(canManage && RoadRules.canUpgrade(plan, maxLevel));
            this.buildButton.setText(plan.unitLevel() >= maxLevel
                    ? Component.translatable("rp_crossroads.gui.upgrade_max", plan.unitLevel())
                    : Component.translatable("rp_crossroads.gui.upgrade_to", plan.unitLevel() + 1));
        } else {
            this.buildButton.setEnabled(false);
            this.buildButton.setText(Component.translatable("rp_crossroads.gui.build"));
        }
        this.repairButton.setEnabled(canManage && RoadRules.canRepair(plan));
        this.editButton.setEnabled(canManage && RoadRules.canToggleEdit(plan));
        this.editButton.setText(Component.translatable(plan != null && plan.allowBlueprintEdit()
                ? "rp_crossroads.gui.edit_blueprint_on" : "rp_crossroads.gui.edit_blueprint_off"));
        this.crossingButton.setEnabled(canManage && editable && plan != null && !plan.segments().isEmpty());
        this.removeButton.setEnabled(canManage && RoadRules.canRemove(plan));
        // A unit switch rewrites the road, so it waits for whatever is running.
        this.styleButton.setEnabled(canManage && !working);
        this.styleButton.setText(plan == null
                ? Component.translatable("rp_crossroads.gui.style_entry")
                : Component.translatableWithFallback("rp_crossroads.style." + plan.unitStyle(), plan.unitStyle()));
        this.seriesButton.setEnabled(canManage && !working);
        this.seriesButton.setText(plan == null
                ? Component.translatable("rp_crossroads.gui.series")
                : Component.translatableWithFallback("rp_crossroads.series." + plan.series(), plan.series())
                        .append("  " + plan.unitLevel() + "/" + maxLevel));
        this.lampValueButton.setEnabled(editable && canManage);
        this.lampValueButton.setText(Component.literal(plan == null ? "-" : String.valueOf(plan.lampSpacing())));
        this.findPaneOfTypeByID("lampDown", Button.class).setEnabled(editable && canManage);
        this.findPaneOfTypeByID("lampUp", Button.class).setEnabled(editable && canManage);
        this.mirrorButton.setEnabled(editable && canManage);
        this.mirrorButton.setText(Component.translatable(plan != null && plan.mirror()
                ? "rp_crossroads.gui.mirror_on" : "rp_crossroads.gui.mirror_off"));
    }

    /**
     * Who is working on the road right now, with the stage.
     *
     * <p>Reads the sync-only fields the server fills in ({@code builder} / {@code builderName} /
     * {@code builderPhase}); returns {@code null} when nobody is working, so the caller can
     * fall back to the plain "busy" line for plans synced before this field existed.</p>
     */
    private static Component builderLine(final RoadPlan plan) {
        if (plan == null || plan.builderPhase().isEmpty()) {
            return null;
        }
        final net.minecraft.world.entity.player.Player self = Minecraft.getInstance().player;
        final boolean mine = self != null && self.getUUID().equals(plan.builder());
        final Component who = mine
                ? Component.translatable("rp_crossroads.gui.you")
                : Component.literal(plan.builderName().isEmpty() ? "?" : plan.builderName());
        if ("REMOVING".equals(plan.builderPhase())) {
            return Component.translatable("rp_crossroads.gui.removing_by", who);
        }
        return Component.translatable("rp_crossroads.gui.building_by", who,
                Component.translatable("PREPARED".equals(plan.builderPhase())
                        ? "rp_crossroads.gui.phase_preparing" : "rp_crossroads.gui.phase_building"));
    }

    /** One button, two actions: a draft is built, anything already planned is upgraded. */
    private void buildOrUpgrade() {
        final RoadPlan plan = this.plan();
        if (plan == null) {
            return;
        }
        if (RoadRules.canCreate(plan)) {
            this.sendPlan(plan);
            return;
        }
        this.send(RoadControllerActionPayload.Action.UPGRADE);
    }

    /**
     * First construction: hand the two endpoints to the server, which freezes the
     * road and opens the native tool.
     *
     * <p>The endpoints come from the local selection, which
     * {@link RoadSelectionClient#bindTo} keeps in step with this plan — the same
     * values the gesture set when the player picked them. Without both there is
     * nothing to build, and the server would reject the request anyway.</p>
     */
    private void sendPlan(final RoadPlan plan) {
        if (!RoadRules.canCreate(plan)) {
            if (this.mc.player != null) {
                RoadNotices.actionBar(this.mc.player, "rp_crossroads.road.locked");
            }
            return;
        }
        // Creative hands the blocks to the player, and the native build tool has no
        // tier picker for our unit files, so the tier has to be asked for now.
        // Survival roads start at tier 1 and are upgraded by a builder later.
        if (this.mc.player != null && this.mc.player.isCreative()
                && com.YLFXM.rp.crossroads.road.RoadUnitLibrary
                        .maxLevel(plan.unitStyle(), plan.series()) > 1) {
            WindowRoadTier.open(this, plan, tier -> this.sendConfigure(plan, RoadPlacementMode.PLAN, -1, tier));
            return;
        }
        this.sendConfigure(plan, RoadPlacementMode.PLAN, -1, -1);
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
        final RoadPlan plan = this.plan();
        if (plan == null || !RoadRules.canEdit(plan) || !ClientRoadCache.canManage(plan.id())) {
            return;
        }
        final int next = Math.max(MIN_LAMP_SPACING, Math.min(MAX_LAMP_SPACING, plan.lampSpacing() + delta));
        if (next != plan.lampSpacing()) {
            this.sendConfigure(plan, RoadPlacementMode.CONFIGURE, next, -1);
        }
    }

    /**
     * Flips the road's mirror and stores it immediately.
     *
     * <p>Mirror used to be a local flag the next build read. That left the plan
     * and the ghost disagreeing until something else re-published, so the flag
     * is sent with the other settings now: the server rebuilds the proposal from
     * the road's own endpoints and writes {@code mirror} into the plan.</p>
     */
    private void toggleMirror() {
        final RoadPlan plan = this.plan();
        if (plan == null || !RoadRules.canEdit(plan) || !ClientRoadCache.canManage(plan.id())) {
            return;
        }
        RoadSelectionClient.setMirror(!plan.mirror());
        this.sendConfigure(plan, RoadPlacementMode.CONFIGURE, -1, -1);
    }

    /**
     * @param lampSpacingOverride the new spacing, or -1 to keep the plan's.
     * @param tier               the tier picked in the creative prompt, or -1 to keep it.
     */
    private void sendConfigure(final RoadPlan plan, final RoadPlacementMode mode,
                               final int lampSpacingOverride, final int tier) {
        // The server rebuilds the proposal from these two endpoints, and for a
        // DRAFT road it does not require them — without them it would build a
        // one-block stub from the anchor and quietly replace the draft's
        // geometry. Refusing here is what the wand window always did.
        if (!RoadSelectionClient.hasBoth()) {
            if (this.mc.player != null) {
                RoadNotices.actionBar(this.mc.player, "rp_crossroads.select.endpoints_first");
            }
            return;
        }
        PacketDistributor.sendToServer(new ConfigureRoadPayload(this.controllerPos, 1, plan.width(),
                lampSpacingOverride >= 0 ? lampSpacingOverride : plan.lampSpacing(),
                RoadSelectionClient.a(), RoadSelectionClient.b(),
                RoadSelectionClient.mirror(), mode, tier));
        // A settings-only update keeps the panel open: the player is stepping a
        // number or flipping a switch and wants to see the ghost follow.
        if (mode != RoadPlacementMode.CONFIGURE) {
            this.close();
        }
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
