package de.elia.cameraplugin.cost;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;

/**
 * The economy of the server, reached through Vault.
 *
 * <p>By reflection, the way {@code CameraPlugin#unregisterCommands} reaches
 * the command map: Vault is not part of the server, and the plugin builds and
 * runs without it. Only {@code start-cost.money} needs it, and only then is
 * it looked for - each time, since the economy plugin registers itself with
 * Vault whenever it gets round to it.</p>
 */
final class VaultEconomy {

    /** The interface Vault offers the economy under. */
    private static final String ECONOMY = "net.milkbowl.vault.economy.Economy";

    private final Object provider;
    private final Method has;
    private final Method withdraw;
    private final Method format;

    private VaultEconomy(Object provider, Class<?> type) throws ReflectiveOperationException {
        this.provider = provider;
        this.has = type.getMethod("has", OfflinePlayer.class, double.class);
        this.withdraw = type.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
        this.format = type.getMethod("format", double.class);
    }

    /**
     * The economy as Vault hands it out right now, or {@code null} when there
     * is none: Vault missing, or no economy plugin registered with it.
     */
    static VaultEconomy find() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return null;
        }
        try {
            Class<?> type = Class.forName(ECONOMY);
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(type);
            if (registration == null || registration.getProvider() == null) {
                return null;
            }
            return new VaultEconomy(registration.getProvider(), type);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            return null;
        }
    }

    boolean has(OfflinePlayer player, double amount) {
        try {
            return (Boolean) has.invoke(provider, player, amount);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }

    /** Takes the money, and says whether the economy did. */
    boolean withdraw(OfflinePlayer player, double amount) {
        try {
            Object response = withdraw.invoke(provider, player, amount);
            return response != null
                    && (Boolean) response.getClass().getMethod("transactionSuccess").invoke(response);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }

    /** An amount the way the economy writes it, with its currency. */
    String format(double amount) {
        try {
            Object text = format.invoke(provider, amount);
            if (text != null) {
                return text.toString();
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // Written plainly below.
        }
        return String.valueOf(amount);
    }
}
