package jp.kukv.typedrouting

import kotlinx.serialization.SerialInfo

/** パスパラメータから値を取ることを示す。[name] が空ならプロパティ名を使う。 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Path(val name: String = "")

/**
 * クエリパラメータから値を取ることを示す。[name] が空ならプロパティ名を使う。
 * 構造型に付けた場合はグループとして再帰的にバインドし、各要素の名前に [prefix] を付ける。
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Query(val name: String = "", val prefix: String = "")

/** リクエストヘッダから値を取ることを示す。[name] が空ならプロパティ名を使う。 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Header(val name: String = "", val prefix: String = "")

/** クッキーから値を取ることを示す。[name] が空ならプロパティ名を使う。 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Cookie(val name: String = "", val prefix: String = "")

/** リクエストボディから値を取ることを示す。1 つの型に 1 つだけ指定できる。 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Body
