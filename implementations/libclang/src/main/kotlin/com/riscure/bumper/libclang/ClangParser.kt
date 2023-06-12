package com.riscure.bumper.libclang

import arrow.core.*
import com.riscure.dobby.clang.*
import com.riscure.bumper.index.TUID
import com.riscure.bumper.parser.Diagnostic
import com.riscure.bumper.parser.ParseError
import com.riscure.bumper.parser.Parser
import com.riscure.bumper.parser.Severity
import com.riscure.dobby.clang.ClangParser
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.llvm.clang.*
import org.bytedeco.llvm.global.clang.*
import java.io.File
import java.nio.file.Path

private fun Int.asSeverity(): Option<Severity> =
    if (this == CXDiagnostic_Error || this == CXDiagnostic_Fatal) {
        Severity.ERROR.some()
    } else if (this == CXDiagnostic_Warning) {
        Severity.WARNING.some()
    } else if (this == CXDiagnostic_Note) {
        Severity.INFO.some()
    } else {
        none()
    }

private fun CXDiagnostic.asDiagnostic(workingDir: Path): Option<Diagnostic> {
    val msg = clang_getDiagnosticSpelling(this).string
    return clang_getDiagnosticLocation(this)
        .let { cxloc ->
            cxloc
                .asLocation()
                .flatMap { loc ->
                    clang_getDiagnosticSeverity(this)
                        .asSeverity()
                        .map { sev ->
                            Diagnostic(sev, loc, cxloc.asPresumedLocation(workingDir), msg)
                        }
                }
        }
}

/**
 * This implements the parser interface using the Bytedeco library to call
 * into libclang to parse a C file.
 *
 * This is *not* thread-safe, as far as we know because Bytedeco is not thread-safe.
 */
class ClangParser : Parser<CXCursor, CXCursor, ClangUnitState> {

    override fun parse(entry: CompilationDb.Entry): Either<ParseError, ClangUnitState> = with (entry) {
        val tuid = TUID.mk(entry)

        // escalate some warnings to errors
        val warnErrors   = if (badWarnings.isNotEmpty()) {
            listOf(
                ClangParser
                    .parseOption("-Werror=${badWarnings.joinToString(separator = ",")}")
                    .getOrHandle { throw RuntimeException("Invariant violation: bad clang options") })
        } else listOf()

        val cmd: Command = with(Spec.clang11) {
            Command(options + warnErrors, listOf())
        }
        val args = cmd.toArguments()

        // We allocate the arguments.
        val c_index: CXIndex = clang_createIndex(0, 0)
        val c_tu = CXTranslationUnit()
        val c_sourceFile = BytePointer(resolvedMainSource.toString())
        val c_arg_pointers = args.map { BytePointer(it) }
        val c_args = PointerPointer<BytePointer>(args.size.toLong())
        val c_parseOptions = CXTranslationUnit_SingleFileParse

        // put all the pointers into the array
        c_arg_pointers.forEachIndexed { i, it ->
            c_args.position(i.toLong())
            c_args.put(it)
        }
        c_args.position(0L)

        // Define the deallocator
        fun free() {
            clang_disposeIndex(c_index)
            c_sourceFile.close()
            c_arg_pointers.forEach { it.deallocate() }
            c_args.deallocate()
            // We don't free c_tu, because that will be part of the unit state.
        }

        // Parse the given file, storing the result in c_tu
        val code: Int =
            // This is the libclang version of clang -fsyntax-only, which performs preprocessing, parsing,
            // and type checking. It ignores -o and -emit-ast by default.
            // Despite the fact that `clang-11 -E` does not seem to imply `-O0` (it does some inlining),
            // this API call does imply it.
            clang_parseTranslationUnit2(c_index, c_sourceFile, c_args, args.size, null, 0, c_parseOptions, c_tu)

        try {
            // Interpret the result code generated by clang:
            val result = errorCodes[code]!!
            if (result != ClangError.Success) {
                return ParseError.FileFailed(tuid, cmd.optArgs, result.msg).left()
            }

            // now check the diagnostics for errors
            val c_diagnostics = clang_getDiagnosticSetFromTU(c_tu)
            val diagnostics = (0 until clang_getNumDiagnosticsInSet(c_diagnostics))
                .map { clang_getDiagnosticInSet(c_diagnostics, it) }
                .flatMap { it.asDiagnostic(entry.workingDirectory).toList() } // silently drop diagnostics that we could not parse

            return if (diagnostics.any { it.severity == Severity.ERROR }) {
                ParseError.ParseFailed(tuid, cmd.optArgs, diagnostics).left()
            } else {
                val rootCursor = clang_getTranslationUnitCursor(c_tu)
                with(CursorParser(tuid, workingDirectory)) {
                    rootCursor
                        .asTranslationUnit()
                        .map { (ast, cursors) -> ClangUnitState(ast, c_tu, cursors, workingDirectory) }
                }
                ClangUnitState
                    .create(tuid, c_tu, workingDirectory)
                    .mapLeft { msg -> ParseError.InternalError(tuid, msg) }
            }
        }
        finally {
            // regardless of success, we free the auxiliary data
            free()
        }
    }

    companion object {
        // Use this to escalate some warnings to errors.
        // For example, if you want to specify '-Werror=missing-declarations',
        // add "missing-declarations" to the list.
        val badWarnings = listOf(
            // LinkAnalyses does not handle implicit function declarations correctly.
            // So by escalating this warning, we avoid bugs downstream. See LinkAnalysisTest
            "implicit-function-declaration"
        )

        val errorCodes: Map<Int, ClangError> = ClangError.values().associateBy { it.code }

        enum class ClangError(val code: Int, val msg: String) {
            Success(CXError_Success, "No error."),
            Failure(CXError_Failure, "A generic error code, no further details are available."),
            Crashed(CXError_Crashed, "libclang crashed while performing the requested operation"),
            InvalidArguments(CXError_InvalidArguments, "The function detected that the arguments violate the function contract."),
            ASTReadError(CXError_ASTReadError, "An AST deserialization error has occurred.");
        }
    }
}
