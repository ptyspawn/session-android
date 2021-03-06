package org.thoughtcrime.securesms.loki.activities

import android.os.Bundle
import android.support.v4.app.LoaderManager
import android.support.v4.content.Loader
import android.support.v7.app.AlertDialog
import android.support.v7.widget.LinearLayoutManager
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_linked_devices.*
import network.loki.messenger.R
import nl.komponents.kovenant.ui.failUi
import nl.komponents.kovenant.ui.successUi
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity
import org.thoughtcrime.securesms.crypto.UnidentifiedAccessUtil
import org.thoughtcrime.securesms.crypto.storage.TextSecureSessionStore
import org.thoughtcrime.securesms.database.DatabaseFactory
import org.thoughtcrime.securesms.devicelist.Device
import org.thoughtcrime.securesms.logging.Log
import org.thoughtcrime.securesms.loki.dialogs.*
import org.thoughtcrime.securesms.loki.protocol.SyncMessagesProtocol
import org.thoughtcrime.securesms.loki.utilities.recipient
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.loki.api.fileserver.LokiFileServerAPI
import java.util.*
import kotlin.concurrent.schedule

class LinkedDevicesActivity : PassphraseRequiredActionBarActivity, LoaderManager.LoaderCallbacks<List<Device>>, DeviceClickListener, EditDeviceNameDialogDelegate, LinkDeviceMasterModeDialogDelegate {
    private var devices = listOf<Device>()
        set(value) { field = value; linkedDevicesAdapter.devices = value }

    private val linkedDevicesAdapter by lazy {
        val result = LinkedDevicesAdapter(this)
        result.deviceClickListener = this
        result
    }

    // region Lifecycle
    constructor() : super()

    override fun onCreate(savedInstanceState: Bundle?, isReady: Boolean) {
        super.onCreate(savedInstanceState, isReady)
        setContentView(R.layout.activity_linked_devices)
        supportActionBar!!.title = "Devices"
        recyclerView.adapter = linkedDevicesAdapter
        recyclerView.layoutManager = LinearLayoutManager(this)
        linkDeviceButton.setOnClickListener { linkDevice() }
        LoaderManager.getInstance(this).initLoader(0, null, this)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_linked_devices, menu)
        return true
    }
    // endregion

    // region Updating
    override fun onCreateLoader(id: Int, bundle: Bundle?): Loader<List<Device>> {
        return LinkedDevicesLoader(this)
    }

    override fun onLoadFinished(loader: Loader<List<Device>>, devices: List<Device>?) {
        update(devices ?: listOf())
    }

    override fun onLoaderReset(loader: Loader<List<Device>>) {
        update(listOf())
    }

    private fun update(devices: List<Device>) {
        this.devices = devices
        emptyStateContainer.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun handleDeviceNameChanged(device: Device) {
        LoaderManager.getInstance(this).restartLoader(0, null, this)
    }
    // endregion

    // region Interaction
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        when(id) {
            R.id.linkDeviceButton -> linkDevice()
            else -> { /* Do nothing */ }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun linkDevice() {
        if (devices.isEmpty()) {
            val linkDeviceDialog = LinkDeviceMasterModeDialog()
            linkDeviceDialog.delegate = this
            linkDeviceDialog.show(supportFragmentManager, "Link Device Dialog")
        } else {
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Multi Device Limit Reached")
            builder.setMessage("It's currently not allowed to link more than one device.")
            builder.setPositiveButton("OK", { dialog, _ -> dialog.dismiss() })
            builder.create().show()
        }
    }

    override fun onDeviceClick(device: Device) {
        val bottomSheet = DeviceEditingOptionsBottomSheet()
        bottomSheet.onEditTapped = {
            bottomSheet.dismiss()
            val editDeviceNameDialog = EditDeviceNameDialog()
            editDeviceNameDialog.device = device
            editDeviceNameDialog.delegate = this
            editDeviceNameDialog.show(supportFragmentManager, "Edit Device Name Dialog")
        }
        bottomSheet.onUnlinkTapped = {
            bottomSheet.dismiss()
            unlinkDevice(device.id)
        }
        bottomSheet.show(supportFragmentManager, bottomSheet.tag)
    }

    private fun unlinkDevice(slaveDevicePublicKey: String) {
        val userPublicKey = TextSecurePreferences.getLocalNumber(this)
        val apiDB = DatabaseFactory.getLokiAPIDatabase(this)
        val deviceLinks = apiDB.getDeviceLinks(userPublicKey)
        val deviceLink = deviceLinks.find { it.masterHexEncodedPublicKey == userPublicKey && it.slaveHexEncodedPublicKey == slaveDevicePublicKey }
        if (deviceLink == null) {
            return Toast.makeText(this, "Couldn't unlink device.", Toast.LENGTH_LONG).show()
        }
        LokiFileServerAPI.shared.setDeviceLinks(setOf()).successUi {
            DatabaseFactory.getLokiAPIDatabase(this).clearDeviceLinks(userPublicKey)
            deviceLinks.forEach { deviceLink ->
                // We don't use PushEphemeralMessageJob because want these messages to send before the pre key and
                // session associated with the slave device have been deleted
                val unlinkingRequest = SignalServiceDataMessage.newBuilder()
                    .withTimestamp(System.currentTimeMillis())
                    .asUnlinkingRequest(true)
                val messageSender = ApplicationContext.getInstance(this@LinkedDevicesActivity).communicationModule.provideSignalMessageSender()
                val address = SignalServiceAddress(deviceLink.slaveHexEncodedPublicKey)
                try {
                    val udAccess = UnidentifiedAccessUtil.getAccessFor(this@LinkedDevicesActivity, recipient(this@LinkedDevicesActivity, deviceLink.slaveHexEncodedPublicKey))
                    messageSender.sendMessage(0, address, udAccess, unlinkingRequest.build()) // The message ID doesn't matter
                } catch (e: Exception) {
                    Log.d("Loki", "Failed to send unlinking request due to error: $e.")
                    throw e
                }
                DatabaseFactory.getLokiPreKeyBundleDatabase(this).removePreKeyBundle(deviceLink.slaveHexEncodedPublicKey)
                val sessionStore = TextSecureSessionStore(this@LinkedDevicesActivity)
                sessionStore.deleteAllSessions(deviceLink.slaveHexEncodedPublicKey)
            }
            LoaderManager.getInstance(this).restartLoader(0, null, this)
            Toast.makeText(this, "Your device was unlinked successfully", Toast.LENGTH_LONG).show()
        }.failUi {
            Toast.makeText(this, "Couldn't unlink device.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDeviceLinkRequestAuthorized() {
        SyncMessagesProtocol.syncAllClosedGroups(this)
        SyncMessagesProtocol.syncAllOpenGroups(this)
        Timer().schedule(4000) { // Not the best way to do this but the idea is to wait for the closed groups sync to go through first
            SyncMessagesProtocol.syncAllContacts(this@LinkedDevicesActivity)
        }
        LoaderManager.getInstance(this).restartLoader(0, null, this)
    }

    override fun onDeviceLinkAuthorizationFailed() {
        Toast.makeText(this, "Couldn't link device", Toast.LENGTH_LONG).show()
    }

    override fun onDeviceLinkCanceled() {
        // Do nothing
    }
    // endregion
}