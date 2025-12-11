package com.zfzfg.inventorybackup.managers;

import com.zfzfg.inventorybackup.InventoryBackup;
import com.zfzfg.inventorybackup.utils.InventorySerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;

public class InventoryManager {
    
    private final InventoryBackup plugin;
    private final SimpleDateFormat dateFormat;
    
    public InventoryManager(InventoryBackup plugin) {
        this.plugin = plugin;
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
    }
    
    public boolean saveInventory(Player player, String type) {
        File playerFolder = new File(plugin.getDataFolder(), "inventories/" + player.getName());
        if (!playerFolder.exists()) {
            playerFolder.mkdirs();
        }
        
        String timestamp = dateFormat.format(new Date());
        String fileName = timestamp + "_" + type + ".yml";
        File file = new File(playerFolder, fileName);
        
        YamlConfiguration config = new YamlConfiguration();
        
        // Save inventory contents
        config.set("player", player.getName());
        config.set("uuid", player.getUniqueId().toString());
        config.set("timestamp", System.currentTimeMillis());
        config.set("type", type);
        
        // Serialize inventory
        config.set("inventory", InventorySerializer.serializeInventory(player.getInventory().getContents()));
        config.set("armor", InventorySerializer.serializeInventory(player.getInventory().getArmorContents()));
        config.set("offhand", InventorySerializer.serializeItemStack(player.getInventory().getItemInOffHand()));
        
        try {
            config.save(file);
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save inventory for " + player.getName() + ": " + e.getMessage());
            return false;
        }
    }
    
    public boolean restoreInventory(Player player, String fileName) {
        File file = getInventoryFile(player.getName(), fileName);
        if (file == null || !file.exists()) {
            return false;
        }
        
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        
        // Restore on main thread
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            ItemStack[] inventory = InventorySerializer.deserializeInventory(config.getString("inventory"));
            ItemStack[] armor = InventorySerializer.deserializeInventory(config.getString("armor"));
            ItemStack offhand = InventorySerializer.deserializeItemStack(config.getString("offhand"));
            
            player.getInventory().setContents(inventory);
            player.getInventory().setArmorContents(armor);
            player.getInventory().setItemInOffHand(offhand);
            
            player.updateInventory();
        });
        
        return true;
    }
    
    public boolean showInventory(Player viewer, String playerName, String fileName) {
        File file = getInventoryFile(playerName, fileName);
        if (file == null || !file.exists()) {
            return false;
        }
        
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        
        // Show inventory on main thread
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            ItemStack[] inventory = InventorySerializer.deserializeInventory(config.getString("inventory"));
            
            // Create GUI
            Inventory gui = Bukkit.createInventory(null, 54, "§6Inventory: " + playerName);
            
            // Add items to GUI
            for (int i = 0; i < inventory.length && i < 36; i++) {
                if (inventory[i] != null) {
                    gui.setItem(i, inventory[i]);
                }
            }
            
            // Add armor in separate slots
            ItemStack[] armor = InventorySerializer.deserializeInventory(config.getString("armor"));
            if (armor.length >= 4) {
                gui.setItem(45, armor[3]); // Helmet
                gui.setItem(46, armor[2]); // Chestplate
                gui.setItem(47, armor[1]); // Leggings
                gui.setItem(48, armor[0]); // Boots
            }
            
            // Add offhand
            ItemStack offhand = InventorySerializer.deserializeItemStack(config.getString("offhand"));
            if (offhand != null) {
                gui.setItem(49, offhand);
            }
            
            viewer.openInventory(gui);
        });
        
        return true;
    }
    
    public int giveMissingItems(Player player, String fileName) {
        File file = getInventoryFile(player.getName(), fileName);
        if (file == null || !file.exists()) {
            return -1;
        }
        
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ItemStack[] savedInventory = InventorySerializer.deserializeInventory(config.getString("inventory"));
        
        int givenCount = 0;
        
        // Check and give missing items on main thread
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Inventory currentInv = player.getInventory();
            
            for (ItemStack savedItem : savedInventory) {
                if (savedItem == null) continue;
                
                if (!hasItem(currentInv, savedItem)) {
                    HashMap<Integer, ItemStack> leftover = currentInv.addItem(savedItem);
                    
                    if (!leftover.isEmpty()) {
                        // Drop items that don't fit
                        for (ItemStack item : leftover.values()) {
                            player.getWorld().dropItemNaturally(player.getLocation(), item);
                        }
                    }
                }
            }
            
            player.updateInventory();
        });
        
        // Count missing items
        for (ItemStack savedItem : savedInventory) {
            if (savedItem == null) continue;
            if (!hasItem(player.getInventory(), savedItem)) {
                givenCount++;
            }
        }
        
        return givenCount;
    }
    
    private boolean hasItem(Inventory inventory, ItemStack item) {
        for (ItemStack invItem : inventory.getContents()) {
            if (invItem != null && invItem.isSimilar(item) && invItem.getAmount() >= item.getAmount()) {
                return true;
            }
        }
        return false;
    }
    
    private File getInventoryFile(String playerName, String fileName) {
        if (!fileName.endsWith(".yml")) {
            fileName += ".yml";
        }
        
        File playerFolder = new File(plugin.getDataFolder(), "inventories/" + playerName);
        return new File(playerFolder, fileName);
    }
    
    public int cleanOldFiles() {
        int deletedCount = 0;
        int maxAgeDays = plugin.getConfig().getInt("auto-delete-days", 30);
        
        if (maxAgeDays <= 0) {
            return 0;
        }
        
        long maxAgeMillis = maxAgeDays * 24L * 60 * 60 * 1000;
        long currentTime = System.currentTimeMillis();
        
        File inventoriesFolder = new File(plugin.getDataFolder(), "inventories");
        if (!inventoriesFolder.exists()) {
            return 0;
        }
        
        File[] playerFolders = inventoriesFolder.listFiles(File::isDirectory);
        if (playerFolders == null) {
            return 0;
        }
        
        for (File playerFolder : playerFolders) {
            File[] files = playerFolder.listFiles((dir, name) -> name.endsWith(".yml"));
            if (files == null) continue;
            
            for (File file : files) {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                long timestamp = config.getLong("timestamp", 0);
                
                if (timestamp > 0 && (currentTime - timestamp) > maxAgeMillis) {
                    if (file.delete()) {
                        deletedCount++;
                    }
                }
            }
            
            // Delete empty player folders
            if (playerFolder.list() != null && playerFolder.list().length == 0) {
                playerFolder.delete();
            }
        }
        
        return deletedCount;
    }
}
