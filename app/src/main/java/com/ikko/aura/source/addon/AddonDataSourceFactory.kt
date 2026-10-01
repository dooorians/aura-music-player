package com.ikko.aura.source.addon

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

class AddonDelegatingDataSource(
    private val addonClient: AddonClient,
    private val ytmDataSource: DataSource
) : DataSource {

    private var addonSource: AddonDataSource? = null
    private var activeSource: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        ytmDataSource.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        activeSource = if (dataSpec.uri.scheme == "addon") {
            if (addonSource == null) addonSource = AddonDataSource(addonClient)
            addonSource
        } else {
            ytmDataSource
        }
        return activeSource!!.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return activeSource?.read(buffer, offset, length) ?: androidx.media3.common.C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = activeSource?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = activeSource?.responseHeaders ?: emptyMap()

    override fun close() {
        activeSource?.close()
        activeSource = null
    }
}
