package com.stark.christmastree;

import org.bukkit.plugin.java.JavaPlugin;

public class ChristmasTreePlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getCommand("christmastree").setExecutor(new ChristmasTreeCommand(this));
        getCommand("oaktree").setExecutor(new OakTreeCommand(this));
        getLogger().info("ChristmasTree enabled - جاهز لبناء الأشجار!");
    }

    @Override
    public void onDisable() {
        getLogger().info("ChristmasTree disabled.");
    }
}
