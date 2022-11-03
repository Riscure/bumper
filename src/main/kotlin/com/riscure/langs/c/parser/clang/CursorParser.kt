package com.riscure.langs.c.parser.clang

import arrow.core.*
import arrow.typeclasses.Monoid
import com.riscure.getOption
import com.riscure.langs.c.ast.*
import com.riscure.langs.c.index.TUID
import com.riscure.toBool
import org.bytedeco.javacpp.annotation.ByVal
import org.bytedeco.llvm.clang.*
import org.bytedeco.llvm.global.clang.*

private typealias Result<T> = Either<String, T>
private typealias ClangDeclaration = Declaration<CXCursor, CXCursor>
private typealias CursorHash = Int

/**
 * A stateful translation from libclang's CXCursors to our typed C ASTs.
 */
class CursorParser(
    /**
     * Mapping cursors of declarations to the parsed declarations.
     */
    private val declarationTable: MutableMap<CursorHash, ClangDeclaration> = mutableMapOf(),
    /**
     * A mapping of declarations to the cursor identifier of their corresponding definitions.
     */
    private val resolutionTable: MutableMap<ErasedDeclaration, CursorHash> = mutableMapOf(),
): ICursorParser {

    /**
     * Record a parsed declaration in the parse state tables.
     */
    private fun rememberDeclaration(cursor: CXCursor, decl: ClangDeclaration) {
        val id = cursor.hash()

        declarationTable[id] = decl

        // Ask clang if this declaration has a definition.
        // Store the cursor hash of the definition for now.
        // This will allos us to find the corresponding ClangDeclaration from declarationTable
        // once we parsed the entire translation unit. At this moment the definition might not yet
        // been seen, because the declaration can be a forward declaration.
        clang_getCursorDefinition(cursor)
            .filterNullCursor()
            .tap { resolutionTable[decl] = it.hash() }
    }

    private fun getDefinitions(): Result<Map<ErasedDeclaration, ClangDeclaration>> =
        Either.catch({"Failed to resolve declaration to corresponding definition."}) {
            resolutionTable.mapValues { entry ->
                when (val def = declarationTable[entry.value].toOption()) {
                    is None -> throw RuntimeException()
                    is Some -> def.value
                }
            }
        }

    /**
     * Combinator to fail with a consistent message if we have an unexpected cursor kind.
     */
    private fun <T> CXCursor.ifKind(k: Int, expectation: String, whenMatch: () -> Result<T>): Result<T> {
        if (kind() != k) {
            return "Expected ${expectation}. Got cursor of kind ${kindName()}".left()
        }

        return whenMatch()
    }

    override fun CXCursor.asTranslationUnit(tuid: TUID): Result<TranslationUnit<CXCursor, CXCursor>> =
        ifKind(CXCursor_TranslationUnit, "translation unit") {
            children()
                .filter { cursor ->
                    when {
                        // somehow e.g. an empty ';' results in top-level UnexposedDecls
                        // not sure what else causes them.
                        cursor.kind() == CXCursor_UnexposedDecl -> false
                        // Clang expands typedef struct {..} to two top-level declarations, one of which is anonymous.
                        else                                    -> true
                    }
                }
                .map { it.asDeclaration() }
                .sequence()
                .flatMap { decls -> getDefinitions().map { TranslationUnit(tuid, decls, it) }}
        }

    override fun CXCursor.asDeclaration(): Result<Declaration<CXCursor, CXCursor>> {
        // We might as well check if we already parsed this one:
        declarationTable[this.hash()].toOption().tap { return it.right() }

        // If not, lets go and parse it:
        val decl = when (kind()) {
            CXCursor_FunctionDecl ->
                if (children().any { child -> child.kind() == CXCursor_CompoundStmt })
                    asFunctionDef()
                else asFunctionDecl()
            CXCursor_StructDecl   -> this.asStructDecl()
            CXCursor_UnionDecl    -> this.asUnionDecl()
            CXCursor_VarDecl      -> this.asVarDecl()
            CXCursor_TypedefDecl  -> this.asTypedef()
            CXCursor_EnumDecl     -> this.asEnumDecl()
            else                  -> "Expected toplevel declaration, got kind ${kindName()}".left()
        }

        // Fill in the properties of the Declaration class.
        return decl
            .map {
                it
                    .withMeta(getMetadata())
                    .withStorage(getStorage())
                    .withVisibility(getVisibility())
            }
            // Record the parsed declaration in the symboltable
            .tap { rememberDeclaration(this, it) }
    }

    private fun String.validateIdentifier(): Option<String> =
        this.some().filter { Regex("[_a-zA-Z]\\w*").matches(it) }

    fun CXCursor.getIdentifier(): Option<String> = spelling().validateIdentifier()

    /**
     * Collects storage for a toplevel declaration cursor.
     */
    fun CXCursor.getStorage(): Storage = clang_Cursor_getStorageClass(this).asStorage()
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
            presumedLocation = getPresumedLocation(),
            doc = comment
        )
    }

    /** Gets the visibility of a definition */
    fun CXCursor.getVisibility(): Visibility =
        if (isLocalDefinition()) Visibility.Local else Visibility.Global

    fun CXCursor.asTypedef(): Result<Declaration.Typedef> =
        ifKind(CXCursor_TypedefDecl, "typedef") {
            clang_getTypedefDeclUnderlyingType(this)
                .asType()
                .map { type -> Declaration.Typedef(clang_getTypedefName(type()).string.validateIdentifier(), type) }
        }

    fun CXCursor.asEnumDecl(): Result<Declaration.Enum> =
        ifKind(CXCursor_EnumDecl, "enum declaration") {
            Declaration.Enum(getIdentifier(), None).right() // TODO enumerators
        }

    private fun CXCursor.asComposite(): Result<Declaration.Composite> {
        // We check if this is the definition.
        val fields = if (clang_isCursorDefinition(this).toBool()) {
            type()
                .fields()
                .map { it.asField() }
                .sequence()
                .map { it.some() }
        } else {
            // For declarations, fields is None
            None.right()
        }

        return fields
            .map { fs -> Declaration.Composite(getIdentifier(), StructOrUnion.Struct, fs) }
    }

    fun CXCursor.asStructDecl(): Result<Declaration.Composite> =
        ifKind(CXCursor_StructDecl, "struct declaration") {
            asComposite().map { c -> c.copy(structOrUnion = StructOrUnion.Struct) }
        }

    fun CXCursor.asUnionDecl(): Result<Declaration.Composite> =
        ifKind(CXCursor_UnionDecl, "union declaration") {
            asComposite().map { c -> c.copy(structOrUnion = StructOrUnion.Union) }
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
        type()
            .asType()
            .map { type ->
                val id = getIdentifier().getOrElse { "" }
                val attrs = this.cursorAttributes(type())
                Field(
                    id,
                    type.withAttrs(type.attrs + attrs),
                    clang_getFieldDeclBitWidth(this).let { if (it == -1) None else Some(it) },
                    id.isEmpty()
                )
            }

    fun CXCursor.asVarDecl(): Result<Declaration.Var<CXCursor>> =
        ifKind(CXCursor_VarDecl, "variable declaration") {
            type()
                .asType()
                .flatMap { type ->
                    val rhs: Option<Result<CXCursor>> = if (clang_isCursorDefinition(this).toBool()) {
                        val subexps = this.children().filter { clang_isExpression(it.kind()).toBool() }
                        if (subexps.size != 1) {
                            "Failed to extract right-hand side of variable declaration.".left().some()
                        } else subexps[0].right().some()
                    } else None

                    rhs.sequenceEither()
                        .map { def -> Declaration.Var(this.spelling(), type, def) }
                }
        }

    fun CXCursor.getReturnType(): Result<Type> {
        val typ = clang_getCursorResultType(this)
        return typ.asType()
    }

    fun CXCursor.getParameters(): Result<List<Param>> {
        val nargs = clang_Cursor_getNumArguments(this)
        return (0 until nargs)
            .map { clang_Cursor_getArgument(this, it) }
            .map { it.asParam() }
            .sequence()
    }

    fun CXCursor.asFunctionDef(): Result<Declaration.Fun<CXCursor>> =
        asFunctionDecl().flatMap { decl ->
            Either
                .fromNullable(children().find { clang_isStatement(it.kind()).toBool() })
                .mapLeft { "Could not parse function body of ${decl.name}." }
                .map { decl.copy(body = it.some()) }
        }

    fun CXCursor.asFunctionDecl(): Result<Declaration.Fun<CXCursor>> =
        ifKind(CXCursor_FunctionDecl, "function declaration") {
            getReturnType().flatMap { resultType ->
                getParameters().map { params ->
                    Declaration.Fun(
                        spelling(),
                        clang_Cursor_isFunctionInlined(this).toBool(),
                        resultType,
                        params,
                        clang_Cursor_isVariadic(this).toBool(),
                    )
                }
            }
        }

    fun CXCursor.asParam(): Result<Param> =
        ifKind(CXCursor_ParmDecl, "parameter declaration") {
            type()
                .asType()
                .map { type ->
                    Param(spelling(), type)
                }
        }

    /**
     * When 'struct S' or 'struct S { ... }' or similar appear as types,
     * Libclang calls this an 'elaborated type'. Probably because Clang actually hoists inline
     * definitions to top-level nodes in its internal AST.
     */
    fun CXCursor.asElaboratedType(): Result<Type> =
        when (kind()) {
            CXCursor_EnumDecl   -> asEnumDecl().map   { Type.InlineDeclaration(it) }
            CXCursor_StructDecl -> asStructDecl().map { Type.InlineDeclaration(it) }
            CXCursor_UnionDecl  -> asUnionDecl().map  { Type.InlineDeclaration(it) }
            else                -> "Expected a compound type declaration, got ${kindName()}".left()
        }

    /* Typedefs yield an assignable type */
    fun CXCursor.asTypedefType(): Result<Type> =
        ifKind(CXCursor_TypedefDecl, "typedef") {
            clang_getTypedefDeclUnderlyingType(this).asType()
        }

    /**
     * Return a reference to the definition under the cursor.
     */
    fun CXCursor.getRef(byName: String): Result<Ref<ClangDeclaration>> {
        // first sanity check if this is indeed a definition
        if (!clang_isCursorDefinition(this).toBool()) { return "Expected definition, to ${kindName()}.".left() }

        // get the parsed declaration
        return when (val decl = declarationTable[this.hash()].toOption()) {
            is None -> "Failed to resolve name ${byName} to declaration".left()
            is Some -> Ref(byName, decl.value).right()
        }

    }

    fun CXType.asRef(): Result<Ref<ClangDeclaration>> = clang_getTypeDeclaration(this).getRef(spelling())

    override fun CXType.asType(): Result<Type> =
        when (kind()) {
            CXType_Void            -> Type.Void().right()
            CXType_Bool            -> Type.Int(IKind.IBoolean).right()
            CXType_Char_U          -> Type.Int(IKind.IUChar).right() // correct?
            CXType_UChar           -> Type.Int(IKind.IUChar).right() // correct?
            CXType_UShort          -> Type.Int(IKind.IUShort).right()
            CXType_UInt            -> Type.Int(IKind.IUInt).right()
            CXType_ULong           -> Type.Int(IKind.IULong).right()
            CXType_ULongLong       -> Type.Int(IKind.IULongLong).right()
            CXType_Char_S          -> Type.Int(IKind.IChar).right() // correct?
            CXType_SChar           -> Type.Int(IKind.ISChar).right() // correct?
            CXType_Short           -> Type.Int(IKind.IShort).right()
            CXType_Int             -> Type.Int(IKind.IInt).right()
            CXType_Long            -> Type.Int(IKind.ILong).right()
            CXType_LongLong        -> Type.Int(IKind.ILongLong).right()
            CXType_Float           -> Type.Float(FKind.FFloat).right()
            CXType_Double          -> Type.Float(FKind.FDouble).right()
            CXType_LongDouble      -> Type.Float(FKind.FLongDouble).right()
            CXType_Complex         ->
                clang_getElementType(this)
                    .asType()
                    .flatMap {
                        when (it) {
                            is Type.Float -> Type.Complex(it.kind).right()
                            else          -> "Complex element type is not a float.".left()
                        }
                    }

            CXType_Pointer         -> clang_getPointeeType(this).asType().map { Type.Ptr(it) }
            CXType_Typedef         ->
                asRef()
                    .flatMap { ref ->
                        when (val d = ref.reffed) {
                            is Declaration.Typedef -> Ref(ref.byName, d).right()
                            else                   -> "Typedef ${ref.byName} resolved to non-typedef declaration".left()
                        }
                    }
                    .map { Type.Typedeffed(it) }
            CXType_ConstantArray   ->
                clang_getArrayElementType(this)
                    .asType()
                    .map { Type.Array(it, clang_getArraySize(this).some()) }
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
                            .map { clang_getArgType(this, it).asType().map { type -> Param("", type) } }
                            .sequence()
                            .map { args -> Type.Fun(retType, args, false) }
                    }

            CXType_Atomic          ->
                clang_Type_getValueType(this)
                    .asType()
                    .map { Type.Atomic(it) }

            // Special type kind for inline declarations.
            // Clang elaborated the inline declaration to a top-level entity.
            // We represent it inline, so that pretty-printing recovers the original,
            // and we do not expose new names after pretty-printing.
            // The elaborated declaration can have a name! If the anonymous declaration is in a function parameter,
            // the name is not visible outside of the function body.
            CXType_Elaborated      -> clang_getTypeDeclaration(this).asElaboratedType()

            // There are others, but as far as I know, these are non-C types.
            else                   -> "Could not parse type of kind '${kindName()}'".left()
        }.map { type -> type.withAttrs(getTypeAttrs()) }

    fun CXType.getTypeAttrs(): Attrs {
        val attrs = mutableListOf<Attr>()
        if (clang_isVolatileQualifiedType(this).toBool())
            attrs.add(Attr.Volatile)
        if (clang_isRestrictQualifiedType(this).toBool())
            attrs.add(Attr.Restrict)
        if (clang_isConstQualifiedType(this).toBool())
            attrs.add(Attr.Constant)
        return attrs
    }

    fun CXCursor.cursorAttributes(type: CXType): Attrs =
        fold(monoid = Monoid.list(), true) {
            if (clang_isAttribute(kind()).toBool()) {
                asAttribute(type).orNone().toList()
            } else listOf()
        }

    fun CXCursor.asAttribute(type: CXType): Result<Attr> = when (kind()) {
        CXCursor_ConstAttr   -> Attr.Constant.right()
        CXCursor_AlignedAttr ->
            type.getAlignment()
                .toEither { "Could not get alignment for type with alignment attribute." }
                .map { Attr.AlignAs(it) }

        /* TODO
    CXCursor_UnexposedAttr  -> TODO()
    CXCursor_AnnotateAttr   -> TODO()
    CXCursor_AsmLabelAttr   -> TODO()
    CXCursor_PackedAttr     -> TODO()
    CXCursor_PureAttr       -> TODO()
    CXCursor_NoDuplicateAttr -> TODO()
    CXCursor_VisibilityAttr -> TODO()
    CXCursor_ConvergentAttr -> TODO()
    CXCursor_WarnUnusedAttr -> TODO()
    CXCursor_WarnUnusedResultAttr -> TODO()
    */

        else                 -> "Not a recognized attribute?".left()
    }

    fun CXType.getAlignment(): Option<Long> {
        val align = clang_Type_getAlignOf(this)
        return when {
            align < 0 -> none()
            else      -> align.some()
        }
    }
}