/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.mvndaemon.mvnd.execution;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.stream.Stream;

import org.apache.maven.api.di.Named;
import org.apache.maven.api.di.Singleton;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This implementation of {@link BuildResumptionDataRepository} persists information in a properties file. The file is
 * stored in the build output directory under the Maven execution root.
 */
@Named
@Singleton
public class DefaultBuildResumptionDataRepository implements BuildResumptionDataRepository {
    private static final String RESUME_PROPERTIES_FILENAME = "resume.properties";
    private static final String REMAINING_PROJECTS = "remainingProjects";
    private static final String PROPERTY_DELIMITER = ", ";
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultBuildResumptionDataRepository.class);

    @Override
    public void persistResumptionData(MavenProject rootProject, BuildResumptionData buildResumptionData)
            throws BuildResumptionPersistenceException {
        Path directory = Paths.get(rootProject.getBuild().getDirectory());
        persistResumptionData(directory, buildResumptionData);
    }

    public void persistResumptionData(Path directory, BuildResumptionData buildResumptionData)
            throws BuildResumptionPersistenceException {
        Properties properties = convertToProperties(buildResumptionData);

        Path resumeProperties = directory.resolve(RESUME_PROPERTIES_FILENAME);
        try {
            Files.createDirectories(resumeProperties.getParent());
            try (Writer writer = Files.newBufferedWriter(resumeProperties)) {
                properties.store(writer, null);
            }
        } catch (IOException e) {
            String message = "Could not create " + RESUME_PROPERTIES_FILENAME + " file.";
            throw new BuildResumptionPersistenceException(message, e);
        }
    }

    private Properties convertToProperties(final BuildResumptionData buildResumptionData) {
        Properties properties = new Properties();

        String value = String.join(PROPERTY_DELIMITER, buildResumptionData.getRemainingProjects());
        properties.setProperty(REMAINING_PROJECTS, value);

        return properties;
    }

    @Override
    public void applyResumptionData(MavenExecutionRequest request, MavenProject rootProject) {
        Path directory = Paths.get(rootProject.getBuild().getDirectory());
        applyResumptionData(request, directory);
    }

    public void applyResumptionData(MavenExecutionRequest request, Path directory) {
        Properties properties = loadResumptionFile(directory);
        applyResumptionProperties(request, properties);
    }

    @Override
    public void removeResumptionData(MavenProject rootProject) {
        Path directory = Paths.get(rootProject.getBuild().getDirectory());
        removeResumptionData(directory);
    }

    public void removeResumptionData(Path directory) {
        Path resumeProperties = directory.resolve(RESUME_PROPERTIES_FILENAME);
        try {
            Files.deleteIfExists(resumeProperties);
        } catch (IOException e) {
            LOGGER.warn("Could not delete {} file. ", RESUME_PROPERTIES_FILENAME, e);
        }
    }

    private Properties loadResumptionFile(Path rootBuildDirectory) {
        Properties properties = new Properties();
        Path path = rootBuildDirectory.resolve(RESUME_PROPERTIES_FILENAME);
        if (!Files.exists(path)) {
            LOGGER.warn("The {} file does not exist. The --resume / -r feature will not work.", path);
            return properties;
        }

        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        } catch (IOException e) {
            LOGGER.warn("Unable to read {}. The --resume / -r feature will not work.", path);
        }

        return properties;
    }

    // This method is made package-private for testing purposes
    void applyResumptionProperties(MavenExecutionRequest request, Properties properties) {
        if (properties.containsKey(REMAINING_PROJECTS)
                && (request.getResumeFrom() == null || request.getResumeFrom().isEmpty())) {
            String propertyValue = properties.getProperty(REMAINING_PROJECTS);
            Stream.of(propertyValue.split(PROPERTY_DELIMITER))
                    .filter(s -> s != null && !s.isEmpty())
                    .forEach(request.getSelectedProjects()::add);
            LOGGER.info("Resuming from {} due to the --resume / -r feature.", propertyValue);
        }
    }
}
