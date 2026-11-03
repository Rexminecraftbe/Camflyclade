package de.elia.cameraplugin.webmap;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Calls into the interface of a web map by name, without the plugin being
 * built against it.
 *
 * <p>BlueMap, Dynmap and squaremap each come with an interface of their own,
 * each in its own repository. Built against all three, the plugin would only
 * build while all three repositories answer - Dynmap's is down often enough -,
 * and every server would have to carry their classes to start at all. Looked
 * up by name only on a server that has the map, nothing of it is needed
 * anywhere else. What the names stand for is written next to each call, in
 * the classes of the maps; which version of each map they were checked
 * against, there as well.</p>
 *
 * <p>A method is found among the public ones of the class of what it is
 * called on, by its name and by what it is handed: a {@code Double} fits a
 * {@code double} and nothing else, {@code null} fits anything that is no
 * number or truth value. The first that fits is taken - which is why the
 * calls hand over exactly what the method of the map takes.</p>
 */
final class Calls {

    /** The truth value and number of each wrapper a call hands over. */
    private static final Map<Class<?>, Class<?>> PRIMITIVES = Map.of(
            Boolean.class, boolean.class,
            Integer.class, int.class,
            Long.class, long.class,
            Double.class, double.class,
            Float.class, float.class);

    private Calls() {
    }

    /** A class of a map, out of the class loader of its plugin. */
    static Class<?> load(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, true, loader);
    }

    /** Calls a method of an object by name. */
    static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        return invoke(target.getClass(), target, name, args);
    }

    /** Calls a static method of a class by name. */
    static Object callStatic(Class<?> type, String name, Object... args) throws ReflectiveOperationException {
        return invoke(type, null, name, args);
    }

    /** Makes a new object of a class with the constructor that fits. */
    static Object create(Class<?> type, Object... args) throws ReflectiveOperationException {
        for (Constructor<?> constructor : type.getConstructors()) {
            if (fits(constructor.getParameterTypes(), args)) {
                return unwrap(() -> constructor.newInstance(args));
            }
        }
        throw new NoSuchMethodException(type.getName() + ".<init>" + describe(args));
    }

    private static Object invoke(Class<?> type, Object target, String name, Object[] args)
            throws ReflectiveOperationException {
        Method found = null;
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || (target == null) != Modifier.isStatic(method.getModifiers())
                    || !fits(method.getParameterTypes(), args)) {
                continue;
            }
            Method callable = callable(type, target, method);
            if (callable != null) {
                found = callable;
                break;
            }
        }
        if (found == null) {
            throw new NoSuchMethodException(type.getName() + "." + name + describe(args));
        }
        Method method = found;
        return unwrap(() -> method.invoke(target, args));
    }

    /**
     * The method as it can be called from here. A class of the map's own
     * that is not open to other plugins hands out the methods of its public
     * interface all the same, only they cannot be called through it: then
     * the same method of the interface is taken, and only where there is
     * none, the class is asked to let the call in.
     */
    private static Method callable(Class<?> type, Object target, Method method) {
        if (method.canAccess(target)) {
            return method;
        }
        for (Class<?> owner : supertypes(type)) {
            try {
                Method same = owner.getMethod(method.getName(), method.getParameterTypes());
                if (same.canAccess(target)) {
                    return same;
                }
            } catch (NoSuchMethodException ex) {
                // Not this one.
            }
        }
        return method.trySetAccessible() ? method : null;
    }

    /** The classes a class extends and every interface of them, the nearest first. */
    private static Set<Class<?>> supertypes(Class<?> type) {
        Set<Class<?>> all = new LinkedHashSet<>();
        Deque<Class<?>> todo = new ArrayDeque<>();
        todo.add(type);
        while (!todo.isEmpty()) {
            Class<?> next = todo.poll();
            if (next == null || !all.add(next)) {
                continue;
            }
            if (next.getSuperclass() != null) {
                todo.add(next.getSuperclass());
            }
            todo.addAll(Arrays.asList(next.getInterfaces()));
        }
        all.remove(type);
        return all;
    }

    /** Whether what is handed over fits these parameters. */
    private static boolean fits(Class<?>[] parameters, Object[] args) {
        if (parameters.length != args.length) {
            return false;
        }
        for (int i = 0; i < parameters.length; i++) {
            Object arg = args[i];
            Class<?> parameter = parameters[i];
            if (parameter.isPrimitive()) {
                if (arg == null || PRIMITIVES.get(arg.getClass()) != parameter) {
                    return false;
                }
            } else if (arg != null && !parameter.isInstance(arg)) {
                return false;
            }
        }
        return true;
    }

    private interface Reflective {
        Object run() throws ReflectiveOperationException;
    }

    /**
     * Runs a call and hands on what the map itself threw, rather than the
     * wrapper reflection puts around it: that says what went wrong.
     */
    private static Object unwrap(Reflective call) throws ReflectiveOperationException {
        try {
            return call.run();
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw ex;
        }
    }

    /** What was handed over, for the message of a method that was not found. */
    private static String describe(Object[] args) {
        StringBuilder text = new StringBuilder("(");
        for (int i = 0; i < args.length; i++) {
            text.append(i > 0 ? ", " : "").append(args[i] == null ? "null" : args[i].getClass().getSimpleName());
        }
        return text.append(')').toString();
    }
}
