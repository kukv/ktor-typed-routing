rootProject.name = "ktor-typed-routing"

include(":core", ":openapi", ":example")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

