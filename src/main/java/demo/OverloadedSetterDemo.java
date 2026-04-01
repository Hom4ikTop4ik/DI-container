package demo;

public final class OverloadedSetterDemo {
    private Object x;

    public void setX(int x) {
        this.x = x;
    }

    public void setX(String x) { // same name + same arity => ambiguous in your container rules
        this.x = x;
    }

    @Override
    public String toString() {
        return "OverloadedSetterDemo{x=" + x + "}";
    }
}
