package di.proxy;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

final class ClasspathBuilder {

    private ClasspathBuilder() {}

    static String build(ClassLoader cl, Class<?>... anchors) {
        Objects.requireNonNull(cl, "cl");
        Set<String> entries = new LinkedHashSet<>();

        // 1) Try URLs from classloaders (if available)
        for (ClassLoader cur = cl; cur != null; cur = cur.getParent()) {
            if (cur instanceof URLClassLoader ucl) {
                for (URL url : ucl.getURLs()) {
                    String p = urlToPath(url);
                    if (p != null && !p.isBlank()) entries.add(p);
                }
            }
        }

        // 2) Add CodeSource locations of anchor classes
        if (anchors != null) {
            for (Class<?> a : anchors) {
                addCodeSource(entries, a);
            }
        }

        // 3) Fallback: java.class.path
        String jcp = System.getProperty("java.class.path", "");
        if (!jcp.isBlank()) {
            for (String e : jcp.split(File.pathSeparator)) {
                e = e.trim();
                if (!e.isBlank()) entries.add(e);
            }
        }

        return String.join(File.pathSeparator, entries);
    }

    private static void addCodeSource(Set<String> out, Class<?> c) {
        if (c == null) return;
        try {
            CodeSource cs = c.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return;
            String p = urlToPath(cs.getLocation());
            if (p != null && !p.isBlank()) out.add(p);
        } catch (SecurityException ignored) {
        }
    }

    private static String urlToPath(URL url) {
        try {
            if (url == null) return null;
            if (!"file".equalsIgnoreCase(url.getProtocol())) return null;
            return new File(url.toURI()).getPath();
        } catch (Exception e) {
            return null;
        }
    }
}
