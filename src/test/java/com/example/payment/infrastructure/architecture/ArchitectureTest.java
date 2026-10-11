package com.example.payment.infrastructure.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * The architecture as executable documentation: each rule is a boundary the
 * earlier episodes drew by hand, now checked on every build.
 */
@AnalyzeClasses(packages = "com.example.payment", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

	private static final String DOMAIN = "com.example.payment.domain..";
	private static final String WEB = "com.example.payment.infrastructure.adapter.primary.web..";

	// Infrastructure -> Application -> Domain, never the reverse (docs/episodes/20).
	// Config wires everything, so it may see every layer and no layer may see it.
	@ArchTest
	static final ArchRule dependenciesPointInwards = layeredArchitecture().consideringOnlyDependenciesInLayers()
			.layer("Config").definedBy("com.example.payment.config..")
			.layer("Infrastructure").definedBy("com.example.payment.infrastructure..")
			.layer("Application").definedBy("com.example.payment.application..")
			.layer("Domain").definedBy(DOMAIN)
			.whereLayer("Config").mayNotBeAccessedByAnyLayer()
			.whereLayer("Infrastructure").mayOnlyBeAccessedByLayers("Config")
			.whereLayer("Application").mayOnlyBeAccessedByLayers("Infrastructure", "Config")
			.whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Infrastructure", "Config");

	@ArchTest
	static final ArchRule domainDoesNotDependOnFrameworks = noClasses()
			.that().resideInAPackage(DOMAIN)
			.should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..", "feign..");

	// Authentication stays in the web adapter and config. The application gets the caller as a plain customer ID.
	@ArchTest
	static final ArchRule coreDoesNotDependOnSpringSecurity = noClasses()
			.that().resideInAnyPackage(DOMAIN, "com.example.payment.application..")
			.should().dependOnClassesThat().resideInAPackage("org.springframework.security..");

	// Tracing is infrastructure too: Spring traces the adapters, and the core never sees a Tracer or a Span (docs/episodes/bonus-05).
	@ArchTest
	static final ArchRule coreDoesNotDependOnTracing = noClasses()
			.that().resideInAnyPackage(DOMAIN, "com.example.payment.application..")
			.should().dependOnClassesThat().resideInAnyPackage("io.micrometer.tracing..", "io.opentelemetry..");

	// The classes generated from src/main/avro are the wire format, like a JPA entity is the table: the messaging
	// adapters map them to and from the domain's PaymentEvent, and nothing else sees them (docs/episodes/bonus-10).
	@ArchTest
	static final ArchRule eventSchemaClassesStayInTheMessagingAdapters = noClasses()
			.that().resideOutsideOfPackages("com.example.payment.events..",
					"com.example.payment.infrastructure.adapter.primary.messaging..",
					"com.example.payment.infrastructure.adapter.secondary.messaging..")
			.should().dependOnClassesThat().resideInAPackage("com.example.payment.events..");

	@ArchTest
	static final ArchRule domainDoesNotUsePersistenceEntities = noClasses()
			.that().resideInAPackage(DOMAIN)
			.should().dependOnClassesThat().areAnnotatedWith(Entity.class);

	// The web adapter may read a payment to build a response, but never create one,
	// change one, or compare it against a domain constant: those are business decisions.
	@ArchTest
	static final ArchRule webDoesNotCallDomainBehaviour = noClasses()
			.that().resideInAPackage(WEB)
			.should().callCodeUnitWhere(DescribedPredicate.describe(
					"the target is domain behaviour (a constructor, a static factory or a void state change)",
					ArchitectureTest::isDomainBehaviour));

	@ArchTest
	static final ArchRule webDoesNotReadDomainConstants = noClasses()
			.that().resideInAPackage(WEB)
			.should().accessFieldWhere(DescribedPredicate.describe(
					"the target is a domain field, such as a PaymentStatus constant",
					(JavaFieldAccess access) -> inDomain(access.getTargetOwner().getPackageName())));

	private static boolean isDomainBehaviour(JavaCall<?> call) {
		if (!inDomain(call.getTargetOwner().getPackageName())) {
			return false;
		}
		return call instanceof JavaConstructorCall
				|| call.getTarget().getRawReturnType().isEquivalentTo(void.class)
				|| call.getTarget().resolveMember()
						.map(member -> member.getModifiers().contains(JavaModifier.STATIC))
						.orElse(true);
	}

	private static boolean inDomain(String packageName) {
		return packageName.startsWith("com.example.payment.domain");
	}
}
