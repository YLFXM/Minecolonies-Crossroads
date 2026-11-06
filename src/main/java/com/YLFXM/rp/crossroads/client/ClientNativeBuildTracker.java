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

import com.ldtteam.blockui.BOScreen;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.client.gui.WindowExtendedBuildTool;
import com.ldtteam.structurize.storage.StructurePacks;
import com.YLFXM.rp.crossroads.CustomRoadPack;
import com.YLFXM.rp.crossroads.Crossroads;
import com.YLFXM.rp.crossroads.network.NativeBuildClosedPayload;
import java.util.List;
import java.util.UUID;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.client.Minecraft;

/**
 * Watches for the native build-tool window to open and close.
 *
 * <p>Its job is undoing the pack switch made to open that window: the selected
 * structure pack is a global that MineColonies writes into the save whenever a
 * building is placed or a colony is created, so leaving the road pack selected
 * after the window closes records it as a colony's style pack — which then makes
 * every later startup look for {@code fundamentals/townhall1} inside our pack.</p>
 *
 * <p>It also drops the window straight into our {@code generated} folder, so the
 * road that was just planned is the first thing on screen instead of the player
 * having to walk the pack tree to find it. The blueprint is deliberately
 * <b>not</b> selected for them: without mixins that would mean reaching into the
 * window's selection state, so the folder is opened and the player clicks.</p>
 */
@EventBusSubscriber(modid = Crossroads.MODID, value = Dist.CLIENT)
public final class ClientNativeBuildTracker {
    /** Folder our generated road blueprints live in, inside the road pack. */
    private static final String GENERATED = "generated";
    /**
     * How many client ticks to keep trying to open the folder. The pack file is
     * written asynchronously, so the first ticks after the window opens can
     * still see an empty folder; roughly two seconds is plenty.
     */
    private static final int FOLDER_ATTEMPTS = 40;

    private static UUID activePlan;
    private static UUID activeRepair=com.YLFXM.rp.crossroads.road.RoadNativeBuildTool.NO_SESSION;
    private static int openingAttempts;
    /** Pack that was selected before the road pack took over; restored on close. */
    private static String previousPackName;
    private static boolean nativeWindowSeen;
    /** True once the folder was opened, or once we stopped trying. */
    private static boolean folderOpened;
    private static int folderAttempts;

    public static boolean isActive() { return activePlan != null; }

    public static void begin(final UUID planId, final String previousPack, final UUID repairToken) {
        activeRepair=repairToken; openingAttempts=0;
        activePlan = planId;
        previousPackName = previousPack;
        nativeWindowSeen = false;
        folderOpened = false;
        folderAttempts = 0;
    }

    /**
     * Puts the previously selected pack back. Nothing recorded the road pack as a
     * style in the meantime, so this is always the pack the player last chose.
     */
    public static void restorePack() {
        if (previousPackName == null) {
            return;
        }
        final String restore = previousPackName;
        previousPackName = null;
        // A null name means nothing was selected, and leaving the road pack there
        // would let Structurize hand it back as a default pick. Fall back to the
        // style pack the road units are authored against.
        final String name = StructurePacks.getStructurePack(restore) != null
                ? restore
                : CustomRoadPack.STYLE_PACK;
        StructurePacks.switchSelectedPack(name);
    }

    @SubscribeEvent
    public static void onClientTick(final ClientTickEvent.Post event) {
        if (activePlan == null) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof BOScreen screen)
                || !(screen.getWindow() instanceof WindowExtendedBuildTool window)) {
            if (nativeWindowSeen || ++openingAttempts > 40) {
                PacketDistributor.sendToServer(new NativeBuildClosedPayload(activePlan,activeRepair));
                activePlan = null;
                nativeWindowSeen = false;
                folderOpened = false;
                restorePack();
            }
            return;
        }
        nativeWindowSeen = true;
        if (folderOpened || minecraft.level == null) {
            return;
        }
        if (++folderAttempts > FOLDER_ATTEMPTS) {
            // Give up quietly: the player is already navigating the list.
            folderOpened = true;
            return;
        }
        final List<Blueprint> generated = StructurePacks.getBlueprints(
                CustomRoadPack.PACK_NAME, GENERATED, minecraft.level.registryAccess());
        if (generated.isEmpty()) {
            return;
        }
        // Lands the list straight in our generated folder. Deliberately not
        // selecting a blueprint: that needs the window's private selection state,
        // and picking one for the player is not what was asked for.
        window.updateBlueprints(generated, GENERATED);
        folderOpened = true;
    }

    public static void abort(final UUID plan, final UUID token) {
        PacketDistributor.sendToServer(new NativeBuildClosedPayload(plan,token));
        activePlan=null; nativeWindowSeen=false; restorePack();
    }
    public static void reset() { activePlan=null; nativeWindowSeen=false; previousPackName=null; }

    private ClientNativeBuildTracker() {
    }
}
