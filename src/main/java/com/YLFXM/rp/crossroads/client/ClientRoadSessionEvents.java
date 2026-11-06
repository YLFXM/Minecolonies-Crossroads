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

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

@EventBusSubscriber(modid="rp_crossroads", value=Dist.CLIENT)
public final class ClientRoadSessionEvents {
    @SubscribeEvent public static void login(final ClientPlayerNetworkEvent.LoggingIn event) {
        // Structurize scanned blueprints/ at startup, so the pre-rename copy is
        // already registered by now; drop both the files and the meta.
        ClientRoadPackWriter.purgeStalePack();
    }

    @SubscribeEvent public static void logout(final ClientPlayerNetworkEvent.LoggingOut event) {
        ClientNativeBuildTracker.reset();
        RoadSelectionClient.clearAll(); ClientRoadPreviewRenderer.clear(); ClientRoadPreview.reset();
        com.YLFXM.rp.crossroads.network.ClientRoadCache.clear();
    }
}
