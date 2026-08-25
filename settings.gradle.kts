rootProject.name = "ktor-typed-routing"

include(":core", ":openapi")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

project(":core").name = "ktor-typed-routing-core"
project(":openapi").name = "ktor-typed-routing-openapi"
