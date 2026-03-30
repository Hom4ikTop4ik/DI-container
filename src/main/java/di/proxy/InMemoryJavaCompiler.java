package di.proxy;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class InMemoryJavaCompiler {

    public Class<?> compile(String fqcn, String source, ClassLoader parent, String classpath) {
        Objects.requireNonNull(fqcn, "fqcn");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(classpath, "classpath");

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system JavaCompiler found. Run on JDK 21 (not JRE).");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        StandardJavaFileManager stdFileManager =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8);

        try (MemoryFileManager fileManager = new MemoryFileManager(stdFileManager)) {
            JavaFileObject sourceFile = new SourceJavaFileObject(fqcn, source);

            List<String> options = new ArrayList<>();
            options.add("--release");
            options.add("21");

            if (!classpath.isBlank()) {
                options.add("-classpath");
                options.add(classpath);
            }

            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    options,
                    null,
                    List.of(sourceFile)
            );

            Boolean ok = task.call();
            if (ok == null || !ok) {
                throw new IllegalStateException(buildCompilationErrorMessage(fqcn, source, classpath, diagnostics));
            }

            Map<String, byte[]> compiled = fileManager.getAllClassBytes();
            ByteArrayClassLoader loader = new ByteArrayClassLoader(parent, compiled);
            try {
                return loader.loadClass(fqcn);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Compilation succeeded but class was not found: " + fqcn, e);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to close file manager", e);
        }
    }

    private static String buildCompilationErrorMessage(String fqcn,
                                                       String source,
                                                       String classpath,
                                                       DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder sb = new StringBuilder();
        sb.append("Failed to compile generated proxy class: ").append(fqcn).append("\n");
        sb.append("Classpath: ").append(classpath).append("\n");

        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            sb.append("[").append(d.getKind()).append("] ");
            if (d.getSource() != null) {
                sb.append(d.getSource().getName()).append(":")
                        .append(d.getLineNumber()).append(":")
                        .append(d.getColumnNumber()).append(" ");
            }
            sb.append(d.getMessage(Locale.ROOT)).append("\n");
        }

        sb.append("\n--- Generated source ---\n");
        sb.append(source);
        sb.append("\n--- End generated source ---\n");

        return sb.toString();
    }

    private static final class SourceJavaFileObject extends SimpleJavaFileObject {
        private final String source;

        SourceJavaFileObject(String fqcn, String source) {
            super(uriForFqcn(fqcn, Kind.SOURCE), Kind.SOURCE);
            this.source = Objects.requireNonNull(source, "source");
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    private static final class MemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager>
            implements AutoCloseable {

        private final Map<String, ByteArrayJavaClassObject> compiled = new HashMap<>();

        MemoryFileManager(StandardJavaFileManager fileManager) {
            super(fileManager);
        }

        Map<String, byte[]> getAllClassBytes() {
            Map<String, byte[]> out = new HashMap<>();
            for (Map.Entry<String, ByteArrayJavaClassObject> e : compiled.entrySet()) {
                out.put(e.getKey(), e.getValue().getBytes());
            }
            return out;
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location,
                                                   String className,
                                                   JavaFileObject.Kind kind,
                                                   FileObject sibling) {
            ByteArrayJavaClassObject obj = new ByteArrayJavaClassObject(className, kind);
            compiled.put(className, obj);
            return obj;
        }

        @Override
        public void close() throws IOException {
            super.close();
        }
    }

    private static final class ByteArrayJavaClassObject extends SimpleJavaFileObject {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        ByteArrayJavaClassObject(String className, Kind kind) {
            super(uriForFqcn(className, kind), kind);
        }

        @Override
        public OutputStream openOutputStream() {
            return out;
        }

        byte[] getBytes() {
            return out.toByteArray();
        }
    }

    private static final class ByteArrayClassLoader extends ClassLoader {
        private final Map<String, byte[]> classBytes;

        ByteArrayClassLoader(ClassLoader parent, Map<String, byte[]> classBytes) {
            super(parent);
            this.classBytes = Map.copyOf(classBytes);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classBytes.get(name);
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static URI uriForFqcn(String fqcn, JavaFileObject.Kind kind) {
        String path = fqcn.replace('.', '/') + kind.extension;
        return URI.create("string:///" + path);
    }
}
