package demo;

import di.config.EdnConfigLoader;
import di.core.SimpleDiContainer;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class Main {

    // Keep this list in sync with scenarioToResource()
    private static final String[] ALL_SCENARIOS = {
            "di",
            "not-found",
            "ambiguous",
            "cycle",
            "bad-form",
            "bad-conversion",
            "overload",
            "thread-proxy-bad",
            "thread-proxy-good"
    };

    public static void main(String[] args) {
        Cli cli = Cli.parse(args);

        String scenario = cli.scenario();

        // Special mode: run everything
        if ("all".equalsIgnoreCase(scenario)) {
            runAll(cli.stacktrace());
            return;
        }

        String resource = scenarioToResource(scenario);

        header(scenario, resource);

        try {
            var defs = new EdnConfigLoader().loadFromResource(resource);
            var container = new SimpleDiContainer(defs);

            // Fail-fast
            try {
                container.validate();
                System.out.println("[validate] OK");
            } catch (Throwable t) {
                System.out.println("[validate] FAILED");
                throw t;
            }

            // Run scenario
            switch (scenario) {
                case "di" -> runDi(container);
                case "not-found" -> runExpectingFailure(container, () -> container.getBean("broken"));
                case "ambiguous" -> runExpectingFailure(container, () -> container.getBean(AmbiguousConsumer.class));
                case "cycle" -> runExpectingFailure(container, () -> container.getBean(CycleA.class));
                case "bad-form" -> {
                    // should fail earlier at load(), so if we are here — it's unexpected
                    System.out.println("UNEXPECTED: bad-form loaded successfully");
                }
                case "bad-conversion" -> runExpectingFailure(container, () -> container.getBean("badConversion"));
                case "overload" -> runExpectingFailure(container, () -> container.getBean("overload"));
                case "thread-proxy-bad" ->
                        runExpectingFailure(container, () -> container.getBean(ThreadProxyBadConsumer.class));
                case "thread-proxy-good" -> runThreadProxyGood(container);
                default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
            }

            footerOk();
        } catch (Throwable t) {
            printError(t, cli.stacktrace());
            footerFail();
            System.exit(2);
        }
    }

    private static void runAll(boolean stacktrace) {
        System.out.println("========================================");
        System.out.println("DI-container demo (ALL scenarios)");
        System.out.println("timestamp : " + Instant.now());
        System.out.println("========================================");

        int ok = 0;
        int failed = 0;

        for (String scenario : ALL_SCENARIOS) {
            System.out.println("\n\n\n\n");

            String resource;
            try {
                resource = scenarioToResource(scenario);
            } catch (Throwable t) {
                System.out.println();
                System.out.println("=== SCENARIO: " + scenario + " ===");
                System.out.println("Cannot map scenario to resource: " + t.getMessage());
                failed++;
                continue;
            }

            System.out.println();
            System.out.println("========================================");
            System.out.println("SCENARIO : " + scenario);
            System.out.println("RESOURCE : " + resource);
            System.out.println("========================================");

            try {
                var defs = new EdnConfigLoader().loadFromResource(resource);
                var container = new SimpleDiContainer(defs);

                try {
                    container.validate();
                    System.out.println("[validate] OK");
                } catch (Throwable t) {
                    System.out.println("[validate] FAILED");
                    throw t;
                }

                // Run the same logic as in main()
                switch (scenario) {
                    case "di" -> runDi(container);
                    case "not-found" -> runExpectingFailure(container, () -> container.getBean("broken"));
                    case "ambiguous" -> runExpectingFailure(container, () -> container.getBean(AmbiguousConsumer.class));
                    case "cycle" -> runExpectingFailure(container, () -> container.getBean(CycleA.class));
                    case "bad-form" -> System.out.println("UNEXPECTED: bad-form loaded successfully");
                    case "bad-conversion" -> runExpectingFailure(container, () -> container.getBean("badConversion"));
                    case "overload" -> runExpectingFailure(container, () -> container.getBean("overload"));
                    case "thread-proxy-bad" ->
                            runExpectingFailure(container, () -> container.getBean(ThreadProxyBadConsumer.class));
                    case "thread-proxy-good" -> runThreadProxyGood(container);
                    default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
                }

                System.out.println("SCENARIO RESULT: OK");
                ok++;
            } catch (Throwable t) {
                printError(t, stacktrace);
                System.out.println("SCENARIO RESULT: FAILED");
                failed++;
            }
        }

        System.out.println();
        System.out.println("========================================");
        System.out.println("ALL DONE");
        System.out.println("OK     : " + ok);
        System.out.println("FAILED : " + failed);
        System.out.println("========================================");

        // Optional: non-zero exit code if something failed
        if (failed > 0) {
            System.exit(2);
        }
    }

    private static void runDi(SimpleDiContainer container) throws Exception {
        System.out.println();
        System.out.println("== Scopes ==");
        Object s1 = container.getBean("fooSingleton");
        Object s2 = container.getBean("fooSingleton");
        System.out.println("singleton same (==): " + (s1 == s2));

        Object p1 = container.getBean("fooPrototype");
        Object p2 = container.getBean("fooPrototype");
        System.out.println("prototype same (==): " + (p1 == p2));

        Object t1 = container.getBean("fooThread");
        final Object[] t2 = new Object[1];
        Thread th = new Thread(() -> t2[0] = container.getBean("fooThread"), "demo-thread-1");
        th.start();
        th.join();
        System.out.println("thread same (==): " + (t1 == t2[0]) + " (expected false)");

        System.out.println();
        System.out.println("== Config-based injection (ctor + methods, conversions) ==");
        Person person = container.getBean(Person.class);
        System.out.println("person: " + person);

        System.out.println();
        System.out.println("== Collections + nested values from config ==");
        ConfigCollectionsDemo collectionsDemo = container.getBean(ConfigCollectionsDemo.class);
        System.out.println(collectionsDemo.describe());

        System.out.println();
        System.out.println("== DI annotations: @Inject field + @Named + scoped proxy (singleton -> thread) ==");

        // We'll capture request IDs from two threads and verify they differ.
        final AtomicReference<String> idA = new AtomicReference<>();
        final AtomicReference<String> idB = new AtomicReference<>();

        WelcomeService welcome = container.getBean(WelcomeService.class);

        System.out.println("expected: request IDs in different threads should be different");

        Runnable rA = () -> {
            WelcomeService ws = container.getBean(WelcomeService.class);
            String threadName = Thread.currentThread().getName();
            System.out.println(threadName + ": welcomeService same instance (==): " + (ws == welcome));
            String id = ws.sayHelloAndReturnRequestId("Nikita");
            idA.set(id);
        };

        Runnable rB = () -> {
            WelcomeService ws = container.getBean(WelcomeService.class);
            String threadName = Thread.currentThread().getName();
            System.out.println(threadName + ": welcomeService same instance (==): " + (ws == welcome));
            String id = ws.sayHelloAndReturnRequestId("Nikita");
            idB.set(id);
        };

        Thread tA = new Thread(rA, "worker-A");
        Thread tB = new Thread(rB, "worker-B");
        tA.start();
        tB.start();
        tA.join();
        tB.join();

        boolean idsDifferent = idA.get() != null && idB.get() != null && !idA.get().equals(idB.get());
        System.out.println("idsDifferent=" + idsDifferent + " (idA=" + idA.get() + ", idB=" + idB.get() + ")");

        System.out.println();
        System.out.println("== Provider<T> for prototype ==");
        MessagePrinter printer = container.getBean(MessagePrinter.class);
        printer.printTwice();

        System.out.println();
        System.out.println("== Monitoring API ==");

        // show getBeanCount growth clearly
        String[] tracked = {"fooSingleton", "fooPrototype", "fooThread", "welcomeService"};
        System.out.println("-- getGetBeanCount(name) BEFORE extra calls");
        for (String n : tracked) {
            System.out.println("  " + n + ": " + container.getGetBeanCount(n));
        }

        // Do a few extra calls so counters change
        container.getBean("fooSingleton");
        container.getBean("fooPrototype");
        container.getBean("fooPrototype");
        container.getBean(WelcomeService.class).sayHello("CounterCheck");

        System.out.println("-- getGetBeanCount(name) AFTER extra calls");
        for (String n : tracked) {
            System.out.println("  " + n + ": " + container.getGetBeanCount(n));
        }

        System.out.println("-- listBeanDefinitions()");
        container.listBeanDefinitions().forEach(def -> System.out.println("  - " + def));

        System.out.println("-- isInstantiatedSingleton(name)");
        System.out.println("  fooSingleton: " + container.isInstantiatedSingleton("fooSingleton"));
        System.out.println("  enGreeter: " + container.isInstantiatedSingleton("enGreeter"));

        System.out.println("-- getDependencyGraph()");
        Map<String, java.util.List<String>> graph = container.getDependencyGraph();
        graph.forEach((bean, deps) -> System.out.println("  " + bean + " -> " + deps));
    }

    private static void runExpectingFailure(SimpleDiContainer container, Runnable action) {
        // For negative scenarios we still call validate() earlier (fail-fast), but also show runtime behavior if needed.
        action.run();
        System.out.println("UNEXPECTED: action completed successfully");
    }

    private static void runThreadProxyGood(SimpleDiContainer container) throws Exception {
        System.out.println();
        System.out.println("== thread-proxy-good (config-based ref) ==");

        ThreadProxyGoodConsumer s = container.getBean(ThreadProxyGoodConsumer.class);

        final AtomicReference<String> id1 = new AtomicReference<>();
        final AtomicReference<String> id2 = new AtomicReference<>();

        System.out.println("expected: same singleton, different requestId per thread");

        Runnable r1 = () -> {
            String threadName = Thread.currentThread().getName();
            ThreadProxyGoodConsumer ss = container.getBean(ThreadProxyGoodConsumer.class);
            System.out.println(threadName + ": same singleton instance (==): " + (ss == s));
            String id = ss.currentId();
            System.out.println(threadName + ": requestId=" + id);
            id1.set(id);
        };

        Runnable r2 = () -> {
            String threadName = Thread.currentThread().getName();
            ThreadProxyGoodConsumer ss = container.getBean(ThreadProxyGoodConsumer.class);
            System.out.println(threadName + ": same singleton instance (==): " + (ss == s));
            String id = ss.currentId();
            System.out.println(threadName + ": requestId=" + id);
            id2.set(id);
        };

        Thread t1 = new Thread(r1, "cfg-worker-1");
        Thread t2 = new Thread(r2, "cfg-worker-2");
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        boolean idsDifferent = id1.get() != null && id2.get() != null && !id1.get().equals(id2.get());
        System.out.println("idsDifferent=" + idsDifferent + " (id1=" + id1.get() + ", id2=" + id2.get() + ")");
    }

    private static void header(String scenario, String resource) {
        System.out.println("========================================");
        System.out.println("DI-container demo");
        System.out.println("timestamp : " + Instant.now());
        System.out.println("scenario  : " + scenario);
        System.out.println("resource  : " + resource);
        System.out.println("========================================");
    }

    private static void footerOk() {
        System.out.println();
        System.out.println("RESULT: OK");
    }

    private static void footerFail() {
        System.out.println();
        System.out.println("RESULT: FAILED");
    }

    private static void printError(Throwable t, boolean stacktrace) {
        System.out.println();
        System.out.println("== ERROR ==");
        System.out.println("type   : " + t.getClass().getName());
        System.out.println("message: " + t.getMessage());
        if (t.getCause() != null) {
            System.out.println("cause  : " + t.getCause().getClass().getName() + ": " + t.getCause().getMessage());
        }
        if (stacktrace) {
            System.out.println();
            t.printStackTrace(System.out);
        }
    }

    private static String scenarioToResource(String scenario) {
        scenario = scenario.toLowerCase(Locale.ROOT);
        return switch (scenario) {
            case "di" -> "di.edn";
            case "not-found" -> "di-not-found.edn";
            case "ambiguous" -> "di-ambiguous.edn";
            case "cycle" -> "di-cycle.edn";
            case "bad-form" -> "di-bad-form.edn";
            case "bad-conversion" -> "di-bad-conversion.edn";
            case "overload" -> "di-overload.edn";
            case "thread-proxy-bad" -> "di-thread-proxy-bad.edn";
            case "thread-proxy-good" -> "di-thread-proxy-good.edn";
            default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
        };
    }

    private record Cli(String scenario, boolean stacktrace) {
        static Cli parse(String[] args) {
            String scenario = args.length >= 1 ? args[0] : "all";
            boolean stacktrace = false;
            for (String a : args) {
                if ("--stacktrace".equals(a)) {
                    stacktrace = true;
                }
            }
            return new Cli(scenario, stacktrace);
        }
    }
}
