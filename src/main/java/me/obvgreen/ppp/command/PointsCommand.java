package me.obvgreen.ppp.command;

import me.obvgreen.ppp.PPPPlugin;
import me.obvgreen.ppp.manager.PointsManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class PointsCommand implements CommandExecutor, TabCompleter {

    private final PPPPlugin plugin;

    public PointsCommand(PPPPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // View Self: /ppp
        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.getMessages().getString("prefix") + "&cThis command can only be used by players."));
                return true;
            }

            Player player = (Player) sender;
            int points = plugin.getPointsManager().getPoints(player);
            String role = plugin.getPointsManager().getRole(player.getUniqueId());
            String roleDisplay = role.isEmpty() ? "" : " (" + role + "&r)";

            String message = plugin.getMessages().getString("points-self", "&aYou have &e{points} &aTrueSkill rating.")
                    .replace("{points}", String.valueOf(points));
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.getMessages().getString("prefix") + message + roleDisplay));
            sendActivityLine(player, player.getUniqueId());
            return true;
        }

        // Top leaderboard: /ppp top
        if (args.length == 1 && args[0].equalsIgnoreCase("top")) {
            return handleTopCommand(sender);
        }

        // View Other: /ppp <player>
        if (args.length == 1) {
            if (!sender.hasPermission("ppp.points.others")) {
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.getMessages().getString("prefix") + plugin.getMessages().getString("no-permission")));
                return true;
            }

            OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
            if (target == null || (!target.hasPlayedBefore() && !target.isOnline())) {
                String message = plugin.getMessages().getString("player-not-found").replace("{player}", args[0]);
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.getMessages().getString("prefix") + message));
                return true;
            }

            int points = plugin.getPointsManager().getPoints(target.getUniqueId());
            String role = plugin.getPointsManager().getRole(target.getUniqueId());
            String roleDisplay = role.isEmpty() ? "" : " (" + role + "&r)";

            String message = plugin.getMessages().getString("points-other", "&a{player} has &e{points} &aTrueSkill rating.")
                    .replace("{player}", target.getName() != null ? target.getName() : args[0])
                    .replace("{points}", String.valueOf(points));
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.getMessages().getString("prefix") + message + roleDisplay));
            sendActivityLine(sender, target.getUniqueId());
            return true;
        }

        String message = plugin.getMessages().getString("invalid-usage").replace("{usage}", "/ppp [player] | /ppp top");
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.getMessages().getString("prefix") + message));
        return true;
    }

    private void sendActivityLine(CommandSender sender, UUID uuid) {
        PointsManager pm = plugin.getPointsManager();
        PointsManager.RankProgress progress = pm.getRankProgress(uuid);
        if (progress == null) return;

        if (progress.isMaxed()) {
            String maxed = plugin.getMessages().getString("points-progress-maxed", "&aYou already hold the highest rank.");
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', maxed));
            return;
        }

        String header = plugin.getMessages().getString("points-progress-header", "&7Rank progress &8» &r{role} &8({percent}%)")
                .replace("{role}", progress.getTargetRole())
                .replace("{percent}", formatPercent(progress.getOverallPercent()));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', header));

        String total = plugin.getMessages().getString("points-progress-total", "&7Overall: &e{percent}%")
                .replace("{percent}", formatPercent(progress.getOverallPercent()));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', total));

        sendRequirementLine(sender, "Leaderboard",
                "top " + formatPercent(progress.getPercentile() * 100.0)
                        + " &8(need top " + formatPercent(progress.getTargetPercentile() * 100.0) + ")",
                progress.getPercentileFraction());

        sendRequirementLine(sender, "Games (" + pm.getRankWindowDays() + "d)",
                progress.getRecentMatches() + "/" + progress.getTargetGames(),
                progress.getMatchesFraction());

        sendRequirementLine(sender, "Elo",
                progress.getElo() + "/" + progress.getTargetElo(),
                progress.getEloFraction());
    }

    private void sendRequirementLine(CommandSender sender, String label, String value, double fraction) {
        boolean met = fraction >= 1.0;
        String status = ChatColor.translateAlternateColorCodes('&', plugin.getMessages().getString(
                met ? "progress-status-met" : "progress-status-missing", met ? "&aDONE" : "&cMISSING"));

        int width = 10;
        int filled = (int) Math.round(Math.max(0.0, Math.min(1.0, fraction)) * width);
        String bar = ChatColor.GREEN + "|" .repeat(filled) + ChatColor.DARK_GRAY + "|".repeat(width - filled);

        String line = plugin.getMessages().getString("points-progress-requirement", "  {bar} &f{label} &8- &7{value} {status}")
                .replace("{bar}", bar)
                .replace("{label}", label)
                .replace("{value}", value)
                .replace("{status}", status);
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', line));
    }

    private String formatPercent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    @SuppressWarnings("deprecation")
    private boolean handleTopCommand(CommandSender sender) {
        PointsManager pm = plugin.getPointsManager();
        List<PointsManager.LeaderboardEntry> leaderboard = pm.getLeaderboard(15);

        if (leaderboard.isEmpty()) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.getMessages().getString("prefix") + "&cNo players found."));
            return true;
        }

        int totalPlayers = pm.getDatabasePlayerCount();
        String header = plugin.getMessages().getString("points-top-header", "&6=== Top Players ===");
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', header));

        String format = plugin.getMessages().getString("points-top-format", "&e{rank}. &a{player}: &f{points}");
        String formatWithRole = plugin.getMessages().getString("points-top-format-role", "&e{rank}. &a{player}: &f{points} {role} &7- top {percentile}");

        for (int i = 0; i < leaderboard.size(); i++) {
            PointsManager.LeaderboardEntry entry = leaderboard.get(i);
            UUID uuid = entry.getUuid();
            int points = entry.getPoints();
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            if (name == null) name = uuid.toString().substring(0, 8);

            String role = entry.getRole();
            double percentile = (double) (i + 1) / (double) totalPlayers * 100.0;
            String percentileStr = String.format(Locale.ROOT, "%.1f", percentile);

            String line;
            if (!role.isEmpty()) {
                line = formatWithRole
                        .replace("{rank}", String.valueOf(i + 1))
                        .replace("{player}", name)
                        .replace("{points}", String.valueOf(points))
                        .replace("{role}", role + "&r")
                        .replace("{percentile}", percentileStr);
            } else {
                line = format
                        .replace("{rank}", String.valueOf(i + 1))
                        .replace("{player}", name)
                        .replace("{points}", String.valueOf(points));
            }
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', line));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (command.getName().equalsIgnoreCase("ppppoints") || command.getName().equalsIgnoreCase("pts") || command.getName().equalsIgnoreCase("ppp")) {
            if (args.length == 1) {
                if (sender.hasPermission("ppp.points.others")) {
                    for (Player player : Bukkit.getOnlinePlayers()) completions.add(player.getName());
                }
                completions.add("top");
            }
        }
        return completions;
    }
}