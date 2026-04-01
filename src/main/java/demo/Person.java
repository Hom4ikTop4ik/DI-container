package demo;

public final class Person {
    private final String name;
    private final int age;
    private boolean active;
    private long score;

    // Config ctor injection: (String, int) + преобразование "30" -> int
    public Person(String name, int age) {
        this.name = name;
        this.age = age;
    }

    // Config method injection
    public void setActive(boolean active) {
        this.active = active;
    }

    public void setScore(long score) {
        this.score = score;
    }

    @Override
    public String toString() {
        return "Person{name='%s', age=%d, active=%s, score=%d}".formatted(name, age, active, score);
    }
}
