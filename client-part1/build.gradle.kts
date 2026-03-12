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
    implementation("io.netty:netty-transport-native-epoll:4.2.8.Final:linux-x86_64")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2")

    implementation("org.apache.logging.log4j:log4j-api:2.23.1")
    implementation("org.apache.logging.log4j:log4j-core:2.23.1")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.23.1")
    implementation("com.lmax:disruptor:3.4.4")
}

tasks.test {
    useJUnitPlatform()
}

// build.gradle.kts
tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
}
