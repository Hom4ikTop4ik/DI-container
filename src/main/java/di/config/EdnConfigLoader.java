package di.config;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.IPersistentMap;
import clojure.lang.IPersistentVector;
import clojure.lang.ISeq;
import clojure.lang.Keyword;
import clojure.lang.LineNumberingPushbackReader;
import clojure.lang.MapEntry;
import clojure.lang.PersistentVector;
import clojure.lang.Seqable;
import clojure.lang.Symbol;
import di.model.BeanDefinition;
import di.model.BeanValue;
import di.model.LiteralValue;
import di.model.ListValue;
import di.model.MapValue;
import di.model.MethodArg;
import di.model.MethodInjection;
import di.model.RefValue;
import di.model.Scope;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * EDN data-only loader.
 *
 * Format:
 * {:beans [ {:name "...", :class "...", :scope :singleton|:prototype|:thread
 *            :constructor-args [{:index 0 :arg (value ...)} ...]
 *            :methods [{:name "setX" :args [{:index 0 :arg (ref "...")} ...]} ...]
 *          } ... ]}
 *
 * Values are strictly: (ref "beanName") | (value X)
 * Collections are supported only inside (value ...): vectors and maps.
 */
public final class EdnConfigLoader {

    private static final IFn EDN_READ = Clojure.var("clojure.edn", "read");

    // допустимые ключи в bean-map
    private static final Set<Keyword> ALLOWED_BEAN_KEYS = Set.of(
            kw("name"),
            kw("class"),
            kw("scope"),
            kw("constructor-args"),
            kw("methods")
    );

    public List<BeanDefinition> loadFromResource(String resourceName) {
        Objects.requireNonNull(resourceName, "resourceName");

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = EdnConfigLoader.class.getClassLoader();

        InputStream is = cl.getResourceAsStream(resourceName);
        if (is == null) {
            throw new EdnValidationException("EDN resource not found: " + resourceName);
        }

        try (Reader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return load(r);
        } catch (Exception e) {
            if (e instanceof EdnValidationException eve) throw eve;
            throw new EdnValidationException("Failed to load EDN config from resource: " + resourceName, e);
        }
    }

    public List<BeanDefinition> load(Reader reader) {
        Objects.requireNonNull(reader, "reader");

        Object root;
        try {
            // data-only parse: clojure.edn/read
            root = EDN_READ.invoke(new LineNumberingPushbackReader(reader));
        } catch (Exception e) {
            throw new EdnValidationException("Failed to parse EDN", e);
        }

        IPersistentMap rootMap = requireMap(root, "root");
        Object beansObj = rootMap.valAt(kw("beans"));
        if (beansObj == null) {
            throw new EdnValidationException("Missing required key :beans at root");
        }

        IPersistentVector beansVec = requireVector(beansObj, "root.:beans");

        List<BeanDefinition> defs = new ArrayList<>(beansVec.count());
        Set<String> names = new HashSet<>();

        for (int i = 0; i < beansVec.count(); i++) {
            IPersistentMap bean = requireMap(beansVec.nth(i), "root.:beans[" + i + "]");
            validateNoUnknownBeanKeys(bean, "root.:beans[" + i + "]");

            String name = requireString(bean.valAt(kw("name")), ctx(i, ":name"));
            if (!names.add(name)) {
                throw new EdnValidationException("Duplicate bean name: " + name);
            }

            String className = requireString(bean.valAt(kw("class")), ctx(i, ":class"));
            Class<?> implClass;
            try {
                implClass = Class.forName(className);
            } catch (ClassNotFoundException e) {
                throw new EdnValidationException("Class not found for bean '" + name + "': " + className, e);
            }

            Scope scope = parseScope(bean.valAt(kw("scope")), ctx(i, ":scope"));

            List<MethodArg> ctorArgs = parseConstructorArgs(bean.valAt(kw("constructor-args")), i);
            List<MethodInjection> methodInjections = parseMethods(bean.valAt(kw("methods")), i);

            defs.add(new BeanDefinition(name, implClass, scope, ctorArgs, methodInjections));
        }

        return List.copyOf(defs);
    }

    // -----------------------------
    // Parse: constructor-args
    // -----------------------------

    private List<MethodArg> parseConstructorArgs(Object obj, int beanIndex) {
        if (obj == null) return List.of();
        IPersistentVector vec = requireVector(obj, ctx(beanIndex, ":constructor-args"));

        Set<Integer> usedIndexes = new HashSet<>();
        List<MethodArg> out = new ArrayList<>(vec.count());

        for (int i = 0; i < vec.count(); i++) {
            IPersistentMap argMap = requireMap(vec.nth(i), ctx(beanIndex, ":constructor-args[" + i + "]"));

            int index = requireInt(argMap.valAt(kw("index")), ctx(beanIndex, "ctor-arg.:index"));

            if (index < 0) {
                throw new EdnValidationException(ctx(beanIndex, "ctor-arg.:index") + ": index must be >= 0, got: " + index);
            }
            if (!usedIndexes.add(index)) {
                throw new EdnValidationException(ctx(beanIndex, ":constructor-args") + ": duplicate :index " + index);
            }

            Object form = argMap.valAt(kw("arg"));
            if (form == null) {
                throw new EdnValidationException(ctx(beanIndex, "ctor-arg.:arg") + ": missing :arg");
            }

            BeanValue value = parseArgForm(form, ctx(beanIndex, "ctor-arg.:arg"));
            out.add(new MethodArg(index, value));
        }

        return List.copyOf(out);
    }

    // -----------------------------
    // Parse: methods
    // -----------------------------

    private List<MethodInjection> parseMethods(Object obj, int beanIndex) {
        if (obj == null) return List.of();
        IPersistentVector vec = requireVector(obj, ctx(beanIndex, ":methods"));

        List<MethodInjection> out = new ArrayList<>(vec.count());
        for (int i = 0; i < vec.count(); i++) {
            IPersistentMap m = requireMap(vec.nth(i), ctx(beanIndex, ":methods[" + i + "]"));

            String methodName = requireString(m.valAt(kw("name")), ctx(beanIndex, "method.:name"));

            Object argsObj = m.valAt(kw("args"));
            IPersistentVector argsVec = argsObj == null ? emptyVector() : requireVector(argsObj, ctx(beanIndex, "method.:args"));

            List<MethodArg> args = new ArrayList<>(argsVec.count());
            Set<Integer> usedIndexes = new HashSet<>();
            for (int j = 0; j < argsVec.count(); j++) {
                IPersistentMap argMap = requireMap(argsVec.nth(j), ctx(beanIndex, "method.:args[" + j + "]"));

                int index = requireInt(argMap.valAt(kw("index")), ctx(beanIndex, "method-arg.:index"));
                if (index < 0) {
                    throw new EdnValidationException(ctx(beanIndex, "method-arg.:index") + ": index must be >= 0, got: " + index);
                }
                if (!usedIndexes.add(index)) {
                    throw new EdnValidationException(ctx(beanIndex, "method.:args") + ": duplicate :index " + index
                            + " for method '" + methodName + "'");
                }
                Object form = argMap.valAt(kw("arg"));
                if (form == null) {
                    throw new EdnValidationException(ctx(beanIndex, "method-arg.:arg") + ": missing :arg");
                }

                BeanValue value = parseArgForm(form, ctx(beanIndex, "method-arg.:arg"));
                args.add(new MethodArg(index, value));
            }

            out.add(new MethodInjection(methodName, List.copyOf(args)));
        }

        return List.copyOf(out);
    }

    // -----------------------------
    // Parse: scope
    // -----------------------------

    private Scope parseScope(Object scopeObj, String ctx) {
        if (!(scopeObj instanceof Keyword k)) {
            throw new EdnValidationException(ctx + ": expected keyword :singleton/:prototype/:thread, got: " + typeName(scopeObj));
        }
        return switch (k.getName()) {
            case "singleton" -> Scope.SINGLETON;
            case "prototype" -> Scope.PROTOTYPE;
            case "thread" -> Scope.THREAD;
            default -> throw new EdnValidationException(ctx + ": unsupported scope: :" + k.getName());
        };
    }

    // -----------------------------
    // Parse: (ref "...") / (value ...)
    // -----------------------------

    private BeanValue parseArgForm(Object form, String ctx) {
        if (!(form instanceof Seqable seqable)) {
            throw new EdnValidationException(ctx + ": expected (ref ...) or (value ...), got: " + typeName(form));
        }

        ISeq seq = seqable.seq();
        if (seq == null) {
            throw new EdnValidationException(ctx + ": expected non-empty list (ref ...) or (value ...)");
        }

        Object head = seq.first();
        if (!(head instanceof Symbol sym)) {
            throw new EdnValidationException(ctx + ": first element must be symbol 'ref' or 'value', got: " + typeName(head));
        }

        String op = sym.getName();
        ISeq tail = seq.next();
        if (tail == null) {
            if ("value".equals(op)) {
                throw new EdnValidationException(ctx + ": (value ...) expects exactly 1 argument");
            }
            if ("ref".equals(op)) {
                throw new EdnValidationException(ctx + ": (ref ...) expects exactly 1 argument");
            }
            throw new EdnValidationException(ctx + ": (" + op + " ...) expects exactly 1 argument");
        }

        Object arg1 = tail.first();
        if (tail.next() != null) {
            if ("value".equals(op)) {
                throw new EdnValidationException(ctx + ": (value ...) expects exactly 1 argument");
            }
            if ("ref".equals(op)) {
                throw new EdnValidationException(ctx + ": (ref ...) expects exactly 1 argument");
            }
            throw new EdnValidationException(ctx + ": (" + op + " ...) expects exactly 1 argument");
        }

        return switch (op) {
            case "ref" -> parseRef(arg1, ctx);
            case "value" -> parseValue(arg1, ctx);
            default -> throw new EdnValidationException(ctx + ": unknown form (" + op + " ...), expected ref/value");
        };
    }

    private BeanValue parseRef(Object arg, String ctx) {
        if (!(arg instanceof String s)) {
            throw new EdnValidationException(ctx + ": (ref ...) expects string bean name, got: " + typeName(arg));
        }
        return new RefValue(s);
    }

    private BeanValue parseValue(Object arg, String ctx) {
        // collections allowed only inside (value ...)
        if (arg instanceof IPersistentVector vec) {
            List<BeanValue> elements = new ArrayList<>(vec.count());
            for (int i = 0; i < vec.count(); i++) {
                Object el = vec.nth(i);
                elements.add(parseValueElement(el, ctx + "[value-vector:" + i + "]"));
            }
            return new ListValue(List.copyOf(elements));
        }

        if (arg instanceof IPersistentMap map) {
            Map<String, BeanValue> entries = new LinkedHashMap<>();

            for (ISeq s = map.seq(); s != null; s = s.next()) {
                MapEntry e = (MapEntry) s.first(); // элементы seq у map — MapEntry
                Object keyObj = e.key();
                Object valObj = e.val();

                String key = mapKeyToString(keyObj, ctx + "[value-map-key]");
                entries.put(key, parseValueElement(valObj, ctx + "[value-map:" + key + "]"));
            }

            return new MapValue(Map.copyOf(entries));
        }

        // scalar literal
        return new LiteralValue(arg);
    }

    private BeanValue parseValueElement(Object el, String ctx) {
        // allow nested (ref)/(value) OR raw literal
        if (el instanceof Seqable) {
            return parseArgForm(el, ctx);
        }
        return new LiteralValue(el);
    }

    private String mapKeyToString(Object keyObj, String ctx) {
        if (keyObj instanceof String s) return s;
        if (keyObj instanceof Keyword k) return k.getName(); // :a -> "a"
        throw new EdnValidationException(ctx + ": map key must be string or keyword, got: " + typeName(keyObj));
    }

    // -----------------------------
    // Helpers
    // -----------------------------

    private static Keyword kw(String name) {
        return Keyword.intern(null, name);
    }

    private static IPersistentMap requireMap(Object o, String ctx) {
        if (o instanceof IPersistentMap m) return m;
        throw new EdnValidationException(ctx + ": expected map, got: " + typeName(o));
    }

    private static IPersistentVector requireVector(Object o, String ctx) {
        if (o instanceof IPersistentVector v) return v;
        throw new EdnValidationException(ctx + ": expected vector, got: " + typeName(o));
    }

    private static void validateNoUnknownBeanKeys(IPersistentMap bean, String ctx) {
        for (ISeq s = bean.seq(); s != null; s = s.next()) {
            MapEntry e = (MapEntry) s.first();
            Object keyObj = e.key();

            if (!(keyObj instanceof Keyword k) || !ALLOWED_BEAN_KEYS.contains(k)) {
                throw new EdnValidationException(ctx + ": unknown key " + formatEdnKey(keyObj)
                        + ". Allowed keys: :name, :class, :scope, :constructor-args, :methods");
            }
        }
    }

    private static String requireString(Object o, String ctx) {
        if (o instanceof String s) return s;
        throw new EdnValidationException(ctx + ": expected string, got: " + typeName(o));
    }

    private static int requireInt(Object o, String ctx) {
        if (o instanceof Number n) return n.intValue();
        throw new EdnValidationException(ctx + ": expected int, got: " + typeName(o));
    }

    private static String ctx(int beanIndex, String msg) {
        return "bean[:beans[" + beanIndex + "] " + msg + "]";
    }

    private static IPersistentVector emptyVector() {
        // simplest portable empty vector
        return (IPersistentVector) PersistentVector.EMPTY;
    }

    private static String typeName(Object o) {
        return o == null ? "null" : o.getClass().getName() + " (" + o + ")";
    }

    private static String formatEdnKey(Object k) {
        if (k instanceof Keyword kw) return ":" + kw.getName();
        return String.valueOf(k);
    }

}
