package com.duel.listeners;

import com.duel.DuelPlugin;
import com.duel.gui.ArenaEditorGUI;
import com.duel.gui.DuelArenaPickerGUI;
import com.duel.gui.DuelConfirmGUI;
import com.duel.gui.MapSelectorGUI;
import com.duel.gui.DuelInviteGUI;
import com.duel.gui.GameSelectorGUI;
import com.duel.gui.KitEditorGUI;
import com.duel.gui.QueueLeaveGUI;
import com.duel.gui.RoundsAnvilGUI;
import com.duel.gui.PlayerKitEditorGUI;
import com.duel.gui.SettingsGUI;
import com.duel.managers.DuelManager;
import com.duel.managers.SettingsManager;
import com.duel.utils.SoundUtils;
import com.duel.managers.DuelRequestManager.DuelRequest;
import com.duel.models.Arena;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;

import java.util.UUID;

public class InventoryListener implements Listener {

    // لاعبين انلقى لهم خصم وينتظرون انتهاء أنيميشن التوتم قبل النقل للماب
    private final java.util.Set<UUID> pendingMatch = new java.util.HashSet<>();

    private final DuelPlugin plugin;

    public InventoryListener(DuelPlugin plugin) {
        this.plugin = plugin;
        // الـ ticker حق الكيو (مدى التقييم يتوسع مع الانتظار) يبدأ المباراة من هنا
        plugin.getQueueManager().setMatchHandler(
                (a, b, arena, aRandom, bRandom) -> startQueueMatch(a, b, arena, aRandom, bRandom));
    }

    // ─────────────────────────────────────────
    //  Sword right-click → open GameSelectorGUI
    // ─────────────────────────────────────────

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();

        var action = event.getAction();
        boolean rightClick = action == org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                || action == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK;
        if (!rightClick) return;

        ItemStack held = player.getInventory().getItemInMainHand();

        // ── Duel sword → GameSelectorGUI ──
        if (isDuelSword(held)) {
            event.setCancelled(true);
            if (plugin.getDuelManager().isInDuel(player)) {
                player.sendMessage("§c⚔ You are currently in a duel!");
                return;
            }
            SoundUtils.playGuiClick(player);
            GameSelectorGUI.open(plugin, player, true);
            return;
        }

        // ── Kit edit book → KitSelectorGUI (reuses GameSelectorGUI look) ──
        if (isKitEditBook(held)) {
            event.setCancelled(true);
            if (plugin.getDuelManager().isInDuel(player)) {
                player.sendMessage("§c⚔ You cannot edit your kit while in a duel!");
                return;
            }
            SoundUtils.playGuiClick(player);
            openKitSelector(plugin, player);
            return;
        }

        // ── Settings clock → SettingsGUI ────────────────────────────────
        if (isSettingsClock(held)) {
            event.setCancelled(true);
            SoundUtils.playGuiClick(player);
            SettingsGUI.open(plugin, player);
            return;
        }

        // ── Star item → Tier Top dialog (بدل قائمة المشاهدة) ─────────────
        // كلاينت أقل من 1.21.6 أو بيدروك: TierTopDialog.open يفتح له قائمة الصندوق TierTopGUI
        if (isSpectateEye(held)) {
            event.setCancelled(true);
            SoundUtils.playGuiClick(player);
            com.duel.tier.TierTopDialog.open(plugin, player, null);
            return;
        }

        // ── Rematch item ─────────────────────────────────────────────────
        if (plugin.getRematchManager().isRematchItem(held)) {
            event.setCancelled(true);
            plugin.getRematchManager().accept(player);
            return;
        }

        // ── Party items ──────────────────────────────────────────────────
        if (isPartyCreate(held) || isPartyInfo(held) || isPartyDisband(held)
                || isPartyLeave(held) || isPartyFFA(held) || isPartyOpenToggle(held)) {
            event.setCancelled(true);
            // صيانة البارتي
            if (plugin.getConfig().getBoolean("party.maintenance", false)
                    && !player.hasPermission("duelplugin.admin")) {
                player.sendMessage("§8[§6Party§8] §cThe party system is currently under maintenance. Please try again later.");
                return;
            }
            if (isPartyCreate(held))  { player.performCommand("party create");  return; }
            if (isPartyInfo(held))    { player.performCommand("party info");     return; }
            if (isPartyDisband(held)) { com.duel.gui.PartyDisbandGUI.open(plugin, player); return; }
            if (isPartyLeave(held))   { player.performCommand("party leave");    return; }
            if (isPartyOpenToggle(held)) {
                var party = plugin.getPartyManager().getParty(player);
                if (party == null) return;
                // Closed -> open, open -> closed (leader check is done by /party open|close)
                boolean isOpen = party.getPrivacy() == com.duel.party.PartyPrivacy.OPEN;
                player.performCommand(isOpen ? "party close" : "party open");
                SoundUtils.playGuiClick(player);
                // Refresh the item so its status text updates
                plugin.getPartyManager().refreshHotbar(player);
                return;
            }
            if (isPartyFFA(held)) {
                // فتح نفس GameSelectorGUI بوضع FFA
                FFA_MODE.add(player.getUniqueId());
                GameSelectorGUI.open(plugin, player);
                SoundUtils.playGuiClick(player);
                return;
            }
        }
    }

    private boolean isSettingsClock(ItemStack item) {
        return matchesHotbarItem(item, com.duel.managers.HotbarManager.HotbarItem.SETTINGS);
    }

    private boolean isDuelSword(ItemStack item) {
        return matchesHotbarItem(item, com.duel.managers.HotbarManager.HotbarItem.DUEL_MENU);
    }

    private boolean isKitEditBook(ItemStack item) {
        return matchesHotbarItem(item, com.duel.managers.HotbarManager.HotbarItem.KIT_EDITOR);
    }

    private boolean matchesHotbarItem(ItemStack item, com.duel.managers.HotbarManager.HotbarItem type) {
        if (item == null) return false;
        var meta = item.getItemMeta();
        if (meta == null) return false;
        // Check via NBT tag — reliable, no string comparison issues
        String tag = meta.getPersistentDataContainer().get(
                new org.bukkit.NamespacedKey(plugin, "hotbar_item"),
                org.bukkit.persistence.PersistentDataType.STRING
        );
        return type.configKey.equals(tag);
    }

    private boolean isPartyCreate(ItemStack item) {
        return com.duel.party.PartyHotbar.isCreateItem(item);
    }

    private boolean isPartyInfo(ItemStack item) {
        return com.duel.party.PartyHotbar.isInfoItem(item);
    }

    private boolean isPartyDisband(ItemStack item) {
        return com.duel.party.PartyHotbar.isDisbandItem(item);
    }

    private boolean isPartyFFA(ItemStack item) {
        return com.duel.party.PartyHotbar.isFFAItem(item);
    }

    private boolean isSpectateEye(ItemStack item) {
        return matchesHotbarItem(item, com.duel.managers.HotbarManager.HotbarItem.SPECTATE);
    }

    private boolean isPartyOpenToggle(ItemStack item) {
        return com.duel.party.PartyHotbar.isOpenPartyItem(item);
    }

    private boolean isPartyLeave(ItemStack item) {
        return com.duel.party.PartyHotbar.isLeaveItem(item);
    }

    /**
     * Opens the same GameSelectorGUI look but clicking an arena
     * opens the PlayerKitEditorGUI for that arena instead of queuing.
     * We reuse KitSelectorGUI — a thin wrapper flag stored per-player.
     */
    private static final java.util.Set<java.util.UUID> KIT_SELECTOR_MODE = new java.util.HashSet<>();
    private static final java.util.Set<java.util.UUID> FFA_MODE          = new java.util.HashSet<>();

    /** هل هذا اللاعب فاتح قائمة الـ Party FFA؟ (يستعملها GameSelectorGUI عشان تعرض سهم العشوائي) */
    public static boolean isFfaMode(java.util.UUID id) { return FFA_MODE.contains(id); }

    public static void openKitSelector(DuelPlugin plugin, Player player) {
        KIT_SELECTOR_MODE.add(player.getUniqueId());
        GameSelectorGUI.open(plugin, player);
    }

    // ─────────────────────────────────────────
    //  Inventory click handler
    // ─────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null) return;

        InventoryHolder holder = event.getView().getTopInventory().getHolder();

        // ── Party Teams GUI (Party FFA → Team) ──
        if (holder instanceof com.duel.gui.PartyTeamGUI teamGui) {
            event.setCancelled(true);
            // نتجاهل كليكات انفنتري اللاعب السفلي، نتعامل بس مع الـ GUI العلوي
            if (event.getRawSlot() < com.duel.gui.PartyTeamGUI.SIZE) {
                teamGui.handleClick(player, event.getRawSlot());
            }
            return;
        }

        // ── Party Teams GUI بصفحات (أكثر من 30 لاعب) ──
        if (holder instanceof com.duel.gui.PartyTeamPagedGUI pagedGui) {
            event.setCancelled(true);
            if (event.getRawSlot() < com.duel.gui.PartyTeamPagedGUI.SIZE) {
                pagedGui.handleClick(player, event.getRawSlot(), event.isRightClick());
            }
            return;
        }

        // ── Party Mode GUI (FFA / Team بعد اختيار الأرينا) ──
        if (holder instanceof com.duel.gui.PartyModeGUI modeGui) {
            event.setCancelled(true);
            if (event.getRawSlot() < com.duel.gui.PartyModeGUI.SIZE) {
                modeGui.handleClick(player, event.getRawSlot());
            }
            return;
        }

        // ── Settings GUI ──
        if (holder instanceof SettingsGUI settingsGui) {
            event.setCancelled(true);
            handleSettingsClick(player, settingsGui, event.getSlot());
            return;
        }

        // ── Game Selector ──
        if (holder instanceof GameSelectorGUI) {
            event.setCancelled(true);
            handleSelectorClick(player, event.getSlot(), event.getCurrentItem());
            return;
        }

        // ── Duel Arena Picker (sender chooses game for /duel) ──
        if (holder instanceof DuelArenaPickerGUI pickerGui) {
            event.setCancelled(true);
            // نتجاهل كليكات انفنتري اللاعب السفلي (نفس أرقام الخانات تتعارض مع الأرينات)
            if (event.getRawSlot() >= 0 && event.getRawSlot() < 45) {
                handleArenaPickerClick(player, pickerGui, event.getRawSlot(), event.getCurrentItem());
            }
            return;
        }

        // ── Duel Confirm GUI (sender confirms before the request is sent) ──
        if (holder instanceof DuelConfirmGUI confirmGui) {
            event.setCancelled(true);
            if (event.getRawSlot() < DuelConfirmGUI.SIZE) {
                handleConfirmClick(player, confirmGui, event.getRawSlot());
            }
            return;
        }

        // ── Map Selector (sender picks the map for the duel) ──
        if (holder instanceof MapSelectorGUI mapGui) {
            event.setCancelled(true);
            if (event.getRawSlot() >= 0 && event.getRawSlot() < MapSelectorGUI.SIZE) {
                handleMapSelectorClick(player, mapGui, event.getRawSlot());
            }
            return;
        }

        // ── Duel Invite GUI (target accepts/declines) ──
        if (holder instanceof DuelInviteGUI inviteGui) {
            event.setCancelled(true);
            handleInviteClick(player, inviteGui, event.getSlot());
            return;
        }

        // ── Arena Editor ──
        if (holder instanceof ArenaEditorGUI editorGui) {
            if (event.getSlot() >= 45) {
                event.setCancelled(true);
                int slot = event.getSlot();
                UUID uid = player.getUniqueId();
                Inventory top = event.getView().getTopInventory();

                if (slot == ArenaEditorGUI.SAVE_SLOT) {
                    String arenaId = ArenaEditorGUI.OPEN_EDITORS.get(uid);
                    if (arenaId != null) ArenaEditorGUI.onSave(plugin, player, top, arenaId);

                } else if (slot == ArenaEditorGUI.BUILD_SLOT) {
                    boolean next = !ArenaEditorGUI.EDITOR_BUILD.getOrDefault(uid, false);
                    ArenaEditorGUI.EDITOR_BUILD.put(uid, next);
                    top.setItem(slot, ArenaEditorGUI.buildToggle("§e🔨 بناء", next, Material.OAK_PLANKS));
                    SoundUtils.playGuiClick(player);

                } else if (slot == ArenaEditorGUI.BREAK_MAP_SLOT) {
                    boolean next = !ArenaEditorGUI.EDITOR_BREAK.getOrDefault(uid, false);
                    ArenaEditorGUI.EDITOR_BREAK.put(uid, next);
                    top.setItem(slot, ArenaEditorGUI.buildToggle("§c⛏ تكسير الماب", next, Material.STONE));
                    SoundUtils.playGuiClick(player);

                } else if (slot == ArenaEditorGUI.BREAK_PLACED_SLOT) {
                    boolean next = !ArenaEditorGUI.EDITOR_PLACED.getOrDefault(uid, false);
                    ArenaEditorGUI.EDITOR_PLACED.put(uid, next);
                    top.setItem(slot, ArenaEditorGUI.buildToggle("§b⛏ تكسير المبني", next, Material.DIRT));
                    SoundUtils.playGuiClick(player);

                } else if (slot == ArenaEditorGUI.DIFFICULTY_SLOT) {
                    String next = ArenaEditorGUI.nextDifficulty(
                            ArenaEditorGUI.EDITOR_DIFF.getOrDefault(uid, "normal"));
                    ArenaEditorGUI.EDITOR_DIFF.put(uid, next);
                    top.setItem(slot, ArenaEditorGUI.buildDifficulty(next));
                    SoundUtils.playGuiClick(player);
                }
            }
            return;
        }

        // ── Kit Editor (admin) ──
        if (holder instanceof KitEditorGUI) {
            int slot = event.getSlot();
            // Save button only — everything else is free for admin
            if (slot == KitEditorGUI.GUI_SAVE) {
                event.setCancelled(true);
                String kitName = KitEditorGUI.OPEN_EDITORS.get(player.getUniqueId());
                if (kitName != null) {
                    KitEditorGUI.onSave(plugin, player, event.getView().getTopInventory(), kitName);
                }
                return;
            }
            // Admin is free to place/swap/remove any item including armor slots.
            // TrimListener will open TrimGUI only when cursor is empty (left-click with no item).
        }

        // ── Party Disband GUI ──
        if (holder instanceof com.duel.gui.PartyDisbandGUI) {
            event.setCancelled(true);
            if (event.getRawSlot() == com.duel.gui.PartyDisbandGUI.DISBAND_SLOT) {
                SoundUtils.playGuiClick(player);
                player.closeInventory();
                player.performCommand("party disband");
            }
            return;
        }

        // ── Queue Leave GUI ──
        if (holder instanceof QueueLeaveGUI) {
            event.setCancelled(true);
            if (QueueLeaveGUI.isLeaveSlot(event.getSlot())) {
                plugin.getQueueManager().removeFromQueue(player);
                player.closeInventory();
                SoundUtils.playGuiClick(player);
                player.sendMessage("§c⚔ You left the queue.");
            }
            return;
        }

        // ── Player Kit Editor ──
        if (holder instanceof PlayerKitEditorGUI) {
            // Always use getRawSlot — getSlot() can return wrong values when a cursor
            // item is being placed, causing the filler check to be bypassed.
            int slot = event.getRawSlot();

            // Block number-key hotswap (1-9 while hovering any GUI slot)
            if (event.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY) {
                event.setCancelled(true);
                return;
            }

            // Block double-click collect — can pull filler into cursor
            if (event.getAction() == org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR) {
                event.setCancelled(true);
                return;
            }

            // Save button
            if (slot == PlayerKitEditorGUI.GUI_SAVE) {
                event.setCancelled(true);
                String arenaId = PlayerKitEditorGUI.OPEN_EDITORS.get(player.getUniqueId());
                if (arenaId != null) {
                    PlayerKitEditorGUI.onSave(plugin, player, event.getView().getTopInventory(), arenaId);
                }
                return;
            }

            boolean clickedBottom = slot >= PlayerKitEditorGUI.SIZE;

            // Block shift-click FROM player inventory (would push into GUI unpredictably)
            if (clickedBottom && event.isShiftClick()) {
                event.setCancelled(true);
                return;
            }

            // Block all other clicks originating from the bottom player inventory
            if (clickedBottom) {
                event.setCancelled(true);
                return;
            }

            // ── TOP inventory clicks ──────────────────────────────────────────
            // Block filler glass and every other non-editable slot.
            // This also fires when the cursor holds an item and the player tries
            // to place it on a locked slot (PLACE_ONE / PLACE_ALL / SWAP_WITH_CURSOR).
            if (!PlayerKitEditorGUI.isEditableSlot(slot)) {
                event.setCancelled(true);
                // If the player managed to pick up the filler somehow, restore inventory
                if (event.getCursor() != null
                        && event.getCursor().getType() == Material.GRAY_STAINED_GLASS_PANE) {
                    Bukkit.getScheduler().runTask(plugin, player::updateInventory);
                }
                return;
            }

            // ── Armor slots: fully locked — any click opens TrimGUI ──────────
            boolean isArmorGuiSlot = slot == PlayerKitEditorGUI.GUI_HELMET
                    || slot == PlayerKitEditorGUI.GUI_CHESTPLATE
                    || slot == PlayerKitEditorGUI.GUI_LEGGINGS
                    || slot == PlayerKitEditorGUI.GUI_BOOTS;

            if (isArmorGuiSlot) {
                event.setCancelled(true);
                com.duel.trim.TrimManager.ArmorSlot armorSlot = switch (slot) {
                    case PlayerKitEditorGUI.GUI_HELMET     -> com.duel.trim.TrimManager.ArmorSlot.HELMET;
                    case PlayerKitEditorGUI.GUI_CHESTPLATE -> com.duel.trim.TrimManager.ArmorSlot.CHESTPLATE;
                    case PlayerKitEditorGUI.GUI_LEGGINGS   -> com.duel.trim.TrimManager.ArmorSlot.LEGGINGS;
                    case PlayerKitEditorGUI.GUI_BOOTS      -> com.duel.trim.TrimManager.ArmorSlot.BOOTS;
                    default -> null;
                };
                if (armorSlot != null) {
                    String arenaId = PlayerKitEditorGUI.OPEN_EDITORS.get(player.getUniqueId());
                    com.duel.utils.SoundUtils.playGuiClick(player);
                    com.duel.trim.TrimGUI.open(plugin, player, armorSlot, arenaId);
                }
                return;
            }

            // Allow reordering within editable GUI slots only
        }
    }

    // ─────────────────────────────────────────
    //  Selector click → queue player
    // ─────────────────────────────────────────

    private void handleSelectorClick(Player player, int slot, ItemStack clicked) {
        if (clicked == null || clicked.getType() == Material.AIR) return;
        if (clicked.getType() == Material.BLACK_STAINED_GLASS_PANE) return;
        if (clicked.getType() == Material.BARRIER) return;

        // Retrieve arena directly from the GUI's slot→arena map
        InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
        if (!(holder instanceof GameSelectorGUI selectorGui)) return;

        // ── سهم الأرينا العشوائية ──
        if (slot == GameSelectorGUI.RANDOM_SLOT && clicked.getType() == Material.SPECTRAL_ARROW) {
            handleRandomArrow(player, selectorGui);
            return;
        }

        // ── زر الخروج من كل الكيوهات ──
        if (selectorGui.isQueueMode() && slot == GameSelectorGUI.LEAVE_SLOT) {
            if (plugin.getQueueManager().isQueued(player)) {
                plugin.getQueueManager().removeFromQueue(player);
                SoundUtils.playGuiClick(player);
                player.sendMessage("§c⚔ You left all queues.");
            }
            return;
        }

        Arena arena = selectorGui.getArenaAt(slot);
        if (arena == null) return;

        // ── Kit selector mode → open PlayerKitEditorGUI ──
        if (KIT_SELECTOR_MODE.remove(player.getUniqueId())) {
            player.closeInventory();
            SoundUtils.playGuiClick(player);
            PlayerKitEditorGUI.open(plugin, player, arena.getId());
            return;
        }

        // ── FFA mode → ابدأ Party FFA بالأرينا المختارة ──
        if (FFA_MODE.remove(player.getUniqueId())) {
            SoundUtils.playGuiClick(player);
            com.duel.party.Party ffaParty = plugin.getPartyManager().getParty(player);
            if (ffaParty == null || !ffaParty.getLeader().equals(player)) {
                player.closeInventory();
                player.sendMessage("§c[Party] You must be a party leader to start FFA.");
                return;
            }
            // بدل البدء مباشرة: افتح GUI اختيار النوع (FFA / Team)
            com.duel.gui.PartyModeGUI.open(plugin, player, arena);
            return;
        }

        // ── Normal mode → queue ──
        if (plugin.getDuelManager().isInDuel(player)) {
            player.closeInventory();
            player.sendMessage("§c⚔ You are currently in a duel!");
            return;
        }
        if (plugin.getPartyManager().isInParty(player)) {
            player.closeInventory();
            player.sendMessage("§c⚔ You can't duel while in a party. Leave your party first.");
            return;
        }
        if (pendingMatch.contains(player.getUniqueId())) {
            player.closeInventory();
            return;
        }

        // ── كليك على أرينا أنت فيها أصلاً → اطلع منها (الـ GUI يبقى مفتوح) ──
        if (plugin.getQueueManager().isQueuedFor(player, arena)) {
            plugin.getQueueManager().removeFromArena(player, arena);
            SoundUtils.playGuiClick(player);
            player.sendMessage("§c⚔ Left queue for §e" + arena.getId() + "§c.");
            return;
        }

        SoundUtils.playQueueJoin(player);

        Player opponent = plugin.getQueueManager().addToQueue(player, arena);

        if (opponent != null) {
            // لو الخصم كان ينتظر في الكيو العشوائي → ما نكشف له الأرينا إلا بعد النقل
            startQueueMatch(player, opponent, arena, false, plugin.getQueueManager().consumeRandomMatched(opponent));
        } else {
            // الـ GUI يبقى مفتوح عشان تقدر تختار أكثر من كيو
            player.sendMessage("§aJoined queue for §e" + arena.getId() + "§a. Waiting for opponent...");
        }
    }

    /** يبدأ مباراة كيو لاعبين (أرينا عادية أو عشوائية). *Random = هذا اللاعب ما يعرف الأرينا لين النقل. */
    private void startQueueMatch(Player player, Player opponent, Arena arena, boolean playerRandom, boolean opponentRandom) {
        // سكّر القائمة للاثنين (باقي كيوهاتهم انلغت تلقائياً)
        player.closeInventory();
        if (opponent.getOpenInventory().getTopInventory().getHolder() instanceof GameSelectorGUI) {
            opponent.closeInventory();
        }
        SoundUtils.playQueueFound(player);
        SoundUtils.playQueueFound(opponent);
        if (playerRandom)   com.duel.utils.TotemPop.showRandom(plugin, player);
        else                com.duel.utils.TotemPop.show(plugin, player, arena);
        if (opponentRandom) com.duel.utils.TotemPop.showRandom(plugin, opponent);
        else                com.duel.utils.TotemPop.show(plugin, opponent, arena);
        player.sendMessage("§a⚔ Match found! Starting duel with §e" + opponent.getName());
        opponent.sendMessage("§a⚔ Match found! Starting duel with §e" + player.getName());
        pendingMatch.add(player.getUniqueId());
        pendingMatch.add(opponent.getUniqueId());
        // التجهيز يبدأ مع أنيميشن التوتم، والنقل ينتظر لين يخلص (INTRO_TICKS)
        if (plugin.getDuelManager().isInDuel(player) || plugin.getDuelManager().isInDuel(opponent)) {
            pendingMatch.remove(player.getUniqueId());
            pendingMatch.remove(opponent.getUniqueId());
        } else {
            if (playerRandom)   plugin.getDuelManager().markRandom(player);
            if (opponentRandom) plugin.getDuelManager().markRandom(opponent);
            // نظام التيرات: لو أحدهم عنده Rankup Challenge → المباراة best-of (راوندات)، أو Tier Defense
            int tierRounds = 1;
            try { tierRounds = plugin.getTierManager().prepareMatch(player, opponent, arena.getId()); }
            catch (Throwable t) { plugin.getLogger().log(java.util.logging.Level.WARNING, "[Tier] prepareMatch failed", t); }
            plugin.getDuelManager().startDuel(player, opponent, arena, tierRounds, null, DuelManager.INTRO_TICKS, true);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                pendingMatch.remove(player.getUniqueId());
                pendingMatch.remove(opponent.getUniqueId());
            }, DuelManager.INTRO_TICKS);
        }
    }

    /** السهم في قائمة الكيو (كيو عشوائي، كليك ثاني = إيقاف) أو في قائمة Party FFA (أرينا عشوائية). */
    private void handleRandomArrow(Player player, GameSelectorGUI gui) {
        // ── Party FFA: نختار أرينا مخفية ونفتح اختيار النوع (FFA / Team) ──
        if (FFA_MODE.contains(player.getUniqueId())) {
            FFA_MODE.remove(player.getUniqueId());
            SoundUtils.playGuiClick(player);
            com.duel.party.Party ffaParty = plugin.getPartyManager().getParty(player);
            if (ffaParty == null || !ffaParty.getLeader().equals(player)) {
                player.closeInventory();
                player.sendMessage("§c[Party] You must be a party leader to start FFA.");
                return;
            }
            Arena picked = com.duel.utils.RandomArena.pick(plugin);
            if (picked == null) {
                player.closeInventory();
                player.sendMessage("§c⚔ No arenas are available right now.");
                return;
            }
            com.duel.gui.PartyModeGUI.open(plugin, player, picked, com.duel.utils.RandomArena.TOKEN);
            return;
        }
        if (!gui.isQueueMode()) return; // وضع الكيت — ما فيه سهم

        // ── كيو عادي ──
        if (plugin.getDuelManager().isInDuel(player)) {
            player.closeInventory();
            player.sendMessage("§c⚔ You are currently in a duel!");
            return;
        }
        if (plugin.getPartyManager().isInParty(player)) {
            player.closeInventory();
            player.sendMessage("§c⚔ You can't duel while in a party. Leave your party first.");
            return;
        }
        if (pendingMatch.contains(player.getUniqueId())) {
            player.closeInventory();
            return;
        }

        // كليك ثاني على السهم = إيقاف الكيو العشوائي (القائمة تبقى مفتوحة)
        if (plugin.getQueueManager().isQueuedRandom(player)) {
            plugin.getQueueManager().removeFromRandom(player);
            SoundUtils.playGuiClick(player);
            player.sendMessage("§c⚔ Left the random queue.");
            return;
        }

        if (com.duel.utils.RandomArena.pick(plugin) == null) {
            player.sendMessage("§c⚔ No arenas are available right now.");
            return;
        }

        SoundUtils.playQueueJoin(player);
        com.duel.managers.QueueManager.RandomMatch m = plugin.getQueueManager().addToRandomQueue(player);
        if (m != null) {
            startQueueMatch(player, m.opponent(), m.arena(), true, m.opponentWasRandom());
        } else {
            player.sendMessage("§aJoined the random queue. Waiting for opponent...");
        }
    }

    // ─────────────────────────────────────────
    //  Duel Arena Picker → sender chose a game → send invite to target
    // ─────────────────────────────────────────

    private void handleArenaPickerClick(Player sender, DuelArenaPickerGUI gui, int slot, ItemStack clicked) {
        if (clicked == null || clicked.getType() == Material.AIR) return;

        // القيد فقط على خانة راس اللاعب (4) — أي راس ثاني (مثل أيتم أرينا) يشتغل عادي
        if (slot == DuelArenaPickerGUI.TARGET_SLOT) return;

        Player target = gui.getTarget();

        // ── سهم الأرينا العشوائية → نفتح شاشة التأكيد بأرينا مخفية ──
        if (slot == DuelArenaPickerGUI.RANDOM_SLOT && clicked.getType() == Material.SPECTRAL_ARROW) {
            if (!target.isOnline()) {
                sender.closeInventory();
                DuelArenaPickerGUI.PICKER_TARGETS.remove(sender.getUniqueId());
                sender.sendMessage(color("&c⚔ &e" + target.getName() + " &cis now offline."));
                return;
            }
            Arena picked = com.duel.utils.RandomArena.pick(plugin);
            if (picked == null) {
                sender.sendMessage(color("&c⚔ No arenas are available right now."));
                return;
            }
            SoundUtils.playGuiClick(sender);
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> DuelConfirmGUI.open(plugin, sender, target, picked, 1, com.duel.utils.RandomArena.TOKEN));
            return;
        }

        // نفس GameSelectorGUI: الأرينا من خريطة slot → arena (الإطار/الفاضي يرجع null)
        Arena arena = gui.getArenaAt(slot);
        if (arena == null) return;

        if (!target.isOnline()) {
            sender.closeInventory();
            DuelArenaPickerGUI.PICKER_TARGETS.remove(sender.getUniqueId());
            sender.sendMessage(color("&c⚔ &e" + target.getName() + " &cis now offline."));
            return;
        }

        // Show the confirmation GUI first — the request is only sent on "Continue".
        // Opened next tick so we don't swap inventories inside the click event.
        plugin.getServer().getScheduler().runTask(plugin,
                () -> DuelConfirmGUI.open(plugin, sender, target, arena));
    }

    // ─────────────────────────────────────────
    //  Duel Confirm GUI → Continue sends the request, Rounds clock opens the anvil, Cancel aborts
    // ─────────────────────────────────────────

    private void handleConfirmClick(Player sender, DuelConfirmGUI gui, int slot) {
        if (slot == DuelConfirmGUI.SLOT_CONFIRM) {
            // Continue → send the request directly with the chosen rounds (default 1)
            sendDuelRequest(sender, gui.getTarget(), gui.getArena(), gui.getRounds(), gui.getMapWorld());
        } else if (slot == DuelConfirmGUI.SLOT_MAP) {
            if (gui.isRandom()) return; // أرينا عشوائية: الماب عشوائية كمان
            Player target = gui.getTarget();
            if (!target.isOnline()) {
                sender.closeInventory();
                sender.sendMessage(color("&c⚔ &e" + target.getName() + " &cis now offline."));
                return;
            }
            SoundUtils.playGuiClick(sender);
            // Open next tick (don't swap inventories inside the click event)
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> MapSelectorGUI.open(plugin, sender, target, gui.getArena(), gui.getRounds(), gui.getMapWorld()));
        } else if (slot == DuelConfirmGUI.SLOT_ROUNDS) {
            Player target = gui.getTarget();
            Arena arena   = gui.getArena();
            int rounds    = gui.getRounds();
            String map    = gui.getMapWorld();
            if (!target.isOnline()) {
                sender.closeInventory();
                sender.sendMessage(color("&c⚔ &e" + target.getName() + " &cis now offline."));
                return;
            }
            SoundUtils.playGuiClick(sender);
            // Open the anvil next tick (don't swap inventories inside the click event)
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> RoundsAnvilGUI.open(plugin, sender, target, arena, rounds, map));
        } else if (slot == DuelConfirmGUI.SLOT_CANCEL) {
            sender.closeInventory();
            DuelArenaPickerGUI.PICKER_TARGETS.remove(sender.getUniqueId());
            sender.sendMessage(color("&c⚔ Duel challenge cancelled."));
        }
    }

    /** Map clicked → back to the Duel confirm GUI with that map selected. */
    private void handleMapSelectorClick(Player sender, MapSelectorGUI gui, int slot) {
        String world = gui.getWorldAt(slot);
        if (world == null) return; // glass / empty slot

        // Party mode: hand the chosen map back to the party GUI
        if (gui.getOnPick() != null) {
            SoundUtils.playGuiClick(sender);
            plugin.getServer().getScheduler().runTask(plugin, () -> gui.getOnPick().accept(world));
            return;
        }

        Player target = gui.getTarget();
        if (!target.isOnline()) {
            sender.closeInventory();
            sender.sendMessage(color("&c⚔ &e" + target.getName() + " &cis now offline."));
            return;
        }
        SoundUtils.playGuiClick(sender);
        plugin.getServer().getScheduler().runTask(plugin,
                () -> DuelConfirmGUI.open(plugin, sender, target, gui.getArena(), gui.getRounds(), world));
    }

    private void sendDuelRequest(Player sender, Player target, Arena arena, int rounds, String mapWorld) {
        sender.closeInventory();
        DuelArenaPickerGUI.PICKER_TARGETS.remove(sender.getUniqueId());
        final boolean rnd = com.duel.utils.RandomArena.is(mapWorld);
        final String arenaShown = rnd ? com.duel.utils.RandomArena.NAME : arena.getId();
        final String mapShown = rnd ? null : mapWorld;

        // Validate target is still valid
        if (!target.isOnline()) {
            sender.sendMessage(color("&c⚔ &e" + target.getName() + " &cis now offline."));
            return;
        }
        if (plugin.getDuelManager().isInDuel(sender) || plugin.getQueueManager().isQueued(sender)
                || plugin.getDuelRequestManager().hasPendingRequestAsSender(sender)) {
            sender.sendMessage(color("&c⚔ You can't send a request right now."));
            return;
        }
        if (plugin.getPartyManager().isInParty(sender)) {
            sender.sendMessage("§c⚔ You can't duel while in a party. Leave your party first.");
            return;
        }
        if (plugin.getPartyManager().isInParty(target)) {
            sender.sendMessage(color("&c⚔ &e" + target.getName() + " &cis in a party and can't duel."));
            return;
        }
        if (plugin.getQueueManager().isQueued(target)) {
            sender.sendMessage(color("&c⚔ &e" + target.getName() + " &cis currently in queue."));
            return;
        }
        if (plugin.getDuelRequestManager().hasPendingRequestAsSender(target)) {
            sender.sendMessage(color("&c⚔ &e" + target.getName() + " &cis busy with another request."));
            return;
        }
        if (plugin.getDuelManager().isInDuel(target)) {
            sender.sendMessage(color("&c⚔ &e" + target.getName() + " &chas entered another duel."));
            return;
        }
        if (plugin.getDuelRequestManager().hasPendingRequestAsTarget(target)) {
            sender.sendMessage(color("&c⚔ &e" + target.getName() + " &calready has a pending request."));
            return;
        }

        if (plugin.getSettingsManager().isEnabled(target.getUniqueId(), com.duel.managers.SettingsManager.Setting.BLOCK_DUEL_REQUESTS)) {
            sender.sendMessage(color("&c⚔ &e" + target.getName() + " &chas disabled duel requests."));
            return;
        }

        // Create request and notify target
        plugin.getDuelRequestManager().createRequest(sender, target, arena, rounds, mapWorld);

        sender.sendMessage(color("&a⚔ Duel request sent to &e" + target.getName()
                + " &ain arena &b" + arenaShown + " &7(&e" + rounds + (rounds == 1 ? " round" : " rounds")
                + "&7)" + (mapShown != null ? " &7on &d" + arena.getWorldDisplayName(mapShown) : "")
                + "&a. Waiting for response..."));
        SoundUtils.playQueueJoin(sender);

        // Notify target with clickable chat message only
        net.kyori.adventure.text.Component acceptBtn = net.kyori.adventure.text.Component.text("[✔  Accept]")
                .color(net.kyori.adventure.text.format.NamedTextColor.GREEN)
                .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/duelaccept"))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        net.kyori.adventure.text.Component.text("Click to accept the duel").color(net.kyori.adventure.text.format.NamedTextColor.GREEN)));

        net.kyori.adventure.text.Component declineBtn = net.kyori.adventure.text.Component.text("[✘  Decline]")
                .color(net.kyori.adventure.text.format.NamedTextColor.RED)
                .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/dueldecline"))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        net.kyori.adventure.text.Component.text("Click to decline").color(net.kyori.adventure.text.format.NamedTextColor.RED)));

        int ping = sender.getPing();
        net.kyori.adventure.text.format.NamedTextColor pingColor = ping < 80 ? net.kyori.adventure.text.format.NamedTextColor.GREEN
                : ping < 150 ? net.kyori.adventure.text.format.NamedTextColor.YELLOW
                : net.kyori.adventure.text.format.NamedTextColor.RED;

        target.sendMessage(net.kyori.adventure.text.Component.empty());
        target.sendMessage(net.kyori.adventure.text.Component.text("⚔  " + sender.getName() + " has challenged you to a 1v1 duel!")
                .color(net.kyori.adventure.text.format.NamedTextColor.GOLD)
                .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD));
        target.sendMessage(net.kyori.adventure.text.Component.text("Challenger: ")
                .color(net.kyori.adventure.text.format.NamedTextColor.GRAY)
                .append(net.kyori.adventure.text.Component.text(sender.getName()).color(net.kyori.adventure.text.format.NamedTextColor.WHITE))
                .append(net.kyori.adventure.text.Component.text("   Ping: ").color(net.kyori.adventure.text.format.NamedTextColor.GRAY))
                .append(net.kyori.adventure.text.Component.text(ping + "ms").color(pingColor))
                .append(net.kyori.adventure.text.Component.text("   Arena: ").color(net.kyori.adventure.text.format.NamedTextColor.GRAY))
                .append(net.kyori.adventure.text.Component.text(arenaShown).color(net.kyori.adventure.text.format.NamedTextColor.AQUA))
                .append(net.kyori.adventure.text.Component.text("   Rounds: ").color(net.kyori.adventure.text.format.NamedTextColor.GRAY))
                .append(net.kyori.adventure.text.Component.text(String.valueOf(rounds)).color(net.kyori.adventure.text.format.NamedTextColor.YELLOW))
                .append(net.kyori.adventure.text.Component.text(mapShown != null ? "   Map: " : "").color(net.kyori.adventure.text.format.NamedTextColor.GRAY))
                .append(net.kyori.adventure.text.Component.text(mapShown != null ? arena.getWorldDisplayName(mapShown) : "").color(net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE)));
        target.sendMessage(net.kyori.adventure.text.Component.text("[✔  Accept]")
                .color(net.kyori.adventure.text.format.NamedTextColor.GREEN)
                .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/duelaccept"))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        net.kyori.adventure.text.Component.text("Click to accept the duel").color(net.kyori.adventure.text.format.NamedTextColor.GREEN)))
                .append(net.kyori.adventure.text.Component.text("  ").color(net.kyori.adventure.text.format.NamedTextColor.GRAY))
                .append(net.kyori.adventure.text.Component.text("[✘  Decline]")
                        .color(net.kyori.adventure.text.format.NamedTextColor.RED)
                        .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/dueldecline"))
                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                                net.kyori.adventure.text.Component.text("Click to decline").color(net.kyori.adventure.text.format.NamedTextColor.RED)))));
        target.sendMessage(net.kyori.adventure.text.Component.empty());
        SoundUtils.playQueueFound(target);
    }

    // ─────────────────────────────────────────
    //  Duel Invite GUI → target accepts or declines
    // ─────────────────────────────────────────

    private void handleInviteClick(Player target, DuelInviteGUI gui, int slot) {
        DuelRequest request = gui.getRequest();

        if (slot == DuelInviteGUI.SLOT_ACCEPT) {
            target.closeInventory();
            plugin.getDuelRequestManager().removeRequest(target);

            Player sender = request.getSender();
            Arena arena   = request.getArena();

            if (!sender.isOnline()) {
                target.sendMessage(color("&c⚔ The challenger &e" + sender.getName() + "&c has disconnected."));
                return;
            }
            if (plugin.getPartyManager().isInParty(sender) || plugin.getPartyManager().isInParty(target)) {
                target.sendMessage(color("&c⚔ One of the players is in a party. Duel cancelled."));
                if (sender.isOnline()) sender.sendMessage(color("&c⚔ Duel cancelled because one of you is in a party."));
                return;
            }
            if (plugin.getQueueManager().isQueued(sender) || plugin.getQueueManager().isQueued(target)) {
                target.sendMessage(color("&c⚔ One of the players is in queue."));
                return;
            }
            if (plugin.getDuelManager().isInDuel(sender) || plugin.getDuelManager().isInDuel(target)) {
                target.sendMessage(color("&c⚔ One of the players has already entered another duel."));
                sender.sendMessage(color("&c⚔ The match was not started because one of you is in a duel."));
                return;
            }

            sender.sendMessage(color("&a⚔ &e" + target.getName() + "&a accepted the challenge! Starting the match..."));
            target.sendMessage(color("&a⚔ You accepted the challenge! Entering the arena..."));
            SoundUtils.playQueueFound(sender);
            SoundUtils.playQueueFound(target);

            plugin.getDuelManager().startDuelWithIntro(sender, target, arena, request.getRounds(), request.getMapWorld());

        } else if (slot == DuelInviteGUI.SLOT_DECLINE) {
            target.closeInventory();
            plugin.getDuelRequestManager().removeRequest(target);

            Player sender = request.getSender();
            target.sendMessage(color("&c⚔ You declined the duel request from &e" + sender.getName() + "&c."));
            if (sender.isOnline()) {
                sender.sendMessage(color("&c⚔ &e" + target.getName() + "&c declined your duel request."));
                SoundUtils.playLoser(sender);
            }
        }
    }

    // ─────────────────────────────────────────
    //  Settings GUI click
    // ─────────────────────────────────────────

    private void handleSettingsClick(Player player, SettingsGUI gui, int slot) {
        SettingsManager.Setting setting = null;

        if (slot == SettingsGUI.SLOT_HIDE_CHAT)        setting = SettingsManager.Setting.HIDE_CHAT;
        else if (slot == SettingsGUI.SLOT_HIDE_SCOREBOARD) setting = SettingsManager.Setting.HIDE_SCOREBOARD;
        else if (slot == SettingsGUI.SLOT_HIDE_PLAYERS)         setting = SettingsManager.Setting.HIDE_PLAYERS;
        else if (slot == SettingsGUI.SLOT_BLOCK_DUEL_REQUESTS)  setting = SettingsManager.Setting.BLOCK_DUEL_REQUESTS;
        else if (slot == SettingsGUI.SLOT_MUTE_SOUNDS)             setting = SettingsManager.Setting.MUTE_SOUNDS;
        else if (slot == SettingsGUI.SLOT_HIDE_ACTIONBAR)          setting = SettingsManager.Setting.HIDE_ACTIONBAR;
        else if (slot == SettingsGUI.SLOT_HIDE_DEATH_MESSAGES)     setting = SettingsManager.Setting.HIDE_DEATH_MESSAGES;
        else if (slot == SettingsGUI.SLOT_HIDE_JOIN_QUIT)          setting = SettingsManager.Setting.HIDE_JOIN_QUIT_MESSAGES;
        else if (slot == SettingsGUI.SLOT_AURA_GG)                 setting = SettingsManager.Setting.AURA_GG;
        else if (slot == SettingsGUI.SLOT_HIDE_RANKUP_TITLE)       setting = SettingsManager.Setting.HIDE_RANKUP_TITLE;

        if (setting == null) return;

        plugin.getSettingsManager().toggle(player, setting);
        SoundUtils.playGuiClick(player);

        // Reopen GUI to refresh state
        plugin.getServer().getScheduler().runTask(plugin, () -> SettingsGUI.open(plugin, player));
    }

    private String pingColored(Player p) {
        int ping = p.getPing();
        String code = ping < 80 ? "&a" : ping < 150 ? "&e" : "&c";
        return color(code + ping + "ms");
    }

    private String color(String s) {
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', s);
    }

    /** Returns true if the item's material name ends with the given armor type suffix. */
    private boolean isArmorType(ItemStack item, String suffix) {
        if (item == null) return false;
        return item.getType().name().endsWith("_" + suffix);
    }


    // ─────────────────────────────────────────
    //  Hotbar Lock — prevent moving/dropping hotbar items
    // ─────────────────────────────────────────

    /**
     * Returns true if slot (0-8) in the given player inventory contains a plugin hotbar item.
     */
    private boolean isHotbarPluginItem(org.bukkit.inventory.PlayerInventory inv, int slot) {
        if (slot < 0 || slot > 8) return false;
        return isPluginItem(inv.getItem(slot));
    }

    /**
     * Helper — is this item a plugin hotbar item (by NBT or party tag)?
     */
    private boolean isPluginItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        var meta = item.getItemMeta();
        if (meta != null) {
            String tag = meta.getPersistentDataContainer().get(
                    new org.bukkit.NamespacedKey(plugin, "hotbar_item"),
                    org.bukkit.persistence.PersistentDataType.STRING
            );
            if (tag != null) return true;
        }
        if (com.duel.party.PartyHotbar.isAnyPartyItem(item)) return true;
        return plugin.getRematchManager().isRematchItem(item);
    }

    /**
     * Full hotbar lock — blocks every interaction type that could move,
     * duplicate, or destroy a plugin hotbar item:
     *   • Normal left/right click (pick up)
     *   • Shift-click (move to other inventory)
     *   • Number-key hotswap (1-9 while hovering another slot)
     *   • Double-click collect
     *   • Drag (START / ADD / STOP phases)
     *   • Any cursor drop onto a locked slot
     *
     * Works regardless of whether a GUI is open on top — that was the
     * root cause of the duplication bug (original code bailed out early
     * when any GUI holder was present).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHotbarItemMove(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null) return;

        // ── Skip our own GUI handlers (they cancel themselves) ──────────
        InventoryHolder topHolder = event.getView().getTopInventory().getHolder();
        if (topHolder instanceof SettingsGUI
                || topHolder instanceof GameSelectorGUI
                || topHolder instanceof DuelArenaPickerGUI
                || topHolder instanceof DuelInviteGUI
                || topHolder instanceof DuelConfirmGUI
                || topHolder instanceof MapSelectorGUI
                || topHolder instanceof QueueLeaveGUI
                || topHolder instanceof com.duel.gui.PartyDisbandGUI
                || topHolder instanceof ArenaEditorGUI
                || topHolder instanceof KitEditorGUI
                || topHolder instanceof PlayerKitEditorGUI) {
            // These GUIs handle their own cancellation above; don't interfere.
            return;
        }

        org.bukkit.event.inventory.ClickType click  = event.getClick();
        org.bukkit.event.inventory.InventoryAction action = event.getAction();
        boolean clickedPlayerInv = event.getClickedInventory().equals(player.getInventory());

        // ── 1. Clicked slot contains a plugin item → block everything ────
        if (clickedPlayerInv) {
            int slot = event.getSlot();
            if (slot >= 0 && slot <= 8 && isHotbarPluginItem(player.getInventory(), slot)) {
                event.setCancelled(true);
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> player.updateInventory());
                return;
            }
        }

        // ── 2. Cursor is a plugin item → block placing it anywhere ───────
        ItemStack cursor = event.getCursor();
        if (isPluginItem(cursor)) {
            event.setCancelled(true);
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> player.updateInventory());
            return;
        }

        // ── 3. Number-key hotswap: the hotbar slot being swapped-to ──────
        //    (slot under cursor may be anywhere; hotbar slot = getHotbarButton())
        if (click == org.bukkit.event.inventory.ClickType.NUMBER_KEY) {
            int hotbarSlot = event.getHotbarButton();
            if (isHotbarPluginItem(player.getInventory(), hotbarSlot)) {
                event.setCancelled(true);
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> player.updateInventory());
                return;
            }
        }

        // ── 4. Double-click collect: would pull plugin items into cursor ──
        if (action == org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR) {
            // If the cursor item matches any hotbar plugin item type, cancel
            if (cursor != null && cursor.getType() != Material.AIR) {
                for (int i = 0; i <= 8; i++) {
                    if (isHotbarPluginItem(player.getInventory(), i)) {
                        event.setCancelled(true);
                        plugin.getServer().getScheduler().runTask(plugin,
                                () -> player.updateInventory());
                        return;
                    }
                }
            }
        }

        // ── 5. Shift-click from any inventory: would push into hotbar ────
        if (click == org.bukkit.event.inventory.ClickType.SHIFT_LEFT
                || click == org.bukkit.event.inventory.ClickType.SHIFT_RIGHT) {
            // Block shift-click INTO hotbar slots (Bukkit fills lowest slot)
            ItemStack current = event.getCurrentItem();
            if (isPluginItem(current)) {
                event.setCancelled(true);
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> player.updateInventory());
            }
        }
    }

    /** Prevent dropping hotbar plugin items via Q key or drag-out. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHotbarItemDrop(PlayerDropItemEvent event) {
        ItemStack dropped = event.getItemDrop().getItemStack();
        if (dropped == null) return;
        var meta = dropped.getItemMeta();
        if (meta == null) return;
        String tag = meta.getPersistentDataContainer().get(
                new org.bukkit.NamespacedKey(plugin, "hotbar_item"),
                org.bukkit.persistence.PersistentDataType.STRING
        );
        if (tag != null) {
            event.setCancelled(true);
            return;
        }
        if (com.duel.party.PartyHotbar.isAnyPartyItem(dropped)) {
            event.setCancelled(true);
        }
    }

    /** Prevent F key (swap to off-hand) for hotbar plugin items. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHotbarSwapHand(PlayerSwapHandItemsEvent event) {
        ItemStack main = event.getMainHandItem();
        ItemStack off  = event.getOffHandItem();
        if (main != null) {
            var meta = main.getItemMeta();
            if (meta != null) {
                String tag = meta.getPersistentDataContainer().get(
                        new org.bukkit.NamespacedKey(plugin, "hotbar_item"),
                        org.bukkit.persistence.PersistentDataType.STRING
                );
                if (tag != null) { event.setCancelled(true); return; }
            }
            if (com.duel.party.PartyHotbar.isAnyPartyItem(main)) { event.setCancelled(true); return; }
        }
        if (off != null) {
            var meta = off.getItemMeta();
            if (meta != null) {
                String tag = meta.getPersistentDataContainer().get(
                        new org.bukkit.NamespacedKey(plugin, "hotbar_item"),
                        org.bukkit.persistence.PersistentDataType.STRING
                );
                if (tag != null) { event.setCancelled(true); return; }
            }
            if (com.duel.party.PartyHotbar.isAnyPartyItem(off)) { event.setCancelled(true); }
        }
    }

    // ─────────────────────────────────────────
    //  Cleanup on close
    // ─────────────────────────────────────────

    /**
     * Block drag-spreading items onto locked/filler slots in PlayerKitEditorGUI.
     * InventoryDragEvent is fired when a player holds an item and drags across slots —
     * it is completely separate from InventoryClickEvent and must be cancelled here
     * to prevent filler glass from being picked up or replaced via drag.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof com.duel.gui.PartyTeamGUI || holder instanceof DuelConfirmGUI || holder instanceof MapSelectorGUI
                || holder instanceof com.duel.gui.PartyTeamPagedGUI || holder instanceof com.duel.gui.PartyModeGUI
                || holder instanceof com.duel.gui.PartyDisbandGUI) {
            event.setCancelled(true);
            return;
        }
        if (!(holder instanceof PlayerKitEditorGUI)) return;

        // If any of the dragged-into slots is non-editable (filler, save, armor, border),
        // cancel the entire drag operation.
        for (int slot : event.getRawSlots()) {
            if (slot >= PlayerKitEditorGUI.SIZE) continue; // player inventory slots — allow
            if (!PlayerKitEditorGUI.isEditableSlot(slot)
                    || slot == PlayerKitEditorGUI.GUI_HELMET
                    || slot == PlayerKitEditorGUI.GUI_CHESTPLATE
                    || slot == PlayerKitEditorGUI.GUI_LEGGINGS
                    || slot == PlayerKitEditorGUI.GUI_BOOTS
                    || slot == PlayerKitEditorGUI.GUI_OFFHAND) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /** Creative mode can clone/take items even when the click is cancelled — block it. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDisbandGuiCreative(org.bukkit.event.inventory.InventoryCreativeEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof com.duel.gui.PartyDisbandGUI) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player p) {
                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                    com.duel.gui.PartyDisbandGUI.scrub(p);
                    p.updateInventory();
                });
            }
        }
    }

    /** Last line of defence: re-cancel any click in the Disband GUI even if another plugin un-cancelled it. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDisbandGuiClickGuard(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof com.duel.gui.PartyDisbandGUI) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        InventoryHolder holder = event.getInventory().getHolder();

        if (holder instanceof com.duel.gui.PartyDisbandGUI) {
            com.duel.gui.PartyDisbandGUI.scrub(player);
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> com.duel.gui.PartyDisbandGUI.scrub(player));
        }

        if (holder instanceof DuelArenaPickerGUI) {
            DuelArenaPickerGUI.PICKER_TARGETS.remove(player.getUniqueId());
        } else if (holder instanceof ArenaEditorGUI) {
            ArenaEditorGUI.OPEN_EDITORS.remove(player.getUniqueId());
            ArenaEditorGUI.EDITOR_BUILD.remove(player.getUniqueId());
            ArenaEditorGUI.EDITOR_BREAK.remove(player.getUniqueId());
            ArenaEditorGUI.EDITOR_PLACED.remove(player.getUniqueId());
            ArenaEditorGUI.EDITOR_DIFF.remove(player.getUniqueId());
        } else if (holder instanceof KitEditorGUI) {
            KitEditorGUI.OPEN_EDITORS.remove(player.getUniqueId());
        } else if (holder instanceof PlayerKitEditorGUI) {
            PlayerKitEditorGUI.OPEN_EDITORS.remove(player.getUniqueId());
            KIT_SELECTOR_MODE.remove(player.getUniqueId());
        } else if (holder instanceof GameSelectorGUI) {
            // لو أغلق الـ GUI بدون اختيار — نمسح FFA_MODE
            FFA_MODE.remove(player.getUniqueId());
            KIT_SELECTOR_MODE.remove(player.getUniqueId());
        }
    }
}
