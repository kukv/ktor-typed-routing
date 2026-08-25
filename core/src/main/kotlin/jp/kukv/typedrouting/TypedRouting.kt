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
