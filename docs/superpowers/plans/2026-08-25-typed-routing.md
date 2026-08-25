# ktor-typed-routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ktor 標準 `Route` ツリーの上に、リクエストを単一の型にバインドし、戻り値をレスポンスにし、OpenAPI 定義を自動生成する型付きエンドポイント DSL を実装する。

**Architecture:** 標準 routing を置き換えず、`Route` の拡張関数として実装する。各エンドポイントは `createChild(HttpMethodRouteSelector(method))` で標準ノードを作り、`attributes` に `EndpointSpec` を載せ、標準の `handle {}` にバインド・検証・ハンドラ呼び出しを詰める。リクエストのバインドは `kotlinx.serialization` の `AbstractDecoder` を自作して行い、同じ `SerialDescriptor` を OpenAPI 生成の情報源にも使う。エラー処理は行わず、例外はすべて素通しして StatusPages に委ねる。

**Tech Stack:** Kotlin 2.3.21 / Gradle Kotlin DSL / Ktor 3.5.2 / kotlinx-serialization 1.11.0 / kotlin-test + JUnit 5 / ktor-server-test-host

**Spec:** `docs/superpowers/specs/2026-08-25-typed-routing-design.md`

## Global Constraints

- Kotlin `2.3.21`、JVM toolchain `21`、`explicitApi()` を有効にする
- Ktor `3.5.2`、kotlinx-serialization `1.11.0`（Ktor 3.5.2 の推移的依存と一致させる）
- JVM 専用。`:core` では `kotlin-reflect` を使わず、型情報はすべて `SerialDescriptor` から取る。
  `:openapi` は `kotlin-reflect` を使ってよい（公式のスキーマ推論が `KType` しか受け付けないため）
- group id は `jp.kukv`。パッケージは `jp.kukv.typedrouting` / `jp.kukv.typedrouting.openapi`
- Gradle プロジェクトパスは `:core` / `:openapi`。artifact 名は `ktor-typed-routing-core` / `ktor-typed-routing-openapi`
- `core` は `ktor-server-status-pages` にも、いかなるバリデーションライブラリにも依存しない
- `core` は例外を捕まえない。`try`/`catch` を書いてよいのは violation の収集と `RequestBindingException` への変換のみ
- 公開 API にはすべて KDoc を付ける（`explicitApi()` が警告するため）
- リクエストボディは JSON のみ。復号は `StringFormat` 経由で行い、差し替え可能にしておく

---

## File Structure

### `:core` — `core/src/main/kotlin/jp/kukv/typedrouting/`

| ファイル | 責務 |
|---|---|
| `Annotations.kt` | `@Path` / `@Query` / `@Header` / `@Cookie` / `@Body`。すべて `@SerialInfo` |
| `Exceptions.kt` | `Violation` / `RequestBindingException` / `ValidationException` |
| `ParameterSource.kt` | 文字列パラメータの取得口を 1 つのインタフェースに抽象化 |
| `ElementOrigin.kt` | descriptor の要素 1 つがどこから来るかの判定（アノテーション + kind） |
| `RequestDecoder.kt` | `AbstractDecoder` 実装。スカラー / 複数値 / グループ / ボディを扱う |
| `RequestBinder.kt` | `suspend bind()`。ボディの先読みと `RequestBindingException` への変換 |
| `EndpointSpec.kt` | エンドポイントのメタデータと、その起動時検証 |
| `Around.kt` | `Around` / `EndpointContext` |
| `Validation.kt` | `ValidationScope` / `reject` |
| `EndpointBuilder.kt` | builder。`summary` / `status` / `errors` / `validate` / `around` / `handle` |
| `TypedRouting.kt` | `ApplicationPlugin`。`json` と アプリ全体の `around` を保持 |
| `EndpointDsl.kt` | `get` / `post` / ... と汎用形 `route<Req, Res>` |

### `:openapi` — `openapi/src/main/kotlin/jp/kukv/typedrouting/openapi/`

| ファイル | 責務 |
|---|---|
| `ParameterFlattening.kt` | Req の `KType` を `kotlin-reflect` で走査し `parameters` に平坦化 |
| `OpenApiBridge.kt` | `EndpointSpec` を公式の `Route.describe {}` に流し込む |

### `:openapi` が `KType` ベースになる理由

Ktor 3.5.2 のスキーマ推論の公開入口は `JsonSchemaInference.buildSchema(KType)` の
1 つだけである（`javap` で確認済み）。`KotlinxSerializerJsonSchemaInference` には
`buildSchemaFromDescriptor` もあるが `internal` で外から呼べない。

したがって `:openapi` は `SerialDescriptor` ではなく `KType` を入力とし、
アノテーションの読み取りと平坦化も `kotlin-reflect` で行う。
1 モジュール 1 機構に揃え、descriptor と reflection の二重管理を避ける。

`:core` は従来どおり `SerialDescriptor` だけで完結し、reflection を使わない。

---

## Task 1: Gradle プロジェクトのセットアップ

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `core/build.gradle.kts`
- Create: `openapi/build.gradle.kts`
- Test: `core/src/test/kotlin/jp/kukv/typedrouting/SetupTest.kt`

**Interfaces:**
- Consumes: なし
- Produces: `:core` と `:openapi` の 2 プロジェクト。`:openapi` は `:core` に `api` 依存する

- [ ] **Step 1: バージョンカタログを作る**

`gradle/libs.versions.toml`:

```toml
[versions]
kotlin = "2.3.21"
ktor = "3.5.2"
serialization = "1.11.0"
logback = "1.5.13"

[libraries]
ktor-server-core = { module = "io.ktor:ktor-server-core", version.ref = "ktor" }
ktor-server-routing-openapi = { module = "io.ktor:ktor-server-routing-openapi", version.ref = "ktor" }
ktor-server-test-host = { module = "io.ktor:ktor-server-test-host", version.ref = "ktor" }
ktor-server-status-pages = { module = "io.ktor:ktor-server-status-pages", version.ref = "ktor" }
ktor-server-auth = { module = "io.ktor:ktor-server-auth", version.ref = "ktor" }
ktor-server-content-negotiation = { module = "io.ktor:ktor-server-content-negotiation", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }
kotlin-reflect = { module = "org.jetbrains.kotlin:kotlin-reflect", version.ref = "kotlin" }
logback-classic = { module = "ch.qos.logback:logback-classic", version.ref = "logback" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 2: settings とルートビルドを作る**

`settings.gradle.kts`:

```kotlin
rootProject.name = "ktor-typed-routing"

include(":core", ":openapi")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
```

`build.gradle.kts`:

```kotlin
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
}
```

- [ ] **Step 3: サブプロジェクトのビルドを作る**

`core/build.gradle.kts`:

```kotlin
description = "ktor-typed-routing-core"

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
```

`openapi/build.gradle.kts`:

```kotlin
description = "ktor-typed-routing-openapi"

dependencies {
    api(project(":core"))
    api(libs.ktor.server.routing.openapi)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.logback.classic)
}
```

`core/build.gradle.kts` と `openapi/build.gradle.kts` で artifact 名を揃えるため、
`settings.gradle.kts` の末尾に次を追記する:

```kotlin
project(":core").name = "ktor-typed-routing-core"
project(":openapi").name = "ktor-typed-routing-openapi"
```

- [ ] **Step 4: セットアップの検証テストを書く**

`core/src/test/kotlin/jp/kukv/typedrouting/SetupTest.kt`:

```kotlin
package jp.kukv.typedrouting

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class SetupTest {
    @Test
    fun `standard routing works in the test harness`() = testApplication {
        application {
            routing {
                get("/ping") { call.respondText("pong") }
            }
        }

        val response = client.get("/ping")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("pong", response.bodyAsText())
    }
}
```

- [ ] **Step 5: テストを実行して通ることを確認する**

Run: `./gradlew :ktor-typed-routing-core:test --tests '*SetupTest*'`
Expected: PASS

Gradle wrapper が未生成の場合は先に `gradle wrapper --gradle-version 8.14` を実行する。

- [ ] **Step 6: コミット**

```bash
git add settings.gradle.kts build.gradle.kts gradle core openapi
git commit -m "build: Gradle プロジェクトをセットアップ"
```

---

## Task 2: アノテーションと例外型

**Files:**
- Create: `core/src/main/kotlin/jp/kukv/typedrouting/Annotations.kt`
- Create: `core/src/main/kotlin/jp/kukv/typedrouting/Exceptions.kt`
- Test: `core/src/test/kotlin/jp/kukv/typedrouting/AnnotationsTest.kt`

**Interfaces:**
- Consumes: なし
- Produces:
  - `annotation class Path(val name: String = "")`
  - `annotation class Query(val name: String = "", val prefix: String = "")`
  - `annotation class Header(val name: String = "")`
  - `annotation class Cookie(val name: String = "")`
  - `annotation class Body`
  - `data class Violation(val path: String, val message: String)`
  - `class RequestBindingException(val violations: List<Violation>) : Exception`
  - `class ValidationException(val violations: List<Violation>) : Exception`

- [ ] **Step 1: 失敗するテストを書く**

`core/src/test/kotlin/jp/kukv/typedrouting/AnnotationsTest.kt`:

```kotlin
package jp.kukv.typedrouting

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnnotationsTest {
    @Serializable
    private data class Sample(
        @Path val orgId: Long,
        @Query("q") val keyword: String?,
        @Header("X-Trace-Id") val traceId: String?,
        @Body val payload: String,
    )

    @Test
    fun `annotations are readable from the serial descriptor`() {
        val descriptor = serializer<Sample>().descriptor

        assertTrue(descriptor.getElementAnnotations(0).any { it is Path })
        assertEquals("q", descriptor.getElementAnnotations(1).filterIsInstance<Query>().single().name)
        assertEquals("X-Trace-Id", descriptor.getElementAnnotations(2).filterIsInstance<Header>().single().name)
        assertTrue(descriptor.getElementAnnotations(3).any { it is Body })
    }

    @Test
    fun `violations carry path and message`() {
        val e = RequestBindingException(listOf(Violation("page", "must be an integer")))
        assertEquals(1, e.violations.size)
        assertEquals("page", e.violations.single().path)
    }
}
```

- [ ] **Step 2: テストを実行して失敗することを確認する**

Run: `./gradlew :ktor-typed-routing-core:test --tests '*AnnotationsTest*'`
Expected: コンパイルエラー（`Path` などが未解決）

- [ ] **Step 3: アノテーションを実装する**

`core/src/main/kotlin/jp/kukv/typedrouting/Annotations.kt`:

```kotlin
package jp.kukv.typedrouting

import kotlinx.serialization.SerialInfo

/** パスパラメータから値を取ることを示す。[name] が空ならプロパティ名を使う。 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Path(val name: String = "")

/**
 * クエリパラメータから値を取ることを示す。[name] が空ならプロパティ名を使う。
 * 構造型に付けた場合はグループとして再帰的にバインドし、各要素の名前に [prefix] を付ける。
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Query(val name: String = "", val prefix: String = "")

/** リクエストヘッダから値を取ることを示す。[name] が空ならプロパティ名を使う。 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Header(val name: String = "", val prefix: String = "")

/** クッキーから値を取ることを示す。[name] が空ならプロパティ名を使う。 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Cookie(val name: String = "", val prefix: String = "")

/** リクエストボディから値を取ることを示す。1 つの型に 1 つだけ指定できる。 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Body
```

- [ ] **Step 4: 例外型を実装する**

`core/src/main/kotlin/jp/kukv/typedrouting/Exceptions.kt`:

```kotlin
package jp.kukv.typedrouting

/**
 * 入力の 1 件の違反。
 *
 * @param path 違反したパラメータの名前
 * @param message 人間向けの説明
 */
public data class Violation(
    public val path: String,
    public val message: String,
)

/**
 * リクエストを型にバインドできなかったことを表す。
 *
 * ステータスコードは持たない。StatusPages 側で割り当てる。
 */
public class RequestBindingException(
    public val violations: List<Violation>,
) : Exception("request binding failed: " + violations.joinToString { "${it.path}: ${it.message}" })

/**
 * `validate` ブロックで違反が見つかったことを表す。
 *
 * ステータスコードは持たない。StatusPages 側で割り当てる。
 */
public class ValidationException(
    public val violations: List<Violation>,
) : Exception("validation failed: " + violations.joinToString { "${it.path}: ${it.message}" })
```

- [ ] **Step 5: テストを実行して通ることを確認する**

Run: `./gradlew :ktor-typed-routing-core:test --tests '*AnnotationsTest*'`
Expected: PASS

- [ ] **Step 6: コミット**

```bash
git add core/src/main/kotlin/jp/kukv/typedrouting/Annotations.kt \
        core/src/main/kotlin/jp/kukv/typedrouting/Exceptions.kt \
        core/src/test/kotlin/jp/kukv/typedrouting/AnnotationsTest.kt
git commit -m "feat: バインド用アノテーションと例外型を追加"
```

---

## Task 3: ParameterSource

**Files:**
- Create: `core/src/main/kotlin/jp/kukv/typedrouting/ParameterSource.kt`
- Test: `core/src/test/kotlin/jp/kukv/typedrouting/ParameterSourceTest.kt`

**Interfaces:**
- Consumes: なし
- Produces:
  - `internal fun interface ParameterSource { fun getAll(name: String): List<String>? }`
  - `internal class RequestSources(val path: ParameterSource, val query: ParameterSource, val header: ParameterSource, val cookie: ParameterSource)`
  - `internal fun RoutingCall.requestSources(): RequestSources`

- [ ] **Step 1: 失敗するテストを書く**

`core/src/test/kotlin/jp/kukv/typedrouting/ParameterSourceTest.kt`:

```kotlin
package jp.kukv.typedrouting

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.parametersOf
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ParameterSourceTest {
    @Test
    fun `parameters source returns all values and null when absent`() {
        val source = ParameterSource.of(parametersOf("tags", listOf("a", "b")))

        assertEquals(listOf("a", "b"), source.getAll("tags"))
        assertNull(source.getAll("missing"))
    }

    @Test
    fun `request sources read path query header and cookie`() = testApplication {
        application {
            routing {
                io.ktor.server.routing.route("/orgs/{orgId}") {
                    io.ktor.server.routing.get {
                        val sources = call.requestSources()
                        call.respondText(
                            buildString {
                                append(sources.path.getAll("orgId"))
                                append("|").append(sources.query.getAll("q"))
                                append("|").append(sources.header.getAll("X-Trace-Id"))
                                append("|").append(sources.cookie.getAll("session"))
                            },
                        )
                    }
                }
            }
        }

        val response = client.get("/orgs/42?q=hello") {
            header("X-Trace-Id", "t-1")
            header("Cookie", "session=abc")
        }
        assertEquals("[42]|[hello]|[t-1]|[abc]", response.bodyAsText())
    }
}
```

`bodyAsText` の import は `io.ktor.client.statement.bodyAsText`。

- [ ] **Step 2: テストを実行して失敗することを確認する**

Run: `./gradlew :ktor-typed-routing-core:test --tests '*ParameterSourceTest*'`
Expected: コンパイルエラー（`ParameterSource` が未解決）

- [ ] **Step 3: 実装する**

`core/src/main/kotlin/jp/kukv/typedrouting/ParameterSource.kt`:

```kotlin
package jp.kukv.typedrouting

import io.ktor.http.Headers
import io.ktor.http.Parameters
import io.ktor.server.request.cookies
import io.ktor.server.routing.RoutingCall

/**
 * 文字列パラメータの取得口。値が 1 つも無い場合は `null` を返し、
 * 「存在するが空文字」と区別する。
 */
internal fun interface ParameterSource {
    fun getAll(name: String): List<String>?

    companion object {
        fun of(parameters: Parameters): ParameterSource =
            ParameterSource { name -> parameters.getAll(name) }

        fun of(headers: Headers): ParameterSource =
            ParameterSource { name -> headers.getAll(name) }

        fun ofCookies(cookies: (String) -> String?): ParameterSource =
            ParameterSource { name -> cookies(name)?.let { listOf(it) } }

        val Empty: ParameterSource = ParameterSource { null }
    }
}

/** 1 リクエスト分の入力ソースをまとめたもの。 */
internal class RequestSources(
    val path: ParameterSource,
    val query: ParameterSource,
    val header: ParameterSource,
    val cookie: ParameterSource,
)

internal fun RoutingCall.requestSources(): RequestSources =
    RequestSources(
        path = ParameterSource.of(pathParameters),
        query = ParameterSource.of(queryParameters),
        header = ParameterSource.of(request.headers),
        cookie = ParameterSource.ofCookies { name -> request.cookies[name] },
    )
```

- [ ] **Step 4: テストを実行して通ることを確認する**

Run: `./gradlew :ktor-typed-routing-core:test --tests '*ParameterSourceTest*'`
Expected: PASS

- [ ] **Step 5: コミット**

```bash
git add core/src/main/kotlin/jp/kukv/typedrouting/ParameterSource.kt \
        core/src/test/kotlin/jp/kukv/typedrouting/ParameterSourceTest.kt
git commit -m "feat: 入力ソースの抽象 ParameterSource を追加"
```

---

## Task 4: ElementOrigin — 要素の由来判定

**Files:**
- Create: `core/src/main/kotlin/jp/kukv/typedrouting/ElementOrigin.kt`
- Test: `core/src/test/kotlin/jp/kukv/typedrouting/ElementOriginTest.kt`

**Interfaces:**
- Consumes: `Annotations.kt` の 5 つのアノテーション、`ParameterSource.kt` の `RequestSources`
- Produces:
  - `internal enum class SourceKind { PATH, QUERY, HEADER, COOKIE, BODY }`
  - `internal data class ElementOrigin(val kind: SourceKind, val name: String, val prefix: String)`
  - `internal fun SerialDescriptor.originOf(index: Int, inherited: SourceKind? = null): ElementOrigin`
  - `internal fun SerialDescriptor.isGroup(index: Int, inherited: SourceKind? = null): Boolean`
  - `internal fun RequestSources.sourceFor(kind: SourceKind): ParameterSource`

判定規則（spec 6.4）:

- `StructureKind.CLASS` / `StructureKind.OBJECT` → グループ（再帰）
- それ以外（`PrimitiveKind` / `SerialKind.ENUM` / `StructureKind.LIST` / インライン） → スカラー

- [ ] **Step 1: 失敗するテストを書く**

`core/src/test/kotlin/jp/kukv/typedrouting/ElementOriginTest.kt`:

```kotlin
package jp.kukv.typedrouting

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ElementOriginTest {
    @Serializable
    private data class Paging(val page: Int = 1, val limit: Int = 20)

    @Serializable
    private enum class Order { Asc, Desc }

    @JvmInline
    @Serializable
    private value class UserId(val raw: Long)

    @Serializable
    private data class Sample(
        @Path val orgId: Long,
        @Query("q") val keyword: String?,
        @Query val paging: Paging,
        @Query(prefix = "f.") val filters: Paging,
        @Query val tags: List<String>,
        @Query val order: Order,
        @Path val userId: UserId,
        @Header("X-Trace-Id") val traceId: String?,
        @Cookie val session: String?,
        @Body val payload: String,
    )

    private val descriptor = serializer<Sample>().descriptor

    @Test
    fun `explicit name wins over property name`() {
        assertEquals(ElementOrigin(SourceKind.QUERY, "q", ""), descriptor.originOf(1))
    }

    @Test
    fun `property name is used when the annotation name is empty`() {
        assertEquals(ElementOrigin(SourceKind.PATH, "orgId", ""), descriptor.originOf(0))
    }

    @Test
    fun `prefix is carried on the origin`() {
        assertEquals("f.", descriptor.originOf(3).prefix)
    }

    @Test
    fun `every source kind is recognised`() {
        assertEquals(SourceKind.HEADER, descriptor.originOf(7).kind)
        assertEquals(SourceKind.COOKIE, descriptor.originOf(8).kind)
        assertEquals(SourceKind.BODY, descriptor.originOf(9).kind)
    }

    @Test
    fun `group children inherit the parent source kind`() {
        val child = serializer<Paging>().descriptor
        assertEquals(
            ElementOrigin(SourceKind.QUERY, "page", ""),
            child.originOf(0, inherited = SourceKind.QUERY),
        )
    }

    @Test
    fun `structure kinds are groups and everything else is scalar`() {
        assertTrue(descriptor.isGroup(2), "data class is a group")
        assertFalse(descriptor.isGroup(0), "primitive is scalar")
        assertFalse(descriptor.isGroup(4), "List is scalar")
        assertFalse(descriptor.isGroup(5), "enum is scalar")
        assertFalse(descriptor.isGroup(6), "value class is scalar")
        assertFalse(descriptor.isGroup(9), "body is never a group")
    }
}
```

- [ ] **Step 2: テストを実行して失敗することを確認する**

Run: `./gradlew :ktor-typed-routing-core:test --tests '*ElementOriginTest*'`
Expected: コンパイルエラー（`ElementOrigin` が未解決）

- [ ] **Step 3: 実装する**

`core/src/main/kotlin/jp/kukv/typedrouting/ElementOrigin.kt`:

```kotlin
package jp.kukv.typedrouting

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind

/** 値をどこから取るか。 */
internal enum class SourceKind { PATH, QUERY, HEADER, COOKIE, BODY }

/**
 * 要素 1 つの由来。
 *
 * @param name パラメータ名。グループの場合は使わない
 * @param prefix グループの子要素に付ける接頭辞
 */
internal data class ElementOrigin(
    val kind: SourceKind,
    val name: String,
    val prefix: String,
)

/**
 * 要素の由来を判定する。
 *
 * グループの子要素にはアノテーションが付かないため、親のソース種別を [inherited] で受け取り、
 * その場合はプロパティ名をそのままパラメータ名として使う。
 * [inherited] が `null`（＝ルート）でアノテーションも無い場合は例外にする。
 * 由来を書き忘れたまま実行時に静かに欠落するのを防ぐため。
 */
internal fun SerialDescriptor.originOf(index: Int, inherited: SourceKind? = null): ElementOrigin {
    val propertyName = getElementName(index)
    val annotations = getElementAnnotations(index)

    annotations.forEach { annotation ->
        when (annotation) {
            is Path -> return ElementOrigin(SourceKind.PATH, annotation.name.ifEmpty { propertyName }, "")
            is Query -> return ElementOrigin(SourceKind.QUERY, annotation.name.ifEmpty { propertyName }, annotation.prefix)
            is Header -> return ElementOrigin(SourceKind.HEADER, annotation.name.ifEmpty { propertyName }, annotation.prefix)
            is Cookie -> return ElementOrigin(SourceKind.COOKIE, annotation.name.ifEmpty { propertyName }, annotation.prefix)
            is Body -> return ElementOrigin(SourceKind.BODY, propertyName, "")
        }
    }

    if (inherited != null) return ElementOrigin(inherited, propertyName, "")

    error(
        "Element '$propertyName' of '$serialName' has no source annotation. " +
            "Annotate it with @Path, @Query, @Header, @Cookie or @Body.",
    )
}

/**
 * 要素をグループとして再帰的にバインドするかどうか。
 *
 * 構造型（data class / object）だけがグループになる。
 * enum / value class / List / カスタム serializer はスカラーとして扱う。
 */
internal fun SerialDescriptor.isGroup(index: Int, inherited: SourceKind? = null): Boolean {
    if (originOf(index, inherited).kind == SourceKind.BODY) return false
    return when (getElementDescriptor(index).kind) {
        StructureKind.CLASS, StructureKind.OBJECT -> true
        else -> false
    }
}

internal fun RequestSources.sourceFor(kind: SourceKind): ParameterSource =
    when (kind) {
        SourceKind.PATH -> path
        SourceKind.QUERY -> query
        SourceKind.HEADER -> header
        SourceKind.COOKIE -> cookie
        SourceKind.BODY -> ParameterSource.Empty
    }
```

- [ ] **Step 4: テストを実行して通ることを確認する**

Run: `./gradlew :ktor-typed-routing-core:test --tests '*ElementOriginTest*'`
Expected: PASS

- [ ] **Step 5: コミット**

```bash
git add core/src/main/kotlin/jp/kukv/typedrouting/ElementOrigin.kt \
        core/src/test/kotlin/jp/kukv/typedrouting/ElementOriginTest.kt
git commit -m "feat: 要素の由来判定 ElementOrigin を追加"
```

---

## Task 5: RequestDecoder — スカラーとグループ

**Files:**
- Create: `core/src/main/kotlin/jp/kukv/typedrouting/RequestDecoder.kt`
- Test: `core/src/test/kotlin/jp/kukv/typedrouting/RequestDecoderTest.kt`

**Interfaces:**
- Consumes: `ElementOrigin.kt`、`ParameterSource.kt`、`Exceptions.kt`
- Produces:
  - `internal class BindingContext(val sources: RequestSources, val bodyText: String?, val format: StringFormat)` — `violations: MutableList<Violation>` を持つ
  - `internal class ObjectDecoder(descriptor, ctx, prefix, ownKind) : AbstractDecoder`
  - `internal fun <T> BindingContext.decode(deserializer: DeserializationStrategy<T>): T`

### 設計メモ（実装者向け）

`AbstractDecoder` は「要求された順に値を吐く」モデルである。`decodeElementIndex` で
次に復号する要素の index を返し、`DECODE_DONE` を返すと終了する。要素をスキップすると
kotlinx が既定値を埋める。必須要素をスキップすると `MissingFieldException` が飛ぶ。

これを利用して、欠落の扱い（spec 6.7）を次のように実装する。

| 状況 | 実装 |
|---|---|
| 値がある | index を返し、直後の `decodeXxx` で変換する |
| 欠落 かつ nullable | index を返し、`decodeNotNullMark()` で `false` を返して `null` にする |
| 欠落 かつ非 nullable | **スキップする。** 既定値があれば kotlinx が埋め、無ければ `MissingFieldException` になる |

`MissingFieldException` は欠落した必須要素を**まとめて**報告するので、
`RequestBinder` 側で捕まえて `Violation` に変換すればよい（Task 6）。

型変換の失敗はプリミティブについては `violations` に積んで既定値（`0` / `""` / `false`）を
返し、復号を続けて全件を集める。カスタム serializer が投げる例外は素通しし、
最初の 1 件で打ち切る。この非対称は spec 6.8 の「まとめて報告する」を
現実的な範囲で満たすための割り切りである。

- [ ] **Step 1: 失敗するテストを書く**

`core/src/test/kotlin/jp/kukv/typedrouting/RequestDecoderTest.kt`:

```kotlin
package jp.kukv.typedrouting

import io.ktor.http.parametersOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestDecoderTest {
    @Serializable
    private data class Paging(val page: Int = 1, val limit: Int = 20)

    @Serializable
    private enum class Order { Asc, Desc }

    @JvmInline
    @Serializable
    private value class UserId(val raw: Long)

    @Serializable
    private data class Scalars(
        @Query val name: String,
        @Query val count: Int,
        @Query val ratio: Double,
        @Query val enabled: Boolean,
        @Query val order: Order,
        @Query val userId: UserId,
        @Query val optional: String?,
        @Query val withDefault: Int = 7,
    )

    @Serializable
    private data class Grouped(
        @Query val paging: Paging,
        @Query(prefix = "f.") val filters: Paging,
    )

    private fun context(vararg pairs: Pair<String, List<String>>): BindingContext {
        val query = ParameterSource { name -> pairs.firstOrNull { it.first == name }?.second }
        return BindingContext(
            sources = RequestSources(
                path = ParameterSource.Empty,
                query = query,
                header = ParameterSource.Empty,
                cookie = ParameterSource.Empty,
            ),
            bodyText = null,
            format = Json,
        )
    }

    @Test
    fun `decodes every scalar kind`() {
        val ctx = context(
            "name" to listOf("alice"),
            "count" to listOf("3"),
            "ratio" to listOf("1.5"),
            "enabled" to listOf("true"),
            "order" to listOf("Desc"),
            "userId" to listOf("42"),
            "optional" to listOf("here"),
            "withDefault" to listOf("9"),
        )

        val result = ctx.decode(serializer<Scalars>())

        assertEquals("alice", result.name)
        assertEquals(3, result.count)
        assertEquals(1.5, result.ratio)
        assertEquals(true, result.enabled)
        assertEquals(Order.Desc, result.order)
        assertEquals(UserId(42), result.userId)
        assertEquals("here", result.optional)
        assertEquals(9, result.withDefault)
    }

    @Test
    fun `absent nullable becomes null and absent default keeps the default`() {
        val ctx = context(
            "name" to listOf("alice"),
            "count" to listOf("3"),
            "ratio" to listOf("1.5"),
            "enabled" to listOf("true"),
            "order" to listOf("Asc"),
            "userId" to listOf("1"),
        )

        val result = ctx.decode(serializer<Scalars>())

        assertNull(result.optional)
        assertEquals(7, result.withDefault)
    }

    @Test
    fun `conversion failures are collected instead of thrown`() {
        val ctx = context(
            "name" to listOf("alice"),
            "count" to listOf("abc"),
            "ratio" to listOf("xyz"),
            "enabled" to listOf("true"),
            "order" to listOf("Asc"),
            "userId" to listOf("1"),
        )

        ctx.decode(serializer<Scalars>())

        assertEquals(listOf("count", "ratio"), ctx.violations.map { it.path })
        assertTrue(ctx.violations.all { it.message.isNotBlank() })
    }

    @Test
    fun `groups are flattened without a prefix by default`() {
        val ctx = context(
            "page" to listOf("2"),
            "limit" to listOf("50"),
            "f.page" to listOf("3"),
        )

        val result = ctx.decode(serializer<Grouped>())

        assertEquals(Paging(page = 2, limit = 50), result.paging)
        assertEquals(Paging(page = 3, limit = 20), result.filters)
    }
}
```

- [ ] **Step 2: テストを実行して失敗することを確認する**

Run: `./gradlew :ktor-typed-routing-core:test --tests '*RequestDecoderTest*'`
Expected: コンパイルエラー（`BindingContext` が未解決）

- [ ] **Step 3: 実装する**

`core/src/main/kotlin/jp/kukv/typedrouting/RequestDecoder.kt`:

```kotlin
package jp.kukv.typedrouting

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.StringFormat
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.modules.SerializersModule

/** 1 リクエスト分の復号状態。違反を蓄積する。 */
internal class BindingContext(
    val sources: RequestSources,
    val bodyText: String?,
    val format: StringFormat,
) {
    val violations: MutableList<Violation> = mutableListOf()
}

internal fun <T> BindingContext.decode(deserializer: DeserializationStrategy<T>): T =
    ObjectDecoder(deserializer.descriptor, this, prefix = "", ownKind = null)
        .decodeSerializableValue(deserializer)

/**
 * 1 つの構造体を復号する。要素ごとに由来を判定し、対応する [ParameterSource] から値を引く。
 * 構造型の要素は同じソース・合成した接頭辞で再帰する。
 */
@OptIn(ExperimentalSerializationApi::class)
internal class ObjectDecoder(
    private val target: SerialDescriptor,
    private val ctx: BindingContext,
    private val prefix: String,
    /** グループの内側なら親のソース種別。ルートなら `null`。 */
    private val ownKind: SourceKind?,
) : AbstractDecoder() {

    override val serializersModule: SerializersModule get() = ctx.format.serializersModule

    private var index = -1
    private var pending: List<String>? = null

    private fun originAt(elementIndex: Int): ElementOrigin =
        target.originOf(elementIndex, ownKind)

    private fun valuesFor(elementIndex: Int): List<String>? {
        val origin = originAt(elementIndex)
        if (origin.kind == SourceKind.BODY) return null
        return ctx.sources.sourceFor(origin.kind).getAll(prefix + origin.name)
    }

    /** グループは子要素のどれか 1 つでも値があれば「存在する」とみなす。 */
    private fun groupHasAnyValue(elementIndex: Int): Boolean {
        val origin = originAt(elementIndex)
        val child = target.getElementDescriptor(elementIndex)
        val source = ctx.sources.sourceFor(origin.kind)
        val childPrefix = prefix + origin.prefix
        return (0 until child.elementsCount).any { i ->
            if (child.isGroup(i, origin.kind)) {
                true
            } else {
                source.getAll(childPrefix + child.originOf(i, origin.kind).name) != null
            }
        }
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        while (true) {
            index++
            if (index >= descriptor.elementsCount) return CompositeDecoder.DECODE_DONE

            val origin = originAt(index)
            if (origin.kind == SourceKind.BODY) return index
            if (descriptor.isGroup(index, ownKind)) {
                if (groupHasAnyValue(index)) return index
                if (descriptor.isElementOptional(index)) continue
                return index
            }

            val values = valuesFor(index)
            if (values != null) {
                pending = values
                return index
            }

            // 欠落。nullable なら null を供給する。
            if (descriptor.getElementDescriptor(index).isNullable) {
                pending = null
                return index
            }

            // 非 nullable の欠落はスキップし、既定値の補完（任意の場合）または
            // MissingFieldException（必須の場合）を kotlinx に任せる。
            // 必須の欠落をここで例外にしないのは、MissingFieldException が
            // 欠落した要素をまとめて報告してくれるためである（Task 7 で変換する）。
            continue
        }
    }

    override fun decodeNotNullMark(): Boolean = pending != null

    override fun decodeNull(): Nothing? = null

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        // decodeElementIndex で選ばれた要素がグループのとき、その子を同じソースで復号する。
        val origin = originAt(index)
        return ObjectDecoder(descriptor, ctx, prefix + origin.prefix, ownKind = origin.kind)
    }

    private fun current(): String = pending?.firstOrNull().orEmpty()

    private fun violate(message: String) {
        ctx.violations += Violation(prefix + originAt(index).name, message)
    }

    override fun decodeString(): String = current()

    override fun decodeInt(): Int =
        current().toIntOrNull() ?: run { violate("must be an integer"); 0 }

    override fun decodeLong(): Long =
        current().toLongOrNull() ?: run { violate("must be an integer"); 0L }

    override fun decodeShort(): Short =
        current().toShortOrNull() ?: run { violate("must be an integer"); 0 }

    override fun decodeByte(): Byte =
        current().toByteOrNull() ?: run { violate("must be an integer"); 0 }

    override fun decodeDouble(): Double =
        current().toDoubleOrNull() ?: run { violate("must be a number"); 0.0 }

    override fun decodeFloat(): Float =
        current().toFloatOrNull() ?: run { violate("must be a number"); 0f }

    override fun decodeBoolean(): Boolean =
        current().toBooleanStrictOrNull() ?: run { violate("must be true or false"); false }

    override fun decodeChar(): Char =
        current().singleOrNull() ?: run { violate("must be a single character"); ' ' }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        val text = current()
        val found = (0 until enumDescriptor.elementsCount)
            .firstOrNull { enumDescriptor.getElementName(it) == text }
        if (found != null) return found

        val allowed = (0 until enumDescriptor.elementsCount).joinToString { enumDescriptor.getElementName(it) }
        violate("must be one of: $allowed")
        return 0
    }
}
```

- [ ] **Step 4: テストを実行して通ることを確認する**

Run: `./gradlew :ktor-typed-routing-core:test --tests '*RequestDecoderTest*'`
Expected: PASS

- [ ] **Step 5: コミット**

```bash
git add core/src/main/kotlin/jp/kukv/typedrouting/RequestDecoder.kt \
        core/src/test/kotlin/jp/kukv/typedrouting/RequestDecoderTest.kt
git commit -m "feat: スカラーとグループを復号する RequestDecoder を追加"
```

---

## Task 6: RequestDecoder — 複数値とボディ

**Files:**
- Modify: `core/src/main/kotlin/jp/kukv/typedrouting/RequestDecoder.kt`
- Test: `core/src/test/kotlin/jp/kukv/typedrouting/RequestDecoderCollectionTest.kt`

**Interfaces:**
- Consumes: Task 5 の `ObjectDecoder` / `BindingContext`
- Produces: `ObjectDecoder` が `StructureKind.LIST` の要素と `@Body` の要素を扱えるようになる

- [ ] **Step 1: 失敗するテストを書く**

`core/src/test/kotlin/jp/kukv/typedrouting/RequestDecoderCollectionTest.kt`:

```kotlin
package jp.kukv.typedrouting

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

class RequestDecoderCollectionTest {
    @Serializable
    private data class Payload(val name: String, val age: Int)

    @Serializable
    private data class WithList(
        @Query val tags: List<String>,
        @Query val ids: List<Int> = emptyList(),
    )

    @Serializable
    private data class WithBody(
        @Path val orgId: Long,
        @Body val payload: Payload,
    )

    private fun context(
        query: Map<String, List<String>> = emptyMap(),
        path: Map<String, List<String>> = emptyMap(),
        bodyText: String? = null,
    ) = BindingContext(
        sources = RequestSources(
            path = ParameterSource { path[it] },
            query = ParameterSource { query[it] },
            header = ParameterSource.Empty,
            cookie = ParameterSource.Empty,
        ),
        bodyText = bodyText,
        format = Json,
    )

    @Test
    fun `repeated query parameters become a list`() {
        val ctx = context(query = mapOf("tags" to listOf("a", "b", "c")))

        val result = ctx.decode(serializer<WithList>())

        assertEquals(listOf("a", "b", "c"), result.tags)
        assertEquals(emptyList(), result.ids)
    }

    @Test
    fun `list elements are converted to the element type`() {
        val ctx = context(query = mapOf("tags" to listOf("x"), "ids" to listOf("1", "2")))

        val result = ctx.decode(serializer<WithList>())

        assertEquals(listOf(1, 2), result.ids)
    }

    @Test
    fun `body is decoded from the prefetched text`() {
        val ctx = context(
            path = mapOf("orgId" to listOf("7")),
            bodyText = """{"name":"alice","age":30}""",
        )

        val result = ctx.decode(serializer<WithBody>())

        assertEquals(7L, result.orgId)
        assertEquals(Payload("alice", 30), result.payload)
    }
}
```

- [ ] **Step 2: テストを実行して失敗することを確認する**

Run: `./gradlew :ktor-typed-routing-core:test --tests '*RequestDecoderCollectionTest*'`
Expected: FAIL（リストが空になる、またはボディの復号で例外）

- [ ] **Step 3: リストとボディの処理を追加する**

`ObjectDecoder` に `decodeSerializableElement` の override を追加する。

```kotlin
    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?,
    ): T {
        val origin = originAt(index)

        if (origin.kind == SourceKind.BODY) {
            val text = ctx.bodyText
            if (text.isNullOrEmpty()) {
                ctx.violations += Violation(origin.name, "request body is required")
                @Suppress("UNCHECKED_CAST")
                return null as T
            }
            return ctx.format.decodeFromString(deserializer, text)
        }

        if (descriptor.getElementDescriptor(index).kind == StructureKind.LIST) {
            val values = ctx.sources.sourceFor(origin.kind).getAll(prefix + origin.name).orEmpty()
            return MultiValueDecoder(values, ctx, origin.name).decodeSerializableValue(deserializer)
        }

        return super.decodeSerializableElement(descriptor, index, deserializer, previousValue)
    }
```

`import kotlinx.serialization.descriptors.StructureKind` を追加する。

同じファイルに複数値用のデコーダを追加する。

```kotlin
/** 同名で複数回現れたパラメータをコレクションとして復号する。 */
@OptIn(ExperimentalSerializationApi::class)
private class MultiValueDecoder(
    private val values: List<String>,
    private val ctx: BindingContext,
    private val name: String,
) : AbstractDecoder() {

    override val serializersModule: SerializersModule get() = ctx.format.serializersModule

    private var position = -1

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = values.size

    override fun decodeSequentially(): Boolean = true

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        position++
        return if (position >= values.size) CompositeDecoder.DECODE_DONE else position
    }

    private fun current(): String = values.getOrElse(position) { "" }

    private fun violate(message: String) {
        ctx.violations += Violation(name, message)
    }

    override fun decodeString(): String = current()

    override fun decodeInt(): Int =
        current().toIntOrNull() ?: run { violate("must be an integer"); 0 }

    override fun decodeLong(): Long =
        current().toLongOrNull() ?: run { violate("must be an integer"); 0L }

    override fun decodeDouble(): Double =
        current().toDoubleOrNull() ?: run { violate("must be a number"); 0.0 }

    override fun decodeBoolean(): Boolean =
        current().toBooleanStrictOrNull() ?: run { violate("must be true or false"); false }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        val text = current()
        val found = (0 until enumDescriptor.elementsCount)
            .firstOrNull { enumDescriptor.getElementName(it) == text }
        if (found != null) return found
        violate("must be one of: " + (0 until enumDescriptor.elementsCount).joinToString { enumDescriptor.getElementName(it) })
        return 0
    }
}
```

`decodeSequentially()` を `true` にすると kotlinx は `decodeCollectionSize` の
件数だけ順に値を要求するため、`decodeElementIndex` は使われない。両方実装しておくのは
kotlinx のバージョン差で経路が変わっても動くようにするためである。

`decodeElementIndex` の欠落判定も更新し、LIST 要素は値が 0 件でも
`isElementOptional` なら既定値に委ねるようにする。Task 5 の `valuesFor` は
そのまま使えるため変更は不要。

- [ ] **Step 4: テストを実行して通ることを確認する**

Run: `./gradlew :ktor-typed-routing-core:test --tests '*RequestDecoderCollectionTest*'`
Expected: PASS

- [ ] **Step 5: 既存のデコーダテストが壊れていないことを確認する**

Run: `./gradlew :ktor-typed-routing-core:test --tests '*RequestDecoder*'`
Expected: PASS

- [ ] **Step 6: コミット**

```bash
git add core/src/main/kotlin/jp/kukv/typedrouting/RequestDecoder.kt \
        core/src/test/kotlin/jp/kukv/typedrouting/RequestDecoderCollectionTest.kt
git commit -m "feat: 複数値パラメータとリクエストボディの復号に対応"
```

---

## Task 7: RequestBinder

**Files:**
- Create: `core/src/main/kotlin/jp/kukv/typedrouting/RequestBinder.kt`
- Test: `core/src/test/kotlin/jp/kukv/typedrouting/RequestBinderTest.kt`

**Interfaces:**
- Consumes: `RequestDecoder.kt`、`ParameterSource.kt`、`Exceptions.kt`
- Produces:
  - `internal fun SerialDescriptor.hasBodyElement(): Boolean`
  - `internal suspend fun <T> bindRequest(call: RoutingCall, deserializer: DeserializationStrategy<T>, format: StringFormat): T`
  - 欠落した必須要素は `MissingFieldException` を捕まえて `Violation` に変換する

- [ ] **Step 1: 失敗するテストを書く**

`core/src/test/kotlin/jp/kukv/typedrouting/RequestBinderTest.kt`:

```kotlin
package jp.kukv.typedrouting

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestBinderTest {
    @Serializable
    private data class Payload(val name: String)

    @Serializable
    private data class NeedsBody(@Body val payload: Payload)

    @Serializable
    private data class NoBody(@Query val q: String)

    @Serializable
    private data class Required(
        @Query val a: String,
        @Query val b: Int,
        @Query val c: String?,
    )

    @Test
    fun `body presence is detected from the descriptor`() {
        assertTrue(serializer<NeedsBody>().descriptor.hasBodyElement())
        assertFalse(serializer<NoBody>().descriptor.hasBodyElement())
    }

    @Test
    fun `missing required fields become violations`() {
        val ctx = BindingContext(
            sources = RequestSources(
                ParameterSource.Empty,
                ParameterSource.Empty,
                ParameterSource.Empty,
                ParameterSource.Empty,
            ),
            bodyText = null,
            format = Json,
        )

        val e = assertFailsWith<RequestBindingException> {
            ctx.decodeOrThrow(serializer<Required>())
        }

        assertEquals(setOf("a", "b"), e.violations.map { it.path }.toSet())
        assertTrue(e.violations.all { it.message == "is required" })
    }

    @Test
    fun `conversion violations are reported together`() {
        val query = mapOf("a" to listOf("ok"), "b" to listOf("nope"))
        val ctx = BindingContext(
            sources = RequestSources(
                ParameterSource.Empty,
                ParameterSource { query[it] },
                ParameterSource.Empty,
                ParameterSource.Empty,
            ),
            bodyText = null,
            format = Json,
        )

        val e = assertFailsWith<RequestBindingException> {
            ctx.decodeOrThrow(serializer<Required>())
        }

        assertEquals(listOf("b"), e.violations.map { it.path })
    }
}
```

- [ ] **Step 2: テストを実行して失敗することを確認する**

Run: `./gradlew :ktor-typed-routing-core:test --tests '*RequestBinderTest*'`
Expected: コンパイルエラー（`hasBodyElement` / `decodeOrThrow` が未解決）

- [ ] **Step 3: 実装する**

`core/src/main/kotlin/jp/kukv/typedrouting/RequestBinder.kt`:

```kotlin
package jp.kukv.typedrouting

import io.ktor.server.request.receiveText
import io.ktor.server.routing.RoutingCall
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.StringFormat
import kotlinx.serialization.descriptors.SerialDescriptor

/** この型がリクエストボディを必要とするかどうか。 */
@OptIn(ExperimentalSerializationApi::class)
internal fun SerialDescriptor.hasBodyElement(): Boolean =
    (0 until elementsCount).any { originOf(it).kind == SourceKind.BODY }

/**
 * 復号し、違反が 1 件でもあれば [RequestBindingException] にまとめて送出する。
 *
 * 欠落した必須要素は kotlinx が [MissingFieldException] としてまとめて報告するため、
 * それを捕まえて [Violation] に変換する。捕まえてよいのはこの 1 箇所だけである。
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun <T> BindingContext.decodeOrThrow(deserializer: DeserializationStrategy<T>): T {
    val result = try {
        decode(deserializer)
    } catch (cause: MissingFieldException) {
        val missing = cause.missingFields.map { Violation(it, "is required") }
        throw RequestBindingException(violations + missing)
    }

    if (violations.isNotEmpty()) throw RequestBindingException(violations.toList())
    return result
}

/**
 * リクエストを [T] にバインドする。
 *
 * ボディを必要とする型の場合のみ [RoutingCall.receiveText] を呼ぶ。
 * `Decoder` は suspend にできないため、ボディはここで先に読んでおく。
 */
internal suspend fun <T> bindRequest(
    call: RoutingCall,
    deserializer: DeserializationStrategy<T>,
    format: StringFormat,
): T {
    val bodyText = if (deserializer.descriptor.hasBodyElement()) call.receiveText() else null
    val ctx = BindingContext(call.requestSources(), bodyText, format)
    return ctx.decodeOrThrow(deserializer)
}
```

- [ ] **Step 4: テストを実行して通ることを確認する**

Run: `./gradlew :ktor-typed-routing-core:test --tests '*RequestBinderTest*'`
Expected: PASS

- [ ] **Step 5: コミット**

```bash
git add core/src/main/kotlin/jp/kukv/typedrouting/RequestBinder.kt \
        core/src/test/kotlin/jp/kukv/typedrouting/RequestBinderTest.kt
git commit -m "feat: リクエストバインドの入口 bindRequest を追加"
```

---

## Task 8: EndpointSpec と起動時検証

**Files:**
- Create: `core/src/main/kotlin/jp/kukv/typedrouting/EndpointSpec.kt`
- Test: `core/src/test/kotlin/jp/kukv/typedrouting/EndpointSpecTest.kt`

**Interfaces:**
- Consumes: `ElementOrigin.kt`
- Produces:
  - `public class EndpointSpec` — `method` / `summary` / `description` / `status` / `errors` / `requestType` / `responseType`
  - `public val EndpointSpecKey: AttributeKey<EndpointSpec>`
  - `internal fun SerialDescriptor.validateBindingShape()` — グループ内 `@Body` と名前衝突を検出

- [ ] **Step 1: 失敗するテストを書く**

`core/src/test/kotlin/jp/kukv/typedrouting/EndpointSpecTest.kt`:

```kotlin
package jp.kukv.typedrouting

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EndpointSpecTest {
    @Serializable
    private data class Paging(val page: Int = 1, val limit: Int = 20)

    @Serializable
    private data class Range(val page: Int = 0)

    @Serializable
    private data class BadGroup(@Body val payload: String)

    @Serializable
    private data class Colliding(
        @Query val paging: Paging,
        @Query val range: Range,
    )

    @Serializable
    private data class BodyInGroup(
        @Query val nested: BadGroup,
    )

    @Serializable
    private data class Fine(
        @Query val paging: Paging,
        @Query(prefix = "r.") val range: Range,
    )

    @Test
    fun `name collisions across groups are rejected`() {
        val e = assertFailsWith<IllegalStateException> {
            serializer<Colliding>().descriptor.validateBindingShape()
        }
        assertTrue(e.message!!.contains("page"))
    }

    @Test
    fun `body inside a group is rejected`() {
        val e = assertFailsWith<IllegalStateException> {
            serializer<BodyInGroup>().descriptor.validateBindingShape()
        }
        assertTrue(e.message!!.contains("@Body"))
    }

    @Test
    fun `a prefix resolves the collision`() {
        serializer<Fine>().descriptor.validateBindingShape()
    }
}
```

- [ ] **Step 2: テストを実行して失敗することを確認する**

Run: `./gradlew :ktor-typed-routing-core:test --tests '*EndpointSpecTest*'`
Expected: コンパイルエラー（`validateBindingShape` が未解決）

- [ ] **Step 3: 実装する**

`core/src/main/kotlin/jp/kukv/typedrouting/EndpointSpec.kt`:

```kotlin
package jp.kukv.typedrouting

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.util.AttributeKey
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlin.reflect.KType

/**
 * エンドポイントのメタデータ。OpenAPI 生成はこれだけを読む。
 *
 * @param errors ドキュメントに出すエラーレスポンスの宣言。実行時のマッピングには関与しない
 * @param requestType / @param responseType `:openapi` がスキーマを起こすために使う。
 *   Ktor のスキーマ推論は `KType` しか受け付けないため、`SerialDescriptor` ではなく `KType` を持つ
 */
public class EndpointSpec(
    public val method: HttpMethod,
    public val summary: String?,
    public val description: String?,
    public val status: HttpStatusCode,
    public val errors: List<Pair<HttpStatusCode, KType>>,
    public val requestType: KType?,
    public val responseType: KType?,
)

/** ルートに載せた [EndpointSpec] を引くためのキー。 */
public val EndpointSpecKey: AttributeKey<EndpointSpec> = AttributeKey("TypedRoutingEndpointSpec")

/**
 * バインド対象の形を検証する。違反は起動時に例外にする。
 *
 * - グループの中に `@Body` を書くことはできない
 * - 平坦化した結果、同じソースで同じ名前になる要素があってはならない
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun SerialDescriptor.validateBindingShape() {
    val seen = mutableMapOf<Pair<SourceKind, String>, String>()

    fun walk(descriptor: SerialDescriptor, prefix: String, inherited: SourceKind?, trail: String) {
        for (i in 0 until descriptor.elementsCount) {
            val origin = descriptor.originOf(i, inherited)
            val path = if (trail.isEmpty()) descriptor.getElementName(i) else "$trail.${descriptor.getElementName(i)}"

            if (origin.kind == SourceKind.BODY) {
                check(inherited == null) {
                    "@Body is not allowed inside a group (at '$path' of '$serialName'). " +
                        "A group must stay within a single input source."
                }
                continue
            }

            if (descriptor.isGroup(i, inherited)) {
                walk(
                    descriptor = descriptor.getElementDescriptor(i),
                    prefix = prefix + origin.prefix,
                    inherited = origin.kind,
                    trail = path,
                )
                continue
            }

            val key = origin.kind to (prefix + origin.name)
            val previous = seen.put(key, path)
            check(previous == null) {
                "Duplicate ${origin.kind} parameter '${prefix + origin.name}' in '$serialName' " +
                    "(declared at '$previous' and '$path'). Use @Query(prefix = ...) to disambiguate."
            }
        }
    }

    walk(this, prefix = "", inherited = null, trail = "")
}
```

- [ ] **Step 4: テストを実行して通ることを確認する**

Run: `./gradlew :ktor-typed-routing-core:test --tests '*EndpointSpecTest*'`
Expected: PASS

- [ ] **Step 5: コミット**

```bash
git add core/src/main/kotlin/jp/kukv/typedrouting/EndpointSpec.kt \
        core/src/test/kotlin/jp/kukv/typedrouting/EndpointSpecTest.kt
git commit -m "feat: EndpointSpec と起動時のバインド形状検証を追加"
```

---

## Task 9: TypedRouting プラグインと Around

**Files:**
- Create: `core/src/main/kotlin/jp/kukv/typedrouting/Around.kt`
- Create: `core/src/main/kotlin/jp/kukv/typedrouting/TypedRouting.kt`
- Test: `core/src/test/kotlin/jp/kukv/typedrouting/TypedRoutingPluginTest.kt`

**Interfaces:**
- Consumes: `EndpointSpec.kt`
- Produces:
  - `public class EndpointContext` — `call` / `spec` / `request` / `status`
  - `public fun interface Around { suspend fun invoke(ctx: EndpointContext, proceed: suspend () -> Any?): Any? }`
  - `public class TypedRoutingConfig` — `var json: Json`、`fun around(interceptor: Around)`
  - `public val TypedRouting: ApplicationPlugin<TypedRoutingConfig>`
  - `internal val TypedRoutingConfigKey: AttributeKey<ResolvedTypedRoutingConfig>`
  - `internal class ResolvedTypedRoutingConfig(val json: Json, val around: List<Around>)`

- [ ] **Step 1: 失敗するテストを書く**

`core/src/test/kotlin/jp/kukv/typedrouting/TypedRoutingPluginTest.kt`:

```kotlin
package jp.kukv.typedrouting

import io.ktor.client.request.get
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TypedRoutingPluginTest {
    @Test
    fun `plugin exposes its resolved configuration`() = testApplication {
        application {
            install(TypedRouting) {
                json = Json { ignoreUnknownKeys = true }
                around { _, proceed -> proceed() }
            }
            routing {
                get("/config") {
                    val resolved = call.application.typedRoutingConfig()
                    call.respondText("${resolved.around.size}")
                }
            }
        }

        assertEquals("1", client.get("/config").bodyAsText())
    }

    @Test
    fun `missing plugin is reported clearly`() = testApplication {
        application {
            routing {
                get("/config") {
                    val message = assertFailsWith<IllegalStateException> {
                        call.application.typedRoutingConfig()
                    }.message
                    call.respondText(message.orEmpty())
                }
            }
        }

        val body = client.get("/config").bodyAsText()
        assertEquals(true, body.contains("install(TypedRouting)"))
    }
}
```

`bodyAsText` の import は `io.ktor.client.statement.bodyAsText`。

- [ ] **Step 2: テストを実行して失敗することを確認する**

Run: `./gradlew :ktor-typed-routing-core:test --tests '*TypedRoutingPluginTest*'`
Expected: コンパイルエラー（`TypedRouting` が未解決）

- [ ] **Step 3: Around を実装する**

`core/src/main/kotlin/jp/kukv/typedrouting/Around.kt`:

```kotlin
package jp.kukv.typedrouting

import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.RoutingCall

/**
 * `around` から見えるエンドポイントの状態。
 *
 * [request] と [status] は `proceed()` の後にしか埋まらない。
 * バインド・検証・ハンドラの実行がすべて `proceed()` の内側で起きるためである。
 */
public class EndpointContext internal constructor(
    public val call: RoutingCall,
    public val spec: EndpointSpec,
) {
    public var request: Any? = null
        internal set

    public var status: HttpStatusCode? = null
        internal set
}

/**
 * エンドポイントの実行を包む。
 *
 * `proceed()` の戻り値は `call.respond` される成功ボディである。
 * 例外は捕まえずに素通しする。ログのために捕まえた場合は必ず再スローすること。
 * 本プラグインはエラー処理を行わず、StatusPages に委ねる。
 */
public fun interface Around {
    public suspend fun invoke(ctx: EndpointContext, proceed: suspend () -> Any?): Any?
}
```

- [ ] **Step 4: プラグインを実装する**

`core/src/main/kotlin/jp/kukv/typedrouting/TypedRouting.kt`:

```kotlin
package jp.kukv.typedrouting

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json

/** [TypedRouting] の設定。 */
public class TypedRoutingConfig {
    /** リクエストボディの復号とパラメータの型変換に使う。 */
    public var json: Json = Json

    internal val interceptors: MutableList<Around> = mutableListOf()

    /** すべてのエンドポイントを包むインターセプタを登録する。登録順に外側から適用される。 */
    public fun around(interceptor: Around) {
        interceptors += interceptor
    }
}

internal class ResolvedTypedRoutingConfig(
    val json: Json,
    val around: List<Around>,
)

internal val TypedRoutingConfigKey: AttributeKey<ResolvedTypedRoutingConfig> =
    AttributeKey("TypedRoutingConfig")

/**
 * 型付きエンドポイント DSL の設定を保持するプラグイン。
 *
 * エンドポイントを定義する前に `install(TypedRouting)` しておく必要がある。
 */
public val TypedRouting: ApplicationPlugin<TypedRoutingConfig> =
    createApplicationPlugin("TypedRouting", ::TypedRoutingConfig) {
        application.attributes.put(
            TypedRoutingConfigKey,
            ResolvedTypedRoutingConfig(pluginConfig.json, pluginConfig.interceptors.toList()),
        )
    }

internal fun Application.typedRoutingConfig(): ResolvedTypedRoutingConfig =
    attributes.getOrNull(TypedRoutingConfigKey)
        ?: error("TypedRouting plugin is not installed. Call install(TypedRouting) in your application module.")
```

`typedRoutingConfig()` はテストから呼ぶため `internal` だが、テストソースセットは
同じモジュールなので参照できる。

- [ ] **Step 5: テストを実行して通ることを確認する**

Run: `./gradlew :ktor-typed-routing-core:test --tests '*TypedRoutingPluginTest*'`
Expected: PASS

- [ ] **Step 6: コミット**

```bash
git add core/src/main/kotlin/jp/kukv/typedrouting/Around.kt \
        core/src/main/kotlin/jp/kukv/typedrouting/TypedRouting.kt \
        core/src/test/kotlin/jp/kukv/typedrouting/TypedRoutingPluginTest.kt
git commit -m "feat: TypedRouting プラグインと Around インターセプタを追加"
```

---

## Task 10: Validation と EndpointBuilder

**Files:**
- Create: `core/src/main/kotlin/jp/kukv/typedrouting/Validation.kt`
- Create: `core/src/main/kotlin/jp/kukv/typedrouting/EndpointBuilder.kt`
- Test: `core/src/test/kotlin/jp/kukv/typedrouting/ValidationTest.kt`

**Interfaces:**
- Consumes: `Exceptions.kt`、`Around.kt`、`EndpointSpec.kt`
- Produces:
  - `public class ValidationScope { public fun reject(path: String, message: String) }`
  - `public class EndpointBuilder<Req, Res>` — `summary` / `description` / `status` / `errors()` / `validate()` / `around()` / `handle()`
  - `internal suspend fun <Req> EndpointBuilder<Req, *>.runValidation(request: Req)`

- [ ] **Step 1: 失敗するテストを書く**

`core/src/test/kotlin/jp/kukv/typedrouting/ValidationTest.kt`:

```kotlin
package jp.kukv.typedrouting

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ValidationTest {
    @Test
    fun `rejections accumulate into one exception`() = runTest {
        val scope = ValidationScope()
        scope.reject("page", "must be >= 1")
        scope.reject("limit", "must be 1..100")

        assertEquals(2, scope.violations.size)
        assertEquals(listOf("page", "limit"), scope.violations.map { it.path })
    }

    @Test
    fun `no rejection means no violations`() = runTest {
        val scope = ValidationScope()
        assertEquals(0, scope.violations.size)
    }
}
```

`runTest` を使うため `core/build.gradle.kts` の `testImplementation` に
`org.jetbrains.kotlinx:kotlinx-coroutines-test` を追加し、`libs.versions.toml` にも
`kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version = "1.11.0" }`
を追加する。

- [ ] **Step 2: テストを実行して失敗することを確認する**

Run: `./gradlew :ktor-typed-routing-core:test --tests '*ValidationTest*'`
Expected: コンパイルエラー（`ValidationScope` が未解決）

- [ ] **Step 3: Validation を実装する**

`core/src/main/kotlin/jp/kukv/typedrouting/Validation.kt`:

```kotlin
package jp.kukv.typedrouting

/**
 * `validate` ブロックの受け皿。違反を蓄積する。
 *
 * ブロックを抜けた時点で 1 件でもあれば [ValidationException] になる。
 */
public class ValidationScope {
    internal val violations: MutableList<Violation> = mutableListOf()

    /** 違反を 1 件記録する。ブロックの実行は止まらない。 */
    public fun reject(path: String, message: String) {
        violations += Violation(path, message)
    }
}
```

- [ ] **Step 4: EndpointBuilder を実装する**

`core/src/main/kotlin/jp/kukv/typedrouting/EndpointBuilder.kt`:

```kotlin
package jp.kukv.typedrouting

import io.ktor.http.HttpStatusCode
import io.ktor.util.KtorDsl
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * エンドポイントの定義を組み立てる。
 *
 * `handle` は必須で、呼ばずに定義を終えると起動時に例外になる。
 */
@KtorDsl
public class EndpointBuilder<Req, Res> internal constructor() {
    /** OpenAPI の `summary`。 */
    public var summary: String? = null

    /** OpenAPI の `description`。 */
    public var description: String? = null

    /**
     * 成功時のステータスコード。
     * 既定は `Res` が `Unit` なら `204 No Content`、それ以外は `200 OK`。
     */
    public var status: HttpStatusCode? = null

    @PublishedApi
    internal val declaredErrors: MutableList<Pair<HttpStatusCode, KType>> = mutableListOf()
    internal val interceptors: MutableList<Around> = mutableListOf()
    internal var validator: (suspend ValidationScope.(Req) -> Unit)? = null
    internal var handler: (suspend (Req) -> Res)? = null

    /**
     * ドキュメントに出すエラーレスポンスを宣言する。`error<ErrorBody>(NotFound)` のように使う。
     * 実行時のマッピングには関与しない。エラー処理は StatusPages が行う。
     *
     * `inline` にするのは `KType` を捕まえるため。`declaredErrors` は `@PublishedApi internal` にする。
     */
    public inline fun <reified T> error(status: HttpStatusCode) {
        declaredErrors += status to typeOf<T>()
    }

    /** このエンドポイントだけを包むインターセプタを登録する。 */
    public fun around(interceptor: Around) {
        interceptors += interceptor
    }

    /** バインド後のリクエストを検証する。違反は [ValidationScope.reject] で記録する。 */
    public fun validate(block: suspend ValidationScope.(Req) -> Unit) {
        validator = block
    }

    /** リクエストを処理し、レスポンスボディを返す。 */
    public fun handle(block: suspend (Req) -> Res) {
        handler = block
    }
}

internal suspend fun <Req> EndpointBuilder<Req, *>.runValidation(request: Req) {
    val block = validator ?: return
    val scope = ValidationScope()
    scope.block(request)
    if (scope.violations.isNotEmpty()) throw ValidationException(scope.violations.toList())
}
```

- [ ] **Step 5: テストを実行して通ることを確認する**

Run: `./gradlew :ktor-typed-routing-core:test --tests '*ValidationTest*'`
Expected: PASS

- [ ] **Step 6: コミット**

```bash
git add core/src/main/kotlin/jp/kukv/typedrouting/Validation.kt \
        core/src/main/kotlin/jp/kukv/typedrouting/EndpointBuilder.kt \
        core/src/test/kotlin/jp/kukv/typedrouting/ValidationTest.kt \
        gradle/libs.versions.toml core/build.gradle.kts
git commit -m "feat: ValidationScope と EndpointBuilder を追加"
```

---

## Task 11: EndpointDsl

**Files:**
- Create: `core/src/main/kotlin/jp/kukv/typedrouting/EndpointDsl.kt`
- Test: `core/src/test/kotlin/jp/kukv/typedrouting/EndpointDslTest.kt`

**Interfaces:**
- Consumes: Task 7〜10 のすべて
- Produces:
  - `public inline fun <reified Req, reified Res> Route.get(path: String = "", noinline build: EndpointBuilder<Req, Res>.() -> Unit): Route`
  - 同形の `post` / `put` / `patch` / `delete` / `head` / `options`
  - `public inline fun <reified Req, reified Res> Route.route(method: HttpMethod, path: String = "", noinline build: EndpointBuilder<Req, Res>.() -> Unit): Route`
  - `public fun <Req, Res> Route.typedEndpoint(method: HttpMethod, path: String, requestSerializer: KSerializer<Req>?, responseSerializer: KSerializer<Res>?, requestType: KType?, responseType: KType?, build: EndpointBuilder<Req, Res>.() -> Unit): Route`

`Req` / `Res` が `Unit` の場合は serializer を `null` として渡し、バインドと respond を省く。

- [ ] **Step 1: 失敗するテストを書く**

`core/src/test/kotlin/jp/kukv/typedrouting/EndpointDslTest.kt`:

```kotlin
package jp.kukv.typedrouting

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

class EndpointDslTest {
    @Serializable
    data class Paging(val page: Int = 1, val limit: Int = 20)

    @Serializable
    data class SearchReq(
        @Path val orgId: Long,
        @Query val paging: Paging,
    )

    @Serializable
    data class NewUser(val name: String)

    @Serializable
    data class CreateReq(
        @Path val orgId: Long,
        @Body val user: NewUser,
    )

    @Serializable
    data class User(val id: Long, val name: String)

    @Test
    fun `get binds path and grouped query and returns the body`() = testApplication {
        application {
            install(TypedRouting)
            routing {
                route("/orgs/{orgId}/users") {
                    get<SearchReq, List<User>> {
                        handle { req ->
                            listOf(User(req.orgId, "page=${req.paging.page} limit=${req.paging.limit}"))
                        }
                    }
                }
            }
        }

        val response = client.get("/orgs/7/users?page=2&limit=50")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""[{"id":7,"name":"page=2 limit=50"}]""", response.bodyAsText())
    }

    @Test
    fun `post reads the body and honours the configured status`() = testApplication {
        application {
            install(TypedRouting)
            routing {
                route("/orgs/{orgId}/users") {
                    post<CreateReq, User> {
                        status = HttpStatusCode.Created
                        handle { req -> User(req.orgId, req.user.name) }
                    }
                }
            }
        }

        val response = client.post("/orgs/7/users") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"alice"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals("""{"id":7,"name":"alice"}""", response.bodyAsText())
    }

    @Test
    fun `Unit response defaults to 204 and Unit request needs no input`() = testApplication {
        application {
            install(TypedRouting)
            routing {
                get<Unit, Unit>("/ping") { handle { } }
            }
        }

        val response = client.get("/ping")
        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("", response.bodyAsText())
    }

    @Test
    fun `path argument nests under the current route`() = testApplication {
        application {
            install(TypedRouting)
            routing {
                route("/orgs/{orgId}/users") {
                    get<SearchReq, String>("/summary") { handle { req -> "org ${req.orgId}" } }
                }
            }
        }

        assertEquals("\"org 7\"", client.get("/orgs/7/users/summary").bodyAsText())
    }

    @Test
    fun `standard routing DSL still resolves alongside the typed one`() = testApplication {
        application {
            install(TypedRouting)
            routing {
                io.ktor.server.routing.get("/standard") {
                    call.respondText("standard")
                }
                get<Unit, String>("/typed") { handle { "typed" } }
            }
        }

        assertEquals("standard", client.get("/standard").bodyAsText())
        assertEquals("\"typed\"", client.get("/typed").bodyAsText())
    }
}
```

`respondText` の import は `io.ktor.server.response.respondText`。

最後のテストが spec 5.5 の「オーバーロード解決の確認」にあたる。
コンパイルが通らない場合は、汎用形 `route<Req, Res>` の名前を変えるか、
メソッド別関数の path 引数を必須にして曖昧さを解消する。
**その判断をしたら spec の 5.4 / 5.5 を更新すること。**

- [ ] **Step 2: テストを実行して失敗することを確認する**

Run: `./gradlew :ktor-typed-routing-core:test --tests '*EndpointDslTest*'`
Expected: コンパイルエラー（型付きの `get` が未解決）

- [ ] **Step 3: 実装する**

`core/src/main/kotlin/jp/kukv/typedrouting/EndpointDsl.kt`:

```kotlin
package jp.kukv.typedrouting

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.application
import io.ktor.server.routing.createRouteFromPath
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * 型付きエンドポイントを 1 つ定義する。
 *
 * [requestSerializer] が `null` のときはバインドを行わず `Unit` を渡す。
 * [responseSerializer] が `null` のときはボディを書かず、既定で `204 No Content` を返す。
 */
public fun <Req, Res> Route.typedEndpoint(
    method: HttpMethod,
    path: String,
    requestSerializer: KSerializer<Req>?,
    responseSerializer: KSerializer<Res>?,
    requestType: KType?,
    responseType: KType?,
    build: EndpointBuilder<Req, Res>.() -> Unit,
): Route {
    val builder = EndpointBuilder<Req, Res>().apply(build)
    val handler = builder.handler
        ?: error("handle { } was not called for $method $path. Every endpoint must declare a handler.")

    requestSerializer?.descriptor?.validateBindingShape()

    val defaultStatus =
        if (responseSerializer == null) HttpStatusCode.NoContent else HttpStatusCode.OK
    val spec = EndpointSpec(
        method = method,
        summary = builder.summary,
        description = builder.description,
        status = builder.status ?: defaultStatus,
        errors = builder.declaredErrors.toList(),
        requestType = requestType,
        responseType = responseType,
    )

    val parent = if (path.isEmpty()) this else createRouteFromPath(path)
    val endpoint = parent.createChild(HttpMethodRouteSelector(method))
    endpoint.attributes.put(EndpointSpecKey, spec)

    endpoint.handle {
        val config = call.application.typedRoutingConfig()
        val ctx = EndpointContext(call, spec)
        val chain: suspend () -> Any? = {
            @Suppress("UNCHECKED_CAST")
            val request: Req = requestSerializer
                ?.let { bindRequest(call, it, config.json) }
                ?: (Unit as Req)
            ctx.request = request
            builder.runValidation(request)
            val result = handler(request)
            ctx.status = spec.status
            result
        }

        val wrapped = (config.around + builder.interceptors)
            .foldRight(chain) { interceptor, next -> { interceptor.invoke(ctx, next) } }

        val body = wrapped()
        respondResult(call, spec.status, responseSerializer, config.json, body)
    }

    return endpoint
}

private suspend fun <Res> respondResult(
    call: RoutingCall,
    status: HttpStatusCode,
    responseSerializer: KSerializer<Res>?,
    json: kotlinx.serialization.json.Json,
    body: Any?,
) {
    if (responseSerializer == null) {
        call.respond(status)
        return
    }
    @Suppress("UNCHECKED_CAST")
    val text = json.encodeToString(responseSerializer, body as Res)
    call.respond(status, io.ktor.http.content.TextContent(text, io.ktor.http.ContentType.Application.Json, status))
}

@PublishedApi
internal inline fun <reified T> serializerOrNullForUnit(): KSerializer<T>? =
    if (T::class == Unit::class) null else serializer<T>()

@PublishedApi
internal inline fun <reified T> typeOrNullForUnit(): KType? =
    if (T::class == Unit::class) null else typeOf<T>()

/** HTTP GET のエンドポイントを定義する。 */
public inline fun <reified Req, reified Res> Route.get(
    path: String = "",
    noinline build: EndpointBuilder<Req, Res>.() -> Unit,
): Route = typedEndpoint(
    HttpMethod.Get,
    path,
    serializerOrNullForUnit<Req>(),
    serializerOrNullForUnit<Res>(),
    typeOrNullForUnit<Req>(),
    typeOrNullForUnit<Res>(),
    build,
)

/** HTTP POST のエンドポイントを定義する。 */
public inline fun <reified Req, reified Res> Route.post(
    path: String = "",
    noinline build: EndpointBuilder<Req, Res>.() -> Unit,
): Route = typedEndpoint(
    HttpMethod.Post,
    path,
    serializerOrNullForUnit<Req>(),
    serializerOrNullForUnit<Res>(),
    typeOrNullForUnit<Req>(),
    typeOrNullForUnit<Res>(),
    build,
)

/** HTTP PUT のエンドポイントを定義する。 */
public inline fun <reified Req, reified Res> Route.put(
    path: String = "",
    noinline build: EndpointBuilder<Req, Res>.() -> Unit,
): Route = typedEndpoint(
    HttpMethod.Put,
    path,
    serializerOrNullForUnit<Req>(),
    serializerOrNullForUnit<Res>(),
    typeOrNullForUnit<Req>(),
    typeOrNullForUnit<Res>(),
    build,
)

/** HTTP PATCH のエンドポイントを定義する。 */
public inline fun <reified Req, reified Res> Route.patch(
    path: String = "",
    noinline build: EndpointBuilder<Req, Res>.() -> Unit,
): Route = typedEndpoint(
    HttpMethod.Patch,
    path,
    serializerOrNullForUnit<Req>(),
    serializerOrNullForUnit<Res>(),
    typeOrNullForUnit<Req>(),
    typeOrNullForUnit<Res>(),
    build,
)

/** HTTP DELETE のエンドポイントを定義する。 */
public inline fun <reified Req, reified Res> Route.delete(
    path: String = "",
    noinline build: EndpointBuilder<Req, Res>.() -> Unit,
): Route = typedEndpoint(
    HttpMethod.Delete,
    path,
    serializerOrNullForUnit<Req>(),
    serializerOrNullForUnit<Res>(),
    typeOrNullForUnit<Req>(),
    typeOrNullForUnit<Res>(),
    build,
)

/** HTTP HEAD のエンドポイントを定義する。 */
public inline fun <reified Req, reified Res> Route.head(
    path: String = "",
    noinline build: EndpointBuilder<Req, Res>.() -> Unit,
): Route = typedEndpoint(
    HttpMethod.Head,
    path,
    serializerOrNullForUnit<Req>(),
    serializerOrNullForUnit<Res>(),
    typeOrNullForUnit<Req>(),
    typeOrNullForUnit<Res>(),
    build,
)

/** HTTP OPTIONS のエンドポイントを定義する。 */
public inline fun <reified Req, reified Res> Route.options(
    path: String = "",
    noinline build: EndpointBuilder<Req, Res>.() -> Unit,
): Route = typedEndpoint(
    HttpMethod.Options,
    path,
    serializerOrNullForUnit<Req>(),
    serializerOrNullForUnit<Res>(),
    typeOrNullForUnit<Req>(),
    typeOrNullForUnit<Res>(),
    build,
)

/** メソッドを動的に決めるエンドポイントを定義する。 */
public inline fun <reified Req, reified Res> Route.route(
    method: HttpMethod,
    path: String = "",
    noinline build: EndpointBuilder<Req, Res>.() -> Unit,
): Route = typedEndpoint(
    method,
    path,
    serializerOrNullForUnit<Req>(),
    serializerOrNullForUnit<Res>(),
    typeOrNullForUnit<Req>(),
    typeOrNullForUnit<Res>(),
    build,
)
```

レスポンスの書き出しに `TextContent` を使うのは、ContentNegotiation が
入っていなくても JSON を返せるようにするためである。バインド側が
ContentNegotiation を経由しない（spec 6.6）のと対称になる。

- [ ] **Step 4: テストを実行して通ることを確認する**

Run: `./gradlew :ktor-typed-routing-core:test --tests '*EndpointDslTest*'`
Expected: PASS

オーバーロードが曖昧になった場合は Step 1 の注記に従って対処し、
spec を更新してから次に進む。

- [ ] **Step 5: 全テストを実行する**

Run: `./gradlew :ktor-typed-routing-core:test`
Expected: PASS

- [ ] **Step 6: コミット**

```bash
git add core/src/main/kotlin/jp/kukv/typedrouting/EndpointDsl.kt \
        core/src/test/kotlin/jp/kukv/typedrouting/EndpointDslTest.kt
git commit -m "feat: 型付きエンドポイント DSL を追加"
```

---

## Task 12: 例外の透過と StatusPages への到達

**Files:**
- Test: `core/src/test/kotlin/jp/kukv/typedrouting/ExceptionPassthroughTest.kt`

**Interfaces:**
- Consumes: Task 11 の DSL
- Produces: なし（振る舞いの保証のみ）

- [ ] **Step 1: 失敗するテストを書く**

`core/src/test/kotlin/jp/kukv/typedrouting/ExceptionPassthroughTest.kt`:

```kotlin
package jp.kukv.typedrouting

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

class ExceptionPassthroughTest {
    @Serializable
    data class Req(@Query val count: Int)

    private class DomainFailure : Exception("domain failure")

    private fun io.ktor.server.testing.ApplicationTestBuilder.withStatusPages(
        configure: io.ktor.server.routing.Route.() -> Unit,
    ) = application {
        install(TypedRouting)
        install(StatusPages) {
            exception<RequestBindingException> { call, cause ->
                call.respondText("binding:" + cause.violations.joinToString { it.path }, status = HttpStatusCode.BadRequest)
            }
            exception<ValidationException> { call, cause ->
                call.respondText("validation:" + cause.violations.joinToString { it.path }, status = HttpStatusCode.UnprocessableEntity)
            }
            exception<DomainFailure> { call, _ ->
                call.respondText("domain", status = HttpStatusCode.InternalServerError)
            }
        }
        routing(configure)
    }

    @Test
    fun `binding failures reach StatusPages`() = testApplication {
        withStatusPages {
            get<Req, String>("/x") { handle { "ok" } }
        }

        val response = client.get("/x?count=abc")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("binding:count", response.bodyAsText())
    }

    @Test
    fun `missing required parameters reach StatusPages`() = testApplication {
        withStatusPages {
            get<Req, String>("/x") { handle { "ok" } }
        }

        val response = client.get("/x")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("binding:count", response.bodyAsText())
    }

    @Test
    fun `validation failures reach StatusPages`() = testApplication {
        withStatusPages {
            get<Req, String>("/x") {
                validate { if (it.count < 1) reject("count", "must be >= 1") }
                handle { "ok" }
            }
        }

        val response = client.get("/x?count=0")
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals("validation:count", response.bodyAsText())
    }

    @Test
    fun `handler exceptions reach StatusPages`() = testApplication {
        withStatusPages {
            get<Req, String>("/x") { handle { throw DomainFailure() } }
        }

        val response = client.get("/x?count=1")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("domain", response.bodyAsText())
    }

    @Test
    fun `an around that rethrows does not block StatusPages`() = testApplication {
        val seen = mutableListOf<String>()
        application {
            install(TypedRouting) {
                around { _, proceed ->
                    try {
                        proceed()
                    } catch (cause: Throwable) {
                        seen += cause::class.simpleName.orEmpty()
                        throw cause
                    }
                }
            }
            install(StatusPages) {
                exception<DomainFailure> { call, _ ->
                    call.respondText("domain", status = HttpStatusCode.InternalServerError)
                }
            }
            routing {
                get<Req, String>("/x") { handle { throw DomainFailure() } }
            }
        }

        val response = client.get("/x?count=1")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals(listOf("DomainFailure"), seen)
    }
}
```

- [ ] **Step 2: テストを実行する**

Run: `./gradlew :ktor-typed-routing-core:test --tests '*ExceptionPassthroughTest*'`
Expected: すべて PASS（Task 11 までで実装済みのはず）

FAIL した場合は `typedEndpoint` のどこかで例外を握り潰していないか確認する。
`core` の本体コードに `try`/`catch` があってよいのは `RequestBinder.kt` の
`decodeOrThrow` だけである。

- [ ] **Step 3: 本体コードに余計な try/catch がないことを確認する**

Run: `grep -rn "catch" core/src/main/kotlin`
Expected: `RequestBinder.kt` の 1 箇所だけ

- [ ] **Step 4: コミット**

```bash
git add core/src/test/kotlin/jp/kukv/typedrouting/ExceptionPassthroughTest.kt
git commit -m "test: 例外が StatusPages に到達することを検証"
```

---

## Task 13: 公式プラグインとの共存

**Files:**
- Test: `core/src/test/kotlin/jp/kukv/typedrouting/InteropTest.kt`

**Interfaces:**
- Consumes: Task 11 の DSL
- Produces: なし（振る舞いの保証のみ）

- [ ] **Step 1: テストを書く**

`core/src/test/kotlin/jp/kukv/typedrouting/InteropTest.kt`:

```kotlin
package jp.kukv.typedrouting

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals

class InteropTest {
    @Serializable
    data class Req(@Path val id: Long)

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `typed endpoints work inside authenticate`() = testApplication {
        application {
            install(TypedRouting)
            install(Authentication) {
                basic("test") {
                    validate { credentials ->
                        if (credentials.name == "user") UserIdPrincipal(credentials.name) else null
                    }
                }
            }
            routing {
                authenticate("test") {
                    route("/items/{id}") {
                        get<Req, String> { handle { req -> "item ${req.id}" } }
                    }
                }
            }
        }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/items/1").status)

        val credentials = Base64.encode("user:pass".toByteArray())
        val response = client.get("/items/1") { header("Authorization", "Basic $credentials") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("\"item 1\"", response.bodyAsText())
    }

    @Test
    fun `route scoped ContentNegotiation can be installed alongside`() = testApplication {
        application {
            install(TypedRouting)
            routing {
                route("/items/{id}") {
                    install(ContentNegotiation) { json() }
                    get<Req, String> { handle { req -> "item ${req.id}" } }
                }
            }
        }

        val response = client.get("/items/1")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("\"item 1\"", response.bodyAsText())
    }
}
```

- [ ] **Step 2: テストを実行する**

Run: `./gradlew :ktor-typed-routing-core:test --tests '*InteropTest*'`
Expected: PASS

- [ ] **Step 3: コミット**

```bash
git add core/src/test/kotlin/jp/kukv/typedrouting/InteropTest.kt
git commit -m "test: authenticate と route スコープ ContentNegotiation との共存を検証"
```

---

## Task 14: OpenAPI ブリッジ — パラメータの平坦化

**Files:**
- Create: `openapi/src/main/kotlin/jp/kukv/typedrouting/openapi/ParameterFlattening.kt`
- Test: `openapi/src/test/kotlin/jp/kukv/typedrouting/openapi/ParameterFlatteningTest.kt`

**Interfaces:**
- Consumes: `core` の `Path` / `Query` / `Header` / `Cookie` / `Body`
- Produces:
  - `internal enum class ParameterIn { PATH, QUERY, HEADER, COOKIE }`
  - `internal data class FlatParameter(val name: String, val location: ParameterIn, val required: Boolean, val type: KType)`
  - `internal fun KType.flattenParameters(): List<FlatParameter>`
  - `internal fun KType.bodyParameterType(): KType?`

### 設計メモ（実装者向け）

Ktor 3.5.2 のスキーマ推論の公開入口は `JsonSchemaInference.buildSchema(KType)` だけである。
`KotlinxSerializerJsonSchemaInference.buildSchemaFromDescriptor` は `internal` で呼べない。
よってこのモジュールは `SerialDescriptor` ではなく `KType` を歩き、
アノテーションの読み取りと必須判定も `kotlin-reflect` で行う。

判定規則は spec 6.4 / 6.5 と揃えること。

| 判定 | reflection での取り方 |
|---|---|
| 由来 | `KProperty1.annotations` から `@Path` / `@Query` / `@Header` / `@Cookie` / `@Body` を探す |
| グループかどうか | `classifier` が `KClass` で、`isData` が true かつ `@Body` でない |
| 必須かどうか | プライマリコンストラクタの対応する `KParameter.isOptional` が false、かつ `returnType.isMarkedNullable` が false |
| 宣言順 | `KClass.primaryConstructor!!.parameters` の順（`memberProperties` は順序が保証されない） |

`@SerialInfo` を付けたアノテーションは `RUNTIME` 保持なので reflection から見える。
アノテーションは**プロパティに付いている**ので、`KProperty1.annotations` を見る
（`KParameter.annotations` ではない）。

- [ ] **Step 1: 失敗するテストを書く**

`openapi/src/test/kotlin/jp/kukv/typedrouting/openapi/ParameterFlatteningTest.kt`:

```kotlin
package jp.kukv.typedrouting.openapi

import jp.kukv.typedrouting.Body
import jp.kukv.typedrouting.Header
import jp.kukv.typedrouting.Path
import jp.kukv.typedrouting.Query
import kotlinx.serialization.Serializable
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ParameterFlatteningTest {
    @Serializable
    data class Paging(val page: Int = 1, val limit: Int = 20)

    @Serializable
    data class Payload(val name: String)

    @Serializable
    data class Req(
        @Path val orgId: Long,
        @Query val paging: Paging,
        @Query(prefix = "f.") val filters: Paging,
        @Query val q: String?,
        @Header("X-Trace-Id") val traceId: String?,
        @Body val payload: Payload,
    )

    @Serializable
    data class NoBody(@Query val q: String)

    @Test
    fun `groups are flattened with their prefix in declaration order`() {
        val flat = typeOf<Req>().flattenParameters()

        assertEquals(
            listOf("orgId", "page", "limit", "f.page", "f.limit", "q", "X-Trace-Id"),
            flat.map { it.name },
        )
    }

    @Test
    fun `locations are carried through`() {
        val flat = typeOf<Req>().flattenParameters().associateBy { it.name }

        assertEquals(ParameterIn.PATH, flat.getValue("orgId").location)
        assertEquals(ParameterIn.QUERY, flat.getValue("f.page").location)
        assertEquals(ParameterIn.HEADER, flat.getValue("X-Trace-Id").location)
    }

    @Test
    fun `required is false for defaults and nullables`() {
        val flat = typeOf<Req>().flattenParameters().associateBy { it.name }

        assertEquals(true, flat.getValue("orgId").required)
        assertEquals(false, flat.getValue("page").required, "has a default")
        assertEquals(false, flat.getValue("q").required, "is nullable")
    }

    @Test
    fun `element types are carried through for schema inference`() {
        val flat = typeOf<Req>().flattenParameters().associateBy { it.name }

        assertEquals(typeOf<Long>(), flat.getValue("orgId").type)
        assertEquals(typeOf<Int>(), flat.getValue("page").type)
        assertEquals(typeOf<String?>(), flat.getValue("q").type)
    }

    @Test
    fun `body type is found when present`() {
        assertEquals(typeOf<Payload>(), typeOf<Req>().bodyParameterType())
        assertNull(typeOf<NoBody>().bodyParameterType())
        assertNotNull(typeOf<Req>().bodyParameterType())
    }
}
```

- [ ] **Step 2: テストを実行して失敗することを確認する**

Run: `./gradlew :ktor-typed-routing-openapi:test --tests '*ParameterFlatteningTest*'`
Expected: コンパイルエラー（`flattenParameters` が未解決）

- [ ] **Step 3: 実装する**

`openapi/src/main/kotlin/jp/kukv/typedrouting/openapi/ParameterFlattening.kt`:

```kotlin
package jp.kukv.typedrouting.openapi

import jp.kukv.typedrouting.Body
import jp.kukv.typedrouting.Cookie
import jp.kukv.typedrouting.Header
import jp.kukv.typedrouting.Path
import jp.kukv.typedrouting.Query
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/** OpenAPI のパラメータ位置。 */
internal enum class ParameterIn { PATH, QUERY, HEADER, COOKIE }

/** 平坦化した 1 パラメータ。 */
internal data class FlatParameter(
    val name: String,
    val location: ParameterIn,
    val required: Boolean,
    val type: KType,
)

private data class Origin(
    val location: ParameterIn?,
    val name: String,
    val prefix: String,
    val isBody: Boolean,
)

private fun KProperty1<*, *>.originOrNull(): Origin? {
    annotations.forEach { annotation ->
        when (annotation) {
            is Path -> return Origin(ParameterIn.PATH, annotation.name.ifEmpty { name }, "", false)
            is Query -> return Origin(ParameterIn.QUERY, annotation.name.ifEmpty { name }, annotation.prefix, false)
            is Header -> return Origin(ParameterIn.HEADER, annotation.name.ifEmpty { name }, annotation.prefix, false)
            is Cookie -> return Origin(ParameterIn.COOKIE, annotation.name.ifEmpty { name }, annotation.prefix, false)
            is Body -> return Origin(null, name, "", true)
        }
    }
    return null
}

/**
 * 宣言順にプロパティを返す。`memberProperties` は順序が保証されないため、
 * プライマリコンストラクタの引数順に並べ直す。
 */
private fun KClass<*>.orderedProperties(): List<Pair<KProperty1<*, *>, KParameter?>> {
    val properties = memberProperties.associateBy { it.name }
    val parameters = primaryConstructor?.parameters.orEmpty()
    if (parameters.isEmpty()) return properties.values.map { it to null }
    return parameters.mapNotNull { parameter ->
        properties[parameter.name]?.let { it to parameter }
    }
}

/** この型がグループとして再帰的に展開されるかどうか。 */
private fun KType.isGroupType(): Boolean {
    val classifier = classifier as? KClass<*> ?: return false
    return classifier.isData && classifier.primaryConstructor != null
}

/**
 * Req の型を OpenAPI の `parameters` 相当に平坦化する。
 * グループは接頭辞を合成しながら再帰的に展開する。`@Body` は含まれない。
 */
internal fun KType.flattenParameters(): List<FlatParameter> {
    val result = mutableListOf<FlatParameter>()

    fun walk(type: KType, prefix: String, inherited: ParameterIn?) {
        val classifier = type.classifier as? KClass<*> ?: return

        for ((property, parameter) in classifier.orderedProperties()) {
            val origin = property.originOrNull()
            if (origin?.isBody == true) continue

            val location = origin?.location ?: inherited ?: continue
            val elementName = origin?.name ?: property.name
            val elementPrefix = origin?.prefix.orEmpty()
            val elementType = property.returnType

            if (origin?.isBody != true && elementType.isGroupType() && origin?.location != null || (origin == null && elementType.isGroupType())) {
                walk(elementType, prefix + elementPrefix, location)
                continue
            }

            val hasDefault = parameter?.isOptional == true
            result += FlatParameter(
                name = prefix + elementName,
                location = location,
                required = !hasDefault && !elementType.isMarkedNullable,
                type = elementType,
            )
        }
    }

    walk(this, prefix = "", inherited = null)
    return result
}

/** `@Body` を付けたプロパティの型。無ければ `null`。 */
internal fun KType.bodyParameterType(): KType? {
    val classifier = classifier as? KClass<*> ?: return null
    return classifier.orderedProperties()
        .firstOrNull { (property, _) -> property.originOrNull()?.isBody == true }
        ?.first
        ?.returnType
}
```

`walk` の中のグループ判定の条件式は読みにくいので、実装時に次の形へ整理すること。
条件の意味は「`@Body` でなく、かつ型が data class なら再帰する」である。

```kotlin
val isGroup = origin?.isBody != true && elementType.isGroupType()
if (isGroup) {
    walk(elementType, prefix + elementPrefix, location)
    continue
}
```

- [ ] **Step 4: テストを実行して通ることを確認する**

Run: `./gradlew :ktor-typed-routing-openapi:test --tests '*ParameterFlatteningTest*'`
Expected: PASS

- [ ] **Step 5: core 側と規則が一致していることを確認する**

`:core` の `ObjectDecoder` と `:openapi` の `flattenParameters` は、
同じ入力に対して同じ名前・同じグループ展開を返さなければならない。
Task 5 のグループテストと Task 14 のテストが同じ期待値
（`page` / `limit` / `f.page` / `f.limit`）になっていることを確認する。

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 6: コミット**

```bash
git add openapi/src core/src/main/kotlin/jp/kukv/typedrouting/ElementOrigin.kt \
        core/src/main/kotlin/jp/kukv/typedrouting/RequestDecoder.kt \
        core/src/main/kotlin/jp/kukv/typedrouting/EndpointSpec.kt
git commit -m "feat: OpenAPI 用のパラメータ平坦化を追加"
```

---

## Task 15: OpenAPI ブリッジ — describe への流し込み

**Files:**
- Create: `openapi/src/main/kotlin/jp/kukv/typedrouting/openapi/OpenApiBridge.kt`
- Test: `openapi/src/test/kotlin/jp/kukv/typedrouting/openapi/OpenApiBridgeTest.kt`

**Interfaces:**
- Consumes: `ParameterFlattening.kt`、`core` の `EndpointSpec` / `EndpointSpecKey`
- Produces:
  - `public fun Route.describeTypedEndpoints()`
  - `public fun Application.describeTypedEndpoints()`

### 確認済みの公式 API（Ktor 3.5.2）

`javap` で確認した `Operation.Builder` の形。**入れ子のビルダになっている。**

```
io.ktor.openapi.Operation$Builder
  var summary: String?
  var description: String?
  fun parameters(block: Parameters.Builder.() -> Unit)
  fun requestBody(block: RequestBody.Builder.() -> Unit)
  fun responses(block: Responses.Builder.() -> Unit)
  fun buildSchema(type: KType): JsonSchema        // JsonSchemaInference の実装

io.ktor.openapi.Parameters$Builder
  fun path(name: String, block: Parameter.Builder.() -> Unit)
  fun query(name: String, block: Parameter.Builder.() -> Unit)
  fun header(name: String, block: Parameter.Builder.() -> Unit)
  fun cookie(name: String, block: Parameter.Builder.() -> Unit)

io.ktor.openapi.Parameter$Builder : JsonSchemaInference
  var required: Boolean
  var description: String?
  var schema: JsonSchema?
  fun buildSchema(type: KType): JsonSchema

io.ktor.openapi.RequestBody$Builder : JsonSchemaInference
  var required: Boolean
  var schema: JsonSchema?
  fun buildSchema(type: KType): JsonSchema

io.ktor.openapi.Responses$Builder
  fun response(code: Int, block: Response.Builder.() -> Unit)
  operator fun invoke(status: HttpStatusCode, block: Response.Builder.() -> Unit)
```

`Response.Builder` のフィールド名は実装時に
`javap -cp <ktor-openapi-schema jar> 'io.ktor.openapi.Response$Builder'` で確認し、
スキーマの設定方法を合わせる。**確認結果は spec の 12 章に追記すること。**

- [ ] **Step 1: 失敗するテストを書く**

`openapi/src/test/kotlin/jp/kukv/typedrouting/openapi/OpenApiBridgeTest.kt`:

```kotlin
package jp.kukv.typedrouting.openapi

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.routing.openapi.openAPI
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import jp.kukv.typedrouting.Body
import jp.kukv.typedrouting.Path
import jp.kukv.typedrouting.Query
import jp.kukv.typedrouting.TypedRouting
import jp.kukv.typedrouting.get
import jp.kukv.typedrouting.post
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertTrue

class OpenApiBridgeTest {
    @Serializable
    data class Paging(val page: Int = 1, val limit: Int = 20)

    @Serializable
    data class SearchReq(@Path val orgId: Long, @Query val paging: Paging)

    @Serializable
    data class NewUser(val name: String)

    @Serializable
    data class CreateReq(@Path val orgId: Long, @Body val user: NewUser)

    @Serializable
    data class ErrorBody(val message: String)

    @Serializable
    data class User(val id: Long, val name: String)

    @Test
    fun `typed endpoints appear in the generated document`() = testApplication {
        application {
            install(TypedRouting)
            routing {
                route("/orgs/{orgId}/users") {
                    get<SearchReq, List<User>> {
                        summary = "Search users"
                        handle { listOf(User(1, "alice")) }
                    }
                    post<CreateReq, User> {
                        summary = "Create a user"
                        status = HttpStatusCode.Created
                        error<ErrorBody>(HttpStatusCode.Conflict)
                        handle { req -> User(req.orgId, req.user.name) }
                    }
                }
                describeTypedEndpoints()
                openAPI("docs")
            }
        }

        val document = client.get("/docs").bodyAsText()

        assertTrue(document.contains("/orgs/{orgId}/users"), "path is present: $document")
        assertTrue(document.contains("Search users"), "summary is present")
        assertTrue(document.contains("\"page\""), "flattened group parameter is present")
        assertTrue(document.contains("\"limit\""), "flattened group parameter is present")
        assertTrue(document.contains("NewUser"), "request body schema is present")
        assertTrue(document.contains("201"), "configured success status is present")
        assertTrue(document.contains("409"), "declared error status is present")
    }
}
```

`openAPI` のシグネチャは実装時に確認し、必要なら引数を補う。
アサーション文字列は、生成された JSON を実際に見て調整してよい。
**調整した場合は失敗時に `$document` が出るようメッセージに残すこと。**

- [ ] **Step 2: テストを実行して失敗することを確認する**

Run: `./gradlew :ktor-typed-routing-openapi:test --tests '*OpenApiBridgeTest*'`
Expected: コンパイルエラー（`describeTypedEndpoints` が未解決）

- [ ] **Step 3: 実装する**

`openapi/src/main/kotlin/jp/kukv/typedrouting/openapi/OpenApiBridge.kt`:

```kotlin
package jp.kukv.typedrouting.openapi

import io.ktor.server.application.Application
import io.ktor.server.routing.Route
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.routingRoot
import io.ktor.utils.io.ExperimentalKtorApi
import jp.kukv.typedrouting.EndpointSpec
import jp.kukv.typedrouting.EndpointSpecKey

/**
 * 配下の型付きエンドポイントを走査し、公式の `describe {}` にメタデータを流し込む。
 *
 * すべてのエンドポイントを定義し終えた後に 1 度だけ呼ぶ。
 */
@OptIn(ExperimentalKtorApi::class)
public fun Route.describeTypedEndpoints() {
    descendants().forEach { node ->
        val spec = node.attributes.getOrNull(EndpointSpecKey) ?: return@forEach
        node.applyEndpointSpec(spec)
    }
}

/** アプリケーション全体の型付きエンドポイントに対して [describeTypedEndpoints] を行う。 */
public fun Application.describeTypedEndpoints() {
    routingRoot.describeTypedEndpoints()
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.applyEndpointSpec(spec: EndpointSpec) {
    describe {
        spec.summary?.let { summary = it }
        spec.description?.let { description = it }

        val flat = spec.requestType?.flattenParameters().orEmpty()
        if (flat.isNotEmpty()) {
            parameters {
                flat.forEach { parameter ->
                    val configure: io.ktor.openapi.Parameter.Builder.() -> Unit = {
                        required = parameter.location == ParameterIn.PATH || parameter.required
                        schema = buildSchema(parameter.type)
                    }
                    when (parameter.location) {
                        ParameterIn.PATH -> path(parameter.name, configure)
                        ParameterIn.QUERY -> query(parameter.name, configure)
                        ParameterIn.HEADER -> header(parameter.name, configure)
                        ParameterIn.COOKIE -> cookie(parameter.name, configure)
                    }
                }
            }
        }

        spec.requestType?.bodyParameterType()?.let { bodyType ->
            requestBody {
                required = true
                schema = buildSchema(bodyType)
            }
        }

        responses {
            spec.responseType?.let { responseType ->
                response(spec.status.value) {
                    schema = buildSchema(responseType)
                }
            }
            spec.errors.forEach { (status, errorType) ->
                response(status.value) {
                    schema = buildSchema(errorType)
                }
            }
        }
    }
}
```

パスパラメータは OpenAPI の仕様上つねに `required: true` でなければならないため、
`ParameterIn.PATH` は無条件に `true` にしている。

`Response.Builder` に `schema` プロパティが無い場合は、
`content { }` 経由でメディアタイプごとに設定する形になる。
Step 2 のコンパイルエラーで判明するので、`javap` で確認して合わせること。

- [ ] **Step 4: テストを実行して通ることを確認する**

Run: `./gradlew :ktor-typed-routing-openapi:test --tests '*OpenApiBridgeTest*'`
Expected: PASS

- [ ] **Step 5: 全テストを実行する**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 6: 実装中に判明した公式 API の形を spec に反映する**

spec の 12 章に、確認した `Operation.Builder` / `Parameters.Builder` /
`RequestBody.Builder` / `Responses.Builder` / `Response.Builder` の形と、
「スキーマ推論の入口は `buildSchema(KType)` だけなので `:openapi` は
`kotlin-reflect` を使う」ことを追記する。

- [ ] **Step 7: コミット**

```bash
git add openapi/src/main/kotlin/jp/kukv/typedrouting/openapi/OpenApiBridge.kt \
        openapi/src/test/kotlin/jp/kukv/typedrouting/openapi/OpenApiBridgeTest.kt \
        docs/superpowers/specs/2026-08-25-typed-routing-design.md
git commit -m "feat: EndpointSpec を公式 OpenAPI の describe に流し込む"
```

---

## Task 16: README

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: Task 1〜15 のすべて
- Produces: なし

- [ ] **Step 1: README を書く**

`README.md` に次の節を作る。すべて動くコードを載せること。

1. **概要** — Ktor 標準 routing の上に乗る型付きエンドポイント DSL であること、
   標準 `routing {}` と共存できること
2. **導入** — Gradle の依存宣言

```kotlin
dependencies {
    implementation("jp.kukv:ktor-typed-routing-core:0.1.0-SNAPSHOT")
    implementation("jp.kukv:ktor-typed-routing-openapi:0.1.0-SNAPSHOT")  // OpenAPI を使う場合
}
```

3. **最小の例** — `install(TypedRouting)` と 1 つの `get`
4. **リクエストの宣言** — `@Path` / `@Query` / `@Header` / `@Cookie` / `@Body`、
   グループ、欠落時の扱い（spec 6.7 の表をそのまま載せる）
5. **バリデーション** — `validate` / `reject`、および YAVI と
   Jakarta Bean Validation の変換例（spec 7.1 のコードをそのまま載せる）
6. **エラー処理** — StatusPages に委ねること、下記の設定例

```kotlin
install(StatusPages) {
    exception<RequestBindingException> { call, cause ->
        call.respond(HttpStatusCode.BadRequest, ErrorBody("invalid request", cause.violations))
    }
    exception<ValidationException> { call, cause ->
        call.respond(HttpStatusCode.UnprocessableEntity, ErrorBody("validation failed", cause.violations))
    }
    exception<Throwable> { call, _ ->
        call.respond(HttpStatusCode.InternalServerError, ErrorBody("internal error"))
    }
}
```

`ErrorBody` は利用者が定義する型であることを明記する。

7. **around** — 成功ボディのログの例。例外は再スローが必須であること（spec 10 章のコード）
8. **OpenAPI** — `describeTypedEndpoints()` と `openAPI("docs")`、
   Gradle のコード推論は `codeInferenceEnabled = false` を推奨すること
9. **制限事項** — JVM 専用、リクエストボディは JSON のみ、
   カスタム serializer の変換失敗は最初の 1 件で打ち切ること

- [ ] **Step 2: README のコードが実際に動くことを確認する**

README に載せた最小の例を `core/src/test/kotlin/jp/kukv/typedrouting/ReadmeExampleTest.kt`
として書き、テストとして通るようにする。

Run: `./gradlew :ktor-typed-routing-core:test --tests '*ReadmeExampleTest*'`
Expected: PASS

- [ ] **Step 3: コミット**

```bash
git add README.md core/src/test/kotlin/jp/kukv/typedrouting/ReadmeExampleTest.kt
git commit -m "docs: README を追加"
```

---

## 完了条件

- [ ] `./gradlew build` が通る
- [ ] `grep -rn "catch" core/src/main/kotlin` が `RequestBinder.kt` の 1 箇所だけを返す
- [ ] spec の各節に対応するタスクが存在し、実装時に判明した相違が spec に反映されている
