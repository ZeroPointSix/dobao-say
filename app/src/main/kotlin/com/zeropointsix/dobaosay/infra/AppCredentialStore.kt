package com.zeropointsix.dobaosay.infra

import android.content.Context
import com.zeropointsix.dobaosay.doubao.DoubaoCredentialFile
import com.zeropointsix.dobaosay.doubao.DoubaoCredentials
import java.io.File

/**
 * Pragmatic Doubao credential persistence under app internal storage.
 *
 * Stores JSON via [DoubaoCredentialFile] at `filesDir/doubao-credentials.json`.
 * Not Keystore-backed; sufficient to avoid re-registering a device each session.
 */
class AppCredentialStore(
    context: Context,
) {
    private val path = File(context.applicationContext.filesDir, FILE_NAME).toPath()

    fun load(): DoubaoCredentials? = DoubaoCredentialFile.read(path)

    fun save(credentials: DoubaoCredentials) {
        DoubaoCredentialFile.write(path, credentials)
    }

    /** Deletes the on-disk credential file (Settings → Clear Credentials). */
    fun clear() {
        path.toFile().delete()
    }

    companion object {
        const val FILE_NAME = "doubao-credentials.json"
    }
}
