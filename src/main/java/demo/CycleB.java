package demo;

import javax.inject.Inject;

public final class CycleB {
    @Inject private CycleC c;
}
