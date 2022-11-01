/**
 * Some extension methods for working with bytedeco's
 * libclang cursor pointer thing.
 */
package com.riscure.langs.c.parser.clang

import arrow.core.*
import arrow.typeclasses.Monoid
import com.riscure.langs.c.parser.getRange
import com.riscure.toBool
import org.bytedeco.llvm.clang.*
import org.bytedeco.llvm.global.clang
import org.bytedeco.llvm.global.clang.*

fun CXCursor.spelling(): String = clang.clang_getCursorSpelling(this).string
fun CXCursor.kindName(): String = clang.clang_getCursorKindSpelling(kind()).string
fun CXType.kindName(): String = clang.clang_getTypeKindSpelling(kind()).string

/**
 * Get the list of child cursors from a cursor.
 */
fun CXCursor.children(): List<CXCursor> = collect(Monoid.list(), false) { listOf(this) }

/**
 * Collect some data from the [recursive] children of a cursor.
 * The data we collect should implement the monoid typeclass,
 * so that we can combine them when we fold up the tree.
 */
fun <T> CXCursor.collect(monoid: Monoid<T>, recursive: Boolean, visitor: CXCursor.() -> T): T {
    val ts = mutableListOf<T>()
    val wrapped = object: CXCursorVisitor() {
        override fun call(self: CXCursor?, parent: CXCursor?, p2: CXClientData?): Int {
            ts.add(visitor(self!!))

            return if (recursive) clang.CXChildVisit_Recurse else clang.CXChildVisit_Continue
        }
    }

    clang.clang_visitChildren(this, wrapped, null)
    wrapped.deallocate()

    return ts.combineAll(monoid)
}

fun CXCursor.getExtent(): Option<CXSourceRange> {
    val ptr = clang.clang_getCursorExtent(this)
    return if (ptr.isNull) None else ptr.some()
}

fun CXCursor.filterNullCursor(): Option<CXCursor>  =
    if (clang_Cursor_isNull(this).toBool() || this.isNull) {
        None
    } else this.some()

fun CXCursor.isNullCursor() = filterNullCursor().isEmpty()

/**
 * Get the cursor of the definition that is being referenced by [this] cursor.
 */
fun CXCursor.getReferenced(): Option<CXCursor> = clang_getCursorReferenced(this).filterNullCursor()
fun CXCursor.isReference()   = getReferenced().isDefined()
fun CXCursor.semanticParent() = clang_getCursorSemanticParent(this)
fun CXCursor.lexicalParent() = clang_getCursorLexicalParent(this)
fun CXCursor.translationUnit() = clang_getTranslationUnitCursor(clang_Cursor_getTranslationUnit(this))

fun CXType.spelling(): String = clang.clang_getTypeSpelling(this).string
