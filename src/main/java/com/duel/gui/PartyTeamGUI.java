package com.duel.gui;

import com.duel.DuelPlugin;
import com.duel.party.Party;
import com.duel.utils.ItemUtils;
import com.duel.utils.SoundUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

/**
 * Party FFA → Team — GUI ترتيب التيمات (5 صفوف).
 *
 *  صف 0-1 : التيم الأزرق (فوق) — 15 خانة
 *  صف 2   : خط فاصل من الزجاج الأزرق + زر Save في أقصى اليسار + راس القائد (تيمات عشوائية) تحته
 *  صف 3-4 : التيم الأحمر (تحت) — 15 خانة
 *  الحواف  : زجاج أزرق (نفس Duel Menu)
 *
 * التحريك: كليك على راس لاعب = تحديده، كليك على خانة ثانية = تبديل / نقل.
 * (الخانة الفاضية تظهر كزجاج ملوّن — النقل إليها يغيّر عدد اللاعبين في التيم.)
 */
public class PartyTeamGUI implements InventoryHolder {

    public static final int SIZE       = 45;
    public static final int SAVE_SLOT  = 18;
    /** زر التيمات العشوائية (راس قائد البارتي) — الجانب الأيسر تحت Save */
    public static final int RANDOM_SLOT = 27;
    public static final int PER_TEAM   = 15;
    public static final int MIN_PARTY  = 3;   // 2v1
    public static final int MAX_PARTY  = 67;  // أقصى عدد في تيمات البارتي (فوق 30 يفتح GUI بصفحات)
    public static final int MIN_TEAM   = 1;

    /** خانات التيم الأزرق (فوق) */
    public static final int[] TOP_SLOTS = {1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13, 14, 15, 16};
    /** خانات التيم الأحمر (تحت) */
    public static final int[] BOTTOM_SLOTS = {28, 29, 30, 31, 32, 33, 34, 35, 37, 38, 39, 40, 41, 42, 43};

    private final DuelPlugin plugin;
    private final Party party;
    private final Inventory inventory;

    /** ترتيب الخانات: 0..14 = فوق، 15..29 = تحت */
    private final UUID[] slots = new UUID[PER_TEAM * 2];
    private int selected = -1;

    /** لو الـ GUI انفتح من اختيار Team في PartyModeGUI: الأرينا اللي تبدأ فيها المباراة بعد Save */
    private com.duel.models.Arena pendingArena = null;
    private String pendingMap = null;

    public PartyTeamGUI(DuelPlugin plugin, Party party) {
        this.plugin = plugin;
        this.party = party;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("⚔ Duel Arena — Party Teams").color(NamedTextColor.DARK_AQUA)
                        .decoration(TextDecoration.BOLD, false));
        loadInitialArrangement();
        render();
    }

    // ─────────────────────────────────────────
    //  فتح الـ GUI مع كل التشيكات
    // ─────────────────────────────────────────

    public static void open(DuelPlugin plugin, Player player) {
        open(plugin, player, null);
    }

    /** arena != null → بعد Save تبدأ مباراة التيمات مباشرة في هذي الأرينا */
    public static void open(DuelPlugin plugin, Player player, com.duel.models.Arena arena) {
        open(plugin, player, arena, null);
    }

    /** mapWorld = map chosen by the leader in PartyModeGUI (null = random) */
    public static void open(DuelPlugin plugin, Player player, com.duel.models.Arena arena, String mapWorld) {
        Party party = plugin.getPartyManager().getParty(player);
        if (party == null) { player.sendMessage("§cYou are not in a party."); return; }
        if (!party.getLeader().equals(player)) { player.sendMessage("§cOnly the party leader can edit the teams."); return; }
        if (plugin.getDuelManager().isInDuel(player) || plugin.getPartyDuelManager().isInPartyDuel(player)) {
            player.sendMessage("§cYou can't edit teams while in a match.");
            return;
        }
        if (party.getSize() < MIN_PARTY) {
            player.sendMessage("§c[Party] Teams need at least " + MIN_PARTY + " players. Your party has " + party.getSize() + ".");
            return;
        }
        if (party.getSize() > MAX_PARTY) {
            player.sendMessage("§c[Party] Teams support at most " + MAX_PARTY + " players. Your party has " + party.getSize() + ".");
            return;
        }
        // أكثر من 30 لاعب → GUI بصفحات
        if (party.getSize() > PER_TEAM * 2) {
            PartyTeamPagedGUI.open(plugin, player, party, arena, mapWorld);
            return;
        }
        SoundUtils.playGuiClick(player);
        PartyTeamGUI gui = new PartyTeamGUI(plugin, party);
        gui.pendingArena = arena;
        gui.pendingMap = mapWorld;
        player.openInventory(gui.getInventory());
    }

    // ─────────────────────────────────────────
    //  الترتيب الابتدائي
    // ─────────────────────────────────────────

    private void loadInitialArrangement() {
        List<UUID> members = new ArrayList<>(party.getMembers());
        // القائد أول واحد دائماً
        members.remove(party.getLeader().getUniqueId());
        members.add(0, party.getLeader().getUniqueId());

        List<UUID> top = new ArrayList<>();
        List<UUID> bottom = new ArrayList<>();

        if (party.hasTeams()) {
            // رجّع الترتيب المحفوظ (مع تجاهل اللي طلعوا من البارتي)
            for (UUID u : party.getTeamTop())    if (members.contains(u)) top.add(u);
            for (UUID u : party.getTeamBottom()) if (members.contains(u)) bottom.add(u);
            // أي عضو جديد غير موزّع → التيم الأصغر
            for (UUID u : members) {
                if (top.contains(u) || bottom.contains(u)) continue;
                if (top.size() <= bottom.size() && top.size() < PER_TEAM) top.add(u);
                else if (bottom.size() < PER_TEAM) bottom.add(u);
                else top.add(u);
            }
            // فيكس: لو تيم أكبر من 15 (محفوظ من الـ GUI بصفحات) نوازن عشان ما يختفي أحد من العرض
            while (top.size() > PER_TEAM)    bottom.add(top.remove(top.size() - 1));
            while (bottom.size() > PER_TEAM) top.add(bottom.remove(bottom.size() - 1));
        } else {
            // توزيع افتراضي متساوي (4 → 2 و 2)
            int topCount = (members.size() + 1) / 2;
            for (int i = 0; i < members.size(); i++) {
                if (i < topCount) top.add(members.get(i)); else bottom.add(members.get(i));
            }
        }

        for (int i = 0; i < top.size() && i < PER_TEAM; i++)    slots[i] = top.get(i);
        for (int i = 0; i < bottom.size() && i < PER_TEAM; i++) slots[PER_TEAM + i] = bottom.get(i);
    }

    // ─────────────────────────────────────────
    //  الرسم
    // ─────────────────────────────────────────

    private void render() {
        // نفس ستايل Duel Menu: زجاج أزرق للإطار والخط الأوسط
        ItemStack blue = ItemUtils.buildFiller(Material.BLUE_STAINED_GLASS_PANE);
        for (int i = 0; i < SIZE; i++) inventory.setItem(i, blue);

        // زر Save — أقصى اليسار
        inventory.setItem(SAVE_SLOT, buildSave());

        // زر Random — راس قائد البارتي، الجانب الأيسر
        inventory.setItem(RANDOM_SLOT, buildRandom());

        for (int i = 0; i < PER_TEAM; i++) {
            inventory.setItem(TOP_SLOTS[i],    buildSlotItem(i, true));
            inventory.setItem(BOTTOM_SLOTS[i], buildSlotItem(PER_TEAM + i, false));
        }
    }

    private ItemStack buildSave() {
        ItemStack item = ItemUtils.build(Material.EMERALD_BLOCK, "Save", NamedTextColor.GREEN,
                "Click to save the teams",
                "§9Blue: §f" + count(true) + "  §cRed: §f" + count(false),
                "§8Min " + MIN_TEAM + " players per team");
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Save").color(NamedTextColor.GREEN)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildRandom() {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(party.getLeader());
        meta.displayName(Component.text("Random Teams").color(NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Click to split the party randomly")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("into two even teams")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        head.setItemMeta(meta);
        return head;
    }

    /** يوزّع كل أعضاء البارتي عشوائياً على التيمين (متساوي، والزيادة للأزرق) */
    private void randomize(Player leader) {
        List<UUID> members = new ArrayList<>(party.getMembers());
        Collections.shuffle(members);

        Arrays.fill(slots, null);
        int topCount = Math.min((members.size() + 1) / 2, PER_TEAM);
        int bottomCount = Math.min(members.size() - topCount, PER_TEAM);
        for (int i = 0; i < topCount; i++) slots[i] = members.get(i);
        for (int i = 0; i < bottomCount; i++) slots[PER_TEAM + i] = members.get(topCount + i);

        selected = -1;
        SoundUtils.playGuiClick(leader);
        render();
    }

    private ItemStack buildSlotItem(int idx, boolean blue) {
        UUID uuid = slots[idx];
        NamedTextColor teamColor = blue ? NamedTextColor.BLUE : NamedTextColor.RED;
        String teamName = blue ? "Blue Team" : "Red Team";

        if (uuid == null) {
            Material pane = blue ? Material.LIGHT_BLUE_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
            ItemStack empty = ItemUtils.build(pane, "Empty slot", NamedTextColor.GRAY,
                    "Select a player, then click here", "to move them to the " + teamName);
            return empty;
        }

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        meta.setOwningPlayer(op);

        Player online = Bukkit.getPlayer(uuid);
        String name = online != null ? online.getName() : (op.getName() != null ? op.getName() : "Unknown");
        meta.displayName(Component.text(name).color(teamColor)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));

        boolean isSelected = idx == selected;
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(teamName).color(teamColor).decoration(TextDecoration.ITALIC, false));
        if (uuid.equals(party.getLeader().getUniqueId())) {
            lore.add(Component.text("★ Party Leader").color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(Component.text(isSelected ? "▶ Selected — click another slot" : "Click to select / move")
                .color(isSelected ? NamedTextColor.YELLOW : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);

        if (isSelected) {
            meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        head.setItemMeta(meta);
        return head;
    }

    private int count(boolean top) {
        int n = 0;
        int from = top ? 0 : PER_TEAM;
        for (int i = from; i < from + PER_TEAM; i++) if (slots[i] != null) n++;
        return n;
    }

    // ─────────────────────────────────────────
    //  الكليكات
    // ─────────────────────────────────────────

    private static int indexOf(int[] arr, int slot) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == slot) return i;
        return -1;
    }

    public void handleClick(Player player, int rawSlot) {
        // لازم يبقى قائد البارتي
        if (!party.getLeader().equals(player) || plugin.getPartyManager().getParty(player) != party) {
            player.closeInventory();
            return;
        }
        if (rawSlot < 0 || rawSlot >= SIZE) return;

        if (rawSlot == SAVE_SLOT) { save(player); return; }
        if (rawSlot == RANDOM_SLOT) { randomize(player); return; }

        int idx = indexOf(TOP_SLOTS, rawSlot);
        if (idx < 0) {
            int b = indexOf(BOTTOM_SLOTS, rawSlot);
            if (b >= 0) idx = PER_TEAM + b;
        }
        if (idx < 0) return; // حافة/فاصل

        if (selected == -1) {
            if (slots[idx] == null) return;      // خانة فاضية بدون تحديد
            selected = idx;
            SoundUtils.playGuiClick(player);
        } else if (selected == idx) {
            selected = -1;
            SoundUtils.playGuiClick(player);
        } else {
            UUID moving = slots[selected];
            slots[selected] = slots[idx];        // swap (أو نقل لو الوجهة فاضية)
            slots[idx] = moving;
            selected = -1;
            SoundUtils.playGuiClick(player);
        }
        render();
    }

    // ─────────────────────────────────────────
    //  Save
    // ─────────────────────────────────────────

    private void save(Player leader) {
        List<UUID> top = new ArrayList<>();
        List<UUID> bottom = new ArrayList<>();
        for (int i = 0; i < PER_TEAM; i++) {
            if (slots[i] != null) top.add(slots[i]);
            if (slots[PER_TEAM + i] != null) bottom.add(slots[PER_TEAM + i]);
        }

        // البارتي تغيّر وانت فاتح الـ GUI؟
        Set<UUID> assigned = new HashSet<>(top);
        assigned.addAll(bottom);
        if (!assigned.equals(new HashSet<>(party.getMembers()))) {
            leader.closeInventory();
            leader.sendMessage("§c[Party] The party members changed. Choose §eTeam §cagain from Party FFA.");
            return;
        }
        if (top.size() < MIN_TEAM || bottom.size() < MIN_TEAM) {
            leader.sendMessage("§c[Party] Each team needs at least " + MIN_TEAM + " player.");
            return;
        }

        party.setTeams(top, bottom);
        leader.closeInventory();
        SoundUtils.playSave(leader);

        party.broadcastAll("§6[Party] §aTeams saved: §9Blue (" + top.size() + ") §7vs §cRed (" + bottom.size() + ")§a."
                + (pendingArena == null ? " §7The leader can start the match with Party FFA." : ""));
        for (UUID u : top)    { Player p = Bukkit.getPlayer(u); if (p != null) p.sendMessage("§6[Party] §7You are on the §9Blue Team§7."); }
        for (UUID u : bottom) { Player p = Bukkit.getPlayer(u); if (p != null) p.sendMessage("§6[Party] §7You are on the §cRed Team§7."); }

        // فتح من Team في PartyModeGUI → ابدأ المباراة مباشرة بالأرينا المختارة
        if (pendingArena != null) {
            plugin.getPartyDuelManager().startPartyTeams(party, pendingArena, pendingMap);
        }
    }

    /** يُستدعى لما عضو ينقطع اتصاله والـ GUI مفتوح — ينشاله من الترتيب بدل ما يخرب الـ Save */
    public void syncMembers() {
        Set<UUID> current = new HashSet<>(party.getMembers());
        boolean changed = false;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null && !current.contains(slots[i])) { slots[i] = null; changed = true; }
        }
        if (changed) { selected = -1; render(); }
    }

    @Override
    public Inventory getInventory() { return inventory; }
}
