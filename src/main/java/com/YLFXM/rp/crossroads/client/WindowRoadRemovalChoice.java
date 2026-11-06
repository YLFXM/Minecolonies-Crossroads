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

import com.YLFXM.rp.crossroads.Crossroads;
import com.ldtteam.blockui.controls.Button;
import com.minecolonies.core.client.gui.AbstractWindowSkeleton;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/** One removal entry point: survival dispatches immediately, creative chooses a native method. */
public final class WindowRoadRemovalChoice extends AbstractWindowSkeleton {
    private final Consumer<Boolean> action;
    private final Button instantButton;
    private boolean submitted;

    public static void choose(AbstractWindowSkeleton parent,Consumer<Boolean> action) {
        var player=Minecraft.getInstance().player;
        if(player==null) return;
        if(!player.isCreative()) { action.accept(false); return; }
        new WindowRoadRemovalChoice(parent,action).open();
    }
    private WindowRoadRemovalChoice(AbstractWindowSkeleton parent,Consumer<Boolean> action) {
        super(parent,ResourceLocation.fromNamespaceAndPath(Crossroads.MODID,"gui/windowroadremovalchoice.xml"));
        this.action=action;
        this.instantButton=findPaneOfTypeByID("instant",Button.class);
        for(String id:new String[]{"builder","instant","cancel"})
            findPaneOfTypeByID(id,Button.class).setColors(0x000000,0x9A9A9A,0x000000);
        registerButton("builder",ignored -> submit(false));
        registerButton("instant",ignored -> submit(true));
        registerButton("cancel",ignored -> close());
    }
    @Override public void onUpdate() {
        super.onUpdate();
        this.instantButton.setEnabled(!submitted && this.mc.player!=null && this.mc.player.isCreative());
    }
    private void submit(boolean instant) {
        if(submitted || this.mc.player==null || (instant && !this.mc.player.isCreative())) return;
        submitted=true;
        close();
        action.accept(instant);
    }
}
