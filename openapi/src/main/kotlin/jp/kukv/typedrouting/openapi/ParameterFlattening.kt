package jp.kukv.typedrouting.openapi

import jp.kukv.typedrouting.Body
import jp.kukv.typedrouting.Cookie
import jp.kukv.typedrouting.Header
import jp.kukv.typedrouting.Path
import jp.kukv.typedrouting.Query
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

/** 平坦化の対象となる要素 1 つ。[serialName] は `@SerialName` を反映した名前。 */
private data class Element(
    val property: KProperty1<*, *>,
    val parameter: KParameter?,
    val serialName: String,
)

/**
 * 宣言順に要素を返す。`memberProperties` は順序が保証されないため、
 * プライマリコンストラクタの引数順に並べ直す。
 *
 * `@SerialName` を反映した名前は `SerialDescriptor` 側にしかないため、descriptor の
 * 要素と添字で対応づける。`@Serializable` なクラスの descriptor の要素順は
 * プライマリコンストラクタの引数順と一致するのでこれは安全だが、要素数が食い違う場合
 * （`@Transient` を含む場合など）は対応を保証できないのでプロパティ名に退避する。
 */
private fun KType.orderedElements(): List<Element> {
    val classifier = classifier as? KClass<*> ?: return emptyList()
    val properties = classifier.memberProperties.associateBy { it.name }
    val parameters = classifier.primaryConstructor?.parameters.orEmpty()
    val ordered: List<Pair<KProperty1<*, *>, KParameter?>> =
        if (parameters.isEmpty()) {
            properties.values.map { it to null }
        } else {
            parameters.mapNotNull { parameter -> properties[parameter.name]?.let { it to parameter } }
        }

    val serialNames = runCatching { serializer(this).descriptor }.getOrNull()
        ?.takeIf { it.elementsCount == ordered.size }
        ?.let { descriptor -> List(descriptor.elementsCount) { descriptor.getElementName(it) } }

    return ordered.mapIndexed { index, (property, parameter) ->
        Element(
            property = property,
            parameter = parameter,
            serialName = serialNames?.get(index) ?: property.name,
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
private fun KType.isGroupType(): Boolean {
    val descriptor = runCatching { serializer(this).descriptor }.getOrNull() ?: return false
    if (descriptor.isInline) return false
    return descriptor.kind == StructureKind.CLASS || descriptor.kind == StructureKind.OBJECT
}

/**
 * Req の型を OpenAPI の `parameters` 相当に平坦化する。
 * グループは接頭辞を合成しながら再帰的に展開する。`@Body` は含まれない。
 */
internal fun KType.flattenParameters(): List<FlatParameter> {
    val result = mutableListOf<FlatParameter>()

    fun walk(type: KType, prefix: String, inherited: ParameterIn?, ancestorOptional: Boolean) {
        for ((property, parameter, serialName) in type.orderedElements()) {
            val origin = property.originOrNull(serialName)
            if (origin?.isBody == true) continue

            val location = origin?.location ?: inherited ?: continue
            val elementName = origin?.name ?: serialName
            val elementPrefix = origin?.prefix.orEmpty()
            val elementType = property.returnType
            val optional = ancestorOptional || parameter?.isOptional == true || elementType.isMarkedNullable

            if (elementType.isGroupType()) {
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
