package de.elia.cameraplugin.bedrock;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.logging.Level;

/**
 * The windows of Bedrock itself - a list of buttons, a question with two
 * answers, a line to type into -, sent through Floodgate or Geyser on this
 * server.
 *
 * <p>Both bring Cumulus along, the library these windows are built with.
 * Neither is part of the server, and the plugin builds and runs without
 * them: everything is reached by reflection, the way
 * {@code VaultEconomy} reaches Vault. Each of them has its own copy of
 * Cumulus in its own class loader, and a window built out of one copy is
 * only taken by the one it came from - so every window is built out of the
 * classes of the one that sends it.</p>
 *
 * <p>Geyser on this server is asked first: it holds the connection to the
 * Bedrock client itself. Floodgate also reaches a player whose Geyser runs on
 * a proxy in front of the server - it hands the window on to it.</p>
 *
 * <p>The answer comes on a thread of theirs; what it does runs on the main
 * thread, and only while the player is still online.</p>
 */
public final class BedrockForms {

    private final CameraPlugin plugin;
    /** Geyser on this server, or {@code null}. */
    private Api geyser;
    /** Floodgate on this server, or {@code null}. */
    private Api floodgate;
    /** Whether the failure of a window has been said in the console already, which is said once. */
    private boolean failureReported;

    public BedrockForms(CameraPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * One of the two, with what is needed of it: the object of its API, how
     * it tells a Bedrock player, how it sends a window, and the classes of
     * its Cumulus.
     */
    private record Api(String name, Object instance, Method isBedrock, Method send, Cumulus cumulus) {

        boolean reaches(UUID id) {
            try {
                return Boolean.TRUE.equals(isBedrock.invoke(instance, id));
            } catch (ReflectiveOperationException | RuntimeException ex) {
                return false;
            }
        }
    }

    /**
     * The parts of Cumulus a window is built of, out of one class loader. The
     * list to pick from and the switch of a window to fill in are only
     * looked for, see {@link #optional}: without them every other window
     * still goes.
     */
    private record Cumulus(Method title, Method build, Method validHandler, Method closedHandler,
                           Method simpleBuilder, Method simpleContent, Method simpleButton, Method simpleClicked,
                           Method modalBuilder, Method modalContent, Method modalFirst, Method modalSecond,
                           Method modalClickedFirst,
                           Method customBuilder, Method customInput, Method customAnswer,
                           Method customDropdown, Method customDropdownAnswer,
                           Method customToggle, Method customToggleAnswer) {

        static Cumulus of(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> builder = type(loader, "form.util.FormBuilder");
            Class<?> simple = type(loader, "form.SimpleForm");
            Class<?> simpleBuilder = type(loader, "form.SimpleForm$Builder");
            Class<?> modal = type(loader, "form.ModalForm");
            Class<?> modalBuilder = type(loader, "form.ModalForm$Builder");
            Class<?> custom = type(loader, "form.CustomForm");
            Class<?> customBuilder = type(loader, "form.CustomForm$Builder");
            Class<?> customResponse = type(loader, "response.CustomFormResponse");
            return new Cumulus(
                    builder.getMethod("title", String.class),
                    builder.getMethod("build"),
                    builder.getMethod("validResultHandler", Consumer.class),
                    builder.getMethod("closedOrInvalidResultHandler", Runnable.class),
                    simple.getMethod("builder"),
                    simpleBuilder.getMethod("content", String.class),
                    simpleBuilder.getMethod("button", String.class),
                    type(loader, "response.SimpleFormResponse").getMethod("clickedButtonId"),
                    modal.getMethod("builder"),
                    modalBuilder.getMethod("content", String.class),
                    modalBuilder.getMethod("button1", String.class),
                    modalBuilder.getMethod("button2", String.class),
                    type(loader, "response.ModalFormResponse").getMethod("clickedFirst"),
                    custom.getMethod("builder"),
                    customBuilder.getMethod("input", String.class, String.class, String.class),
                    customResponse.getMethod("asInput", int.class),
                    optional(customBuilder, "dropdown", String.class, List.class, int.class),
                    optional(customResponse, "asDropdown", int.class),
                    optional(customBuilder, "toggle", String.class, boolean.class),
                    optional(customResponse, "asToggle", int.class));
        }

        /** A method that an older or newer Cumulus may not have, or {@code null}. */
        static Method optional(Class<?> type, String name, Class<?>... parameters) {
            try {
                return type.getMethod(name, parameters);
            } catch (NoSuchMethodException ex) {
                return null;
            }
        }

        /** Whether a window with these fields can be built out of this copy. */
        boolean builds(List<Field> fields) {
            for (Field field : fields) {
                if (field instanceof Dropdown && (customDropdown == null || customDropdownAnswer == null)
                        || field instanceof Toggle && (customToggle == null || customToggleAnswer == null)) {
                    return false;
                }
            }
            return true;
        }

        static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException {
            return Class.forName("org.geysermc.cumulus." + name, true, loader);
        }
    }

    /**
     * Looks Geyser and Floodgate up, at the start and after a reload. What is
     * not there, or not as expected, is simply not used.
     */
    public void load() {
        geyser = api("Geyser-Spigot", "org.geysermc.geyser.api.GeyserApi", "api", "isBedrockPlayer");
        floodgate = api("floodgate", "org.geysermc.floodgate.api.FloodgateApi", "getInstance",
                "isFloodgatePlayer");
    }

    private Api api(String pluginName, String apiClass, String getter, String test) {
        if (!Bukkit.getPluginManager().isPluginEnabled(pluginName)) {
            return null;
        }
        try {
            ClassLoader loader = Bukkit.getPluginManager().getPlugin(pluginName).getClass().getClassLoader();
            Class<?> type = Class.forName(apiClass, true, loader);
            Object instance = type.getMethod(getter).invoke(null);
            if (instance == null) {
                return null;
            }
            Class<?> form = Class.forName("org.geysermc.cumulus.form.Form", true, loader);
            return new Api(pluginName, instance, type.getMethod(test, UUID.class),
                    type.getMethod("sendForm", UUID.class, form), Cumulus.of(loader));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            plugin.getLogger().log(Level.WARNING, pluginName + " is there, but its windows for Bedrock players"
                    + " cannot be reached: " + ex);
            return null;
        }
    }

    /** Whether Geyser or Floodgate is on this server at all. */
    public boolean present() {
        return geyser != null || floodgate != null;
    }

    /** Whether Geyser or Floodgate on this server knows him as a player on Bedrock. */
    public boolean knows(Player player) {
        return reaching(player) != null;
    }

    /** The one that reaches him: Geyser first, then Floodgate; {@code null} for neither. */
    private Api reaching(Player player) {
        UUID id = player.getUniqueId();
        if (geyser != null && geyser.reaches(id)) {
            return geyser;
        }
        if (floodgate != null && floodgate.reaches(id)) {
            return floodgate;
        }
        return null;
    }

    /**
     * A window with a list of buttons, one under the other.
     *
     * @param clicked what a button does, by its place in the list from 0
     * @param closed  what closing the window does; {@code null} for nothing
     * @return whether it went out - if not, nothing comes back
     */
    public boolean list(Player player, String title, String content, List<String> buttons, IntConsumer clicked,
                        Runnable closed) {
        return send(player, (cumulus, form) -> {
            form = cumulus.simpleContent().invoke(form, content);
            for (String button : buttons) {
                form = cumulus.simpleButton().invoke(form, button);
            }
            return form;
        }, Cumulus::simpleBuilder, title, (cumulus, response) -> {
            int index = (Integer) cumulus.simpleClicked().invoke(response);
            if (index >= 0 && index < buttons.size()) {
                clicked.accept(index);
            }
        }, closed);
    }

    /**
     * A window with a text and two buttons under it.
     *
     * @param answer {@code true} for the first button, {@code false} for the second
     * @param closed what closing the window does; {@code null} for nothing
     * @return whether it went out
     */
    public boolean question(Player player, String title, String content, String first, String second,
                            Consumer<Boolean> answer, Runnable closed) {
        return send(player, (cumulus, form) -> {
            form = cumulus.modalContent().invoke(form, content);
            form = cumulus.modalFirst().invoke(form, first);
            return cumulus.modalSecond().invoke(form, second);
        }, Cumulus::modalBuilder, title, (cumulus, response) ->
                answer.accept((Boolean) cumulus.modalClickedFirst().invoke(response)), closed);
    }

    /**
     * A window with a line to type into.
     *
     * @param label  what stands over the line
     * @param answer what was typed, as it was typed
     * @param closed what closing the window does; {@code null} for nothing
     * @return whether it went out
     */
    public boolean input(Player player, String title, String label, String placeholder, Consumer<String> answer,
                         Runnable closed) {
        return send(player, (cumulus, form) -> cumulus.customInput().invoke(form, label, placeholder, ""),
                Cumulus::customBuilder, title, (cumulus, response) -> {
                    Object text = cumulus.customAnswer().invoke(response, 0);
                    answer.accept(text == null ? "" : text.toString());
                }, closed);
    }

    /** One field of a window to fill in, see {@link #fill}. */
    public sealed interface Field permits Input, Dropdown, Toggle {
    }

    /** A line to type into, with what it holds already. */
    public record Input(String label, String placeholder, String value) implements Field {
    }

    /** A list to pick one from, with the one picked already, from 0. */
    public record Dropdown(String label, List<String> options, int chosen) implements Field {
    }

    /** A switch, on or off already. */
    public record Toggle(String label, boolean on) implements Field {
    }

    /**
     * A window of fields to fill in, one under the other, and the button that
     * sends it: lines to type into, lists to pick from, switches.
     *
     * @param answer what was filled in, one value for each field in their
     *               order: the text of a line as it was typed, the place of
     *               the pick of a list from 0 ({@link Integer}), whether a
     *               switch is on ({@link Boolean})
     * @param closed what closing the window does; {@code null} for nothing
     * @return whether it went out - not where the Cumulus there cannot build
     *         such a field
     */
    public boolean fill(Player player, String title, List<Field> fields, Consumer<List<Object>> answer,
                        Runnable closed) {
        Api api = reaching(player);
        if (api == null || !api.cumulus().builds(fields)) {
            return false;
        }
        return send(player, (cumulus, form) -> {
            for (Field field : fields) {
                form = switch (field) {
                    case Input input -> cumulus.customInput().invoke(form, input.label(), input.placeholder(),
                            input.value());
                    case Dropdown dropdown -> cumulus.customDropdown().invoke(form, dropdown.label(),
                            dropdown.options(), dropdown.chosen());
                    case Toggle toggle -> cumulus.customToggle().invoke(form, toggle.label(), toggle.on());
                };
            }
            return form;
        }, Cumulus::customBuilder, title, (cumulus, response) -> {
            List<Object> values = new ArrayList<>(fields.size());
            for (int index = 0; index < fields.size(); index++) {
                Object value = switch (fields.get(index)) {
                    case Input input -> cumulus.customAnswer().invoke(response, index);
                    case Dropdown dropdown -> cumulus.customDropdownAnswer().invoke(response, index);
                    case Toggle toggle -> cumulus.customToggleAnswer().invoke(response, index);
                };
                values.add(fields.get(index) instanceof Input && value == null ? "" : value);
            }
            answer.accept(values);
        }, closed);
    }

    /** What fills a window in, between its title and its handlers. */
    @FunctionalInterface
    private interface Fill {
        Object apply(Cumulus cumulus, Object form) throws ReflectiveOperationException;
    }

    /** What reads the answer out of what came back. */
    @FunctionalInterface
    private interface Read {
        void accept(Cumulus cumulus, Object response) throws ReflectiveOperationException;
    }

    /**
     * Builds a window out of the Cumulus of the one that reaches him, and
     * sends it. A failure is said once in the console; the caller goes on as
     * if there were no window.
     */
    private boolean send(Player player, Fill fill, java.util.function.Function<Cumulus, Method> builder,
                         String title, Read read, Runnable closed) {
        Api api = reaching(player);
        if (api == null) {
            return false;
        }
        Cumulus cumulus = api.cumulus();
        UUID id = player.getUniqueId();
        try {
            Object form = builder.apply(cumulus).invoke(null);
            form = cumulus.title().invoke(form, title);
            form = fill.apply(cumulus, form);
            Consumer<Object> valid = response -> onMain(id, () -> {
                try {
                    read.accept(cumulus, response);
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    reportOnce(api, ex);
                }
            });
            form = cumulus.validHandler().invoke(form, valid);
            Runnable shut = () -> {
                if (closed != null) {
                    onMain(id, closed);
                }
            };
            form = cumulus.closedHandler().invoke(form, shut);
            Object built = cumulus.build().invoke(form);
            return Boolean.TRUE.equals(api.send().invoke(api.instance(), id, built));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            reportOnce(api, ex);
            return false;
        }
    }

    /** Runs something on the main thread, if he is still online then. */
    private void onMain(UUID id, Runnable action) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                action.run();
            }
        });
    }

    private void reportOnce(Api api, Throwable ex) {
        if (failureReported) {
            return;
        }
        failureReported = true;
        plugin.getLogger().log(Level.WARNING, "A window for a Bedrock player could not be sent through "
                + api.name() + "; it goes on without one - the menu as a chest, an invitation in the chat alone", ex);
    }
}
