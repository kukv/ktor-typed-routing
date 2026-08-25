package jp.kukv.typedrouting

/**
 * `validate` ブロックの受け皿。違反を蓄積する。
 *
 * ブロックを抜けた時点で 1 件でもあれば [ValidationException] になる。
 */
public class ValidationScope {
    internal val violations: MutableList<Violation> = mutableListOf()

    /** 違反を 1 件記録する。ブロックの実行は止まらない。 */
    public fun reject(path: String, message: String) {
        violations += Violation(path, message)
    }
}
