package jp.kukv.typedrouting.example

import java.util.concurrent.atomic.AtomicLong

/** ユーザーが見つからなかったことを表す。ステータスコードの割り当ては StatusPages 側で行う。 */
class UserNotFoundException(val orgId: Long, val userId: Long) :
    Exception("user $userId not found in org $orgId")

/** サンプル用のインメモリ実装。永続化はしない。 */
class UserRepository {
    private val lock = Any()
    private val users = mutableListOf<User>()
    private val nextId = AtomicLong(1)

    fun list(orgId: Long, tag: String?): List<User> = synchronized(lock) {
        users.filter { it.orgId == orgId && (tag == null || tag in it.tags) }
    }

    fun find(orgId: Long, userId: Long): User? = synchronized(lock) {
        users.firstOrNull { it.orgId == orgId && it.id == userId }
    }

    fun get(orgId: Long, userId: Long): User =
        find(orgId, userId) ?: throw UserNotFoundException(orgId, userId)

    fun create(orgId: Long, new: NewUser): User = synchronized(lock) {
        val user = User(
            id = nextId.getAndIncrement(),
            orgId = orgId,
            name = new.name,
            email = new.email,
            tags = new.tags,
        )
        users += user
        user
    }

    fun delete(orgId: Long, userId: Long) = synchronized(lock) {
        val removed = users.removeIf { it.orgId == orgId && it.id == userId }
        if (!removed) throw UserNotFoundException(orgId, userId)
    }
}
