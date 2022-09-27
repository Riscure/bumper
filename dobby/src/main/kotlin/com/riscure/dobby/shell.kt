package com.riscure.dobby

import arrow.core.*
import com.riscure.lang.shell.ShellLexer
import com.riscure.lang.shell.ShellParser.*
import com.riscure.lang.shell.ShellParser
import org.antlr.v4.runtime.*

typealias Result<T> = Either<Throwable,T>

/**
 * A symbol is an abstraction of a char in the context of possible
 * quotation. Escaped characters are represented as a single symbol.
 */
data class Symbol(val value: String) {
    companion object {
        val escapedDouble = Symbol("\\\"")
        val escapedSingle = Symbol("\\'")
        val escapedEscape = Symbol("\\\\")
        val escapedSpace  = Symbol("\\ ")
    }
    fun eval(): String = when (this) {
        escapedDouble -> "\""
        escapedSingle -> "'"
        escapedEscape -> "\\"
        escapedSpace  -> " "
        else          -> value
    }
}

data class Line(val args: List<Arg>) {
    fun eval(): List<String> = args.map { it.eval() }
}
data class Arg(val parts: List<Val>) {
    fun eval(): String = parts.joinToString(separator="") { it.eval() }
}

sealed class Val {
    abstract val content: List<Symbol>
    fun eval(): String = content.joinToString(separator="") { it.eval() }

    data class DoubleQuoted(override val content: List<Symbol>) : Val()
    data class SingleQuoted(override val content: List<Symbol>) : Val()

    data class Unquoted(override val content: List<Symbol>) : Val()
}

/**
 * Utilities to parse and transform a subset of shell encountered
 * in compilation databases.
 */
object Shell {

    private fun mkParser(stream: CharStream) =
        ShellParser(CommonTokenStream(ShellLexer(stream)))
            .apply {
                // make sure errors are escalated instead of printed.
                errorHandler = object : DefaultErrorStrategy() {
                    override fun reportError(recognizer: Parser?, e: RecognitionException?) {
                        throw e!!;
                    }
                }
            }

    /**
     * Parse the whole input as a commandline line.
     */
    fun line(line: String): Result<Line> {
        val s = CharStreams.fromString(line)
        return Either
            .catch { mkParser(s).line() }
            .map { it.ast() }
    }

    /**
     * Parse a single argument from the string, and return the result
     * and the unparsed remainder.
     */
    fun arg(line: String): Result<Pair<Arg, String>> {
        val s = CharStreams.fromString(line)
        return Either
            .catch { mkParser(s).arg() }
            .map { it.ast() }
            .map { Pair(it, s.unwind().trim()) }
    }

    private fun ShellParser.LineContext.ast(): Line =
        Line(this.shellargs().ast())

    private fun ShellParser.ShellargsContext.ast(): List<Arg> = when (this) {
        is ConsContext   -> tail
            .ast()
            .let { args ->
                head.ast().let { head -> args.toMutableList().apply { add(0, head) } }
            }
        is SingleContext -> listOf(single.ast())
        is NilContext    -> listOf<Arg>()
        else -> throw RuntimeException("This should never happen. Please report a bug.")
    }

    private fun ArgContext.ast(): Arg =
        Arg(`val`().map { it.ast() })

    private fun ValContext.ast(): Val = when (this) {
        is SingleQuotedContext -> Val.SingleQuoted(CHAR().map { Symbol(it.text) })
        is DoubleQuotedContext -> Val.DoubleQuoted(CHAR().map { Symbol(it.text) })
        is UnquotedContext     -> Val.Unquoted(CHAR().map { Symbol(it.text) })
        else -> throw RuntimeException("This should never happen. Please report a bug.")
    }

    /**
     * Utility to unwind the remainder of a charstream onto a string.
     */
    private fun CharStream.unwind(): String {
        val s = java.lang.StringBuilder()
        while (true) {
            val c = LA(1)
            if (c != CharStream.EOF) {
                s.appendCodePoint(c)
                consume()
            } else break
        }
        return s.toString()
    }
}