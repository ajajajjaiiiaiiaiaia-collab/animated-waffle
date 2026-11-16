package com.duel.listeners;

import com.duel.DuelPlugin;
import com.duel.gui.SpectateGUI;
import com.duel.models.DuelSession;
import com.duel.models.PartyDuelSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class SpectateGUIListener implements Listener {

    private final DuelPlugin plugin;

    public SpectateGUIListener(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) return;

        String title = stripTitle(event.getView().title());
        if (!title.startsWith("👁 Spectate")) return;
        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        int currentPage = viewer.getPersistentDataContainer().getOrDefault(
                new org.bukkit.NamespacedKey(plugin, "spectate_page"),
                org.bukkit.persistence.PersistentDataType.INTEGER, 0);

        // Previous arrow (slot 45)
        if (slot == 45) {
            plugin.getSpectateGUI().open(viewer, currentPage - 1);
            return;
        }

        // Next arrow (slot 53)
        if (slot == 53) {
            plugin.getSpectateGUI().open(viewer, currentPage + 1);
            return;
        }

        // Content slot — find which match was clicked
        int[] contentSlots = buildContentSlots();
        int matchIndex = -1;
        for (int i = 0; i < contentSlots.length; i++) {
            if (contentSlots[i] == slot) { matchIndex = i; break; }
        }
        if (matchIndex < 0) return;

        int absoluteIndex = currentPage * 28 + matchIndex;
        java.util.List<SpectateGUI.MatchEntry> matches = collectMatches();
        if (absoluteIndex >= matches.size()) return;

        SpectateGUI.MatchEntry entry = matches.get(absoluteIndex);
        viewer.closeInventory();
        spectate(viewer, entry);
    }

    private void spectate(Player viewer, SpectateGUI.MatchEntry entry) {
        if (!entry.isParty()) {
            DuelSession ds = (DuelSession) entry.getSession();
            plugin.getSpectateCommand().spectate(viewer, ds.getPlayer1());
        } else {
            PartyDuelSession ps = (PartyDuelSession) entry.getSession();
            java.util.List<org.bukkit.entity.Player> alive = ps.getAllPlayers().stream()
                    .filter(p -> ps.isAlive(p) && p.isOnline())
                    .toList();
            if (alive.isEmpty()) {
                viewer.sendMessage(Component.text("Match ended — no alive players to spectate.")
                        .color(NamedTextColor.RED));
                return;
            }
            plugin.getSpectateCommand().spectate(viewer, alive.get(0));
        }
    }

    private java.util.List<SpectateGUI.MatchEntry> collectMatches() {
        java.util.List<SpectateGUI.MatchEntry> list = new java.util.ArrayList<>();
        for (DuelSession s : plugin.getDuelManager().getAllActiveSessions())
            list.add(new SpectateGUI.MatchEntry(s));
        for (PartyDuelSession s : plugin.getPartyDuelManager().getAllActiveSessions())
            list.add(new SpectateGUI.MatchEntry(s));
        list.sort(java.util.Comparator.comparingLong(SpectateGUI.MatchEntry::getStartTime).reversed());
        return list;
    }

    private int[] buildContentSlots() {
        int[] slots = new int[28];
        int idx = 0;
        for (int row = 1; row <= 4; row++)
            for (int col = 1; col <= 7; col++)
                slots[idx++] = row * 9 + col;
        return slots;
    }

    private String stripTitle(Component c) {
        // Simple plain text extraction
        StringBuilder sb = new StringBuilder();
        c.children().forEach(child -> sb.append(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(child)));
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
    }
}
