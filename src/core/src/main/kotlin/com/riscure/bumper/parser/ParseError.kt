package com.riscure.bumper.parser

import com.riscure.bumper.index.TUID
import com.riscure.dobby.clang.CompilationDb
import com.riscure.dobby.clang.ICompilationDb
import com.riscure.dobby.clang.Options
import java.io.File
import java.nio.file.Path

sealed class ParseError: Exception() {
    data class MissingCompileCommand(val path: Path, val cdb: ICompilationDb): ParseError() {
        override val message: String get() = "No compile command for file '$path'"
    }

    /**
     * Errors on the file-level, without a source location
     */
    data class FileFailed(
        val tuid: TUID,
        val options: Options,
        override val message: String
    ): ParseError()

    /**
     * Errors during parsing, may contain multiple diagnostics,
     * each with a source location.
     */
    data class ParseFailed(
        val tuid: TUID,
        val options: Options,
        val diagnostics: List<Diagnostic>
    ): ParseError() {
        override val message: String
            get() =
                diagnostics
                    .sortedBySeverity
                    .joinToString("\n") { it.format() }
                    .let { "Failed to parse ${tuid.main}:\n${it}" }
    }

    /**
     * Internal errors indicative of a bug. User can't directly do much about these.
     */
    data class InternalError(
        val tuid: TUID,
        override val message: String,
        override val cause: Throwable? = null // JDK choice
    ): ParseError()

    data class PreprocFailed(val input: Path, val reason: List<String>): ParseError() {
        constructor(input: Path, reason: String): this(input, listOf(reason))

        override val message: String
            get() = "Failed to preprocess ${input}, saying:\n${reason.joinToString { it }}"

    }

    data class AnalysisFailed(val reason: String): ParseError() {
        override val message: String
            get() = "Analysis failed: $reason"
    }
}