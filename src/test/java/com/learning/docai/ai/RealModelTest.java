package com.learning.docai.ai;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
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
 * <p>The lifecycle is per class so that one document costs one model call however many assertions
 * are made about it. A test class therefore describes a single response, which is also what the
 * assertions about two tables not being merged, or a score staying with its own row, actually mean.
 *
 * <p>They assume the fixtures are reasonably fresh. Every date in them is written relative to the
 * day of generation, so once {@code arbeitsunfaehigVon} or {@code datumVon} is older than
 * {@code docai.validation.max-days-in-past} the assertions on an empty `probleme` list start
 * failing on {@code DATUM_ALT} - a fault of the fixture, not of the model.
 * {@code ./gradlew generateFixtures} is the fix. Endpoints 1 and 4 are unaffected: neither runs a
 * date through {@code SharedRules.datumsgrenzen}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Tag("llm")
@SpringBootTest
@TestInstance(Lifecycle.PER_CLASS)
@ActiveProfiles({ "local", "anthropic" })
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
public @interface RealModelTest {
}
