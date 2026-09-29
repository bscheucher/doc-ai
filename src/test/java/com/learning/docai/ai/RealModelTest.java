package com.learning.docai.ai;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Marks a test that really calls the model (SPEC §11, phase 7). Two locks, because these are the
 * only tests in the suite that cost money and depend on a service being up:
 *
 * <ul>
 *   <li>the JUnit tag {@code llm}, which build.gradle.kts excludes unless
 *       {@code -PincludeLlmTests} is given, so CI never runs them;</li>
 *   <li>an ANTHROPIC_API_KEY in the environment - without it the tests are skipped rather than
 *       failing on a context that cannot start.</li>
 * </ul>
 *
 * <p>Profiles `local` and `anthropic`: the model comes from application.yml as in production,
 * and `local` spares the tests a tenant they have no use for - nothing here goes over HTTP.
 *
 * <p>What these tests are for is the seam the mocked tests cannot reach: that a real model, given
 * a real rendered page, fills the right field with the right value. They therefore assert
 * structure and key values - a date in the right field, two times told apart, a score next to its
 * competency - and never the model's free text, which is allowed to vary.
 *
 * <p>They assume the fixtures are reasonably fresh. Every date in them is written relative to the
 * day of generation, so once they are older than {@code docai.validation.max-days-in-past} the
 * assertions on an empty `probleme` list start failing on DATUM_ZU_ALT - a fault of the fixture,
 * not of the model. {@code ./gradlew generateFixtures} is the fix.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Tag("llm")
@SpringBootTest
@ActiveProfiles({ "local", "anthropic" })
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
public @interface RealModelTest {
}
