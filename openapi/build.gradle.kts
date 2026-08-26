description = "ktor-typed-routing-openapi"

dependencies {
    api(project(":core"))
    api(libs.ktor.server.routing.openapi)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.logback.classic)
}
