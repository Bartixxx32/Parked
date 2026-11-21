package com.bartixxx.parked.family

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FamilyStoreRobolectricTest {

    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: FamilyStore

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("family-store").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(directory, "family.preferences_pb")
        }
        store = FamilyStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
        directory.deleteRecursively()
    }

    @Test
    fun `defaults use the public server and no session`() = runTest {
        val credentials = store.current()

        assertFalse(credentials.enabled)
        assertEquals(DEFAULT_FAMILY_SERVER, credentials.serverUrl)
        assertNull(credentials.groupId)
        assertNull(credentials.deviceToken)
        assertEquals("", credentials.carLabel)
        assertEquals("", credentials.groupName)
        assertEquals(FamilyCryptoMode.PLAINTEXT, credentials.cryptoMode)
        assertEquals(FamilyCrypto.KEY_VERSION, credentials.keyVersion)
        assertFalse(credentials.joined)
    }

    @Test
    fun `saving a session clears the previous group name and optional fields`() = runTest {
        store.saveSession(
            serverUrl = "https://family.example",
            groupId = "group-1",
            deviceId = "device-1",
            deviceToken = "token-1",
            adminToken = "admin-1",
            inviteCode = "invite-1",
            carLabel = "Golf",
            cryptoMode = FamilyCryptoMode.E2EE,
            keyVersion = 2,
            wrappedGroupKey = "wrapped-key",
            inviteSecret = "invite-secret",
        )
        store.setGroupName("Rodzina")
        store.setShareNote(true)
        assertEquals("Rodzina", store.current().groupName)

        store.saveSession(
            serverUrl = "https://family.example",
            groupId = "group-2",
            deviceId = "device-2",
            deviceToken = "token-2",
            adminToken = null,
            inviteCode = null,
            carLabel = "Polo",
            cryptoMode = FamilyCryptoMode.PLAINTEXT,
            keyVersion = 1,
            wrappedGroupKey = null,
            inviteSecret = null,
        )

        val credentials = store.current()
        assertTrue(credentials.enabled)
        assertTrue(credentials.joined)
        assertEquals("group-2", credentials.groupId)
        assertEquals("device-2", credentials.deviceId)
        assertEquals("token-2", credentials.deviceToken)
        assertEquals("Polo", credentials.carLabel)
        assertEquals("", credentials.groupName)
        assertEquals(FamilyCryptoMode.PLAINTEXT, credentials.cryptoMode)
        assertEquals(1, credentials.keyVersion)
        assertNull(credentials.adminToken)
        assertNull(credentials.inviteCode)
        assertNull(credentials.wrappedGroupKey)
        assertNull(credentials.inviteSecret)
        // Sharing preferences are not tied to a single session.
        assertTrue(credentials.shareNote)
    }

    @Test
    fun `clear keeps the self-hosted server and the car label`() = runTest {
        store.saveSession(
            serverUrl = "https://self-hosted.example",
            groupId = "group-1",
            deviceId = "device-1",
            deviceToken = "token-1",
            adminToken = "admin-1",
            inviteCode = "invite-1",
            carLabel = "Golf",
            cryptoMode = FamilyCryptoMode.E2EE,
            keyVersion = 1,
            wrappedGroupKey = "wrapped-key",
            inviteSecret = "invite-secret",
        )
        store.setGroupName("Home")

        store.clear()

        val credentials = store.current()
        assertFalse(credentials.enabled)
        assertNull(credentials.groupId)
        assertNull(credentials.deviceId)
        assertNull(credentials.deviceToken)
        assertNull(credentials.adminToken)
        assertNull(credentials.inviteCode)
        assertNull(credentials.wrappedGroupKey)
        assertNull(credentials.inviteSecret)
        assertEquals("", credentials.groupName)
        assertEquals("https://self-hosted.example", credentials.serverUrl)
        assertEquals("Golf", credentials.carLabel)
    }

    @Test
    fun `group and car names survive a new store instance`() = runTest {
        store.saveSession(
            serverUrl = DEFAULT_FAMILY_SERVER,
            groupId = "group-1",
            deviceId = "device-1",
            deviceToken = "token-1",
            adminToken = null,
            inviteCode = "invite-1",
            carLabel = "Golf",
            cryptoMode = FamilyCryptoMode.PLAINTEXT,
            keyVersion = 1,
            wrappedGroupKey = null,
            inviteSecret = null,
        )
        store.setGroupName("Rodzina Kowalskich 🚗")
        store.setCarLabel("WA 12345")

        val reopened = FamilyStore(dataStore)
        val credentials = reopened.current()

        assertEquals("Rodzina Kowalskich 🚗", credentials.groupName)
        assertEquals("WA 12345", credentials.carLabel)
    }

    @Test
    fun `context constructor reads the real family store`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val realStore = FamilyStore(context)
        realStore.saveSession(
            serverUrl = DEFAULT_FAMILY_SERVER,
            groupId = "group-1",
            deviceId = "device-1",
            deviceToken = "token-1",
            adminToken = null,
            inviteCode = "invite-1",
            carLabel = "Context car",
            cryptoMode = FamilyCryptoMode.PLAINTEXT,
            keyVersion = 1,
            wrappedGroupKey = null,
            inviteSecret = null,
        )

        assertEquals("Context car", FamilyStore(context).current().carLabel)
    }
}
