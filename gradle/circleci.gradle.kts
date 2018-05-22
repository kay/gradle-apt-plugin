import org.gradle.util.GradleVersion

task("generateCircleciConfig", GenerateCircleciConfig::class) {
    jdks = listOf(8, 9, 10)
    crossVersion = mapOf(
        "gradle46" to CrossVersion("4.6", listOf(8, 9)),
        "gradle45" to CrossVersion("4.5.1", listOf(8, 9)),
        "gradle44" to CrossVersion("4.4.1", listOf(8, 9)),
        "gradle43" to CrossVersion("4.3.1", listOf(8, 9)),
        "gradle42" to CrossVersion("4.2.1", listOf(8, 9)),
        "gradle41" to CrossVersion("4.1", 8),
        "gradle40" to CrossVersion("4.0.2", 8),
        "gradle35" to CrossVersion("3.5.1", 8),
        "gradle34" to CrossVersion("3.4.1", 8),
        "gradle33" to CrossVersion("3.3", 8),
        "gradle32" to CrossVersion("3.2.1", 8),
        "gradle31" to CrossVersion("3.1", 8),
        "gradle30" to CrossVersion("3.0", 8),
        "gradle214" to CrossVersion("2.14.1", 8),
        "gradle213" to CrossVersion("2.13", 8),
        "gradle212" to CrossVersion("2.12", 8),
        "gradle211" to CrossVersion("2.11", 8),
        "gradle210" to CrossVersion("2.10", 8),
        "gradle29" to CrossVersion("2.9", 8),
        "gradle28" to CrossVersion("2.8", 8),
        "gradle27" to CrossVersion("2.7", 8),
        "gradle26" to CrossVersion("2.6", 8),
        "gradle25" to CrossVersion("2.5", 8)
    )
    outputFile = file(".circleci/config.yml")
}

open class GenerateCircleciConfig : DefaultTask() {
    @get:Input
    lateinit var jdks: List<Int>
    @get:Nested
    lateinit var crossVersion: Map<String, CrossVersion>

    @get:OutputFile
    lateinit var outputFile: File

    @TaskAction
    fun generate() {
        // XXX: this currently assumes that CrossVersion#jdks are a subset of jdks

        outputFile.writeText((
            """
            #
            # This is a generated file
            #
            platforms:
              - &defaults
                environment:
                  - GRADLE_OPTS: -Dorg.gradle.daemon=false
                  - JAVA_TOOL_OPTIONS: -XX:MaxRAM=4g -XX:ParallelGCThreads=2
            """ + jdks.map {
                """
              - &java$it
                <<: *defaults
                docker:
                  - image: circleci/openjdk:$it-jdk
                """
            }.joinToString(separator = "") + """

            caches:
              workspace:
                - &persist-workspace
                  persist_to_workspace:
                    root: .
                    paths: .
                - &attach-workspace
                  attach_workspace:
                    at: .
              test_results:
                - &store-test-results
                  store_test_results:
                    paths: build/test-results/
              dependencies:
            """ + jdks.map {
                """
                - &save-gradle-dependencies-java$it
                  save_cache:
                    name: Saving Gradle dependencies
                    key: v2-gradle-java$it-{{ checksum "build.gradle.kts" }}
                    paths:
                      - ~/.gradle/caches/modules-*/
                - &restore-gradle-dependencies-java$it
                  restore_cache:
                    name: Restoring Gradle dependencies
                    keys:
                      - v2-gradle-java$it-{{ checksum "build.gradle.kts" }}
                """
            }.joinToString(separator = "") + """
              wrapper:
            """ + (setOf(GradleVersion.current().getVersion()) + crossVersion.values.asSequence().map(CrossVersion::gradle)).map {
                """
                - &save-gradle-wrapper-${it.replace('.', '-')}
                  save_cache:
                    name: Saving Gradle wrapper $it
                    key: v1-gradle-wrapper-$it
                    paths:
                      - ~/.gradle/wrapper/dists/gradle-$it-bin/
                - &restore-gradle-wrapper-${it.replace('.', '-')}
                  restore_cache:
                    name: Restoring Gradle wrapper $it
                    keys:
                      - v1-gradle-wrapper-$it
                      - v1-gradle-wrapper-gradle${it.splitToSequence(delimiters = ".").take(2).joinToString(separator = "")}
                      - v1-gradle-wrapper-current
                """
            }.joinToString(separator = "") + """

            version: 2
            jobs:
              checkout_code:
                <<: *java${jdks.first()}
                steps:
                  - checkout
                  - run:
                      name: Remove Git tracking files (reduces workspace size)
                      command: rm -rf .git/
                  - *persist-workspace
            """ + jdks.map { """
              java$it:
                <<: *java$it
                steps:
                  - *attach-workspace
                  - *restore-gradle-dependencies-java$it
                  - *restore-gradle-wrapper-${GradleVersion.current().getVersion().replace('.', '-')}
                  - run:
                      name: Build
                      command: ./gradlew build
                  - *store-test-results
                  - *save-gradle-wrapper-${GradleVersion.current().getVersion().replace('.', '-')}
                  - *save-gradle-dependencies-java$it
                  - *persist-workspace
                """
            }.joinToString(separator = "") + crossVersion.flatMap { (name, version) ->
                version.jdks.map { """
              java${it}_$name:
                <<: *java$it
                steps:
                  - *attach-workspace
                  - *restore-gradle-dependencies-java$it
                  - *restore-gradle-wrapper-${GradleVersion.current().getVersion().replace('.', '-')}
                  - *restore-gradle-wrapper-${version.gradle.replace('.', '-')}
                  - run:
                      name: Test against Gradle ${version.gradle}
                      command: ./gradlew test -Ptest.gradle-version=${version.gradle}
                  - *store-test-results
                  - *save-gradle-wrapper-${version.gradle.replace('.', '-')}
                    """
                }
            }.joinToString(separator = "") + """

            workflows:
              version: 2
              tests:
                jobs:
                  - checkout_code
            """ + jdks.mapIndexed { index, it ->
                """
                  - java$it:
                      requires:
                        - ${if (index > 0) "java${jdks.first()}" else "checkout_code" }
                """
            }.joinToString(separator = "") + crossVersion.flatMap { (name, version) ->
                version.jdks.mapIndexed { index, it ->
                    """
                  - java${it}_$name:
                      requires:
                        - java$it
                        ${if (index > 0) "- java${version.jdks.first()}_$name" else ""}
                    """
                }
            }.joinToString(separator = "")
        ).trimIndent())
    }
}

data class CrossVersion(
    @get:Input val gradle: String,
    @get:Input val jdks: List<Int>
) {
    constructor(gradle: String, jdk: Int) : this(gradle, listOf(jdk))
}
