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
import com.YLFXM.rp.crossroads.network.CancelWorkOrdersPayload;
import com.YLFXM.rp.crossroads.network.WorkOrderListPayload;
import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.views.ScrollingList;
import com.minecolonies.core.client.gui.AbstractWindowSkeleton;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Colony work-order list opened by the Work Order Scroll. Read-only rows plus
 * multi-select and cancel, mirroring the Road Surveyor window.
 */
public class WindowWorkOrderList extends AbstractWindowSkeleton {
    /** The single live instance, so a refreshed list updates it in place. */
    private static WindowWorkOrderList current;

    private final Set<Integer> selected = new HashSet<>();
    private final ScrollingList orderList;
    private final Text subtitle;
    private final Button cancelButton;
    private int colonyId;
    private String colonyName;
    private boolean bound;
    private boolean canManage;
    private List<WorkOrderListPayload.Entry> entries;

    /**
     * Opens the window, or refreshes the open one. The server re-sends the list
     * after a cancel, so this is both the "open" and "update" entry point.
     */
    public static void show(final int colonyId, final String colonyName,
                            final List<WorkOrderListPayload.Entry> entries,
                            final boolean bound, final boolean canManage) {
        if (current != null) {
            current.apply(colonyId, colonyName, entries, bound, canManage);
            return;
        }
        final WindowWorkOrderList window =
                new WindowWorkOrderList(colonyId, colonyName, entries, bound, canManage);
        current = window;
        window.open();
    }

    public WindowWorkOrderList(final int colonyId, final String colonyName,
                               final List<WorkOrderListPayload.Entry> entries,
                               final boolean bound, final boolean canManage) {
        super(ResourceLocation.fromNamespaceAndPath(Crossroads.MODID, "gui/windowworkorderlist.xml"));
        // Fields must be populated before the data provider is installed:
        // BlockUI queries getElementCount() as soon as the provider is set.
        this.colonyId = colonyId;
        this.colonyName = colonyName;
        this.bound = bound;
        this.canManage = canManage;
        this.entries = entries == null ? List.of() : entries;

        this.subtitle = this.findPaneOfTypeByID("subtitle", Text.class);
        this.orderList = this.findPaneOfTypeByID("orders", ScrollingList.class);
        this.cancelButton = this.findPaneOfTypeByID("cancelOrders", Button.class);
        // "color" alone is reused by BlockUI for the disabled state, so a
        // disabled button would look enabled; give it an explicit grey.
        this.cancelButton.setColors(0x000000, 0x9A9A9A, 0x000000);
        this.updateTitle();

        this.orderList.setDataProvider(new ScrollingList.DataProvider() {
            @Override
            public int getElementCount() {
                final List<WorkOrderListPayload.Entry> list = WindowWorkOrderList.this.entries;
                return list == null ? 0 : list.size();
            }

            @Override
            public void updateElement(final int index, final Pane row) {
                final List<WorkOrderListPayload.Entry> list = WindowWorkOrderList.this.entries;
                if (list == null || index < 0 || index >= list.size()) {
                    return;
                }
                final WorkOrderListPayload.Entry entry = list.get(index);
                final boolean checked = WindowWorkOrderList.this.selected.contains(entry.id());
                final Button select = row.findPaneOfTypeByID("select", Button.class);
                select.setText(Component.literal(checked ? "[x]" : "[ ]"));
                select.setHandler(button -> {
                    if (!WindowWorkOrderList.this.selected.add(entry.id())) {
                        WindowWorkOrderList.this.selected.remove(entry.id());
                    }
                    WindowWorkOrderList.this.refresh();
                });
                row.findPaneOfTypeByID("orderName", Text.class).setText(nameComponent(entry));
                row.findPaneOfTypeByID("orderDetail", Text.class).setText(Component.translatable(
                        "rp_crossroads.gui.order_detail", typeComponent(entry.type()), entry.priority(),
                        statusComponent(entry), entry.currentLevel(), entry.targetLevel()));
                row.findPaneOfTypeByID("orderLocation", Text.class).setText(Component.literal(
                        entry.location()));
            }
        });

        this.registerButton("selectAll", this::selectAll);
        this.registerButton("clear", this::clearSelection);
        this.registerButton("close", button -> this.close());
        this.registerButton("cancelOrders", button -> this.cancelSelected());
    }

    @Override
    public void onOpened() {
        super.onOpened();
        this.refresh();
    }

    @Override
    public void onClosed() {
        if (current == this) {
            current = null;
        }
        super.onClosed();
    }

    private void apply(final int colonyId, final String colonyName,
                       final List<WorkOrderListPayload.Entry> entries,
                       final boolean bound, final boolean canManage) {
        this.colonyId = colonyId;
        this.colonyName = colonyName;
        this.bound = bound;
        this.canManage = canManage;
        this.entries = entries == null ? List.of() : entries;
        this.updateTitle();
        this.refresh();
    }

    private void updateTitle() {
        this.findPaneOfTypeByID("title", Text.class)
                .setText(this.bound
                        ? Component.translatable("rp_crossroads.gui.work_orders_bound", this.colonyName)
                        : Component.translatable("rp_crossroads.gui.work_orders_scroll"));
    }

    /** Refreshes counts and rows; only safe once the window is open. */
    private void refresh() {
        if (this.orderList == null || this.entries == null) {
            return;
        }
        this.selected.removeIf(id -> this.entries.stream().noneMatch(entry -> entry.id() == id));
        if (this.subtitle != null) {
            this.subtitle.setText(this.bound
                    ? Component.translatable("rp_crossroads.gui.work_orders_summary",
                            this.entries.size(), this.selected.size())
                    : Component.translatable("rp_crossroads.gui.work_orders_unbound"));
        }
        if (this.cancelButton != null) {
            // Greyed out instead of a chat prompt: the colony permission system
            // already tells the player when they may not manage this colony.
            this.cancelButton.setEnabled(this.bound && this.canManage);
        }
        this.orderList.refreshElementPanes();
    }

    /**
     * Row title: the order number plus the component the server serialized.
     *
     * <p>The name is parsed rather than received as text so MineColonies'
     * translatable building names resolve in this client's language. A parse
     * failure (future component type, missing registries) falls back to the raw
     * JSON instead of dropping the row.</p>
     */
    private static Component nameComponent(final WorkOrderListPayload.Entry entry) {
        final Component name;
        final net.minecraft.client.multiplayer.ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
        Component parsed = null;
        if (level != null && entry.nameJson() != null && !entry.nameJson().isEmpty()) {
            try {
                parsed = Component.Serializer.fromJson(entry.nameJson(), level.registryAccess());
            } catch (final RuntimeException ex) {
                Crossroads.LOGGER.warn("Unreadable work order name: {}", entry.nameJson(), ex);
            }
        }
        name = parsed == null ? Component.literal(entry.nameJson() == null ? "" : entry.nameJson()) : parsed;
        return Component.literal("#" + entry.id() + "  ").append(name);
    }

    private static Component typeComponent(final String type) {
        return Component.translatable("rp_crossroads.wo.type."
                + (type == null ? "unknown" : type.toLowerCase(java.util.Locale.ROOT)));
    }

    private static Component statusComponent(final WorkOrderListPayload.Entry entry) {
        if (!entry.claimed()) {
            return Component.translatable("rp_crossroads.wo.unclaimed");
        }
        if (entry.stage() == null) {
            return Component.translatable("rp_crossroads.wo.claimed");
        }
        return Component.translatable("rp_crossroads.wo.claimed_stage",
                Component.translatable("rp_crossroads.wo.stage."
                        + entry.stage().toLowerCase(java.util.Locale.ROOT)));
    }

    private void selectAll() {
        this.selected.clear();
        for (final WorkOrderListPayload.Entry entry : this.entries) {
            this.selected.add(entry.id());
        }
        this.refresh();
    }

    private void clearSelection() {
        this.selected.clear();
        this.refresh();
    }

    private void cancelSelected() {
        if (this.selected.isEmpty()) {
            if (this.mc.player != null) {
                com.YLFXM.rp.crossroads.road.RoadNotices.actionBar(this.mc.player,"rp_crossroads.ui.select_order_first");
            }
            return;
        }
        PacketDistributor.sendToServer(new CancelWorkOrdersPayload(this.colonyId, List.copyOf(this.selected)));
    }
}
