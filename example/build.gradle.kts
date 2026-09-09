plugins {
    application
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("su.onno.desktop")
    // Compiles src/main/widgets/*.tsx into onno UI widget plugins (see DashboardPage's "eventLog").
    id("su.onno.widgets")
}

dependencies {
    // Core features run by default; the crm Spring profile binds the optional messaging module.
    implementation(project(":onno-framework-starter"))
    implementation(project(":onno-ui-starter"))
    implementation(project(":onno-auth-starter"))
    // The crm profile explicitly binds the existing Customer and Employee catalogs.
    implementation(project(":onno-crm-starter"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("com.h2database:h2")

    // Dev live-reload: on a recompile devtools restarts the context (metamodel rescan, schema
    // re-diff, layout/page rebuild — all boot-time work), and the UI starter detects devtools on
    // the classpath and tells the browser to refresh over the /api/events stream. developmentOnly
    // keeps it out of the production bootJar. Loop: `./gradlew :example:bootRun` in one terminal,
    // `./gradlew -t :example:classes` in another; save a file → app restarts → browser reloads.
    developmentOnly("org.springframework.boot:spring-boot-devtools")

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
}

application {
    mainClass.set("com.example.BookstoreApp")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // onno reads constructor/record parameter names reflectively; keep them in the bytecode.
    options.compilerArgs.add("-parameters")
}

// Prints the full runtime classpath — including developmentOnly, so devtools rides along — for
// running the exploded app directly (`java -cp "$(./gradlew -q :example:devClasspath)"
// com.example.BookstoreApp`). Devtools disables itself inside a fat jar, so the live-reload dev
// loop needs this exploded form: recompile → devtools sees build/classes change → context restart
// → browser reloads over /api/events. Depends on the classpath so dependency jars get built.
tasks.register("devClasspath") {
    val classpath = sourceSets["main"].runtimeClasspath
    dependsOn(classpath)
    doLast {
        println(classpath.asPath)
    }
}

// Optional ordinary sales model; the inbox example does not install opportunities or metrics.
if (providers.gradleProperty("salesExample").isPresent) {
    sourceSets["main"].java.srcDir("src/sales/java")
}
