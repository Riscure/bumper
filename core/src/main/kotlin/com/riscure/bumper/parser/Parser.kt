package com.riscure.bumper.parser

import arrow.core.*
import com.riscure.bumper.ast.Location
import com.riscure.dobby.clang.Options
import com.riscure.bumper.index.TUID
import com.riscure.dobby.clang.CompilationDb
import java.io.File


enum class Severity {
    // in order of high severity to low.
    // This defines the natural order used by [this.compareTo] of the type's values,
    // so the order is relevant.
    ERROR,
    WARNING,
    INFO;

    override fun toString() = when (this) {
        INFO    -> "info"
        WARNING -> "warning"
        ERROR   -> "error"
    }

    val symbol get() = when (this) {
        INFO    -> "-"
        WARNING -> "?"
        ERROR   -> "!"
    }
}
data class Diagnostic(
    val severity: Severity,
    val loc: Location,
    val presumedLoc: Option<Location>,
    val cause: String
) {
    val headline get() = cause.lines().firstOrNone().getOrElse { "" }

    val details get()  =
        cause.lines().some()
            .filter { it.isEmpty() }
            .map { it.drop(1).joinToString { "\n" }}

    fun format(): String {
        val presumed = presumedLoc
            .map { "\n              originally at $it" } // indent is trimmed
            .getOrElse { "" }
        return """
            ${severity.symbol} $severity
              at $loc$presumed
              because: $headline
        """.trimIndent() + (details.map { "\n\n$${it.prependIndent("  ")}" } .getOrElse { "" })
    }
}

val List<Diagnostic>.sortedBySeverity get() =
    this.sortedBy { it.severity }

fun interface Parser<Exp, Stmt, S : UnitState<Exp, Stmt, S>> {

    fun parse(cdb: CompilationDb, tuid: TUID): Either<ParseError, S> =
        cdb[tuid.main].toEither { ParseError.MissingCompileCommand(tuid.main, cdb) }
            .flatMap { parse(it) }

    /**
     * Given a file, preprocess and parse it. As a side-effect, this may perform
     * static analysis of the file, so this can fail on illtyped C programs.
     *
     * Some options are removed, namely any option conflicting with -E,
     * and any option specifying output.
     */
    fun parse(entry: CompilationDb.Entry, tuid: TUID) : Either<ParseError, S>

    fun parse(entry: CompilationDb.Entry) = parse(entry, TUID(entry.resolvedMainSource))
}
