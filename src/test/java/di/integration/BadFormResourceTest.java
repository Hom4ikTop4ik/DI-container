package di.integration;

import di.config.EdnConfigLoader;
import di.config.EdnValidationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class BadFormResourceTest {

    @Test
    void givenBadFormResource_whenLoadFromResource_thenThrows() {
        EdnValidationException ex = assertThrows(
                EdnValidationException.class,
                () -> new EdnConfigLoader().loadFromResource("di-bad-form.edn")
        );
        assertTrue(ex.getMessage().contains("(ref ...) expects string bean name"));
    }
}
