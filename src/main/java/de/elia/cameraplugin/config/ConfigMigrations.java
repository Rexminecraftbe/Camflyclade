package de.elia.cameraplugin.config;

import de.elia.cameraplugin.menu.AdminButton;
import de.elia.cameraplugin.menu.MenuButton;

import java.util.List;

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
            }));
}
