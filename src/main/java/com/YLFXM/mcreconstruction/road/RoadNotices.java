/* SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 ZhuanZ (YLFXM) */
package com.YLFXM.mcreconstruction.road;

import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Player-facing notices share one throttle so a repeated failure is reported once, not per attempt. */
public final class RoadNotices {
    private static final long WINDOW=10_000L;
    private static final Map<String,Long> SENT=new HashMap<>();
    public static void send(ServerPlayer player,UUID road,String key,Component message) {
        if(player==null)return;
        String dedupe=player.getUUID()+":"+(road==null ? "-" : road.toString())+":"+key;
        long now=System.currentTimeMillis();
        Long previous=SENT.get(dedupe);
        if(previous!=null && now-previous<WINDOW)return;
        SENT.put(dedupe,now);
        if(SENT.size()>256)SENT.entrySet().removeIf(entry -> now-entry.getValue()>WINDOW);
        player.displayClientMessage(message,false);
    }
    /** A successful action clears its key so the next real failure is still reported. */
    public static void clear(ServerPlayer player,UUID road,String key) {
        if(player==null)return;
        SENT.remove(player.getUUID()+":"+(road==null ? "-" : road.toString())+":"+key);
    }
    private RoadNotices() { }
}
