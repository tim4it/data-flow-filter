plugins {
    java
    application
}

val lombokVersion = "1.18.46"
val jacksonVersion = "2.22.1"

group = "tim4it.login.eko"
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

repositories {
    mavenCentral()
}

dependencies {
    // Lombok
    compileOnly("org.projectlombok:lombok:$lombokVersion")
    annotationProcessor("org.projectlombok:lombok:$lombokVersion")

    // SQLite JDBC driver
    implementation("org.xerial:sqlite-jdbc:3.53.2.0")

    // Flyway for database migrations
    implementation("org.flywaydb:flyway-core:12.10.0")

    // CSV parsing
    implementation("com.univocity:univocity-parsers:2.9.1")

    // JSON processing
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.38")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter-api:6.1.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "tim4it.login.eko.Main"
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// Handle duplicate resources gracefully
tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}
