package jp.kukv.typedrouting

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.createRouteFromPath
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * 型付きエンドポイントを 1 つ定義する。
 *
 * [requestSerializer] が `null` のときはバインドを行わず `Unit` を渡す。
 * [responseSerializer] が `null` のときはボディを書かず、既定で `204 No Content` を返す。
 *
 * バインド・検証・ハンドラの例外は一切捕まえない。StatusPages に委ねるためである。
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

        // アプリ全体のインターセプタがエンドポイント個別のものを包む。登録順に外側から。
        val wrapped = (config.around + builder.interceptors)
            .foldRight(chain) { interceptor, next -> { interceptor.invoke(ctx, next) } }

        val body = wrapped()
        respondResult(call, spec.status, responseSerializer, config.json, body)
    }

    return endpoint
}

/**
 * 成功ボディを書き出す。
 *
 * ContentNegotiation を経由せず自前で JSON を書くのは、
 * バインド側が ContentNegotiation を経由しない（spec 6.6）のと対称にするためである。
 */
private suspend fun <Res> respondResult(
    call: RoutingCall,
    status: HttpStatusCode,
    responseSerializer: KSerializer<Res>?,
    json: Json,
    body: Any?,
) {
    if (responseSerializer == null) {
        call.respond(status)
        return
    }
    @Suppress("UNCHECKED_CAST")
    val text = json.encodeToString(responseSerializer, body as Res)
    call.respondText(text, ContentType.Application.Json, status)
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
