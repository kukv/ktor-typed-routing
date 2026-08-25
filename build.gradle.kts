plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

subprojects {
    group = "jp.kukv"
    version = "0.1.0-SNAPSHOT"

    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
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
            add("testImplementation", rootProject.libs.httpcore5)
            add("testImplementation", rootProject.libs.httpcore5.h2)
            add("testImplementation", rootProject.libs.httpclient5)
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
}
