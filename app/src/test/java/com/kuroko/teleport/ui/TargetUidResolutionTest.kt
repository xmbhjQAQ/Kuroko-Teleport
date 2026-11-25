package com.kuroko.teleport.ui

import com.kuroko.teleport.ipc.KurokoProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetUidResolutionTest {

    @Test
    fun uninstalledAppIsRemovedAndRecycledUidIsNeverEmitted() {
        val recycledUid = 10250
        val saved = listOf(
            SavedTargetApp("com.example.uninstalled", recycledUid, KurokoProtocol.POLICY_ALL_STRICT),
            SavedTargetApp("com.example.keep", 10300, KurokoProtocol.POLICY_ALL_SPOOF)
        )
        // Simulate recycledUid now owned by a completely unrelated installed package,
        // while com.example.uninstalled is no longer installed.
        val installedByPkg = mapOf(
            "com.example.unrelated" to recycledUid,
            "com.example.keep" to 10300
        )

        val result = resolveSavedTargets(saved, selfUid = 10100) { pkg ->
            val uid = installedByPkg[pkg]
            if (uid != null) PackageUidLookup.Found(uid) else PackageUidLookup.NotInstalled
        }

        assertTrue(result.changed)
        assertEquals(listOf("com.example.uninstalled"), result.removedPackages)
        assertTrue(result.updatedUidPackages.isEmpty())
        assertEquals(
            listOf(SavedTargetApp("com.example.keep", 10300, KurokoProtocol.POLICY_ALL_SPOOF)),
            result.activeTargets
        )
        assertEquals(result.activeTargets, result.persistedTargets)
        assertTrue(result.activeTargets.none { it.uid == recycledUid })
        assertTrue(result.persistedTargets.none { it.uid == recycledUid })
    }

    @Test
    fun reinstalledAppUpdatesUidAndPreservesPolicyFlags() {
        val customPolicy = KurokoProtocol.POLICY_LOCATION or KurokoProtocol.POLICY_STEPS or KurokoProtocol.POLICY_HIDE_WIFI
        val saved = listOf(
            SavedTargetApp("com.example.runner", 10210, customPolicy)
        )

        val result = resolveSavedTargets(saved, selfUid = 10100) { pkg ->
            if (pkg == "com.example.runner") PackageUidLookup.Found(10412)
            else PackageUidLookup.NotInstalled
        }

        assertTrue(result.changed)
        assertTrue(result.removedPackages.isEmpty())
        assertEquals(listOf("com.example.runner"), result.updatedUidPackages)
        val expected = listOf(SavedTargetApp("com.example.runner", 10412, customPolicy))
        assertEquals(expected, result.activeTargets)
        assertEquals(expected, result.persistedTargets)
    }

    @Test
    fun selfUidAndNonPositiveUidAreRejected() {
        val selfUid = 10100
        val saved = listOf(
            SavedTargetApp("com.kuroko.teleport", selfUid, KurokoProtocol.POLICY_ALL_STRICT),
            SavedTargetApp("com.example.zero", 10200, KurokoProtocol.POLICY_ALL_STRICT),
            SavedTargetApp("com.example.negative", 10201, KurokoProtocol.POLICY_ALL_STRICT),
            SavedTargetApp("com.example.valid", 10305, KurokoProtocol.POLICY_ALL_STRICT)
        )

        val result = resolveSavedTargets(saved, selfUid = selfUid) { pkg ->
            when (pkg) {
                "com.kuroko.teleport" -> PackageUidLookup.Found(selfUid)
                "com.example.zero" -> PackageUidLookup.Found(0)
                "com.example.negative" -> PackageUidLookup.Found(-1)
                "com.example.valid" -> PackageUidLookup.Found(10305)
                else -> PackageUidLookup.NotInstalled
            }
        }

        assertTrue(result.changed)
        assertEquals(
            listOf("com.kuroko.teleport", "com.example.zero", "com.example.negative"),
            result.removedPackages
        )
        assertTrue(result.updatedUidPackages.isEmpty())
        val expected = listOf(SavedTargetApp("com.example.valid", 10305, KurokoProtocol.POLICY_ALL_STRICT))
        assertEquals(expected, result.activeTargets)
        assertEquals(expected, result.persistedTargets)
    }

    @Test
    fun duplicateAndBlankPackagesArePrunedAndDeduplicated() {
        val saved = listOf(
            SavedTargetApp("   ", 10200, KurokoProtocol.POLICY_ALL_STRICT),
            SavedTargetApp("com.example.dup", 10201, KurokoProtocol.POLICY_LOCATION),
            SavedTargetApp("com.example.dup", 10201, KurokoProtocol.POLICY_ALL_STRICT),
            SavedTargetApp(" com.example.trimmed ", 10205, KurokoProtocol.POLICY_ALL_SPOOF)
        )

        val result = resolveSavedTargets(saved, selfUid = 10100) { pkg ->
            when (pkg) {
                "com.example.dup" -> PackageUidLookup.Found(10201)
                "com.example.trimmed" -> PackageUidLookup.Found(10205)
                else -> PackageUidLookup.NotInstalled
            }
        }

        assertTrue(result.changed)
        assertTrue(result.removedPackages.isEmpty())
        assertTrue(result.updatedUidPackages.isEmpty())
        val expected = listOf(
            SavedTargetApp("com.example.dup", 10201, KurokoProtocol.POLICY_LOCATION),
            SavedTargetApp("com.example.trimmed", 10205, KurokoProtocol.POLICY_ALL_SPOOF)
        )
        assertEquals(expected, result.activeTargets)
        assertEquals(expected, result.persistedTargets)
    }

    @Test
    fun transientLookupErrorOmitsFromActiveButPreservesPersisted() {
        val okEntry = SavedTargetApp("com.example.ok", 10200, KurokoProtocol.POLICY_ALL_STRICT)
        val flakyEntry = SavedTargetApp("com.example.flaky", 10201, KurokoProtocol.POLICY_ALL_SPOOF)

        val result = resolveSavedTargets(listOf(okEntry, flakyEntry), selfUid = 10100) { pkg ->
            when (pkg) {
                "com.example.ok" -> PackageUidLookup.Found(10200)
                "com.example.flaky" -> PackageUidLookup.LookupError
                else -> PackageUidLookup.NotInstalled
            }
        }

        assertFalse(result.changed)
        assertTrue(result.removedPackages.isEmpty())
        assertTrue(result.updatedUidPackages.isEmpty())
        assertEquals(listOf(okEntry), result.activeTargets)
        assertEquals(listOf(okEntry, flakyEntry), result.persistedTargets)
    }

    @Test
    fun unchangedValidTargetsReportNotChanged() {
        val saved = listOf(
            SavedTargetApp("com.example.alpha", 10201, KurokoProtocol.POLICY_ALL_STRICT),
            SavedTargetApp("com.example.beta", 10202, KurokoProtocol.POLICY_ALL_SPOOF)
        )

        val result = resolveSavedTargets(saved, selfUid = 10100) { pkg ->
            when (pkg) {
                "com.example.alpha" -> PackageUidLookup.Found(10201)
                "com.example.beta" -> PackageUidLookup.Found(10202)
                else -> PackageUidLookup.NotInstalled
            }
        }

        assertFalse(result.changed)
        assertTrue(result.removedPackages.isEmpty())
        assertTrue(result.updatedUidPackages.isEmpty())
        assertEquals(saved, result.activeTargets)
        assertEquals(saved, result.persistedTargets)
    }

    @Test
    fun mixedOutcomesHandleEachCategoryIndependently() {
        val keep = SavedTargetApp("com.example.keep", 10200, KurokoProtocol.POLICY_ALL_STRICT)
        val reinstalled = SavedTargetApp("com.example.reinstalled", 10201, KurokoProtocol.POLICY_LOCATION)
        val uninstalled = SavedTargetApp("com.example.gone", 10202, KurokoProtocol.POLICY_ALL_SPOOF)
        val flaky = SavedTargetApp("com.example.flaky", 10203, KurokoProtocol.POLICY_ALL_STRICT)

        val result = resolveSavedTargets(
            listOf(keep, reinstalled, uninstalled, flaky),
            selfUid = 10100
        ) { pkg ->
            when (pkg) {
                "com.example.keep" -> PackageUidLookup.Found(10200)
                "com.example.reinstalled" -> PackageUidLookup.Found(10555)
                "com.example.gone" -> PackageUidLookup.NotInstalled
                "com.example.flaky" -> PackageUidLookup.LookupError
                else -> PackageUidLookup.NotInstalled
            }
        }

        assertTrue(result.changed)
        assertEquals(listOf("com.example.gone"), result.removedPackages)
        assertEquals(listOf("com.example.reinstalled"), result.updatedUidPackages)
        val updatedReinstalled = SavedTargetApp("com.example.reinstalled", 10555, KurokoProtocol.POLICY_LOCATION)
        assertEquals(listOf(keep, updatedReinstalled), result.activeTargets)
        assertEquals(listOf(keep, updatedReinstalled, flaky), result.persistedTargets)
    }
}
