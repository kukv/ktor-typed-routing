import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode

plugins {
    id("ktor.kotlin-conventions")
    application
}

description = "ktor-typed-routing の使い方を示すサンプルアプリ"

// サンプルは公開 API ではないので、explicitApi の可視性修飾子までは求めない。
kotlin {
    explicitApi = ExplicitApiMode.Disabled
}

application {
    mainClass.set("jp.kukv.typedrouting.example.MainKt")
}

dependencies {
    // ktor-server-netty が引き込む Netty に既知の脆弱性があるため、修正済みバージョンへ引き上げる
    implementation(platform(libs.netty.bom))

    implementation(project(":core"))
    implementation(project(":openapi"))
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.swagger)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.logback.classic)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
}
