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

    private static final Keyword KW_BEANS = kw("beans");

    private static final Keyword KW_NAME = kw("name");
    private static final Keyword KW_CLASS = kw("class");
    private static final Keyword KW_SCOPE = kw("scope");
    private static final Keyword KW_CONSTRUCTOR_ARGS = kw("constructor-args");
    private static final Keyword KW_METHODS = kw("methods");

    private static final Keyword KW_INDEX = kw("index");
    private static final Keyword KW_ARG = kw("arg");

    private static final Keyword KW_METHOD_NAME = kw("name");
    private static final Keyword KW_METHOD_ARGS = kw("args");

    // допустимые ключи в bean-map
    private static final Set<Keyword> ALLOWED_BEAN_KEYS = Set.of(
            KW_NAME,
            KW_CLASS,
            KW_SCOPE,
            KW_CONSTRUCTOR_ARGS,
            KW_METHODS
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
        Object beansObj = rootMap.valAt(KW_BEANS);
        if (beansObj == null) {
            throw new EdnValidationException("Missing required key :beans at root");
        }

        IPersistentVector beansVec = requireVector(beansObj, "root.:beans");

        List<BeanDefinition> defs = new ArrayList<>(beansVec.count());
        Set<String> names = new HashSet<>();

        for (int i = 0; i < beansVec.count(); i++) {
            String beanCtx = "root.:beans[" + i + "]";
            IPersistentMap bean = requireMap(beansVec.nth(i), beanCtx);

            validateNoUnknownBeanKeys(bean, beanCtx);

            // Fail-fast: required keys
            requirePresent(bean, KW_NAME, beanCtx);
            requirePresent(bean, KW_CLASS, beanCtx);
            requirePresent(bean, KW_SCOPE, beanCtx);

            String name = requireString(bean.valAt(KW_NAME), ctx(i, ":name"));
            if (!names.add(name)) {
                throw new EdnValidationException("Duplicate bean name: " + name);
            }

            String className = requireString(bean.valAt(KW_CLASS), ctx(i, ":class"));
            Class<?> implClass;
            try {
                implClass = Class.forName(className);
            } catch (ClassNotFoundException e) {
                throw new EdnValidationException("Class not found for bean '" + name + "': " + className, e);
            }

            Scope scope = parseScope(bean.valAt(KW_SCOPE), ctx(i, ":scope"));

            List<MethodArg> ctorArgs = parseConstructorArgs(bean.valAt(KW_CONSTRUCTOR_ARGS), i);
            List<MethodInjection> methodInjections = parseMethods(bean.valAt(KW_METHODS), i);

            defs.add(new BeanDefinition(name, implClass, scope, ctorArgs, methodInjections));
        }

        return List.copyOf(defs);
    }

    // -----------------------------
    // Parse: constructor-args
    // -----------------------------

    private List<MethodArg> parseConstructorArgs(Object obj, int beanIndex) {
        if (obj == null) return List.of();

        String baseCtx = ctx(beanIndex, ":constructor-args");
        IPersistentVector vec = requireVector(obj, baseCtx);

        Set<Integer> usedIndexes = new HashSet<>();
        List<MethodArg> out = new ArrayList<>(vec.count());

        for (int i = 0; i < vec.count(); i++) {
            String argCtx = baseCtx + "[" + i + "]";
            IPersistentMap argMap = requireMap(vec.nth(i), argCtx);

            requirePresent(argMap, KW_INDEX, argCtx);
            requirePresent(argMap, KW_ARG, argCtx);

            int index = requireInt(argMap.valAt(KW_INDEX), ctx(beanIndex, "ctor-arg[" + i + "].:index"));
            if (index < 0) {
                throw new EdnValidationException(ctx(beanIndex, "ctor-arg[" + i + "].:index")
                        + ": index must be >= 0, got: " + index);
            }
            if (!usedIndexes.add(index)) {
                throw new EdnValidationException(baseCtx + ": duplicate :index " + index);
            }

            Object form = argMap.valAt(KW_ARG);
            BeanValue value = parseArgForm(form, ctx(beanIndex, "ctor-arg[" + i + "].:arg"));
            out.add(new MethodArg(index, value));
        }

        return List.copyOf(out);
    }

    // -----------------------------
    // Parse: methods
    // -----------------------------

    private List<MethodInjection> parseMethods(Object obj, int beanIndex) {
        if (obj == null) return List.of();

        String baseCtx = ctx(beanIndex, ":methods");
        IPersistentVector vec = requireVector(obj, baseCtx);

        List<MethodInjection> out = new ArrayList<>(vec.count());
        for (int i = 0; i < vec.count(); i++) {
            String mCtx = baseCtx + "[" + i + "]";
            IPersistentMap m = requireMap(vec.nth(i), mCtx);

            requirePresent(m, KW_METHOD_NAME, mCtx);

            String methodName = requireString(m.valAt(KW_METHOD_NAME), ctx(beanIndex, "method[" + i + "].:name"));

            Object argsObj = m.valAt(KW_METHOD_ARGS);
            IPersistentVector argsVec = argsObj == null ? emptyVector() : requireVector(argsObj, ctx(beanIndex, "method[" + i + "].:args"));

            List<MethodArg> args = new ArrayList<>(argsVec.count());
            Set<Integer> usedIndexes = new HashSet<>();

            for (int j = 0; j < argsVec.count(); j++) {
                String aCtx = ctx(beanIndex, "method[" + i + "].:args[" + j + "]");
                IPersistentMap argMap = requireMap(argsVec.nth(j), aCtx);

                requirePresent(argMap, KW_INDEX, aCtx);
                requirePresent(argMap, KW_ARG, aCtx);

                int index = requireInt(argMap.valAt(KW_INDEX), ctx(beanIndex, "method[" + i + "]-arg[" + j + "].:index"));
                if (index < 0) {
                    throw new EdnValidationException(ctx(beanIndex, "method[" + i + "]-arg[" + j + "].:index")
                            + ": index must be >= 0, got: " + index);
                }
                if (!usedIndexes.add(index)) {
                    throw new EdnValidationException(ctx(beanIndex, "method[" + i + "].:args")
                            + ": duplicate :index " + index + " for method '" + methodName + "'");
                }

                Object form = argMap.valAt(KW_ARG);
                BeanValue value = parseArgForm(form, ctx(beanIndex, "method[" + i + "]-arg[" + j + "].:arg"));
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
            throw new EdnValidationException(ctx + ": (" + op + " ...) expects exactly 1 argument");
        }

        Object arg1 = tail.first();
        if (tail.next() != null) {
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

                // предотвращаем тихое перетирание после нормализации ключа (например :a и "a")
                if (entries.containsKey(key)) {
                    throw new EdnValidationException(ctx + ": duplicate map key after normalization: " + key);
                }

                entries.put(key, parseValueElement(valObj, ctx + "[value-map:" + key + "]"));
            }

            return new MapValue(Map.copyOf(entries));
        }

        // scalar literal
        return new LiteralValue(arg);
    }

    private BeanValue parseValueElement(Object el, String ctx) {
        // treat ONLY lists as (ref ...)/(value ...) forms
        if (el instanceof ISeq) {
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

    private static void requirePresent(IPersistentMap m, Keyword key, String ctx) {
        if (m.valAt(key) == null) {
            throw new EdnValidationException(ctx + ": missing required key :" + key.getName());
        }
    }

    private static void validateNoUnknownBeanKeys(IPersistentMap bean, String ctx) {
        for (ISeq s = bean.seq(); s != null; s = s.next()) {
            MapEntry e = (MapEntry) s.first();
            Object keyObj = e.key();

            if (!(keyObj instanceof Keyword k)) {
                throw new EdnValidationException(ctx + ": key must be keyword, got: " + typeName(keyObj));
            }
            if (!ALLOWED_BEAN_KEYS.contains(k)) {
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
