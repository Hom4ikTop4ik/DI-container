package demo;

import javax.inject.Inject;
import javax.inject.Provider;

public final class MessagePrinter {
    private final Provider<PrototypeMessage> messageProvider;

    @Inject
    // constructor injection
    public MessagePrinter(Provider<PrototypeMessage> messageProvider) {
        this.messageProvider = messageProvider;
    }

    public void printTwice() {
        System.out.println("msg1: " + messageProvider.get());
        System.out.println("msg2: " + messageProvider.get());
    }
}
