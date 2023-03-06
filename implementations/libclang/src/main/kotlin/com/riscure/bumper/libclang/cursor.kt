/**
 * Some extension methods for working with bytedeco's
 * libclang cursor pointer thing.
 */
package com.riscure.bumper.libclang

import arrow.core.*
import arrow.typeclasses.Monoid
import com.riscure.getOption
import com.riscure.bumper.ast.Location
import com.riscure.bumper.ast.SourceRange
import com.riscure.toBool
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.llvm.clang.*
import org.bytedeco.llvm.global.clang.*
import java.nio.file.Path

/**
 * Safer version of string() that checks if the C string is not null.
 */
fun CXString.stringOption(): Option<String> =
    clang_getCString(this)
        .toOption()
        .map { ptr ->
            val result = ptr.getString()
            clang_disposeString(this)
            result
        }

fun CXCursor.spelling(): String = clang_getCursorSpelling(this).string
fun CXCursor.kindName(): String = clang_getCursorKindSpelling(kind()).string
fun CXType.kindName(): String = clang_getTypeKindSpelling(kind()).string

/**
 * Get the list of child cursors from a cursor.
 */
fun CXCursor.children(): List<CXCursor> = fold(monoid = Monoid.list(), false) { listOf(this) }

fun CXCursor.type(): CXType = clang_getCursorType(this)

fun <T> CXCursor.fold(acc: T, recursive: Boolean, visitor: CXCursor.(acc: T) -> T): T {
    var accumulator = acc

    val wrapped = object: CXCursorVisitor() {
        override fun call(self: CXCursor?, parent: CXCursor?, p2: CXClientData?): Int {
            accumulator = visitor(self!!, accumulator)
            return if (recursive) CXChildVisit_Recurse else CXChildVisit_Continue
        }
    }

    try { clang_visitChildren(this, wrapped, null) }
    finally { wrapped.deallocate() }

    return accumulator
}

/**
 * Collect some data from the [recursive] children of a cursor.
 * The data we collect should implement the monoid typeclass,
 * so that we can combine them when we fold up the tree.
 */
fun <T> CXCursor.fold(monoid: Monoid<T>, recursive: Boolean, visitor: CXCursor.() -> T): T =
    fold(monoid.empty(), recursive) { acc ->
        val cursor = this
        with(monoid) {
            acc.combine(visitor(cursor))
        }
    }

fun CXCursor.getExtent(): Option<CXSourceRange> {
    val ptr = clang_getCursorExtent(this)
    return if (ptr.isNull) None else ptr.some()
}

fun CXCursor.getLocation(): Option<CXSourceLocation> =
    getExtent().map { clang_getRangeStart(it) }

fun CXCursor.filterNullCursor(): Option<CXCursor>  =
    if (clang_Cursor_isNull(this).toBool() || this.isNull) {
        None
    } else this.some()

fun CXCursor.isNullCursor() = filterNullCursor().isEmpty()

/**
 * Get the cursor of the definition that is being referenced by [this] cursor.
 *
 * WARNING: Be careful how you use this. Clang happily returns cursors for many expressions that don't appear like
 * references. Which cursor is returned exactly is also not always predictable.
 */
fun CXCursor.getReferenced(): Option<CXCursor> = clang_getCursorReferenced(this).filterNullCursor()

@Deprecated("Useless: clang returns false for cursors of kind DeclRefExpr!?")
fun CXCursor.isReference() =
    // This is not the same as getReferenced().isDefined
    // because clang returns something from getCursorReferenced for various cursors
    // that are not references
    clang_isReference(kind()).toBool()

fun CXCursor.semanticParent() = clang_getCursorSemanticParent(this)
fun CXCursor.lexicalParent() = clang_getCursorLexicalParent(this)
fun CXCursor.translationUnit() = clang_getTranslationUnitCursor(clang_Cursor_getTranslationUnit(this))

fun CXType.spelling(): String = clang_getTypeSpelling(this).string

// TODO needs testing
fun CXCursor.isLocalDefinition(): Boolean {
    // Checking the inverse (that the semanticParent is the translation unit)
    // does not work, because libclang sometimes returns some InvalidFile (?) cursor for some declarations.
    val parent = semanticParent().kind()
    return (clang_isStatement(parent).toBool() || parent == CXCursor_FunctionDecl)
}

fun CXCursor.isGlobalDefinition(): Boolean = !isLocalDefinition()

fun CXCursor.getStart(): Option<Location> =
    getExtent().flatMap {
        clang_getRangeStart(it).asLocation()
    }

fun CXCursor.getEnd(): Option<Location> =
    getExtent().flatMap {
        clang_getRangeEnd(it).asLocation()
    }

fun CXCursor.getRange(): Option<SourceRange> =
    getStart().flatMap { begin ->
        getEnd().map { end ->
            SourceRange(begin, end)
        }
    }

fun CXSourceLocation.asLocation(): Option<Location> {
    val line   = IntPointer(1)
    val col    = IntPointer(1)
    val offset = IntPointer(1)
    val file   = CXFile()

    return try {
        clang_getExpansionLocation(this, file, line, col, offset)
        line.getOption()
            .flatMap { l ->
                col.getOption()
                    .flatMap { c ->
                        clang_getFileName(file).stringOption()
                            .map { file -> Location(Path.of(file), l, c) }
                    }
            }
    } finally {
        line.close(); col.close(); offset.close(); file.close()
    }
}

/**
 * Get the so-called 'presumed location', which follows the #line directives
 * which are generated by the preprocessor.
 */
fun CXSourceLocation.asPresumedLocation(): Option<Location> {
    val line   = IntPointer(1)
    val col    = IntPointer(1)
    val offset = IntPointer(1)
    val file   = CXString()

    return try {
        clang_getPresumedLocation(this, file, line, col)
        line.getOption().flatMap { l ->
            col.getOption().map { c -> Location(Path.of(file.string), l, c) }
        }
    } finally {
        line.close(); col.close(); offset.close(); file.close()
    }
}

/**
 * Uniquely identifies a cursor (hashCode() is not well-defined on CXCursor)
 */
fun CXCursor.hash() = clang_hashCursor(this)
