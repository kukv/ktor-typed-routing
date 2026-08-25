# ktor-typed-routing

Ktor 標準 routing の上に構築する型付きエンドポイント DSL — バインド・バリデーション・OpenAPI 定義を単一の宣言から導出する

## 概要

`ktor-typed-routing` は、Ktor の標準 `routing {}` を置き換えるのではなく、その **上に乗る拡張関数レイヤ**として型付きエンドポイント DSL を提供する。

- リクエストの入力（パス・クエリ・ヘッダ・クッキー・ボディ）を 1 つの `data class` に集約し、`@Path` / `@Query` / `@Header` / `@Cookie` / `@Body` で由来を示す
- バリデーションをエンドポイント定義の `validate` ブロックに集約し、違反を 1 つの例外にまとめて送出する
- ハンドラの戻り値がそのままレスポンスボディになる
- `around` インターセプタで、成功レスポンスのボディを型付きのまま観測できる
- 公式 `ktor-server-routing-openapi` にそのまま乗り、`describeTypedEndpoints()` でメタデータを流し込める

標準の `Route` ツリーの上に生えるだけなので、`authenticate {}` や `install(ContentNegotiation)` といった既存のプラグイン、`routing {}` 内の素の Ktor エンドポイントとも共存できる。段階的に導入できる。

## 導入

```kotlin
dependencies {
    implementation("jp.kukv:ktor-typed-routing-core:0.1.0-SNAPSHOT")
    implementation("jp.kukv:ktor-typed-routing-openapi:0.1.0-SNAPSHOT")  // OpenAPI を使う場合
}
```

Kotlin `2.3.21` / Ktor `3.5.2` / kotlinx-serialization `1.11.0` / JVM toolchain 21 で開発・テストしている。

**まだどこにも公開していない。** `mavenCentral()` から解決できる座標ではなく、上記はローカルにビルド・publish した場合の座標である。

## 最小の例

```kotlin
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import jp.kukv.typedrouting.TypedRouting
import jp.kukv.typedrouting.get
import kotlinx.serialization.Serializable

@Serializable
data class Greeting(val message: String)

fun Application.module() {
    install(TypedRouting)
    routing {
        get<Unit, Greeting>("/hello") {
            handle { Greeting("hello") }
        }
    }
}
```

`GET /hello` は `200 OK` で `{"message":"hello"}` を返す。`install(TypedRouting)` はエンドポイントを定義するより前に呼んでおく必要がある。あとは通常の Ktor アプリと同じで、好きなエンジン（`ktor-server-netty` など）を利用者側で足して `embeddedServer(Netty, port = 8080, module = Application::module).start(wait = true)` のように起動すればよい。

## リクエストの宣言

入力は 1 つの `data class` に集約し、プロパティに `@Path` / `@Query` / `@Header` / `@Cookie` / `@Body` を付けて由来を示す。名前を省略するとプロパティ名がそのまま使われる。

```kotlin
@Serializable
data class Paging(val page: Int = 1, val limit: Int = 20)

@Serializable
data class ListItemsReq(
    @Path val orgId: Long,
    @Query val paging: Paging,       // ?page=2&limit=50 のようにグループとして展開される
    @Query val tag: String?,
    @Header val requestId: String,
    @Cookie val sessionId: String?,
)

@Serializable
data class ItemsResult(
    val orgId: Long,
    val page: Int,
    val limit: Int,
    val tag: String?,
    val requestId: String,
    val sessionId: String?,
)

routing {
    route("/orgs/{orgId}/items") {
        get<ListItemsReq, ItemsResult> {
            handle { req ->
                ItemsResult(req.orgId, req.paging.page, req.paging.limit, req.tag, req.requestId, req.sessionId)
            }
        }
    }
}
```

`Req` に `Unit` を指定すると、バインドを一切行わずハンドラに `Unit` を渡す（上の最小の例を参照）。

### グループ

構造型のプロパティ（`Paging` のような `data class`）はグループとして扱われ、**同じ入力ソースから接頭辞なしで再帰的に**バインドされる。`Paging.page` は `page` であって `paging.page` ではない。複数のグループで名前が衝突する場合は `@Query(prefix = "filter.")` のように明示する。

### 値が来なかったときの扱い

| 宣言 | 値が来なかったとき |
|---|---|
| `@Query val page: Int = 1` | デフォルト値（`1`） |
| `@Query val q: String?` | `null` |
| `@Query val q: String? = null` | `null` |
| `@Query val status: UserStatus`（デフォルトなし・非 null） | バインド失敗（`400` 相当） |

2 行目は kotlinx.serialization の通常の規則から**意図的に外れている**。通常「nullable かつデフォルト値なし」は必須で、明示的な `null` の送出を要求するが、クエリ・ヘッダ・クッキーでは「省略」と「`null` を明示」を区別できないため、欠落を `null` として扱う。この逸脱は `@Path` / `@Query` / `@Header` / `@Cookie` に限る。

グループにも同じ規則が適用される。子要素の値が 1 つでもあれば展開し、1 つもなければ次のように埋める。

| 宣言 | 子の値が 1 つ以上ある | 子の値が 1 つも無い |
|---|---|---|
| `@Query val paging: Paging` | 展開する | 子それぞれの既定値で埋める |
| `@Query val paging: Paging = Paging(1, 20)` | 展開する | グループごと既定値を使う |
| `@Query val paging: Paging?` | 展開する | `null` |

グループ自体が任意（既定値あり、または nullable）の場合、その子孫はすべて任意になる。

**`@Body` の中身はこの規則の対象外で、kotlinx.serialization の通常の規則にそのまま従う。** 欠落した必須フィールドはバインド失敗になる。

## バリデーション

バインド後のリクエストを `validate` ブロックで検証する。`reject` は違反を蓄積するだけでブロックの実行は止めず、ブロックを抜けた時点で 1 件以上あれば `ValidationException` として送出される。

```kotlin
get<SearchReq, String>("/search") {
    validate { req ->
        if (req.q.isBlank()) reject("q", "must not be blank")
        if (req.paging.limit !in 1..100) reject("limit", "must be 1..100")
    }
    handle { req -> req.q }
}
```

### バリデーションライブラリの利用

`validate` の中身は普通の Kotlin コードなので、任意のライブラリを使える。**アダプタは同梱していない** — `:core` はどのバリデーションライブラリにも依存しない。違反を `reject` に変換する数行を自分で書く。

```kotlin
// YAVI
val searchUsersValidator = ValidatorBuilder.of<SearchUsersReq>()
    .constraint(SearchUsersReq::q, "q") { it.lessThanOrEqual(64) }
    .build()

validate { req ->
    searchUsersValidator.validate(req).forEach { v -> reject(v.name(), v.message()) }
}
```

```kotlin
// Jakarta Bean Validation (Hibernate Validator)
validate { req ->
    jakartaValidator.validate(req).forEach { v -> reject(v.propertyPath.toString(), v.message) }
}
```

## エラー処理

**このライブラリはステータスコードもエラーボディも一切決めない。** バインド失敗は `RequestBindingException`、検証失敗は `ValidationException` として送出するだけで、どちらもステータスコードを持たない。`core/src/main` にある `try`/`catch` は 1 箇所だけで、kotlinx の `MissingFieldException` を violation に変換するためのものであり、何かを揉み消しているわけではない。エラー処理は丸ごと `StatusPages` に委ねる。

```kotlin
@Serializable
data class ErrorBody(val message: String, val violations: List<Violation> = emptyList())

install(StatusPages) {
    exception<RequestBindingException> { call, cause ->
        call.respond(HttpStatusCode.BadRequest, ErrorBody("invalid request", cause.violations))
    }
    exception<ValidationException> { call, cause ->
        call.respond(HttpStatusCode.UnprocessableEntity, ErrorBody("validation failed", cause.violations))
    }
    // ボディの JSON が壊れている場合はここに来る（RequestBindingException にはならない）。
    exception<SerializationException> { call, _ ->
        call.respond(HttpStatusCode.BadRequest, ErrorBody("malformed body"))
    }
    exception<Throwable> { call, _ ->
        call.respond(HttpStatusCode.InternalServerError, ErrorBody("internal error"))
    }
}
```

**リクエストボディの JSON が壊れている場合は `RequestBindingException` にはならない。** 本ライブラリは何も捕捉しないため、`kotlinx.serialization.SerializationException`（`Json.decodeFromString` が投げるもの）がそのまま伝播する。`exception<RequestBindingException>` しか登録していないと `exception<Throwable>` に落ちて 500 になるので、上のように `exception<SerializationException>` を登録して 400 にする。

`ErrorBody` は**利用者が定義する型**であり、本ライブラリはエラーボディの形式を提供しない。`RequestBindingException.violations` / `ValidationException.violations` は `List<Violation>`（`path` と `message` を持つ `@Serializable` な型）なので、上のようにそのまま持たせられる。

また、`call.respond(status, ErrorBody(...))` が JSON を書き出すには `install(ContentNegotiation) { json() }` が要る。型付きエンドポイント自身のレスポンスはこれを経由しない（後述）が、これは `StatusPages` 側の通常の `call.respond` であり、通常の Ktor の挙動がそのまま適用される。

## around

`around` はエンドポイントの実行を包み、成功レスポンスのボディを型付きのまま観測できる。アプリ全体（`install(TypedRouting) { around(...) }`）とエンドポイント単位（builder の `around(...)`）の両方で登録でき、アプリ側が外側になる。

```kotlin
install(TypedRouting) {
    around { ctx, proceed ->
        try {
            val res = proceed()
            log.debug("res body: {}", res)
            res
        } catch (cause: Throwable) {
            log.warn("failed: {}", cause.toString())
            throw cause   // 再スローしないと StatusPages に届かず、応答が返らなくなる
        }
    }
}
```

**例外を捕まえた場合は必ず再スローすること。** 本ライブラリは `proceed()` の外側で例外を一切捕まえないため、`around` がログのために捕まえたまま再スローし忘れると、サーバーは応答を返さなくなる。

## OpenAPI

`:openapi` は公式 `ktor-server-routing-openapi` へのブリッジで、`EndpointSpec` を公式の `describe {}` に流し込む。

```kotlin
routing {
    route("/orgs/{orgId}/users") {
        get<SearchUsersReq, List<User>> {
            summary = "Search users"
            handle { req -> userService.search(req.orgId) }
        }
    }
    describeTypedEndpoints()   // すべてのエンドポイントを定義し終えた後に呼ぶ
}
```

**`describeTypedEndpoints()` は、対象にしたいすべてのエンドポイントを定義し終えた後に呼ぶこと。** ルートツリーを 1 度だけ走査するため、呼び出し時点で存在しないエンドポイントはドキュメントに載らない。

### ドキュメントの取り出し

`ktor-server-routing-openapi` 3.5.2 には `openAPI(path)` のようなドキュメント配信ルートは**含まれていない**。ドキュメントの生成自体は次のように行う。

```kotlin
val text = OpenApiDocSource.Routing()
    .read(application, OpenApiDoc(info = OpenApiInfo(title = "My API", version = "1.0.0")))
    .content
```

Swagger UI のような配信ルートが要る場合は、`ktor-server-swagger` / `ktor-server-openapi` を自分で足す。

### 素の Ktor ルートも文書に現れる

公式ジェネレータはルートツリー全体を列挙するため、`describeTypedEndpoints()` を呼んでいても、同じツリーの中にある素の Ktor ルート（`routing { get("/health") { ... } }` など）は `"/health":{"get":{}}` という空の operation として文書に残る。「ブリッジが読み飛ばす」とは「文書から消す」ことではなく「メタデータを何も足さない」ことを意味する。文書から消したい場合は公式の `Route.hide()` を使う。

```kotlin
get("/health") { call.respondText("ok") }.hide()
```

### Gradle のコード推論について

Ktor の Gradle プラグインによるコード推論（route ハンドラの中身を静的解析して OpenAPI メタデータを補う機能）は本 DSL のパターンを認識できないため空振りする。実行時注釈（本ライブラリが生成するもの）が常に最優先されるため有効なままでも定義自体は正しく出るが、無駄な解析を避けるため `codeInferenceEnabled = false` にして実行時注釈に一本化することを推奨する。

## 制限事項

- **JVM 専用。** マルチプラットフォーム対応はしていない。
- **リクエストボディは JSON のみ。** `StringFormat` は差し替え可能だが、初版では JSON 専用。
- **型付きエンドポイントのレスポンスは `ContentNegotiation` を経由しない。** `respondText` で直接 JSON を書き出すため、`ContentNegotiation` をインストールしていなくても動くし、インストールしても型付きエンドポイントの挙動は変わらない（同じ `routing {}` 配下の素の Ktor エンドポイントには通常どおり効く）。
- **リクエストボディの不正は `RequestBindingException` ではなく `kotlinx.serialization.SerializationException` として届く。** 壊れた JSON は `Json.decodeFromString` の中で失敗し、本ライブラリはそれを捕捉しない。400 を返したいなら `StatusPages` に `exception<SerializationException>` を登録する（「エラー処理」を参照）。バインド対象の型が受け付けない値（`@Body` に載せた型のフィールドの型違いなど）も同様である。
- **`@Body` は構造型でなければならない。** `@Body val text: String` のようなスカラー（プリミティブ / String / enum / `@JvmInline value class`）のボディは起動時に例外になる。`@Serializable` なクラスで包むこと。
- **`@Body` は 1 リクエストにつき 1 つまで。** 2 つ以上付けると起動時に例外になる。
- **プリミティブの型変換失敗はすべて集めて報告するが、カスタム serializer が投げた例外は最初の 1 件で打ち切る。** kotlinx.serialization の `Decoder` の性質上、カスタム serializer 内の例外はそこで即座に伝播するため、他のフィールドの検証を続けられない。
