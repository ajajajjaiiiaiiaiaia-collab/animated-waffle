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
 * GUI التيمات للبارتيات الكبيرة (أكثر من 30 لاعب) — نفس شكل وطريقة {@link PartyTeamGUI}
 * بس بصفحات (سهم واحد ينقل الصفحة للتيمين مع بعض):
 *
 *  صف 0-1 : التيم الأزرق (فوق) — 15 خانة في الصفحة
 *  صف 2   : خط فاصل + Save (18)
 *  صف 3-4 : التيم الأحمر (تحت) — 15 خانة في الصفحة، Random (27)
 *  خانة 36 (أقصى تحت يسار) : سهم الصفحات — كليك يسار = التالية، كليك يمين = السابقة
 *
 * كل لاعب له خانة ثابتة (مثل الصغير): تغيير الصفحة ما يغيّر أي ترتيب، والنقل لخانة فاضية
 * يحطه في نفس الخانة اللي ضغطت عليها بالضبط بدون ما يزحزح باقي اللاعبين.
 */
public class PartyTeamPagedGUI implements InventoryHolder {

    public static final int SIZE = PartyTeamGUI.SIZE;
    public static final int PER_PAGE = PartyTeamGUI.PER_TEAM;

    /** أقصى تحت يسار */
    public static final int PAGE_SLOT = 36;

    private final DuelPlugin plugin;
    private final Party party;
    private final Inventory inventory;

    /** خانات كل تيم بترتيب ثابت — null = خانة فاضية (تتمدد حسب الحاجة) */
    private final List<UUID> blueSlots = new ArrayList<>();
    private final List<UUID> redSlots  = new ArrayList<>();
    private int page = 0;
    private UUID selected = null;

    /** لو انفتح من Team في PartyModeGUI: الأرينا والماب اللي تبدأ فيها المباراة بعد Save */
    private com.duel.models.Arena pendingArena = null;
    private String pendingMap = null;

    private PartyTeamPagedGUI(DuelPlugin plugin, Party party) {
        this.plugin = plugin;
        this.party = party;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("⚔ Duel Arena — Party Teams").color(NamedTextColor.DARK_AQUA)
                        .decoration(TextDecoration.BOLD, false));
        loadInitialArrangement();
        render();
    }

    // ─────────────────────────────────────────
    //  فتح (يُستدعى من PartyTeamGUI.open بعد التشيكات)
    // ─────────────────────────────────────────

    static void open(DuelPlugin plugin, Player player, Party party, com.duel.models.Arena arena, String mapWorld) {
        SoundUtils.playGuiClick(player);
        PartyTeamPagedGUI gui = new PartyTeamPagedGUI(plugin, party);
        gui.pendingArena = arena;
        gui.pendingMap = mapWorld;
        player.openInventory(gui.getInventory());
    }

    private void loadInitialArrangement() {
        List<UUID> members = new ArrayList<>(party.getMembers());
        members.remove(party.getLeader().getUniqueId());
        members.add(0, party.getLeader().getUniqueId());   // القائد أول واحد

        List<UUID> blue = new ArrayList<>();
        List<UUID> red  = new ArrayList<>();
        if (party.hasTeams()) {
            for (UUID u : party.getTeamTop())    if (members.contains(u)) blue.add(u);
            for (UUID u : party.getTeamBottom()) if (members.contains(u)) red.add(u);
            // أي عضو جديد غير موزّع → التيم الأصغر
            for (UUID u : members) {
                if (blue.contains(u) || red.contains(u)) continue;
                if (blue.size() <= red.size()) blue.add(u); else red.add(u);
            }
        } else {
            int half = (members.size() + 1) / 2;
            for (int i = 0; i < members.size(); i++) {
                if (i < half) blue.add(members.get(i)); else red.add(members.get(i));
            }
        }
        blueSlots.addAll(blue);
        redSlots.addAll(red);
    }

    // ─────────────────────────────────────────
    //  أدوات الخانات
    // ─────────────────────────────────────────

    private List<UUID> listOf(boolean blue) { return blue ? blueSlots : redSlots; }

    private static UUID at(List<UUID> list, int idx) { return idx >= 0 && idx < list.size() ? list.get(idx) : null; }

    private static void put(List<UUID> list, int idx, UUID value) {
        while (list.size() <= idx) list.add(null);
        list.set(idx, value);
    }

    /** يشيل الخانات الفاضية اللي في آخر القائمة */
    private static void trim(List<UUID> list) {
        while (!list.isEmpty() && list.get(list.size() - 1) == null) list.remove(list.size() - 1);
    }

    private static int count(List<UUID> list) {
        int n = 0;
        for (UUID u : list) if (u != null) n++;
        return n;
    }

    private static List<UUID> compact(List<UUID> list) {
        List<UUID> out = new ArrayList<>();
        for (UUID u : list) if (u != null) out.add(u);
        return out;
    }

    /** دايماً فيه صفحة فيها خانة فاضية (عشان تقدر تنقل لاعب لآخر التيم) */
    private static int pages(List<UUID> list) { return list.size() / PER_PAGE + 1; }

    /** عدد الصفحات = الأكبر بين التيمين (الصفحة تنقل التيمين مع بعض) */
    private int totalPages() { return Math.max(pages(blueSlots), pages(redSlots)); }

    private void clampPage() { page = Math.max(0, Math.min(page, totalPages() - 1)); }

    // ─────────────────────────────────────────
    //  الرسم
    // ─────────────────────────────────────────

    private void render() {
        trim(blueSlots);
        trim(redSlots);
        clampPage();

        ItemStack frame = ItemUtils.buildFiller(Material.BLUE_STAINED_GLASS_PANE);
        for (int i = 0; i < SIZE; i++) inventory.setItem(i, frame);

        inventory.setItem(PartyTeamGUI.SAVE_SLOT, buildSave());
        inventory.setItem(PartyTeamGUI.RANDOM_SLOT, buildRandom());

        for (int i = 0; i < PER_PAGE; i++) {
            inventory.setItem(PartyTeamGUI.TOP_SLOTS[i],    buildSlotItem(blueSlots, i, true));
            inventory.setItem(PartyTeamGUI.BOTTOM_SLOTS[i], buildSlotItem(redSlots,  i, false));
        }

        inventory.setItem(PAGE_SLOT, buildPageArrow());
    }

    private ItemStack buildPageArrow() {
        ItemStack item = ItemUtils.build(Material.ARROW, "Page " + (page + 1) + "/" + totalPages(), NamedTextColor.YELLOW,
                "§7Left click: next page",
                "§7Right click: previous page",
                "§9Blue: §f" + count(blueSlots) + "  §cRed: §f" + count(redSlots));
        item.setAmount(Math.min(64, page + 1));
        return item;
    }

    private ItemStack buildSave() {
        ItemStack item = ItemUtils.build(Material.EMERALD_BLOCK, "Save", NamedTextColor.GREEN,
                "Click to save the teams",
                "§9Blue: §f" + count(blueSlots) + "  §cRed: §f" + count(redSlots),
                "§8Min " + PartyTeamGUI.MIN_TEAM + " players per team");
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
        meta.lore(List.of(
                Component.text("Click to split the party randomly")
                        .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("into two even teams")
                        .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        head.setItemMeta(meta);
        return head;
    }

    private ItemStack buildSlotItem(List<UUID> team, int slotIdx, boolean blue) {
        UUID uuid = at(team, page * PER_PAGE + slotIdx);
        NamedTextColor teamColor = blue ? NamedTextColor.BLUE : NamedTextColor.RED;
        String teamName = blue ? "Blue Team" : "Red Team";

        if (uuid == null) {
            Material pane = blue ? Material.LIGHT_BLUE_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
            return ItemUtils.build(pane, "Empty slot", NamedTextColor.GRAY,
                    "Select a player, then click here", "to move them to the " + teamName);
        }

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        meta.setOwningPlayer(op);

        Player online = Bukkit.getPlayer(uuid);
        String name = online != null ? online.getName() : (op.getName() != null ? op.getName() : "Unknown");
        meta.displayName(Component.text(name).color(teamColor)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));

        boolean isSelected = uuid.equals(selected);
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

    // ─────────────────────────────────────────
    //  الكليكات
    // ─────────────────────────────────────────

    private static int indexOf(int[] arr, int slot) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == slot) return i;
        return -1;
    }

    public void handleClick(Player player, int rawSlot) {
        handleClick(player, rawSlot, false);
    }

    /** rightClick = true → الصفحة السابقة (على سهم الصفحات) */
    public void handleClick(Player player, int rawSlot, boolean rightClick) {
        // لازم يبقى قائد نفس البارتي (نفس تحقق PartyTeamGUI)
        if (!party.getLeader().equals(player) || plugin.getPartyManager().getParty(player) != party) {
            player.closeInventory();
            return;
        }
        if (rawSlot < 0 || rawSlot >= SIZE) return;

        switch (rawSlot) {
            case PartyTeamGUI.SAVE_SLOT   -> { save(player); return; }
            case PartyTeamGUI.RANDOM_SLOT -> { randomize(player); return; }
            case PAGE_SLOT -> {
                int total = totalPages();
                if (total > 1) {
                    page = rightClick ? (page - 1 + total) % total : (page + 1) % total;
                    SoundUtils.playGuiClick(player);
                    render();
                }
                return;
            }
            default -> { }
        }

        boolean blue = true;
        int slotIdx = indexOf(PartyTeamGUI.TOP_SLOTS, rawSlot);
        if (slotIdx < 0) {
            slotIdx = indexOf(PartyTeamGUI.BOTTOM_SLOTS, rawSlot);
            blue = false;
        }
        if (slotIdx < 0) return; // حافة/فاصل

        List<UUID> team = listOf(blue);
        int idx = page * PER_PAGE + slotIdx;
        UUID target = at(team, idx);

        if (selected == null) {
            if (target == null) return;              // خانة فاضية بدون تحديد
            selected = target;
        } else if (selected.equals(target)) {
            selected = null;                         // إلغاء التحديد
        } else {
            // مكان المحدد الحالي (قد يكون في تيم/صفحة ثانية)
            List<UUID> from = blueSlots.contains(selected) ? blueSlots : redSlots;
            int fromIdx = from.indexOf(selected);

            put(from, fromIdx, target);              // تبديل، أو تفريغ الخانة لو الوجهة فاضية
            put(team, idx, selected);                // يدخل نفس الخانة اللي ضغطت عليها بالضبط
            selected = null;
        }
        SoundUtils.playGuiClick(player);
        render();
    }

    private void randomize(Player leader) {
        List<UUID> all = compact(blueSlots);
        all.addAll(compact(redSlots));
        Collections.shuffle(all);
        int half = (all.size() + 1) / 2;
        blueSlots.clear();
        redSlots.clear();
        for (int i = 0; i < all.size(); i++) {
            if (i < half) blueSlots.add(all.get(i)); else redSlots.add(all.get(i));
        }
        page = 0;
        selected = null;
        SoundUtils.playGuiClick(leader);
        render();
    }

    // ─────────────────────────────────────────
    //  Save
    // ─────────────────────────────────────────

    private void save(Player leader) {
        List<UUID> top = compact(blueSlots);
        List<UUID> bottom = compact(redSlots);

        // البارتي تغيّر وانت فاتح الـ GUI؟
        Set<UUID> assigned = new HashSet<>(top);
        assigned.addAll(bottom);
        if (!assigned.equals(new HashSet<>(party.getMembers()))) {
            leader.closeInventory();
            leader.sendMessage("§c[Party] The party members changed. Choose §eTeam §cagain from Party FFA.");
            return;
        }
        if (assigned.size() > PartyTeamGUI.MAX_PARTY) {
            leader.sendMessage("§c[Party] Teams support at most " + PartyTeamGUI.MAX_PARTY + " players.");
            return;
        }
        if (top.size() < PartyTeamGUI.MIN_TEAM || bottom.size() < PartyTeamGUI.MIN_TEAM) {
            leader.sendMessage("§c[Party] Each team needs at least " + PartyTeamGUI.MIN_TEAM + " player.");
            return;
        }

        party.setTeams(top, bottom);
        leader.closeInventory();
        SoundUtils.playSave(leader);

        party.broadcastAll("§6[Party] §aTeams saved: §9Blue (" + top.size() + ") §7vs §cRed (" + bottom.size() + ")§a."
                + (pendingArena == null ? " §7The leader can start the match with Party FFA." : ""));
        for (UUID u : top)    { Player p = Bukkit.getPlayer(u); if (p != null) p.sendMessage("§6[Party] §7You are on the §9Blue Team§7."); }
        for (UUID u : bottom) { Player p = Bukkit.getPlayer(u); if (p != null) p.sendMessage("§6[Party] §7You are on the §cRed Team§7."); }

        if (pendingArena != null) {
            plugin.getPartyDuelManager().startPartyTeams(party, pendingArena, pendingMap);
        }
    }

    /** يُستدعى لما عضو ينقطع اتصاله والـ GUI مفتوح — ينشاله من الترتيب بدل ما يخرب الـ Save */
    public void syncMembers() {
        Set<UUID> current = new HashSet<>(party.getMembers());
        boolean changed = false;
        for (List<UUID> list : List.of(blueSlots, redSlots)) {
            for (int i = 0; i < list.size(); i++) {
                UUID u = list.get(i);
                if (u != null && !current.contains(u)) { list.set(i, null); changed = true; }
            }
        }
        if (selected != null && !current.contains(selected)) selected = null;
        if (changed) render();
    }

    @Override
    public Inventory getInventory() { return inventory; }
}
