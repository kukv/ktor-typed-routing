package jp.kukv.typedrouting

import io.ktor.server.request.receiveText
import io.ktor.server.routing.RoutingCall
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.StringFormat
import kotlinx.serialization.descriptors.SerialDescriptor

/** この型がリクエストボディを必要とするかどうか。 */
@OptIn(ExperimentalSerializationApi::class)
internal fun SerialDescriptor.hasBodyElement(): Boolean =
    (0 until elementsCount).any { originOf(it).kind == SourceKind.BODY }

/**
 * 復号し、違反が 1 件でもあれば [RequestBindingException] にまとめて送出する。
 *
 * 欠落した必須要素は kotlinx が [MissingFieldException] としてまとめて報告するため、
 * それを捕まえて [Violation] に変換する。捕まえてよいのはこの 1 箇所だけである。
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun <T> BindingContext.decodeOrThrow(deserializer: DeserializationStrategy<T>): T {
    val result = try {
        decode(deserializer)
    } catch (cause: MissingFieldException) {
        val missing = cause.missingFields.map { Violation(it, "is required") }
        throw RequestBindingException(violations + missing)
    }

    if (violations.isNotEmpty()) throw RequestBindingException(violations.toList())
    return result
}

/**
 * リクエストを [T] にバインドする。
 *
 * ボディを必要とする型の場合のみ [RoutingCall.receiveText] を呼ぶ。
 * `Decoder` は suspend にできないため、ボディはここで先に読んでおく。
 */
internal suspend fun <T> bindRequest(
    call: RoutingCall,
    deserializer: DeserializationStrategy<T>,
    format: StringFormat,
): T {
    val bodyText = if (deserializer.descriptor.hasBodyElement()) call.receiveText() else null
    val ctx = BindingContext(call.requestSources(), bodyText, format)
    return ctx.decodeOrThrow(deserializer)
}
