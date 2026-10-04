package me.obvgreen.ppp.manager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MatchTracker {

    private final Map<String, Map<UUID, PointsManager.MatchPerformance>> arenaStatsMap = new ConcurrentHashMap<>();

    public PointsManager.MatchPerformance getOrCreatePerformance(String arenaName, UUID uuid) {
        return arenaStatsMap.computeIfAbsent(arenaName, k -> new HashMap<>())
                .computeIfAbsent(uuid, k -> new PointsManager.MatchPerformance());
    }

    public Map<UUID, PointsManager.MatchPerformance> removeStats(String arenaName) {
        Map<UUID, PointsManager.MatchPerformance> stats = arenaStatsMap.remove(arenaName);
        return stats != null ? stats : new HashMap<>();
    }
}