package me.rosillogames.eggwars.utils.reflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.rosillogames.eggwars.EggWars;

/** Bukkit-API-only implementation for 1.21.5+ (Paper uses Mojang mappings there, so obfuscated NMS names can't be used). */
public class Reflections_1_21_5 implements Reflections
{
    @Override
    public void setArmorStandInvisible(ArmorStand stand)
    {
        stand.setInvisible(true);
    }

    @Override
    public void setTNTSource(TNTPrimed tnt, Player player)
    {
        tnt.setSource(player);
    }

    @Override
    public void setItemAge(Item item, int age)
    {
        if (age == -32768)
        {
            item.setUnlimitedLifetime(true);
        }
        else
        {
            item.setUnlimitedLifetime(false);
            item.setTicksLived(Math.max(1, age));
        }
    }

    /** Converts the item JSON ({id, count, components}) to the item-string format, upgrading pre-1.21.5 component formats. */
    @Override
    public ItemStack parseItemStack(JsonObject json)
    {
        String id = json.has("id") ? json.get("id").getAsString() : "minecraft:air";
        int count = 1;

        if (json.has("count"))
        {
            count = json.get("count").getAsInt();
        }
        else if (json.has("Count"))
        {
            count = json.get("Count").getAsInt();
        }

        StringBuilder sb = new StringBuilder(id);
        JsonObject components = json.has("components") && json.get("components").isJsonObject() ? json.getAsJsonObject("components") : null;

        if (components != null && components.size() > 0)
        {
            List<String> parts = new ArrayList<String>();
            boolean hideUnbreakable = false;

            for (Map.Entry<String, JsonElement> entry : components.entrySet())
            {
                String key = entry.getKey();
                JsonElement value = entry.getValue();
                String bare = key.startsWith("!") ? key.substring(1) : key;
                bare = bare.startsWith("minecraft:") ? bare.substring(10) : bare;

                if (key.startsWith("!"))
                {
                    parts.add(key);
                    continue;
                }

                if (value.isJsonObject())
                {
                    JsonObject obj = value.getAsJsonObject();

                    if (bare.equals("enchantments") || bare.equals("stored_enchantments"))
                    {
                        JsonObject flat = obj.has("levels") && obj.get("levels").isJsonObject() ? obj.getAsJsonObject("levels") : obj;
                        JsonObject clean = new JsonObject();

                        for (Map.Entry<String, JsonElement> e : flat.entrySet())
                        {
                            if (!e.getKey().equals("show_in_tooltip"))
                            {
                                clean.add(e.getKey(), e.getValue());
                            }
                        }

                        value = clean;
                    }
                    else if (bare.equals("unbreakable"))
                    {
                        hideUnbreakable = hideUnbreakable || (obj.has("show_in_tooltip") && !obj.get("show_in_tooltip").getAsBoolean());
                        value = new JsonObject();
                    }
                    else if (bare.equals("dyed_color") && obj.has("rgb"))
                    {
                        value = obj.get("rgb");
                    }
                }

                parts.add(key + "=" + value.toString());
            }

            if (hideUnbreakable)
            {
                parts.add("minecraft:tooltip_display={hidden_components:[\"minecraft:unbreakable\"]}");
            }

            if (!parts.isEmpty())
            {
                sb.append('[').append(String.join(",", parts)).append(']');
            }
        }

        try
        {
            ItemStack stack = Bukkit.getItemFactory().createItemStack(sb.toString());
            stack.setAmount(Math.max(1, count));
            return stack;
        }
        catch (Exception exception)
        {
            EggWars.instance.getLogger().warning("Could not parse item '" + sb + "': " + exception.getMessage());
            Material mat = Material.matchMaterial(id);
            return mat == null ? new ItemStack(Material.AIR) : new ItemStack(mat, Math.max(1, count));
        }
    }

    @Nullable
    @Override
    public BlockData parseBlockData(String string)
    {
        try
        {
            return Bukkit.createBlockData(string);
        }
        catch (Exception exception)
        {
            return null;
        }
    }

    @Override
    public void hideDyeFlag(LeatherArmorMeta meta)
    {
        meta.addItemFlags(ItemFlag.HIDE_DYE);
    }

    @Nullable
    @Override
    public String getStackName(ItemStack stack)
    {
        try
        {
            ItemMeta meta = stack.getItemMeta();

            if (meta != null && meta.hasItemName())
            {
                return meta.getItemName();
            }

            String name = stack.getType().getKey().getKey().replace('_', ' ');
            return Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
        catch (Exception exception)
        {
            return null;
        }
    }

    @Override
    @Deprecated
    public List<String> getEnchantmentsLore(ItemStack stack)
    {
        return new ArrayList<String>();
    }

    @Override
    public void setEnchantGlint(ItemStack stack, boolean glint, boolean force)
    {
        ItemMeta meta = stack.getItemMeta();
        Material mat = stack.getType();

        if (!force && (mat == Material.ENCHANTED_GOLDEN_APPLE || mat == Material.NETHER_STAR || mat == Material.END_CRYSTAL || mat == Material.EXPERIENCE_BOTTLE || mat == Material.ENCHANTED_BOOK || mat == Material.WRITTEN_BOOK || mat == Material.DEBUG_STICK))
        {
            return;
        }

        meta.setEnchantmentGlintOverride(glint);
        stack.setItemMeta(meta);
    }

    @Override
    public void killOutOfWorld(Player p)
    {
        p.damage((double)Float.MAX_VALUE, DamageSource.builder(DamageType.OUT_OF_WORLD).build());
    }

    @Override
    public void saveFullWorld(World world)
    {
        world.save();
    }

    /** Ender chest block lookup used NMS internals; the block the player is looking at is used instead. */
    @Nullable
    @Override
    public Block getEndChestBlock(Player p)
    {
        try
        {
            Block block = p.getTargetBlockExact(6);
            return block != null && block.getType() == Material.ENDER_CHEST ? block : null;
        }
        catch (Exception exception)
        {
            return null;
        }
    }

    @Override
    public void sendPacket(Player player, Object packetObj)
    {//no raw packet access without NMS; not used by the plugin outside reflections
    }

    @Override
    public void sendTitle(Player player, Integer fadeInTime, Integer stayTime, Integer fadeOutTime, String title, String subtitle)
    {
        player.sendTitle(title, subtitle, fadeInTime, stayTime, fadeOutTime);
    }

    @Override
    public void sendActionBar(Player player, String s, Integer integer, Integer integer1, Integer integer2)
    {
        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, net.md_5.bungee.api.chat.TextComponent.fromLegacy(s));
    }
}
