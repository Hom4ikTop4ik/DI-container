package di.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ValueConverterTest {

    enum E {A, B}

    @Test
    void givenNullAndPrimitiveExpected_whenConvert_thenThrows() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> ValueConverter.convert(null, int.class)
        );
        assertTrue(ex.getMessage().contains("Cannot inject null into primitive type int"));
    }

    @Test
    void givenNullAndReferenceExpected_whenConvert_thenReturnsNull() {
        assertNull(ValueConverter.convert(null, Integer.class));
        assertNull(ValueConverter.convert(null, String.class));
    }

    @Test
    void givenAlreadyExpectedType_whenConvert_thenReturnsSameInstance() {
        String s = "x";
        Object out = ValueConverter.convert(s, String.class);
        assertSame(s, out);
    }

    @Test
    void givenStringToNumbersAndBooleanAndChar_whenConvert_thenOk() {
        assertEquals(10, ValueConverter.convert("10", int.class));
        assertEquals(10L, ValueConverter.convert("10", long.class));
        assertEquals((short) 10, ValueConverter.convert("10", short.class));
        assertEquals((byte) 10, ValueConverter.convert("10", byte.class));
        assertEquals(1.25d, (Double) ValueConverter.convert("1.25", double.class), 0.0000001);
        assertEquals(1.25f, (Float) ValueConverter.convert("1.25", float.class), 0.0000001f);

        assertEquals(true, ValueConverter.convert("true", boolean.class));
        // Boolean.parseBoolean("xxx") == false — фиксируем контракт.
        assertEquals(false, ValueConverter.convert("xxx", boolean.class));

        assertEquals('Z', ValueConverter.convert("Z", char.class));
    }

    @Test
    void givenInvalidStringToNumber_whenConvert_thenThrowsNumberFormatException() {
        assertThrows(NumberFormatException.class, () -> ValueConverter.convert("qwe", int.class));
        assertThrows(NumberFormatException.class, () -> ValueConverter.convert("qwe", long.class));
        assertThrows(NumberFormatException.class, () -> ValueConverter.convert("qwe", double.class));
    }

    @Test
    void givenStringToCharWithWrongLength_whenConvert_thenThrows() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> ValueConverter.convert("ZZ", char.class)
        );
        assertTrue(ex.getMessage().contains("Cannot convert String"));
        assertTrue(ex.getMessage().contains("java.lang.Character"));
    }

    @Test
    void givenNumberToOtherNumber_whenConvert_thenOk() {
        assertEquals(5L, ValueConverter.convert(5, Long.class));
        assertEquals(5, ValueConverter.convert(5L, Integer.class));
        assertEquals(5.0d, (Double) ValueConverter.convert(5, Double.class), 0.0d);
        assertEquals(5.0f, (Float) ValueConverter.convert(5d, Float.class), 0.0f);
    }

    @Test
    void givenNonConvertibleTypes_whenConvert_thenThrows() {
        Object value = new Object();
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> ValueConverter.convert(value, Integer.class)
        );
        assertTrue(ex.getMessage().contains("Cannot convert value of type"));
    }

    @Test
    void givenEnumString_whenConvert_thenThrowsBecauseStringBranchRunsFirst() {
        // В текущем коде enum-конвертация недостижима из-за более ранней ветки `value instanceof String`.
        // Поэтому фиксируем фактическое поведение: строка в enum НЕ конвертируется и падает.
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> ValueConverter.convert("A", E.class)
        );
        assertTrue(ex.getMessage().contains("Cannot convert String"));
    }
}
