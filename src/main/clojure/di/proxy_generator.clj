(ns di.proxy-generator
  (:require [clojure.string :as str])
  (:import (java.lang.reflect Method)))

;; ---------------------------
;; Type name helpers
;; ---------------------------

(defn- java-type-name
  "Type name for method params/return types.
   Uses JVM name for nested types (Outer$Inner) which is fine for those positions,
   and supports primitives + arrays."
  [^Class c]
  (cond
    (.isArray c) (str (java-type-name (.getComponentType c)) "[]")
    :else (.getName c)))

(defn- iface-type-name
  "Type name for referencing the interface itself in Java source:
   MUST use canonical name (Outer.Inner), otherwise javac can't resolve nested types
   written as Outer$Inner in source."
  [^Class c]
  (or (.getCanonicalName c) (.getName c)))

;; ---------------------------
;; Method generation
;; ---------------------------

(defn- method->java [^Class iface ^Method m]
  (let [ret (.getReturnType m)
        name (.getName m)
        ptypes (.getParameterTypes m)
        etypes (.getExceptionTypes m)

        params (map-indexed (fn [i ^Class t] {:t t :n (str "arg" i)}) ptypes)

        decl-args (str/join ", "
                            (map (fn [{:keys [t n]}]
                                   (str (java-type-name t) " " n))
                                 params))
        call-args (str/join ", " (map :n params))

        throws (when (pos? (alength etypes))
                 (str " throws " (str/join ", " (map java-type-name etypes))))

        ;; IMPORTANT: cast must wrap the whole getBean(...) expression
        target-expr (str "((" (iface-type-name iface) ") container.getBean(beanName))")

        body (if (= ret Void/TYPE)
               (str "    " target-expr "." name "(" call-args ");\n")
               (str "    return " target-expr "." name "(" call-args ");\n"))]
    (str "  @Override\n"
         "  public " (java-type-name ret) " " name "(" decl-args ")" throws " {\n"
         body
         "  }\n")))

;; ---------------------------
;; Public API
;; ---------------------------

(defn generate-java-proxy-source
  "Generates Java source for a thread-scoped proxy class.

   iface-class: java.lang.Class (interface to implement)
   proxy-fqcn: full class name to generate (e.g. \"di.generated.X\")
  "
  [^Class iface-class ^String proxy-fqcn]
  (let [last-dot (.lastIndexOf proxy-fqcn ".")
        pkg (when (>= last-dot 0) (.substring proxy-fqcn 0 last-dot))
        cls (if (>= last-dot 0) (.substring proxy-fqcn (inc last-dot)) proxy-fqcn)

        methods (->> (.getMethods iface-class)
                     ;; exclude Object methods
                     (filter (fn [^Method m]
                               (not= (.getDeclaringClass m) Object)))
                     (map (partial method->java iface-class))
                     (apply str))]
    (str
     (when pkg (str "package " pkg ";\n\n"))
     "import di.api.DiContainer;\n\n"
     "public final class " cls " implements " (iface-type-name iface-class) " {\n"
     "  private final DiContainer container;\n"
     "  private final String beanName;\n\n"
     "  public " cls "(DiContainer container, String beanName) {\n"
     "    this.container = container;\n"
     "    this.beanName = beanName;\n"
     "  }\n\n"
     methods
     "}\n")))
