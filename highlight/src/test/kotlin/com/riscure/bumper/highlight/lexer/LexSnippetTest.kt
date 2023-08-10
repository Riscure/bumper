package com.riscure.bumper.highlight.lexer

import com.riscure.bumper.highlight.StrEncoding
import com.riscure.bumper.highlight.Token
import com.riscure.bumper.highlight.tokenize
import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LexSnippetTest {

    fun lex(input: String, noWhitespace: Boolean = false, handle: Iterator<Token>.() -> Unit) =
        handle(tokenize(input.reader(), noWhitespace))

    inline fun <reified T> Iterator<Token>.nextIs() = assertIs<T>(this.next())

    @Test
    @DisplayName("integer global declaration")
    fun test_00() = lex("""
        int i;
    """.trimIndent()) {
        nextIs<Token.Type>()
        nextIs<Token.Ws>()
        nextIs<Token.Identifier>()
        nextIs<Token.Punctuation>()
        nextIs<Token.EOF>()
    }

    @Test
    @DisplayName("const integer global declaration")
    fun test_01() = lex("""
        const int i;
    """.trimIndent()) {
        val key = nextIs<Token.Keyword>()
        assertEquals("const", key.lexeme)

        nextIs<Token.Ws>()
        nextIs<Token.Type>()
    }

    @Test
    @DisplayName("extern integer global declaration")
    fun test_02() = lex("""
        extern int i;
    """.trimIndent()) {
        val key = nextIs<Token.Keyword>()
        assertEquals("extern", key.lexeme)

        nextIs<Token.Ws>()
        nextIs<Token.Type>()
    }

    @Test
    @DisplayName("integer global declaration with instantiation")
    fun test_03() = lex("""
        = 42;
    """.trimIndent(), noWhitespace = true) {
        nextIs<Token.Punctuation>()
        nextIs<Token.Blob>() /* TODO more precision */
        nextIs<Token.Punctuation>()
    }

    @Test
    @DisplayName("dollar var")
    fun test_04() = lex("int \$i") {
        nextIs<Token.Type>()
        nextIs<Token.Ws>()
        val ident = nextIs<Token.Identifier>()
        assertEquals("\$i", ident.ident)
    }

    @Test
    @DisplayName("Basic char")
    fun test_05() = lex("'a'") {
        val lit = nextIs<Token.CharLiteral>()
        assertEquals(StrEncoding.None, lit.encoding)
    }

    @Test
    @DisplayName("u8 encoded char")
    fun test_06() = lex("u8'a'") {
        val lit = nextIs<Token.CharLiteral>()
        assertEquals(StrEncoding.UTF8, lit.encoding)
    }

    @Test
    @DisplayName("basic string")
    fun test_07() = lex(""""a"""") {
        val lit = nextIs<Token.StringLiteral>()
        assertEquals(StrEncoding.None, lit.encoding)
        assertEquals("""a""", lit.lexeme)
    }

    @Test
    @DisplayName("string containing double-quote")
    fun test_08() = lex(""""\""""") {
        val lit = nextIs<Token.StringLiteral>()
        assertEquals(StrEncoding.None, lit.encoding)
        assertEquals("""\"""", lit.lexeme)
    }

    @Test
    @DisplayName("consecutive strings")
    fun test_09() = lex(""""\""""".let { x -> x + x }) {
        val lit = nextIs<Token.StringLiteral>()
        assertEquals(StrEncoding.None, lit.encoding)
        assertEquals("""\"""", lit.lexeme)
        val lit2 = nextIs<Token.StringLiteral>()
        assertEquals(StrEncoding.None, lit2.encoding)
        assertEquals("""\"""", lit2.lexeme)
    }

    @Test
    @DisplayName("raw string")
    fun test10() = lex("""R"prefix(some string)prefix"""") {
        val lit = nextIs<Token.StringLiteral>()
        assertEquals(StrEncoding.RAW, lit.encoding)
    }
}