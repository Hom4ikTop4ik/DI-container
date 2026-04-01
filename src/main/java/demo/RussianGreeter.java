package demo;

public final class RussianGreeter implements Greeter {
    @Override
    public String greet(String name) {
        return "Привет, " + name;
    }
}
