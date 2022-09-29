package com.riscure.langs.c.parser

import arrow.core.*
import com.riscure.getOption
import com.riscure.langs.c.ast.*
import com.riscure.tc.codeanalysis.clang.ast.loader.ClangParsingResult
import com.riscure.toOptional
import org.bytedeco.javacpp.*
import org.bytedeco.llvm.clang.*
import org.bytedeco.llvm.global.clang.*
import org.bytedeco.llvm.global.clang.clang_parseTranslationUnit2 as clang_parse
import java.io.File
import java.nio.file.Path
import java.util.*

class ClangUnitState(val cxunit: CXTranslationUnit) : UnitState {

    val cursor = clang_getTranslationUnitCursor(cxunit)
    val _ast by lazy { cursor.asTranslationUnit() }

    override fun close() {
        cxunit.close()
    }

    override fun ast() = _ast

    override fun getSource(dcl: TopLevel): Option<String> =
        dcl.location.map {
            val cursor = it.begin.getCursor()
            clang_getCursorPrettyPrinted(cursor, clang_getCursorPrintingPolicy(cursor)).string
        }

    fun Location.getCursor()  =
        clang_getCursor(cxunit, cx())

    fun SourceRange.cx(): CXSourceRange =
        clang_getRange(this.begin.cx(), this.end.cx())

    fun Location.cx(): CXSourceLocation =
        clang_getLocation(cxunit, sourceFile.cx(), row, col)

    fun File.cx(): CXFile =
        clang_getFile(cxunit, absolutePath)
}

/**
 * This implements the parser interface using the Bytedeco library to call
 * into libclang to parse a C file.
 *
 * This is *not* thread-safe, as far as we know because Bytedeco is not thread-safe.
 */
class ClangParser : Parser<ClangUnitState> {

    override fun parse(file: File): Result<ClangUnitState> {
        val args: Array<String> = arrayOf("")

        // We allocate the arguments.
        val c_index: CXIndex = clang_createIndex(0, 0)
        val c_tu = CXTranslationUnit()
        val c_sourceFile = BytePointer(file.absolutePath.toString())
        val c_arg_pointers = args.map { BytePointer(it) }
        val c_args = PointerPointer<BytePointer>(args.size.toLong())
        val c_parseOptions = CXTranslationUnit_SingleFileParse

        c_arg_pointers.forEach { c_args.put(it) }

        // Define the deallocator
        fun free() {
            clang_disposeIndex(c_index)
            c_sourceFile.close()
            c_arg_pointers.forEach { it.deallocate() }
            c_args.deallocate()
            // We don't free c_tu, because that will be part of the unit state.
        }

        // Parse the given file, storing the result in c_tu
        val code: Int = clang_parse(c_index, c_sourceFile, c_args, args.size, null, 0, c_parseOptions, c_tu)

        try {
            // Interpret the result code generated by clang:
            val result = ClangParsingResult.fromCode(code)
            return if (result != ClangParsingResult.Success) {
                result.message.left()
            } else ClangUnitState(c_tu).right()
        }
        catch (e : Exception) {
            // if something went wrong, we do have to free the tu
            c_tu.close()

            return "Failed to parse translation unit: ${e.message}".left()
        }
        finally {
            // regardless of success, we free the auxiliary data
            free()
        }
    }
}

fun CXCursor.children(): List<CXCursor> {
    val cs = mutableListOf<CXCursor>()
    val visitor = object: CXCursorVisitor() {
        override fun call(self: CXCursor?, parent: CXCursor?, p2: CXClientData?): Int {
            cs.add(self!!)
            return CXChildVisit_Continue
        }
    }

    clang_visitChildren(this, visitor, null)
    visitor.deallocate()

    return cs
}

fun CXString.get(): String = clang_getCString(this).string

fun CXString.getOption(): Option<String> =
    this.some()
        .filter { it.isNull() }
        .map { clang_getCString(this) }
        .filter { it.isNull }
        .map { it.string }

fun CXCursor.spelling(): String = clang_getCursorSpelling(this).string
fun CXCursor.kindName(): String = clang_getCursorKindSpelling(kind()).string

/**
 * Combinator to fail with a consistent message if we have an unexpected cursor kind.
 */
fun <T> CXCursor.ifKind(k: Int, expectation: String, whenMatch: () -> Result<T>): Result<T> {
    if (kind() != k) {
        return "Expected ${expectation}. Got cursor of kind ${kindName()}".left()
    }

    return whenMatch()
}

fun CXCursor.asTranslationUnit(): Result<TranslationUnit> {
    if (this.kind() != CXCursor_TranslationUnit) {
        return "Expected translation unit, got cursor of kind ${this.kindName()}".left()
    }

    return this.children()
        .map { it.asTopLevel() }
        .sequenceEither()
        .map { TranslationUnit(it) }
}

fun CXCursor.asTopLevel(): Result<TopLevel> =
    (when (kind()) {
        CXCursor_FunctionDecl ->
            if (children().any { child -> child.kind() == CXCursor_CompoundStmt })
                asFunctionDef()
            else asFunctionDecl()
        CXCursor_StructDecl   -> this.asStructDecl()
        CXCursor_VarDecl      -> this.asVarDecl()
        CXCursor_TypedefDecl  -> this.asTypedef()
        else -> "Expected global declaration".left()
    })
    .map { top:TopLevel ->
        // collect available meta
        // FIXME?
        val comment = clang_Cursor_getBriefCommentText(this).let { it.getOption() }
        top.withMeta(comment, getSourceLocation())
    }

fun CXCursor.getSourceLocation(): Option<SourceRange> =
    clang_getCursorExtent(this).asSourceRange()

fun CXSourceRange.asSourceRange(): Option<SourceRange> =
    clang_getRangeStart(this).asLocation().flatMap { begin ->
        clang_getRangeEnd(this).asLocation().map { end ->
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

        line.getOption().flatMap {l ->
            col.getOption().map { c ->
                Location(Path.of(clang_getFileName(file).string).toFile(), line.get(), col.get())
            }
        }
    } finally {
        line.close(); col.close(); offset.close(); file.close()
    }
}

fun CXCursor.asTypedef(): Result<TopLevel.Typedef> =
    ifKind (CXCursor_TypedefDecl, "typedef") {
        clang_getTypedefDeclUnderlyingType(this).asType().map { type ->
            TopLevel.Typedef(clang_getTypedefName(clang_getCursorType(this)).string, type)
        }
    }

fun CXCursor.asStructDecl(): Result<TopLevel.Composite> =
    ifKind (CXCursor_StructDecl, "struct declaration") {
        TopLevel.Composite(
            this.spelling(),
            StructOrUnion.Struct,
            listOf() // TODO
        ).right()
    }

fun CXCursor.asVarDecl(): Result<TopLevel.VarDecl> =
    ifKind (CXCursor_VarDecl, "variable declaration") {
        clang_getCursorType(this)
            .asType()
            .map { TopLevel.VarDecl(this.spelling(), it) }
    }

fun CXCursor.getResultType(): Result<Type> {
    val typ = clang_getCursorResultType(this)
    return typ.asType()
}

fun CXCursor.getParameters(): Result<List<Param>> {
    val nargs = clang_Cursor_getNumArguments(this)
    return (0 until nargs)
        .map { clang_Cursor_getArgument(this, it) }
        .map { it.asParam() }
        .sequenceEither()
}

fun CXCursor.asFunctionDef(): Result<TopLevel.FunDef> = ifKind(CXCursor_FunctionDecl, "function declaration") {
    this.getResultType().flatMap { resultType ->
        this.getParameters().map { params ->
            TopLevel.FunDef(
                spelling(),
                false,  // TODO
                resultType,
                params,
                false, // TODO
            )
        }
    }
}

fun CXCursor.asFunctionDecl(): Result<TopLevel.FunDecl> = ifKind(CXCursor_FunctionDecl, "function declaration") {
    this.getResultType().flatMap { resultType ->
        this.getParameters().map { params ->
            TopLevel.FunDecl(
                spelling(),
                false,  // TODO
                resultType,
                params,
                false, // TODO
            )
        }
    }
}

fun CXCursor.asParam(): Result<Param> {
    if (kind() != CXCursor_ParmDecl) {
        return "Exepected parameter declaration".left()
    }

    return clang_getCursorType(this)
        .asType()
        .map { type -> Param(spelling(), type) }
}

// CXType extensions

fun CXType.spelling(): String = clang_getTypeSpelling(this).string

/* Type declarations yield an assignable type */
fun CXCursor.asTypeDeclType(): Result<Type> =
    when(kind()) {
        CXCursor_EnumDecl   -> Type.Enum(spelling()).right()
        CXCursor_StructDecl -> Type.Struct(spelling()).right()
        CXCursor_UnionDecl  -> Type.Union(spelling()).right()
        else -> "Expected a type declaration, got ${kindName()}".left()
    }

/* Typedefs yield an assignable type */
fun CXCursor.asTypedefType(): Result<Type> = ifKind(CXCursor_TypedefDecl, "typedef") {
    clang_getTypedefDeclUnderlyingType(this).asType()
}

fun CXType.asType(): Result<Type> =
    when (this.kind()) {
        CXType_Void -> Type.Void().right()
        CXType_Bool -> Type.Int(IKind.IBoolean).right()
        CXType_Char_U -> Type.Int(IKind.IUChar).right() // correct?
        CXType_UChar  -> Type.Int(IKind.IUChar).right() // correct?
        CXType_UShort  -> Type.Int(IKind.IUShort).right()
        CXType_UInt  -> Type.Int(IKind.IUInt).right()
        CXType_ULong  -> Type.Int(IKind.IULong).right()
        CXType_ULongLong  -> Type.Int(IKind.IULongLong).right()
        CXType_Char_S -> Type.Int(IKind.IChar).right() // correct?
        CXType_SChar -> Type.Int(IKind.ISChar).right() // correct?
        CXType_Short -> Type.Int(IKind.IShort).right()
        CXType_Int -> Type.Int(IKind.IInt).right()
        CXType_Long -> Type.Int(IKind.ILong).right()
        CXType_LongLong -> Type.Int(IKind.ILongLong).right()

        CXType_Float -> Type.Float(FKind.FFloat).right()
        CXType_Double -> Type.Float(FKind.FDouble).right()
        CXType_LongDouble -> Type.Float(FKind.FLongDouble).right()

        CXType_Pointer -> clang_getPointeeType(this).asType().map { Type.Ptr(it) }
        CXType_Record  -> Type.Struct(spelling()).right()
        CXType_Elaborated -> clang_getTypeDeclaration(this).asTypeDeclType()
        CXType_Enum    -> TODO()
        CXType_Typedef -> clang_getTypeDeclaration(this).asTypedefType().map { Type.Named(spelling(),it) }
        CXType_ConstantArray ->
            clang_getArrayElementType(this)
                .asType()
                .map { Type.Array(it, clang_getArraySize(this).some()) }
        CXType_IncompleteArray ->
            clang_getArrayElementType(this)
                .asType()
                .map { Type.Array(it) }

        // others that could occur in C?

        else -> "Could not parse type of kind '${this.kind()}'".left()
    }