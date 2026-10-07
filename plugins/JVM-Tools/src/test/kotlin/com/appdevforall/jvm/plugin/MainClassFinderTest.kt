package com.appdevforall.jvm.plugin

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MainClassFinderTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `java main is named after the class that declares it, with its package`() {
        val source = """
            package com.example.app;

            class Helper {}

            public final class Launcher {
                public static void main(String[] args) {}
            }
        """.trimIndent()

        assertEquals(listOf("com.example.app.Launcher"), MainClassFinder.mainClassNames("Launcher.java", source))
    }

    @Test
    fun `java main accepts varargs, c-style arrays and modifiers in any order`() {
        assertEquals(listOf("A"), MainClassFinder.mainClassNames("A.java", "class A { static public void main(String... a) {} }"))
        assertEquals(listOf("B"), MainClassFinder.mainClassNames("B.java", "class B { public static void main(String a[]) {} }"))
    }

    @Test
    fun `java main after a nested type belongs to the class that encloses it`() {
        val source = """
            public class Main {
                record Point(int x, int y) {}
                static final String BRACE = "}";
                static final char OPEN = '{';
                public static void main(String[] args) {}
            }
        """.trimIndent()

        assertEquals(listOf("Main"), MainClassFinder.mainClassNames("Main.java", source))
    }

    @Test
    fun `java main in a nested type runs under its binary name`() {
        val source = """
            package com.example;

            public class Outer {
                static class Inner {
                    public static void main(String[] args) {}
                }
                interface Tool {
                    static void main(String[] args) {}
                }
                void run() {
                    class Local {
                        public static void main(String[] args) {}
                    }
                }
            }
        """.trimIndent()

        assertEquals(
            listOf("com.example.Outer\$Inner", "com.example.Outer\$Tool"),
            MainClassFinder.mainClassNames("Outer.java", source),
        )
    }

    @Test
    fun `java without a runnable main is not listed`() {
        val source = """
            class A {
                // public static void main(String[] args) {}
                /* public static void main(String[] args) {} */
                public void main(String[] args) {}
                static void main(int x) {}
            }
        """.trimIndent()

        assertEquals(emptyList<String>(), MainClassFinder.mainClassNames("A.java", source))
    }

    @Test
    fun `kotlin top-level main runs as the file facade`() {
        assertEquals(listOf("tools.MainKt"), MainClassFinder.mainClassNames("main.kt", "package tools\n\nfun main() {}\n"))
        assertEquals(listOf("My_appKt"), MainClassFinder.mainClassNames("my-app.kt", "suspend fun main(args: Array<String>) {}\n"))
        assertEquals(listOf("Launcher"), MainClassFinder.mainClassNames("App.kt", "@file:JvmName(\"Launcher\")\nfun main() {}\n"))
    }

    @Test
    fun `kotlin jvmstatic main runs in its object or in the class of its companion`() {
        val source = """
            object Tool {
                @JvmStatic fun main(args: Array<String>) {}
            }

            class App {
                companion object {
                    @JvmStatic
                    fun main(args: Array<String>) {}
                }
            }
        """.trimIndent()

        assertEquals(listOf("Tool", "App"), MainClassFinder.mainClassNames("Both.kt", source))
    }

    @Test
    fun `kotlin jvmstatic main in a nested object or companion runs under its binary name`() {
        val source = """
            package tools

            class Outer {
                val type = Outer::class
                object Tool {
                    @JvmStatic fun main(args: Array<String>) {}
                }
                class App(val name: String) {
                    companion object Factory {
                        @JvmStatic fun main(args: Array<String>) {}
                    }
                }
            }
        """.trimIndent()

        assertEquals(listOf("tools.Outer\$Tool", "tools.Outer\$App"), MainClassFinder.mainClassNames("Outer.kt", source))
    }

    @Test
    fun `kotlin main inside a class without JvmStatic is not listed`() {
        assertEquals(emptyList<String>(), MainClassFinder.mainClassNames("A.kt", "class A {\n    fun main() {}\n}\n"))
    }

    @Test
    fun `find walks the project, skipping build and hidden folders`() {
        folder.newFolder("src", "tools")
        folder.newFile("src/Main.java").writeText("public class Main { public static void main(String[] a) {} }")
        folder.newFile("src/tools/Echo.kt").writeText("package tools\nfun main() {}\n")
        folder.newFolder("build")
        folder.newFile("build/Old.java").writeText("class Old { public static void main(String[] a) {} }")
        folder.newFolder(".cg")
        folder.newFile(".cg/Hidden.kt").writeText("fun main() {}\n")

        assertEquals(listOf("Main", "tools.EchoKt"), MainClassFinder.find(folder.root).map { it.name })
    }
}
