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
