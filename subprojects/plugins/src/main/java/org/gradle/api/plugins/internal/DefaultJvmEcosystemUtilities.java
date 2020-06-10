/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.plugins.internal;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.gradle.api.Action;
import org.gradle.api.JavaVersion;
import org.gradle.api.NonNullApi;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.ConfigurationPublications;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.attributes.Bundling;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.HasConfigurableAttributes;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.capabilities.Capability;
import org.gradle.api.internal.artifacts.ConfigurationVariantInternal;
import org.gradle.api.internal.artifacts.JavaEcosystemSupport;
import org.gradle.api.internal.artifacts.configurations.ConfigurationInternal;
import org.gradle.api.internal.artifacts.dsl.LazyPublishArtifact;
import org.gradle.api.internal.attributes.AttributeContainerInternal;
import org.gradle.api.internal.tasks.DefaultSourceSetOutput;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.internal.Cast;
import org.gradle.internal.Factory;

import javax.annotation.Nullable;
import java.io.File;
import java.util.List;
import java.util.Set;

import static org.gradle.api.attributes.Bundling.BUNDLING_ATTRIBUTE;

@NonNullApi
public class DefaultJvmEcosystemUtilities implements JvmEcosystemUtilitiesInternal {
    private final ConfigurationContainer configurations;
    private final ObjectFactory objectFactory;
    private final TaskContainer tasks;

    private JavaPluginConvention javaConvention;
    private SourceSetContainer sourceSets;

    public DefaultJvmEcosystemUtilities(ConfigurationContainer configurations,
                                        ObjectFactory objectFactory,
                                        TaskContainer tasks) {
        this.configurations = configurations;
        this.objectFactory = objectFactory;
        this.tasks = tasks;
    }

    @Override
    public void setJavaConvention(JavaPluginConvention javaConvention) {
        this.javaConvention = javaConvention;
        this.sourceSets = javaConvention.getSourceSets();
    }

    @Override
    public void addApiToSourceSet(SourceSet sourceSet) {
        Configuration apiConfiguration = configurations.maybeCreate(sourceSet.getApiConfigurationName());
        apiConfiguration.setVisible(false);
        apiConfiguration.setDescription("API dependencies for " + sourceSet + ".");
        apiConfiguration.setCanBeResolved(false);
        apiConfiguration.setCanBeConsumed(false);

        Configuration apiElementsConfiguration = configurations.getByName(sourceSet.getApiElementsConfigurationName());
        apiElementsConfiguration.extendsFrom(apiConfiguration);

        Configuration implementationConfiguration = configurations.getByName(sourceSet.getImplementationConfigurationName());
        implementationConfiguration.extendsFrom(apiConfiguration);

        @SuppressWarnings("deprecation")
        Configuration compileConfiguration = configurations.getByName(sourceSet.getCompileConfigurationName());
        apiConfiguration.extendsFrom(compileConfiguration);
    }

    @Override
    public void configureClassesDirectoryVariant(String configurationName, SourceSet sourceSet) {
        configurations.all(config -> {
            if (configurationName.equals(config.getName())) {
                registerClassesDirVariant(sourceSet, config);
            }
        });
    }

    @Override
    public <T> void configureAsCompileClasspath(HasConfigurableAttributes<T> configuration) {
        configureAttributes(configuration, details -> details.library().providingApi().withExternalDependencies());
    }

    @Override
    public <T> void configureAsRuntimeClasspath(HasConfigurableAttributes<T> configuration) {
        configureAttributes(configuration, details -> details.library().providingRuntime().asJar().withExternalDependencies());
    }

    @Override
    public <T> void configureAttributes(HasConfigurableAttributes<T> configurable, Action<? super JvmEcosystemAttributesDetails> details) {
        AttributeContainerInternal attributes = (AttributeContainerInternal) configurable.getAttributes();
        details.execute(new DefaultJvmEcosystemAttributesDetails(attributes));
    }

    @Override
    public void useDefaultTargetPlatformInference(Configuration configuration, SourceSet sourceSet) {
        ((ConfigurationInternal) configuration).beforeLocking(
            configureDefaultTargetPlatform(configuration.isCanBeConsumed(), tasks.named(sourceSet.getCompileJavaTaskName(), JavaCompile.class)));
    }

    private void registerClassesDirVariant(final SourceSet sourceSet, Configuration configuration) {
        // Define a classes variant to use for compilation
        ConfigurationPublications publications = configuration.getOutgoing();
        ConfigurationVariantInternal variant = (ConfigurationVariantInternal) publications.getVariants().maybeCreate("classes");
        variant.getAttributes().attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objectFactory.named(LibraryElements.class, LibraryElements.CLASSES));
        variant.artifactsProvider(new Factory<List<PublishArtifact>>() {
            @Nullable
            @Override
            public List<PublishArtifact> create() {
                Set<File> classesDirs = sourceSet.getOutput().getClassesDirs().getFiles();
                DefaultSourceSetOutput output = Cast.uncheckedCast(sourceSet.getOutput());
                TaskDependency compileDependencies = output.getCompileDependencies();
                ImmutableList.Builder<PublishArtifact> artifacts = ImmutableList.builderWithExpectedSize(classesDirs.size());
                for (File classesDir : classesDirs) {
                    // this is an approximation: all "compiled" sources will use the same task dependency
                    artifacts.add(new JvmPluginsHelper.IntermediateJavaArtifact(ArtifactTypeDefinition.JVM_CLASS_DIRECTORY, compileDependencies) {
                        @Override
                        public File getFile() {
                            return classesDir;
                        }
                    });
                }
                return artifacts.build();
            }
        });
    }

    private Action<ConfigurationInternal> configureDefaultTargetPlatform(boolean alwaysEnabled, TaskProvider<JavaCompile> compileTaskProvider) {
        return conf -> {
            if (alwaysEnabled || !javaConvention.getAutoTargetJvmDisabled()) {
                JavaCompile javaCompile = compileTaskProvider.get();
                int majorVersion;
                int releaseOption = getReleaseOption(javaCompile.getOptions().getCompilerArgs());
                if (releaseOption > 0) {
                    majorVersion = releaseOption;
                } else {
                    majorVersion = Integer.parseInt(JavaVersion.toVersion(javaCompile.getTargetCompatibility()).getMajorVersion());
                }
                JavaEcosystemSupport.configureDefaultTargetPlatform(conf, majorVersion);
            }
        };
    }

    @Override
    public Configuration createOutgoingElements(String name, Action<? super OutgoingElementsBuilder> action) {
        DefaultElementsConfigurationBuilder builder = new DefaultElementsConfigurationBuilder(name);
        action.execute(builder);
        return builder.build();
    }

    private static int getReleaseOption(List<String> compilerArgs) {
        int flagIndex = compilerArgs.indexOf("--release");
        if (flagIndex != -1 && flagIndex + 1 < compilerArgs.size()) {
            return Integer.parseInt(String.valueOf(compilerArgs.get(flagIndex + 1)));
        }
        return 0;
    }

    private class DefaultElementsConfigurationBuilder implements OutgoingElementsBuilder {
        final String name;
        String description;
        boolean api;
        Configuration[] extendsFrom;
        SourceSet sourceSet;
        List<TaskProvider<Task>> artifactProducers;
        Action<? super JvmEcosystemAttributesDetails> attributesRefiner;
        List<Capability> capabilities;
        boolean classDirectory;

        private DefaultElementsConfigurationBuilder(String name) {
            this.name = name;
        }

        Configuration build() {
            Configuration cnf = configurations.maybeCreate(name);
            if (description != null) {
                cnf.setDescription(description);
            }
            cnf.setVisible(false);
            cnf.setCanBeConsumed(true);
            cnf.setCanBeResolved(false);
            if (extendsFrom != null) {
                cnf.extendsFrom(extendsFrom);
            }
            configureAttributes(cnf, details -> {
                    details.library()
                        .asJar()
                        .withExternalDependencies();
                    if (api) {
                        details.providingApi();
                    } else {
                        details.providingRuntime();
                    }
                    if (attributesRefiner != null) {
                        attributesRefiner.execute(details);
                    }
                }
            );
            if (sourceSet != null) {
                useDefaultTargetPlatformInference(cnf, sourceSet);
            }
            if (artifactProducers != null) {
                for (TaskProvider<Task> provider : artifactProducers) {
                    cnf.getArtifacts().add(new LazyPublishArtifact(provider));
                }
            }
            if (capabilities != null) {
                ConfigurationPublications outgoing = cnf.getOutgoing();
                for (Capability capability : capabilities) {
                    outgoing.capability(capability);
                }
            }
            if (classDirectory) {
                if (!api) {
                    throw new IllegalStateException("Cannot add a class directory variant for a runtime outgoing variant");
                }
                if (sourceSet == null) {
                    throw new IllegalStateException("Cannot add a class directory variant without specifying the source set");
                }
                configureClassesDirectoryVariant(name, sourceSet);
            }
            return cnf;
        }

        @Override
        public OutgoingElementsBuilder withDescription(String description) {
            this.description = description;
            return this;
        }

        @Override
        public OutgoingElementsBuilder forApi() {
            this.api = true;
            return this;
        }

        @Override
        public OutgoingElementsBuilder forRuntime() {
            this.api = false;
            return this;
        }

        @Override
        public OutgoingElementsBuilder extendsFrom(Configuration... parentConfigurations) {
            this.extendsFrom = parentConfigurations;
            return this;
        }

        @Override
        public OutgoingElementsBuilder fromSourceSet(SourceSet sourceSet) {
            this.sourceSet = sourceSet;
            return this;
        }

        @Override
        public OutgoingElementsBuilder addArtifact(TaskProvider<Task> producer) {
            if (artifactProducers == null) {
                artifactProducers = Lists.newArrayList();
            }
            artifactProducers.add(producer);
            return this;
        }

        @Override
        public OutgoingElementsBuilder attributes(Action<? super JvmEcosystemAttributesDetails> refiner) {
            this.attributesRefiner = refiner;
            return this;
        }

        @Override
        public OutgoingElementsBuilder withCapabilities(List<Capability> capabilities) {
            this.capabilities = capabilities;
            return this;
        }

        @Override
        public OutgoingElementsBuilder withClassDirectoryVariant() {
            this.classDirectory = true;
            return this;
        }
    }

    private class DefaultJvmEcosystemAttributesDetails implements JvmEcosystemAttributesDetails {
        private final AttributeContainerInternal attributes;

        public DefaultJvmEcosystemAttributesDetails(AttributeContainerInternal attributes) {
            this.attributes = attributes;
        }

        @Override
        public JvmEcosystemAttributesDetails providingApi() {
            attributes.attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named(Usage.class, Usage.JAVA_API));
            return this;
        }

        @Override
        public JvmEcosystemAttributesDetails providingRuntime() {
            attributes.attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named(Usage.class, Usage.JAVA_RUNTIME));
            return this;
        }

        @Override
        public JvmEcosystemAttributesDetails library() {
            attributes.attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(Category.class, Category.LIBRARY));
            return this;
        }

        @Override
        public JvmEcosystemAttributesDetails platform() {
            attributes.attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(Category.class, Category.REGULAR_PLATFORM));
            return this;
        }

        @Override
        public JvmEcosystemAttributesDetails enforcedPlatform() {
            attributes.attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(Category.class, Category.ENFORCED_PLATFORM));
            return this;
        }

        @Override
        public JvmEcosystemAttributesDetails withExternalDependencies() {
            attributes.attribute(BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.class, Bundling.EXTERNAL));
            return this;
        }

        @Override
        public JvmEcosystemAttributesDetails withEmbeddedDependencies() {
            attributes.attribute(BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.class, Bundling.EMBEDDED));
            return this;
        }

        @Override
        public JvmEcosystemAttributesDetails withShadowedDependencies() {
            attributes.attribute(BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.class, Bundling.SHADOWED));
            return this;
        }

        @Override
        public JvmEcosystemAttributesDetails asJar() {
            attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objectFactory.named(LibraryElements.class, LibraryElements.JAR));
            return this;
        }
    }
}
