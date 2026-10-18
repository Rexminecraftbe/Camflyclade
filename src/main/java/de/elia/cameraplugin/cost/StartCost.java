package de.elia.cameraplugin.cost;

import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.item.CameraItem;
import de.elia.cameraplugin.log.ConsoleLog;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * What a start of camera mode costs, the section {@code start-cost}:
 * experience levels, items, and money through Vault.
 *
 * <p>All or nothing: a player who lacks any part of it pays nothing and is
 * told what he is missing, and one who has all of it pays all of it at
 * once.</p>
 */
public final class StartCost {

    /** The slots a player carries his things in: the hotbar and the three rows. */
    private static final int STORAGE_SLOTS = 36;

    private final CamSettings settings;
    private final Messages messages;
    private final ConsoleLog log;
    private final CameraItem cameraItem;
    /** Whether the missing economy has been reported, which is said once and not at every start. */
    private boolean economyMissingReported;

    public StartCost(CamSettings settings, Messages messages, ConsoleLog log, CameraItem cameraItem) {
        this.settings = settings;
        this.messages = messages;
        this.log = log;
        this.cameraItem = cameraItem;
    }

    /** Lets the missing economy be reported again, after a reload. */
    public void reset() {
        economyMissingReported = false;
    }

    /**
     * Takes the cost of a start from the player, if he has all of it.
     *
     * @return whether he has paid - also when there is nothing to pay; if
     *         not, he has been told what he is missing
     */
    public boolean pay(Player player) {
        if (!settings.hasStartCost()) {
            return true;
        }
        int levels = settings.getStartCostLevels();
        double money = settings.getStartCostMoney();
        VaultEconomy economy = money > 0.0 ? economy() : null;
        if (economy == null) {
            money = 0.0;
        }
        List<String> cost = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        if (levels > 0) {
            cost.add(levels(levels));
            if (player.getLevel() < levels) {
                missing.add(levels(levels - player.getLevel()));
            }
        }
        for (Map.Entry<Material, Integer> entry : settings.getStartCostItems().entrySet()) {
            int needed = entry.getValue();
            cost.add(items(entry.getKey(), needed));
            int carried = count(player.getInventory(), entry.getKey());
            if (carried < needed) {
                missing.add(items(entry.getKey(), needed - carried));
            }
        }
        if (money > 0.0) {
            cost.add(economy.format(money));
            if (!economy.has(player, money)) {
                missing.add(economy.format(money));
            }
        }
        if (cost.isEmpty()) {
            // Only money was asked for, and there is no economy to take it.
            return true;
        }
        if (!missing.isEmpty()) {
            messages.sendMessage(player, "cost-missing",
                    "{cost}", String.join(", ", cost), "{missing}", String.join(", ", missing));
            return false;
        }
        // The money first: it is the one part that can still fail, and then
        // nothing else has been taken yet.
        if (money > 0.0 && !economy.withdraw(player, money)) {
            messages.sendMessage(player, "cost-missing",
                    "{cost}", String.join(", ", cost), "{missing}", economy.format(money));
            return false;
        }
        if (levels > 0) {
            player.setLevel(player.getLevel() - levels);
        }
        for (Map.Entry<Material, Integer> entry : settings.getStartCostItems().entrySet()) {
            take(player.getInventory(), entry.getKey(), entry.getValue());
        }
        player.updateInventory();
        messages.sendMessage(player, "cost-paid", "{cost}", String.join(", ", cost));
        return true;
    }

    /**
     * The economy, or {@code null} without one. Without it the money part is
     * left out, and that is said once in the console: turning everybody away
     * for a cost nobody can pay would lock camera mode up for good.
     */
    private VaultEconomy economy() {
        VaultEconomy economy = VaultEconomy.find();
        if (economy == null && !economyMissingReported) {
            economyMissingReported = true;
            log.log(Level.WARNING, "start-cost.money is set, but there is no economy: Vault and an economy"
                    + " plugin are needed for it. Starts cost no money until there is one.");
        }
        return economy;
    }

    private String levels(int amount) {
        return amount == 1
                ? messages.getMessage("cost-level")
                : messages.getMessage("cost-levels").replace("{amount}", String.valueOf(amount));
    }

    private String items(Material material, int amount) {
        return messages.getMessage("cost-item")
                .replace("{amount}", String.valueOf(amount))
                .replace("{item}", messages.getName("item-names", material.getKey()));
    }

    /**
     * Whether an item in his pockets counts towards the cost. The camera item
     * does not, even when it is made of the same thing: it would be gone after
     * the very start it was meant for.
     */
    private boolean counts(ItemStack item, Material material) {
        return item != null && item.getType() == material && !cameraItem.isCameraItem(item);
    }

    /** How many of an item he carries: in the hotbar, the three rows and the off hand, not worn. */
    private int count(PlayerInventory inventory, Material material) {
        int count = 0;
        for (int slot = 0; slot < STORAGE_SLOTS; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (counts(item, material)) {
                count += item.getAmount();
            }
        }
        ItemStack offHand = inventory.getItemInOffHand();
        if (counts(offHand, material)) {
            count += offHand.getAmount();
        }
        return count;
    }

    /** Takes that many of an item out of his pockets, the off hand last. */
    private void take(PlayerInventory inventory, Material material, int amount) {
        int left = amount;
        for (int slot = 0; slot < STORAGE_SLOTS && left > 0; slot++) {
            left = takeFrom(inventory, slot, inventory.getItem(slot), material, left);
        }
        if (left > 0) {
            ItemStack offHand = inventory.getItemInOffHand();
            if (counts(offHand, material)) {
                int taken = Math.min(left, offHand.getAmount());
                offHand.setAmount(offHand.getAmount() - taken);
                inventory.setItemInOffHand(offHand.getAmount() > 0 ? offHand : null);
            }
        }
    }

    /** @return how many are still to be taken after this slot */
    private int takeFrom(PlayerInventory inventory, int slot, ItemStack item, Material material, int left) {
        if (!counts(item, material)) {
            return left;
        }
        int taken = Math.min(left, item.getAmount());
        if (taken == item.getAmount()) {
            inventory.clear(slot);
        } else {
            item.setAmount(item.getAmount() - taken);
            inventory.setItem(slot, item);
        }
        return left - taken;
    }
}
