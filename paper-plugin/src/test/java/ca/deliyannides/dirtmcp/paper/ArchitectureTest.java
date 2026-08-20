package ca.deliyannides.dirtmcp.paper;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

final class ArchitectureTest {
    private static final JavaClasses PRODUCTION_CLASSES =
            new ClassFileImporter()
                    .withImportOption(new ImportOption.DoNotIncludeTests())
                    .importPackages("ca.deliyannides.dirtmcp.paper");

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
    void transportLibrariesStayInsideTheBridge() {
        noClasses()
                .that()
                .resideOutsideOfPackage("ca.deliyannides.dirtmcp.paper.bridge..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.google.gson..", "com.sun.net.httpserver..")
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
}
