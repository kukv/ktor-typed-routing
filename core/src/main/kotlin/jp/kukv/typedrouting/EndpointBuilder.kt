package jp.kukv.typedrouting

import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.KtorDsl
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
     *
     * これはドキュメント宣言に過ぎず、実行時の挙動には一切関与しない。エラーレスポンスは
     * StatusPages プラグインが生成するのであって、このライブラリではない。
     * `error<ErrorBody>(NotFound)` を呼んだからといって、サーバーの実際のレスポンスが
     * 変わるわけではないことに注意すること。
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

/**
 * 登録された `validate` ブロックを実行する。違反は 1 件も漏らさず集め、
 * ブロックを抜けた後にまとめて 1 つの [ValidationException] として投げる。
 */
internal suspend fun <Req> EndpointBuilder<Req, *>.runValidation(request: Req) {
    val block = validator ?: return
    val scope = ValidationScope()
    scope.block(request)
    if (scope.violations.isNotEmpty()) throw ValidationException(scope.violations.toList())
}
