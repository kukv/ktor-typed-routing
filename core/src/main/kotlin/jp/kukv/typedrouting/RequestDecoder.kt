package jp.kukv.typedrouting

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.StringFormat
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
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

    private companion object {
        /** グループを展開することを示す番兵。グループ自身は文字列値を持たない。 */
        val GROUP_PRESENT: List<String> = emptyList()
    }

    private fun originAt(elementIndex: Int): ElementOrigin =
        target.originOf(elementIndex, ownKind)

    private fun valuesFor(elementIndex: Int): List<String>? {
        val origin = originAt(elementIndex)
        if (origin.kind == SourceKind.BODY) return null
        return ctx.sources.sourceFor(origin.kind).getAll(prefix + origin.name)
    }

    /**
     * グループは子孫のどれか 1 つでも値があれば「存在する」とみなす。
     * 子がまたグループの場合は接頭辞を合成して再帰する。子グループを無条件に
     * 「値あり」とすると、空のグループを内包するだけの nullable グループが
     * 決して null にならない。
     */
    private fun groupHasAnyValue(elementIndex: Int): Boolean {
        val origin = originAt(elementIndex)
        return descriptorHasAnyValue(
            descriptor = target.getElementDescriptor(elementIndex),
            source = ctx.sources.sourceFor(origin.kind),
            prefix = prefix + origin.prefix,
            kind = origin.kind,
        )
    }

    private fun descriptorHasAnyValue(
        descriptor: SerialDescriptor,
        source: ParameterSource,
        prefix: String,
        kind: SourceKind,
    ): Boolean =
        (0 until descriptor.elementsCount).any { i ->
            val childOrigin = descriptor.originOf(i, kind)
            if (descriptor.isGroup(i, kind)) {
                descriptorHasAnyValue(
                    descriptor = descriptor.getElementDescriptor(i),
                    source = source,
                    prefix = prefix + childOrigin.prefix,
                    kind = kind,
                )
            } else {
                source.getAll(prefix + childOrigin.name) != null
            }
        }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        while (true) {
            index++
            if (index >= descriptor.elementsCount) return CompositeDecoder.DECODE_DONE

            val origin = originAt(index)
            if (origin.kind == SourceKind.BODY) return index
            if (descriptor.isGroup(index, ownKind)) {
                // グループ自身は 1 つの値に対応しないが、nullable な要素では
                // decodeNotNullMark() が pending を見るため、ここで必ず設定する。
                // 設定を怠ると直前の要素が残した pending を読んでしまう。
                if (groupHasAnyValue(index)) {
                    pending = GROUP_PRESENT
                    return index
                }
                if (descriptor.isElementOptional(index)) continue
                // 値が 1 つも無い。nullable なら null、そうでなければ展開して
                // 子それぞれの既定値の補完（または欠落報告）を kotlinx に任せる。
                pending = if (descriptor.getElementDescriptor(index).isNullable) null else GROUP_PRESENT
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

    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?,
    ): T {
        val origin = originAt(index)

        if (origin.kind == SourceKind.BODY) {
            val text = ctx.bodyText
            if (text.isNullOrEmpty()) {
                ctx.violations += Violation(origin.name, "request body is required")
                @Suppress("UNCHECKED_CAST")
                return null as T
            }
            return ctx.format.decodeFromString(deserializer, text)
        }

        if (descriptor.getElementDescriptor(index).kind == StructureKind.LIST) {
            val values = ctx.sources.sourceFor(origin.kind).getAll(prefix + origin.name).orEmpty()
            return MultiValueDecoder(values, ctx, prefix + origin.name).decodeSerializableValue(deserializer)
        }

        return super.decodeSerializableElement(descriptor, index, deserializer, previousValue)
    }

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

/** 同名で複数回現れたパラメータをコレクションとして復号する。 */
@OptIn(ExperimentalSerializationApi::class)
private class MultiValueDecoder(
    private val values: List<String>,
    private val ctx: BindingContext,
    private val name: String,
) : AbstractDecoder() {

    override val serializersModule: SerializersModule get() = ctx.format.serializersModule

    private var position = -1

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = values.size

    // 元の草案は true にしていたが、kotlinx 1.11.0 の AbstractDecoder では
    // decodeXxxElement(descriptor, index) が final で、渡された index を無視して
    // 単に decodeXxx() に委譲するだけになっている。decodeSequentially() が true だと
    // kotlinx はコレクション読み取り時に decodeElementIndex を一切呼ばずに
    // 自前のループ変数だけで decodeXxxElement を呼ぶため、`position` が更新されず
    // 全要素が同じ（初期値の）位置を読んでしまう。decodeElementIndex を経由させて
    // `position` を確実に前進させるため false にする。
    override fun decodeSequentially(): Boolean = false

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        position++
        return if (position >= values.size) CompositeDecoder.DECODE_DONE else position
    }

    private fun current(): String = values.getOrElse(position) { "" }

    private fun violate(message: String) {
        ctx.violations += Violation(name, message)
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
        violate("must be one of: " + (0 until enumDescriptor.elementsCount).joinToString { enumDescriptor.getElementName(it) })
        return 0
    }
}
