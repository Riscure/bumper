package com.riscure.bumper

import arrow.core.*
import com.riscure.bumper.ast.*
import com.riscure.bumper.ast.Storage
import com.riscure.bumper.parser.UnitState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.*

interface FunctionParseTest<E,S,U: UnitState<E, S, U>>: ParseTestBase<E,S,U> {

    @Test
    @DisplayName("Empty main")
    fun test00() = roundtrip("""
        void main() {}
    """.trimIndent()) { ast ->
        assertEquals(1, ast.declarations.size)

        assertEquals(1, ast.declarations.size)
        val function = assertIs<UnitDeclaration.Fun<*>>(ast.declarations[0])

        assertEquals("main", function.ident)
        assertEquals(Storage.Default, function.storage)
        assertEquals(EntityKind.Fun, function.kind)
        assertTrue(function.isDefinition)
    }

    @Test
    @DisplayName("Function param local struct definition reference")
    fun test01() = roundtrip("""
        void f(struct A { int i; } a1, struct A a2);
    """.trimIndent()){ ast ->
        assertEquals(2, ast.declarations.size)

        val f = assertNotNull(ast.functions[0])

        assertEquals(2, f.params.size)
        val a1 = assertNotNull(f.params[0])
        val a2 = assertNotNull(f.params[1])

        val t1 = assertIs<Type.Struct>(a1.type)
        val t2 = assertIs<Type.Struct>(a2.type)

        val struct = assertNotNull(ast.structs.find { it.ident == "A" })
        assertEquals(struct.tlid, t1.ref)
        assertEquals(struct.tlid, t2.ref)
    }

    @Test
    @DisplayName("Forward declaration in param to function body")
    fun test02() = invalid("""
        void f(struct A a) { // incomplete type for param
          a = { .member = 42; };
          
          struct A {
            int member;
          };
        }
    """.trimIndent())

    @Test
    @DisplayName("Forward declaration in function body")
    fun test03() = invalid("""
        void f() {
          struct A a = { .member = 42; };
          struct A {
            int member;
          };
        }
    """.trimIndent())

    @Test
    @DisplayName("Vararg function")
    fun test04() = roundtrip("""
        void f(int x, ...) {
          return;
        }
    """.trimIndent()) { ast ->
        val f = ast.functions.find { it.ident == "f" }.assertOK()
        assertEquals(1, f.params.size)
        assertTrue(f.vararg)
    }

    @Test
    @DisplayName("Function declaration in function body")
    fun test05() = bumped("""
        void f() {
          void f2(void);
          f2();
        }
    """.trimIndent()) { ast, unit ->
        assertEquals(2, ast.functions.size)
        assertEquals( 0, ast.variables.size)
        assertNotNull(ast.functions.find { it.ident == "f2" })
        val f = assertNotNull(ast.functions.find { it.ident == "f" })
        val deps = assertNotNull(unit.dependencies.assertOK().dependencies[f.mkSymbol(ast.tuid)])
        assertEquals(1, deps.size)
        assertNotNull(deps.find { it.name == "f2" })
    }

    @Test
    @DisplayName("Extern global variable declaration in function body")
    fun test06() = bumped("""
        int f() {
          extern int g_var;
          return g_var;
        }
    """.trimIndent()) { ast, unit ->
        assertEquals(1, ast.functions.size)
        assertEquals( 1, ast.variables.size)
        assertNotNull(ast.variables.find { it.ident == "g_var" })
        val f = assertNotNull(ast.functions.find { it.ident == "f" })
        val deps = assertNotNull(unit.dependencies.assertOK().dependencies[f.mkSymbol(ast.tuid)])
        assertEquals(1, deps.size)
        assertNotNull(deps.find { it.name == "g_var" })
    }

    @Test
    @DisplayName("Static global variable declaration in function body")
    fun test07() = bumped("""
        int f() {
          static int g_var = 0;
          return g_var;
        }
    """.trimIndent()) { ast, unit ->
        assertEquals(1, ast.functions.size)
        assertEquals( 1, ast.variables.size)
        assertNotNull(ast.variables.find { it.ident == "g_var" })
        val f = assertNotNull(ast.functions.find { it.ident == "f" })
        val deps = assertNotNull(unit.dependencies.assertOK().dependencies[f.mkSymbol(ast.tuid)])
        assertEquals(1, deps.size)
        assertNotNull(deps.find { it.name == "g_var" })
    }

    @Test
    @DisplayName("Registered global variable declaration in function body")
    fun test08() = bumped("""
        int f() {
          register int g_var = 0;
          return g_var;
        }
    """.trimIndent()) { ast, unit ->
        assertEquals(1, ast.functions.size)
        assertEquals( 0, ast.variables.size)
    }

    @Test
    @DisplayName("Local variable declaration in function body")
    fun test09() = bumped("""
        int f() {
          int var;
          return var;
        }
    """.trimIndent()) { ast, unit ->
        assertEquals(1, ast.functions.size)
        assertEquals( 0, ast.variables.size)
    }
}