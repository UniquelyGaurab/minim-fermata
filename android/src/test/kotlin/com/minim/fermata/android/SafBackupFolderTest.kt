package com.minim.fermata.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SafBackupFolderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun createWriteReadRenameDelete() {
        val dir = tmp.newFolder()
        TestDocumentsProvider.register(dir)
        val folder = SafBackupFolder(context.contentResolver, TestDocumentsProvider.tree)
        assertEquals(emptyList(), folder.list())

        val tmpFile = folder.create("minim-p-1-ab.tmp")
        folder.openOutput(tmpFile).use { it.write("hello".toByteArray()) }
        assertEquals("hello", folder.openInput(tmpFile).use { String(it.readBytes()) })
        assertEquals(listOf("minim-p-1-ab.tmp"), folder.list().map { it.name })
        assertEquals(5L, folder.list().single().size)

        val latest = folder.rename(tmpFile, "minim-p-latest.minim")
        assertEquals("minim-p-latest.minim", latest.name)
        assertEquals("hello", folder.openInput(latest).use { String(it.readBytes()) })
        assertEquals(listOf("minim-p-latest.minim"), dir.list()!!.toList())

        folder.delete(latest)
        assertEquals(emptyList(), folder.list())
    }

    @Test
    fun neverSettlesForAnotherName() {
        val dir = tmp.newFolder()
        TestDocumentsProvider.register(dir)
        val folder = SafBackupFolder(context.contentResolver, TestDocumentsProvider.tree)
        val a = folder.create("a.minim")
        // The provider would make "a (1).minim"; the folder refuses and cleans up.
        assertFailsWith<IOException> { folder.create("a.minim") }
        val b = folder.create("b.tmp")
        assertFailsWith<IOException> { folder.rename(b, "a.minim") }
        assertEquals(listOf("a.minim", "b.tmp"), dir.list()!!.sorted())
        assertEquals(a.name, folder.list().first().name)
    }
}
