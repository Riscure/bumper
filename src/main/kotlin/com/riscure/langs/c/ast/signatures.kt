// This is a fully type-annotated C source AST.
// The representation is ported from the CompCert CParser elaborated AST.
package com.riscure.langs.c.ast

import arrow.core.*
import java.nio.file.Path
import kotlin.collections.Map.Entry

typealias Name  = String
typealias Ident = String
data class Location(val sourceFile: Path, val row: Int, val col: Int)
data class SourceRange(val begin: Location, val end: Location)

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

typealias Attrs = List<Attr>

/* Types */
sealed class Type {
    abstract val attrs: Attrs

    data class Void   (override val attrs: Attrs = listOf()): Type()
    data class Int    (val kind: IKind, override val attrs: Attrs = listOf()): Type()
    data class Float  (val kind: FKind, override val attrs: Attrs = listOf()): Type()
    data class Ptr    (val type: Type, override val attrs: Attrs = listOf()): Type()
    data class Array  (val type: Type, val size: Option<Long> = None, override val attrs: Attrs = listOf()): Type()
    data class Fun    (val retType: Type, val args: List<Type>, val vararg: Boolean, override val attrs: Attrs = listOf()): Type()
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
    val name: Ident,
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
interface TopLevel {
    val name: Ident

    val meta: Meta
    fun withMeta(meta: Meta): TopLevel

    /* Mixin */
    interface Typelike : TopLevel {
        fun definesType(): Type
    }

    data class Var(
        override val name: Ident,
        val type: Type,
        override val meta: Meta = Meta.default
    ): TopLevel {
        override fun withMeta(meta: Meta) = this.copy(meta = meta)
    }

    data class Fun(
        override val name: Ident,
        val inline: Boolean,
        val ret: Type,
        val params: List<Param>,
        val vararg: Boolean,
        val definition: Boolean = false,
        override val meta: Meta = Meta.default,
    ): TopLevel {
        override fun withMeta(meta: Meta) = this.copy(meta = meta)

        fun type(): Type = Type.Fun(ret, params.map { it.type }, vararg)
    }

    data class Composite(
        override val name: Ident,
        val structOrUnion: StructOrUnion,
        val fields: FieldDecls,
        override val meta: Meta = Meta.default
    ): Typelike {
        override fun definesType(): Type =
            when (structOrUnion) {
                StructOrUnion.Struct -> Type.Struct(name)
                StructOrUnion.Union  -> Type.Union(name)
            }

        override fun withMeta(meta: Meta) = this.copy(meta = meta)
    }

    data class Typedef(
        override val name: Ident,
        val typ: Type,
        override val meta: Meta = Meta.default
    ): Typelike {
        override fun withMeta(meta: Meta) = this.copy(meta = meta)
        override fun definesType(): Type = Type.Named(name, typ)
    }

    data class EnumDef(
        override val name: Ident,
        val enumerators: List<Enumerator>,
        override val meta: Meta = Meta.default
    ): Typelike {
        override fun withMeta(meta: Meta) = this.copy(meta = meta)
        override fun definesType(): Type = Type.Enum(name)
    }
}

data class TranslationUnit(
    val decls: List<TopLevel>
) {
    /**
     *  Mapping from locations to top-level entities.
     *  Toplevel entities without locations are not represented
     */
    private val byLocation: Map<Location, TopLevel> by lazy {
        decls
            .flatMap {
                when (val loc = it.meta.location) {
                    is Some -> listOf(Pair(loc.value.begin, it))
                    None    -> listOf()
                }
            }
            .toMap()
    }

    fun getByLocation(loc: Location): Option<TopLevel> = byLocation.get(loc).toOption()
}

/* filters for toplevel declarations */

fun List<TopLevel>.functions(): List<TopLevel.Fun> =
    filterIsInstance(TopLevel.Fun::class.java)

fun List<TopLevel.Fun>.definitions(): List<TopLevel.Fun> =
    filter { it.definition }

fun List<TopLevel.Fun>.declarations(): List<TopLevel.Fun> =
    filter { !it.definition }

fun List<TopLevel>.typelike(): List<TopLevel.Typelike> =
    filterIsInstance(TopLevel.Typelike::class.java)