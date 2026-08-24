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
| ② | バリデーションがしにくい | エンドポイント定義の `validate` ブロックに集約し、失敗を 422 に一元化 |
| ③ | レスポンスを明示的に `call` に詰めなければならない | ハンドラの戻り値がレスポンスボディになる |
| ④ | レスポンスボディのログが `call` の中で容易に取れない | `around` インターセプタが型付きの req / res を受け取る |
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

- 型付きエンドポイント DSL（簡易形と完全形）
- `kotlinx.serialization` の `Decoder` を用いたリクエストバインド
- エンドポイント単位・アプリ単位のバリデーション
- `around` インターセプタ
- 例外 → HTTP ステータス / エラーボディのマッピング
- 公式 `ktor-server-routing-openapi` へのブリッジ

### やらないこと

- 標準 routing の置き換え（`routing {}` はそのまま使える。共存する）
- ルートマッチングアルゴリズムの変更（標準の DFS + `RouteSelector` をそのまま使う）
- JVM 以外のプラットフォーム対応
- JSON 以外のリクエストボディ形式（拡張余地は残すが初版では扱わない）
- OpenAPI ドキュメントの生成・配信そのもの（公式実装に委ねる）

## 4. モジュール構成

```
ktor-typed-routing/
├── settings.gradle.kts
├── build.gradle.kts
├── ktor-typed-routing/                      # コア
│   └── src/main/kotlin/io/ktor/typed/routing/
│       ├── Annotations.kt                   # @Path @Query @Header @Cookie @Body
│       ├── RequestDecoder.kt                # AbstractDecoder 実装
│       ├── RequestBinder.kt                 # suspend bind()
│       ├── ParameterSource.kt               # 入力ソースの抽象
│       ├── EndpointSpec.kt                  # エンドポイントのメタデータ
│       ├── EndpointBuilder.kt               # 完全形の builder
│       ├── EndpointDsl.kt                   # Route.endpoint()
│       ├── TypedRouting.kt                  # ApplicationPlugin
│       ├── Around.kt                        # インターセプタ
│       ├── Validation.kt                    # validate / reject
│       └── errors/
│           ├── ApiException.kt              # 例外階層
│           ├── ErrorBody.kt
│           └── ErrorMapping.kt
└── ktor-typed-routing-openapi/              # 公式 OpenAPI へのブリッジ
    └── src/main/kotlin/io/ktor/typed/routing/openapi/
        └── OpenApiBridge.kt
```

### 2 artifact に分ける理由

`ktor-server-routing-openapi` の実行時注釈 API は `@ExperimentalKtorApi` である。
OpenAPI を使わない利用者に実験的 API を強制せず、公式 API のシグネチャ変更による
影響をこのモジュールに閉じ込める。

## 5. 公開 API

### 5.1 プラグインの設定

```kotlin
install(TypedRouting) {
    json = Json { ignoreUnknownKeys = true }

    around(RequestLogging)

    errors {
        map<InsufficientBalance> { PaymentRequired to ErrorBody(it.message) }
        map<OptimisticLockError> { Conflict to ErrorBody("conflict") }
        fallback { InternalServerError to ErrorBody("internal error") }
    }
}
```

### 5.2 簡易形

設定が不要な場合に使う。builder を持たないため、成功ステータスは引数で受ける。

```kotlin
endpoint(Post, "/users", Created) { req: CreateUserReq -> userService.create(req) }
endpoint(Get,  "/health")         { _: Unit -> HealthStatus.OK }
```

### 5.3 完全形

メタデータを持つ場合に使う。成功ステータスを含むすべての宣言が builder に集まる。

```kotlin
routing {
    authenticate("jwt") {                        // 公式プラグインと共存する
        endpoint<CreateUserReq, User>(Post, "/orgs/{orgId}/users") {
            summary = "Create a user"
            description = "Creates a user in the given organization."
            status = Created
            errors(Conflict to ErrorBody::class)
            validate { if (it.user.name.isBlank()) reject("name", "must not be blank") }
            around(MaskSensitiveFields)
            handle { req -> userService.create(req.orgId, req.user) }
        }
    }
}
```

簡易形と完全形で `status` の置き場所は異なるが、**それぞれの形に書き方は 1 つしかない**。
簡易形には builder が存在せず、完全形には引数が存在しない。

### 5.4 標準 Route への落とし込み

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
@SerialInfo @Target(AnnotationTarget.PROPERTY) annotation class Header(val name: String = "")
@SerialInfo @Target(AnnotationTarget.PROPERTY) annotation class Cookie(val name: String = "")
@SerialInfo @Target(AnnotationTarget.PROPERTY) annotation class Body
```

`name` が空の場合はプロパティ名を使う。

### 6.2 入力を取らないエンドポイント

Req 型に `Unit` を指定した場合、バインドを行わずハンドラに `Unit` を渡す。
`Unit` は `@Serializable` ではないため、Decoder は起動しない。

```kotlin
endpoint(Get, "/health") { _: Unit -> HealthStatus.OK }
```

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
| `UserId`（value class） | inline → `PrimitiveKind` | スカラー |
| `LocalDate`（カスタム serializer） | `PrimitiveKind.STRING` | スカラー |
| `List<String>` | `StructureKind.LIST` | 複数値スカラー（`?tags=a&tags=b`） |
| `Paging`（data class） | `StructureKind.CLASS` / `OBJECT` | グループ（再帰） |

この規則により、value class / enum / カスタム serializer / コレクションが
特別扱いなしに動作する。

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

### 6.7 バインド失敗

欠落・型変換失敗は `RequestBindingException(field, reason)` として送出し、
`400 Bad Request` にマッピングする。複数フィールドの失敗はまとめて報告する。

## 7. バリデーション

エンドポイント定義の `validate` ブロックに書く。DB 参照など副作用を伴う検証も書ける。

```kotlin
validate { req ->
    if (req.paging.page < 1) reject("page", "must be >= 1")
    if (req.paging.limit !in 1..100) reject("limit", "must be 1..100")
}
```

`reject` は違反を蓄積し、ブロック終了時に 1 件以上あれば
`ValidationException(violations)` を送出する。既定のマッピングは
`422 Unprocessable Content`。

## 8. レスポンスとステータス

ハンドラの戻り値がレスポンスボディになる。成功ステータスの既定値は次のとおり。

- 戻り値が `Unit` の場合: `204 No Content`（ボディを書かない）
- それ以外: `200 OK`

簡易形では第 3 引数、完全形では builder の `status` で上書きする。

## 9. エラーマッピング

### 9.1 ライブラリ提供の例外階層

```kotlin
sealed class ApiException(
    val status: HttpStatusCode,
    override val message: String,
    val code: String? = null,
) : Exception(message)

class BadRequest(message: String, code: String? = null)          : ApiException(HttpStatusCode.BadRequest, message, code)
class Unauthorized(message: String, code: String? = null)        : ApiException(HttpStatusCode.Unauthorized, message, code)
class Forbidden(message: String, code: String? = null)           : ApiException(HttpStatusCode.Forbidden, message, code)
class NotFound(message: String, code: String? = null)            : ApiException(HttpStatusCode.NotFound, message, code)
class Conflict(message: String, code: String? = null)            : ApiException(HttpStatusCode.Conflict, message, code)
class UnprocessableContent(message: String, code: String? = null): ApiException(HttpStatusCode.UnprocessableEntity, message, code)
```

`RequestBindingException` と `ValidationException` はこの階層の外に置き、
違反の詳細を保持したうえで既定で 400 / 422 にマッピングする。

すぐに使える。

```kotlin
throw NotFound("user not found")
throw Conflict("already exists", code = "USER_DUP")
```

### 9.2 ドメイン例外の登録

ドメイン層をライブラリに依存させたくない場合は、マッピングを登録する。

```kotlin
errors {
    map<InsufficientBalance> { PaymentRequired to ErrorBody(it.message) }
    fallback { InternalServerError to ErrorBody("internal error") }
}
```

解決順序は「`ApiException` → 登録されたマッピング（登録順） → `fallback`」。

### 9.3 OpenAPI への反映

エラーレスポンスの宣言は builder に書く。実行時のマッピングとは独立している。

```kotlin
errors(NotFound to ErrorBody::class, Conflict to ErrorBody::class)
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

`request` と `status` が `proceed()` の後にしか埋まらないのは、バインドが
`proceed()` の内側で起きるためである。バインド自体が失敗した場合、`request` は
`null` のままで `status` は 400 になる。

`proceed()` の戻り値は、実際に `call.respond` されるボディである。正常時はハンドラの
戻り値、エラー時はマッピング後のエラーボディが入る。`around` が別の値を返した場合、
その値が respond されるボディを置き換える。値を観測するだけであれば `proceed()` の
戻り値をそのまま返す。

`around` は例外マッピングよりも外側に位置するため、**エラーレスポンスのボディも
`proceed()` の戻り値として観測できる**。これが不満④に対する解となる。

## 11. 実行フロー

```
RoutingHandler
  └─ around チェーン（アプリ → エンドポイント、外→内）
       ├─ try
       │    ├─ RequestBinder.bind(call, reqSerializer)   失敗 → 400
       │    ├─ validate(req)                             失敗 → 422
       │    ├─ handler(req): Res
       │    └─ call.respond(status, res)
       └─ catch: ErrorMapping.resolve(e) → call.respond(status, body)
```

## 12. OpenAPI ブリッジ

`ktor-typed-routing-openapi` は `EndpointSpec` を読み、公式の `Route.describe {}` に
流し込む。

- Req 型の descriptor を平坦化し、`@Path` / `@Query` / `@Header` / `@Cookie` の要素を
  OpenAPI の `parameters` に展開する。グループは接頭辞の規則に従って平坦化する。
- `@Body` の要素と Res 型は `requestBody` / `responses` のスキーマにする。
  スキーマ推論は公式の `KotlinxSerializerJsonSchemaInference` を使う。
- builder の `summary` / `description` / `status` / `errors` を対応する項目に反映する。

ドキュメントの組み立て・`$ref` 解決・スキーマ命名・YAML/JSON 出力・Swagger UI 配信は
公式実装に委ねる。

利用者は公式の手順どおりに設定する。

```kotlin
routing {
    openAPI("docs")     // または swaggerUI(...)
}
```

Gradle のコンパイラプラグインによるコード推論は本 DSL では空振りするため、
`codeInferenceEnabled = false` とし、実行時注釈に一本化することを推奨する。
実行時注釈が最優先されるため、有効なままでも本設計の定義が勝つ。

## 13. 既存プラグインとの共存

標準の `Route` ツリー上にいるため、以下がそのまま使える。

- `authenticate {}`（auth）
- route スコープの `install(ContentNegotiation)` / `install(CORS)`
- `Route.rateLimit {}`
- `webSocket()` / `sse()`（標準 DSL のまま併用）
- `get<Resource>()`（Resources。標準 DSL のまま併用）
- `ktor-server-metrics` / `metrics-micrometer` のルート単位メトリクス
- `openAPI()` / `swaggerUI()`

`routing {}` の中で標準 DSL と `endpoint()` を混在させられるため、段階的に移行できる。

## 14. テスト戦略

1. **RequestDecoder の単体テスト** — 型カタログを網羅する。
   primitive / nullable / デフォルト値 / enum / value class / `List<T>` /
   カスタム serializer / グループ / ネストしたグループ / 欠落 / 型変換失敗 / 名前衝突
2. **エンドポイントの統合テスト** — `testApplication` で
   bind → validate → handle → respond の一巡と、各エラー経路を検証する
3. **公式プラグインとの共存テスト** — `authenticate {}` および
   route スコープの `install(ContentNegotiation)` と併用して壊れないことを確認する
4. **OpenAPI のスナップショットテスト** — 生成された JSON をゴールデンファイルと比較する

## 15. 将来の検討事項

初版には含めないが、必要になれば検討する。

- JSON 以外のリクエストボディ形式（`StringFormat` の差し替えで対応）
- 単一クエリパラメータに JSON をエンコードして渡すケース
- KSP によるバインダ生成（実行時コストの削減と、パス文字列と `@Path` の
  不一致のコンパイル時検出）
- `webSocket()` / `sse()` に対する型付きラッパー

## 16. 参照

調査時点のソース位置。

- `ktor/ktor-server/ktor-server-core/common/src/io/ktor/server/routing/RoutingRoot.kt`
- `ktor/ktor-server/ktor-server-core/common/src/io/ktor/server/routing/RoutingNode.kt`
- `ktor/ktor-server/ktor-server-core/common/src/io/ktor/server/application/CreatePluginUtils.kt`
- `ktor/ktor-server/ktor-server-core/common/src/io/ktor/server/application/ApplicationPlugin.kt`
- `ktor/ktor-server/ktor-server-core/api/ktor-server-core.api`
- `ktor/ktor-server/ktor-server-plugins/ktor-server-routing-openapi/`
- https://ktor.io/docs/openapi-spec-generation.html
