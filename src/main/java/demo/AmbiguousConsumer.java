package demo;

import javax.inject.Inject;

public final class AmbiguousConsumer {
    @Inject
    // без @Named -> ошибка "ambiguous" — 2+ классов на выьор (enGreeter + ruGreeter)
    private Greeter greeter;

    public String run() {
        return greeter.greet("X");
    }
}
