package jp.kukv.typedrouting.example

import jp.kukv.typedrouting.Body
import jp.kukv.typedrouting.Header
import jp.kukv.typedrouting.Path
import jp.kukv.typedrouting.Query
import jp.kukv.typedrouting.Violation
import kotlinx.serialization.Serializable

/** レスポンスに載せるユーザー。 */
@Serializable
data class User(
    val id: Long,
    val orgId: Long,
    val name: String,
    val email: String,
    val tags: List<String> = emptyList(),
)

/**
 * ページング指定。`@Query` を付けたグループなので `?page=2&limit=50` として展開される
 * （`paging.page` ではない）。値が 1 つも来なければ、この既定値で埋まる。
 */
@Serializable
data class Paging(val page: Int = 1, val limit: Int = 20)

@Serializable
data class ListUsersReq(
    @Path val orgId: Long,
    @Query val paging: Paging,
    @Query val tag: String?,
    @Header("X-Request-Id") val requestId: String?,
)

@Serializable
data class UserPage(
    val items: List<User>,
    val page: Int,
    val limit: Int,
    val total: Int,
)

/** 1 件のユーザーを指すパスパラメータ。取得と削除で共用する。 */
@Serializable
data class UserRef(
    @Path val orgId: Long,
    @Path val userId: Long,
)

/** `@Body` に載せる型。スカラーは置けないので、必ずこうしたクラスで包む。 */
@Serializable
data class NewUser(
    val name: String,
    val email: String,
    val tags: List<String> = emptyList(),
)

@Serializable
data class CreateUserReq(
    @Path val orgId: Long,
    @Body val user: NewUser,
)

/**
 * エラーボディ。**ライブラリはエラーボディの形式を提供しない**ので、
 * これはこのサンプルが決めた型である。
 */
@Serializable
data class ErrorBody(
    val message: String,
    val violations: List<Violation> = emptyList(),
)
