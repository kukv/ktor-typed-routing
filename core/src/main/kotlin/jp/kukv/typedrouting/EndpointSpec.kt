package jp.kukv.typedrouting

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.util.AttributeKey
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlin.reflect.KType

/**
 * エンドポイントのメタデータ。OpenAPI 生成はこれだけを読む。
 *
 * @param method HTTP メソッド
 * @param summary 短い説明。ドキュメントの見出しに使う
 * @param description 詳しい説明
 * @param status 成功時のステータスコード
 * @param errors ドキュメントに出すエラーレスポンスの宣言。実行時のマッピングには関与しない
 * @param requestType / @param responseType `:openapi` がスキーマを起こすために使う。
 *   Ktor のスキーマ推論は `KType` しか受け付けないため、`SerialDescriptor` ではなく `KType` を持つ
 */
public class EndpointSpec(
    public val method: HttpMethod,
    public val summary: String?,
    public val description: String?,
    public val status: HttpStatusCode,
    public val errors: List<Pair<HttpStatusCode, KType>>,
    public val requestType: KType?,
    public val responseType: KType?,
)

/** ルートに載せた [EndpointSpec] を引くためのキー。 */
public val EndpointSpecKey: AttributeKey<EndpointSpec> = AttributeKey("TypedRoutingEndpointSpec")

/**
 * バインド対象の形を検証する。違反は起動時に例外にする。
 *
 * - グループの中に `@Body` を書くことはできない
 * - 平坦化した結果、同じソースで同じ名前になる要素があってはならない
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun SerialDescriptor.validateBindingShape() {
    val seen = mutableMapOf<Pair<SourceKind, String>, String>()

    fun walk(descriptor: SerialDescriptor, prefix: String, inherited: SourceKind?, trail: String) {
        for (i in 0 until descriptor.elementsCount) {
            val origin = descriptor.originOf(i, inherited)
            val path = if (trail.isEmpty()) descriptor.getElementName(i) else "$trail.${descriptor.getElementName(i)}"

            if (origin.kind == SourceKind.BODY) {
                check(inherited == null) {
                    "@Body is not allowed inside a group (at '$path' of '$serialName'). " +
                        "A group must stay within a single input source."
                }
                continue
            }

            if (descriptor.isGroup(i, inherited)) {
                walk(
                    descriptor = descriptor.getElementDescriptor(i),
                    prefix = prefix + origin.prefix,
                    inherited = origin.kind,
                    trail = path,
                )
                continue
            }

            val key = origin.kind to (prefix + origin.name)
            val previous = seen.put(key, path)
            check(previous == null) {
                "Duplicate ${origin.kind} parameter '${prefix + origin.name}' in '$serialName' " +
                    "(declared at '$previous' and '$path'). Use @Query(prefix = ...) to disambiguate."
            }
        }
    }

    walk(this, prefix = "", inherited = null, trail = "")
}
