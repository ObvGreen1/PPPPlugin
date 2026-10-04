package me.obvgreen.ppp.command;

import me.obvgreen.ppp.PPPPlugin;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class ReloadCommand implements CommandExecutor {

    private final PPPPlugin plugin;

    public ReloadCommand(PPPPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("ppp.admin")) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.getMessages().getString("prefix") + plugin.getMessages().getString("no-permission")));
            return true;
        }

        try {
            plugin.onDisable();
            plugin.onEnable();
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.getMessages().getString("prefix") + plugin.getMessages().getString("reload-success")));
        } catch (Exception e) {
            e.printStackTrace();
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.getMessages().getString("prefix") + plugin.getMessages().getString("reload-failed")));
        }
        return true;
    }
}