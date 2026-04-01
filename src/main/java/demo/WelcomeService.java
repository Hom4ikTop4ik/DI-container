package demo;

import javax.inject.Inject;
import javax.inject.Named;

public final class WelcomeService {

    @Inject
    @Named("ruGreeter")
    private Greeter greeter; // field injection + qualifier

    private RequestIdProvider requestIdProvider;

    @Inject
    public void setRequestIdProvider(RequestIdProvider requestIdProvider) {
        // метод injection (setter-style)
        this.requestIdProvider = requestIdProvider;
    }

    public void sayHello(String user) {
        System.out.println("[" + requestIdProvider.currentRequestId() + "] " + greeter.greet(user));
    }

    public String sayHelloAndReturnRequestId(String user) {
        String id = requestIdProvider.currentRequestId();
        System.out.println("[" + id + "] " + greeter.greet(user));
        return id;
    }
}
