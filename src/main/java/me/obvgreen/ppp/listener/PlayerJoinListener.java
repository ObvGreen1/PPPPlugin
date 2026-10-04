package me.obvgreen.ppp.listener;

import me.obvgreen.ppp.PPPPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class PlayerJoinListener implements Listener {

    private final PPPPlugin plugin;

    public PlayerJoinListener(PPPPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getPointsManager().getSkill(player.getUniqueId()).setLastActive(System.currentTimeMillis());
        plugin.getPointsManager().savePlayer(player.getUniqueId());

        // Elo comes from rbw-hook, which only knows players that are online.
        // Pull it shortly after the join so the hook plugin has had time to
        // register its profile for us.
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> plugin.getPointsManager().refreshElo(player), 40L);
    }
}