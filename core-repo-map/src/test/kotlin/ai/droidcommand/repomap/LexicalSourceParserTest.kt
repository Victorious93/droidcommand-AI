package ai.droidcommand.repomap

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RM-1 .. RM-4: what the parser reports as definitions and references. */
class LexicalSourceParserTest {
    private val parser = LexicalSourceParser()

    private fun parse(file: String, source: String) = parser.parse(Path.of(file), source)

    private fun ParsedFile.defs() = definitions.map { "${it.kind} ${it.name}" }

    // ------------------------------------------------------------------ RM-1

    @Test
    fun `RM-1 supports the four language families and nothing else`() {
        for (name in listOf("A.kt", "b.kts", "C.java", "d.py", "e.js", "f.ts", "g.tsx", "h.jsx", "i.mjs")) {
            assertTrue(parser.supports(Path.of(name)), name)
        }
        for (name in listOf("README.md", "Makefile", "x.png", "y.rs", "noextension")) assertFalse(parser.supports(Path.of(name)), name)
    }

    @Test
    fun `RM-1 Kotlin declarations`() {
        val parsed = parse(
            "Sample.kt",
            """
            package a
            class Alpha(val x: Int) {
                val prop = 1
                fun method() { val local = 2; fun inner() {} }
                init { val initLocal = 3 }
                companion object { const val LIMIT = 5 }
            }
            interface Beta
            fun interface Gamma { fun run() }
            object Delta
            typealias Eps = Int
            fun <T> List<T>.ext(): Int = size
            val topLevel = Alpha::class
            enum class Color { RED }
            val lambda = { val insideLambda = 1 }
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "class Alpha", "val prop", "fun method", "val LIMIT", "interface Beta", "interface Gamma", "fun run",
                "object Delta", "typealias Eps", "fun ext", "val topLevel", "class Color", "val lambda",
            ),
            parsed.defs(),
        )
    }

    @Test
    fun `RM-1 Kotlin definitions carry 1-based line numbers`() {
        val parsed = parse("L.kt", "\n\nclass Third\n\nfun sixth() {}\n")
        assertEquals(listOf(3, 5), parsed.definitions.map { it.line })
    }

    @Test
    fun `RM-1 Kotlin backtick names and accessors`() {
        val parsed = parse(
            "B.kt",
            """
            class Box {
                var size: Int = 0
                    get() { val tmp = field; return tmp }
                    set(value) { field = value }
                fun `weird name`() {}
            }
            """.trimIndent(),
        )
        assertEquals(listOf("class Box", "var size", "fun weird name"), parsed.defs())
    }

    @Test
    fun `RM-1 Java declarations`() {
        val parsed = parse(
            "Sample.java",
            """
            package a;
            public class Alpha extends Base {
                private int field;
                public Alpha(int x) { this.field = x; }
                public static <T> List<T> build(T t) throws Exception { int local = compute(t); return null; }
                abstract void abs();
                @Override public String toString() { return "class Fake"; }
                static { init(); }
                interface Inner { void call(); }
                enum Mode { A, B }
                Runnable r = new Runnable() { public void run() {} };
            }
            record Point(int x, int y) {}
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "class Alpha", "method Alpha", "method build", "method abs", "method toString", "interface Inner",
                "method call", "enum Mode", "record Point",
            ),
            parsed.defs(),
        )
    }

    @Test
    fun `RM-1 Python declarations`() {
        val parsed = parse(
            "sample.py",
            """
            import os
            class Alpha(Base):
                x = 1
                def method(self):
                    def inner(): pass
                    return "def nope():"
                async def amethod(self): ...
            def top(a,
                    b):
                # def ghost():
                pass
            @decorator
            def decorated(): pass
            """.trimIndent(),
        )
        assertEquals(listOf("class Alpha", "method method", "method amethod", "def top", "def decorated"), parsed.defs())
        assertTrue("decorator" in parsed.references)
    }

    @Test
    fun `RM-1 JavaScript and TypeScript declarations`() {
        val parsed = parse(
            "sample.ts",
            """
            import { x } from './x';
            export class Alpha extends Base { method(a) { const local = 1; } static make() {} }
            export function top(a) { function inner() {} }
            export const arrow = (a) => a;
            const plain = 5;
            interface Shape { area(): number; }
            type Id = string;
            enum Color { Red }
            let fn = async function () {};
            const typed = (a: number): number => a;
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "class Alpha", "method method", "method make", "function top", "function arrow", "const plain",
                "interface Shape", "method area", "type Id", "enum Color", "function fn", "function typed",
            ),
            parsed.defs(),
        )
    }

    @Test
    fun `RM-1 references count uses and exclude a declaration's own name`() {
        val parsed = parse("R.kt", "fun helper() {}\nfun caller() { helper(); helper(); other() }\n")
        assertEquals(2, parsed.references["helper"])
        assertEquals(1, parsed.references["other"])
        assertFalse("caller" in parsed.references)
    }

    @Test
    fun `RM-1 keywords are not references`() {
        val parsed = parse("K.kt", "fun f(x: Int): Int { if (x > 0) return 1 else return 2 }\n")
        assertFalse("if" in parsed.references)
        assertFalse("return" in parsed.references)
        assertTrue("Int" in parsed.references)
    }

    @Test
    fun `RM-1 an unsupported file yields an empty result`() {
        val parsed = parse("notes.txt", "class Foo")
        assertTrue(parsed.definitions.isEmpty())
        assertTrue(parsed.references.isEmpty())
    }

    // ------------------------------------------------------------------ RM-2

    @Test
    fun `RM-2 code-looking text in comments and strings is neither defined nor referenced`() {
        val parsed = parse(
            "Trap.kt",
            """
            // class GhostLine
            /* fun ghostBlock() {} */
            val s = "class FakeInString { fun nope() {} }"
            val c = '"'
            fun real() = Used
            """.trimIndent(),
        )
        assertEquals(listOf("val s", "val c", "fun real"), parsed.defs())
        for (ghost in listOf("GhostLine", "ghostBlock", "FakeInString", "nope")) assertFalse(ghost in parsed.references, ghost)
        assertTrue("Used" in parsed.references)
    }

    @Test
    fun `RM-2 a stray quote only damages its own line`() {
        val parsed = parse(
            "Stray.js",
            "const re = /\"/;\nfunction afterStray() {}\nclass AlsoFine {}\n",
        )
        assertTrue("function afterStray" in parsed.defs(), parsed.defs().toString())
        assertTrue("class AlsoFine" in parsed.defs(), parsed.defs().toString())
    }

    // ------------------------------------------------------------------ RM-3

    @Test
    fun `RM-3 Kotlin nested block comments and raw strings`() {
        val parsed = parse(
            "Nest.kt",
            "/* outer /* inner */ still comment fun Hidden() {} */\nfun visible() {}\nval raw = \"\"\"\n class Fake { fun nope() {} }\n\"\"\"\nfun after() {}\n",
        )
        assertEquals(listOf("fun visible", "val raw", "fun after"), parsed.defs())
        assertEquals(listOf(2, 3, 6), parsed.definitions.map { it.line }, "line numbers stay correct after a multi-line comment and raw string")
    }

    @Test
    fun `RM-3 line numbers survive multi-line comments`() {
        assertEquals(listOf(5), parse("M.kt", "/*\n one\n two\n*/\nfun after() {}\n").definitions.map { it.line })
        assertEquals(listOf(4), parse("M.java", "// one\n/** two\n * three */\nclass After {}\n").definitions.map { it.line })
    }

    @Test
    fun `RM-3 Kotlin string templates are code references`() {
        val parsed = parse("T.kt", "fun f() = \"hello \$name and \${user.id} and \${call(arg)}\" + \"\"\"raw \$rawRef\"\"\"\n")
        for (used in listOf("name", "user", "id", "call", "arg", "rawRef")) assertTrue(used in parsed.references, used)
    }

    @Test
    fun `RM-3 Java text blocks`() {
        val parsed = parse(
            "T.java",
            "class A {\n  String s = \"\"\"\n      class Fake { void nope() {} }\n      \"\"\";\n  void real() {}\n}\n",
        )
        assertEquals(listOf("class A", "method real"), parsed.defs())
    }

    @Test
    fun `RM-3 Python triple-quoted strings and prefixes`() {
        val parsed = parse(
            "t.py",
            "def a():\n    \"\"\"def ghost(): pass\"\"\"\n    x = r'class Fake' + f\"def nope() {y}\"\n    '''\n    def alsoGhost():\n    '''\n\ndef b(): pass\n",
        )
        assertEquals(listOf("def a", "def b"), parsed.defs())
        assertFalse("r" in parsed.references)
        assertFalse("f" in parsed.references)
    }

    @Test
    fun `RM-3 JavaScript template literals can span lines`() {
        val parsed = parse("t.js", "const t = `class Fake {\n function nope() {}\n}`;\nfunction real() {}\n")
        assertEquals(listOf("const t", "function real"), parsed.defs())
    }

    @Test
    fun `RM-3 an unterminated block comment or string at end of file does not hang or throw`() {
        parse("U1.kt", "fun a() {}\n/* never closed")
        parse("U2.kt", "val s = \"never closed")
        parse("U3.py", "x = '''never closed")
        parse("U4.js", "const t = `never closed")
    }

    // ------------------------------------------------------------------ RM-4

    @Test
    fun `RM-4 function-local declarations are not reported`() {
        val kotlin = parse("L.kt", "fun outer() {\n  val a = 1\n  var b = 2\n  fun local() {}\n  class LocalClass\n}\n")
        assertEquals(listOf("fun outer"), kotlin.defs())

        val python = parse("l.py", "def outer():\n    def local(): pass\n    class LocalClass: pass\n")
        assertEquals(listOf("def outer"), python.defs())

        val js = parse("l.js", "function outer() { const a = 1; function local() {} }\n")
        assertEquals(listOf("function outer"), js.defs())
    }
}
