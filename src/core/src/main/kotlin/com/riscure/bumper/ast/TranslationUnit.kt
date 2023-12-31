@file:UseSerializers(NothingSerializer::class)

/**
 * This package contains a fully type-annotated C source AST *after elaboration*.
 *
 * The representation is similar to CompCert's elaborated AST:
 * - https://github.com/AbsInt/CompCert/blob/master/cparser/C.mli
 * And to Frama-C's elaborated AST:
 * - https://git.frama-c.com/pub/frama-c/-/blob/master/src/kernel_services/ast_data/cil_types.ml
 */
package com.riscure.bumper.ast

import arrow.core.*
import com.riscure.bumper.index.Symbol
import com.riscure.bumper.index.TUID
import com.riscure.bumper.serialization.NothingSerializer
import kotlinx.serialization.UseSerializers

/**
 * @param <E> the type of expressions in the AST.
 * @param <S> the type of statements in the AST.
 */
data class TranslationUnit<out E, out T> (
    /** The translation unit identifier */
    val tuid : TUID,

    /**
     * All declarations in the unit.
     */
    val declarations: List<UnitDeclaration<E, T>>,
) {
    val isEmpty get() = declarations.isEmpty()

    val byIdentifier: Map<TLID, List<UnitDeclaration<E, T>>> by lazy {
        val mapping = mutableMapOf<TLID, MutableList<UnitDeclaration<E,T>>>()
        for (d  in declarations) {
            val ds = mapping.getOrDefault(d.tlid, mutableListOf())
            ds.add(d)
            mapping[d.tlid] = ds
        }

        mapping
    }

    private val nameToEnumerator: Map<Ident, Enumerator> by lazy {
        enums
            .flatMap { enum -> enum.enumerators
                .getOrElse { listOf() }
                .map { enumerator -> Pair(enumerator.ident, enumerator) } }
            .toMap()
    }

    /**
     * Resolves a global [ident] to a global declaration.
     */
    fun resolve(ident: Ident): Option<GlobalDeclaration> = nameToEnumerator[ident]
        .toOption()
        .orElse { declarations.find { it.ident == ident }.toOption() }

    val variables: List<UnitDeclaration.Var<E>>  get() = declarations.variables
    val functions: List<UnitDeclaration.Fun<T>>  get() = declarations.functions
    val structs: List<UnitDeclaration.Struct>    get() = declarations.structs
    val unions: List<UnitDeclaration.Union>      get() = declarations.unions
    val typedefs: List<UnitDeclaration.Typedef>  get() = declarations.typedefs
    val enums: List<UnitDeclaration.Enum>        get() = declarations.enums
    val typeDeclarations  : List<UnitDeclaration.TypeDeclaration> get() = declarations.typeDeclarations
    val valuelikeDeclarations: List<UnitDeclaration.Valuelike<E, T>> get() = declarations.valuelikeDeclarations
    val functionDefinitions: List<UnitDeclaration.Fun<T>> get() = declarations.functionDefinitions
    val varDefinitions: List<UnitDeclaration.Var<E>> get() = declarations.varDefinitions
    val valuelikeDefinitions: List<UnitDeclaration.Valuelike<E, T>>  get() = declarations.valuelikeDefinitions

    fun filter(predicate: (d: UnitDeclaration<E, T>) -> Boolean) =
        copy(declarations = declarations.filter(predicate))

    /** Map locations to top-level declarations */
    fun getAtLocation(loc: Location): Option<UnitDeclaration<E, T>> =
        declarations
            .find { decl -> decl.meta.location.exists { it.begin == loc }}
            .toOption()

    /** Map locations to top-level declarations */
    fun getEnclosing(range: SourceRange): Option<UnitDeclaration<E, T>> =
        declarations
            .find { decl -> decl.meta.location.exists { it.encloses(range) }}
            .toOption()

    /** Map locations to top-level declarations */
    fun getEnclosing(loc: Location): Option<UnitDeclaration<E, T>> =
        declarations
            .find { decl -> decl.meta.location.exists { it.encloses(loc) }}
            .toOption()

    /**
     * Find declarations in this translation unit with a given [TLID] [id],
     * in the order that they appear.
     */
    fun declarationsForTLID(id: TLID): List<UnitDeclaration<E, T>> = byIdentifier.getOrDefault(id, listOf())

    /**
     * Find declarations in this translation unit with a given [Symbol] [sym],
     * in the order that they appear.
     */
    fun declarationsForSymbol(sym: Symbol): List<UnitDeclaration<E, T>> =
        if (sym.unit == tuid) byIdentifier.getOrDefault(sym.tlid, listOf())
        else listOf()

    fun definitionFor(id: TLID): Option<UnitDeclaration<E, T>> {
        var best = none<UnitDeclaration<E,T>>()
        declarations
            .asSequence()
            .filter { it.tlid == id && it.isDefinition }
            .forEach { decl ->
                // disambiguation of 'best' definitions
                when (decl) {
                    is UnitDeclaration.Valuelike -> {
                        // at most one strong definition per valid unit,
                        // so this is OK
                        if (best.isEmpty() || decl.isStrong)
                            best = decl.some()
                    }
                    else -> best = decl.some()
                }
            }

        return best
    }

    /**
     * Within a translation unit, a global variable may have multiple definitions (e.g. int k; int k = 0;)
     * but has only 0 or 1 initialization.
     * @param globalVariable variable for which we search the initialization
     * @return option of initialization for a global variable within a translation unit
     */
    fun initializationFor(globalVariable: UnitDeclaration.Var<*>): Option<UnitDeclaration<E, T>> =
        byIdentifier[globalVariable.tlid]
            ?.filterIsInstance<UnitDeclaration.Var<E>>()
            ?.find { it.rhs.isDefined() }
            .toOption()

    /**
     * The type environment generated by this translation unit.
     */
    fun typeEnv(builtins: Builtins) = typeDeclarations.typeEnv(builtins)
}

typealias ErasedDeclaration     = UnitDeclaration<Unit, Unit>
typealias ErasedTranslationUnit = TranslationUnit<Unit, Unit>

/**
 * Forget the expressions/statements at the leaves.
 */
fun <E,T> TranslationUnit<E, T>.erase(): ErasedTranslationUnit =
    TranslationUnit(tuid, declarations.map { it.erase() })

/**
 * Translation unit is functorial
 */
fun <E1, E2, S1, S2> TranslationUnit<E1, S1>.map(
    onExp : (decl: E1) -> E2,
    onStmt: (stmt: S1) -> S2,
): TranslationUnit<E2, S2> =
    declarations
        .map { d ->
            when (d) {
                is UnitDeclaration.TypeDeclaration -> d
                is UnitDeclaration.Fun       -> d.mapBody(onStmt)
                is UnitDeclaration.Var       -> d.mapRhs(onExp)
            }
        }
        .let { decls -> TranslationUnit(tuid, decls) }

/**
 * Update all declarations with the given TLID.
 */
fun <E,T> TranslationUnit<E,T>.update(id: TLID, f: (decl: UnitDeclaration<E, T>) -> UnitDeclaration<E, T>) =
    copy(declarations = declarations.map { if (it.tlid == id) f(it) else it })

fun <E,T> TranslationUnit<E,T>.collect(transform: (d: UnitDeclaration<E, T>) -> Option<UnitDeclaration<E, T>>) =
    copy(declarations = declarations.flatMap { d -> transform(d).toList() })

// Some convenience extension methods: filters for lists of declarations

private typealias UnitDecls<E, T> = Collection<UnitDeclaration<E, T>>

val <E, T> UnitDecls<E, T>.variables: List<UnitDeclaration.Var<E>> get() =
    filterIsInstance<UnitDeclaration.Var<E>>()

val <E, T> UnitDecls<E, T>.functions: List<UnitDeclaration.Fun<T>> get() =
    filterIsInstance<UnitDeclaration.Fun<T>>()

val <E, T> UnitDecls<E, T>.typedefs: List<UnitDeclaration.Typedef> get() =
    filterIsInstance<UnitDeclaration.Typedef>()

val <E, T> UnitDecls<E, T>.enums: List<UnitDeclaration.Enum> get() =
    filterIsInstance<UnitDeclaration.Enum>()

val <E,T> UnitDecls<E, T>.structs: List<UnitDeclaration.Struct> get() =
    filterIsInstance<UnitDeclaration.Struct>()

val <E,T> UnitDecls<E, T>.unions: List<UnitDeclaration.Union> get() =
    filterIsInstance<UnitDeclaration.Union>()

val <T> Collection<UnitDeclaration.Fun<T>>.definitions: List<UnitDeclaration.Fun<T>> get() =
    filter { it.isDefinition }

val <T> Collection<UnitDeclaration.Fun<T>>.declarations: List<UnitDeclaration.Fun<T>> get() =
    filter { !it.isDefinition }

val <E, T> UnitDecls<E, T>.typeDeclarations: List<UnitDeclaration.TypeDeclaration>
    get() = filterIsInstance<UnitDeclaration.TypeDeclaration>()

val <E, T> UnitDecls<E, T>.valuelikeDeclarations: List<UnitDeclaration.Valuelike<E, T>>
    get() = filterIsInstance<UnitDeclaration.Valuelike<E,T>>()

val <E, T> UnitDecls<E, T>.functionDefinitions: List<UnitDeclaration.Fun<T>> get() =
    filterIsInstance<UnitDeclaration.Fun<T>>()
        .filter { it.isDefinition }

val <E, T> UnitDecls<E, T>.varDefinitions: List<UnitDeclaration.Var<E>> get() {
    // we collect the 'best' (aka strongest) definitions
    val varDefMap = LinkedHashMap<TLID, UnitDeclaration.Var<E>>()

    this
        .asSequence()
        .filterIsInstance<UnitDeclaration.Var<E>>()
        .filter { it.isDefinition }
        // disambiguate between multiple definitions
        .forEach { varDecl ->
            // only one strong definition per unit,
            // so this is OK
            if (varDefMap[varDecl.tlid] == null || varDecl.isStrong)
                varDefMap[varDecl.tlid] = varDecl
        }

    return varDefMap.values.toList();
}

val <E, T> UnitDecls<E, T>.valuelikeDefinitions: List<UnitDeclaration.Valuelike<E, T>> get() =
    functionDefinitions + varDefinitions