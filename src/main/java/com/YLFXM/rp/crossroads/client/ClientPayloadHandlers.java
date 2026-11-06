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

import com.YLFXM.rp.crossroads.CustomRoadPack;
import com.YLFXM.rp.crossroads.Crossroads;
import com.YLFXM.rp.crossroads.network.BindRoadWandPayload;
import com.YLFXM.rp.crossroads.network.ClientRoadCache;
import com.YLFXM.rp.crossroads.network.OpenBuildToolPayload;
import com.YLFXM.rp.crossroads.network.OpenRoadPlanPayload;
import com.YLFXM.rp.crossroads.network.RoadPreviewPayload;
import com.YLFXM.rp.crossroads.network.RoadRemovedPayload;
import com.YLFXM.rp.crossroads.network.RoadSyncPayload;
import com.YLFXM.rp.crossroads.network.WorkOrderListPayload;
import com.YLFXM.rp.crossroads.road.RoadNotices;
import com.YLFXM.rp.crossroads.road.RoadPlan;
import com.YLFXM.rp.crossroads.road.RoadWandBinding;
import com.ldtteam.structurize.storage.StructurePackMeta;
import com.ldtteam.structurize.storage.StructurePacks;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.ListTag;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Client-bound payload handlers.
 *
 * <p>Kept in a class of its own, and in the {@code client} package, on purpose:
 * the server-only handlers live in {@code network.Network}, which the dedicated
 * server loads while constructing the mod. A method reference to
 * {@code Network::register} makes the JVM verify every method of that class, and
 * verification loads the types named in its stack map frames -- so a single
 * {@code Minecraft}/{@code LocalPlayer} local there aborts startup with
 * {@code Attempted to load class net/minecraft/client/player/LocalPlayer for
 * invalid dist DEDICATED_SERVER}. Nothing in this file may be referenced from
 * common code except through {@link #register}, which is only called when
 * {@code FMLEnvironment.dist} is {@code CLIENT}.</p>
 */
public final class ClientPayloadHandlers {
    /**
     * Rendering-cache slot the native build-tool window renders into.
     *
     * <p>Hard-coded to {@code "blueprint"} because that is what
     * {@code WindowExtendedBuildTool} passes as its blueprint id to
     * {@code AbstractBlueprintManipulationWindow}. Our own planning ghost uses a
     * different key, so the two never fight over one slot.</p>
     */
    private static final String BUILD_TOOL_PREVIEW_KEY = "blueprint";

    /**
     * Registers every client-bound payload. Call only on the physical client.
     *
     * <p>Takes the registrar created by {@code Network.register} instead of
     * asking the event for a new one: the channel version ({@code "13"}) is
     * declared once per mod, and a second registrar would try to register the
     * same versions again.</p>
     */
    public static void register(final PayloadRegistrar registrar) {
        registrar.playToClient(RoadSyncPayload.TYPE, RoadSyncPayload.CODEC, ClientPayloadHandlers::handleSync);
        registrar.playToClient(OpenRoadPlanPayload.TYPE, OpenRoadPlanPayload.CODEC,
                ClientPayloadHandlers::handleOpenPlan);
        registrar.playToClient(BindRoadWandPayload.TYPE, BindRoadWandPayload.CODEC,
                ClientPayloadHandlers::handleBindWand);
        registrar.playToClient(OpenBuildToolPayload.TYPE, OpenBuildToolPayload.CODEC,
                ClientPayloadHandlers::handleOpenBuildTool);
        registrar.playToClient(WorkOrderListPayload.TYPE, WorkOrderListPayload.CODEC,
                ClientPayloadHandlers::handleWorkOrderList);
        registrar.playToClient(RoadPreviewPayload.TYPE, RoadPreviewPayload.CODEC,
                ClientPayloadHandlers::handlePreview);
        registrar.playToClient(RoadRemovedPayload.TYPE, RoadRemovedPayload.CODEC,
                ClientPayloadHandlers::handleRemoved);
    }

    private static void handlePreview(final RoadPreviewPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> ClientRoadPreviewRenderer.accept(payload));
    }

    private static void handleRemoved(final RoadRemovedPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            final Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || !mc.level.dimension().location().toString().equals(payload.dimension())) return;
            ClientRoadCache.remove(payload.roadId());
            RoadSelectionClient.removed(payload.roadId());
            if (mc.player != null) for (final var stack : RoadWandBinding.wands(mc.player.getInventory())) {
                if (payload.controller().equals(RoadWandBinding.getBoundController(stack))) {
                    RoadWandBinding.unbind(stack);
                }
            }
        });
    }

    private static void handleSync(final RoadSyncPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientRoadCache.set(payload.data());
            final java.util.Set<java.util.UUID> manageable = new java.util.HashSet<>();
            for (final RoadPlan road : ClientRoadCache.roads()) if (ClientRoadCache.canManage(road.id())) manageable.add(road.id());
            RoadSelectionClient.retain(ClientRoadCache.roads(), manageable, ClientRoadCache.dimension());
            // Drop local blueprint copies the server no longer vouches for. This
            // only ever removes files under generated/ that this client is not
            // supposed to see, never anything the player is entitled to.
            final ListTag allowed = payload.data().getList("blueprints", 8);
            final java.util.Set<String> keep = new java.util.HashSet<>();
            for (int i = 0; i < allowed.size(); i++) {
                keep.add(allowed.getString(i));
            }
            ClientRoadPackWriter.retainOnly(keep);
        });
    }

    private static void handleBindWand(final BindRoadWandPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            final var player = Minecraft.getInstance().player;
            if (player != null) {
                RoadWandBinding.rebind(
                        player.getInventory(), payload.previousPos(), payload.controllerPos());
            }
        });
    }

    private static void handleOpenBuildTool(final OpenBuildToolPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            final var minecraft = Minecraft.getInstance();
            if (minecraft.level == null || minecraft.player == null) {
                return;
            }
            // Nothing to build with. Opening the native tool anyway would put a
            // panel in front of a player who cannot place anything (the placement
            // path itself never checks the held item), so say so and stop before
            // any client state is touched -- no pack written, no pack switched.
            if (findBuildToolSlot(minecraft.player.getInventory()) < 0) {
                RoadNotices.chat(minecraft.player, null, "no_tool", "rp_crossroads.plan.no_build_tool");
                ClientNativeBuildTracker.abort(payload.planId(),payload.repairToken());
                return;
            }
            // The server pushes the blueprint with the payload: packs are only
            // synced on join, so a freshly generated one does not exist here yet.
            ClientRoadPackWriter.ensure(
                    payload.blueprintPath(), payload.blueprint(), payload.packVersion());
            // The selected pack is a global: whichever pack is selected when a
            // building is placed or a colony is created is the one MineColonies
            // records in the save. Switching to the road pack therefore has to be
            // undone as soon as the window closes, or the colony ends up with
            // selected road pack as its style pack and looks for
            // fundamentals/townhall1 inside our pack on every startup.
            final StructurePackMeta previous = StructurePacks.getSelectedPack();
            ClientNativeBuildTracker.begin(
                    payload.planId(), previous == null ? CustomRoadPack.STYLE_PACK : previous.getName(), payload.repairToken());
            StructurePacks.switchSelectedPack(CustomRoadPack.PACK_NAME);
            // The native window draws its own ghost for the same blueprint, so
            // ours has to go or the two would stack.
            ClientRoadPreviewRenderer.clear();
            // Where the window puts its box is decided once, from the preview
            // cache, and only when that cache has no position yet -- a position
            // left over from an earlier open would win over the anchor we pass.
            // Clearing it first makes the anchor below authoritative.
            com.ldtteam.structurize.storage.rendering.RenderingCache
                    .getOrCreateBlueprintPreviewData(BUILD_TOOL_PREVIEW_KEY)
                    .setPos(null);
            // The blueprint we just pushed may have the same name, file name,
            // path and volume as the previous one — a re-planned road keeps all
            // of them — and Blueprint.equals ignores the blocks entirely. The
            // native tool would therefore draw the previously cached mesh: same
            // shape as before, only moved. Drop the meshes so it is rebuilt from
            // the blueprint actually on disk.
            com.ldtteam.structurize.client.BlueprintHandler.getInstance().clearCache();
            // The anchor is the blueprint's primary block (the road controller's
            // support block). Passing null here makes Structurize fall back to
            // "ten blocks in front of the player", which is why the box used to
            // drift off in whatever direction the player was facing.
            try {
                if (payload.repairToken().equals(com.YLFXM.rp.crossroads.road.RoadNativeBuildTool.NO_SESSION))
                    throw new IllegalArgumentException("Road build tool requires an authorized construction session");
                new WindowRoadRepairBuildTool(payload,minecraft.level.registryAccess()).open();
            } catch (final RuntimeException ex) {
                Crossroads.LOGGER.error("Could not open native road build tool",ex);
                ClientNativeBuildTracker.abort(payload.planId(),payload.repairToken());
                return;
            }
            // Hand the player the tool they are about to build with. This is
            // presentation only: the placement path never checks the held item.
            selectBuildTool(minecraft);
        });
    }

    /**
     * First inventory slot holding the Structurize build tool, or -1.
     *
     * <p>Takes the {@code Inventory}, not the player: passing
     * {@code Minecraft.player} (a {@code LocalPlayer}) as an argument would make
     * the JVM verifier load that client-only class, which the dist cleaner
     * rejects on a dedicated server -- the whole mod fails to construct with a
     * {@code BootstrapMethodError} pointing at {@code Network::register}.</p>
     *
     * <p>Scans the whole inventory: slots 0-8 are the hotbar and can simply be
     * selected, anything above has to be swapped into the selected slot to be
     * reachable at all.</p>
     */
    private static int findBuildToolSlot(final net.minecraft.world.entity.player.Inventory inventory) {
        return com.YLFXM.rp.crossroads.road.RoadNativeBuildTool.findToolSlot(inventory);
    }

    /**
     * Points the player's selected hotbar slot at the Structurize build tool.
     *
     * <p>If the tool is somewhere in the backpack it cannot be selected at all, so
     * it is swapped into the selected slot — the one case where items move. The
     * caller has already verified a tool exists.</p>
     */
    private static void selectBuildTool(final Minecraft minecraft) {
        if (minecraft.player == null) {
            return;
        }
        final net.minecraft.world.entity.player.Inventory inventory = minecraft.player.getInventory();
        final int found = findBuildToolSlot(minecraft.player.getInventory());
        if (found < 0) {
            return;
        }
        if (found <= 8) {
            inventory.selected = found;
            return;
        }
        final int selected = inventory.selected;
        final net.minecraft.world.item.ItemStack displaced = inventory.getItem(selected);
        inventory.setItem(selected, inventory.getItem(found));
        inventory.setItem(found, displaced);
    }

    private static void handleWorkOrderList(final WorkOrderListPayload payload,
                                            final IPayloadContext context) {
        context.enqueueWork(() -> WindowWorkOrderList
                .show(payload.colonyId(), payload.colonyName(), payload.entries(),
                        payload.bound(), payload.canManage()));
    }

    private static void handleOpenPlan(final OpenRoadPlanPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            final var minecraft = Minecraft.getInstance();
            if (minecraft.level == null) return;
            if (payload.nativeWindow()) {
                new com.minecolonies.core.client.gui.WindowDecorationController(payload.controllerPos()).open();
                return;
            }
            if (!payload.plan().hasUUID("id")) return;
            final RoadPlan approved = RoadPlan.load(payload.plan());
            if (!payload.controllerPos().equals(approved.controllerPos())
                    || !minecraft.level.dimension().equals(approved.dimension())) return;
            ClientRoadCache.rememberAuthorized(approved);
            RoadSelectionClient.setControllerPos(payload.controllerPos());
            new WindowRoadController(payload.controllerPos()).open();
        });
    }

    private ClientPayloadHandlers() {
    }
}
