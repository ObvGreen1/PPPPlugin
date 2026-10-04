package me.obvgreen.ppp;

import me.obvgreen.ppp.command.PointsCommand;
import me.obvgreen.ppp.command.ReloadCommand;
import me.obvgreen.ppp.listener.BedwarsListener;
import me.obvgreen.ppp.listener.GameEndListener;
import me.obvgreen.ppp.listener.PlayerJoinListener;
import me.obvgreen.ppp.manager.MatchTracker;
import me.obvgreen.ppp.manager.PointsManager;
import me.obvgreen.ppp.placeholder.PPPPlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class PPPPlugin extends JavaPlugin {

    private PointsManager pointsManager;
    private MatchTracker matchTracker;
    private FileConfiguration messagesConfig;
    private File messagesFile;

    @Override
    public void onEnable() {
        // Save default config
        saveDefaultConfig();

        // Load messages config
        loadMessagesConfig();

        // Initialize match tracker shared by listeners
        matchTracker = new MatchTracker();

        // Initialize points manager with TrueSkill 2 logic
        pointsManager = new PointsManager(this);

        // Register events
        getServer().getPluginManager().registerEvents(new BedwarsListener(this, matchTracker), this);
        getServer().getPluginManager().registerEvents(new GameEndListener(this, matchTracker), this);
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);

        // Register commands
        PointsCommand pointsCommand = new PointsCommand(this);
        registerCommand("ppppoints", pointsCommand, pointsCommand);
        registerCommand("pppreload", new ReloadCommand(this), null);

        // Register PlaceholderAPI expansion if PlaceholderAPI is enabled
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new PPPPlaceholderExpansion(this).register();
            getLogger().info("Successfully registered PlaceholderAPI expansion (%ppp_current_rank%).");
        }

        getLogger().info("PPPPlugin with TrueSkill 2 has been enabled!");
    }

    private void registerCommand(String commandName, CommandExecutor executor, TabCompleter tabCompleter) {
        PluginCommand command = getCommand(commandName);
        if (command == null) {
            getLogger().severe("Missing command registration in plugin.yml: /" + commandName);
            return;
        }

        command.setExecutor(executor);
        if (tabCompleter != null) {
            command.setTabCompleter(tabCompleter);
        }
    }

    @Override
    public void onDisable() {
        // Save points data and close connection
        if (pointsManager != null) {
            pointsManager.close();
        }

        getLogger().info("PPPPlugin has been disabled!");
    }

    public PointsManager getPointsManager() {
        return pointsManager;
    }

    public MatchTracker getMatchTracker() {
        return matchTracker;
    }

    public FileConfiguration getMessages() {
        return messagesConfig;
    }

    public void loadMessagesConfig() {
        messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            saveResource("messages.yml", false);
        }
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
    }

    public void reloadMessagesConfig() {
        if (messagesFile == null) {
            messagesFile = new File(getDataFolder(), "messages.yml");
        }
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        reloadMessagesConfig();
    }
}