package com.csqtt.client

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.csqtt.client.Store253Snapshots.load
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 B3: a field of the «Туннель» form is stored when it is left, and it is left too when «Профили», open over the
 * tab, deletes its profile or imports a link into it — with the field still in focus. Its write lands after that, and
 * must not bring the deleted profile back or put a server's address into a link's profile.
 */
class TunnelFormWritesTest {
    private val first = CsqttConstants.Profiles.MIN_INDEX

    @Test fun aProfileInUseTakesTheFieldsOfItsOwnKindWhateverWasReplacedSince() {
        val owner = load(Store253Snapshots.owner)                 // slot 0, the user's own server
        assertTrue(SettingsStore.formWriteStands(owner, 0, linkForm = false, epoch = 0))
        assertTrue("another profile was added meanwhile", SettingsStore.formWriteStands(owner, 0, linkForm = false, epoch = 7))
        assertFalse("a link's field is not this profile's", SettingsStore.formWriteStands(owner, 0, linkForm = true, epoch = 0))

        val participant = load(Store253Snapshots.participant)     // slot 3, a link
        assertTrue(SettingsStore.formWriteStands(participant, 3, linkForm = true, epoch = 0))
        assertFalse("a server's field is not this profile's", SettingsStore.formWriteStands(participant, 3, linkForm = false, epoch = 0))
    }

    @Test fun theOpenProfileThatHoldsNothingYetTakesItsFormsFirstField() {
        val prefs = mutablePreferencesOf()                        // a new install
        assertFalse(SettingsStore.profileInUse(prefs, first))
        assertTrue(SettingsStore.formWriteStands(prefs, first, linkForm = false, epoch = 0))
        assertFalse("a slot that is neither open nor in use is nobody's", SettingsStore.formWriteStands(prefs, first + 1, linkForm = false, epoch = 0))
    }

    @Test fun aDeletedProfilesFieldIsNotWrittenBack() {
        // with another profile left, the open one moves on and the slot is nobody's
        val two = load(Store253Snapshots.owner)
        val other = SettingsStore.addProfileKeys(two, linkMode = false)!!
        SettingsStore.openProfileKeys(two, 0)
        val drawnUnder = SettingsStore.epochOf(two)
        SettingsStore.deleteProfileKeys(two, 0)
        assertFalse(SettingsStore.formWriteStands(two, 0, linkForm = false, epoch = drawnUnder))
        assertTrue("the other profile's own form is as it was", SettingsStore.formWriteStands(two, other, linkForm = false, epoch = drawnUnder))

        // the only profile: its emptied slot stays the open one, and is not the form's any more
        val one = load(Store253Snapshots.owner)
        val before = SettingsStore.epochOf(one)
        SettingsStore.deleteProfileKeys(one, 0)
        assertFalse(SettingsStore.profileInUse(one, 0))
        assertFalse(SettingsStore.formWriteStands(one, 0, linkForm = false, epoch = before))
        assertTrue("the form drawn for the emptied slot is a new one", SettingsStore.formWriteStands(one, 0, linkForm = false, epoch = SettingsStore.epochOf(one)))
        assertNull(one[stringPreferencesKey("peer")])
    }

    @Test fun aLinkImportedIntoTheOpenProfileEndsItsServerForm() {
        val prefs = mutablePreferencesOf()                        // the server's form is on screen, an address half typed
        val epoch = SettingsStore.epochOf(prefs)
        assertEquals(first, SettingsStore.importLinkKeys(prefs, Store253Snapshots.participantLink, target = first))
        assertFalse(SettingsStore.formWriteStands(prefs, first, linkForm = false, epoch = epoch))
        assertTrue(SettingsStore.formWriteStands(prefs, first, linkForm = true, epoch = epoch))
    }
}
