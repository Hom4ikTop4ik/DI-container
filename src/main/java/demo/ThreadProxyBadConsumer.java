package demo;

import javax.inject.Inject;

public final class ThreadProxyBadConsumer {
    @Inject
    // класс, не интерфейс -> невозможно созлать прокси
    private ThreadRequestIdProvider requestIdProvider;

    public String id() {
        return requestIdProvider.currentRequestId();
    }
}
