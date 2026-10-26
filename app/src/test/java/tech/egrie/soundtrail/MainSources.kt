package tech.egrie.soundtrail

import java.io.File

/**
 * Locates main-source files, for the tests that assert on the source itself.
 *
 * Some guarantees cannot be expressed as a unit test against a running object: "nothing logs a
 * credential" and "the secret store has no plaintext fallback" are properties of code that has to
 * stay a certain way, and the only way to fail a build over them is to read it. These tests are
 * deliberately lint-style checks, and this helper is what lets them find the tree without caring
 * whether Gradle started them from the module directory or the repository root.
 */
internal object MainSources {

    /** The `src/main/java` directory of the app module. */
    fun root(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(4) {
            listOf("src/main/java", "app/src/main/java").forEach { relative ->
                val candidate = File(dir, relative)
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile ?: return File("src/main/java")
        }
        return File("src/main/java")
    }

    /** Every Kotlin file in the app's main sources. */
    fun kotlinFiles(): List<File> = root().walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    /** One main-source file by its path under the package root. */
    fun file(relative: String): File = File(root(), "tech/egrie/soundtrail/$relative")

    fun read(relative: String): String = file(relative).readText()

    /**
     * The body of one function, braces balanced, or null when the declaration is not found.
     *
     * Reading a whole file and asking whether a string appears somewhere in it proves very little:
     * `secrets.remove(TOKEN_KEY)` could be in any function. Scoping to the body is what makes an
     * assertion about `disconnect()` an assertion about `disconnect()`.
     */
    fun bodyOf(source: String, declaration: String): String? {
        val start = source.indexOf(declaration)
        if (start < 0) return null
        val open = source.indexOf('{', start)
        if (open < 0) return null
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
                else -> Unit
            }
        }
        return null
    }
}
