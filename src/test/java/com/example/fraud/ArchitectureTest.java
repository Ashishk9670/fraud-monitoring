package com.example.fraud;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Package-boundary rules for the application code (test classes are not analysed). */
@AnalyzeClasses(packages = "com.example.fraud", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    private static final String BASE = "com.example.fraud.";

    @ArchTest
    static final ArchRule model_is_a_leaf = noClasses().that().resideInAPackage(BASE + "model..")
            .should().dependOnClassesThat().resideInAnyPackage(BASE + "web..", BASE + "graphql..", BASE + "streams..",
                    BASE + "redis..", BASE + "cases..", BASE + "analytics..", BASE + "messaging..", BASE + "rules..",
                    BASE + "replay..", BASE + "scoring..", BASE + "config..", BASE + "security..", BASE + "simulator..");

    @ArchTest
    static final ArchRule streams_is_independent_of_the_delivery_and_persistence_layers =
            noClasses().that().resideInAPackage(BASE + "streams..")
                    .should().dependOnClassesThat().resideInAnyPackage(BASE + "web..", BASE + "graphql..",
                            BASE + "cases..", BASE + "analytics..", BASE + "messaging..", BASE + "replay..",
                            BASE + "security..", BASE + "simulator..");

    @ArchTest
    static final ArchRule scoring_only_knows_the_model = noClasses().that().resideInAPackage(BASE + "scoring..")
            .should().dependOnClassesThat().resideInAnyPackage(BASE + "web..", BASE + "graphql..", BASE + "streams..",
                    BASE + "redis..", BASE + "cases..", BASE + "analytics..", BASE + "messaging..", BASE + "rules..",
                    BASE + "replay..", BASE + "config..", BASE + "security..");

    @ArchTest
    static final ArchRule redis_services_do_not_reach_upwards = noClasses().that().resideInAPackage(BASE + "redis..")
            .should().dependOnClassesThat().resideInAnyPackage(BASE + "web..", BASE + "graphql..", BASE + "streams..",
                    BASE + "cases..", BASE + "analytics..", BASE + "messaging..", BASE + "replay..");

    @ArchTest
    static final ArchRule analytics_does_not_depend_on_delivery_or_streams =
            noClasses().that().resideInAPackage(BASE + "analytics..")
                    .should().dependOnClassesThat().resideInAnyPackage(BASE + "web..", BASE + "graphql..",
                            BASE + "streams..", BASE + "cases..", BASE + "messaging..", BASE + "replay..");

    @ArchTest
    static final ArchRule adapters_do_not_touch_the_stream_topology =
            noClasses().that().resideInAnyPackage(BASE + "web..", BASE + "graphql..")
                    .should().dependOnClassesThat().resideInAPackage(BASE + "streams..")
                    .because("adapters talk to services, not to the stream topology");

    @ArchTest
    static final ArchRule graphql_does_not_depend_on_web = noClasses().that().resideInAPackage(BASE + "graphql..")
            .should().dependOnClassesThat().resideInAPackage(BASE + "web..");

    @ArchTest
    static final ArchRule no_field_injection = noClasses().should().dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.beans.factory.annotation.Autowired")
            .because("use constructor injection");

    @ArchTest
    static final ArchRule no_standard_streams_or_jul = noClasses()
            .should().accessClassesThat().haveFullyQualifiedName("java.util.logging.Logger")
            .because("use SLF4J");

    @ArchTest
    static final ArchRule controllers_live_in_adapter_packages = classes()
            .that().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
            .should().resideInAnyPackage(BASE + "web..", BASE + "rules..", BASE + "replay..");

    @ArchTest
    static final ArchRule no_cycles_between_leaf_domain_packages = slices()
            .matching(BASE + "(model|scoring|redis|analytics|rules|replay|cases)..").should().beFreeOfCycles();
}
