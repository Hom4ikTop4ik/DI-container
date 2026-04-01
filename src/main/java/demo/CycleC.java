package demo;

import javax.inject.Inject;

public final class CycleC {
    @Inject private CycleA a;
}
