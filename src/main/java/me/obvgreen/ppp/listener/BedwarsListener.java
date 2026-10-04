package me.obvgreen.ppp.listener;

import com.andrei1058.bedwars.api.arena.IArena;
import com.andrei1058.bedwars.api.events.player.PlayerBedBreakEvent;
import com.andrei1058.bedwars.api.events.player.PlayerKillEvent;
import me.obvgreen.ppp.PPPPlugin;
import me.obvgreen.ppp.manager.MatchTracker;
import me.obvgreen.ppp.manager.PointsManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class BedwarsListener implements Listener {

    private final PPPPlugin plugin;
    private final String arenaGroup;
    private final MatchTracker matchTracker;

    public BedwarsListener(PPPPlugin plugin, MatchTracker matchTracker) {
        this.plugin = plugin;
        this.arenaGroup = plugin.getConfig().getString("arena-group", "RBW");
        this.matchTracker = matchTracker;
    }

    private boolean isRBWArena(IArena arena) {
        return arena != null && arenaGroup.equals(arena.getGroup());
    }

    @EventHandler
    public void onPlayerKill(PlayerKillEvent event) {
        IArena arena = event.getArena();

        if (!isRBWArena(arena)) {
            return;
        }

        Player victim = event.getVictim();
        Player killer = event.getKiller();

        // Track victim death statistic
        if (victim != null) {
            matchTracker.getOrCreatePerformance(arena.getArenaName(), victim.getUniqueId()).addDeath();
        }

        // Track killer stats (Kill / Final Kill)
        if (killer != null && !killer.equals(victim)) {
            PointsManager.MatchPerformance killerStats = matchTracker.getOrCreatePerformance(arena.getArenaName(), killer.getUniqueId());
            if (event.getCause().isFinalKill()) {
                killerStats.addFinalKill();
            } else {
                killerStats.addKill();
            }
        }
    }

    @EventHandler
    public void onBedBreak(PlayerBedBreakEvent event) {
        IArena arena = event.getArena();

        if (!isRBWArena(arena)) {
            return;
        }

        Player player = event.getPlayer();
        if (player != null) {
            matchTracker.getOrCreatePerformance(arena.getArenaName(), player.getUniqueId()).addBedBreak();
        }
    }
}