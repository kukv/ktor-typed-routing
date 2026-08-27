plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

// precompiled script plugin では型安全な libs アクセサが使えないため、カタログを明示的に取得する
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

group = "jp.kukv.ktor-typed-routing"
version = "0.1.0-SNAPSHOT"

kotlin {
    explicitApi()
    jvmToolchain(21)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// ktor-server-test-host が引き込む Apache HttpComponents に既知の脆弱性があるため、
// テスト用クラスパスのみ修正済みバージョンへ引き上げる
dependencies {
    constraints {
        add("testImplementation", libs.findLibrary("httpcore5").get())
        add("testImplementation", libs.findLibrary("httpcore5-h2").get())
        add("testImplementation", libs.findLibrary("httpclient5").get())
    }
}

// SCA(OSV-Scanner)が読む gradle.lockfile を生成するため依存関係をロックする
dependencyLocking {
    lockAllConfigurations()
}

// ロックファイル更新用: ./gradlew resolveAndLockAll --write-locks
tasks.register("resolveAndLockAll") {
    notCompatibleWithConfigurationCache("解決時点で configuration を絞り込むため")
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) {
            "--write-locks を付けて実行してください"
        }
    }
    doLast {
        configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
    }
}
