package tech.egrie.soundtrail.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The backup allowlists, checked as the security rule they are.
 *
 * Both `res/xml/backup_rules.xml` (Android 11 and lower) and `res/xml/data_extraction_rules.xml`
 * (12+, cloud backup and device transfer) are allowlists: an `<include>` is what makes everything
 * else ineligible. That is a strong property, and it only holds while nobody adds a path to it.
 *
 * So the rule this test enforces is the one a future edit would break: **a preference file that
 * can hold a credential must never appear in an include.** The files that hold credentials in this
 * app are `soundtrail_secrets` (Keystore-wrapped tokens and API keys), `soundtrail_cloud_sync`
 * (the sync session, cursor and the recovery key), and `soundtrail_settings` (server addresses and
 * client ids). None of them may be listed, and this test fails the build if one ever is.
 *
 * It also pins the finding that made the release variant unbuildable: an `<exclude>` in an
 * allowlist is a fatal Android lint error (`FullBackupContent`: "X is not in an included path"),
 * which fails `:app:lintVitalRelease`, which `assembleRelease` depends on. Exclusions here are
 * expressed by omission and documented in comments — never as rules.
 */
class BackupRulesTest {

    private val backupRules = resourceFile("backup_rules.xml")
    private val extractionRules = resourceFile("data_extraction_rules.xml")

    /**
     * Names a preference file must not carry if it is going to be backed up. These are the words
     * that say "a credential lives here", matched on the lower-cased file name.
     */
    private val credentialWords = listOf(
        "token", "secret", "password", "recovery", "credential", "session", "auth", "apikey", "key"
    )

    /** The library the listener built, and the only thing either file is allowed to include. */
    private val allowedIncludes = setOf(
        "soundtrail_pins.xml",
        "soundtrail_playlists.xml",
        "soundtrail_automations.xml"
    )

    @Test fun `both backup rule files exist to be checked`() {
        assertTrue("missing ${backupRules.path}", backupRules.isFile)
        assertTrue("missing ${extractionRules.path}", extractionRules.isFile)
    }

    @Test fun `no included path could hold a credential`() {
        (includesIn(backupRules) + includesIn(extractionRules)).forEach { path ->
            val name = path.substringAfterLast('/').lowercase()
            credentialWords.forEach { word ->
                assertFalse(
                    "$path is included in a backup allowlist but its name says \"$word\" — a " +
                        "credential-bearing file must be left out, not included",
                    name.contains(word)
                )
            }
        }
    }

    @Test fun `the credential stores are not included anywhere`() {
        // Named explicitly, in addition to the word check above, so that renaming a file cannot
        // quietly move a credential into the allowlist.
        val never = listOf(
            "soundtrail_secrets.xml",
            "soundtrail_cloud_sync.xml",
            "soundtrail_settings.xml",
            "soundtrail_mood.xml",
            "soundtrail_mood_consent.xml",
            "soundtrail_activity_recognition.xml",
            "soundtrail_backups.xml"
        )
        val included = includesIn(backupRules) + includesIn(extractionRules)
        never.forEach { name ->
            assertFalse("$name must never be backed up", included.any { it.endsWith(name) })
        }
    }

    @Test fun `the allowlist is exactly the pins, playlists and automations`() {
        assertEquals(allowedIncludes, includesIn(backupRules).toSet())
        // Both blocks of the 12+ file: cloud backup and device transfer.
        assertEquals(allowedIncludes, includesIn(extractionRules).toSet())
        assertEquals(
            "device-transfer must carry the same allowlist as cloud-backup",
            2, countBlocks(extractionRules, "device-transfer")
        )
        assertEquals(
            "each block includes the same three files",
            allowedIncludes.size * 2, includesIn(extractionRules).size
        )
    }

    @Test fun `neither file carries an exclude, which is a fatal lint error in an allowlist`() {
        listOf(backupRules, extractionRules).forEach { file ->
            // Comments are stripped first: both files legitimately *mention* `<exclude>` while
            // explaining why they no longer use one, and lint does not parse comment text as an
            // element either. What must not exist is the element.
            val elements = withoutComments(file.readText())
            assertFalse(
                "${file.name} contains an <exclude>. In an allowlist that is a fatal " +
                    "FullBackupContent lint error and it breaks assembleRelease; express the " +
                    "exclusion by leaving the path out and say so in a comment.",
                Regex("<exclude[\\s>]").containsMatchIn(elements)
            )
        }
    }

    @Test fun `both files state the rule for whoever adds a preference file next`() {
        // The comment is the control that works at edit time; the assertions above are the one
        // that works at build time. Losing the comment is how the next credential file gets added
        // by someone who never knew the rule existed.
        listOf(backupRules, extractionRules).forEach { file ->
            val text = file.readText()
            assertTrue(
                "${file.name} no longer documents the rule that a credential-bearing preference " +
                    "file must not be added to the allowlist",
                text.contains("must NOT be added") || text.contains("must be added to NEITHER")
            )
        }
    }

    // ----- reading the files -----

    /** The document with its XML comments removed, so only real elements are matched. */
    private fun withoutComments(text: String): String =
        Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL).replace(text, "")

    /** Every `<include domain="sharedpref" path="…">` element in the document, in order. */
    private fun includesIn(file: File): List<String> =
        Regex("<include\\s+domain=\"sharedpref\"\\s+path=\"([^\"]+)\"")
            .findAll(withoutComments(file.readText()))
            .map { it.groupValues[1] }
            .toList()

    /** How many times an element such as `device-transfer` is opened. */
    private fun countBlocks(file: File, name: String): Int =
        Regex("<$name>").findAll(withoutComments(file.readText())).count()

    /**
     * Locates a res/xml file without depending on where the test JVM was started.
     *
     * Gradle runs unit tests from the module directory, so `src/main/res/xml/…` resolves; the
     * upward walk keeps the test working from a repository-root working directory too, rather
     * than failing with a confusing "file not found" in someone else's setup.
     */
    private fun resourceFile(name: String): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(4) {
            listOf("src/main/res/xml/$name", "app/src/main/res/xml/$name").forEach { relative ->
                val candidate = File(dir, relative)
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile ?: return File("src/main/res/xml/$name")
        }
        return File("src/main/res/xml/$name")
    }
}
