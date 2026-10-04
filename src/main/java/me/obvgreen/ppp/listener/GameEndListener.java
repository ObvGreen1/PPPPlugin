package me.obvgreen.ppp.listener;

import com.andrei1058.bedwars.api.arena.IArena;
import com.andrei1058.bedwars.api.events.gameplay.GameEndEvent;
import me.obvgreen.ppp.PPPPlugin;
import me.obvgreen.ppp.manager.MatchTracker;
import me.obvgreen.ppp.manager.PointsManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class GameEndListener implements Listener {

    private final PPPPlugin plugin;
    private final String arenaGroup;
    private final MatchTracker matchTracker;

    public GameEndListener(PPPPlugin plugin, MatchTracker matchTracker) {
        this.plugin = plugin;
        this.arenaGroup = plugin.getConfig().getString("arena-group", "RBW");
        this.matchTracker = matchTracker;
    }

    private boolean isRBWArena(IArena arena) {
        return arena != null && arenaGroup.equals(arena.getGroup());
    }

    @EventHandler
    public void onGameEnd(GameEndEvent event) {
        IArena arena = event.getArena();

        if (!isRBWArena(arena)) {
            return;
        }

        Set<UUID> winners = new HashSet<>(event.getWinners());
        Set<UUID> losers = new HashSet<>();

        for (UUID loserUUID : event.getLosers()) {
            if (!winners.contains(loserUUID)) {
                losers.add(loserUUID);
            }
        }

        Map<UUID, PointsManager.MatchPerformance> stats = matchTracker.removeStats(arena.getArenaName());

        // Execute TrueSkill 2 Bayesian rating update with match statistics
        plugin.getPointsManager().processMatch(winners, losers, stats);
    }
}