package ca.deliyannides.dirtmcp.paper;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ArchitectureTest {
    private static final JavaClasses PRODUCTION_CLASSES =
            new ClassFileImporter()
                    .withImportOption(new ImportOption.DoNotIncludeTests())
                    .importPackages("ca.deliyannides.dirtmcp.paper");

    private static final Set<String> PLATFORM_ADAPTERS =
            Set.of(
                    "ca.deliyannides.dirtmcp.paper.DirtMcpPlugin",
                    "ca.deliyannides.dirtmcp.paper.bootstrap.DirtRuntime",
                    "ca.deliyannides.dirtmcp.paper.command.BukkitCommandAccess",
                    "ca.deliyannides.dirtmcp.paper.command.DirtAdminCommand",
                    "ca.deliyannides.dirtmcp.paper.config.DirtConfigLoader",
                    "ca.deliyannides.dirtmcp.paper.logging.DirtLog",
                    "ca.deliyannides.dirtmcp.paper.platform.PaperMainThread",
                    "ca.deliyannides.dirtmcp.paper.status.BukkitServerStatusAccess",
                    "ca.deliyannides.dirtmcp.paper.world.edit.FaweEditExecutor",
                    "ca.deliyannides.dirtmcp.paper.world.edit.PaperEditPreparation",
                    "ca.deliyannides.dirtmcp.paper.world.edit.PaperFaweEditPlatform",
                    "ca.deliyannides.dirtmcp.paper.world.edit.PaperWorldEditServiceFactory",
                    "ca.deliyannides.dirtmcp.paper.world.edit.WorldEditLifecycleListener",
                    "ca.deliyannides.dirtmcp.paper.world.inspection.BukkitPerspectiveViewAccess",
                    "ca.deliyannides.dirtmcp.paper.world.inspection.BukkitPlayerContextAccess",
                    "ca.deliyannides.dirtmcp.paper.world.inspection.BukkitPlayerResolver",
                    "ca.deliyannides.dirtmcp.paper.world.inspection.PaperRegionSnapshotSource");

    @Test
    void operationContractsAndWorldModelsStayPlatformIndependent() {
        noClasses()
                .that()
                .resideInAnyPackage(
                        "ca.deliyannides.dirtmcp.paper.operation..",
                        "ca.deliyannides.dirtmcp.paper.world.model..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.google.gson..",
                        "com.sun.net.httpserver..",
                        "org.bukkit..",
                        "com.sk89q.worldedit..",
                        "com.fastasyncworldedit..")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void bridgeEndpointsStayIndependentOfPaperAndFawe() {
        noClasses()
                .that()
                .resideInAPackage("ca.deliyannides.dirtmcp.paper.bridge.endpoint..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.bukkit..", "com.sk89q.worldedit..", "com.fastasyncworldedit..")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void featureInterfacesStayIndependentOfTransportPaperAndFawe() {
        noClasses()
                .that()
                .areInterfaces()
                .and()
                .arePublic()
                .and()
                .resideInAnyPackage(
                        "ca.deliyannides.dirtmcp.paper.status..",
                        "ca.deliyannides.dirtmcp.paper.access..",
                        "ca.deliyannides.dirtmcp.paper.command..",
                        "ca.deliyannides.dirtmcp.paper.world.inspection..",
                        "ca.deliyannides.dirtmcp.paper.world.edit..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.google.gson..",
                        "com.sun.net.httpserver..",
                        "org.bukkit..",
                        "com.sk89q.worldedit..",
                        "com.fastasyncworldedit..")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void httpTransportStaysInsideTheBridge() {
        noClasses()
                .that()
                .resideOutsideOfPackage("ca.deliyannides.dirtmcp.paper.bridge..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.sun.net.httpserver..")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void jsonSerializationStaysInsideTransportAndLoggingAdapters() {
        noClasses()
                .that()
                .resideOutsideOfPackages(
                        "ca.deliyannides.dirtmcp.paper.bridge..",
                        "ca.deliyannides.dirtmcp.paper.access..",
                        "ca.deliyannides.dirtmcp.paper.catalog..",
                        "ca.deliyannides.dirtmcp.paper.logging..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.google.gson..")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void schedulerImplementationStaysInsideThePlatformBoundary() {
        noClasses()
                .that()
                .resideOutsideOfPackage("ca.deliyannides.dirtmcp.paper.platform..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("org.bukkit.scheduler..")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void onlyExplicitPlatformAdaptersDependOnBukkitOrFawe() {
        noClasses()
                .that(notPlatformAdapters())
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.bukkit..", "com.sk89q.worldedit..", "com.fastasyncworldedit..")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void paperConfigurationStatusAndAdministrationDoNotDependOnHttpEndpoints() {
        noClasses()
                .that()
                .resideInAnyPackage(
                        "ca.deliyannides.dirtmcp.paper.config..",
                        "ca.deliyannides.dirtmcp.paper.status..",
                        "ca.deliyannides.dirtmcp.paper.command..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("ca.deliyannides.dirtmcp.paper.bridge.endpoint..")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void bridgeTransportDoesNotDependOnConcreteOperationResults() {
        noClasses()
                .that()
                .resideInAPackage("ca.deliyannides.dirtmcp.paper.bridge")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("Result")
                .check(PRODUCTION_CLASSES);
    }

    private static DescribedPredicate<JavaClass> notPlatformAdapters() {
        return new DescribedPredicate<>("are not explicit Paper or FAWE adapters") {
            @Override
            public boolean test(JavaClass type) {
                return PLATFORM_ADAPTERS.stream()
                        .noneMatch(
                                adapter ->
                                        type.getName().equals(adapter)
                                                || type.getName().startsWith(adapter + "$"));
            }
        };
    }
}
