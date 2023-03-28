package com.riscure.bumper.analyses

import arrow.core.*
import arrow.typeclasses.Monoid
import com.riscure.bumper.ast.*
import com.riscure.bumper.index.Symbol
import com.riscure.bumper.index.TUID

/**
 * A [DeclarationInUnit] is a pair of a translation unit identifier [unit]
 * and a [proto]type of a value (function or global) in that [unit].
 */
data class DeclarationInUnit(
    val tuid: TUID,
    val proto: Prototype
) {
    val name: String get() = proto.tlid.name
    val symbol: Symbol get() = Symbol(tuid, proto.tlid)
}

/**
 * A [Link] associates a [declaration] without a definition in one unit
 * with a [definition] of the same symbol in another unit.
 */
data class Link(
    val declaration: DeclarationInUnit,
    val definition : DeclarationInUnit,
)

/**
 * A [DependencyGraph] describes the dependencies between symbols.
 * The graph is represented as an edge-set: the [dependencies] map gives a set of
 * symbols that a given symbol depends on.
 *
 * A [DependencyGraph] can be used for graphs for a single or multiple translation units.
 */
data class DependencyGraph(val dependencies: Map<Symbol, Set<Symbol>>) {

    val nodes get() = dependencies.keys

    fun union(other: DependencyGraph) = with (graphMonoid) { combine(other) }

    /**
     * Performs a reachability analysis on the dependency graph.
     *
     * @param roots are the nodes from which we start the reachability analysis.
     * @return the subgraph of nodes that are reachable in this graph
     *         from the given set of [roots].
     */
    fun reachable(roots: Set<Symbol>): DependencyGraph {
        // we start with the given set of symbols to keep
        val worklist     = roots.toMutableList()
        val reachable    = mutableSetOf<Symbol>()

        // then we recursively add dependencies,
        // monotonically growing the set of reachable nodes in the dependency graph
        while (worklist.isNotEmpty()) {
            val focus = worklist.removeAt(0)

            if (focus in reachable) continue // already analyzed
            else reachable.add(focus)

            worklist.addAll(dependencies.getOrDefault(focus, listOf()))
        }

        return copy(dependencies = dependencies.filter { (k, _) -> reachable.contains(k) })
    }

    companion object {

        object graphMonoid: Monoid<DependencyGraph> {

            override fun DependencyGraph.combine(b: DependencyGraph): DependencyGraph {
                val result = mutableMapOf<Symbol, Set<Symbol>>()

                for (k in this.dependencies.keys + b.dependencies.keys) {
                    result[k] = this.dependencies.getOrDefault(k, setOf()) + b.dependencies.getOrDefault(k, setOf())
                }

                return DependencyGraph(result)
            }

            override fun empty() = DependencyGraph(mapOf())
        }

        /** Construct an empty dependency graph */
        @JvmStatic fun empty() = graphMonoid.empty()

        /** Combine a list of dependency graphs into one */
        @JvmStatic fun union(graphs: List<DependencyGraph>) = graphMonoid.fold(graphs)

    }
}

/**
 * The linkgraph represents inter-unit dependencies.
 * It consists of a set [dependencies] of [Link]s for every translation unit U identified by a [TUID].
 * Every [Link] associates a declaration in the unit U, with some declaration in some other known unit V.
 *
 * A linkgraph can be computed from a set of translation units using [LinkAnalysis].
 */
data class LinkGraph(private val dependencies: Map<TUID, Set<Link>>) {

    /**
     * For every symbol, a set of symbols in other translation unit that it depends on.
     */
    val externalDependencyGraph: DependencyGraph by lazy {
        val result = mutableMapOf<Symbol, MutableSet<Symbol>>()

        for ((_, links) in dependencies) {
            for ((declaration, definition) in links) {
                val from = declaration.symbol

                val deps = result.getOrDefault(from, mutableSetOf())
                result[from] = deps

                deps.add(definition.symbol)
            }
        }

        DependencyGraph(result)
    }

    operator fun get(tuid: TUID) = dependencies[tuid].toOption()

    /**
     * Get the *external direct dependencies* for the given [symbol]
     */
    operator fun get(symbol: Symbol): Set<Symbol> = externalDependencyGraph
        .dependencies
        .getOrDefault(symbol, setOf())
}

/**
 * [LinkAnalysis] implements a dependency analysis *between* translation units.
 *
 * For each unit, we compute an object interface with exports (defined symbols)
 * and imports (declared but not defined symbols).
 *
 * We then cross-reference the imports of each unit against the exports
 * from other units, obtaining a [LinkGraph].
 */
object LinkAnalysis {

    /**
     * Compute the [LinkGraph] for a set of translation [units],
     * by cross-referencing the imports with the combined exports of all the units.
     *
     * If a declaration cannot be resolved within the given [units],
     * we simply do not record a link for it in the graph.
     */
    @JvmStatic
    fun <E, S> linkGraph(units: Collection<TranslationUnit<E, S>>): LinkGraph = this(units).first

    /**
     * Compute the [LinkGraph] for a set of translation [units],
     * by cross-referencing the imports with the combined exports of all the units,
     * as well as the set of symbols that have no matching definition in the set of [units].
     *
     * This assumes that there are no duplicate definitions in the set of [units].
     * In other words: it is a well typed link target.
     *
     * @return a [Pair] of the [LinkGraph] and the set of prototypes for which no definition was found.
     */
    @JvmStatic
    operator fun <E, S> invoke(units: Collection<TranslationUnit<E, S>>): Pair<LinkGraph, Set<Prototype>> {
        // compute for each translation unit its interface
        val interfaces = units.associate { unit -> Pair(unit.tuid, objectInterface(unit)) }

        // Index the exports by TLID.
        // This assumes that we have at most one definition per tlid in the units.
        val exportIndex: Map<TLID, DeclarationInUnit> = interfaces
            .entries
            .flatMap { (tuid, intf) ->
                intf.exports
                    .map { Pair(it.tlid, DeclarationInUnit(tuid, it.prototype())) }
            }
            .toMap()

        // Now try to resolve all imports, obtaining links from declarations to definitions.
        val missing = mutableSetOf<Prototype>()
        val edges: Map<TUID, Set<Link>> = interfaces
            .mapValues { (tuid, intf) ->
                intf
                    .imports
                    .flatMap { import ->
                        // we try to resolve the import to a matching export from the index.
                        val link: Option<Link> = exportIndex[import.tlid]
                            .toOption()
                            .map { Link(DeclarationInUnit(tuid, import.prototype()), it) }

                        when (link) {
                            is Some -> listOf(link.value)
                            is None -> {
                                // If resolution then we don't have a definition
                                // within the set of translation units, and we don't incur a link dependency.
                                // We record the missing dependency
                                missing.add(import.prototype())
                                listOf()
                            }
                        }
                    }
                    .toSet()
            }

        return Pair(LinkGraph(edges), missing)
    }

    /**
     * A [UnitInterface] is set of imports (declarations without definitions in the unit)
     * together with a set of exports (defined and visible/non-static symbols in the unit).
     */
    data class UnitInterface<E, S>(
        val imports: Set<UnitDeclaration.Valuelike<E,S>>,
        val exports: Set<UnitDeclaration.Valuelike<E,S>>,
    )

    /**
     * Compute the [UnitInterface] of a translation [unit].
     */
    @JvmStatic
    fun <E, S> objectInterface(unit: TranslationUnit<E, S>): UnitInterface<E, S> {
        // compute the list of declarations that are visible
        // across linked units
        val visible = unit
            .valuelikeDeclarations
            .filter { decl -> decl.storage != Storage.Static }

        val exports = visible.filter { it.isDefinition }
        val exportIndex = exports.map { it.tlid } .toSet()
        val imports = visible
            .filter { decl -> !exportIndex.contains(decl.tlid) }

        return UnitInterface(imports.toSet(), exports.toSet())
    }
}