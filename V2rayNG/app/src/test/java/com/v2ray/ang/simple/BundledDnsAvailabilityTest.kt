package com.v2ray.ang.simple

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.io.File

class BundledDnsAvailabilityTest {
    @Test fun freshAndClearedStorageAlwaysContainTheCanonicalDns() {
        val directory=kotlin.io.path.createTempDirectory("bundled-dns").toFile()
        try {
            val context=mock(Context::class.java)
            val assets=mock(AssetManager::class.java)
            val prefs=mock(SharedPreferences::class.java)
            val bootstrap=File("src/main/assets/panel-bootstrap.json").readBytes()
            `when`(context.noBackupFilesDir).thenReturn(directory)
            `when`(context.assets).thenReturn(assets)
            `when`(context.getSharedPreferences(anyString(),anyInt())).thenReturn(prefs)
            `when`(prefs.getString(anyString(),anyString())).thenAnswer { it.getArgument<String>(1) }
            `when`(prefs.getBoolean(anyString(),anyBoolean())).thenAnswer { it.getArgument<Boolean>(1) }
            `when`(assets.open("panel-bootstrap.json")).thenAnswer { bootstrap.inputStream() }
            val store=SimpleStore(context)
            val original=store.configurations.single()
            assertEquals(BundledDnsTunnel.ID,original.id)
            assertEquals("dns",original.mode)
            assertTrue(original.domain.isNotBlank())
            assertEquals(64,original.publicKey.length)
            val modified=original.copy(domain="changed.example",resolver="9.9.9.9:53")
            assertThrows(IllegalArgumentException::class.java) { store.save(modified) }
            assertThrows(IllegalArgumentException::class.java) { store.remove(original.id) }
            assertEquals(original,store.canonicalConfiguration(modified))
            store.configurations.single().resolver="9.9.9.9:53"
            assertEquals(original,store.configurations.single())
            assertEquals(original,store.backup(selected=original.id).profiles.single())
            directory.deleteRecursively()
            assertEquals(listOf(original),SimpleStore(context).configurations)
        } finally { directory.deleteRecursively() }
    }
}
