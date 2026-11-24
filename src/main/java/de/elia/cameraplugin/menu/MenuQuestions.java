package de.elia.cameraplugin.menu;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.spot.Spot;
import de.elia.cameraplugin.spot.SpotBans;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The questions of {@link CamMenu}: what cannot be clicked - the name of a
 * new spot, why a ban is given - is asked in the chat; the menu closes, and
 * the next line he writes is the answer, which nobody else reads. On Bedrock
 * in a window instead, where that goes. The line itself comes from
 * {@link CamMenu#onChat}.
 */
final class MenuQuestions {

    /** How long the menu waits for an answer in the chat, in milliseconds. */
    static final long ANSWER_WAIT = 60_000L;

    /** The word that calls off an answer in the chat. */
    static final String CANCEL = "cancel";

    private final CamMenu menu;
    private final CameraPlugin plugin;
    private final Messages messages;
    /** Who is asked for something in the chat, until when and what to do with it. Read from the chat thread. */
    private final Map<UUID, Question> questions = new ConcurrentHashMap<>();

    /**
     * A question in the chat: until when the answer counts, what it does - on
     * the main thread -, and what calling it off does besides saying so,
     * {@code null} for nothing.
     */
    record Question(long until, Consumer<String> answer, Runnable cancelled) {
    }

    MenuQuestions(CamMenu menu) {
        this.menu = menu;
        this.plugin = menu.plugin;
        this.messages = menu.messages;
    }

    /**
     * Asks for the name of a new spot in the chat; the next line he writes is
     * it. A public one is made public once it is saved.
     */
    void askName(Player player, boolean makePublic) {
        // Asked before the name: nobody types one only to hear that it does not go here.
        if (!plugin.getSpots().maySetHere(player)) {
            return;
        }
        if (makePublic && !plugin.getSpotModeration().checkNew(player, SpotBans.Kind.PUBLIC)) {
            return;
        }
        ask(player, makePublic ? "menu-spot-name-public" : "menu-spot-name", name -> {
            Spot spot = plugin.getSpots().set(player, name);
            if (spot != null && makePublic) {
                plugin.getSpots().makePublic(player, spot, true);
            }
        });
    }

    /**
     * Asks something in the chat: the next line he writes is the answer, or
     * {@code cancel}, which calls it off with {@code menu-answer-cancelled}.
     * On Bedrock, under {@code bedrock.menu: forms} and {@code mixed}, in a
     * window instead, see {@link #askInForm}, where that goes.
     *
     * @param key    the question, with {@code {cancel}} for the word that calls it off
     * @param answer what the answer does, on the main thread
     */
    void ask(Player player, String key, Consumer<String> answer, String... fills) {
        ask(player, key, answer, (Runnable) null, fills);
    }

    /**
     * {@link #ask(Player, String, Consumer, String...)}, and when he calls it
     * off, something besides saying so - the page he came from comes back.
     *
     * @param cancelled what calling it off does, {@code null} for nothing
     */
    void ask(Player player, String key, Consumer<String> answer, Runnable cancelled, String... fills) {
        menu.shown(player);
        if (plugin.getBedrock().menuChoicesAsForms(player)) {
            menu.bedrockPages.whenChestShut(player, () -> {
                if (!askInForm(player, key, answer, cancelled, fills)) {
                    askInChat(player, key, answer, cancelled, fills);
                }
            });
            return;
        }
        askInChat(player, key, answer, cancelled, fills);
    }

    /** {@link #ask} in the chat: the next line he writes is the answer, or {@code cancel}. */
    void askInChat(Player player, String key, Consumer<String> answer, Runnable cancelled,
                           String... fills) {
        questions.put(player.getUniqueId(), new Question(System.currentTimeMillis() + ANSWER_WAIT, answer,
                cancelled));
        String[] all = Arrays.copyOf(fills, fills.length + 2);
        all[fills.length] = "{cancel}";
        all[fills.length + 1] = CANCEL;
        messages.sendConfiguredMessage(player, key, all);
    }

    /**
     * The line he writes after a question of the menu: the answer, or
     * {@code cancel}. Nobody else reads it. Asked on the thread of the chat,
     * so the answer itself is acted on on the main thread - the name of a
     * spot is saved where he stands then.
     */
    void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        Question question = questions.remove(player.getUniqueId());
        if (question == null || question.until() < System.currentTimeMillis()) {
            return;
        }
        event.setCancelled(true);
        String answer = event.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (answer.equalsIgnoreCase(CANCEL)) {
                messages.sendConfiguredMessage(player, "menu-answer-cancelled");
                if (question.cancelled() != null) {
                    question.cancelled().run();
                }
            } else {
                question.answer().accept(answer);
            }
        });
    }

    /**
     * Asks in a window of Bedrock instead of the chat: a line to type the
     * answer into, under the text of the question with {@code -form} after
     * its key. Closing the window, or an empty line, calls it off.
     *
     * @return whether the window went out; if not, the chat asks
     */
    boolean askInForm(Player player, String key, Consumer<String> answer, Runnable cancelled,
                              String... fills) {
        String label = messages.getMessage(key + "-form");
        if (label.isEmpty()) {
            label = messages.getMessage(key).replace("{cancel}", CANCEL);
        }
        for (int i = 0; i + 1 < fills.length; i += 2) {
            label = label.replace(fills[i], fills[i + 1]);
        }
        return plugin.getBedrock().forms().input(player, ChatColor.stripColor(messages.getMessage("menu-title")),
                label, messages.getMessage("menu-form-placeholder"), typed -> {
                    String text = typed.trim();
                    if (text.isEmpty()) {
                        called(player, cancelled);
                    } else {
                        answer.accept(text);
                    }
                }, () -> called(player, cancelled));
    }

    /** A question called off: he hears so, and what calling it off does besides is done. */
    void called(Player player, Runnable cancelled) {
        messages.sendConfiguredMessage(player, "menu-answer-cancelled");
        if (cancelled != null) {
            cancelled.run();
        }
    }

    /** He left: a question that was still open counts no more. */
    void forget(UUID id) {
        questions.remove(id);
    }
}
