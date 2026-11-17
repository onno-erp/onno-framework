plugins {
    application
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    // Builds the app UI in src/main/ui (a Vite app on @onno/ui) into the jar.
    id("su.onno.ui")
}

dependencies {
    // Core features run by default; the crm Spring profile binds the optional messaging module.
    implementation(project(":onno-framework-starter"))
    implementation(project(":onno-web-starter"))
    implementation(project(":onno-auth-starter"))
    // The crm profile explicitly binds the existing Customer and Employee catalogs.
    implementation(project(":onno-crm-starter"))
    // Print forms: the Orders record menu offers a Word invoice and an Excel pick list (com.example.print).
    implementation(project(":onno-print-starter"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("com.h2database:h2")

    // Dev live-reload: on a recompile devtools restarts the context (metamodel rescan, schema
    // re-diff, layout/page rebuild — all boot-time work), and the UI starter detects devtools on
    // the classpath and tells the browser to refresh over the /api/events stream. developmentOnly
    // keeps it out of the production bootJar. Use the root npm scripts: dev (Vite), dev:backend
    // (bootRun), and dev:watch (continuous classes + generated model, skipping frontend builds).
    developmentOnly("org.springframework.boot:spring-boot-devtools")

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
}

application {
    mainClass.set("com.example.BookstoreApp")
}

// The UI is a member of the repository's npm workspace (root package.json), installed once by the
// UI starter. A standalone app keeps the default: `npm install` in src/main/ui.
onnoUi {
    npmProjectDir.set(rootProject.layout.projectDirectory)
}
tasks.named("npmInstall") {
    enabled = false
    dependsOn(":onno-web-starter:npmInstall")
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
