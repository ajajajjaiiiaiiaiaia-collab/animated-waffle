package com.duel.managers;

import com.duel.DuelPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * يعرض علم دولة اللاعب قبل اسمه في الـ TAB كرأس لاعب (Player Head object component)
 * بدون ريسورس باك. يشتغل فقط للاعبين 1.21.9+ (الباقي ما يشوفون العلم).
 *
 * الأعلام: plugins/DuelPlugin/flags.yml  (رمز الدولة ← texture base64)
 * الدولة: CountryManager (IP lookup).
 *
 * config.yml:
 *   tab-flag.enabled: true
 *   tab-flag.mode: PAPER        # PAPER = البلجن يضبط اسم التاب مباشرة
 *                               # PLACEHOLDER = فقط %duel_flaghead% لـ TAB plugin
 */
public class TabFlagManager {

    private final DuelPlugin plugin;
    private final Map<String, String> textures = new HashMap<>();

    public TabFlagManager(DuelPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        textures.clear();
        File file = new File(plugin.getDataFolder(), "flags.yml");
        if (!file.exists()) plugin.saveResource("flags.yml", false);
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String key : yml.getKeys(false)) {
            String value = yml.getString(key);
            if (value != null && !value.isBlank()) textures.put(key.toUpperCase(), value.trim());
        }
        plugin.getLogger().info("[TabFlag] Loaded " + textures.size() + " flag textures.");
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("tab-flag.enabled", true);
    }

    private boolean paperMode() {
        return "PAPER".equalsIgnoreCase(plugin.getConfig().getString("tab-flag.mode", "PAPER"));
    }

    /** رأس العلم كـ Component، أو null لو ما عندنا علم لهالدولة. */
    public Component flagHead(String countryCode) {
        if (countryCode == null) return null;
        String texture = textures.get(countryCode.toUpperCase());
        if (texture == null) return null;
        return Component.object(
                ObjectContents.playerHead()
                        .profileProperty(PlayerHeadObjectContents.property("textures", texture))
                        .hat(true)
                        .build());
    }

    /**
     * وسم لـ TAB plugin عبر %duel_flaghead%.
     * TAB ما يقبل Base64 داخل <head:...> (يقبل فقط UUID أو اسم لاعب)، فنستخرج
     * الـ hash من رابط التكستشر ونستخدم الوسم <head_texture:HASH>.
     */
    public String flagHeadMiniMessage(String countryCode) {
        if (countryCode == null) return "";
        String texture = textures.get(countryCode.toUpperCase());
        if (texture == null) return "";
        String hash = textureHash(texture);
        return hash == null ? "" : "<head_texture:" + hash + ">";
    }

    private static final java.util.regex.Pattern HASH_PATTERN =
            java.util.regex.Pattern.compile("/texture/([0-9a-fA-F]+)");

    /** يفك Base64 ويرجّع الـ hash الموجود في رابط textures.minecraft.net، أو null. */
    private static String textureHash(String base64) {
        try {
            String json = new String(java.util.Base64.getDecoder().decode(base64),
                    java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher m = HASH_PATTERN.matcher(json);
            return m.find() ? m.group(1).toLowerCase() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** رأس أيتم (PLAYER_HEAD) للعلم — للـ GUI بدون ريسورس باك، أو null لو ما عندنا علم. */
    public org.bukkit.inventory.ItemStack flagSkull(String countryCode) {
        if (countryCode == null) return null;
        String texture = textures.get(countryCode.toUpperCase());
        if (texture == null) return null;
        org.bukkit.inventory.ItemStack skull = new org.bukkit.inventory.ItemStack(org.bukkit.Material.PLAYER_HEAD);
        org.bukkit.inventory.meta.SkullMeta meta = (org.bukkit.inventory.meta.SkullMeta) skull.getItemMeta();
        com.destroystokyo.paper.profile.PlayerProfile profile = org.bukkit.Bukkit.createProfile(
                java.util.UUID.nameUUIDFromBytes(("flag:" + countryCode.toUpperCase()).getBytes()));
        profile.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", texture));
        meta.setPlayerProfile(profile);
        skull.setItemMeta(meta);
        return skull;
    }

    /** يطبّق العلم على اسم اللاعب في التاب (وضع PAPER). */
    public void apply(Player player) {
        if (!enabled() || !paperMode() || player == null || !player.isOnline()) return;
        Component flag = flagHead(plugin.getCountryManager().getCountryCode(player.getUniqueId()));
        if (flag == null) return;
        player.playerListName(flag.append(Component.space()).append(Component.text(player.getName())));
    }

    /** يرجّع اسم التاب الافتراضي. */
    public void reset(Player player) {
        if (player != null && player.isOnline()) player.playerListName(null);
    }

    public void resetAll() {
        if (!enabled() || !paperMode()) return;
        for (Player p : plugin.getServer().getOnlinePlayers()) reset(p);
    }
}
