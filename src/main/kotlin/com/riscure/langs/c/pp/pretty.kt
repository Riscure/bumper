package com.riscure.langs.c.pp

import arrow.core.*
import com.riscure.langs.c.ast.*
import com.riscure.langs.c.pp.writer.*

/**
 * Total pretty printing functions, mainly for C types.
 */
object Pretty {
    fun integerKind(kind: IKind): String = when(kind) {
        IKind.IBoolean -> "bool"
        IKind.IChar    -> "char"
        IKind.ISChar   -> "signed char"
        IKind.IUChar   -> "unsigned char"
        IKind.IShort   -> "short"
        IKind.IUShort  -> "unsigned short"
        IKind.IInt     -> "int"
        IKind.IUInt    -> "unsigned int"
        IKind.ILong    -> "long"
        IKind.IULong   -> "unsigned long"
        IKind.ILongLong -> "long long"
        IKind.IULongLong -> "unsigned long long"
    }
    fun floatKind(kind: FKind): String = when (kind) {
        FKind.FFloat -> "float"
        FKind.FDouble -> "double"
        FKind.FLongDouble -> "long double"
    }

    fun declaration(ident: String, type: Type): String {
        val (remainingType, decl) = namePart(type, ident)
        return "${typePrefix(remainingType)} $decl".trim()
    }

    private fun formals(params: List<Param>): String =
        params.joinToString(separator=", ") { declaration(it.name, it.type) }

    private fun namePart(type: Type, name: String): Pair<Type, String> = when (type) {
        is Type.Ptr   -> namePart(type.pointeeType, "*${name}")
        is Type.Fun   -> {
            // this is strange, but true, I think
            namePart(type.returnType, "($name)(${formals(type.params)})")
        }
        is Type.Array -> namePart(type.elementType, "$name[${type.size.getOrElse { "" }}]")
        else          -> Pair(type, name)
    }

    private fun typePrefix(type: Type): String = when (type) {
        // This part should have been printed by the name-part printer
        is Type.Array  ->
            throw RuntimeException("Failed to pretty-print ill-formed type.")
        is Type.Fun    ->
            throw RuntimeException("Failed to pretty-print ill-formed type.")

        // Possible remainders:
        is Type.Ptr    -> "${typePrefix(type.pointeeType)}*"
        is Type.Enum   -> type.id
        is Type.Float  -> floatKind(type.kind)
        is Type.Int    -> integerKind(type.kind)
        is Type.Named  -> type.id
        is Type.Struct -> "(struct ${type.id})"
        is Type.Union  -> "(union ${type.id})"
        is Type.Void   -> "void"
    }

    fun prototype(thefun: TopLevel.Fun): String {
        assert(thefun.returnType !is Type.Array) { "Invariant violation while pretty-printing type" }
        return declaration("${thefun.name}(${formals(thefun.params)})", thefun.returnType)
    }

    fun typedef(typedef: TopLevel.Typedef): String = "typedef ${declaration(typedef.name, typedef.underlyingType)}"
}

/**
 * A class that knows how to text ASTs as long as you provide
 * the method for writing the bodies of top-level definitions.
 */
class AstWriters(
    /**
     * A factory for writers for top-level entity bodies.
     * It is expected that the bodyWriter includes the non-mandatory whitespace around the rhs's.
     */
    val getBodySource: (toplevel: TopLevel) -> Either<Throwable, String>
) {
    private val semicolon = text(";")

    fun print(unit: TranslationUnit): Either<Throwable,Writer> =
        unit.decls
            .map { print(it) }
            .sequence()
            .map { writers -> sequence(writers, separator = text("\n")) }

    fun print(toplevel: TopLevel): Either<Throwable, Writer> = when (toplevel) {
        is TopLevel.Var -> {
            text(Pretty.declaration(toplevel.name, toplevel.type))
                .right()
                .flatMap { lhs ->
                    val rhs = if (toplevel.isDefinition) {
                        getBodySource(toplevel).map { body -> text(" =$body;") }
                    } else {
                        semicolon.right()
                    }

                    rhs.map { lhs andThen it }
                }
        }

        is TopLevel.Fun -> {
            text(Pretty.prototype(toplevel))
                .right()
                .flatMap { lhs ->
                    val rhs = if (toplevel.isDefinition) {
                        getBodySource(toplevel).map { body -> text(" {$body}") }
                    } else {
                        semicolon.right()
                    }

                    rhs.map { lhs andThen it }
                }
        }

        is TopLevel.Typedef ->
            text(Pretty.typedef(toplevel)).right()

        is TopLevel.Composite ->
            getBodySource(toplevel)
                .map { rhs -> text("struct ${toplevel.name} $rhs;") }

        is TopLevel.EnumDef ->
            getBodySource(toplevel)
                .map { rhs -> text("enum ${toplevel.name} {$rhs};") }
    }
}