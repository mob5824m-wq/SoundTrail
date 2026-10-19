package tech.egrie.soundtrail.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** File names the managed backup store will accept, and the ones it must refuse. */
class ManagedBackupTest {
    @Test
    fun `accepts only the snapshots this store writes`() {
        assertTrue(ManagedBackupStore.isBackupName("soundtrail-managed-1700000000000.json"))
        assertTrue(ManagedBackupStore.isBackupName("soundtrail-managed-1700000000000-1.json"))
        assertFalse(ManagedBackupStore.isBackupName("soundtrail-managed-1700000000000.json.tmp"))
        assertFalse(ManagedBackupStore.isBackupName("soundtrail-managed-.json"))
    }

    @Test
    fun `refuses names that could leave private storage`() {
        assertFalse(ManagedBackupStore.isBackupName("../soundtrail-managed-1700000000000.json"))
        assertFalse(ManagedBackupStore.isBackupName("soundtrail-managed-../x.json"))
        assertFalse(ManagedBackupStore.isBackupName("soundtrail-managed-1700000000000.json/.."))
        assertFalse(ManagedBackupStore.isBackupName("/data/soundtrail-managed-1.json"))
    }
}
