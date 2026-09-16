package com.transcribbio.phone.sync

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.transcribbio.phone.AppGraph
import com.transcribbio.phone.data.PendingRecording
import com.transcribbio.shared.sync.SyncProtocol
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Receives lecture recordings streamed from the Wear OS watch, drops them into
 *  the phone's upload queue, and kicks off a sync to the desktop. */
class WatchReceiverService : WearableListenerService() {

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != SyncProtocol.WEAR_RECORDING_PATH) return
        val channelClient = Wearable.getChannelClient(this)
        try {
            val input = Tasks.await(channelClient.getInputStream(channel))
            val (id, dest) = AppGraph.store.newRecording()
            dest.outputStream().use { out -> input.copyTo(out) }
            runCatching { input.close() }

            if (dest.length() > 0) {
                val title = "Watch lecture " +
                    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date())
                AppGraph.store.add(
                    PendingRecording(
                        id = id,
                        title = title,
                        language = AppGraph.prefs.language.value,
                        fileName = dest.name,
                        createdAtMillis = System.currentTimeMillis(),
                        durationMs = 0,
                    )
                )
                AppGraph.sync.requestSync(applicationContext)
            } else {
                dest.delete()
            }
        } catch (_: Exception) {
            // The watch will retry from its own queue on the next connection.
        } finally {
            runCatching { Tasks.await(channelClient.close(channel)) }
        }
    }
}
