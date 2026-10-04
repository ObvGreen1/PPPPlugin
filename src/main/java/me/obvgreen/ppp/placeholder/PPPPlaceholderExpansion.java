package me.obvgreen.ppp.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.obvgreen.ppp.PPPPlugin;
import me.obvgreen.ppp.manager.PointsManager;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public class PPPPlaceholderExpansion extends PlaceholderExpansion {

    private final PPPPlugin plugin;

    public PPPPlaceholderExpansion(PPPPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "ppp";
    }

    @Override
    public @NotNull String getAuthor() {
        return "obvgreen";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }

        if (params.equalsIgnoreCase("current_rank")) {
            return plugin.getPointsManager().getRole(player.getUniqueId());
        }

        if (params.equalsIgnoreCase("points") || params.equalsIgnoreCase("rating")) {
            return String.valueOf(plugin.getPointsManager().getPoints(player.getUniqueId()));
        }

        if (params.equalsIgnoreCase("recent_games")) {
            return String.valueOf(plugin.getPointsManager().getRecentMatchCount(player.getUniqueId()));
        }

        if (params.equalsIgnoreCase("required_games")) {
            return String.valueOf(plugin.getPointsManager().getRequiredGamesForRank(player.getUniqueId()));
        }

        if (params.equalsIgnoreCase("elo")) {
            return String.valueOf(plugin.getPointsManager().getElo(player.getUniqueId()));
        }

        if (params.equalsIgnoreCase("progress")) {
            PointsManager.RankProgress progress = plugin.getPointsManager().getRankProgress(player.getUniqueId());
            if (progress == null) return "";
            return String.format(Locale.ROOT, "%.1f", progress.getOverallPercent());
        }

        if (params.equalsIgnoreCase("next_rank")) {
            PointsManager.RankProgress progress = plugin.getPointsManager().getRankProgress(player.getUniqueId());
            if (progress == null || progress.isMaxed()) return "";
            return progress.getTargetRole();
        }

        return null;
    }
}