package io.gateway.oss.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Architecture tests enforcing layering rules documented in AGENTS.md.
 *
 * <p>These tests run without Spring context (pure classpath scan).
 * They prevent architectural drift from AI-generated or human code.
 *
 * <p>These rules are hard gates. New controller/entity or controller/repository
 * shortcuts must fail immediately instead of being frozen as legacy exceptions.
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.gateway.oss.core");
    }

    // ── Layer 1: Controllers MUST NOT inject Repository directly ──

    @Test
    void controllersMustNotDependOnRepositories() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..web..")
                .should().dependOnClassesThat()
                .resideInAPackage("..repository..")
                .because("Controllers must use Service intermediaries, not Repository directly");
        rule.check(classes);
    }

    // ── Layer 2: DTOs MUST NOT contain Spring annotations ──

    @Test
    void dtoMustNotUseSpringAnnotations() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..dto..")
                .should().dependOnClassesThat()
                .resideInAPackage("org.springframework..")
                .because("DTOs should be pure data carriers with Jackson/Swagger only");
        rule.check(classes);
    }

    // ── Layer 3: Controllers MUST NOT import Entity classes ──

    @Test
    void controllersMustNotDependOnEntities() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..web..")
                .should().dependOnClassesThat()
                .resideInAPackage("..entity..")
                .because("Entity classes must not be returned directly from controllers");
        rule.check(classes);
    }

    // ── Layer 4: Cross-feature deps must go through Service ──

    @Test
    void noDirectCrossFeatureRepositoryAccess() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..web..")
                .should().accessClassesThat()
                .resideInAPackage("..repository..")
                .because("Cross-feature communication must go through Service interfaces");
        rule.check(classes);
    }
}
