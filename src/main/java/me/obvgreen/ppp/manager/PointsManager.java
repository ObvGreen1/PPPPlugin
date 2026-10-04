package me.obvgreen.ppp.manager;

import me.clip.placeholderapi.PlaceholderAPI;
import me.obvgreen.ppp.PPPPlugin;
import me.obvgreen.ppp.math.TrueSkillMath;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;

public class PointsManager {

    public static class PlayerSkill {
        private double mu;
        private double sigma;
        private int experience;
        private long lastActive;
        // Elo mirrored from the rbw-hook plugin, cached so offline lookups and
        // rank checks still work when the player is not on the network.
        private int elo;

        public PlayerSkill(double mu, double sigma, int experience, long lastActive) {
            this(mu, sigma, experience, lastActive, 0);
        }

        public PlayerSkill(double mu, double sigma, int experience, long lastActive, int elo) {
            this.mu = mu;
            this.sigma = sigma;
            this.experience = experience;
            this.lastActive = lastActive;
            this.elo = elo;
        }

        public double getMu() { return mu; }
        public void setMu(double mu) { this.mu = mu; }

        public double getSigma() { return sigma; }
        public void setSigma(double sigma) { this.sigma = sigma; }

        public int getExperience() { return experience; }
        public void setExperience(int experience) { this.experience = experience; }

        public long getLastActive() { return lastActive; }
        public void setLastActive(long lastActive) { this.lastActive = lastActive; }

        public int getElo() { return elo; }
        public void setElo(int elo) { this.elo = elo; }

        public double getConservativeRating() {
            return mu - (3.0 * sigma);
        }
    }

    public static class MatchPerformance {
        private int kills = 0;
        private int finalKills = 0;
        private int deaths = 0;
        private int bedBreaks = 0;

        public void addKill() { kills++; }
        public void addFinalKill() { finalKills++; }
        public void addDeath() { deaths++; }
        public void addBedBreak() { bedBreaks++; }

        public int getKills() { return kills; }
        public int getFinalKills() { return finalKills; }
        public int getDeaths() { return deaths; }
        public int getBedBreaks() { return bedBreaks; }
    }

    private final PPPPlugin plugin;
    private final File databaseFile;
    private Connection connection;
    private final Map<UUID, PlayerSkill> playerSkills;
    private final Map<UUID, Deque<Long>> recentMatches;
    private final Queue<Object[]> pendingHistory;
    // Last role we notified a player about, so the hourly sweep can detect
    // expiry-driven demotions without spamming unchanged ranks.
    private final Map<UUID, String> roleCache;

    private static final String ROLE_PREMIUM = ChatColor.translateAlternateColorCodes('&', "&bPremium");
    private static final String ROLE_PUGS = ChatColor.translateAlternateColorCodes('&', "&cPUGs");
    private static final String ROLE_PUPS = ChatColor.translateAlternateColorCodes('&', "&dPUPs");

    public PointsManager(PPPPlugin plugin) {
        this.plugin = plugin;
        this.databaseFile = new File(plugin.getDataFolder(), "points.db");
        this.playerSkills = new ConcurrentHashMap<>();
        this.recentMatches = new ConcurrentHashMap<>();
        this.pendingHistory = new ConcurrentLinkedQueue<>();
        this.roleCache = new ConcurrentHashMap<>();
        initializeDatabase();
        loadData();
        loadMatchHistory();
        startAutoSaveTask();
        startDecayTask();
        startRankRefreshTask();
    }

    private void initializeDatabase() {
        try {
            if (!databaseFile.exists()) {
                databaseFile.createNewFile();
            }

            connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());

            try (Statement statement = connection.createStatement()) {
                statement.execute(
                        "CREATE TABLE IF NOT EXISTS player_trueskill (" +
                                "uuid TEXT PRIMARY KEY, " +
                                "mu REAL NOT NULL, " +
                                "sigma REAL NOT NULL, " +
                                "experience INTEGER NOT NULL DEFAULT 0, " +
                                "last_active BIGINT NOT NULL DEFAULT 0, " +
                                "elo INTEGER NOT NULL DEFAULT 0)"
                );
                statement.execute("PRAGMA journal_mode=WAL;");
                statement.execute(
                        "CREATE TABLE IF NOT EXISTS match_history (" +
                                "uuid TEXT NOT NULL, " +
                                "played_at BIGINT NOT NULL)"
                );
                statement.execute("CREATE INDEX IF NOT EXISTS idx_match_history ON match_history (uuid, played_at)");
            }

            // Migration to add columns to existing DBs safely
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE player_trueskill ADD COLUMN last_active BIGINT DEFAULT 0");
                statement.execute("ALTER TABLE player_trueskill ADD COLUMN elo INTEGER DEFAULT 0");
            } catch (SQLException ignored) {
                // Column already exists, safe to ignore
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void loadData() {
        try (PreparedStatement statement = connection.prepareStatement("SELECT uuid, mu, sigma, experience, last_active, elo FROM player_trueskill")) {
            ResultSet resultSet = statement.executeQuery();
            while (resultSet.next()) {
                try {
                    UUID uuid = UUID.fromString(resultSet.getString("uuid"));
                    double mu = resultSet.getDouble("mu");
                    double sigma = resultSet.getDouble("sigma");
                    int experience = resultSet.getInt("experience");
                    long lastActive = resultSet.getLong("last_active");
                    int elo = resultSet.getInt("elo");
                    playerSkills.put(uuid, new PlayerSkill(mu, sigma, experience, lastActive, elo));
                } catch (IllegalArgumentException e) { }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void loadMatchHistory() {
        long cutoff = System.currentTimeMillis() - rankWindowMillis();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT uuid, played_at FROM match_history WHERE played_at >= ?")) {
            statement.setLong(1, cutoff);
            ResultSet resultSet = statement.executeQuery();
            while (resultSet.next()) {
                try {
                    UUID uuid = UUID.fromString(resultSet.getString("uuid"));
                    long playedAt = resultSet.getLong("played_at");
                    recentMatches.computeIfAbsent(uuid, k -> new ConcurrentLinkedDeque<>()).addLast(playedAt);
                } catch (IllegalArgumentException ignored) { }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void recordMatchPlayed(UUID uuid, long now) {
        Deque<Long> times = recentMatches.computeIfAbsent(uuid, k -> new ConcurrentLinkedDeque<>());
        long cutoff = now - rankWindowMillis();
        times.addLast(now);
        while (!times.isEmpty() && times.peekFirst() < cutoff) {
            times.pollFirst();
        }
        pendingHistory.add(new Object[]{uuid.toString(), now});
    }

    private void flushMatchHistory() {
        if (pendingHistory.isEmpty()) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO match_history (uuid, played_at) VALUES (?, ?)")) {
            Object[] row;
            while ((row = pendingHistory.poll()) != null) {
                statement.setString(1, (String) row[0]);
                statement.setLong(2, (Long) row[1]);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void pruneMatchHistory() {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM match_history WHERE played_at < ?")) {
            statement.setLong(1, System.currentTimeMillis() - rankWindowMillis());
            statement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public int getRecentMatchCount(UUID uuid) {
        Deque<Long> times = recentMatches.get(uuid);
        if (times == null) return 0;
        long cutoff = System.currentTimeMillis() - rankWindowMillis();
        while (!times.isEmpty() && times.peekFirst() < cutoff) {
            times.pollFirst();
        }
        return times.size();
    }

    private int rankWindowDays() {
        return Math.max(1, plugin.getConfig().getInt("ranks.window-days", 30));
    }

    private long rankWindowMillis() {
        return rankWindowDays() * 86_400_000L;
    }

    public void saveData() {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR REPLACE INTO player_trueskill (uuid, mu, sigma, experience, last_active, elo) VALUES (?, ?, ?, ?, ?, ?)")) {
            for (Map.Entry<UUID, PlayerSkill> entry : playerSkills.entrySet()) {
                statement.setString(1, entry.getKey().toString());
                statement.setDouble(2, entry.getValue().getMu());
                statement.setDouble(3, entry.getValue().getSigma());
                statement.setInt(4, entry.getValue().getExperience());
                statement.setLong(5, entry.getValue().getLastActive());
                statement.setInt(6, entry.getValue().getElo());
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        flushMatchHistory();
    }

    public void savePlayer(UUID uuid) {
        PlayerSkill skill = playerSkills.get(uuid);
        if (skill == null) return;

        String sql = "INSERT OR REPLACE INTO player_trueskill (uuid, mu, sigma, experience, last_active, elo) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setDouble(2, skill.getMu());
            statement.setDouble(3, skill.getSigma());
            statement.setInt(4, skill.getExperience());
            statement.setLong(5, skill.getLastActive());
            statement.setInt(6, skill.getElo());
            statement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public PlayerSkill getSkill(UUID uuid) {
        return playerSkills.computeIfAbsent(uuid, k -> new PlayerSkill(TrueSkillMath.DEFAULT_MU, TrueSkillMath.DEFAULT_SIGMA, 0, System.currentTimeMillis()));
    }

    public int getPoints(Player player) {
        return getPoints(player.getUniqueId());
    }

    public int getPoints(UUID uuid) {
        PlayerSkill skill = getSkill(uuid);
        double displayScale = plugin.getConfig().getDouble("trueskill.display-scale", 40.0);
        return TrueSkillMath.displayPoints(skill.getMu(), skill.getSigma(), displayScale);
    }

    public String getRole(UUID uuid) {
        return computeRoleFor(uuid);
    }

    public int getPlayerCount() {
        return playerSkills.size();
    }

    public static class LeaderboardEntry {
        private final UUID uuid;
        private final int points;
        private final String role;

        public LeaderboardEntry(UUID uuid, int points, String role) {
            this.uuid = uuid;
            this.points = points;
            this.role = role;
        }

        public UUID getUuid() { return uuid; }
        public int getPoints() { return points; }
        public String getRole() { return role; }
    }

    public List<LeaderboardEntry> getLeaderboard(int limit) {
        double displayScale = plugin.getConfig().getDouble("trueskill.display-scale", 40.0);
        List<LeaderboardEntry> result = new ArrayList<>();

        List<Map.Entry<UUID, Double>> allPlayers = new ArrayList<>();
        for (Map.Entry<UUID, PlayerSkill> entry : playerSkills.entrySet()) {
            allPlayers.add(new AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue().getConservativeRating()));
        }
        allPlayers.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        int totalPlayers = allPlayers.size();
        for (int i = 0; i < Math.min(limit, totalPlayers); i++) {
            UUID uuid = allPlayers.get(i).getKey();
            PlayerSkill skill = playerSkills.get(uuid);
            int points = TrueSkillMath.displayPoints(skill.getMu(), skill.getSigma(), displayScale);
            double percentile = (double) (i + 1) / (double) totalPlayers;
            String role = roleForRequirements(percentile, getRecentMatchCount(uuid), skill.getElo());
            result.add(new LeaderboardEntry(uuid, points, role));
        }
        return result;
    }

    public int getDatabasePlayerCount() {
        return playerSkills.size();
    }

    public void processMatch(Set<UUID> winners, Set<UUID> losers, Map<UUID, MatchPerformance> matchStats) {
        if (winners.isEmpty() && losers.isEmpty()) return;

        List<UUID> winningList = new ArrayList<>(winners);
        List<UUID> losingList = new ArrayList<>(losers);

        Set<UUID> matchPlayers = new HashSet<>();
        matchPlayers.addAll(winners);
        matchPlayers.addAll(losers);

        Map<UUID, String> oldRoles = new HashMap<>();
        for (UUID uuid : matchPlayers) {
            oldRoles.put(uuid, getRole(uuid));
        }

        long now = System.currentTimeMillis();

        // Count the game towards the rolling activity window before ranks are
        // recomputed, otherwise the match that unlocks a rank would only count
        // on the next one.
        for (UUID uuid : matchPlayers) {
            recordMatchPlayed(uuid, now);
        }

        // Evolve both teams' ratings through the true skill:
        // 1) apply the experience bonus and dynamic variance (TAU) to each player
        // 2) sum each team into a single "super player" belief
        double winnerTeamMu = 0, winnerTeamSigmaSq = 0;
        for (UUID uuid : winningList) {
            PlayerSkill skill = getSkill(uuid);
            skill.setLastActive(now); // Update activity timestamp
            skill.setMu(skill.getMu() + TrueSkillMath.experienceOffset(skill.getExperience()));
            skill.setSigma(TrueSkillMath.inflateVariance(skill.getSigma()));
            skill.setExperience(skill.getExperience() + 1);
            winnerTeamMu += skill.getMu();
            winnerTeamSigmaSq += Math.pow(skill.getSigma(), 2);
        }

        double loserTeamMu = 0, loserTeamSigmaSq = 0;
        for (UUID uuid : losingList) {
            PlayerSkill skill = getSkill(uuid);
            skill.setLastActive(now); // Update activity timestamp
            skill.setMu(skill.getMu() + TrueSkillMath.experienceOffset(skill.getExperience()));
            skill.setSigma(TrueSkillMath.inflateVariance(skill.getSigma()));
            skill.setExperience(skill.getExperience() + 1);
            loserTeamMu += skill.getMu();
            loserTeamSigmaSq += Math.pow(skill.getSigma(), 2);
        }

        // Bayesian factor shared by every player in the match.
        TrueSkillMath.MatchFactor factor = TrueSkillMath.computeMatchFactor(
                winnerTeamMu, winnerTeamSigmaSq, winningList.size(),
                loserTeamMu, loserTeamSigmaSq, losingList.size());

        for (UUID uuid : winningList) {
            updatePlayerRating(uuid, factor, matchStats, true);
        }
        for (UUID uuid : losingList) {
            updatePlayerRating(uuid, factor, matchStats, false);
        }

        // Ranks are computed live, so just notify anyone whose rank changed
        for (UUID uuid : matchPlayers) {
            String newRole = computeRoleFor(uuid);
            String oldRole = oldRoles.get(uuid);
            roleCache.put(uuid, newRole);
            if (!newRole.equals(oldRole)) {
                notifyRankChange(uuid, oldRole, newRole);
            }
        }

        // Persist immediately so the leaderboard / any external DB readers see the new ratings right away
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::saveData);

        // Elo is owned by rbw-hook, so pull the fresh value in before ranks are
        // reported back to the players.
        for (UUID uuid : matchPlayers) {
            Player online = plugin.getServer().getPlayer(uuid);
            if (online != null && online.isOnline()) {
                refreshElo(online);
            }
        }

        // Progress report lands a few seconds after the end-of-game messages so
        // it does not compete with them for attention.
        long delaySeconds = Math.max(0, plugin.getConfig().getInt("ranks.progress-delay-seconds", 5));
        if (delaySeconds > 0) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                for (UUID uuid : matchPlayers) {
                    Player online = plugin.getServer().getPlayer(uuid);
                    if (online != null && online.isOnline()) {
                        sendProgressSummary(online, uuid);
                    }
                }
            }, delaySeconds * 20L);
        }
    }

    /** Everything the progress output needs for one player's next rank up. */
    public static class RankProgress {
        private final String targetRole;
        private final double targetPercentile;
        private final int targetGames;
        private final int targetElo;
        private final double percentile;
        private final int recentMatches;
        private final int elo;
        private final double percentileFraction;
        private final double matchesFraction;
        private final double eloFraction;
        private final double overallPercent;
        private final boolean maxed;

        RankProgress(String targetRole, double targetPercentile, int targetGames, int targetElo,
                     double percentile, int recentMatches, int elo,
                     double percentileFraction, double matchesFraction, double eloFraction,
                     double overallPercent, boolean maxed) {
            this.targetRole = targetRole;
            this.targetPercentile = targetPercentile;
            this.targetGames = targetGames;
            this.targetElo = targetElo;
            this.percentile = percentile;
            this.recentMatches = recentMatches;
            this.elo = elo;
            this.percentileFraction = percentileFraction;
            this.matchesFraction = matchesFraction;
            this.eloFraction = eloFraction;
            this.overallPercent = overallPercent;
            this.maxed = maxed;
        }

        public String getTargetRole() { return targetRole; }
        public double getTargetPercentile() { return targetPercentile; }
        public int getTargetGames() { return targetGames; }
        public int getTargetElo() { return targetElo; }
        public double getPercentile() { return percentile; }
        public int getRecentMatches() { return recentMatches; }
        public int getElo() { return elo; }
        public double getPercentileFraction() { return percentileFraction; }
        public double getMatchesFraction() { return matchesFraction; }
        public double getEloFraction() { return eloFraction; }
        public double getOverallPercent() { return overallPercent; }
        public boolean isMaxed() { return maxed; }
    }

    private static final String[] TIER_ORDER = {"premium", "pugs", "pups"};

    private String computeRoleFor(UUID uuid) {
        double percentile = computePercentile(uuid);
        if (percentile < 0) return "";
        PlayerSkill skill = playerSkills.get(uuid);
        return roleForRequirements(percentile, getRecentMatchCount(uuid), skill.getElo());
    }

    /** -1 when the player has no rating row yet. */
    private double computePercentile(UUID uuid) {
        PlayerSkill skill = playerSkills.get(uuid);
        if (skill == null || playerSkills.isEmpty()) return -1;

        int higher = 0;
        for (PlayerSkill other : playerSkills.values()) {
            if (other.getConservativeRating() > skill.getConservativeRating()) {
                higher++;
            }
        }

        return (double) (higher + 1) / (double) playerSkills.size();
    }

    public int getRankWindowDays() {
        return rankWindowDays();
    }

    public int getElo(UUID uuid) {
        PlayerSkill skill = playerSkills.get(uuid);
        return skill == null ? 0 : skill.getElo();
    }

    /**
     * A rank needs all three requirements at once: the percentile cut-off, the
     * number of games inside the activity window, and the elo floor. Any single
     * one of them is trivially cheesable on an empty or stale leaderboard.
     */
    private String roleForRequirements(double percentile, int recentMatches, int elo) {
        for (String tier : TIER_ORDER) {
            if (meetsTier(tier, percentile, recentMatches, elo)) {
                return roleForTier(tier);
            }
        }
        return "";
    }

    private boolean meetsTier(String tier, double percentile, int recentMatches, int elo) {
        return percentile <= percentileFor(tier)
                && recentMatches >= requiredGames(tier)
                && elo >= requiredElo(tier);
    }

    private String roleForTier(String tier) {
        switch (tier) {
            case "premium": return ROLE_PREMIUM;
            case "pugs": return ROLE_PUGS;
            default: return ROLE_PUPS;
        }
    }

    private double percentileFor(String tier) {
        switch (tier) {
            case "premium": return 0.005;
            case "pugs": return 0.015;
            default: return 0.10;
        }
    }

    private int requiredElo(String tier) {
        return plugin.getConfig().getInt("ranks." + tier + ".min-elo", 0);
    }

    private int requiredGames(String tier) {
        return plugin.getConfig().getInt("ranks." + tier + ".min-games", 0);
    }

    /**
     * Games needed for the best tier this player's rating reaches, or 0 when
     * their rating sits outside every tier.
     */
    public int getRequiredGamesForRank(UUID uuid) {
        double percentile = computePercentile(uuid);
        if (percentile < 0) return 0;

        if (percentile <= 0.005) return requiredGames("premium");
        if (percentile <= 0.015) return requiredGames("pugs");
        if (percentile <= 0.10) return requiredGames("pups");
        return 0;
    }

    /** Next rank up together with how full each of its requirements is. */
    public RankProgress getRankProgress(UUID uuid) {
        double percentile = computePercentile(uuid);
        PlayerSkill skill = playerSkills.get(uuid);
        if (percentile < 0 || skill == null) return null;

        int matches = getRecentMatchCount(uuid);
        int elo = skill.getElo();

        String target = null;
        for (String tier : TIER_ORDER) {
            if (!meetsTier(tier, percentile, matches, elo)) {
                target = tier;
                break;
            }
        }

        if (target == null) {
            return new RankProgress("", 0, 0, 0, percentile, matches, elo, 1, 1, 1, 100.0, true);
        }

        double targetPercentile = percentileFor(target);
        int targetGames = requiredGames(target);
        int targetElo = requiredElo(target);

        double percentileFraction = percentile <= targetPercentile ? 1.0 : targetPercentile / percentile;
        double matchesFraction = targetGames <= 0 ? 1.0 : Math.min(1.0, (double) matches / targetGames);
        double eloFraction = targetElo <= 0 ? 1.0 : Math.min(1.0, (double) elo / targetElo);

        double overall = (percentileFraction + matchesFraction + eloFraction) / 3.0 * 100.0;
        return new RankProgress(roleForTier(target), targetPercentile, targetGames, targetElo,
                percentile, matches, elo, percentileFraction, matchesFraction, eloFraction,
                overall, false);
    }

    public void sendProgressSummary(Player player, UUID uuid) {
        RankProgress progress = getRankProgress(uuid);
        if (progress == null) return;

        String prefix = plugin.getMessages().getString("prefix", "");

        if (progress.isMaxed()) {
            String maxed = plugin.getMessages().getString("points-progress-maxed",
                    "&aYou already hold the highest rank.");
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', prefix + maxed));
            return;
        }

        String header = plugin.getMessages().getString("points-progress-header",
                        "&7Rank progress &8» &r{role} &8({percent}%)")
                .replace("{role}", progress.getTargetRole())
                .replace("{percent}", formatPercent(progress.getOverallPercent()));
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', prefix + header));

        String total = plugin.getMessages().getString("points-progress-total", "&7Overall: &e{percent}%")
                .replace("{percent}", formatPercent(progress.getOverallPercent()));
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', total));

        sendRequirementLine(player, "Leaderboard",
                "top " + formatPercent(progress.getPercentile() * 100.0)
                        + " &8(need top " + formatPercent(progress.getTargetPercentile() * 100.0) + ")",
                progress.getPercentileFraction());

        sendRequirementLine(player, "Games (" + rankWindowDays() + "d)",
                progress.getRecentMatches() + "/" + progress.getTargetGames(),
                progress.getMatchesFraction());

        sendRequirementLine(player, "Elo",
                progress.getElo() + "/" + progress.getTargetElo(),
                progress.getEloFraction());
    }

    private void sendRequirementLine(Player player, String label, String value, double fraction) {
        boolean met = fraction >= 1.0;
        String status = ChatColor.translateAlternateColorCodes('&', plugin.getMessages().getString(
                met ? "progress-status-met" : "progress-status-missing", met ? "&aDONE" : "&cMISSING"));

        String line = plugin.getMessages().getString("points-progress-requirement",
                        "  {bar} &f{label} &8- &7{value} {status}")
                .replace("{bar}", progressBar(fraction))
                .replace("{label}", label)
                .replace("{value}", value)
                .replace("{status}", status);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', line));
    }

    private String progressBar(double fraction) {
        int width = 10;
        int filled = (int) Math.round(Math.max(0.0, Math.min(1.0, fraction)) * width);
        return ChatColor.GREEN + "|" .repeat(filled) + ChatColor.DARK_GRAY + "|".repeat(width - filled);
    }

    private String formatPercent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    /**
     * Elo lives in the rbw-hook plugin and is only readable for online players,
     * so mirror it into our own row and keep the last known value for offline
     * lookups and rank checks.
     */
    public void refreshElo(Player player) {
        if (player == null || !player.isOnline()) return;
        if (!plugin.getServer().getPluginManager().isPluginEnabled("rbw-hook")) return;

        String raw = PlaceholderAPI.setPlaceholders(player, "%rbw_stats_elo%");
        if (raw == null) return;
        raw = raw.trim();
        if (raw.isEmpty() || "???".equals(raw)) return;

        try {
            int elo = Integer.parseInt(raw);
            PlayerSkill skill = getSkill(player.getUniqueId());
            if (skill.getElo() != elo) {
                skill.setElo(elo);
                savePlayer(player.getUniqueId());
            }
        } catch (NumberFormatException ignored) {
            // Hook plugin has no elo for this player yet
        }
    }

    private void notifyRankChange(UUID uuid, String oldRole, String newRole) {
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) return;

        boolean promoted = roleTier(newRole) > roleTier(oldRole);
        String path = promoted ? "rank-promoted" : "rank-demoted";
        String def = "&fYour rank is now {role}";
        String msg = plugin.getMessages().getString(path, def)
                .replace("{role}", newRole)
                .replace("{old_role}", oldRole);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.getMessages().getString("prefix", "") + msg));

        if (promoted && !newRole.isEmpty()) {
            playPromotionSequence(player, oldRole, newRole);
        }
    }

    /**
     * Two-step title on promotion: the rank being left fades out, then the new
     * rank name lands with the level-up sting. Spigot 1.8 only exposes the
     * two-argument title call, so the timing runs off the scheduler instead.
     */
    private void playPromotionSequence(Player player, String oldRole, String newRole) {
        String from = oldRole.isEmpty()
                ? ChatColor.translateAlternateColorCodes('&', "&7Unranked")
                : oldRole;

        player.sendTitle(from, ChatColor.translateAlternateColorCodes('&', "&7Rank up!"));
        player.playSound(player.getLocation(), Sound.ORB_PICKUP, 1.0f, 1.4f);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            player.sendTitle(newRole, ChatColor.translateAlternateColorCodes('&', "&aNew rank unlocked"));
            player.playSound(player.getLocation(), Sound.LEVEL_UP, 1.0f, 1.0f);
        }, 30L);
    }

    private static int roleTier(String role) {
        if (ROLE_PREMIUM.equals(role)) return 3;
        if (ROLE_PUGS.equals(role)) return 2;
        if (ROLE_PUPS.equals(role)) return 1;
        return 0;
    }

    private void updatePlayerRating(UUID uuid, TrueSkillMath.MatchFactor factor, Map<UUID, MatchPerformance> matchStats, boolean isWinner) {
        PlayerSkill skill = getSkill(uuid);
        int oldPoints = getPoints(uuid);

        MatchPerformance stats = matchStats.getOrDefault(uuid, new MatchPerformance());
        TrueSkillMath.RatingUpdate update = TrueSkillMath.computeRatingUpdate(
                skill.getMu(), skill.getSigma(), factor, isWinner,
                stats.getKills(), stats.getFinalKills(), stats.getBedBreaks(), stats.getDeaths());

        // Apply the signed mean change and shrink uncertainty. The safety-floor
        // on sigma (1.0) keeps a player's deviation from collapsing to zero.
        skill.setMu(skill.getMu() + update.getMeanDelta());
        skill.setSigma(Math.max(1.0, skill.getSigma() * Math.sqrt(update.getVarianceFactor())));

        int pointDelta = getPoints(uuid) - oldPoints;
        notifyRatingChange(uuid, pointDelta);
    }

    private void notifyRatingChange(UUID uuid, int pointDelta) {
        // Never announce a match that did not change the player's displayed rating
        if (pointDelta == 0) {
            return;
        }

        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) return;

        String msgPath = pointDelta >= 0 ? "points-earned-victory" : "points-lost-game";
        String def = pointDelta >= 0 ? "&a+{points} TrueSkill rating!" : "&c{points} TrueSkill rating!";

        String msg = plugin.getMessages().getString(msgPath, def).replace("{points}", String.valueOf(pointDelta));
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.getMessages().getString("prefix", "") + msg));
    }

    private void startRankRefreshTask() {
        // Hourly sweep so a player who stops playing drops out of the activity
        // window and loses the rank they no longer qualify for.
        long interval = 20L * 60 * 60;
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            pruneMatchHistory();
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                UUID uuid = player.getUniqueId();
                String cached = roleCache.get(uuid);
                if (cached == null) {
                    roleCache.put(uuid, computeRoleFor(uuid));
                    continue;
                }
                String current = computeRoleFor(uuid);
                if (!current.equals(cached)) {
                    roleCache.put(uuid, current);
                    notifyRankChange(uuid, cached, current);
                }
            }
        }, interval, interval);
    }

    private void startDecayTask() {
        // Runs every hour
        long interval = 20L * 60 * 60;
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!plugin.getConfig().getBoolean("decay.enabled", true)) return;

            int thresholdDays = plugin.getConfig().getInt("decay.days-inactive", 7);
            int pointsPerDay = plugin.getConfig().getInt("decay.points-per-day", 10);
            int minPoints = plugin.getConfig().getInt("decay.minimum-points", 0);

            long millisInDay = 86400000L;
            long now = System.currentTimeMillis();

            for (Map.Entry<UUID, PlayerSkill> entry : playerSkills.entrySet()) {
                PlayerSkill skill = entry.getValue();
                if (skill.getLastActive() == 0) {
                    skill.setLastActive(now);
                    continue;
                }

                long daysInactive = (now - skill.getLastActive()) / millisInDay;
                if (daysInactive >= thresholdDays) {
                    int currentPoints = getPoints(entry.getKey());
                    if (currentPoints > minPoints) {
                        int newPoints = Math.max(minPoints, currentPoints - pointsPerDay);
                        // Re-anchor mu so the displayed points shrink by one day's decay
                        skill.setMu(TrueSkillMath.muForPoints(newPoints,
                                plugin.getConfig().getDouble("trueskill.display-scale", 40.0),
                                skill.getSigma()));
                    }

                    // Increment their last_active forward by 1 day so they don't decay again until tomorrow
                    skill.setLastActive(skill.getLastActive() + millisInDay);
                }
            }

            saveData();
        }, interval, interval);
    }

    private void startAutoSaveTask() {
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::saveData, 30 * 60 * 20L, 30 * 60 * 20L);
    }

    public void reload() {
        playerSkills.clear();
        recentMatches.clear();
        roleCache.clear();
        loadData();
        loadMatchHistory();
    }

    public void close() {
        saveData();
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}