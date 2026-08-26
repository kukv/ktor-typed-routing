package jp.kukv.typedrouting.example

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.OpenApiInfo
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get as ktorGet
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.routing
import jp.kukv.typedrouting.RequestBindingException
import jp.kukv.typedrouting.TypedRouting
import jp.kukv.typedrouting.ValidationException
import jp.kukv.typedrouting.openapi.TypedRoutingOpenApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

fun main() {
    embeddedServer(Netty, port = 8080) { module() }.start(wait = true)
}

/**
 * サンプルアプリの本体。
 *
 * [users] を引数にしてあるのは、テストから状態を覗けるようにするためだけである。
 */
fun Application.module(users: UserRepository = UserRepository()) {
    // エンドポイントを定義するより前に install する必要がある。
    install(TypedRouting) {
        json = Json { ignoreUnknownKeys = true }

        // 成功レスポンスのボディを型付きのまま観測できる。
        around { ctx, proceed ->
            try {
                val body = proceed()
                log.info("{} {} -> {}", ctx.spec.method.value, ctx.call.request.uri, ctx.status)
                body
            } catch (cause: Throwable) {
                log.warn("{} {} failed: {}", ctx.spec.method.value, ctx.call.request.uri, cause.toString())
                throw cause // 再スローしないと StatusPages に届かず、応答が返らなくなる
            }
        }
    }

    // 起動完了時にエンドポイントのメタデータを OpenAPI に流し込む。
    install(TypedRoutingOpenApi)

    // 型付きエンドポイント自身のレスポンスは経由しないが、下の StatusPages の
    // call.respond が JSON を書き出すために要る。
    install(ContentNegotiation) { json() }
    installErrorHandling()

    routing {
        userRoutes(users)

        // ドキュメントのソースを指定しなければ、ルートツリーから生成したものが使われる。
        // /swagger に Swagger UI、/swagger/documentation.yaml に文書が出る。
        swaggerUI("swagger") {
            info = OpenApiInfo(title = "Example User API", version = "1.0.0")
        }

        ktorGet("/health") { call.respondText("ok") }.hide()
    }
}

/**
 * ステータスコードとエラーボディはライブラリではなくアプリが決める。
 * ライブラリは例外を投げるだけで、何も捕まえない。
 */
private fun Application.installErrorHandling() {
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
        exception<UserNotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ErrorBody(cause.message ?: "not found"))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("unhandled", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorBody("internal error"))
        }
    }
}
