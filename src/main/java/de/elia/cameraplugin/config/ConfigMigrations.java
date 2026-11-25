package de.elia.cameraplugin.config;

import de.elia.cameraplugin.area.AreaRuleLevel;
import de.elia.cameraplugin.body.MovementSensitivity;
import de.elia.cameraplugin.display.ActionBarSettings;
import de.elia.cameraplugin.display.NameMode;
import de.elia.cameraplugin.menu.AdminButton;
import de.elia.cameraplugin.menu.MenuButton;

import java.util.List;
import java.util.Locale;

/**
 * What changed in the layout of {@code config.yml} since a file of an older
 * version was written, for {@link ConfigUpdater}.
 *
 * <p>Needed only where adding the missing keys would change what a file
 * means. A key that is simply new needs nothing here - the file is read with
 * its default until then, and gets the same default written into it. A key
 * whose absence means something does: a renamed one is read under its old
 * name as long as the new one is missing, and a new switch whose default
 * differs from what an older file did is read as that file did without it,
 * see {@link CamSettings}.</p>
 *
 * <p>Those are run on every file, {@link ConfigUpdater.Migration#ALWAYS}: the
 * file shows whether it needs them, just as {@link CamSettings} reads it
 * whatever its {@code config-version} says. That version is for a change the
 * file does not show - a value that comes to mean something else. Such a
 * change is run on files below its version; a file without
 * {@code config-version} counts as version 0, the files of every version
 * before there was one.</p>
 */
final class ConfigMigrations {

    private ConfigMigrations() {
    }

    /** The changes, in the order of their versions. */
    static final List<ConfigUpdater.Migration> CONFIG = List.of(
            new ConfigUpdater.Migration(ConfigUpdater.Migration.ALWAYS, file -> {
                // /cam tour became /cam follow, and its section, its sounds
                // and its buttons in the menu were renamed with it.
                file.rename(CamSettings.OLD_FOLLOW_SECTION, CamSettings.FOLLOW_SECTION);
                file.rename("sounds.tour-invite", "follow-invite");
                file.rename("sounds.tour-reach", "follow-reach");
                for (MenuButton button : MenuButton.values()) {
                    if (button.oldKey() != null) {
                        file.rename("menu.buttons." + button.oldKey(), button.key());
                    }
                }
                for (AdminButton button : AdminButton.values()) {
                    if (button.oldKey() != null) {
                        file.rename("menu.admin-buttons." + button.oldKey(), button.key());
                    }
                }
                // cam-trail.show-mode came with dynamic as its default. A file
                // from before, with show-seconds alone, showed every line that
                // long, and is read so as long as show-mode is missing.
                if (!file.has("cam-trail.show-mode") && file.has("cam-trail.show-seconds")) {
                    file.add("cam-trail.show-mode", "fixed");
                }
            }),
            new ConfigUpdater.Migration(ConfigUpdater.Migration.ALWAYS, ConfigMigrations::tidyUp));

    /**
     * Settings that are no settings any more, and the ones that have another
     * name, another place or other values since the file was tidied up.
     */
    private static void tidyUp(ConfigUpdater file) {
        // Fixed in the code now: technical values, and the ways of before
        // that the menu had a switch for.
        for (String gone : REMOVED) {
            file.remove(gone);
        }
        // Keys with underscores got hyphens like all the others.
        file.move("camera-mode.player_visibility_mode", "camera-mode.player-visibility");
        file.move("camera-mode.allow_invisibility_potion", "camera-mode.allow-invisibility-potion");
        file.move("camera-mode.allow_lava_flight", "camera-mode.allow-lava-flight");
        file.move("camera-mode.allow_water_flight", "camera-mode.allow-water-flight");
        file.move("camera-mode.allow_powder_snow_flight", "camera-mode.allow-powder-snow-flight");
        // One wait between the messages at every border, where there were three.
        file.move("camera-mode.distance-warning-cooldown", "camera-mode.warning-cooldown");
        file.remove("cam-area.warning-cooldown");
        file.remove("portals.warning-cooldown");
        // Numbers became words.
        file.renameValue("camera-mode.name-mode", value -> NameMode.oldValues().get(value));
        file.renameValue("body.movement-sensitivity", value -> MovementSensitivity.oldValues().get(value));
        file.renameValue("cam-area.level", value -> AreaRuleLevel.oldValues().get(value));
        file.renameValue("action-bar.display",
                value -> ActionBarSettings.OLD_DISPLAY_VALUES.get(value.toLowerCase(Locale.ROOT)));
        // The cooldown counts for every flight-limit, so it left time-limit
        // for a section of its own. The colour and the notches of the
        // bossbar were the same for both bars until then.
        file.move("time-limit.cooldowns-enabled", "cooldown.enabled");
        file.move("time-limit.cooldown-seconds", "cooldown.seconds");
        file.move("time-limit.show-bossbar-cooldown", "cooldown.show-bossbar");
        file.copy("time-limit.bossbar-color", "cooldown.bossbar-color");
        file.copy("time-limit.bossbar-notches", "cooldown.bossbar-notches");
        // One storage at the top for everything. A file from before has none
        // there, but one in each section, mostly file: those go, and one that
        // says otherwise stays, read there, see SettingValues#storage.
        if (!file.has(SettingValues.STORAGE)) {
            for (String own : OWN_STORAGE) {
                if ("file".equalsIgnoreCase(file.value(own))) {
                    file.remove(own);
                }
            }
            if ("file".equalsIgnoreCase(file.value("time-limit.storage"))) {
                file.remove("time-limit.storage");
            } else {
                file.moveOutside("time-limit.storage", "cooldown.storage");
            }
        }
    }

    /**
     * The changes of {@code lang/en.yml}: the texts of the ways of the spot
     * menu that went with {@code viewpoints.create-page} and
     * {@code viewpoints.options.enabled}, and their switch.
     */
    static final List<ConfigUpdater.Migration> LANGUAGE = List.of(
            new ConfigUpdater.Migration(ConfigUpdater.Migration.ALWAYS, file -> {
                for (String text : List.of("menu-admin-spot-actions", "menu-admin-spot-actions-bedrock",
                        "menu-admin-spot-actions-shared", "menu-admin-spot-actions-shared-bedrock",
                        "menu-new-spot-lore-public", "menu-new-spot-lore-public-bedrock", "menu-pick-spot-delete",
                        "menu-pick-spot-offline", "menu-pick-spot-private", "menu-pick-spot-public",
                        "menu-pick-spot-share", "menu-pick-spot-take-down", "menu-public-own-actions",
                        "menu-public-own-actions-bedrock", "menu-spot-actions", "menu-spot-actions-bedrock",
                        "menu-spot-actions-share", "menu-spot-actions-share-bedrock", "menu-spot-name-public",
                        "menu-spot-name-public-form")) {
                    file.remove("messages." + text);
                }
                file.remove("message-settings.menu-spot-name-public");
            }));

    /** What is no setting any more. */
    private static final List<String> REMOVED = List.of(
            "action-bar.off-duration",
            "fireguard.tick-interval", "fireguard.radius-horizontal", "fireguard.radius-up", "fireguard.radius-down",
            "crash-protection",
            "portals.remember-blocked", "portals.remember-blocked-count", "portals.forget-changed",
            "portals.forget-radius",
            "cam-area.structures-cache-chunks",
            "viewpoints.create-page", "viewpoints.options.enabled");

    /**
     * Where a section had a {@code storage} of its own, before there was one
     * for everything at the top of the file. One that says otherwise is still
     * read there, see {@link SettingValues#storage}; the cooldowns had theirs
     * under {@code time-limit}.
     */
    static final List<String> OWN_STORAGE = List.of("player-settings.storage", "battery.storage",
            "resume-command.storage", "viewpoints.storage", "cam-trail.storage");
}
