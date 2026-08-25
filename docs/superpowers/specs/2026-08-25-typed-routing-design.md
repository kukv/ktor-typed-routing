# ktor-typed-routing 設計書

- 日付: 2026-08-25
- 対象: Ktor 3.6.0-SNAPSHOT / JVM
- ステータス: 設計確定、実装前

## 1. 目的

Ktor 標準 routing の上に、型付きエンドポイント DSL を提供する。ハンドラの入出力を型で表現し、
リクエストのバインド・バリデーション・OpenAPI 定義を単一の宣言から導出する。

### 解決する課題

現行の `routing {}` に対して挙がった 5 つの不満に対応する。

| # | 不満 | 本設計での解決 |
|---|---|---|
| ① | クエリ / パスパラメータ / JSON ボディで取得方法が不揃い。統一するには Resources が要るが、Resources とボディで受け取り方が違う | 単一の Req 型にすべての入力を集約し、`@Path` / `@Query` / `@Header` / `@Cookie` / `@Body` で由来を示す |
| ② | バリデーションがしにくい | エンドポイント定義の `validate` ブロックに集約し、違反を 1 つの例外にまとめて送出 |
| ③ | レスポンスを明示的に `call` に詰めなければならない | ハンドラの戻り値がレスポンスボディになる |
| ④ | レスポンスボディのログが `call` の中で容易に取れない | `around` インターセプタが型付きの req / res を受け取る（エラーは StatusPages 側で扱う） |
| ⑤ | OpenAPI の定義が書けない | エンドポイント定義から公式 `ktor-server-routing-openapi` の注釈を自動生成する |

## 2. 事前調査の結論

実装方式の選定にあたり、Ktor 3.6.0-SNAPSHOT のソースと API ダンプ
(`ktor-server-core.api` / `.klib.api`) を調査した。結論のみ記す。

### 2.1 標準 routing の置き換えは技術的に可能だが、本設計では行わない

`RoutingRoot` は通常の `BaseApplicationPlugin` であり、`install` 時に
`pipeline.intercept(Call)` しているだけである (`RoutingRoot.kt:180`)。
エンジン側に routing 専用の分岐はないため、完全に独立した routing プラグインを
外部モジュールとして作ることは可能である。

しかし以下の理由により、**標準の `Route` ツリーの上に乗る拡張関数レイヤ**として実装する。

### 2.2 完全独立を選ばない理由

`RoutingCall` は `internal constructor` を持ち (`RoutingNode.kt:218`)、API ダンプにも
公開コンストラクタが存在しない。構築箇所はリポジトリ全体で 2 箇所のみ
(`RoutingRoot.kt:125`, `RoutingNode.kt:124`)。`RoutingNode.handlers` と
`buildPipeline()` も internal である。

このため `ktor-server-core` の外からは `RoutingHandler` を実行できない。
完全独立の routing プラグインを作った場合、`Route` インタフェースを介する公式プラグイン
(auth の `authenticate {}`、`webSocket()`、`sse()`、`Route.rateLimit`、
route スコープの `install(ContentNegotiation)`、`get<Resource>()` など) は
インタフェースを実装しても実行できない。

### 2.3 決定打となった OpenAPI 生成の拾い上げ条件

公式の OpenAPI 生成は次の条件を満たすルートのみを対象とする
(`OpenApiRoutes.kt:151-168`)。

```kotlin
private fun Route.asPathItem(onOperation: OperationMapping): Pair<String, PathItem>? {
    if (this !is RoutingNode || !this.hasHandler()) return null
    val path = path(format = OpenApiRoutePathFormat)
    val method = method() ?: return null   // lineage 中の HttpMethodRouteSelector
    ...
}
```

1. ノードが `RoutingNode`（サブクラス可）であること
2. `hasHandler()` が true であること（標準の `handle()` を呼んでいる）
3. 系譜のどこかに `HttpMethodRouteSelector` があること

本設計はこの 3 条件をすべて満たすため、公式の OpenAPI 生成にそのまま乗る。

### 2.4 利用できる公開 API

OpenAPI ドキュメント生成のパイプラインはすべて公開されている。

```kotlin
public fun Sequence<Route>.mapToPathItemsAndSchema(): Pair<Map<String, PathItem>, Map<String, JsonSchema>>
public fun Sequence<Route>.mapToPathItems(onOperation: OperationMapping = ...): Map<String, PathItem>
public operator fun OpenApiDoc.plus(routes: Sequence<Route>): OpenApiDoc
public val OperationDescribeAttributeKey: AttributeKey<List<RouteOperationFunction>>
public val JsonSchemaAttributeKey: AttributeKey<JsonSchemaInference>
```

メタデータの優先順位は「コンパイラ生成 → コメント注釈 → 実行時注釈」で、
**実行時注釈が最優先**である。本設計は実行時注釈のみを使う。

## 3. スコープ

### やること

- 型付きエンドポイント DSL（メソッド別の `get` / `post` / ... と汎用形の `route<Req, Res>`）
- `kotlinx.serialization` の `Decoder` を用いたリクエストバインド
- エンドポイント単位・アプリ単位のバリデーション
- `around` インターセプタ
- バインド失敗・検証失敗を表す例外の定義（ステータスの決定は行わない）
- 公式 `ktor-server-routing-openapi` へのブリッジ

### やらないこと

- 標準 routing の置き換え（`routing {}` はそのまま使える。共存する）
- ルートマッチングアルゴリズムの変更（標準の DFS + `RouteSelector` をそのまま使う）
- JVM 以外のプラットフォーム対応
- JSON 以外のリクエストボディ形式（拡張余地は残すが初版では扱わない）
- OpenAPI ドキュメントの生成・配信そのもの（公式実装に委ねる）
- 例外 → HTTP ステータス / エラーボディのマッピング（StatusPages に委ねる）

## 4. モジュール構成

```
ktor-typed-routing/
├── settings.gradle.kts
├── build.gradle.kts
├── core/                                    # artifact: ktor-typed-routing-core
│   └── src/main/kotlin/jp/kukv/typedrouting/
│       ├── Annotations.kt                   # @Path @Query @Header @Cookie @Body
│       ├── RequestDecoder.kt                # AbstractDecoder 実装
│       ├── RequestBinder.kt                 # suspend bind()
│       ├── ParameterSource.kt               # 入力ソースの抽象
│       ├── EndpointSpec.kt                  # エンドポイントのメタデータ
│       ├── EndpointBuilder.kt               # 完全形の builder
│       ├── EndpointDsl.kt                   # get / post / ... と汎用形の route<Req, Res>()
│       ├── TypedRouting.kt                  # ApplicationPlugin
│       ├── Around.kt                        # インターセプタ
│       ├── Validation.kt                    # validate / reject
│       └── Exceptions.kt                    # Violation / RequestBindingException / ValidationException
└── openapi/                                 # artifact: ktor-typed-routing-openapi
    └── src/main/kotlin/jp/kukv/typedrouting/openapi/
        └── OpenApiBridge.kt
```

### 2 artifact に分ける理由

`ktor-server-routing-openapi` の実行時注釈 API は `@ExperimentalKtorApi` である。
OpenAPI を使わない利用者に実験的 API を強制せず、公式 API のシグネチャ変更による
影響をこのモジュールに閉じ込める。

Gradle のプロジェクトパスは `:core` / `:openapi`、公開時の artifact 名は
`ktor-typed-routing-core` / `ktor-typed-routing-openapi` とする。
ルートプロジェクトと同名のサブモジュールは作らない。

名前空間は暫定で `jp.kukv` とする（group id: `jp.kukv`、パッケージ:
`jp.kukv.typedrouting` / `jp.kukv.typedrouting.openapi`）。

## 5. 公開 API

### 5.1 プラグインの設定

```kotlin
install(TypedRouting) {
    json = Json { ignoreUnknownKeys = true }
    around(RequestLogging)
}
```

### 5.2 エンドポイントの定義

エンドポイントは常に builder を持つ。`summary` / `description` / `status` / `error` /
`validate` / `around` / `handle` のすべてが builder の中に集まる。**書き方は 1 つしかない。**

グルーピングは標準の `route {}` に任せ、リーフにあたるメソッド別の関数
（`get` / `post` / `put` / `patch` / `delete` / `head` / `options`）を提供する。
名前を Ktor 標準と揃えることで、標準 DSL と混在しても語彙が浮かない。

```kotlin
routing {
    authenticate("jwt") {                        // 公式プラグインと共存する
        route("/orgs/{orgId}/users") {
            get<SearchUsersReq, List<User>> {
                summary = "Search users"
                handle { req -> userService.search(req.orgId, req.paging) }
            }

            post<CreateUserReq, User> {
                summary = "Create a user"
                description = "Creates a user in the given organization."
                status = Created
                error<ErrorBody>(Conflict)
                validate { if (it.user.name.isBlank()) reject("name", "must not be blank") }
                around(MaskSensitiveFields)
                handle { req -> userService.create(req.orgId, req.user) }
            }

            route("/{userId}") {
                get<GetUserReq, User>       { handle { req -> userService.find(req.userId) } }
                delete<DeleteUserReq, Unit> { handle { req -> userService.delete(req.userId) } }
            }
        }
    }
}
```

### 5.3 path 引数

メソッド別の関数は path を取れる。省略した場合は現在の `Route` にそのまま生える。

```kotlin
route("/orgs/{orgId}/users") {
    get<SearchUsersReq, List<User>> { ... }                 // 省略 = /orgs/{orgId}/users
    get<GetUserReq, User>("/{userId}") { ... }              // /orgs/{orgId}/users/{userId}
    post<BulkCreateReq, List<User>>("/bulk") { ... }        // /orgs/{orgId}/users/bulk
}
```

内部の組み立ては標準の `Route.get(path, body)` と同じで、
`createRouteFromPath(path).createChild(HttpMethodRouteSelector(method))` である。

### 5.4 汎用形

メソッドを動的に決めたい場合のために、型引数を取る `route` を用意する。

```kotlin
route<CreateUserReq, User>(config.method, "/bulk") {
    handle { req -> userService.create(req.orgId, req.user) }
}
```

標準の `route(path) {}` はグルーピング（子を持つ中間ノード）、こちらはリーフ
（ハンドラを持つ終端）で意味が異なるが、**型引数の有無で用途が判別できる**ため
同じ名前を用いる。型引数のない `route` は常に標準のグルーピングである。

### 5.5 ネストとパス変数

親の `route()` で宣言したパス変数は `call.pathParameters` にすべて入るため、
子の Req 型で `@Path val orgId` として受け取れる。OpenAPI のパスは `Route.path()` が
系譜を辿って合成するため、ネストしていても正しい完全パスが出る。

**実装時に確認する項目:** 標準の `Route.get(path, body)`、Resources の `Route.get<T>(body)`、
および標準の `Route.route(path, build)` / `Route.route(path, method, build)` との
オーバーロード解決。型引数の個数が異なる（本 DSL は 2 つ）ため解決できる見込みだが、
実装の最初に検証する。曖昧になる場合は汎用形の名前を再検討する。

### 5.6 標準 Route への落とし込み

```kotlin
createChild(HttpMethodRouteSelector(method))          // 条件 3 を満たす
    .also { it.attributes[EndpointSpecKey] = spec }
    .handle { /* bind → validate → around → handler → respond */ }  // 条件 2 を満たす
```

## 6. リクエストバインド

### 6.1 アノテーション

すべて `@SerialInfo` を付与し、`descriptor.getElementAnnotations(i)` から読み取る。
`kotlin-reflect` は使わない。

```kotlin
@SerialInfo @Target(AnnotationTarget.PROPERTY) annotation class Path(val name: String = "")
@SerialInfo @Target(AnnotationTarget.PROPERTY) annotation class Query(val name: String = "", val prefix: String = "")
@SerialInfo @Target(AnnotationTarget.PROPERTY) annotation class Header(val name: String = "", val prefix: String = "")
@SerialInfo @Target(AnnotationTarget.PROPERTY) annotation class Cookie(val name: String = "", val prefix: String = "")
@SerialInfo @Target(AnnotationTarget.PROPERTY) annotation class Body
```

`name` が空の場合はプロパティ名を使う。

### 6.2 入力を取らないエンドポイント

Req 型に `Unit` を指定した場合、バインドを行わずハンドラに `Unit` を渡す。
`Unit` は `@Serializable` ではないため、Decoder は起動しない。

```kotlin
get<Unit, HealthStatus>("/health") { handle { HealthStatus.OK } }
```

Req が `Unit` の場合、`handle` のラムダは引数を取らない。

### 6.3 入力ソースの対応

| 由来 | 入力元 |
|---|---|
| `@Path` | `call.pathParameters.getAll(name)` |
| `@Query` | `call.queryParameters.getAll(name)` |
| `@Header` | `call.request.headers.getAll(name)` |
| `@Cookie` | `call.request.cookies[name]` |
| `@Body` | リクエストボディ（6.6 参照） |

### 6.4 型の扱い

要素の descriptor の kind で扱いが決まる。

| 型の例 | descriptor kind | 扱い |
|---|---|---|
| `Int`, `String`, `ULong` | `PrimitiveKind` | スカラー |
| `UserStatus`（enum） | `SerialKind.ENUM` | スカラー |
| `UserId`（value class） | `StructureKind.CLASS` + `isInline` | スカラー |
| `LocalDate`（カスタム serializer） | `PrimitiveKind.STRING` | スカラー |
| `List<String>` | `StructureKind.LIST` | 複数値スカラー（`?tags=a&tags=b`） |
| `Paging`（data class） | `CLASS` / `OBJECT`（`isInline` でない） | グループ（再帰） |

この規則により、enum / カスタム serializer / コレクションが特別扱いなしに動作する。

**value class だけは明示が要る。** `@JvmInline value class` の descriptor は
`kind = StructureKind.CLASS` かつ `isInline = true` である。`isInline` を見ないと
value class がグループとして再帰され、内側のプロパティ名（`raw` など）を
パラメータとして探しに行ってしまう。グループ判定は
**「`CLASS` または `OBJECT` かつ `isInline` でない」** とする。

この規則は `:core`（`SerialDescriptor` 経由）と `:openapi`（`KType` から
`serializer(kType).descriptor` を得て判定）の両方で同一でなければならない。

### 6.5 グルーピング

構造型の要素はグループとして扱い、同じ入力ソースから再帰的にバインドする。
共通のパラメータ群を複数のエンドポイントで再利用できる。

```kotlin
@Serializable
data class Paging(
    val page: Int = 1,
    val limit: Int = 20,
)

@Serializable
data class Sorting(
    val sortBy: String = "createdAt",
    val order: SortOrder = SortOrder.Desc,
)

@Serializable
data class SearchUsersReq(
    @Path  val orgId: ULong,
    @Query val paging: Paging,      // ?page=2&limit=50
    @Query val sorting: Sorting,    // ?sortBy=name&order=asc
    @Query val q: String?,
)
```

規則:

- **接頭辞は付けない。** `Paging.page` は `page` であって `paging.page` ではない。
  必要な場合のみ `@Query(prefix = "filter.")` で明示する。
- **ネストは許可する。** グループの中の構造型も再帰的にグループとして扱う。
- **グループの中に `@Body` は書けない。** グループは単一の入力ソース内で閉じる。
  違反は起動時に例外とする。
- **`@Body` は 1 つの Req につき 1 つまで。** ボディは 1 度しか読めない。
  違反は起動時に例外とする。
- **`@Body` はスカラー型（`PrimitiveKind` / `ENUM` / `isInline`）に付けられない。** 構造型でなければならない。
  違反は起動時に例外とする。

  理由は実装上の制約である。kotlinx はスカラー要素に対して `decodeStringElement` などを呼び、
  `AbstractDecoder` のそれらは `final` で素の `decodeXxx()` に委譲する。そのため
  `decodeSerializableElement` に到達せず、ボディを読む経路に入れない。
  チェックが無いと直前の要素の値が静かに入る。
- **名前衝突は起動時に検出して例外とする。** 2 つのグループが同名のパラメータを
  持つ場合、`EndpointSpec` の構築時にチェックする。

### 6.6 ボディの読み取り

`kotlinx.serialization` の `Decoder` は suspend にできないため、Decoder 内から
`call.receive()` を呼べない。したがって次の順で処理する。

1. suspend 文脈で `call.receiveText()` によりボディ全体を読む
2. Decoder 内で、設定された `Json` インスタンスを用い、
   子 deserializer に対して `decodeFromString` する

ContentNegotiation は経由しない。初版は JSON 専用とする。
`StringFormat` を差し替え可能にしておき、将来の他フォーマット対応の余地を残す。

この方式を選ぶ理由は、実装が単純かつ確実であること、および OpenAPI のスキーマ生成と
同じ `kotlinx.serialization` の descriptor を唯一の情報源にできることである。

### 6.7 値が来なかったときの扱い

デフォルト値は Kotlin のデフォルト引数に任せる。Decoder が `decodeElementIndex` で
その要素をスキップすれば、kotlinx.serialization がデフォルト値を埋める。

| 宣言 | 値が来なかったとき |
|---|---|
| `@Query val page: Int = 1` | `1`（デフォルト値） |
| `@Query val q: String?` | `null` |
| `@Query val q: String? = null` | `null` |
| `@Query val status: UserStatus` | `400`（必須） |

2 行目は **kotlinx.serialization の通常の規則から意図的に外す**。通常「nullable かつ
デフォルト値なし」は必須であり、明示的な `null` の送出を要求する。しかしクエリ・ヘッダ・
クッキーでは「省略」と「`null` を明示」を区別できないため、欠落を `null` として扱う。

この逸脱が適用されるのは `@Path` / `@Query` / `@Header` / `@Cookie` に限る。
`@Body` の中身は通常の kotlinx.serialization の規則に従う。

### 6.7.1 グループに対する適用

グループ（構造型の要素）にも同じ規則を適用する。子孫の値が 1 つでもあるかどうかで
「値が来た」を判定する。子がまたグループの場合は接頭辞を合成して再帰的に見る。
子グループを無条件に「値あり」とすると、空のグループしか持たない nullable グループが
決して `null` にならない。

| 宣言 | 子の値が 1 つ以上ある | 子の値が 1 つも無い |
|---|---|---|
| `@Query val paging: Paging` | 展開する | 子それぞれの既定値で埋める |
| `@Query val paging: Paging = Paging(1, 20)` | 展開する | グループごと既定値を使う |
| `@Query val paging: Paging?` | 展開する | **`null`** |

**グループ自体が任意（既定値あり）または nullable の場合、その子孫はすべて任意である。**
サーバが省略を受け付けるため、OpenAPI も子を required と宣言してはならない。
`:openapi` の平坦化はこの継承を実装しなければならない。

### 6.8 バインド失敗

欠落（必須のもの）・型変換失敗は `RequestBindingException(violations)` として送出する。
複数フィールドの失敗は 1 つの例外にまとめて報告する。**ステータスコードは決めない。**
StatusPages 側で `400 Bad Request` などに割り当てる（9.3 参照）。

**ただしボディの JSON 構文エラーは対象外である。** `decodeFromString` が投げる
`SerializationException`（欠落フィールドを除く）は捕まえずに素通しする。
本ライブラリが例外を捕まえないという原則（11 章）を優先するためで、
利用者は StatusPages に `exception<SerializationException>` を足す必要がある。

## 7. バリデーション

エンドポイント定義の `validate` ブロックに書く。DB 参照など副作用を伴う検証も書ける。

```kotlin
validate { req ->
    if (req.paging.page < 1) reject("page", "must be >= 1")
    if (req.paging.limit !in 1..100) reject("limit", "must be 1..100")
}
```

`reject` は違反を蓄積し、ブロック終了時に 1 件以上あれば
`ValidationException(violations)` を送出する。**ステータスコードは決めない。**
StatusPages 側で `422 Unprocessable Content` などに割り当てる（9.3 参照）。

### 7.1 バリデーションライブラリの利用

`validate` の中身は通常の Kotlin コードなので、任意のライブラリを使える。
違反を `reject` に流すだけでよい。

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

**アダプタは同梱しない。** core をどのバリデーションライブラリにも依存させないため、
変換は利用者側の数行に委ねる。README とサンプルに上記の例を載せる。

## 8. レスポンスとステータス

ハンドラの戻り値がレスポンスボディになる。成功ステータスの既定値は次のとおり。

- 戻り値が `Unit` の場合: `204 No Content`（ボディを書かない）
- それ以外: `200 OK`

builder の `status` で上書きする。

## 9. エラー処理

**エラー処理は StatusPages に全面的に委ねる。** 本プラグインは例外を投げるだけで、
ステータスコードやエラーボディの決定には一切関与しない。

### 9.1 その判断の理由

**分断が起きない。** ルートが一致しなかった `404`、メソッド不一致の `405`、
標準 `routing {}` のハンドラで起きた例外、auth の challenge 失敗は、いずれも
本プラグインの外側で起きる。自前のマッピング機構を持つと、例外処理が必ず 2 箇所に分かれる。

**自前化の主要な価値が重複している。** StatusPages は既に例外のクラス階層で
ハンドラを解決している (`StatusPages.kt:44-54`)。

```kotlin
fun findHandlerByValue(cause: Throwable): HandlerFunction? {
    val keys = exceptions.keys.filter { cause.instanceOf(it) }
    if (keys.isEmpty()) return null
    if (keys.size == 1) return exceptions[keys.single()]
    val key = selectNearestParentClass(cause, keys)
    return exceptions[key]
}
```

`on(CallFailed)` フックで捕まえるため、ルートハンドラ内で送出した例外はそのまま届く。

**エラーボディを `around` で観測する要件がない。** どんなエラーが起きたかはログで足りる。
これが自前化する唯一の実質的な利得だったため、放棄する。

### 9.2 core が提供するもの

バインドと検証は本プラグインの内部で起きるため、この 2 つの例外だけは core が定義する。
いずれも違反の一覧を持ち、ステータスコードは持たない。

```kotlin
data class Violation(val path: String, val message: String)

class RequestBindingException(val violations: List<Violation>) : Exception()
class ValidationException(val violations: List<Violation>) : Exception()
```

エラーボディの形式は core では規定しない。RFC 9457 の Problem Details にするか
独自形式にするかは利用者が決める。

### 9.3 利用者側の設定

```kotlin
install(StatusPages) {
    exception<RequestBindingException> { call, e ->
        call.respond(BadRequest, ErrorBody("invalid request", e.violations))
    }
    exception<ValidationException> { call, e ->
        call.respond(UnprocessableEntity, ErrorBody("validation failed", e.violations))
    }

    // ドメイン例外も同じ場所に並ぶ
    exception<UserNotFound>        { call, e -> call.respond(NotFound, ErrorBody(e.message)) }
    exception<InsufficientBalance> { call, e -> call.respond(PaymentRequired, ErrorBody(e.message)) }
    exception<Throwable>           { call, _ -> call.respond(InternalServerError, ErrorBody("internal error")) }

    // プラグインの外側の話も同じ場所に書ける
    status(NotFound) { call, _ -> call.respond(NotFound, ErrorBody("not found")) }
}
```

`ErrorBody` は利用者が定義する型である。この設定例は README に載せる。

### 9.4 提供しないもの

- `install(TypedRouting) { errors { ... } }` — 実行時のマッピング機構は持たない
- `ApiException` / `NotFound` / `Conflict` などの例外階層 — 利用者のドメイン例外か、
  好みのライブラリを使えばよい。ライブラリ固有の例外型を押し付けない
- StatusPages 用の既定設定を入れる拡張関数 — core を StatusPages に依存させない。
  上記 3 行は README からコピーすれば済む

### 9.5 OpenAPI との関係

builder の `error<T>(status)` は **OpenAPI のドキュメント宣言としてのみ**存在する。
実行時のマッピングとは完全に独立しているため、役割が明確になる。

```kotlin
post<CreateUserReq, User> {
    error<ErrorBody>(Conflict)   // ドキュメントに出るだけ
    handle { req -> userService.create(req.orgId, req.user) }
}
```

## 10. around インターセプタ

```kotlin
fun interface Around {
    suspend fun invoke(ctx: EndpointContext, proceed: suspend () -> Any?): Any?
}
```

アプリ全体（`install(TypedRouting) { around(...) }`）とエンドポイント単位
（builder の `around(...)`）の両方で登録でき、アプリ → エンドポイントの順に外側から適用する。

`EndpointContext` が持つ値と、それが利用可能になる時点は次のとおり。

| プロパティ | 型 | 可用性 |
|---|---|---|
| `call` | `RoutingCall` | 常時 |
| `spec` | `EndpointSpec` | 常時 |
| `request` | `Any?` | `proceed()` の後（バインド前は `null`） |
| `status` | `HttpStatusCode?` | `proceed()` の後 |

`request` と `status` が `proceed()` の後にしか埋まらないのは、バインド・検証・ハンドラの
実行がいずれも `proceed()` の内側で起きるためである。

`proceed()` の戻り値は、`call.respond` される成功ボディである。`around` が別の値を返した
場合、その値が respond されるボディを置き換える。値を観測するだけであれば `proceed()` の
戻り値をそのまま返す。

**例外は `proceed()` から素通しで送出される。** 本プラグインは例外を捕まえないため、
`around` から見ると `proceed()` が throw する。ログのために捕まえた場合は、
StatusPages に届くよう必ず再スローする。

```kotlin
around { ctx, proceed ->
    log.info("--> {} {}", ctx.call.request.httpMethod, ctx.call.request.path())
    try {
        val res = proceed()
        log.debug("res body: {}", res)
        res
    } catch (e: Throwable) {
        log.warn("failed: {}", e.toString())   // どんなエラーが起きたかはここで分かる
        throw e                                 // StatusPages に委ねる
    }
}
```

不満④に対しては、**成功レスポンスのボディを型付きのまま観測できる**ことで応える。
エラーレスポンスのボディは StatusPages のハンドラ内で手元にあるため、
ログが取れないケースはない。

## 11. 実行フロー

```
RoutingHandler
  └─ around チェーン（アプリ → エンドポイント、外→内）
       ├─ RequestBinder.bind(call, reqSerializer)   失敗 → RequestBindingException
       ├─ validate(req)                             失敗 → ValidationException
       ├─ handler(req): Res
       └─ call.respond(status, res)

  例外はどこでも捕まえずに送出される
       ↓
  ルートパイプライン → アプリケーションパイプライン
       ↓
  StatusPages の on(CallFailed)
```

本プラグインには try/catch がない。これが「エラー処理を StatusPages に委ねる」ことの
実装上の意味である。

## 12. OpenAPI ブリッジ

`ktor-typed-routing-openapi` は `EndpointSpec` を読み、公式の `Route.describe {}` に
流し込む。

- Req 型の descriptor を平坦化し、`@Path` / `@Query` / `@Header` / `@Cookie` の要素を
  OpenAPI の `parameters` に展開する。グループは接頭辞の規則に従って平坦化する。
- **パラメータ名とグループ判定は `SerialDescriptor` から取る。** 宣言順を得るために
  reflection を使うが、名前は `@SerialName` を、グループかどうかはプロパティ単位の
  `@Serializable(with = ...)` を反映した要素記述子を見る。Kotlin のプロパティ名や
  プロパティの型から判定すると `:core` の束縛と食い違う。descriptor と対応づける際は
  `@Transient` のプロパティを除いてから添字を合わせる。
- `@Body` の要素と Res 型は `requestBody` / `responses` のスキーマにする。
  スキーマ推論は公式の `KotlinxSerializerJsonSchemaInference` を使う。
- **成功レスポンスは `Res = Unit`（スキーマなし）でも必ず 1 件出す。**
  OAS 3.1 は Responses Object に最低 1 つの応答コードを要求するため、
  `204 No Content` のエンドポイントで `responses` が空になってはならない。
- builder の `summary` / `description` / `status` / `error` を対応する項目に反映する。

### 12.1 確認済みの公式 API（Ktor 3.5.2）

`javap` で確認した形。`Operation.Builder` は入れ子のビルダになっている。

```
io.ktor.openapi.Operation$Builder : JsonSchemaInference
  var summary: String?;  var description: String?
  fun parameters(block: Parameters.Builder.() -> Unit)
  fun requestBody(block: RequestBody.Builder.() -> Unit)
  fun responses(block: Responses.Builder.() -> Unit)

io.ktor.openapi.Parameters$Builder
  fun path / query / header / cookie (name: String, block: Parameter.Builder.() -> Unit)

io.ktor.openapi.Parameter$Builder : JsonSchemaInference
  var required: Boolean;  var description: String?;  var schema: JsonSchema?

io.ktor.openapi.RequestBody$Builder : JsonSchemaInference
  var required: Boolean;  var schema: JsonSchema?

io.ktor.openapi.Responses$Builder
  fun response(code: Int, block: Response.Builder.() -> Unit)
  operator fun invoke(status: HttpStatusCode, block: Response.Builder.() -> Unit)
  fun default(block: Response.Builder.() -> Unit)

io.ktor.openapi.Response$Builder : JsonSchemaInference
  var description: String?;  var schema: JsonSchema?
  fun content(block: MediaType.Builder.() -> Unit)
  operator fun invoke(contentType: ContentType, block: MediaType.Builder.() -> Unit)
  fun headers(block: Headers.Builder.() -> Unit);  fun link(name, block)

io.ktor.openapi.MediaType$Builder : JsonSchemaInference
  var schema: JsonSchema?
  fun example(name: String, example: ExampleObject);  fun encoding(name, encoding)

io.ktor.openapi.JsonSchemaInference
  fun buildSchema(type: KType): JsonSchema
```

`Response.Builder` にも `RequestBody.Builder` にも `schema` プロパティが直接あり、
これを設定すると `describe` 側が既定のコンテントタイプ（`application/json`）の
`MediaType` に展開する。`content { }` を経由する必要はない。

### 12.2 `:openapi` が `KType` を入力にする理由

**スキーマ推論の公開入口は `buildSchema(KType)` だけである。**
`KotlinxSerializerJsonSchemaInference.buildSchemaFromDescriptor` も存在するが
`internal` で外から呼べない。

したがって `:openapi` は `SerialDescriptor` ではなく `KType` を入力とし、
アノテーションの読み取り・グループの平坦化・必須判定も `kotlin-reflect` で行う。
`EndpointSpec` は Req / Res の `KType` を保持する。

`kotlin-reflect` を使うのは `:openapi` だけである。`:core` は
`SerialDescriptor` だけで完結し、reflection を使わない。両者は同じ入力に対して
同じパラメータ名とグループ展開を返さなければならず、テストで突き合わせる。

**パラメータ名は両モジュールとも `SerialDescriptor.getElementName(index)` を使う。**
Kotlin のプロパティ名を使ってはならない。`@SerialName("user_id")` が付いていると
`:core` は `user_id` をバインドするため、プロパティ名で文書を作ると
サーバが提供しない API を宣言することになる。

ドキュメントの組み立て・`$ref` 解決・スキーマ命名・YAML/JSON 出力・Swagger UI 配信は
公式実装に委ねる。

### 12.3 ドキュメントの取り出しと注意点

`ktor-server-routing-openapi` 3.5.2 には `Route.openAPI(path)` のような
ドキュメント配信ルートは含まれない（パッケージ `io.ktor.server.routing.openapi` の
公開 API は `describe` / `hide` / `mapToPathItems` / `OpenApiDocSource` / `OpenApiDoc.plus` のみ）。
ドキュメントの生成は次のいずれかで行う。

```kotlin
// ルートツリーからドキュメントを組み立ててシリアライズする
val text = OpenApiDocSource.Routing()
    .read(application, OpenApiDoc(info = OpenApiInfo(title = "...", version = "...")))
    .content

// あるいはドキュメントモデルだけを組み立てる
val doc = OpenApiDoc(info = ...) + application.plugin(RoutingRoot).descendants()
```

配信ルート（Swagger UI など）が要るなら、別途 `ktor-server-swagger` /
`ktor-server-openapi` を利用者側で足す。

ルートツリーの根は `application.plugin(RoutingRoot)` で取る。
`Application.routingRoot` も 3.5.2 の `ktor-server-core` に存在し
（`RoutingIntrospectionKt.getRoutingRoot(Application)` を javap で確認）、
実装は `pluginOrNull(RoutingRoot) ?: throw IllegalStateException(...)` なので
両者は等価である。違いは未 install 時の例外型だけ。

`descendants()` は `Route` が継承する `io.ktor.util.collections.TreeLike` のメンバである。

`OpenApiDocSource.Routing` はルートツリー全体を列挙するため、`describe` していない
素の Ktor ルートも空の Operation（`"/health":{"get":{}}`）として文書に現れる。
ブリッジが `EndpointSpec` の無いルートを読み飛ばすとは「文書に出さない」ではなく
「何のメタデータも足さない」の意。文書から消したいルートには公式の `Route.hide()` を使う。

`EndpointSpec.responseType` が `null`（`Res = Unit`）でも成功レスポンス自体は必ず宣言する。
OAS 3.1 は Responses Object に最低 1 件を要求するため、スキーマだけを条件付きにする。
出力は `"responses":{"204":{"description":""}}` になる。

Gradle のコンパイラプラグインによるコード推論は本 DSL では空振りするため、
`codeInferenceEnabled = false` とし、実行時注釈に一本化することを推奨する。
実行時注釈が最優先されるため、有効なままでも本設計の定義が勝つ。

## 13. 既存プラグインとの共存

標準の `Route` ツリー上にいるため、以下がそのまま使える。

- `authenticate {}`（auth）
- route スコープの `install(ContentNegotiation)` / `install(CORS)`

  ただし **型付きエンドポイント自身は ContentNegotiation を経由しない。** 6.6 のとおり
  レスポンスは `respondText` で直接書き出すため、ContentNegotiation が無くても動く。
  ここで言う「使える」は次の 2 つを意味する。

  1. 型付きエンドポイントを含むルートに route スコープで install しても起動時に落ちず、
     型付きエンドポイントが正常に動く（Ktor 内部の `is RoutingNode` 判定を通る）
  2. 同じルート配下の**標準エンドポイント**は、その ContentNegotiation を通常どおり使える
- `Route.rateLimit {}`
- `webSocket()` / `sse()`（標準 DSL のまま併用）
- `get<Resource>()`（Resources。標準 DSL のまま併用）
- `ktor-server-metrics` / `metrics-micrometer` のルート単位メトリクス
- `openAPI()` / `swaggerUI()`

`routing {}` の中で標準 DSL と本 DSL を混在させられるため、段階的に移行できる。

## 14. テスト戦略

1. **RequestDecoder の単体テスト** — 型カタログを網羅する。
   primitive / nullable / デフォルト値 / enum / value class / `List<T>` /
   カスタム serializer / グループ / ネストしたグループ / 欠落 / 型変換失敗 / 名前衝突
   加えて、6.7 の「値が来なかったときの扱い」の 4 パターンを個別に検証する
2. **エンドポイントの統合テスト** — `testApplication` で
   bind → validate → handle → respond の一巡と、各エラー経路を検証する。
   ネストした `route {}` の下でパス変数が正しくバインドされることを含む
3. **例外の透過テスト** — バインド失敗・検証失敗・ハンドラ内の例外が
   いずれも捕まえられずに送出され、StatusPages のハンドラに到達することを検証する。
   `around` で捕まえて再スローした場合も同様であることを含む
4. **公式プラグインとの共存テスト** — `authenticate {}` および
   route スコープの `install(ContentNegotiation)` と併用して壊れないことを確認する
5. **OpenAPI のスナップショットテスト** — 生成された JSON をゴールデンファイルと比較する

## 15. 将来の検討事項

初版には含めないが、必要になれば検討する。

- JSON 以外のリクエストボディ形式（`StringFormat` の差し替えで対応）
- 単一クエリパラメータに JSON をエンコードして渡すケース
- KSP によるバインダ生成（実行時コストの削減と、パス文字列と `@Path` の
  不一致のコンパイル時検出）
- `webSocket()` / `sse()` に対する型付きラッパー
- バリデーションライブラリのアダプタ artifact（`-yavi` / `-jakarta`）。
  利用者側の変換が数行で済むため初版では作らない

## 16. 参照

調査時点のソース位置。

- `ktor/ktor-server/ktor-server-core/common/src/io/ktor/server/routing/RoutingRoot.kt`
- `ktor/ktor-server/ktor-server-core/common/src/io/ktor/server/routing/RoutingNode.kt`
- `ktor/ktor-server/ktor-server-core/common/src/io/ktor/server/application/CreatePluginUtils.kt`
- `ktor/ktor-server/ktor-server-core/common/src/io/ktor/server/application/ApplicationPlugin.kt`
- `ktor/ktor-server/ktor-server-core/api/ktor-server-core.api`
- `ktor/ktor-server/ktor-server-plugins/ktor-server-routing-openapi/`
- https://ktor.io/docs/openapi-spec-generation.html
