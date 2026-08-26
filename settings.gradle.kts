rootProject.name = "ktor-typed-routing"

include(":core", ":openapi", ":example")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

project(":core").name = "ktor-typed-routing-core"
project(":openapi").name = "ktor-typed-routing-openapi"
project(":example").name = "ktor-typed-routing-example"
