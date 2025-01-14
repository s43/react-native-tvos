/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.react.utils

import com.facebook.react.utils.PropertyUtils.DEFAULT_INTERNAL_PUBLISHING_GROUP
import com.facebook.react.utils.PropertyUtils.INTERNAL_PUBLISHING_GROUP
import com.facebook.react.utils.PropertyUtils.INTERNAL_REACT_NATIVE_MAVEN_LOCAL_REPO
import com.facebook.react.utils.PropertyUtils.INTERNAL_USE_HERMES_NIGHTLY
import com.facebook.react.utils.PropertyUtils.INTERNAL_VERSION_NAME
import java.io.File
import java.net.URI
import java.util.*
import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.MavenArtifactRepository

internal object DependencyUtils {

  /**
   * This method takes care of configuring the repositories{} block for both the app and all the 3rd
   * party libraries which are auto-linked.
   */
  fun configureRepositories(project: Project, reactNativeDir: File) {
    project.rootProject.allprojects { eachProject ->
      with(eachProject) {
        if (hasProperty(INTERNAL_REACT_NATIVE_MAVEN_LOCAL_REPO)) {
          val mavenLocalRepoPath = property(INTERNAL_REACT_NATIVE_MAVEN_LOCAL_REPO) as String
          mavenRepoFromURI(File(mavenLocalRepoPath).toURI())
        }
        // We add the snapshot for users on nightlies.
        mavenRepoFromUrl("https://oss.sonatype.org/content/repositories/snapshots/")
        repositories.mavenCentral { repo ->
          // We don't want to fetch JSC from Maven Central as there are older versions there.
          repo.content { it.excludeModule("org.webkit", "android-jsc") }
        }
        // Android JSC is installed from npm
        mavenRepoFromURI(File(reactNativeDir, "../jsc-android/dist").toURI())
        repositories.google()
        mavenRepoFromUrl("https://www.jitpack.io")
      }
    }
  }

  /**
   * This method takes care of configuring the resolution strategy for both the app and all the 3rd
   * party libraries which are auto-linked. Specifically it takes care of:
   * - Forcing the react-android/hermes-android/flipper-integration version to the one specified in
   *   the package.json
   * - Substituting `react-native` with `react-android` and `hermes-engine` with `hermes-android`.
   */
  fun configureDependencies(
      project: Project,
      versionString: String,
      groupString: String = DEFAULT_INTERNAL_PUBLISHING_GROUP
  ) {
    if (versionString.isBlank()) return
    project.rootProject.allprojects { eachProject ->
      eachProject.configurations.all { configuration ->
        // Here we set a dependencySubstitution for both react-native and hermes-engine as those
        // coordinates are voided due to https://github.com/facebook/react-native/issues/35210
        // This allows users to import libraries that are still using
        // implementation("com.facebook.react:react-native:+") and resolve the right dependency.
        configuration.resolutionStrategy.dependencySubstitution {
          getDependencySubstitutions(versionString, groupString).forEach { (module, dest, reason) ->
            it.substitute(it.module(module)).using(it.module(dest)).because(reason)
          }
        }
        configuration.resolutionStrategy.force(
            "${groupString}:react-android:${versionString}",
            "${groupString}:flipper-integration:0.182.0",
        )
        if (!(eachProject.findProperty(INTERNAL_USE_HERMES_NIGHTLY) as? String).toBoolean()) {
          // Contributors only: The hermes-engine version is forced only if the user has
          // not opted into using nightlies for local development.
          configuration.resolutionStrategy.force("${groupString}:hermes-android:${versionString}")
        }
      }
    }
  }

  internal fun getDependencySubstitutions(
      versionString: String,
      groupString: String = DEFAULT_INTERNAL_PUBLISHING_GROUP
  ): List<Triple<String, String, String>> {
    val dependencySubstitution = mutableListOf<Triple<String, String, String>>()
    dependencySubstitution.add(
        Triple(
            "com.facebook.react:react-native",
            "${groupString}:react-android:${versionString}",
            "The react-native artifact was deprecated in favor of react-android due to https://github.com/facebook/react-native/issues/35210."))
    dependencySubstitution.add(
        Triple(
            "com.facebook.react:hermes-engine",
            "${groupString}:hermes-android:${versionString}",
            "The hermes-engine artifact was deprecated in favor of hermes-android due to https://github.com/facebook/react-native/issues/35210."))
    if (groupString != DEFAULT_INTERNAL_PUBLISHING_GROUP) {
      dependencySubstitution.add(
          Triple(
              "com.facebook.react:react-android",
              "${groupString}:react-android:${versionString}",
              "The react-android dependency was modified to use the correct Maven group."))
      dependencySubstitution.add(
          Triple(
              "com.facebook.react:hermes-android",
              "${groupString}:hermes-android:${versionString}",
              "The hermes-android dependency was modified to use the correct Maven group."))
    }
    return dependencySubstitution
  }

  fun readVersionAndGroupStrings(propertiesFile: File): Pair<String, String> {
    val reactAndroidProperties = Properties()
    propertiesFile.inputStream().use { reactAndroidProperties.load(it) }
    val versionStringFromFile = reactAndroidProperties[INTERNAL_VERSION_NAME] as? String ?: ""
    // If on a nightly, we need to fetch the -SNAPSHOT artifact from Sonatype.
    val versionString =
        if (versionStringFromFile.startsWith("0.0.0") || "-nightly-" in versionStringFromFile) {
          "$versionStringFromFile-SNAPSHOT"
        } else {
          versionStringFromFile
        }
    // Returns Maven group for repos using different group for Maven artifacts
    val groupString =
        reactAndroidProperties[INTERNAL_PUBLISHING_GROUP] as? String
            ?: DEFAULT_INTERNAL_PUBLISHING_GROUP
    return Pair(versionString, groupString)
  }

  fun Project.mavenRepoFromUrl(url: String): MavenArtifactRepository =
      project.repositories.maven { it.url = URI.create(url) }

  fun Project.mavenRepoFromURI(uri: URI): MavenArtifactRepository =
      project.repositories.maven { it.url = uri }
}
