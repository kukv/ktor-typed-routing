package jp.kukv.typedrouting.example

import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import jp.kukv.typedrouting.delete
import jp.kukv.typedrouting.get
import jp.kukv.typedrouting.post

/** ユーザー API。`routing {}` の下に生やす普通の Ktor ルートである。 */
fun Route.userRoutes(users: UserRepository) {
    route("/orgs/{orgId}/users") {
        get<ListUsersReq, UserPage> {
            summary = "ユーザーを一覧する"
            description = "`page` / `limit` はクエリのグループとして展開される。"
            error<ErrorBody>(HttpStatusCode.BadRequest)
            error<ErrorBody>(HttpStatusCode.UnprocessableEntity)

            validate { req ->
                if (req.paging.page < 1) reject("page", "must be >= 1")
                if (req.paging.limit !in 1..100) reject("limit", "must be 1..100")
            }
            handle { req ->
                val all = users.list(req.orgId, req.tag)
                val from = (req.paging.page - 1) * req.paging.limit
                UserPage(
                    items = all.drop(from).take(req.paging.limit),
                    page = req.paging.page,
                    limit = req.paging.limit,
                    total = all.size,
                )
            }
        }

        post<CreateUserReq, User> {
            summary = "ユーザーを作成する"
            status = HttpStatusCode.Created
            error<ErrorBody>(HttpStatusCode.BadRequest)
            error<ErrorBody>(HttpStatusCode.UnprocessableEntity)

            // reject は違反を溜めるだけでブロックを止めない。抜けた時点でまとめて 1 つの
            // ValidationException になる。
            validate { req ->
                if (req.user.name.isBlank()) reject("name", "must not be blank")
                if ("@" !in req.user.email) reject("email", "must be an email address")
            }
            handle { req -> users.create(req.orgId, req.user) }
        }

        route("/{userId}") {
            get<UserRef, User> {
                summary = "ユーザーを 1 件取得する"
                error<ErrorBody>(HttpStatusCode.NotFound)

                handle { req -> users.get(req.orgId, req.userId) }
            }

            // Res が Unit なので既定のステータスは 204 No Content、ボディは書かれない。
            delete<UserRef, Unit> {
                summary = "ユーザーを削除する"
                error<ErrorBody>(HttpStatusCode.NotFound)

                handle { req -> users.delete(req.orgId, req.userId) }
            }
        }
    }
}
