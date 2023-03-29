package com.riscure.bumper.libclang

import arrow.core.*
import com.riscure.bumper.ast.*
import com.riscure.bumper.index.TUID
import com.riscure.bumper.parser.UnitData
import com.riscure.bumper.parser.UnitState
import com.riscure.bumper.pp.AstWriters
import com.riscure.bumper.pp.Extractor
import com.riscure.bumper.preprocessor.CPPInfo
import org.bytedeco.llvm.clang.*
import org.bytedeco.llvm.global.clang

/**
 * The Clang parser implementation now only analyzes upto expression/statements.
 * And then remembers the locations for those.
 */
typealias ClangTranslationUnit = TranslationUnit<CXCursor, CXCursor>
typealias ClangDeclaration     = UnitDeclaration<CXCursor, CXCursor>

/**
 * The state of a parsed unit in context of the libclang state of the AST.
 *
 * As soon as you close this UnitState, the cursors are invalidated.
 * When you make a copy of the instance you have, you lose ownership of the
 * former copy.
 */
data class ClangUnitState(
    override val ast: TranslationUnit<CXCursor, CXCursor>,
    private val cxunit: CXTranslationUnit,
    private val elaboratedCursors: Map<CursorHash, ClangDeclaration>,
    override val cppinfo: CPPInfo = CPPInfo(),
) : UnitState<CXCursor, CXCursor, ClangUnitState> {
    override fun close() = cxunit.close()
    override fun withCppinfo(cppinfo: CPPInfo): ClangUnitState = copy(cppinfo = cppinfo)
    override fun withTUID(tuid: TUID): ClangUnitState = copy(ast = ast.copy(tuid = tuid))

    override fun erase() = Either
        .catch({ e -> close(); e.message!! }) {
            fun rangeExtractor(c: CXCursor) = c.getRange().getOrElse {
                throw Throwable("Failed to extract source ranges of definitions.")
            }

            ast
                .map(::rangeExtractor, ::rangeExtractor)
                .let { ast ->
                    this@ClangUnitState.close()
                    UnitData(ast, dependencies)
                }
        }

    override val dependencies get() =
        ClangDependencyAnalysis(ast, elaboratedCursors).ofUnit(ast)

    companion object {
        @JvmStatic
        fun create(tuid: TUID, cxunit: CXTranslationUnit): Either<String, ClangUnitState> {
            val rootCursor = clang.clang_getTranslationUnitCursor(cxunit)
            return with(CursorParser(tuid)) {
                rootCursor
                    .asTranslationUnit()
                    .map { (ast, cursors) -> ClangUnitState(ast, cxunit, cursors) }
            }
        }

        /**
         * Utility function to create the ast pretty printers for a [ClangTranslationUnit],
         * assuming that the source file that was used to parse the unit is still available for reading.
         */
        @JvmStatic
        fun pp(tuid: TUID): AstWriters<CXCursor, CXCursor> {
            // We could use libclang's pretty printing facilities here,
            // except that I've encountered corner cases where pretty printing returns "" incorrectly:
            // - https://github.com/llvm/llvm-project/issues/59155
            // So we fall back here on extracting lines from the source file instead.
            val extractor = Extractor(tuid.main.toFile())

            fun cursorPrinter(c: CXCursor) =
                c.getRange()
                    .toEither { "Failed to get source range for expression." }
                    .flatMap { extractor.extract(it) }

            return AstWriters(::cursorPrinter, ::cursorPrinter)
        }
    }
}
