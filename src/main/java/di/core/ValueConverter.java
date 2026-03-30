package di.core;

/**
 * Утилита для приведения литеральных значений из конфигурации
 * к ожидаемым типам параметров/полей/методов.
 */
final class ValueConverter {

    private ValueConverter() {
    }

    static Object convert(Object value, Class<?> expectedType) {
        if (value == null) {
            if (expectedType.isPrimitive()) {
                throw new IllegalStateException("Cannot inject null into primitive type " + expectedType.getName());
            }
            return null;
        }

        // Если уже подходит по типу — можно использовать как есть.
        if (expectedType.isInstance(value)) {
            return value;
        }

        // Для примитивов работаем с их boxed-типами.
        Class<?> target = expectedType.isPrimitive() ? boxed(expectedType) : expectedType;

        // String -> target
        if (value instanceof String s) {
            return fromString(s, target);
        }

        // Числовые приведения (например, Integer -> Long и т.п.)
        if (value instanceof Number n) {
            return fromNumber(n, target);
        }

        // boolean совместимость (например, Boolean -> boolean/Boolean)
        if (value instanceof Boolean b && (target == Boolean.class)) {
            return b;
        }

        // Enum по имени
        if (target.isEnum() && value instanceof String enumName) {
            @SuppressWarnings({"rawtypes", "unchecked"})
            Class<? extends Enum> enumType = (Class<? extends Enum>) target;
            return Enum.valueOf(enumType, enumName);
        }

        throw new IllegalStateException("Cannot convert value of type " + value.getClass().getName()
                + " to " + expectedType.getName());
    }

    private static Object fromString(String s, Class<?> target) {
        if (target == String.class) {
            return s;
        }
        if (target == Integer.class) {
            return Integer.parseInt(s);
        }
        if (target == Long.class) {
            return Long.parseLong(s);
        }
        if (target == Short.class) {
            return Short.parseShort(s);
        }
        if (target == Byte.class) {
            return Byte.parseByte(s);
        }
        if (target == Double.class) {
            return Double.parseDouble(s);
        }
        if (target == Float.class) {
            return Float.parseFloat(s);
        }
        if (target == Boolean.class) {
            return Boolean.parseBoolean(s);
        }
        if (target == Character.class && s.length() == 1) {
            return s.charAt(0);
        }
        throw new IllegalStateException("Cannot convert String '" + s + "' to " + target.getName());
    }

    private static Object fromNumber(Number n, Class<?> target) {
        if (target == Integer.class) {
            return n.intValue();
        }
        if (target == Long.class) {
            return n.longValue();
        }
        if (target == Short.class) {
            return n.shortValue();
        }
        if (target == Byte.class) {
            return n.byteValue();
        }
        if (target == Double.class) {
            return n.doubleValue();
        }
        if (target == Float.class) {
            return n.floatValue();
        }
        throw new IllegalStateException("Cannot convert Number " + n + " to " + target.getName());
    }

    private static Class<?> boxed(Class<?> primitive) {
        if (primitive == int.class) return Integer.class;
        if (primitive == long.class) return Long.class;
        if (primitive == short.class) return Short.class;
        if (primitive == byte.class) return Byte.class;
        if (primitive == double.class) return Double.class;
        if (primitive == float.class) return Float.class;
        if (primitive == boolean.class) return Boolean.class;
        if (primitive == char.class) return Character.class;
        return primitive;
    }
}

