package demo;

import java.util.UUID;

public final class ThreadRequestIdProvider implements RequestIdProvider {
    private final String id = UUID.randomUUID().toString();

    @Override
    public String currentRequestId() {
        return id;
    }
}
