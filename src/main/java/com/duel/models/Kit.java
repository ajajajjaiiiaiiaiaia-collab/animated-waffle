package com.duel.models;

import org.bukkit.inventory.ItemStack;

/**
 * Represents a duel kit tied to an Arena.
 * Slots: 0-8 hotbar, 9-35 inventory, 36 boots, 37 leggings, 38 chestplate, 39 helmet, 40 offhand
 */
public class Kit {

    private final String name;
    private final ItemStack[] contents; // size 41 (inventory + armor + offhand)

    public Kit(String name) {
        this.name = name;
        this.contents = new ItemStack[41];
    }

    public Kit(String name, ItemStack[] contents) {
        this.name = name;
        this.contents = contents.clone();
    }

    public String getName()            { return name; }
    public ItemStack[] getContents()   { return contents.clone(); }

    public void setSlot(int slot, ItemStack item) {
        if (slot >= 0 && slot < contents.length) {
            contents[slot] = item;
        }
    }

    public ItemStack getSlot(int slot) {
        if (slot >= 0 && slot < contents.length) return contents[slot];
        return null;
    }
}
