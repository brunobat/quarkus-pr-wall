package io.quarkus.prreview.classification.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.quarkus.prreview.classification.model.QuestionResult;

/**
 * GitHub's facts and {@code jev}'s answers are separate results: no GitHub record holds a {@code jev} answer type,
 * and no {@code jev} answer type holds a GitHub record, so neither can be turned into the other.
 */
class SourceSeparationTest {

    static final String GITHUB = "io.quarkus.prreview.classification.github";
    static final String JEV = "io.quarkus.prreview.classification.model";

    @Test
    void noGitHubRecordReferencesAJevAnswerType() throws IOException {
        List<Class<?>> records = records(GITHUB);
        assertTrue(records.size() >= 8, records.toString());
        for (Class<?> record : records) {
            for (Class<?> used : componentTypes(record)) {
                assertFalse(used.getName().startsWith(JEV + "."), record.getName() + " uses " + used.getName());
            }
        }
    }

    @Test
    void noJevAnswerTypeReferencesAGitHubRecord() throws IOException {
        List<Class<?>> records = records(JEV);
        assertTrue(records.contains(QuestionResult.class), records.toString());
        for (Class<?> record : records) {
            for (Class<?> used : componentTypes(record)) {
                assertFalse(used.getName().startsWith(GITHUB + "."), record.getName() + " uses " + used.getName());
            }
        }
    }

    @Test
    void aJevAnswerIsOnlyYesNoOrChoice() {
        assertEquals(Set.of(QuestionResult.YesNo.class, QuestionResult.Choice.class),
                Set.of(QuestionResult.Answer.class.getPermittedSubclasses()));
    }

    /** Every record, nested ones included, declared in the package or below it. */
    static List<Class<?>> records(String pkg) throws IOException {
        Path root = Path.of("src/main/java", pkg.replace('.', '/'));
        List<Class<?>> records = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = root.relativize(file).toString().replace('/', '.').replaceAll("\\.java$", "");
                collect(load(pkg + "." + name), records);
            }
        }
        return records;
    }

    private static void collect(Class<?> type, List<Class<?>> records) {
        if (type.isRecord()) {
            records.add(type);
        }
        Arrays.stream(type.getDeclaredClasses()).forEach(nested -> collect(nested, records));
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    /** The classes a record's components name, type arguments included. */
    static Set<Class<?>> componentTypes(Class<?> record) {
        Set<Class<?>> types = new LinkedHashSet<>();
        for (RecordComponent component : record.getRecordComponents()) {
            add(component.getGenericType(), types);
        }
        return types;
    }

    private static void add(Type type, Set<Class<?>> types) {
        if (type instanceof Class<?> c) {
            types.add(c.isArray() ? c.getComponentType() : c);
        } else if (type instanceof ParameterizedType p) {
            add(p.getRawType(), types);
            Arrays.stream(p.getActualTypeArguments()).forEach(a -> add(a, types));
        }
    }
}
