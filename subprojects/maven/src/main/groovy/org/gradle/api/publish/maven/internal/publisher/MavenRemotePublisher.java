/*
 * Copyright 2012 the original author or authors.
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

package org.gradle.api.publish.maven.internal.publisher;

import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.internal.artifacts.mvnsettings.LocalMavenRepositoryLocator;
import org.gradle.api.internal.artifacts.repositories.transport.RepositoryTransportFactory;
import org.gradle.api.publication.maven.internal.ant.MavenDeployAction;
import org.gradle.api.publication.maven.internal.wagon.RepositoryTransportDeployWagon;
import org.gradle.api.publication.maven.internal.wagon.WagonRegistry;
import org.gradle.internal.Factory;
import org.gradle.logging.LoggingManagerInternal;

import java.io.File;

public class MavenRemotePublisher extends AbstractMavenPublisher<MavenDeployAction> {
    private final Factory<File> temporaryDirFactory;
    private final RepositoryTransportFactory repositoryTransportFactory;

    public MavenRemotePublisher(Factory<LoggingManagerInternal> loggingManagerFactory, LocalMavenRepositoryLocator mavenRepositoryLocator, Factory<File> temporaryDirFactory, RepositoryTransportFactory repositoryTransportFactory) {
        super(loggingManagerFactory, mavenRepositoryLocator);
        this.temporaryDirFactory = temporaryDirFactory;
        this.repositoryTransportFactory = repositoryTransportFactory;
    }

    protected MavenDeployAction createDeployTask(File pomFile, LocalMavenRepositoryLocator mavenRepositoryLocator, MavenArtifactRepository artifactRepository) {
        MavenDeployAction deployTask = new GradleWagonMavenDeployAction(pomFile, artifactRepository, repositoryTransportFactory);
        deployTask.setLocalMavenRepositoryLocation(temporaryDirFactory.create());
        deployTask.setRepositories(new MavenRemoteRepositoryFactory(artifactRepository).create(), null);
        deployTask.setUniqueVersion(true);
        return deployTask;
    }

    private static class GradleWagonMavenDeployAction extends MavenDeployAction {
        private final MavenArtifactRepository artifactRepository;
        private final RepositoryTransportFactory repositoryTransportFactory;
        private final WagonRegistry wagonRegistry;

        public GradleWagonMavenDeployAction(File pomFile, MavenArtifactRepository artifactRepository, RepositoryTransportFactory repositoryTransportFactory) {
            super(pomFile);
            this.artifactRepository = artifactRepository;
            this.repositoryTransportFactory = repositoryTransportFactory;
            this.wagonRegistry = new WagonRegistry(getContainer());

            wagonRegistry.registerAll();
        }

        @Override
        public void publish() {
            String protocol = artifactRepository.getUrl().getScheme().toLowerCase();
            if (wagonRegistry.isCustomWagonProtocol(protocol)) {
                RepositoryTransportDeployWagon.init(protocol, artifactRepository, repositoryTransportFactory);
            }

            try {
                super.publish();
            } finally {
                RepositoryTransportDeployWagon.reset();
            }
        }
    }
}
