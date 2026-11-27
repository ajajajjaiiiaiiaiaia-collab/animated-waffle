package com.starkmc.moderation.commands;

import com.starkmc.moderation.ModerationPlugin;
import com.starkmc.moderation.data.DataManager;
import com.starkmc.moderation.data.KickEntry;
import com.starkmc.moderation.data.WarnEntry;
import com.starkmc.moderation.util.DurationUtil;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

public class AlltsCommand implements CommandExecutor, TabCompleter, Listener {
   private static final int ROWS = 6;
   private static final int CONTENT_SLOTS = 36;
   private static final int SLOT_PREV = 45;
   private static final int SLOT_TAB0 = 46;
   private static final int SLOT_TAB1 = 47;
   private static final int SLOT_TAB2 = 48;
   private static final int SLOT_TAB3 = 49;
   private static final int SLOT_INFO = 50;
   private static final int SLOT_NEXT = 53;
   private static final int PAGE_BAN = 0;
   private static final int PAGE_MUTE = 1;
   private static final int PAGE_KICK = 2;
   private static final int PAGE_WARN = 3;
   private static final String[] TAB_LABELS = new String[]{"§c§l⚔  Ban", "§6§l\ud83d\udd07  Mute", "§d§l\ud83d\udc62  Kick", "§e§l⚠  Warn"};
   private static final Material[] TAB_MATS = new Material[]{Material.RED_CONCRETE, Material.ORANGE_CONCRETE, Material.PINK_CONCRETE, Material.YELLOW_CONCRETE};
   private static final TextColor[] TAB_COLORS = new TextColor[]{
      TextColor.fromHexString("#FF4444"), TextColor.fromHexString("#FF9900"), TextColor.fromHexString("#FF77FF"), TextColor.fromHexString("#FFD700")
   };
   private static final TextColor C_GRAY = TextColor.fromHexString("#AAAAAA");
   private static final TextColor C_WHITE = TextColor.fromHexString("#FFFFFF");
   private static final TextColor C_DARK = TextColor.fromHexString("#444444");
   private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy/MM/dd HH:mm");
   private static final ItemStack FILLER_BLACK = makeFiller(Material.BLACK_STAINED_GLASS_PANE);
   private static final ItemStack[] FILLER_TAB = new ItemStack[]{
      makeFiller(Material.RED_CONCRETE), makeFiller(Material.ORANGE_CONCRETE), makeFiller(Material.PINK_CONCRETE), makeFiller(Material.YELLOW_CONCRETE)
   };
   private static final long CACHE_TTL_MS = 10000L;
   private static final int SWEEP_EVERY_N_BUILDS = 100;
   private final Map<String, AlltsCommand.CacheEntry> entryCache = new ConcurrentHashMap<>();
   private final Map<String, List<KickEntry>> prefetchedKicks = new ConcurrentHashMap<>();
   private final Map<String, List<WarnEntry>> prefetchedWarns = new ConcurrentHashMap<>();
   private final AtomicInteger buildCount = new AtomicInteger();
   private final Map<UUID, AlltsCommand.GUIState> sessions = new ConcurrentHashMap<>();
   private final ModerationPlugin plugin;
   private final DataManager data;

   private static ItemStack makeFiller(Material mat) {
      ItemStack i = new ItemStack(mat);
      ItemMeta m = i.getItemMeta();
      if (m != null) {
         m.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
         i.setItemMeta(m);
      }

      return i;
   }

   public AlltsCommand(ModerationPlugin plugin, DataManager data) {
      this.plugin = plugin;
      this.data = data;
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
   }

   public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
      if (sender instanceof Player viewer) {
         if (!viewer.hasPermission("starkmod.allts")) {
            viewer.sendMessage("§cليس لديك صلاحية.");
            return true;
         } else if (args.length < 1) {
            viewer.sendMessage("§cالاستخدام: §f/allts §7<اسم اللاعب>");
            return true;
         } else {
            String target = args[0];
            this.openGUI(viewer, target, 0, 0);
            return true;
         }
      } else {
         sender.sendMessage("§cهذا الأمر للاعبين فقط.");
         return true;
      }
   }

   private boolean isFresh(String target, int page) {
      AlltsCommand.CacheEntry c = this.entryCache.get(target.toLowerCase() + ":" + page);
      return c != null && System.currentTimeMillis() - c.builtAt() < 10000L;
   }

   /** Kicks/warns tabs need MySQL: load on the DB thread first, then build + open the GUI on the main thread. */
   private void openGUI(Player viewer, String target, int page, int scroll) {
      if ((page == 2 || page == 3) && !this.isFresh(target, page)) {
         String key = target.toLowerCase();
         if (page == 2) {
            this.data.async(this.plugin, () -> this.data.getKicks(target), rows -> {
               if (rows != null) {
                  this.prefetchedKicks.put(key, rows);
               }
               if (viewer.isOnline()) {
                  this.openGUINow(viewer, target, page, scroll);
               }
            });
         } else {
            this.data.async(this.plugin, () -> this.data.getWarns(target), rows -> {
               if (rows != null) {
                  this.prefetchedWarns.put(key, rows);
               }
               if (viewer.isOnline()) {
                  this.openGUINow(viewer, target, page, scroll);
               }
            });
         }
         return;
      }
      this.openGUINow(viewer, target, page, scroll);
   }

   private void openGUINow(Player viewer, String target, int page, int scroll) {
      List<ItemStack> entries = this.getEntries(target, page);
      int totalRows = (int)Math.ceil(entries.size() / 9.0);
      int maxScroll = Math.max(0, totalRows - 4);
      scroll = Math.min(scroll, maxScroll);
      String title = TAB_LABELS[page] + " §8│ §7" + target + " §8(§f" + entries.size() + "§8)";
      Inventory inv = Bukkit.createInventory(new AlltsHolder(), 54, Component.text(ChatColor.translateAlternateColorCodes('§', title)));

      for (int i = 0; i < 36; i++) {
         int idx = scroll * 9 + i;
         inv.setItem(i, idx < entries.size() ? entries.get(idx) : FILLER_BLACK);
      }

      ItemStack tabFiller = FILLER_TAB[page];

      for (int i = 36; i <= 44; i++) {
         inv.setItem(i, tabFiller);
      }

      for (int i = 45; i <= 53; i++) {
         inv.setItem(i, FILLER_BLACK);
      }

      if (scroll > 0) {
         inv.setItem(45, this.navBtn("§e§l◀ السابق", "§7للأعلى"));
      }

      for (int t = 0; t <= 3; t++) {
         inv.setItem(46 + t, this.tabBtn(t, t == page));
      }

      inv.setItem(50, this.infoBtn(target, page, scroll, entries.size(), maxScroll));
      if (scroll < maxScroll) {
         inv.setItem(53, this.navBtn("§e§lالتالي ▶", "§7للأسفل"));
      }

      this.sessions.put(viewer.getUniqueId(), new AlltsCommand.GUIState(target, page, scroll));
      viewer.openInventory(inv);
   }

   private List<ItemStack> getEntries(String target, int page) {
      String key = target.toLowerCase() + ":" + page;
      AlltsCommand.CacheEntry cached = this.entryCache.get(key);
      if (cached != null && System.currentTimeMillis() - cached.builtAt() < 10000L) {
         return cached.items();
      } else {
         List<ItemStack> fresh = this.buildEntries(target, page);
         this.entryCache.put(key, new AlltsCommand.CacheEntry(fresh, System.currentTimeMillis()));
         if (this.buildCount.incrementAndGet() % 100 == 0) {
            long now = System.currentTimeMillis();
            this.entryCache.entrySet().removeIf(e -> now - e.getValue().builtAt() >= 10000L);
         }

         return fresh;
      }
   }

   public void invalidateAll() {
      this.entryCache.clear();
   }

   public void invalidateCache(String playerName) {
      String lower = playerName.toLowerCase();
      this.entryCache.keySet().removeIf(k -> k.startsWith(lower + ":"));
   }

   private List<ItemStack> buildEntries(String target, int page) {
      return switch (page) {
         case 0 -> this.buildBans(target);
         case 1 -> this.buildMutes(target);
         case 2 -> this.buildKicks(target);
         case 3 -> this.buildWarns(target);
         default -> List.of();
      };
   }

   private List<ItemStack> buildBans(String target) {
      DataManager.PunishmentEntry pe = this.data.getBanEntry(target);
      return pe == null ? List.of(this.placeholder("§cلا يوجد باندات لهذا اللاعب")) : List.of(this.punishHead(target, pe, 0));
   }

   private List<ItemStack> buildMutes(String target) {
      DataManager.PunishmentEntry pe = this.data.getMuteEntry(target);
      return pe == null ? List.of(this.placeholder("§6لا يوجد ميوتات لهذا اللاعب")) : List.of(this.punishHead(target, pe, 1));
   }

   private List<ItemStack> buildKicks(String target) {
      List<KickEntry> kicks = this.prefetchedKicks.remove(target.toLowerCase());
      if (kicks == null) {
         kicks = this.data.getKicks(target);
      }
      if (kicks.isEmpty()) {
         return List.of(this.placeholder("§dلا يوجد كيكات لهذا اللاعب"));
      } else {
         List<KickEntry> reversed = new ArrayList<>(kicks);
         Collections.reverse(reversed);
         List<ItemStack> list = new ArrayList<>(reversed.size());

         for (KickEntry k : reversed) {
            list.add(this.kickHead(target, k));
         }

         return list;
      }
   }

   private List<ItemStack> buildWarns(String target) {
      List<WarnEntry> warns = this.prefetchedWarns.remove(target.toLowerCase());
      if (warns == null) {
         warns = this.data.getWarns(target);
      }
      if (warns.isEmpty()) {
         return List.of(this.placeholder("§eلا يوجد وارنات لهذا اللاعب"));
      } else {
         List<WarnEntry> reversed = new ArrayList<>(warns);
         Collections.reverse(reversed);
         List<ItemStack> list = new ArrayList<>(reversed.size());

         for (WarnEntry w : reversed) {
            list.add(this.warnHead(target, w));
         }

         return list;
      }
   }

   private ItemStack makeSkull(String name) {
      ItemStack head = new ItemStack(Material.PLAYER_HEAD);
      SkullMeta meta = (SkullMeta)head.getItemMeta();
      if (meta == null) {
         return head;
      } else {
         Player online = Bukkit.getPlayerExact(name);
         if (online != null) {
            meta.setOwningPlayer(online);
            head.setItemMeta(meta);
            return head;
         } else {
            CompletableFuture.<OfflinePlayer>supplyAsync(() -> Bukkit.getOfflinePlayer(name)).thenAcceptAsync(op -> {
               SkullMeta asyncMeta = (SkullMeta)head.getItemMeta();
               if (asyncMeta != null) {
                  asyncMeta.setOwningPlayer(op);
                  head.setItemMeta(asyncMeta);
               }
            }, runnable -> Bukkit.getScheduler().runTask(this.plugin, runnable));
            return head;
         }
      }
   }

   private ItemStack punishHead(String name, DataManager.PunishmentEntry pe, int page) {
      ItemStack head = this.makeSkull(name);
      SkullMeta meta = (SkullMeta)head.getItemMeta();
      if (meta == null) {
         return head;
      } else {
         String prefix = page == 0 ? "§c§l⚔" : "§6§l\ud83d\udd07";
         meta.displayName(this.txt(prefix + "  " + this.cap(name)));
         boolean perm = pe.expires() == -1L;
         String duration = perm ? "دائم" : DurationUtil.format(pe.expires() - pe.at());
         String remaining = DurationUtil.remaining(pe.expires());
         String date = SDF.format(new Date(pe.at()));
         String status = pe.isActive() ? "§a● نشط" : "§c● منتهي";
         TextColor accent = TAB_COLORS[page];
         List<Component> lore = new ArrayList<>(12);
         lore.add(Component.empty());
         lore.add(this.line("━━━━━━━━━━━━━━━━━━━━━━━━━", C_DARK));
         lore.add(this.row("اللاعب   ", this.cap(name), accent));
         lore.add(this.rowLegacy("الحالة   ", status));
         lore.add(this.line("─────────────────────────", C_DARK));
         lore.add(this.row("السبب    ", pe.reason(), C_WHITE));
         lore.add(this.row("بواسطة   ", pe.by(), C_WHITE));
         lore.add(this.row("المدة    ", duration, accent));
         lore.add(this.row("المتبقي  ", remaining, TextColor.fromHexString("#FFD700")));
         lore.add(this.row("التاريخ  ", date, C_GRAY));
         lore.add(Component.empty());
         lore.add(this.line("━━━━━━━━━━━━━━━━━━━━━━━━━", C_DARK));
         meta.lore(lore);
         head.setItemMeta(meta);
         return head;
      }
   }

   private ItemStack kickHead(String name, KickEntry k) {
      ItemStack head = this.makeSkull(name);
      SkullMeta meta = (SkullMeta)head.getItemMeta();
      if (meta == null) {
         return head;
      } else {
         meta.displayName(this.txt("§d§l\ud83d\udc62  " + this.cap(name) + " §8#" + k.id()));
         String date = SDF.format(new Date(k.at()));
         List<Component> lore = new ArrayList<>(10);
         lore.add(Component.empty());
         lore.add(this.line("━━━━━━━━━━━━━━━━━━━━━━━━━", C_DARK));
         lore.add(this.row("اللاعب   ", this.cap(name), TAB_COLORS[2]));
         lore.add(this.row("رقم الكيك", "#" + k.id(), TAB_COLORS[2]));
         lore.add(this.line("─────────────────────────", C_DARK));
         lore.add(this.row("السبب    ", k.reason(), C_WHITE));
         lore.add(this.row("بواسطة   ", k.by(), C_WHITE));
         lore.add(this.row("التاريخ  ", date, C_GRAY));
         lore.add(Component.empty());
         lore.add(this.line("━━━━━━━━━━━━━━━━━━━━━━━━━", C_DARK));
         meta.lore(lore);
         head.setItemMeta(meta);
         return head;
      }
   }

   private ItemStack warnHead(String name, WarnEntry w) {
      ItemStack head = this.makeSkull(name);
      SkullMeta meta = (SkullMeta)head.getItemMeta();
      if (meta == null) {
         return head;
      } else {
         meta.displayName(this.txt("§e§l⚠  " + this.cap(name) + " §8#" + w.id()));
         String date = SDF.format(new Date(w.at()));
         List<Component> lore = new ArrayList<>(10);
         lore.add(Component.empty());
         lore.add(this.line("━━━━━━━━━━━━━━━━━━━━━━━━━", C_DARK));
         lore.add(this.row("اللاعب      ", this.cap(name), TAB_COLORS[3]));
         lore.add(this.row("رقم الإنذار ", "#" + w.id(), TAB_COLORS[3]));
         lore.add(this.line("─────────────────────────", C_DARK));
         lore.add(this.row("السبب    ", w.reason(), C_WHITE));
         lore.add(this.row("بواسطة   ", w.by(), C_WHITE));
         lore.add(this.row("التاريخ  ", date, C_GRAY));
         lore.add(Component.empty());
         lore.add(this.line("━━━━━━━━━━━━━━━━━━━━━━━━━", C_DARK));
         meta.lore(lore);
         head.setItemMeta(meta);
         return head;
      }
   }

   private ItemStack navBtn(String display, String hint) {
      ItemStack i = new ItemStack(Material.ARROW);
      ItemMeta m = i.getItemMeta();
      if (m != null) {
         m.displayName(this.txt(display));
         m.lore(List.of(this.txt(hint)));
         i.setItemMeta(m);
      }

      return i;
   }

   private ItemStack tabBtn(int page, boolean active) {
      ItemStack i = new ItemStack(TAB_MATS[page], active ? 2 : 1);
      ItemMeta m = i.getItemMeta();
      if (m != null) {
         m.displayName(this.txt(TAB_LABELS[page]));
         m.lore(List.of(this.txt(active ? "§a✔ الصفحة الحالية" : "§7انقر للتبديل")));
         if (active) {
            m.addEnchant(Enchantment.UNBREAKING, 1, true);
            m.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ENCHANTS});
         }

         i.setItemMeta(m);
      }

      return i;
   }

   private ItemStack infoBtn(String target, int page, int scroll, int total, int maxScroll) {
      ItemStack i = new ItemStack(Material.COMPASS);
      ItemMeta m = i.getItemMeta();
      if (m != null) {
         m.displayName(this.txt("§b§l✦  " + this.cap(target)));
         m.lore(
            List.of(
               this.txt("§7الصفحة: §f" + TAB_LABELS[page].replaceAll("§[0-9a-fk-or]", "")),
               this.txt("§7المجموع: §f" + total),
               this.txt("§7الصفحة: §f" + (scroll + 1) + " §8/ §f" + (maxScroll + 1))
            )
         );
         i.setItemMeta(m);
      }

      return i;
   }

   private ItemStack placeholder(String msg) {
      ItemStack i = new ItemStack(Material.BARRIER);
      ItemMeta m = i.getItemMeta();
      if (m != null) {
         m.displayName(this.txt(msg));
         i.setItemMeta(m);
      }

      return i;
   }

   @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
   public void onInventoryClick(InventoryClickEvent event) {
      if (this.isOurs(event.getView().getTopInventory())) {
         event.setCancelled(true);
         if (event.getWhoClicked() instanceof Player viewer) {
            if (event.getClickedInventory() == null || !this.isOurs(event.getClickedInventory())) {
               return;
            }

            AlltsCommand.GUIState state = this.sessions.get(viewer.getUniqueId());
            if (state != null) {
               int slot = event.getRawSlot();
               if (slot >= 0 && slot < 54) {
                  String target = state.target();
                  int page = state.page();
                  int scroll = state.scroll();
                  switch (slot) {
                     case 45:
                        if (scroll > 0) {
                           this.openGUI(viewer, target, page, scroll - 1);
                        }
                        break;
                     case 46:
                        if (page != 0) {
                           this.openGUI(viewer, target, 0, 0);
                        }
                        break;
                     case 47:
                        if (page != 1) {
                           this.openGUI(viewer, target, 1, 0);
                        }
                        break;
                     case 48:
                        if (page != 2) {
                           this.openGUI(viewer, target, 2, 0);
                        }
                        break;
                     case 49:
                        if (page != 3) {
                           this.openGUI(viewer, target, 3, 0);
                        }
                        break;
                     case 53:
                        this.openGUI(viewer, target, page, scroll + 1);
                        break;
                     default:
                        break;
                  }
               }
            }
         }
      }
   }

   @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
   public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
      if (this.isOurs(event.getView().getTopInventory())) {
         event.setCancelled(true);
      }
   }

   @EventHandler
   public void onInventoryClose(InventoryCloseEvent event) {
      if (event.getPlayer() instanceof Player viewer && this.isOurs(event.getInventory())) {
         java.util.UUID id = viewer.getUniqueId();
         Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (!this.isOurs(viewer.getOpenInventory().getTopInventory())) {
               this.sessions.remove(id);
            }
         });
      }
   }

   private boolean isOurs(Inventory inv) {
      return inv != null && inv.getHolder() instanceof AlltsCommand.AlltsHolder;
   }

   private static final class AlltsHolder implements org.bukkit.inventory.InventoryHolder {
      public Inventory getInventory() {
         return null;
      }
   }

   private Component txt(String legacy) {
      return LegacyComponentSerializer.legacySection().deserialize(legacy).decoration(TextDecoration.ITALIC, false);
   }

   private Component line(String text, TextColor color) {
      return ((TextComponent)Component.text(text).color(color)).decoration(TextDecoration.ITALIC, false);
   }

   private Component row(String label, String value, TextColor vc) {
      return ((TextComponent)((TextComponent)Component.text("  " + label + ": ").color(C_GRAY)).decoration(TextDecoration.ITALIC, false))
         .append(((TextComponent)Component.text(value).color(vc)).decoration(TextDecoration.ITALIC, false));
   }

   private Component rowLegacy(String label, String valueLegacy) {
      return ((TextComponent)((TextComponent)Component.text("  " + label + ": ").color(C_GRAY)).decoration(TextDecoration.ITALIC, false))
         .append(LegacyComponentSerializer.legacySection().deserialize(valueLegacy).decoration(TextDecoration.ITALIC, false));
   }

   private String cap(String s) {
      return s != null && !s.isEmpty() ? Character.toUpperCase(s.charAt(0)) + s.substring(1) : s;
   }

   public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
      return args.length == 1
         ? this.plugin
            .getServer()
            .getOnlinePlayers()
            .stream()
            .<String>map(Player::getName)
            .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
            .collect(Collectors.toList())
         : List.of();
   }

   private record CacheEntry(List<ItemStack> items, long builtAt) {
   }

   private record GUIState(String target, int page, int scroll) {
   }
}
