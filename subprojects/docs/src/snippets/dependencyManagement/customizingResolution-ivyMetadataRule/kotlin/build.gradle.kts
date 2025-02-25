plugins {
    `java-library`
}

repositories {
    ivy {
        url = uri("$projectDir/repo")
    }
}

// tag::ivy-component-metadata-rule[]
abstract class IvyVariantDerivationRule : ComponentMetadataRule {
    @Inject abstract fun getObjects(): ObjectFactory

    override fun execute(context: ComponentMetadataContext) {
        // This filters out any non Ivy module
        if(context.getDescriptor(IvyModuleDescriptor::class) == null) {
            return
        }

        context.details.addVariant("runtimeElements", "default") {
            attributes {
                attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, getObjects().named<LibraryElements>(LibraryElements.JAR))
                attribute(Category.CATEGORY_ATTRIBUTE, getObjects().named<Category>(Category.LIBRARY))
                attribute(Usage.USAGE_ATTRIBUTE, getObjects().named<Usage>(Usage.JAVA_RUNTIME))
            }
        }
        context.details.addVariant("apiElements", "compile") {
            attributes {
                attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, getObjects().named<LibraryElements>(LibraryElements.JAR))
                attribute(Category.CATEGORY_ATTRIBUTE, getObjects().named<Category>(Category.LIBRARY))
                attribute(Usage.USAGE_ATTRIBUTE, getObjects().named<Usage>(Usage.JAVA_API))
            }
        }
    }
}

dependencies {
    components { all<IvyVariantDerivationRule>() }
}
// end::ivy-component-metadata-rule[]

dependencies {
    implementation("org.sample:api:2.0")
}

tasks.register("compileClasspathArtifacts") {
    doLast {
        configurations["compileClasspath"].forEach { println(it.name) }
    }
}
tasks.register("runtimeClasspathArtifacts") {
    doLast {
        configurations["runtimeClasspath"].forEach { println(it.name) }
    }
}
