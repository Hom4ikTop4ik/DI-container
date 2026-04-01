package demo;

import java.util.List;
import java.util.Map;

public final class ConfigCollectionsDemo {
    private final Map<String, Object> config;

    public ConfigCollectionsDemo(Object config) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) config;
        this.config = m;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("ConfigCollectionsDemo{\n");
        sb.append("  keys=").append(config.keySet()).append("\n");
        sb.append("  title=").append(config.get("title")).append("\n");

        Object numbers = config.get("numbers");
        sb.append("  numbersType=").append(numbers == null ? "null" : numbers.getClass().getName()).append("\n");
        sb.append("  numbers=").append(numbers).append("\n");

        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) config.get("nested");
        sb.append("  nested.keys=").append(nested.keySet()).append("\n");

        Object k2 = nested.get("k2");
        sb.append("  nested.k2=").append(k2).append("\n");

        if (k2 instanceof List<?> list) {
            sb.append("  nested.k2.size=").append(list.size()).append("\n");
            sb.append("  nested.k2[2].class=").append(list.get(2) == null ? "null" : list.get(2).getClass().getName()).append("\n");
        }

        sb.append("}");
        return sb.toString();
    }
}
