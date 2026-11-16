package com.duel.utils;

import org.bukkit.Bukkit;

public class MVWorldUtils {

    /**
     * Clone a world using Multiverse-Core console command.
     * Returns the new world name, or null if failed.
     */
    public static String cloneWorld(String template, String newName) {
        boolean ok = Bukkit.dispatchCommand(
                Bukkit.getConsoleSender(),
                "mv clone " + template + " " + newName
        );
        return ok ? newName : null;
    }

    /**
     * Delete a world using Multiverse-Core.
     * Runs mv remove → mv delete → mv confirm.
     */
    public static void deleteWorld(String worldName) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv remove " + worldName);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv delete " + worldName);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv confirm");
    }
}
