package de.elia.cameraplugin.webmap;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link Calls}, through which the plugin talks to BlueMap, Dynmap and
 * squaremap without being built against them - here on classes of Java
 * itself, which follow the same rules.
 */
class CallsTest {

    @Test
    void methodIsFoundByWhatItIsHanded() throws ReflectiveOperationException {
        assertEquals("mera", Calls.call("camera", "substring", 2));
        assertEquals("am", Calls.call("camera", "substring", 1, 3));
    }

    @Test
    void wrapperFitsItsOwnPrimitiveAndNoOther() throws ReflectiveOperationException {
        assertEquals(2.5, Calls.callStatic(Math.class, "abs", -2.5));
        assertEquals(2, Calls.callStatic(Math.class, "abs", -2));
        assertThrows(NoSuchMethodException.class, () -> Calls.call("camera", "substring", 2L));
    }

    @Test
    void nullFitsAnObjectButNoPrimitive() throws ReflectiveOperationException {
        assertEquals(true, Calls.callStatic(Objects.class, "isNull", new Object[]{null}));
        assertThrows(NoSuchMethodException.class, () -> Calls.callStatic(Math.class, "abs", new Object[]{null}));
    }

    @Test
    void staticAndInstanceMethodsAreKeptApart() {
        assertThrows(NoSuchMethodException.class, () -> Calls.call("camera", "valueOf", 5));
        assertThrows(NoSuchMethodException.class, () -> Calls.callStatic(String.class, "length"));
    }

    @Test
    void methodOfAClassThatIsNotOpenIsCalledThroughItsInterface() throws ReflectiveOperationException {
        // The class behind List.of is not public, its methods are those of List.
        List<Integer> list = List.of(4, 5, 6);

        assertEquals(3, Calls.call(list, "size"));
        assertEquals(5, Calls.call(list, "get", 1));
    }

    @Test
    void whatTheMethodThrowsComesThroughAsItIs() {
        assertThrows(StringIndexOutOfBoundsException.class, () -> Calls.call("camera", "substring", 10));
    }

    @Test
    void missingMethodSaysWhatItWasHanded() {
        NoSuchMethodException missing = assertThrows(NoSuchMethodException.class,
                () -> Calls.call("camera", "fly", 1, null));

        assertEquals("java.lang.String.fly(Integer, null)", missing.getMessage());
    }

    @Test
    void constructorIsFoundByWhatItIsHanded() throws ReflectiveOperationException {
        assertEquals("cam", Calls.create(StringBuilder.class, "cam").toString());
        assertEquals("", Calls.create(StringBuilder.class, 32).toString());

        NoSuchMethodException missing = assertThrows(NoSuchMethodException.class,
                () -> Calls.create(StringBuilder.class, 1.5));
        assertEquals("java.lang.StringBuilder.<init>(Double)", missing.getMessage());
    }

    @Test
    void classIsLoadedByName() throws ClassNotFoundException {
        assertSame(String.class, Calls.load(getClass().getClassLoader(), "java.lang.String"));
    }
}
