package jp.kukv.typedrouting.openapi

import io.ktor.openapi.Parameter
import io.ktor.server.application.Application
import io.ktor.server.application.plugin
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi
import jp.kukv.typedrouting.EndpointSpec
import jp.kukv.typedrouting.EndpointSpecKey

/**
 * 配下の型付きエンドポイントを走査し、公式の `describe {}` にメタデータを流し込む。
 *
 * ルートツリーを 1 度だけ走査するため、**すべてのエンドポイントを定義し終えた後に呼ぶこと**。
 * 呼び出し時点で存在しないエンドポイントはドキュメントに載らない。
 *
 * [EndpointSpec] を持たないルート（素の Ktor で書かれたエンドポイントなど）は
 * 黙って読み飛ばす。
 */
@OptIn(ExperimentalKtorApi::class)
public fun Route.describeTypedEndpoints() {
    descendants().forEach { node ->
        val spec = node.attributes.getOrNull(EndpointSpecKey) ?: return@forEach
        node.applyEndpointSpec(spec)
    }
}

/**
 * アプリケーション全体の型付きエンドポイントに対して [describeTypedEndpoints] を行う。
 *
 * ルーティングプラグインがインストールされていること（`routing {}` を 1 度でも呼んでいること）が前提。
 * [Route.describeTypedEndpoints] と同じく、すべてのエンドポイントを定義し終えた後に呼ぶ。
 */
public fun Application.describeTypedEndpoints() {
    plugin(RoutingRoot).describeTypedEndpoints()
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
                    val configure: Parameter.Builder.() -> Unit = {
                        // OpenAPI の仕様上、パスパラメータはつねに required でなければならない。
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
