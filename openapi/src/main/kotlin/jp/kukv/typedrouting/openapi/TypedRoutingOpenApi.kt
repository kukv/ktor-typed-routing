package jp.kukv.typedrouting.openapi

import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.pluginOrNull
import io.ktor.server.routing.RoutingRoot

/**
 * 起動完了時に [describeTypedEndpoints] を 1 度だけ行うプラグイン。
 *
 * 起動が完了した時点でルートツリーは出来上がっているので、`describeTypedEndpoints()` を
 * 「すべてのエンドポイントを定義し終えた後に」自分で呼ぶ必要がなくなる。`install` の位置は
 * どこでもよい。
 *
 * ルーティングを 1 度も使っていないアプリでは何もしない。
 */
public val TypedRoutingOpenApi: ApplicationPlugin<Unit> =
    createApplicationPlugin("TypedRoutingOpenApi") {
        on(MonitoringEvent(ApplicationStarted)) { application ->
            application.pluginOrNull(RoutingRoot)?.describeTypedEndpoints()
        }
    }
