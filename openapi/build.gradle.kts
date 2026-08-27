plugins {
    id("ktor.kotlin-conventions")
}

description = "型付きエンドポイントのメタデータを公式 ktor-server-routing-openapi に流し込むブリッジ"

dependencies {
    api(project(":core"))
    api(libs.ktor.server.routing.openapi)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.logback.classic)
}
