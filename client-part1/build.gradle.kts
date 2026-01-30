plugins {
    id("java")
}

group = "com.chatflow"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    implementation(project(":common"))
    implementation("io.netty:netty-all:4.2.8.Final")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
}

tasks.test {
    useJUnitPlatform()
}

// build.gradle.kts
tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
}