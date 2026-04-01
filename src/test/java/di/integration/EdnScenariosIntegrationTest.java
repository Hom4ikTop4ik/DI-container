package di.integration;

import di.config.EdnConfigLoader;
import di.core.SimpleDiContainer;
import demo.ConfigCollectionsDemo;
import demo.Person;
import demo.ThreadProxyBadConsumer;
import demo.ThreadProxyGoodConsumer;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class EdnScenariosIntegrationTest {

    private static SimpleDiContainer containerFrom(String resource) {
        var defs = new EdnConfigLoader().loadFromResource(resource);
        return new SimpleDiContainer(defs);
    }

    @Test
    void givenDiEdn_whenValidate_thenOk() {
        SimpleDiContainer c = containerFrom("di.edn");
        assertDoesNotThrow(c::validate);
    }

    @Test
    void givenDiEdn_whenScopes_thenSingletonCachedPrototypeNewThreadScopedPerThread() throws Exception {
        SimpleDiContainer c = containerFrom("di.edn");
        c.validate();

        Object s1 = c.getBean("fooSingleton");
        Object s2 = c.getBean("fooSingleton");
        assertSame(s1, s2);
        assertTrue(c.isInstantiatedSingleton("fooSingleton"));

        Object p1 = c.getBean("fooPrototype");
        Object p2 = c.getBean("fooPrototype");
        assertNotSame(p1, p2);

        // THREAD scope: same in same thread
        Object t1 = c.getBean("fooThread");
        Object t2 = c.getBean("fooThread");
        assertSame(t1, t2);

        // THREAD scope: different across threads (deterministic)
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Object> fA = pool.submit(() -> {
                barrier.await();
                return c.getBean("fooThread");
            });
            Future<Object> fB = pool.submit(() -> {
                barrier.await();
                return c.getBean("fooThread");
            });

            Object a = fA.get(3, TimeUnit.SECONDS);
            Object b = fB.get(3, TimeUnit.SECONDS);
            assertNotSame(a, b);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void givenDiEdn_whenConfigBasedInjection_thenPersonCreatedAndMethodsApplied() {
        SimpleDiContainer c = containerFrom("di.edn");
        c.validate();

        Person p = c.getBean(Person.class);

        // У Person нет геттеров — проверяем по toString() (стабильно: формат задан в коде)
        String s = p.toString();
        assertTrue(s.contains("name='Alice'"));
        assertTrue(s.contains("age=30"));
        assertTrue(s.contains("active=true"));
        assertTrue(s.contains("score=12"));
    }

    @Test
    void givenDiEdn_whenCollectionsDemo_thenNestedRefResolvedToRealBeanInstance() {
        SimpleDiContainer c = containerFrom("di.edn");
        c.validate();

        ConfigCollectionsDemo demo = c.getBean(ConfigCollectionsDemo.class);
        String description = demo.describe();

        // В конфиге вложено (ref "fooSingleton") на позиции nested.k2[2]
        assertTrue(description.contains("nested.k2.size=3"));
        // Проверяем что там реальный объект Foo (класс из demo)
        assertTrue(description.contains("nested.k2[2].class=demo.Foo"));
    }

    @Test
    void givenDiNotFoundEdn_whenValidate_thenThrowsNoSuchElement() {
        SimpleDiContainer c = containerFrom("di-not-found.edn");

        NoSuchElementException ex = assertThrows(NoSuchElementException.class, c::validate);

        assertTrue(ex.getMessage().contains("bean not found by name: noSuchBean"));
        assertTrue(ex.getMessage().contains("Bean 'broken': constructor-arg[0]"));
    }

    @Test
    void givenDiAmbiguousEdn_whenGetBeanAmbiguousConsumer_thenThrowsIllegalState() {
        SimpleDiContainer c = containerFrom("di-ambiguous.edn");
        c.validate();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> c.getBean(demo.AmbiguousConsumer.class));
        assertTrue(ex.getMessage().contains("Ambiguous beans for type"));
        assertTrue(ex.getMessage().contains("enGreeter"));
        assertTrue(ex.getMessage().contains("ruGreeter"));
    }

    @Test
    void givenDiOverloadEdn_whenValidate_thenThrowsIllegalState() {
        SimpleDiContainer c = containerFrom("di-overload.edn");

        IllegalStateException ex = assertThrows(IllegalStateException.class, c::validate);
        assertTrue(ex.getMessage().contains("Ambiguous setter overload"));
        assertTrue(ex.getMessage().contains("setX"));
    }

    @Test
    void givenDiBadConversionEdn_whenValidate_thenThrowsIllegalStateWithCause() {
        SimpleDiContainer c = containerFrom("di-bad-conversion.edn");

        IllegalStateException ex = assertThrows(IllegalStateException.class, c::validate);
        assertTrue(ex.getMessage().contains("cannot convert literal"));
        assertNotNull(ex.getCause());
    }

    @Test
    void givenDiCycleEdn_whenGetBean_thenThrowsIllegalStateWithCyclePath() {
        SimpleDiContainer c = containerFrom("di-cycle.edn");
        c.validate();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> c.getBean("cycleA"));
        assertTrue(ex.getMessage().contains("Cyclic dependency detected"));
        assertTrue(ex.getMessage().contains("cycleA"));
        assertTrue(ex.getMessage().contains("->"));
    }

    @Test
    void givenThreadProxyGoodEdn_whenValidateAndRunInTwoThreads_thenDifferentIds() throws Exception {
        SimpleDiContainer c = containerFrom("di-thread-proxy-good.edn");
        assertDoesNotThrow(c::validate);

        ThreadProxyGoodConsumer consumer = c.getBean(ThreadProxyGoodConsumer.class);

        AtomicReference<String> idA = new AtomicReference<>();
        AtomicReference<String> idB = new AtomicReference<>();

        Thread tA = new Thread(() -> idA.set(consumer.currentId()), "A");
        Thread tB = new Thread(() -> idB.set(consumer.currentId()), "B");

        tA.start(); tB.start();
        tA.join(); tB.join();

        assertNotNull(idA.get());
        assertNotNull(idB.get());
        assertNotEquals(idA.get(), idB.get(), "thread-scoped ids must differ across threads via proxy");
    }

    @Test
    void givenThreadProxyBadEdn_whenGetBean_thenThrowsIllegalStateProxyNotPossible() {
        SimpleDiContainer c = containerFrom("di-thread-proxy-bad.edn");
        c.validate(); // validate может пройти, ошибка ожидается при создании

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> c.getBean(ThreadProxyBadConsumer.class));
        assertTrue(ex.getMessage().contains("requires proxy"));
        assertTrue(ex.getMessage().contains("not an interface"));
    }
}
