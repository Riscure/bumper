@file:Suppress("MemberVisibilityCanBePrivate")

package com.riscure.bumper.libclang

import arrow.core.*
import arrow.typeclasses.Monoid
import com.riscure.bumper.ast.*
import com.riscure.bumper.index.Symbol
import com.riscure.bumper.index.TUID
import com.riscure.getOption
import com.riscure.toBool
import org.bytedeco.javacpp.annotation.ByVal
import org.bytedeco.llvm.clang.*
import org.bytedeco.llvm.global.clang.*
import java.nio.file.Path

private typealias Result<T> = Either<String, T>
typealias CursorHash = Int

private object myDependencyOrder: Comparator<Pair<CXCursor, ClangDeclaration>> {
    override fun compare(d1: Pair<CXCursor, ClangDeclaration>, d2: Pair<CXCursor, ClangDeclaration>): Int =
        when (val l1 = d1.second.meta.location) {
            is None -> -1 // stable order
            is Some -> when (val l2 = d2.second.meta.location) {
                is None -> 1
                is Some -> dependencyOrder.compare(l1.value, l2.value) }
        }
}

data class UnitWithCursorData(
    val ast: TranslationUnit<CXCursor, CXCursor>,
    val elaboratedCursors: Map<CursorHash, ClangDeclaration>
)

/**
 * A stateful translation from libclang's CXCursors to our typed C ASTs.
 * The state is bound to a single translation unit, which is assumed to be parsed only once.
 *
 * This parser also elaborates inline definitions, rather than doing that in a separate pass.
 * The reason for this is that libclang does not allow us to distinguish inline definitions
 * from inline declarations. Types are treated semantically--rather than syntactically--in the
 * libclang API.
 */
open class CursorParser(
    val tuid: TUID,

    /**
     * The clang model of the translation unit that we're parsing cursors of.
     */
    val cxTranslationUnit: CXTranslationUnit,

    /**
     * workingDir is the working directory for clang during source file parsing. CXCursor may have relative paths,
     * like file.string from clang_getPresumedLocation, with workingDir as base.
     */
    private val workingDir: Path,

    /**
     * Declarations that need to be elaborated to top-level definitions.
     * Because we elaborate during parsing, we may (re)name the declaration.
     *
     * We store in this map the symbol with the generated/elaborated name.
     * References should be consistently renamed to use the elaborated name.
     * This map is monotonically growing
     */
    private val elaborated    : MutableMap<CursorHash, Symbol> = mutableMapOf(),

    /**
     * A worklist for cursor representing (type) definitions that need to be elaborated.
     */
    private val toBeElaborated: MutableList<CXCursor>          = mutableListOf(),

    private var freshNameSuffix: Int = -1
) {

    /**
     * Generate a fresh name for an anonymous declaration.
     * The [hint] will be incorporated to give the user some idea of where this came from.
     */
    private fun freshAnonymousIdentifier(hint: Option<Ident> = None): Ident {
        freshNameSuffix += 1
        return "__anontype${hint.map { "_$it" }.getOrElse { "" }}_${freshNameSuffix}"
    }

    /**
     * Parse the list of [toBeElaborated] definitions.
     * Every parsed definition is removed from that list.
     */
    private fun pleaseDoElaborate(): Result<List<Pair<CXCursor, ClangDeclaration>>> {
        // We use [toBeElaborated] as a worklist
        // Parsing a declaration can add new items to the worklist as a side-effect.
        // When parsing fails, we immediately return the failure.
        return Either.catch({ it.message!! }) {
            val alreadyParsed = mutableSetOf<CursorHash>()
            val results = mutableListOf<Pair<CXCursor, ClangDeclaration>>()

            // work loop
            while (toBeElaborated.isNotEmpty()) {
                val cursor = toBeElaborated.removeAt(0)
                if (cursor.hash() in alreadyParsed) continue

                val decl = cursor
                    .asDeclaration()
                    .getOrHandle { throw Throwable(it) } // escalate

                alreadyParsed.add(cursor.hash())

                // name the anonymous declarations correctly.
                // TODO it might be better to solve this in getIdentifier() to avoid inconsistencies
                val sym = cursor.getElaboratedSymbol()
                    .getOrHandle { throw RuntimeException("Invariant violation: missing elaborated name for elaborated cursor.") }

                results.add(Pair(cursor, decl.withIdent(sym.name)))
            }

            results
        }

    }

    // Parsing functions
    //-----------------------------------------------------------------------------------------------

    /**
     * Combinator to fail with a consistent message if we have an unexpected cursor kind.
     */
    private fun <T> CXCursor.ifKind(k: Int, expectation: String, whenMatch: () -> Result<T>): Result<T> {
        if (kind() != k) {
            return "Expected $expectation. Got cursor of kind ${kindName()}".left()
        }

        return whenMatch()
    }

    fun CXCursor.asTranslationUnit(): Result<UnitWithCursorData> =
        ifKind(CXCursor_TranslationUnit, "translation unit") {
            // Add all top-level declarations in the clang AST to the work list
            // of things to be elaborated
            children()
                // somehow e.g. an empty ';' results in top-level UnexposedDecls
                .filter { cursor -> (cursor.kind() != CXCursor_UnexposedDecl) }
                .map { cursor -> cursor.lazyElaborate() }
                .sequence()
                .flatMap {
                    // We now run the worklist algorithm to parse all nodes marked for elaboration.
                    // While doing the work, we may see new nodes that need elaboration.
                    pleaseDoElaborate()
                        // we sort these, because we don't know from the traversal order where
                        // the elaborated declarations should appear. And the order is relevant for type completeness
                        // of structs and unions.
                        .map { it.sortedWith(myDependencyOrder) }
                        .map { ds -> ds.map { (cursor, decl) -> Pair(cursor.hash(), decl) } }
                        // and finally collect the outputs in a translation unit model.
                        .map { ds ->
                            UnitWithCursorData(
                                TranslationUnit(tuid, ds.map { it.second }),
                                ds.toMap()
                            )
                        }
                }

            // FIXME this is unsound when we elaborate a named definition from a local scope,
            // because we are possibly extending the visibility of elaborated declarations,
            // which can now clash with an existing one.
            // We cannot fix that right now, because it requires renaming
            // all references. This is not a problem for any reference that we parse, but we do not
            // parse statements and expressions at the moment, so we cannot perform renaming there.
        }

    private fun CXCursor.asDeclaration(): Result<ClangDeclaration> {
        val decl = when (kind()) {
            CXCursor_FunctionDecl -> this.asFunction()
            CXCursor_StructDecl   -> this.asStruct()
            CXCursor_UnionDecl    -> this.asUnion()
            CXCursor_VarDecl      -> this.asVariable()
            CXCursor_TypedefDecl  -> this.asTypedef()
            CXCursor_EnumDecl     -> this.asEnum()
            else                  -> "Expected toplevel declaration, got kind ${kindName()}".left()
        }

        // Fill in the properties of the Declaration class.
        return decl
            .map {
                it
                    .withMeta(getMetadata())
                    .withStorage(getStorage())
            }
    }

    private fun CXCursor.asFunction(): Result<UnitDeclaration.Fun<CXCursor>> =
        if (children().any { child -> child.kind() == CXCursor_CompoundStmt })
            asFunctionDef()
        else asFunctionDecl()

    /**
     * Check if this is a valid C identifier. Empty string is accepted.
     */
    private fun String.validateIdentifier(): Result<String> =
        when {
            // check if it is the magic string that libclang uses for anonymous structs...
            // I have not found a more reliable way to check this.
            // In any case, it cannot overlap with valid identifiers
            Regex("\\w+ \\((unnamed|anonymous) at .*\\)").matches(this) -> "".right()
            // C identifiers start with a non-digit.
            // But an empty string is considered valid, regarding anonymous declarations.
            Regex("[_a-zA-Z]?\\w*").matches(this)       -> this.right()
            else -> "Expected valid C identifier, got '$this'.".left()
        }

    /**
     * Returns the spelling if it is a valid identifier, according to [validateIdentifier].
     */
    fun CXCursor.getIdentifier(): Result<String> =
        spelling()
            .validateIdentifier()

    /**
     * Collects storage for a toplevel declaration cursor.
     */
    fun CXCursor.getStorage(): Storage = clang_Cursor_getStorageClass(this).asStorage()

    /**
     * Map libclang's storage constants to our AST's enum values.
     */
    fun Int.asStorage(): Storage = when (this) {
        CX_SC_Static   -> Storage.Static
        CX_SC_Auto     -> Storage.Auto
        CX_SC_Extern   -> Storage.Extern
        CX_SC_Register -> Storage.Register
        else           -> Storage.Default
    }

    /**
     * Collects metadata for a toplevel declaration cursor.
     */
    fun CXCursor.getMetadata(): Meta {
        // FIXME? No doc available
        val comment = clang_Cursor_getBriefCommentText(this).getOption()

        return Meta(
            location = getRange(),
            presumedLocation = getLocation().flatMap { it.asPresumedLocation(workingDir) },
            doc = comment
        )
    }

    fun CXCursor.asTypedef(): Result<UnitDeclaration.Typedef> =
        ifKind(CXCursor_TypedefDecl, "typedef") {
            clang_getTypedefDeclUnderlyingType(this)
                .asType()
                .flatMap { type ->
                    getIdentifier().map { id -> UnitDeclaration.Typedef(id, type) }
                }
        }

    fun CXCursor.asEnum(): Result<UnitDeclaration.Enum> =
        ifKind(CXCursor_EnumDecl, "enum declaration") {
            if (clang_isCursorDefinition(this).toBool()) {
                lazyElaborate().flatMap { symbol ->
                    children()
                        .map { it.asEnumerator(symbol.tlid) }
                        .sequence()
                        .map { enumerators ->
                            UnitDeclaration.Enum(symbol.name, enumerators, cursorAttributes(type()))
                        }
                }
            } else {
                lazyElaborate().map { symbol -> UnitDeclaration.Enum(symbol.name, cursorAttributes(type()), None) }
            }
        }

    fun CXCursor.asEnumerator(enum: TLID): Result<Enumerator> =
        ifKind(CXCursor_EnumConstantDecl, "enumerator") {
            val name  = spelling()
            val const = clang_getEnumConstantDeclValue(this)
            Enumerator(name, const, enum).right()
        }

    private fun CXCursor.getCompoundFields() =
        if (clang_isCursorDefinition(this).toBool()) {
            type()
                .fields()
                .mapIndexed { i, it -> it.asField() }
                .sequence()
                .map { it.some() }
        } else {
            // For declarations, fields is None
            None.right()
        }

    fun CXCursor.asStruct(): Result<UnitDeclaration.Struct> =
        ifKind(CXCursor_StructDecl, "struct declaration") {
            // We check if this is the definition, because the field visitor
            // will just poke through the declaration into the related definition
            // and visit the fields there.

            getCompoundFields().flatMap { fs ->
                getIdentifier().map { id -> UnitDeclaration.Struct(id, fs, cursorAttributes(type())) }
            }
        }

    fun CXCursor.asUnion(): Result<UnitDeclaration.Union> =
        ifKind(CXCursor_UnionDecl, "union declaration") {
            // We check if this is the definition, because the field visitor
            // will just poke through the declaration into the related definition
            // and visit the fields there.

            getCompoundFields().flatMap { fs ->
                getIdentifier().map { id -> UnitDeclaration.Union(id, fs, cursorAttributes(type())) }
            }
        }

    fun CXType.fields(): List<CXCursor> {
        val ts = mutableListOf<CXCursor>()
        val wrapped = object : CXFieldVisitor() {
            override fun call(@ByVal field: CXCursor?, p2: CXClientData?): Int {
                ts.add(field!!)
                return CXVisit_Continue
            }
        }

        try {
            clang_Type_visitFields(this, wrapped, null)
        } finally {
            wrapped.deallocate()
        }

        return ts
    }

    fun CXCursor.asField(): Result<Field> =
        getIdentifier()
            .flatMap { id ->
                val type = type()

                when {
                    id.isBlank() && type.kind() == CXType_Record ->
                        // Record Types for anonymous fields are treated a little different,
                        // because anonymous inline struct/union type definitions cannot be
                        // elaborated, because that would change the visibility of the nested members.
                        //
                        // For example:
                        // struct Scope { struct { int a; int b; }; }
                        // has no elaborated counterpart such that the inner struct has a separate declaration
                        // but that still enables direct access of the innermost fields `a` and `b` on a value
                        // of the outermost struct type.
                        clang_getTypeDeclaration(type)
                            .asDeclaration()
                            .flatMap { d ->
                                // sanity check: the type should not have a name,
                                assert(d.ident == "")
                                when (d) {
                                    is UnitDeclaration.Struct ->
                                        Field
                                            .AnonymousRecord(StructOrUnion.Struct, d.fields.getOrElse { listOf() })
                                            .right()
                                    is UnitDeclaration.Union  ->
                                        Field
                                            .AnonymousRecord(StructOrUnion.Union , d.fields.getOrElse { listOf() })
                                            .right()
                                    else -> "Invariant violation: failed to parse anonymous field.".left()
                                }
                            }

                    else -> {
                        val attrs = this.cursorAttributes(type())
                        type()
                            .asType()
                            .map { t ->
                                Field.Leaf(
                                    id,
                                    t,
                                    clang_getFieldDeclBitWidth(this).let { if (it == -1) None else Some(it) })
                            }
                    }
                }
            }

    fun CXCursor.asVariable(): Result<UnitDeclaration.Var<CXCursor>> =
        ifKind(CXCursor_VarDecl, "variable declaration") {
            type()
                .asType()
                .flatMap { type ->
                    val rhs: Option<Result<CXCursor>> = if (clang_isCursorDefinition(this).toBool()) {
                        val subexps = this.children().filter { clang_isExpression(it.kind()).toBool() }
                        when {
                            // most global variable initializations are of the form `<typed name> = <exp>`
                            subexps.size == 1                                               ->
                                subexps[0].right().some()
                            // an array can be initialized as xs[size] = expr,
                            // in which case we have two sub-expressions, and the last one represents the rhs.
                            type is Type.Array && type.size.isDefined() && subexps.size > 1 ->
                                subexps.last().right().some()
                            // some other form that has a differently shaped AST that we have not forseen
                            else                                                            ->
                                "Failed to extract right-hand side of variable declaration '${spelling()}'.".left()
                                    .some()
                        }
                    } else None

                    rhs.sequenceEither()
                        .flatMap { def ->
                            getIdentifier().map { id -> UnitDeclaration.Var(id, type, def) }
                        }
                }
        }

    fun CXCursor.getReturnType(): Result<Type> {
        val typ = clang_getCursorResultType(this)
        return typ.asType()
    }

    fun CXCursor.getParameters(): Result<List<Param>> {
        val nargs = clang_Cursor_getNumArguments(this)
        return (0 until nargs)
            .map { clang_Cursor_getArgument(this, it).asParam() }
            .sequence()
    }

    fun CXCursor.asFunctionDef(): Result<UnitDeclaration.Fun<CXCursor>> =
        asFunctionDecl().flatMap { decl ->
            Either
                .fromNullable(children().find { clang_isStatement(it.kind()).toBool() })
                .mapLeft { "Could not parse function body of ${decl.ident}." }
                .map { stmt ->
                    // Search stmt for effectively global declaration that need to be elaborated
                    stmt.fold(Unit, true){
                        result -> if (isEffectivelyGlobal()) this.lazyElaborate()
                    }
                    decl.copy(body = stmt.some()) }
        }

    /**
     * return   true if the CXCursor, as child of a function definition, is a global declaration.
     *          Examples: an extern global variable declaration or a function declaration inside a function body
     */
    fun CXCursor.isEffectivelyGlobal() : Boolean =
        when (kind() ) {
            CXCursor_FunctionDecl -> true
            CXCursor_VarDecl -> {
                when (getStorage()) {
                    is Storage.Static -> true
                    is Storage.Extern -> true
                    else -> false
                }
            }
            else -> false
        }

    fun CXCursor.asFunctionDecl(): Result<UnitDeclaration.Fun<CXCursor>> =
        ifKind(CXCursor_FunctionDecl, "function declaration") {
            getReturnType().flatMap { resultType ->
                getParameters().flatMap { params ->
                    getIdentifier()
                        .filterOrOther({ it != "" }) { "Anonymous function declarations are not allowed." }
                        .map { id ->
                            UnitDeclaration.Fun(
                                id,
                                clang_Cursor_isFunctionInlined(this).toBool(),
                                Type.Fun(
                                    resultType,
                                    params,
                                    clang_Cursor_isVariadic(this).toBool(),
                                    cursorAttributes(type())
                                )
                            )
                        }
                }
            }
        }

    fun CXCursor.asParam(): Result<Param> =
        ifKind(CXCursor_ParmDecl, "parameter declaration") {
            type()
                .asType()
                .flatMap { type ->
                    getIdentifier()
                        .map { id -> Param(id, type) }
                }
        }

    /**
     * Get the entity kind for the declaration/definition under the cursor.
     */
    fun CXCursor.getEntityKind(): Result<EntityKind> = when (kind()) {
        CXCursor_StructDecl   -> EntityKind.Struct.right()
        CXCursor_UnionDecl    -> EntityKind.Union.right()
        CXCursor_EnumDecl     -> EntityKind.Enum.right()
        CXCursor_FunctionDecl -> EntityKind.Fun.right()
        CXCursor_VarDecl      -> EntityKind.Var.right()
        CXCursor_TypedefDecl  -> EntityKind.Typedef.right()
        else                  -> "Failed to parse entity kind from cursor kind '${kindName()}'".left()
    }

    fun CXCursor.getElaboratedSymbol(): Result<Symbol> =
        Either
            .fromNullable(elaborated[hash()])
            .mapLeft { "Failed to get elaborated name for cursor." }

    /**
     * Mark the CXCursor for elaboration (if it has not been already) and return
     * a symbol for the definition under the cursor.
     * The returned symbol is stable and can be used to reference the
     * AST node that will be generated by the elaborated possibly later.
     */
    fun CXCursor.lazyElaborate(): Result<Symbol> =
        // elaboration may have already (re)named the definition under the cursor,
        // so we check our tables
        getElaboratedSymbol()
            // if not, we will generate one now,
            // and record the generated symbol.
            .handleErrorWith {
                generateSymbol()
                    .tap { sym ->
                        assert(hash() !in elaborated)

                        // We remember that we chose a name for this definition,
                        elaborated[hash()] = sym
                        // and we mark the cursor for elaboration.
                        toBeElaborated.add(this)
                    }
            }

    fun CXCursor.generateSymbol(): Result<Symbol> =
        // get what the programmer wrote.
        getEntityKind()
            .zip(getIdentifier())
            .map { (kind, id) ->
                // if the definition is anonymous, we generate a fresh name for it
                val ident = if (id == "") freshAnonymousIdentifier() else id
                Symbol(tuid, TLID(ident, kind))
            }

    fun CXType.asType(): Result<Type> =
        when (kind()) {
            CXType_Void            -> Type.Void().right()
            CXType_Bool            -> Type.Int(IKind.IBoolean).right()
            CXType_Char_U          -> Type.Int(IKind.IUChar).right()
            CXType_UChar           -> Type.Int(IKind.IUChar).right()
            CXType_UShort          -> Type.Int(IKind.IUShort).right()
            CXType_UInt            -> Type.Int(IKind.IUInt).right()
            CXType_ULong           -> Type.Int(IKind.IULong).right()
            CXType_ULongLong       -> Type.Int(IKind.IULongLong).right()
            CXType_Char_S          -> Type.Int(IKind.IChar).right()
            CXType_SChar           -> Type.Int(IKind.ISChar).right()
            CXType_Short           -> Type.Int(IKind.IShort).right()
            CXType_Int             -> Type.Int(IKind.IInt).right()
            CXType_Long            -> Type.Int(IKind.ILong).right()
            CXType_LongLong        -> Type.Int(IKind.ILongLong).right()
            CXType_Float           -> Type.Float(FKind.FFloat).right()
            CXType_Double          -> Type.Float(FKind.FDouble).right()
            CXType_LongDouble      -> Type.Float(FKind.FLongDouble).right()
            CXType_Complex         -> "_Complex is not yet supported".left()
//                clang_getElementType(this)
//                    .asType()
//                    .flatMap {
//                        when (it) {
//                            is Type.Float -> Type.Complex(it.kind).right()
//                            else          -> "Complex element type is not a float.".left()
//                        }
//                    }

            CXType_Pointer         ->
                clang_getPointeeType(this)
                    .asType()
                    .map { Type.Ptr(it) }

            CXType_Typedef         -> {
                val cursor = clang_getTypeDeclaration(this)
                cursor
                    .getIdentifier()
                    .flatMap { id ->
                        // clang presents this as a typedef.
                        if (id == "__builtin_va_list") {
                            Type.VaList().right()
                        } else {
                            cursor
                                .lazyElaborate()
                                .map { Type.Typedeffed(it.tlid) }
                        }
                    }
            }

            // Array whose size could be determined statically
            CXType_ConstantArray   ->
                clang_getArrayElementType(this)
                    .asType()
                    .map { Type.Array(it, clang_getArraySize(this).some()) }

            // Array whose size is given by an expression that has
            // no value at compile-time.
            CXType_VariableArray   ->
                clang_getArrayElementType(this)
                    .asType()
                    .map { Type.Array(it) }

            // CXType_DependentSizedArray is C++ only afaik
            // see https://github.com/llvm-mirror/clang/blob/master/include/clang/AST/Type.h

            CXType_IncompleteArray ->
                clang_getArrayElementType(this)
                    .asType()
                    .map { Type.Array(it) }

            // http://clang.llvm.org/doxygen/classclang_1_1FunctionNoProtoType.html
            CXType_FunctionNoProto ->
                clang_getResultType(this)
                    .asType()
                    .map { retType -> Type.Fun(retType, listOf(), false) }

            CXType_FunctionProto   ->
                clang_getResultType(this)
                    .asType()
                    .flatMap { retType ->
                        (0 until clang_getNumArgTypes(this))
                            .map { i ->
                                clang_getArgType(this, i)
                                    .asType()
                                    .map { type -> Param("", type) }
                            }
                            .sequence()
                            .map { args -> Type.Fun(retType, args, false) }
                    }

            CXType_Atomic          -> "_Atomic is not yet supported".left()
//                clang_Type_getValueType(this)
//                    .asType()
//                    .map { Type.Atomic(it) }

            // Special type kind for inline declarations are elaborated by clang.
            CXType_Elaborated      ->
                clang_Type_getNamedType(this)
                    .asType()

            CXType_Record          -> {
                clang_getTypeDeclaration(this)
                    .lazyElaborate()
                    .flatMap { sym ->
                        when (sym.kind) {
                            EntityKind.Struct -> Type.Struct(sym.tlid).right()
                            EntityKind.Union  -> Type.Union(sym.tlid).right()
                            else              -> "Invariant violation: failed to reference elaborated struct/union.".left()
                        }
                    }
            }

            CXType_Enum            -> {
                clang_getTypeDeclaration(this)
                    .lazyElaborate()
                    .map { sym -> Type.Enum(sym.tlid) }
            }

            CXType_Unexposed       -> {
                // Some types that libclang keeps symbolic it does not expose via the libclang interface.
                // In particular: typeof expressions.
                // But, we can try to get a semantically equivalent type at this point from libclang
                // and parse that instead:
                clang_getCanonicalType(this).asType()
            }

            // There are others, but as far as I know, these are non-C types.
            else                   -> "Could not parse type of kind '${kindName()}'".left()
        }.map { type -> type.withAttrs(getTypeQualifiers()) }

    fun CXType.getTypeQualifiers(): Attrs {
        val attrs = mutableListOf<Attr>()
        if (clang_isVolatileQualifiedType(this).toBool())
            attrs.add(TypeQualifier.Volatile)
        if (clang_isRestrictQualifiedType(this).toBool())
            attrs.add(TypeQualifier.Restrict)
        if (clang_isConstQualifiedType(this).toBool())
            attrs.add(TypeQualifier.Constant)
        return attrs
    }

    fun CXCursor.cursorAttributes(type: CXType): Attrs =
        fold(monoid = Monoid.list(), true) {
            if (clang_isAttribute(kind()).toBool()) {
                // there are a bunch of attributes that we do not support/parse yet.
                // we should not crash at this time on those attributes, but rather choose the lesser evil
                // of ignoring them.
                // TODO actually parse them
                asAttribute(type).orNone().toList()
            } else listOf()
        }

    fun CXCursor.asAttribute(type: CXType): Result<Attr> = when (kind()) {
        CXCursor_ConstAttr   -> TypeQualifier.Constant.right()
        CXCursor_AlignedAttr ->
            type.getAlignment()
                .toEither { "Could not get alignment for type with alignment attribute." }
                .map { Attr.AlignAs(it) }
        CXCursor_UnexposedAttr  -> {
            // In part this is a problem with libclang. There does not seem to be an API to get arbitrary
            // attributes like __attribute__((weak)) from the cursor: they are reported as 'UnexposedAttr'.
            // To properly solve this we need a libclang extension...
            // For now we rely on the fact that we can get the underlying tokens, and we can parse the attributes
            // that we are interested in from that manually.
            this
                .tokens(cxTranslationUnit)
                .asUnexposedAttr()
        }
        CXCursor_PackedAttr     -> Attr.Packed.right()
        /* TODO
    CXCursor_AnnotateAttr   -> TODO()
    CXCursor_AsmLabelAttr   -> TODO()
    CXCursor_PureAttr       -> TODO()
    CXCursor_NoDuplicateAttr -> TODO()
    CXCursor_VisibilityAttr -> TODO()
    CXCursor_ConvergentAttr -> TODO()
    CXCursor_WarnUnusedAttr -> TODO()
    CXCursor_WarnUnusedResultAttr -> TODO()
    */

        else                 -> "Unknown attribute of kind ${kindName()}".left()
    }

    fun List<String>.asUnexposedAttr(): Result<Attr> = when {
        size == 1 && this[0] == "weak" -> Attr.Weak.right()
        else -> "Unknown attribute".left()
    }

    fun CXType.getAlignment(): Option<Long> {
        val align = clang_Type_getAlignOf(this)
        return when {
            align < 0 -> none()
            else      -> align.some()
        }
    }
}
