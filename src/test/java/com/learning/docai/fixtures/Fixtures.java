package com.learning.docai.fixtures;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Loads the sample documents of {@code src/test/resources/fixtures} as uploads, for the
 * real-model tests of SPEC §11. {@link FixtureGenerator} writes them; this reads them.
 *
 * <p>Read from the classpath rather than from a path, so the tests do not depend on the working
 * directory Gradle happens to run them in.
 */
public final class Fixtures {

    private Fixtures() {
    }

    public static MockMultipartFile pdf(String name) {
        return upload(name, MediaType.APPLICATION_PDF_VALUE);
    }

    public static MockMultipartFile png(String name) {
        return upload(name, MediaType.IMAGE_PNG_VALUE);
    }

    private static MockMultipartFile upload(String name, String contentType) {
        try (InputStream quelle = Fixtures.class.getClassLoader()
                .getResourceAsStream("fixtures/" + name)) {

            if (quelle == null) {
                throw new IllegalStateException("No fixture " + name
                        + " - run ./gradlew generateFixtures");
            }
            return new MockMultipartFile("file", name, contentType, quelle.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the fixture " + name, e);
        }
    }
}
