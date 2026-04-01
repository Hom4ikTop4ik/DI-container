package demo;

import java.util.UUID;

public final class PrototypeMessage {
    private final String id = UUID.randomUUID().toString();

    @Override
    public String toString() {
        return "PrototypeMessage{id='%s'}".formatted(id);
    }
}
