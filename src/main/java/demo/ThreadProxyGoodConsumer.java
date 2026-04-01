package demo;

public final class ThreadProxyGoodConsumer {
    private final RequestIdProvider requestIdProvider;

    // ВАЖНО: тип параметра — интерфейс RequestIdProvider,
    // поэтому контейнер сможет создать scoped proxy при singleton -> thread.
    public ThreadProxyGoodConsumer(RequestIdProvider requestIdProvider) {
        this.requestIdProvider = requestIdProvider;
    }

    public String currentId() {
        return requestIdProvider.currentRequestId();
    }
}
