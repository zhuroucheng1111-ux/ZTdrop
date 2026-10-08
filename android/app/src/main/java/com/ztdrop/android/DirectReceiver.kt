package com.ztdrop.android

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.BitSet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Direct SAF writer. Private storage contains only a small completed-segment journal. */
internal class DirectReceiver(private val context: Context, private val port: Int) {
    private val resolver get() = context.contentResolver
    private val block = 256 * 1024
    private val segmentSize = 4L * 1024 * 1024
    private data class Segment(val id: Int, val file: Int, val offset: Long, val length: Long)
    private data class Sink(val stream: ParcelFileDescriptor.AutoCloseOutputStream, val uri: Uri) : Closeable {
        val channel: FileChannel get() = stream.channel
        override fun close() = stream.close()
    }
    private class Connection(val socket: Socket, val control: ReceiveControl) : Closeable {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 256 * 1024))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 256 * 1024))
        override fun close() { try { socket.close() } finally { control.detach(socket) } }
    }
    private fun journal(token: String) = File(context.filesDir, "receive-journals/$token.json")
    private fun readJournal(token: String): JSONObject? = journal(token).takeIf { it.isFile }?.let { JSONObject(it.readText()) }
    private fun writeJournal(token: String, value: JSONObject) {
        val target = journal(token); target.parentFile?.mkdirs()
        val temporary = File(target.path + ".tmp")
        FileOutputStream(temporary).use { out -> out.write(value.toString().toByteArray(Charsets.UTF_8)); out.fd.sync() }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    private fun signature(manifest: JSONObject): String = MessageDigest.getInstance("SHA-256")
        .digest((manifest.getBoolean("is_folder").toString() + manifest.getJSONArray("files") +
            (manifest.optJSONArray("directories") ?: JSONArray())).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun open(address: String, token: String, control: ReceiveControl): Pair<Connection, JSONObject> {
        val socket = Socket()
        control.attach(socket)
        try {
            socket.receiveBufferSize = block * 4
            socket.connect(InetSocketAddress(address, port), 5000); socket.soTimeout = 30_000
            val connection = Connection(socket, control)
            connection.output.writeBytes("ZTB4"); connection.output.writeBytes(token); connection.output.flush()
            require(connection.input.readUnsignedByte() == 1) { "分享已失效或发送端拒绝连接" }
            val length = connection.input.readInt(); require(length in 1..32 * 1024 * 1024) { "清单大小无效" }
            val bytes = ByteArray(length); connection.input.readFully(bytes)
            return connection to JSONObject(String(bytes, Charsets.UTF_8))
        } catch (error: Exception) { socket.close(); control.detach(socket); throw error }
    }
    private fun safePath(value: String): String {
        require(value.isNotBlank() && value.split('/').all { part ->
            part.isNotBlank() && part !in setOf(".", "..") && !part.endsWith('.') && !part.endsWith(' ') &&
                part.toByteArray(Charsets.UTF_8).size <= 255 && part.none { it in "\\:*?\"<>|\u0000" }
        }) { "清单路径无效" }
        return value
    }
    private fun documentName(uri: Uri): String = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else error("下载临时文件不可访问")
    } ?: error("下载临时文件不可访问")
    private fun documentSize(uri: Uri): Long = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1
    } ?: -1

    /** Cleanup is restricted to a journal-owned incomplete root; committed files have no journal. */
    fun discard(token: String) {
        val state = readJournal(token) ?: return
        if (state.optBoolean("committed")) {
            journal(token).delete(); File(journal(token).path + ".tmp").delete(); return
        }
        if (state.optBoolean("owned_root")) {
            val root = Uri.parse(state.getString("root"))
            // A completed rename may survive a crash before the journal deletion. Never delete that final file.
            if (!state.optBoolean("committed") && documentName(root) == state.getString("temporary_name")) {
                require(DocumentsContract.deleteDocument(resolver, root)) { "删除下载临时文件失败" }
            }
        } else {
            resolver.openFileDescriptor(Uri.parse(state.getString("root")), "rw")?.use { descriptor ->
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { out -> out.channel.truncate(0) }
            } ?: error("清理临时下载内容失败")
        }
        require(journal(token).delete()) { "清理续传记录失败" }
        File(journal(token).path + ".tmp").delete()
    }

    // false means the provider cannot supply a seekable direct target: caller uses SAF staging/export.
    fun receive(address: String, token: String, name: String, expectedSize: Long, folder: Boolean,
                destination: Uri, destinationIsTree: Boolean, threads: Int, control: ReceiveControl,
                onSaved: (Uri) -> Unit = {}, report: (Long, Long, String) -> Unit): Boolean {
        val (initial, manifest) = open(address, token, control)
        initial.use { connection ->
            require(manifest.getBoolean("is_folder") == folder) { "分享类型不一致" }
            val entries = manifest.getJSONArray("files")
            require(entries.length() <= 100_000) { "文件数量超限" }
            if (!folder) require(entries.length() == 1 && entries.getJSONObject(0).getLong("size") == expectedSize) { "文件大小变化" }
            var total = 0L
            val paths = HashSet<String>()
            val segments = ArrayList<Segment>()
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i); val size = entry.getLong("size")
                require(size >= 0) { "文件大小无效" }; total = Math.addExact(total, size)
                if (folder) require(paths.add(safePath(entry.getString("relative")))) { "重复文件路径" }
                var offset = 0L
                while (offset < size) {
                    require(segments.size < 1_000_000) { "下载分段数量超限" }
                    val length = minOf(segmentSize, size - offset)
                    segments.add(Segment(segments.size, i, offset, length)); offset += length
                }
            }
            val directories = manifest.optJSONArray("directories") ?: JSONArray()
            require(directories.length() <= 100_000) { "目录数量超限" }
            for (i in 0 until directories.length()) safePath(directories.getString(i))
            val identity = signature(manifest)
            var state = readJournal(token)
            if (state != null && (state.optBoolean("committed") || state.optBoolean("owned_root") &&
                    documentName(Uri.parse(state.getString("root"))) != state.getString("temporary_name"))) {
                journal(token).delete(); state = null
            }
            if (state != null) {
                require(state.getString("destination") == destination.toString()) { "续传请使用原下载目录，或先停止并清理" }
                if (state.getString("identity") != identity || state.getJSONArray("files").length() != entries.length()) {
                    discard(token); state = null
                }
            }
            val existing = state != null
            val sinks = ArrayList<Sink>()
            try {
                if (state == null) {
                    // Avoid opening thousands of descriptors at once; large folders use bounded legacy export.
                    if (entries.length() > 64) { connection.output.writeInt(0); connection.output.flush(); return false }
                    // Preserve an existing explicitly-selected document until legacy export succeeds.
                    if (!destinationIsTree && (folder || documentSize(destination) != 0L)) {
                        connection.output.writeInt(0); connection.output.flush(); return false
                    }
                    val root = if (destinationIsTree) {
                        val parent = DocumentsContract.buildDocumentUriUsingTree(destination, DocumentsContract.getTreeDocumentId(destination))
                        DocumentsContract.createDocument(resolver, parent,
                            if (folder) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream",
                            ".ztdrop-${token.take(12)}.part") ?: error("下载目录不可写入")
                    } else destination
                    state = JSONObject().put("root", root.toString()).put("owned_root", destinationIsTree)
                        .put("destination", destination.toString()).put("identity", identity).put("temporary_name", documentName(root)).put("files", JSONArray())
                        .put("completed", JSONArray())
                    // Journal before creating child documents so every failure retains an owned cleanup root.
                    writeJournal(token, state)
                    val parents = HashMap<String, Uri>(); parents[""] = root
                    fun directory(path: String): Uri {
                        parents[path]?.let { return it }; control.checkActive()
                        val parent = directory(path.substringBeforeLast('/', ""))
                        val uri = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR,
                            path.substringAfterLast('/')) ?: error("创建下载目录失败")
                        parents[path] = uri; return uri
                    }
                    if (folder) for (i in 0 until directories.length()) directory(directories.getString(i))
                    for (i in 0 until entries.length()) {
                        control.checkActive()
                        val relative = entries.getJSONObject(i).getString("relative")
                        val uri = if (folder) DocumentsContract.createDocument(resolver,
                            directory(relative.substringBeforeLast('/', "")), "application/octet-stream", relative.substringAfterLast('/'))
                            ?: error("创建下载文件失败") else root
                        state.getJSONArray("files").put(uri.toString())
                    }
                    writeJournal(token, state)
                }
                val uris = state.getJSONArray("files")
                require(uris.length() == entries.length()) { "下载文件准备未完成，请先停止并清理" }
                for (i in 0 until entries.length()) {
                    control.checkActive()
                    val uri = Uri.parse(uris.getString(i))
                    val descriptor = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("目录不支持可定位写入")
                    val stream = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
                    try { stream.channel.position(0); sinks.add(Sink(stream, uri)) }
                    catch (error: Exception) { stream.close(); throw error }
                }
            } catch (error: Exception) {
                sinks.forEach { try { it.close() } catch (_: Exception) { } }
                if (control.cancelled || existing) throw error
                // Only new targets with no downloaded data can switch to staging.
                if (state?.optBoolean("owned_root") == true) discard(token)
                else { journal(token).delete(); File(journal(token).path + ".tmp").delete() }
                connection.output.writeInt(0); connection.output.flush()
                return false
            }
            val activeState = state!!
            val complete = BitSet(segments.size)
            val stored = activeState.getJSONArray("completed")
            for (i in 0 until stored.length()) { val index = stored.getInt(i); require(index in segments.indices); complete.set(index) }
            // Refuse stale/broken target files instead of trusting only the journal.
            for (segment in segments) if (complete[segment.id] && sinks[segment.file].channel.size() < segment.offset + segment.length) complete.clear(segment.id)
            val ranged = manifest.optInt("range_requests") == 1 && threads > 1
            if (!ranged) {
                // Serial peers can resume only the contiguous prefix of each file.
                val missing = HashSet<Int>()
                for (segment in segments) {
                    if (!complete[segment.id]) missing.add(segment.file)
                    if (segment.file in missing) complete.clear(segment.id)
                }
            }
            val gate = Any()
            fun checkpoint() = synchronized(gate) {
                val values = JSONArray(); var index = complete.nextSetBit(0)
                while (index >= 0) { values.put(index); index = complete.nextSetBit(index + 1) }
                activeState.put("completed", values); writeJournal(token, activeState)
            }
            val done = AtomicLong(segments.filter { complete[it.id] }.sumOf { it.length })
            report(done.get(), total, if (ranged) "parallel" else "transferring")
            fun write(input: DataInputStream, segment: Segment) {
                val channel = sinks[segment.file].channel
                val bytes = ByteArray(block); var received = 0L
                while (received < segment.length) {
                    control.checkActive()
                    val count = input.read(bytes, 0, minOf(block.toLong(), segment.length - received).toInt())
                    require(count > 0) { "连接中断，已保留已完成分段" }
                    control.checkActive()
                    val buffer = ByteBuffer.wrap(bytes, 0, count); var position = segment.offset + received
                    while (buffer.hasRemaining()) { control.checkActive(); val written = channel.write(buffer, position); require(written > 0); position += written }
                    received += count
                    report(done.addAndGet(count.toLong()), total, if (ranged) "parallel" else "transferring")
                }
                channel.force(true)
                synchronized(gate) { complete.set(segment.id); checkpoint() }
            }
            try {
                if (ranged) {
                    connection.output.writeInt(0); connection.output.flush(); connection.close()
                    val pending = segments.filter { !complete[it.id] }
                    val lanes = minOf(threads.coerceIn(1, 8), maxOf(1, pending.size))
                    val pool = Executors.newFixedThreadPool(lanes)
                    try {
                        val tasks = (0 until lanes).map { lane -> pool.submit {
                            try {
                                pending.filterIndexed { i, _ -> i % lanes == lane }.chunked(4096).forEach { batch ->
                                    control.checkActive()
                                    val (worker, current) = open(address, token, control)
                                    worker.use {
                                        require(signature(current) == identity && current.optInt("range_requests") == 1) { "分段下载期间来源发生变化" }
                                        it.output.writeInt(-1); it.output.writeInt(batch.size)
                                        for (segment in batch) { it.output.writeInt(segment.file); it.output.writeLong(segment.offset); it.output.writeLong(segment.length) }
                                        it.output.flush()
                                        for (segment in batch) { require(it.input.readUnsignedByte() == 1) { "发送端拒绝分段" }; write(it.input, segment) }
                                    }
                                }
                            } catch (error: Exception) { if (!control.cancelled) control.fail(error); throw error }
                        } }
                        tasks.forEach { it.get() }
                    } finally {
                        pool.shutdownNow()
                        if (!pool.awaitTermination(35, TimeUnit.SECONDS)) error("下载线程退出超时，临时数据未清理")
                    }
                } else {
                    val pendingFiles = (0 until entries.length()).filter { i -> segments.any { it.file == i && !complete[it.id] } || entries.getJSONObject(i).getLong("size") == 0L }
                    connection.output.writeInt(pendingFiles.size)
                    for (i in pendingFiles) {
                        val offset = segments.filter { it.file == i }.takeWhile { complete[it.id] }.sumOf { it.length }
                        connection.output.writeInt(i); connection.output.writeLong(offset)
                    }
                    connection.output.flush()
                    for (i in pendingFiles) {
                        require(connection.input.readUnsignedByte() == 1) { "发送端文件发生变化" }
                        for (segment in segments.filter { it.file == i && !complete[it.id] }) write(connection.input, segment)
                    }
                }
                control.checkActive()
                for (i in sinks.indices) { sinks[i].channel.truncate(entries.getJSONObject(i).getLong("size")); sinks[i].channel.force(true) }
                sinks.forEach { it.close() }; sinks.clear()
                report(total, total, "saving")
                control.finish {
                    if (activeState.optBoolean("owned_root")) {
                        val finalUri = DocumentsContract.renameDocument(resolver, Uri.parse(activeState.getString("root")), name)
                            ?: error("提交下载文件名失败")
                        activeState.put("root", finalUri.toString())
                    }
                    activeState.put("committed", true); writeJournal(token, activeState)
                    journal(token).delete(); File(journal(token).path + ".tmp").delete()
                    onSaved(Uri.parse(activeState.getString("root")))
                    report(total, total, "completed")
                }
                return true
            } finally {
                sinks.forEach { try { it.close() } catch (_: Exception) { } }
            }
        }
    }
}
