package jp.kukv.typedrouting

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.StringFormat
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.modules.SerializersModule

/** 1 リクエスト分の復号状態。違反を蓄積する。 */
internal class BindingContext(
    val sources: RequestSources,
    val bodyText: String?,
    val format: StringFormat,
) {
    val violations: MutableList<Violation> = mutableListOf()
}

internal fun <T> BindingContext.decode(deserializer: DeserializationStrategy<T>): T =
    ObjectDecoder(deserializer.descriptor, this, prefix = "", ownKind = null)
        .decodeSerializableValue(deserializer)

/**
 * 1 つの構造体を復号する。要素ごとに由来を判定し、対応する [ParameterSource] から値を引く。
 * 構造型の要素は同じソース・合成した接頭辞で再帰する。
 */
@OptIn(ExperimentalSerializationApi::class)
internal class ObjectDecoder(
    private val target: SerialDescriptor,
    private val ctx: BindingContext,
    private val prefix: String,
    /** グループの内側なら親のソース種別。ルートなら `null`。 */
    private val ownKind: SourceKind?,
) : AbstractDecoder() {

    override val serializersModule: SerializersModule get() = ctx.format.serializersModule

    private var index = -1
    private var pending: List<String>? = null

    private fun originAt(elementIndex: Int): ElementOrigin =
        target.originOf(elementIndex, ownKind)

    private fun valuesFor(elementIndex: Int): List<String>? {
        val origin = originAt(elementIndex)
        if (origin.kind == SourceKind.BODY) return null
        return ctx.sources.sourceFor(origin.kind).getAll(prefix + origin.name)
    }

    /** グループは子要素のどれか 1 つでも値があれば「存在する」とみなす。 */
    private fun groupHasAnyValue(elementIndex: Int): Boolean {
        val origin = originAt(elementIndex)
        val child = target.getElementDescriptor(elementIndex)
        val source = ctx.sources.sourceFor(origin.kind)
        val childPrefix = prefix + origin.prefix
        return (0 until child.elementsCount).any { i ->
            if (child.isGroup(i, origin.kind)) {
                true
            } else {
                source.getAll(childPrefix + child.originOf(i, origin.kind).name) != null
            }
        }
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        while (true) {
            index++
            if (index >= descriptor.elementsCount) return CompositeDecoder.DECODE_DONE

            val origin = originAt(index)
            if (origin.kind == SourceKind.BODY) return index
            if (descriptor.isGroup(index, ownKind)) {
                if (groupHasAnyValue(index)) return index
                if (descriptor.isElementOptional(index)) continue
                return index
            }

            val values = valuesFor(index)
            if (values != null) {
                pending = values
                return index
            }

            // 欠落。nullable なら null を供給する。
            if (descriptor.getElementDescriptor(index).isNullable) {
                pending = null
                return index
            }

            // 非 nullable の欠落はスキップし、既定値の補完（任意の場合）または
            // MissingFieldException（必須の場合）を kotlinx に任せる。
            // 必須の欠落をここで例外にしないのは、MissingFieldException が
            // 欠落した要素をまとめて報告してくれるためである（Task 7 で変換する）。
            continue
        }
    }

    override fun decodeNotNullMark(): Boolean = pending != null

    override fun decodeNull(): Nothing? = null

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        // kotlinx は各構造体に入る際、まだ 1 要素も選ばれていない状態で beginStructure を
        // 呼ぶ（ルートの場合は decode() が構築した直後、グループの場合は decodeElementIndex
        // が返した直後）。index == -1 はまだ自分自身の構造体に入っていない印なので、
        // その場合は自分自身をそのまま composite として返す。
        // index が進んでいれば、decodeElementIndex で選ばれた要素がグループであり、
        // その子を同じソース・合成した接頭辞で復号する新しいインスタンスを返す。
        if (index == -1) return this
        val origin = originAt(index)
        return ObjectDecoder(descriptor, ctx, prefix + origin.prefix, ownKind = origin.kind)
    }

    private fun current(): String = pending?.firstOrNull().orEmpty()

    private fun violate(message: String) {
        ctx.violations += Violation(prefix + originAt(index).name, message)
    }

    override fun decodeString(): String = current()

    override fun decodeInt(): Int =
        current().toIntOrNull() ?: run { violate("must be an integer"); 0 }

    override fun decodeLong(): Long =
        current().toLongOrNull() ?: run { violate("must be an integer"); 0L }

    override fun decodeShort(): Short =
        current().toShortOrNull() ?: run { violate("must be an integer"); 0 }

    override fun decodeByte(): Byte =
        current().toByteOrNull() ?: run { violate("must be an integer"); 0 }

    override fun decodeDouble(): Double =
        current().toDoubleOrNull() ?: run { violate("must be a number"); 0.0 }

    override fun decodeFloat(): Float =
        current().toFloatOrNull() ?: run { violate("must be a number"); 0f }

    override fun decodeBoolean(): Boolean =
        current().toBooleanStrictOrNull() ?: run { violate("must be true or false"); false }

    override fun decodeChar(): Char =
        current().singleOrNull() ?: run { violate("must be a single character"); ' ' }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        val text = current()
        val found = (0 until enumDescriptor.elementsCount)
            .firstOrNull { enumDescriptor.getElementName(it) == text }
        if (found != null) return found

        val allowed = (0 until enumDescriptor.elementsCount).joinToString { enumDescriptor.getElementName(it) }
        violate("must be one of: $allowed")
        return 0
    }
}
