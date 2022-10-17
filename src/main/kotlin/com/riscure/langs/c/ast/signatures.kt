// This is a fully type-annotated C source AST.
// The representation is ported from the CompCert CParser elaborated AST.
package com.riscure.langs.c.ast

import arrow.core.*
import java.nio.file.Path

typealias Name  = String
typealias Ident = String

data class Location(
    val sourceFile: Path,
    /** The line number, with first line being 1 */
    val row: Int,
    /** The column number, with first column being 1 */
    val col: Int
) {
    /**
     * compares the row and col of [this] to [other], ignoring the sourceFile.
     */
    operator fun compareTo(other: Location): Int =
        Pair(row, col).compareTo(Pair(other.row, other.col))
}

data class SourceRange(
    /** begin location of the source range (inclusive) */
    val begin: Location,
    /** end location of the source range (inclusive) */
    val end: Location
) {
    /**
     * Returns true iff [this] range fully encloses [other]
     */
    fun encloses(other: SourceRange): Boolean =
        file == other.file && begin <= other.begin && end >= other.end

    fun encloses(other: Location): Boolean =
        file == other.sourceFile && begin <= other && end >= other

    /** Gets the sourceFile of [begin], which is assumed to match [end]'s */
    val file get() = begin.sourceFile
}

enum class IKind {
      IBoolean
    , IChar, ISChar, IUChar
    , IShort, IUShort
    , IInt, IUInt
    , ILong, IULong
    , ILongLong, IULongLong
}

enum class FKind {
    FFloat, FDouble, FLongDouble
}

/* Attributes */
sealed class AttrArg {
    data class AnIdent(val value: String) : AttrArg()
    data class AnInt(val value: Int) : AttrArg()
    data class AString(val value: String) : AttrArg()
}

sealed class Attr {
    object Constant : Attr()
    object Volatile : Attr()
    object Restrict : Attr()
    data class AlignAs(val alignment: Int) : Attr()
    data class NamedAttr(val name: String, val args: List<AttrArg>) : Attr()
}

enum class Storage {
    Default, // used for toplevel names without explicit storage
    Extern,
    Static,
    Auto, // used for block-scoped names without explicit storage
    Register
}

typealias Attrs = List<Attr>

/* Types */
sealed class Type {
    abstract val attrs: Attrs

    data class Void   (override val attrs: Attrs = listOf()): Type()
    data class Int    (val kind: IKind, override val attrs: Attrs = listOf()): Type()
    data class Float  (val kind: FKind, override val attrs: Attrs = listOf()): Type()
    data class Ptr    (val pointeeType: Type, override val attrs: Attrs = listOf()): Type()
    data class Array  (val elementType: Type, val size: Option<Long> = None, override val attrs: Attrs = listOf()): Type()
    data class Fun    (val returnType: Type, val params: List<Param>, val vararg: Boolean, override val attrs: Attrs = listOf()): Type()
    data class Named  (val id: Ident, val underlying: Type, override val attrs: Attrs = listOf()): Type()
    data class Struct (val id: Ident, override val attrs: Attrs = listOf()): Type()
    data class Union  (val id: Ident, override val attrs: Attrs = listOf()): Type()
    data class Enum   (val id: Ident, override val attrs: Attrs = listOf()): Type()
}

/* Struct or union field */
data class Field(
    val name: String
  , val type: Type
  , val bitfield: Int?
  , val anonymous: Boolean
)

enum class StructOrUnion { Struct, Union }

typealias FieldDecls = List<Field>

data class Param(
    val name: Ident = "",
    val type: Type
)

data class Enumerator(val name: Ident, val key: Long) // TODO missing optional exp?

/**
 * Metadata for the top-level elements.
 */
data class Meta(
    /**
     * The location where this element was parsed.
     */
    val location: Option<SourceRange> = None,
    /**
     * This location reflects {@code #line} directives,
     * as for example outputted by the preprocessor, pointing
     * poking through to the location beneath the {@code #include} directive.
     */
    val presumedLocation: Option<Location> = None,
    val doc: Option<String> = None,
    val fromMain: Boolean = true
) {
    companion object {
        val default = Meta(None, None, None, true)
    }
}

/* TODO fun possibly missing attributes and storage fields, as well as a pragma thingy? */
sealed interface TopLevel {
    val name: Ident

    /**
     * Meta-data comprises the file and location in file for the
     * top-level entity.
     */
    val meta: Meta
    fun withMeta(meta: Meta): TopLevel

    /**
     * Storage for the top-level entity (e.g., default or static).
     */
    val storage: Storage
    fun withStorage(storage: Storage): TopLevel

    val tlid: TLID
    val kind: EntityKind get() = tlid.kind

    /* Mixin */
    interface Typelike {
        fun definesType(): Type
    }

    data class Var(
        override val name: Ident,
        val type: Type,
        val isDefinition: Boolean = false,
        override val storage: Storage = Storage.Default,
        override val meta: Meta = Meta.default
    ): TopLevel {
        override fun withMeta(meta: Meta) = this.copy(meta = meta)
        override fun withStorage(storage: Storage) = this.copy(storage = storage)

        override val tlid: TLID get() = TLID(name, EntityKind.Var)
    }

    data class Fun(
        override val name: Ident,
        val inline: Boolean,
        val returnType: Type,
        val params: List<Param>,
        val vararg: Boolean,
        val isDefinition: Boolean = false,
        override val storage: Storage = Storage.Default,
        override val meta: Meta = Meta.default,
    ): TopLevel {
        override fun withMeta(meta: Meta) = this.copy(meta = meta)
        override fun withStorage(storage: Storage) = this.copy(storage = storage)

        fun type(): Type = Type.Fun(returnType, params, vararg)

        override val tlid: TLID get() =
            TLID(name, if (isDefinition) EntityKind.FunDef else EntityKind.FunDecl)
    }

    data class Composite(
        override val name: Ident,
        val structOrUnion: StructOrUnion,
        val fields: FieldDecls,
        override val storage: Storage = Storage.Default,
        override val meta: Meta = Meta.default
    ): TopLevel, Typelike {
        override fun definesType(): Type =
            when (structOrUnion) {
                StructOrUnion.Struct -> Type.Struct(name)
                StructOrUnion.Union  -> Type.Union(name)
            }

        override fun withMeta(meta: Meta) = this.copy(meta = meta)
        override fun withStorage(storage: Storage) = this.copy(storage = storage)

        override val tlid: TLID get() =
            TLID(name, when (structOrUnion) {
                StructOrUnion.Union -> EntityKind.Union
                StructOrUnion.Struct -> EntityKind.Struct
            })
    }

    data class Typedef(
        override val name: Ident,
        val underlyingType: Type,
        override val storage: Storage = Storage.Default,
        override val meta: Meta = Meta.default
    ): TopLevel, Typelike {
        override fun withMeta(meta: Meta) = this.copy(meta = meta)
        override fun withStorage(storage: Storage) = this.copy(storage = storage)
        override fun definesType(): Type = Type.Named(name, underlyingType)

        override val tlid: TLID get() = TLID(name, EntityKind.Typedef )
    }

    data class EnumDef(
        override val name: Ident,
        val enumerators: List<Enumerator>,
        override val storage: Storage = Storage.Default,
        override val meta: Meta = Meta.default
    ): TopLevel, Typelike {
        override fun withMeta(meta: Meta) = this.copy(meta = meta)
        override fun withStorage(storage: Storage) = this.copy(storage = storage)
        override val tlid: TLID get() = TLID(name, EntityKind.Enum)
        override fun definesType(): Type = Type.Enum(name)
    }

    fun ofKind(kind: EntityKind): Boolean = when (kind) {
        EntityKind.FunDecl -> this is Fun && !isDefinition
        EntityKind.FunDef -> this is Fun && isDefinition
        EntityKind.Enum -> this is EnumDef
        EntityKind.Struct -> this is Composite && structOrUnion == StructOrUnion.Struct
        EntityKind.Union -> this is Composite && structOrUnion == StructOrUnion.Union
        EntityKind.Typedef -> this is Typedef
        EntityKind.Var -> this is Var
    }
}

enum class EntityKind {
    FunDecl,
    FunDef,
    Enum,
    Struct,
    Union,
    Typedef,
    Var;

    companion object {
        fun kindOf(toplevel: TopLevel) = toplevel.kind
    }
}

/**
 * Identifies a top-level entity uniquely within a translation unit.
 */
data class TLID(val name: String, val kind: EntityKind)

data class TranslationUnit(
    val decls: List<TopLevel>
) {
    /**
     * Find a top-level element in this translation unit, based
     * on a given [TLID] [id].
     */
    fun get(id: TLID): Option<TopLevel> = decls
        .find { it.name == id.name && it.ofKind(id.kind) }
        .toOption()

    /* Map locations to top-level declarations */
    fun getAtLocation(loc: Location): Option<TopLevel> =
        decls
            .find { decl ->
                when (val range = decl.meta.location) {
                    is Some -> range.value.begin == loc
                    else    -> false
                }
            }
            .toOption()

    /* Map locations to top-level declarations */
    fun getEnclosing(range: SourceRange): Option<TopLevel> =
        decls
            .find { decl ->
                when (val loc = decl.meta.location) {
                    is Some -> loc.value.encloses(range)
                    else -> false
                }
            }
            .toOption()

    /* Map locations to top-level declarations */
    fun getEnclosing(loc: Location): Option<TopLevel> =
        decls
            .find { decl ->
                when (val range = decl.meta.location) {
                    is Some -> range.value.encloses(loc)
                    else -> false
                }
            }
            .toOption()
}

/* filters for toplevel declarations */

fun List<TopLevel>.functions(): List<TopLevel.Fun> =
    filterIsInstance(TopLevel.Fun::class.java)

fun List<TopLevel.Fun>.definitions(): List<TopLevel.Fun> =
    filter { it.isDefinition }

fun List<TopLevel.Fun>.declarations(): List<TopLevel.Fun> =
    filter { !it.isDefinition }

fun List<TopLevel>.typelike(): List<TopLevel.Typelike> =
    filterIsInstance(TopLevel.Typelike::class.java)