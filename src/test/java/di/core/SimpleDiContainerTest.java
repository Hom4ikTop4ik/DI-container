package di.core;

import di.model.*;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

final class SimpleDiContainerUnitTest {

    // -----------------------
    // Test helper classes
    // -----------------------

    interface IService { String name(); }

    static final class ServiceA implements IService {
        @Override public String name() { return "A"; }
    }

    static final class ServiceB implements IService {
        @Override public String name() { return "B"; }
    }

    static final class NeedsNamedService {
        final IService service;

        @Inject
        NeedsNamedService(@Named("serviceB") IService service) {
            this.service = service;
        }
    }

    static final class TwoInjectCtors {
        @Inject TwoInjectCtors() {}
        @Inject TwoInjectCtors(String x) {}
    }

    static final class TwoCtorsNoInject {
        TwoCtorsNoInject() {}
        TwoCtorsNoInject(String x) {}
    }

    static class BaseWithInjectField {
        @Inject IService baseService;
    }

    static final class ChildWithNoFields extends BaseWithInjectField { }

    static final class WithInjectMethod {
        IService s;

        @Inject
        public void set(IService s) { this.s = s; }
    }

    static final class ProviderGood {
        @Inject Provider<IService> provider;
    }

    static final class ProviderRaw {
        @SuppressWarnings("rawtypes")
        @Inject Provider provider;
    }

    static final class ProviderWildcard {
        @Inject Provider<?> provider;
    }

    static final class ThreadScopedDep { }

    static final class SingletonNeedsConcreteThreadDep {
        final ThreadScopedDep dep;
        // config ctor injection будет ref -> thread bean, expected type = ThreadScopedDep (НЕ интерфейс)
        SingletonNeedsConcreteThreadDep(ThreadScopedDep dep) { this.dep = dep; }
    }

    static final class DoubleInjectionTarget {
        @Inject
        public void setX(IService s) { }
    }

    // -----------------------
    // Basic getBean(Class/name)
    // -----------------------

    @Test
    void givenNoSuchBean_whenGetBeanByName_thenThrowsNoSuchElement() {
        var c = new SimpleDiContainer(List.of());
        NoSuchElementException ex = assertThrows(NoSuchElementException.class, () -> c.getBean("x"));
        assertTrue(ex.getMessage().contains("Bean not found by name: x"));
    }

    @Test
    void givenGetBeanByTypeWithNoCandidates_thenThrows() {
        var defs = List.of(new BeanDefinition("a", ServiceA.class, Scope.SINGLETON));
        var c = new SimpleDiContainer(defs);

        NoSuchElementException ex = assertThrows(NoSuchElementException.class, () -> c.getBean(ServiceB.class));
        assertTrue(ex.getMessage().contains("No beans found for type"));
    }

    @Test
    void givenGetBeanByTypeAmbiguous_thenThrowsIllegalState() {
        var defs = List.of(
                new BeanDefinition("a", ServiceA.class, Scope.SINGLETON),
                new BeanDefinition("b", ServiceB.class, Scope.SINGLETON)
        );
        var c = new SimpleDiContainer(defs);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> c.getBean(IService.class));
        assertTrue(ex.getMessage().contains("Ambiguous beans for type"));
        assertTrue(ex.getMessage().contains("a"));
        assertTrue(ex.getMessage().contains("b"));
    }

    @Test
    void givenGetBeanTypeNameMismatch_thenThrowsClassCast() {
        var defs = List.of(new BeanDefinition("a", ServiceA.class, Scope.SINGLETON));
        var c = new SimpleDiContainer(defs);

        ClassCastException ex = assertThrows(ClassCastException.class, () -> c.getBean(ServiceB.class, "a"));
        assertTrue(ex.getMessage().contains("not assignable"));
    }

    // -----------------------
    // Scopes
    // -----------------------

    @Test
    void givenSingleton_whenGetBeanTwice_thenSameAndIsInstantiatedSingletonTrue() {
        var defs = List.of(new BeanDefinition("a", ServiceA.class, Scope.SINGLETON));
        var c = new SimpleDiContainer(defs);

        assertFalse(c.isInstantiatedSingleton("a"));
        Object o1 = c.getBean("a");
        assertTrue(c.isInstantiatedSingleton("a"));
        Object o2 = c.getBean("a");
        assertSame(o1, o2);
    }

    @Test
    void givenPrototype_whenGetBeanTwice_thenDifferent() {
        var defs = List.of(new BeanDefinition("a", ServiceA.class, Scope.PROTOTYPE));
        var c = new SimpleDiContainer(defs);

        Object o1 = c.getBean("a");
        Object o2 = c.getBean("a");
        assertNotSame(o1, o2);
    }

    // -----------------------
    // validate(): index completeness
    // -----------------------

    @Test
    void givenMissingCtorIndex_whenValidate_thenThrows() {
        // Person ctor (String, int) needs indexes 0 and 1, we provide only 1 => validate should fail
        var def = new BeanDefinition(
                "p",
                demo.Person.class,
                Scope.PROTOTYPE,
                List.of(new MethodArg(1, new LiteralValue("30"))),
                List.of()
        );
        var c = new SimpleDiContainer(List.of(def));

        IllegalStateException ex = assertThrows(IllegalStateException.class, c::validate);
        assertTrue(ex.getMessage().contains("constructor args must define all indexes"));
        assertTrue(ex.getMessage().contains("missing index 0"));
    }

    // -----------------------
    // validate(): singleton -> thread via config-ref requires interface
    // -----------------------

    @Test
    void givenSingletonDependsOnThreadByConfigRefAndTypeNotInterface_whenValidate_thenThrows() {
        var threadDef = new BeanDefinition("t", ThreadScopedDep.class, Scope.THREAD);

        var singletonDef = new BeanDefinition(
                "s",
                SingletonNeedsConcreteThreadDep.class,
                Scope.SINGLETON,
                List.of(new MethodArg(0, new RefValue("t"))),
                List.of()
        );

        var c = new SimpleDiContainer(List.of(threadDef, singletonDef));

        IllegalStateException ex = assertThrows(IllegalStateException.class, c::validate);
        assertTrue(ex.getMessage().contains("requires proxy"));
        assertTrue(ex.getMessage().contains("not an interface"));
    }

    // -----------------------
    // @Inject ctor selection
    // -----------------------

    @Test
    void givenMultipleInjectCtors_whenValidate_thenThrows() {
        var defs = List.of(new BeanDefinition("x", TwoInjectCtors.class, Scope.SINGLETON));
        var c = new SimpleDiContainer(defs);

        IllegalStateException ex = assertThrows(IllegalStateException.class, c::validate);
        assertTrue(ex.getMessage().contains("Multiple @Inject constructors"));
    }

    @Test
    void givenMultipleCtorsNoInject_whenValidate_thenThrows() {
        var defs = List.of(new BeanDefinition("x", TwoCtorsNoInject.class, Scope.SINGLETON));
        var c = new SimpleDiContainer(defs);

        IllegalStateException ex = assertThrows(IllegalStateException.class, c::validate);
        assertTrue(ex.getMessage().contains("No @Inject constructor"));
        assertTrue(ex.getMessage().contains("multiple constructors"));
    }

    // -----------------------
    // @Inject field injection incl. superclass
    // -----------------------

    @Test
    void givenInjectFieldInSuperclass_whenGetBean_thenInjected() {
        var defs = List.of(
                new BeanDefinition("a", ServiceA.class, Scope.SINGLETON),
                new BeanDefinition("child", ChildWithNoFields.class, Scope.SINGLETON)
        );
        var c = new SimpleDiContainer(defs);

        ChildWithNoFields child = c.getBean(ChildWithNoFields.class);
        assertNotNull(child.baseService);
        assertEquals("A", child.baseService.name());
    }

    // -----------------------
    // @Inject method injection
    // -----------------------

    @Test
    void givenInjectMethod_whenGetBean_thenInjected() {
        var defs = List.of(
                new BeanDefinition("a", ServiceA.class, Scope.SINGLETON),
                new BeanDefinition("x", WithInjectMethod.class, Scope.SINGLETON)
        );
        var c = new SimpleDiContainer(defs);

        WithInjectMethod x = c.getBean(WithInjectMethod.class);
        assertNotNull(x.s);
        assertEquals("A", x.s.name());
    }

    // -----------------------
    // Double injection source: config method + @Inject method
    // -----------------------

    @Test
    void givenConfigMethodAndInjectMethodSameName_whenGetBean_thenThrows() {
        var defs = List.of(
                new BeanDefinition("a", ServiceA.class, Scope.SINGLETON),
                new BeanDefinition(
                        "x",
                        DoubleInjectionTarget.class,
                        Scope.SINGLETON,
                        List.of(),
                        List.of(
                                new MethodInjection("setX", List.of(new MethodArg(0, new RefValue("a"))))
                        )
                )
        );
        var c = new SimpleDiContainer(defs);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> c.getBean("x"));
        assertTrue(ex.getMessage().contains("Double injection source"));
        assertTrue(ex.getMessage().contains("setX"));
    }

    // -----------------------
    // Provider<T>
    // -----------------------

    @Test
    void givenProviderWithConcreteGeneric_whenGetBean_thenProviderWorks() {
        var defs = List.of(
                new BeanDefinition("a", ServiceA.class, Scope.PROTOTYPE),
                new BeanDefinition("p", ProviderGood.class, Scope.SINGLETON)
        );
        var c = new SimpleDiContainer(defs);

        ProviderGood p = c.getBean(ProviderGood.class);
        assertNotNull(p.provider);

        Object v1 = p.provider.get();
        Object v2 = p.provider.get();
        assertTrue(v1 instanceof IService);
        assertTrue(v2 instanceof IService);
        // ServiceA is prototype here => provider.get() should return different instances
        assertNotSame(v1, v2);
    }

    @Test
    void givenProviderRaw_whenGetBean_thenThrows() {
        var defs = List.of(
                new BeanDefinition("a", ServiceA.class, Scope.SINGLETON),
                new BeanDefinition("p", ProviderRaw.class, Scope.SINGLETON)
        );
        var c = new SimpleDiContainer(defs);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> c.getBean("p"));
        assertTrue(ex.getMessage().contains("Provider<T> must have concrete generic type parameter"));
    }

    @Test
    void givenProviderWildcard_whenGetBean_thenThrows() {
        var defs = List.of(
                new BeanDefinition("a", ServiceA.class, Scope.SINGLETON),
                new BeanDefinition("p", ProviderWildcard.class, Scope.SINGLETON)
        );
        var c = new SimpleDiContainer(defs);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> c.getBean("p"));
        assertTrue(ex.getMessage().contains("Provider<T> must have concrete generic type parameter"));
    }

    @Test
    void givenNamedProvider_whenGetBean_thenResolvesByName() {
        var defs = List.of(
                new BeanDefinition("serviceA", ServiceA.class, Scope.SINGLETON),
                new BeanDefinition("serviceB", ServiceB.class, Scope.SINGLETON),
                new BeanDefinition("x", NeedsNamedService.class, Scope.SINGLETON)
        );
        var c = new SimpleDiContainer(defs);

        NeedsNamedService x = c.getBean(NeedsNamedService.class);
        assertNotNull(x.service);
        assertEquals("B", x.service.name());
    }

    // -----------------------
    // getDependencyGraph(): must not crash on bad Provider signature (best-effort)
    // -----------------------

    @Test
    void givenBadProviderSignature_whenGetDependencyGraph_thenDoesNotThrow() {
        var defs = List.of(
                new BeanDefinition("a", ServiceA.class, Scope.SINGLETON),
                new BeanDefinition("p", ProviderRaw.class, Scope.SINGLETON)
        );
        var c = new SimpleDiContainer(defs);

        assertDoesNotThrow(c::getDependencyGraph);
        Map<String, List<String>> g = c.getDependencyGraph();
        assertTrue(g.containsKey("a"));
        assertTrue(g.containsKey("p"));
    }
}
