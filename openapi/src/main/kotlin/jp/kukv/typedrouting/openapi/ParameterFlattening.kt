package jp.kukv.typedrouting.openapi

import jp.kukv.typedrouting.Body
import jp.kukv.typedrouting.Cookie
import jp.kukv.typedrouting.Header
import jp.kukv.typedrouting.Path
import jp.kukv.typedrouting.Query
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/** OpenAPI のパラメータ位置。 */
internal enum class ParameterIn { PATH, QUERY, HEADER, COOKIE }

/** 平坦化した 1 パラメータ。 */
internal data class FlatParameter(
    val name: String,
    val location: ParameterIn,
    val required: Boolean,
    val type: KType,
)

/** プロパティ 1 つの由来。[location] が `null` なら `@Body`。 */
private data class Origin(
    val location: ParameterIn?,
    val name: String,
    val prefix: String,
    val isBody: Boolean,
)

/**
 * プロパティに付いた由来アノテーションを読む。無ければ `null`。
 *
 * 名前を省略した場合に使うのは [serialName]（`@SerialName` を反映した名前）であって
 * Kotlin のプロパティ名ではない。`:core` は `SerialDescriptor.getElementName` で
 * 束縛するため、ここでプロパティ名を使うと 2 つのモジュールで名前が食い違う。
 */
private fun KProperty1<*, *>.originOrNull(serialName: String): Origin? {
    annotations.forEach { annotation ->
        when (annotation) {
            is Path -> return Origin(ParameterIn.PATH, annotation.name.ifEmpty { serialName }, "", false)
            is Query -> return Origin(ParameterIn.QUERY, annotation.name.ifEmpty { serialName }, annotation.prefix, false)
            is Header -> return Origin(ParameterIn.HEADER, annotation.name.ifEmpty { serialName }, annotation.prefix, false)
            is Cookie -> return Origin(ParameterIn.COOKIE, annotation.name.ifEmpty { serialName }, annotation.prefix, false)
            is Body -> return Origin(null, serialName, "", true)
        }
    }
    return null
}

/**
 * 平坦化の対象となる要素 1 つ。
 *
 * @param serialName `@SerialName` を反映した名前
 * @param elementDescriptor この要素の直列化記述子。プロパティ単位の `@Serializable(with = ...)`
 *   を反映した形になっているため、グループ判定はこれを見る。対応づけられなければ `null`
 */
private data class Element(
    val property: KProperty1<*, *>,
    val parameter: KParameter?,
    val serialName: String,
    val elementDescriptor: SerialDescriptor?,
)

/**
 * 宣言順に要素を返す。`memberProperties` は順序が保証されないため、
 * プライマリコンストラクタの引数順に並べ直す。
 *
 * `@SerialName` を反映した名前と、プロパティ単位の serializer を反映した記述子は
 * `SerialDescriptor` 側にしかないため、descriptor の要素と添字で対応づける。
 * `@Transient` を付けたプロパティは直列化されず descriptor に現れないので、
 * 対応づける前に除く。それでも要素数が食い違う場合は対応を保証できないので、
 * 名前はプロパティ名に、グループ判定は型からの推定に退避する。
 */
@OptIn(ExperimentalSerializationApi::class)
private fun KType.orderedElements(): List<Element> {
    val classifier = classifier as? KClass<*> ?: return emptyList()
    val properties = classifier.memberProperties.associateBy { it.name }
    val parameters = classifier.primaryConstructor?.parameters.orEmpty()
    val ordered: List<Pair<KProperty1<*, *>, KParameter?>> =
        if (parameters.isEmpty()) {
            properties.values.map { it to null }
        } else {
            parameters.mapNotNull { parameter -> properties[parameter.name]?.let { it to parameter } }
        }.filterNot { (property, _) -> property.annotations.any { it is Transient } }

    val descriptor = runCatching { serializer(this).descriptor }.getOrNull()
        ?.takeIf { it.elementsCount == ordered.size }

    return ordered.mapIndexed { index, (property, parameter) ->
        Element(
            property = property,
            parameter = parameter,
            serialName = descriptor?.getElementName(index) ?: property.name,
            elementDescriptor = descriptor?.getElementDescriptor(index),
        )
    }
}

/**
 * この型がグループとして再帰的に展開されるかどうか。
 *
 * `:core` の `isGroup` とまったく同じ規則にするため、`KClass` の性質ではなく
 * `SerialDescriptor` の `kind` と `isInline` で判定する。`isData` で判定すると、
 * 非 data の `@Serializable class` が `:core` ではグループ、`:openapi` ではスカラーになり、
 * 2 つのモジュールで挙動が食い違う。
 */
private fun SerialDescriptor.isGroupDescriptor(): Boolean {
    if (isInline) return false
    return kind == StructureKind.CLASS || kind == StructureKind.OBJECT
}

private fun KType.isGroupType(): Boolean =
    runCatching { serializer(this).descriptor }.getOrNull()?.isGroupDescriptor() ?: false

/**
 * Req の型を OpenAPI の `parameters` 相当に平坦化する。
 * グループは接頭辞を合成しながら再帰的に展開する。`@Body` は含まれない。
 */
internal fun KType.flattenParameters(): List<FlatParameter> {
    val result = mutableListOf<FlatParameter>()

    fun walk(type: KType, prefix: String, inherited: ParameterIn?, ancestorOptional: Boolean) {
        for ((property, parameter, serialName, elementDescriptor) in type.orderedElements()) {
            val origin = property.originOrNull(serialName)
            if (origin?.isBody == true) continue

            val location = origin?.location ?: inherited ?: continue
            val elementName = origin?.name ?: serialName
            val elementPrefix = origin?.prefix.orEmpty()
            val elementType = property.returnType
            val optional = ancestorOptional || parameter?.isOptional == true || elementType.isMarkedNullable

            // グループかどうかはプロパティの記述子で決める。プロパティ単位の
            // `@Serializable(with = ...)` を無視して型から引くと、`:core` がスカラーとして
            // 束縛するものを `:openapi` だけが展開してしまう。
            val isGroup = elementDescriptor?.isGroupDescriptor() ?: elementType.isGroupType()
            if (isGroup) {
                // グループ自体が任意（既定値あり）または nullable なら、
                // `:core` はグループごと省略を許すので子孫はすべて任意になる。
                walk(elementType, prefix + elementPrefix, location, optional)
                continue
            }

            result += FlatParameter(
                name = prefix + elementName,
                location = location,
                required = !optional,
                type = elementType,
            )
        }
    }

    walk(this, prefix = "", inherited = null, ancestorOptional = false)
    return result
}

/** `@Body` を付けたプロパティの型。無ければ `null`。 */
internal fun KType.bodyParameterType(): KType? =
    orderedElements()
        .firstOrNull { it.property.originOrNull(it.serialName)?.isBody == true }
        ?.property
        ?.returnType
