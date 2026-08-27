plugins {
    id("ktor.kotlin-conventions")
}

description = "Ktor 標準 routing の上に乗る型付きエンドポイント DSL"

dependencies {
    api(libs.ktor.server.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.server.status.pages)
    testImplementation(libs.ktor.server.auth)
    testImplementation(libs.ktor.server.content.negotiation)
    testImplementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(libs.logback.classic)
}
