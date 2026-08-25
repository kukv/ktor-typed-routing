package jp.kukv.typedrouting

import io.ktor.http.Headers
import io.ktor.http.Parameters
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
