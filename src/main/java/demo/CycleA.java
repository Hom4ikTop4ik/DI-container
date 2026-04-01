package demo;

import javax.inject.Inject;

public final class CycleA {
    @Inject private CycleB b;
}
