package jp.kukv.typedrouting

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind

/** 値をどこから取るか。 */
internal enum class SourceKind { PATH, QUERY, HEADER, COOKIE, BODY }

/**
 * 要素 1 つの由来。
 *
 * @param name パラメータ名。グループの場合は使わない
 * @param prefix グループの子要素に付ける接頭辞
 */
internal data class ElementOrigin(
    val kind: SourceKind,
    val name: String,
    val prefix: String,
)

/**
 * 要素の由来を判定する。
 *
 * グループの子要素にはアノテーションが付かないため、親のソース種別を [inherited] で受け取り、
 * その場合はプロパティ名をそのままパラメータ名として使う。
 * [inherited] が `null`（＝ルート）でアノテーションも無い場合は例外にする。
 * 由来を書き忘れたまま実行時に静かに欠落するのを防ぐため。
 */
internal fun SerialDescriptor.originOf(index: Int, inherited: SourceKind? = null): ElementOrigin {
    val propertyName = getElementName(index)
    val annotations = getElementAnnotations(index)

    annotations.forEach { annotation ->
        when (annotation) {
            is Path -> return ElementOrigin(SourceKind.PATH, annotation.name.ifEmpty { propertyName }, "")
            is Query -> return ElementOrigin(SourceKind.QUERY, annotation.name.ifEmpty { propertyName }, annotation.prefix)
            is Header -> return ElementOrigin(SourceKind.HEADER, annotation.name.ifEmpty { propertyName }, annotation.prefix)
            is Cookie -> return ElementOrigin(SourceKind.COOKIE, annotation.name.ifEmpty { propertyName }, annotation.prefix)
            is Body -> return ElementOrigin(SourceKind.BODY, propertyName, "")
        }
    }

    if (inherited != null) return ElementOrigin(inherited, propertyName, "")

    error(
        "Element '$propertyName' of '$serialName' has no source annotation. " +
            "Annotate it with @Path, @Query, @Header, @Cookie or @Body.",
    )
}

/**
 * 要素をグループとして再帰的にバインドするかどうか。
 *
 * 構造型（data class / object）だけがグループになる。
 * enum / List / カスタム serializer はスカラーとして扱う。
 * value class は kind こそ [StructureKind.CLASS] だが `isInline` が立つため、
 * それを除外してスカラー扱いにする。
 */
internal fun SerialDescriptor.isGroup(index: Int, inherited: SourceKind? = null): Boolean {
    if (originOf(index, inherited).kind == SourceKind.BODY) return false
    val elementDescriptor = getElementDescriptor(index)
    return when (elementDescriptor.kind) {
        StructureKind.CLASS, StructureKind.OBJECT -> !elementDescriptor.isInline
        else -> false
    }
}

internal fun RequestSources.sourceFor(kind: SourceKind): ParameterSource =
    when (kind) {
        SourceKind.PATH -> path
        SourceKind.QUERY -> query
        SourceKind.HEADER -> header
        SourceKind.COOKIE -> cookie
        SourceKind.BODY -> ParameterSource.Empty
    }
