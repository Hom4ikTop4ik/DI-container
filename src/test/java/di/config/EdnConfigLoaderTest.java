package di.config;

import di.model.BeanDefinition;
import di.model.BeanValue;
import di.model.ListValue;
import di.model.MapValue;
import di.model.RefValue;
import di.model.Scope;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class EdnConfigLoaderTest {

    @Test
    void givenExistingResource_whenLoadFromResource_thenOk() {
        List<BeanDefinition> defs = new EdnConfigLoader().loadFromResource("di.edn");
        assertFalse(defs.isEmpty());
        assertTrue(defs.stream().anyMatch(d -> d.name().equals("fooSingleton")));
    }

    @Test
    void givenMissingResource_whenLoadFromResource_thenThrows() {
        EdnValidationException ex = assertThrows(
                EdnValidationException.class,
                () -> new EdnConfigLoader().loadFromResource("no-such-resource.edn")
        );
        assertTrue(ex.getMessage().contains("EDN resource not found"));
    }

    @Test
    void givenValidMinimalEdn_whenLoad_thenParsesBean() {
        String edn = """
                {:beans
                 [{:name "a" :class "demo.Foo" :scope :singleton}]}
                """;
        List<BeanDefinition> defs = new EdnConfigLoader().load(new StringReader(edn));
        assertEquals(1, defs.size());
        assertEquals("a", defs.getFirst().name());
        assertEquals(Scope.SINGLETON, defs.getFirst().scope());
        assertEquals("demo.Foo", defs.getFirst().implClass().getName());
    }

    @Test
    void givenMissingBeansAtRoot_whenLoad_thenThrows() {
        String edn = "{:x 1}";
        EdnValidationException ex = assertThrows(
                EdnValidationException.class,
                () -> new EdnConfigLoader().load(new StringReader(edn))
        );
        assertTrue(ex.getMessage().contains("Missing required key :beans at root"));
    }

    @Test
    void givenUnknownKeyInBean_whenLoad_thenThrows() {
        String edn = """
                {:beans
                 [{:name "a" :class "demo.Foo" :scope :singleton :lol 1}]}
                """;
        EdnValidationException ex = assertThrows(
                EdnValidationException.class,
                () -> new EdnConfigLoader().load(new StringReader(edn))
        );
        assertTrue(ex.getMessage().contains("unknown key"));
        assertTrue(ex.getMessage().contains(":lol"));
    }

    @Test
    void givenDuplicateBeanNames_whenLoad_thenThrows() {
        String edn = """
                {:beans
                 [{:name "a" :class "demo.Foo" :scope :singleton}
                  {:name "a" :class "demo.Foo" :scope :singleton}]}
                """;
        EdnValidationException ex = assertThrows(
                EdnValidationException.class,
                () -> new EdnConfigLoader().load(new StringReader(edn))
        );
        assertTrue(ex.getMessage().contains("Duplicate bean name: a"));
    }

    @Test
    void givenClassNotFound_whenLoad_thenThrows() {
        String edn = """
                {:beans
                 [{:name "a" :class "no.such.Clazz" :scope :singleton}]}
                """;
        EdnValidationException ex = assertThrows(
                EdnValidationException.class,
                () -> new EdnConfigLoader().load(new StringReader(edn))
        );
        assertTrue(ex.getMessage().contains("Class not found for bean 'a'"));
    }

    @Test
    void givenUnsupportedScope_whenLoad_thenThrows() {
        String edn = """
                {:beans
                 [{:name "a" :class "demo.Foo" :scope :scoped}]}
                """;
        EdnValidationException ex = assertThrows(
                EdnValidationException.class,
                () -> new EdnConfigLoader().load(new StringReader(edn))
        );
        assertTrue(ex.getMessage().contains("unsupported scope"));
    }

    @Test
    void givenBadRefForm_whenLoad_thenThrows() {
        // Аналогично src/main/resources/di-bad-form.edn
        String edn = """
                {:beans
                 [{:name "bad" :class "demo.Foo" :scope :singleton
                   :constructor-args [{:index 0 :arg (ref 123)}]}]}
                """;
        EdnValidationException ex = assertThrows(
                EdnValidationException.class,
                () -> new EdnConfigLoader().load(new StringReader(edn))
        );
        assertTrue(ex.getMessage().contains("(ref ...) expects string bean name"));
    }

    @Test
    void givenWrongArityValueForm_whenLoad_thenThrows() {
        String edn = """
                {:beans
                 [{:name "a" :class "demo.Foo" :scope :singleton
                   :constructor-args [{:index 0 :arg (value 1 2)}]}]}
                """;
        EdnValidationException ex = assertThrows(
                EdnValidationException.class,
                () -> new EdnConfigLoader().load(new StringReader(edn))
        );
        assertTrue(ex.getMessage().contains("expects exactly 1 argument"));
    }

    @Test
    void givenValueMapWithNonStringOrKeywordKey_whenLoad_thenThrows() {
        String edn = """
                {:beans
                 [{:name "a" :class "demo.ConfigCollectionsDemo" :scope :singleton
                   :constructor-args
                   [{:index 0 :arg (value {1 "x"})}]}]}
                """;
        EdnValidationException ex = assertThrows(
                EdnValidationException.class,
                () -> new EdnConfigLoader().load(new StringReader(edn))
        );
        assertTrue(ex.getMessage().contains("map key must be string or keyword"));
    }

    @Test
    void givenValueMapWithDuplicateAfterNormalization_whenLoad_thenThrows() {
        String edn = """
                {:beans
                 [{:name "a" :class "demo.ConfigCollectionsDemo" :scope :singleton
                   :constructor-args
                   [{:index 0 :arg (value {:a 1 "a" 2})}]}]}
                """;
        EdnValidationException ex = assertThrows(
                EdnValidationException.class,
                () -> new EdnConfigLoader().load(new StringReader(edn))
        );
        assertTrue(ex.getMessage().contains("duplicate map key after normalization"));
    }

    @Test
    void givenCtorArgsNegativeIndex_whenLoad_thenThrows() {
        String edn = """
                {:beans
                 [{:name "a" :class "demo.Foo" :scope :singleton
                   :constructor-args [{:index -1 :arg (value 1)}]}]}
                """;
        EdnValidationException ex = assertThrows(
                EdnValidationException.class,
                () -> new EdnConfigLoader().load(new StringReader(edn))
        );
        assertTrue(ex.getMessage().contains("index must be >= 0"));
    }

    @Test
    void givenCtorArgsDuplicateIndex_whenLoad_thenThrows() {
        String edn = """
                {:beans
                 [{:name "a" :class "demo.Person" :scope :prototype
                   :constructor-args
                   [{:index 0 :arg (value "A")}
                    {:index 0 :arg (value "B")}]}]}
                """;
        EdnValidationException ex = assertThrows(
                EdnValidationException.class,
                () -> new EdnConfigLoader().load(new StringReader(edn))
        );
        assertTrue(ex.getMessage().contains("duplicate :index"));
    }

    @Test
    void givenMethodsDuplicateArgIndex_whenLoad_thenThrows() {
        String edn = """
                {:beans
                 [{:name "a" :class "demo.BadConversionTarget" :scope :singleton
                   :methods
                   [{:name "setAge" :args [{:index 0 :arg (value 1)}
                                           {:index 0 :arg (value 2)}]}]}]}
                """;
        EdnValidationException ex = assertThrows(
                EdnValidationException.class,
                () -> new EdnConfigLoader().load(new StringReader(edn))
        );
        assertTrue(ex.getMessage().contains("duplicate :index"));
        assertTrue(ex.getMessage().contains("setAge"));
    }

    @Test
    void givenNestedValueVectorAndRef_whenLoad_thenBuildsBeanValueTree() {
        String edn = """
                {:beans
                 [{:name "foo" :class "demo.Foo" :scope :singleton}
                  {:name "a" :class "demo.ConfigCollectionsDemo" :scope :singleton
                   :constructor-args
                   [{:index 0
                     :arg (value {:title "T"
                                  :arr (value [1 2 (ref "foo")])})}]}]}
                """;

        List<BeanDefinition> defs = new EdnConfigLoader().load(new StringReader(edn));
        BeanDefinition a = defs.stream().filter(d -> d.name().equals("a")).findFirst().orElseThrow();

        BeanValue root = a.constructorArgs().getFirst().value();
        assertTrue(root instanceof MapValue);

        MapValue mv = (MapValue) root;
        assertEquals("T", ((di.model.LiteralValue) mv.entries().get("title")).value());

        BeanValue arr = mv.entries().get("arr");
        assertTrue(arr instanceof ListValue);

        ListValue lv = (ListValue) arr;
        assertEquals(3, lv.elements().size());
        assertTrue(lv.elements().get(2) instanceof RefValue);
        assertEquals("foo", ((RefValue) lv.elements().get(2)).beanName());
    }
}
