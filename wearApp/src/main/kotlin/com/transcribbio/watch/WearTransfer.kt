package com.transcribbio.watch

import android.content.Context
import com.google.android.gms.wearable.Wearable
import com.transcribbio.shared.sync.SyncProtocol
import kotlinx.coroutines.tasks.await
import java.io.File

/** Streams a recording file to the paired phone over a Data Layer channel. */
object WearTransfer {
    suspend fun send(context: Context, nodeId: String, file: File): Boolean {
        val channelClient = Wearable.getChannelClient(context)
        val channel = channelClient.openChannel(nodeId, SyncProtocol.WEAR_RECORDING_PATH).await()
        try {
            val out = channelClient.getOutputStream(channel).await()
            out.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            }
            return true
        } finally {
            runCatching { channelClient.close(channel).await() }
        }
    }
}
