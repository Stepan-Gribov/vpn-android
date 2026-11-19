package com.v2ray.ang.simple

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** Cross-process locking + fsynced temp file + atomic rename. Uncommitted temp is ignored. */
internal class AtomicStateFile(val file: File) {
    companion object { private val mutex = Any() }
    fun <T> locked(block: () -> T): T = synchronized(mutex) {
        file.parentFile!!.mkdirs()
        RandomAccessFile(File(file.parentFile,"${file.name}.lock"),"rw").channel.use { channel ->
            channel.lock().use { block() }
        }
    }
    fun replace(bytes: ByteArray, beforeCommit: () -> Unit = {}) {
        val tmp=File(file.parentFile,"${file.name}.tmp")
        try {
            FileOutputStream(tmp).use { out-> out.write(bytes); out.fd.sync() }
            beforeCommit()
            check(tmp.renameTo(file)) { "Не удалось сохранить настройки. Прежние данные сохранены." }
        } finally { tmp.delete() }
    }
}
