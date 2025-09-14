package ru.florestdev.florestGigaChat;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class FlorestGigaChat extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("Started the FlorestAI plugin!");
        saveDefaultConfig();
        PluginCommand command = getCommand("florestai");

        if (getConfig().getString("api_key") == null) {
            getLogger().severe("ALARM. API КЛЮЧ РАВЕН ПУСТОЙ СТРОКЕ.");
            getServer().getPluginManager().disablePlugin(this);
        }

        if (command == null) {
            getLogger().severe("/florestai command doesn't exists!");
        } else {
            command.setExecutor(new FlorestAIProcesser(this));
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabling plugin. Goodbye!");
    }
}
