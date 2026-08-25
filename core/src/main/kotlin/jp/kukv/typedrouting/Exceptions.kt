package jp.kukv.typedrouting

import kotlinx.serialization.Serializable

/**
 * 入力の 1 件の違反。
 *
 * @param path 違反したパラメータの名前
 * @param message 人間向けの説明
 */
@Serializable
public data class Violation(
    public val path: String,
    public val message: String,
)

/**
 * リクエストを型にバインドできなかったことを表す。
 *
 * ステータスコードは持たない。StatusPages 側で割り当てる。
 */
public class RequestBindingException(
    public val violations: List<Violation>,
) : Exception("request binding failed: " + violations.joinToString { "${it.path}: ${it.message}" })

/**
 * `validate` ブロックで違反が見つかったことを表す。
 *
 * ステータスコードは持たない。StatusPages 側で割り当てる。
 */
public class ValidationException(
    public val violations: List<Violation>,
) : Exception("validation failed: " + violations.joinToString { "${it.path}: ${it.message}" })
