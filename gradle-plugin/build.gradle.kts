import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm") version "2.2.21"
    `java-gradle-plugin`
    `maven-publish`
}

group = "io.github.minjoonchoi"
version = "0.2.0"
repositories { mavenCentral() }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}
kotlin {
    compilerOptions {
        // Gradle 8.10.2's Kotlin DSL must be able to read our public task types.
        languageVersion.set(KotlinVersion.KOTLIN_1_9)
        apiVersion.set(KotlinVersion.KOTLIN_1_9)
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
dependencies {
    // Gradle supplies these at runtime. Parser dependencies stay in the isolated worker.
    compileOnly(localGroovy())
    compileOnly(kotlin("stdlib"))
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21")
    compileOnly("org.yaml:snakeyaml:2.3")
    testImplementation(localGroovy())
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21")
    testImplementation("org.yaml:snakeyaml:2.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation(gradleTestKit())
}
gradlePlugin {
    plugins {
        create("springPolicy") {
            id = "io.github.minjoonchoi.spring-policy"
            implementationClass = "io.github.minjoonchoi.policy.PolicyPlugin"
            displayName = "Spring Policy Check"
            description = "Check Kotlin Spring source policies inside the Gradle JVM."
        }
    }
}
tasks.test {
    inputs.dir("../tests/fixtures")
    inputs.file("../policy.yml")
    systemProperty("policy.repository", file("..").absolutePath)
    systemProperty("policy.gradle.home", requireNotNull(gradle.gradleHomeDir).absolutePath)
    maxHeapSize = "768m"
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
tasks.withType<Jar>().configureEach {
    from("../LICENSE") { into("META-INF/spring-policy") }
    from("../THIRD_PARTY_NOTICES.md") { into("META-INF/spring-policy") }
    from("../third_party") { into("META-INF/spring-policy/third_party") }
}
