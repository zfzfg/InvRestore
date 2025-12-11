package com.zfzfg.inventorybackup.listeners;

import com.zfzfg.inventorybackup.InventoryBackup;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public class PlayerDeathListener implements Listener {
    
    private final InventoryBackup plugin;
    
    public PlayerDeathListener(InventoryBackup plugin) {
        this.plugin = plugin;
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!plugin.getConfig().getBoolean("save-on-death", true)) {
            return;
        }
        
        Player player = event.getEntity();
        
        // Save inventory asynchronously
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean saved = plugin.getInventoryManager().saveInventory(player, "death");
            
            if (saved) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    String message = plugin.getMessage("inventory-saved")
                            .replace("{player}", player.getName());
                    player.sendMessage(message);
                });
            }
        });
    }
}
