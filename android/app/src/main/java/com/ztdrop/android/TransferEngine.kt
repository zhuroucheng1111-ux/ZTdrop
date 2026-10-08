package com.ztdrop.android

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val FILE_PORT = 52111
private const val WEB_PORT = 52112
private const val MAX_MANIFEST = 32 * 1024 * 1024
private const val CHUNK = 256 * 1024

internal data class SourceFile(val uri: Uri, val relative: String, val size: Long, val modified: Long, val mime: String)
internal data class Share(
    val token: String, val name: String, val isFolder: Boolean, val files: List<SourceFile>,
    val directories: List<String>, val mode: String, val expiresAt: Long,
    var code: String = "", var shareId: String = "", var claimId: String = ""
) {
    val size: Long get() = files.sumOf { it.size }
}

/** The Windows ZTB4 manifest/request/data wire format, backed by user-granted SAF URIs. */
internal class TransferEngine(private val context: Context, private val changed: () -> Unit) {
    private val io = Executors.newCachedThreadPool()
    private val shares = ConcurrentHashMap<String, Share>()
    private val progress = ConcurrentHashMap<String, JSONObject>()
    private data class ReceiveJob(val token: String, val name: String, val size: Long, val mode: String)
    private val receiveJobs = ConcurrentHashMap<String, ReceiveJob>()
    private val directReceiver = DirectReceiver(context, FILE_PORT)
    private val receiving = ConcurrentHashMap<String, ReceiveControl>()
    private val lastNotice = ConcurrentHashMap<String, Long>()
    @Volatile private var tcp: ServerSocket? = null
    @Volatile private var http: ServerSocket? = null
    private val random = SecureRandom()

    fun start() {
        tcp = ServerSocket(FILE_PORT).also { server -> io.execute { accept(server, ::serveTransfer) } }
        http = ServerSocket(WEB_PORT).also { server -> io.execute { accept(server, ::serveHttp) } }
    }

    fun stop() {
        receiving.values.forEach { it.cancel() }
        tcp?.close(); http?.close()
        io.shutdownNow()
    }

    fun snapshot(): JSONArray = JSONArray().also { array ->
        progress.values.sortedBy { it.optLong("updated_at") }.forEach(array::put)
    }

    fun shares(): JSONArray = JSONArray().also { array ->
        shares.values.filter { it.expiresAt > System.currentTimeMillis() }.sortedBy { it.expiresAt }.forEach { share ->
            array.put(JSONObject().put("token", share.token).put("code", share.code)
                .put("mode", share.mode).put("name", share.name).put("size", share.size)
                .put("is_folder", share.isFolder).put("expires_at", share.expiresAt)
                .put("file_count", share.files.size).put("files", JSONArray().also { files ->
                    share.files.take(200).forEach { file -> files.put(JSONObject()
                        .put("name", file.relative.ifBlank { share.name }).put("size", file.size)) }
                }))
        }
    }

    fun prepare(uri: Uri, folder: Boolean, mode: String): Share {
        val files = ArrayList<SourceFile>()
        val directories = ArrayList<String>()
        val name = documentName(if (folder)
            DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) else uri)
        if (folder) walk(uri, "", files, directories)
        else files.add(file(uri, ""))
        require(files.size <= 100_000 && directories.size <= 100_000) { "文件夹条目过多" }
        return Share(id(), safeName(name), folder, files.sortedBy { it.relative },
            directories.sorted(), mode, System.currentTimeMillis() + when (mode) {
                "direct" -> 3_600_000L
                "chat" -> 86_400_000L
                else -> 900_000L
            })
    }

    fun register(share: Share) { shares[share.token] = share; changed() }

    fun revoke(token: String) { shares.remove(token); changed() }

    fun share(token: String): Share? = shares[token]?.takeIf { it.expiresAt > System.currentTimeMillis() }

    @Synchronized
    fun update(id: String, mode: String, name: String, transferred: Long, total: Long, status: String, error: String = "",
               receiverIp: String = "", requestId: String = "") {
        val key = if (requestId.isEmpty()) id else "$id:$requestId"
        val previous = progress[key]
        val now = System.currentTimeMillis()
        // Throttle before JSON allocation, not just before notifying the UI.
        if (previous?.optString("status") == status && transferred < previous.optLong("transferred")) return
        if (previous?.optString("status") == status && transferred < total &&
            now - (lastNotice[key] ?: 0) < 200) return
        val started = if (status == "connecting" || status in setOf("transferring", "parallel") && previous?.optString("status") != status) now else previous?.optLong("started_at", now) ?: now
        val initial = if (status == "connecting") 0L else if (status in setOf("transferring", "parallel") && previous?.optString("status") != status)
            transferred else previous?.optLong("initial_transferred", 0L) ?: 0L
        progress[key] = JSONObject().put("id", id).put("mode", mode).put("name", name)
            .put("transferred", transferred).put("total", total).put("status", status).put("error", error)
            .put("updated_at", now).put("started_at", started).put("receiver_ip", receiverIp)
            .put("request_id", requestId).put("initial_transferred", initial)
            .put("bytes_per_second", if (now > started) (transferred - initial).coerceAtLeast(0) * 1000 / (now - started) else 0L)
        if (progress.size > 512) progress.entries.sortedBy { it.value.optLong("updated_at") }
            .take(progress.size - 512).forEach { progress.remove(it.key); lastNotice.remove(it.key) }
        if (previous?.optString("status") != status || transferred >= total || now - (lastNotice[key] ?: 0) >= 200) {
            lastNotice[key] = now
            changed()
        }
    }

    fun isReceiving(taskId: String): Boolean = receiving.containsKey(taskId)

    fun pauseReceive(taskId: String) { receiving[taskId]?.cancel() }

    fun stopReceive(taskId: String) {
        receiving[taskId]?.let { it.cancel(discard = true); return }
        val job = receiveJobs[taskId] ?: return
        // Serialize cleanup with task startup: a stopped task may not restart until cleanup finishes.
        val control = ReceiveControl()
        if (receiving.putIfAbsent(taskId, control) != null) { receiving[taskId]?.cancel(discard = true); return }
        control.cancel(discard = true)
        io.execute {
            try {
                cleanupReceiveFiles(job.token)
                receiveJobs.remove(taskId, job)
                update(taskId, job.mode, job.name, 0, job.size, "cancelled")
            } catch (error: Exception) {
                update(taskId, job.mode, job.name, 0, job.size, "cleanup_error", error.message ?: "临时文件清理失败")
            } finally { receiving.remove(taskId, control) }
        }
    }

    private fun cleanupReceiveFiles(token: String) {
        require(validId(token)) { "接收任务标识无效" }
        directReceiver.discard(token)
        val parent = File(context.filesDir, "receives").canonicalFile
        val directory = File(parent, token).canonicalFile
        require(directory.parentFile == parent) { "临时目录超出任务范围" }
        if (directory.exists()) require(directory.deleteRecursively()) { "清理接收临时目录失败" }
    }

    fun receive(address: String, token: String, name: String, expectedSize: Long, folder: Boolean,
                destination: Uri, mode: String, taskId: String = token, destinationIsTree: Boolean = false,
                threads: Int = 8, completed: (Boolean, Uri?) -> Unit = { _, _ -> }): Boolean {
        val control = ReceiveControl()
        if (receiving.putIfAbsent(taskId, control) != null) return false
        val job = ReceiveJob(token, name, expectedSize, mode)
        receiveJobs[taskId] = job
        try {
            io.execute {
                var success = false
                var savedUri: Uri? = null
                try {
                    update(taskId, mode, name, 0, expectedSize, "connecting")
                    val direct = directReceiver.receive(address, token, name, expectedSize, folder, destination,
                        destinationIsTree, threads, control, onSaved = { savedUri = it }) { done, total, status ->
                        if (status != "completed") update(taskId, mode, name, done, total, status)
                    }
                    if (!direct) receiveBlocking(address, token, name, expectedSize, folder, destination, mode, taskId, destinationIsTree, control) { savedUri = it }
                    cleanupReceiveFiles(token)
                    receiveJobs.remove(taskId, job)
                    val total = progress[taskId]?.optLong("total") ?: expectedSize
                    update(taskId, mode, name, total, total, "completed")
                    success = true
                } catch (error: Exception) {
                    val previous = progress[taskId]
                    if (control.discard) {
                        try {
                            cleanupReceiveFiles(token)
                            receiveJobs.remove(taskId, job)
                            update(taskId, mode, name, 0, expectedSize, "cancelled")
                        } catch (cleanup: Exception) {
                            update(taskId, mode, name, previous?.optLong("transferred") ?: 0, expectedSize,
                                "cleanup_error", cleanup.message ?: "临时文件清理失败")
                        }
                    } else update(taskId, mode, name, previous?.optLong("transferred") ?: 0,
                        previous?.optLong("total") ?: expectedSize,
                        if (control.cancelled) "paused" else "error", error.message ?: "接收失败")
                } finally {
                    receiving.remove(taskId, control)
                }
                completed(success, if (success) savedUri else null)
            }
        } catch (error: Exception) {
            receiving.remove(taskId, control)
            throw error
        }
        return true
    }

    private fun receiveBlocking(address: String, token: String, name: String, expectedSize: Long,
                                folder: Boolean, destination: Uri, mode: String, taskId: String, destinationIsTree: Boolean, control: ReceiveControl, onSaved: (Uri) -> Unit) {
        require(validId(token)) { "分享令牌无效" }
        val staging = File(context.filesDir, "receives/$token").apply { mkdirs() }
        update(taskId, mode, name, 0, expectedSize, "connecting")
        Socket().use { socket ->
            control.attach(socket)
            control.checkActive()
            socket.receiveBufferSize = CHUNK * 4
            socket.connect(InetSocketAddress(address, FILE_PORT), 5000)
            socket.soTimeout = 30_000
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), CHUNK))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), CHUNK))
            output.write("ZTB4".toByteArray(Charsets.US_ASCII))
            output.write(token.toByteArray(Charsets.US_ASCII))
            output.flush()
            require(input.readUnsignedByte() == 1) { "分享已失效或发送端拒绝连接" }
            val length = input.readInt()
            require(length in 1..MAX_MANIFEST) { "清单大小无效" }
            val raw = ByteArray(length)
            input.readFully(raw)
            val manifest = JSONObject(String(raw, Charsets.UTF_8))
            require(manifest.getBoolean("is_folder") == folder) { "分享类型不一致" }
            val entries = manifest.getJSONArray("files")
            require(entries.length() <= 100_000) { "文件数量超限" }
            if (!folder) require(entries.length() == 1 && entries.getJSONObject(0).getLong("size") == expectedSize) { "分享文件大小不一致" }
            val total = (0 until entries.length()).sumOf { entries.getJSONObject(it).getLong("size") }
            val targets = ArrayList<Pair<JSONObject, File>>()
            var transferred = 0L
            val request = ArrayList<Pair<Int, Long>>()
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val relative = entry.getString("relative")
                val target = if (folder) File(staging, safeRelative(relative)) else File(staging, "received.bin")
                val size = entry.getLong("size")
                require(size >= 0 && target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "清单路径无效" }
                control.checkActive()
                val partial = File(target.path + ".part")
                val partialStamp = File(target.path + ".part.meta")
                target.parentFile?.mkdirs()
                val identity = "${entry.getLong("size")}:${entry.getLong("modified_ms")}"
                if (!partialStamp.isFile || partialStamp.readText() != identity) {
                    if (partial.exists()) require(partial.delete()) { "清理旧续传文件失败" }
                    partialStamp.writeText(identity)
                }
                val offset = if (partial.isFile && partial.length() <= size) partial.length() else 0L
                if (offset == 0L && partial.exists()) partial.delete()
                targets.add(entry to target)
                val complete = target.isFile && target.length() == size && target.lastModified() == entry.getLong("modified_ms")
                if (!complete) request.add(i to offset)
                transferred += if (complete) size else offset
            }
            output.writeInt(request.size)
            request.forEach { (index, offset) -> output.writeInt(index); output.writeLong(offset) }
            output.flush()
            update(taskId, mode, name, transferred, total, "transferring")
            val buffer = ByteArray(CHUNK)
            for ((index, offset) in request) {
                require(input.readUnsignedByte() == 1) { "发送端文件发生变化" }
                val (entry, target) = targets[index]
                val partial = File(target.path + ".part")
                var remaining = entry.getLong("size") - offset
                FileOutputStream(partial, true).use { fileSink ->
                    val sink = BufferedOutputStream(fileSink, CHUNK)
                    try {
                        while (remaining > 0) {
                            control.checkActive()
                            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            require(count > 0) { "连接中断，已保留临时文件供续传" }
                            control.checkActive()
                            sink.write(buffer, 0, count)
                            remaining -= count
                            transferred += count
                            update(taskId, mode, name, transferred, total, "transferring")
                        }
                    } finally { sink.flush() }
                    fileSink.fd.sync()
                }
                control.checkActive()
                require(partial.length() == entry.getLong("size")) { "文件大小校验失败" }
                if (target.exists()) target.delete()
                require(partial.renameTo(target)) { "保存临时文件失败" }
                target.setLastModified(entry.getLong("modified_ms"))
            }
            update(taskId, mode, name, total, total, "saving")
            control.checkActive()
            val outputRoot=if(destinationIsTree) {
                val parent=DocumentsContract.buildDocumentUriUsingTree(destination,DocumentsContract.getTreeDocumentId(destination))
                DocumentsContract.createDocument(context.contentResolver,parent,
                    if(folder) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream",safeName(name))
                    ?: error("默认下载目录不可写入，请在设置中重新选择目录")
            } else destination
            try {
                control.checkActive()
                if (folder) exportTree(destination, manifest.optJSONArray("directories") ?: JSONArray(), targets,
                    if (destinationIsTree) outputRoot else null, control)
                else copyToDocument(targets.single().second, outputRoot, control)
                control.finish { }
                onSaved(outputRoot)
            } catch (error: Exception) {
                // Only remove a root created by this attempt; never delete the user's selected tree.
                if (destinationIsTree) try { DocumentsContract.deleteDocument(context.contentResolver, outputRoot) } catch (_: Exception) { }
                throw error
            }
        }
    }

    private fun accept(server: ServerSocket, handler: (Socket) -> Unit) {
        while (!server.isClosed) try {
            val client = server.accept()
            io.execute { client.use { if (it.inetAddress.isSiteLocalAddress) handler(it) } }
        } catch (_: Exception) { if (server.isClosed) return }
    }

    private fun serveTransfer(socket: Socket) {
        var activeToken = ""
        val receiverIp = socket.inetAddress.hostAddress.orEmpty()
        val requestId = id()
        try {
        socket.soTimeout = 30_000
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), CHUNK))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), CHUNK))
        val magic = ByteArray(4)
        input.readFully(magic)
        if (String(magic, Charsets.US_ASCII) != "ZTB4") return
        val tokenBytes = ByteArray(32)
        input.readFully(tokenBytes)
        val token = String(tokenBytes, Charsets.US_ASCII)
        activeToken = token
        val share = share(token) ?: run { output.writeByte(0); output.flush(); return }
        fun report(done: Long, total: Long, status: String) =
            update(token, share.mode, share.name, done, total, status, receiverIp = receiverIp, requestId = requestId)
        val manifest = JSONObject().put("is_folder", share.isFolder).put("range_requests", 1)
            .put("directories", JSONArray(share.directories))
            .put("files", JSONArray().also { array -> share.files.forEach { file ->
                array.put(JSONObject().put("relative", file.relative).put("size", file.size).put("modified_ms", file.modified))
            } })
        val bytes = manifest.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_MANIFEST) { output.writeByte(0); output.flush(); return }
        output.writeByte(1)
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
        val first = input.readInt()
        val ranged = first == -1
        val count = if (ranged) input.readInt() else first
        require(count in 0..100_000 && (ranged || count <= share.files.size)) { "请求文件数量无效" }
        val requests = (0 until count).map {
            val index = input.readInt(); val offset = input.readLong()
            val source = share.files.getOrNull(index) ?: error("文件序号无效")
            val length = if (ranged) input.readLong() else source.size - offset
            require(offset in 0..source.size && length >= 0 && length <= source.size - offset && (!ranged || length > 0)) { "分段范围无效" }
            Triple(index, offset, length)
        }
        if (!ranged) require(requests.map { it.first }.distinct().size == count) { "重复文件序号" }
        requests.groupBy { it.first }.values.forEach { values ->
            val sorted = values.sortedBy { it.second }
            require(sorted.zipWithNext().none { (a, b) -> a.second + a.third > b.second }) { "分段范围重叠" }
        }
        val total = requests.fold(0L) { sum, item -> Math.addExact(sum, item.third) }
        var transferred = 0L
        report(0, total, "sending")
        for ((index, offset, length) in requests) {
            val source = share.files[index]
            val data = context.contentResolver.openInputStream(source.uri) ?: error("来源文件不可读取")
            data.use { stream ->
                if (documentSize(source.uri) != source.size) { output.writeByte(0); output.flush(); error("分享期间文件大小发生变化") }
                output.writeByte(1)
                skipFully(stream, offset)
                val buffer = ByteArray(CHUNK)
                var remaining = length
                while (remaining > 0) {
                    require(shares[token] === share) { "分享已停止" }
                    val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    require(read > 0) { "来源文件被截断" }
                    output.write(buffer, 0, read)
                    remaining -= read
                    transferred += read
                    report(transferred, total, "sending")
                }
            }
        }
        output.flush()
        report(total, total, "completed")
        } catch (error: Exception) {
            share(activeToken)?.let { update(activeToken, it.mode, it.name, 0, it.size,
                "error", error.message ?: "发送失败", receiverIp, requestId) }
        }
    }

    private fun serveHttp(socket: Socket) {
        socket.soTimeout = 15_000
        val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        val line = input.readLine() ?: return
        val headers = HashMap<String, String>()
        while (true) { val item = input.readLine() ?: return; if (item.isEmpty()) break
            val split = item.indexOf(':'); if (split > 0) headers[item.substring(0, split).lowercase()] = item.substring(split + 1).trim() }
        val parts = line.split(' ')
        if (parts.size < 2 || parts[0] != "GET") { httpReply(socket, 405, "Method Not Allowed", ByteArray(0)); return }
        val route = parts[1].split('?')[0]
        val token = route.substringAfterLast('/')
        val share = share(token)?.takeIf { it.mode == "direct" && !it.isFolder }
        if (share == null) { httpReply(socket, 404, "Not Found", ByteArray(0)); return }
        val receiverIp = socket.inetAddress.hostAddress.orEmpty()
        val requestId = id()
        fun report(done: Long, total: Long, status: String, error: String = "") =
            update(token, "direct", share.name, done, total, status, error, receiverIp, requestId)
        if (route == "/s/$token") {
            report(0, share.size, "visited")
            val html = "<!doctype html><meta charset=utf-8><title>ZTDrop</title><a href=\"/download/$token\">下载文件</a>"
            httpReply(socket, 200, "OK", html.toByteArray(Charsets.UTF_8), "text/html; charset=utf-8")
            return
        }
        if (route != "/download/$token") { httpReply(socket, 404, "Not Found", ByteArray(0)); return }
        val source = share.files.single()
        val range = Regex("bytes=(\\d+)-(\\d*)").matchEntire(headers["range"] ?: "")
        val start = range?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val end = range?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: (source.size - 1)
        if (source.size == 0L) {
            httpReply(socket, 200, "OK", ByteArray(0), "application/octet-stream")
            report(0, 0, "completed"); return
        }
        if (start < 0 || start >= source.size || end < start || end >= source.size) {
            httpReply(socket, 416, "Range Not Satisfiable", ByteArray(0)); return
        }
        val partial = range != null
        val out = socket.getOutputStream()
        val safe = share.name.replace('"', '_').replace('\\', '_')
        val header = "HTTP/1.1 ${if (partial) "206 Partial Content" else "200 OK"}\r\n" +
            "Content-Type: application/octet-stream\r\nContent-Disposition: attachment; filename*=UTF-8''${java.net.URLEncoder.encode(safe, "UTF-8")}\r\n" +
            "Accept-Ranges: bytes\r\nContent-Length: ${end - start + 1}\r\n" +
            (if (partial) "Content-Range: bytes $start-$end/${source.size}\r\n" else "") + "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        try {
        report(0, end - start + 1, "sending")
        (context.contentResolver.openInputStream(source.uri) ?: error("来源文件不可读取")).use { data ->
            skipFully(data, start)
            val buffer = ByteArray(CHUNK)
            var remaining = end - start + 1
            var sent = 0L
            while (remaining > 0) {
                require(shares[token] === share) { "分享已停止" }
                val read = data.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                require(read > 0) { "来源文件被截断" }
                out.write(buffer, 0, read)
                remaining -= read; sent += read
                report(sent, end - start + 1, "sending")
            }
            out.flush()
            report(sent, sent, "completed")
        }
        } catch (error: Exception) { report(0, end - start + 1, "error", error.message ?: "下载中断") }
    }

    private fun httpReply(socket: Socket, status: Int, label: String, body: ByteArray, contentType: String = "text/plain") {
        socket.getOutputStream().write(("HTTP/1.1 $status $label\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
            .toByteArray(Charsets.US_ASCII) + body)
    }

    private fun walk(uri: Uri, prefix: String, files: MutableList<SourceFile>, directories: MutableList<String>) {
        val resolver = context.contentResolver
        val parentId = if (prefix.isEmpty()) DocumentsContract.getTreeDocumentId(uri) else DocumentsContract.getDocumentId(uri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, parentId)
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val child = DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(0))
                val relative = if (prefix.isEmpty()) safeName(cursor.getString(1)) else "$prefix/${safeName(cursor.getString(1))}"
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    directories.add(relative); walk(child, relative, files, directories)
                } else files.add(file(child, relative))
                require(files.size <= 100_000 && directories.size <= 100_000) { "文件夹条目过多" }
            }
        } ?: error("无法读取所选文件夹")
    }

    private fun file(uri: Uri, relative: String): SourceFile {
        val resolver = context.contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            require(cursor.moveToFirst()) { "来源文件不可读取" }
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
            require(size >= 0) { "无法确定文件大小" }
            val modified = try {
                resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { meta ->
                    if (meta.moveToFirst() && !meta.isNull(0)) meta.getLong(0) else 0L
                } ?: 0L
            } catch (_: Exception) { 0L }
            return SourceFile(uri, relative, size, modified.coerceAtLeast(0), resolver.getType(uri) ?: "application/octet-stream")
        }
        error("无法读取来源文件")
    }

    private fun documentName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        error("无法识别文件名")
    }

    private fun documentSize(uri: Uri): Long = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (cursor.moveToFirst() && index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else -1L
    } ?: -1L

    private fun copyToDocument(source: File, target: Uri, control: ReceiveControl) {
        control.checkActive()
        context.contentResolver.openOutputStream(target, "wt")?.use { out ->
            source.inputStream().use { input ->
                val buffer = ByteArray(CHUNK)
                while (true) {
                    control.checkActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
                out.flush()
            }
        } ?: error("目标文件不可写入")
        control.checkActive()
    }

    private fun exportTree(tree: Uri, directories: JSONArray, targets: List<Pair<JSONObject, File>>, root: Uri?, control: ReceiveControl) {
        val resolver = context.contentResolver
        val folders = HashMap<String, Uri>()
        folders[""] = root ?: DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        fun directory(path: String): Uri {
            control.checkActive()
            folders[path]?.let { return it }
            val parent = path.substringBeforeLast('/', "")
            val name = path.substringAfterLast('/')
            val created = DocumentsContract.createDocument(resolver, directory(parent), DocumentsContract.Document.MIME_TYPE_DIR, name)
                ?: error("无法创建目标文件夹")
            folders[path] = created
            return created
        }
        for (i in 0 until directories.length()) directory(safeRelative(directories.getString(i)))
        for ((entry, source) in targets) {
            val path = safeRelative(entry.getString("relative"))
            val parent = path.substringBeforeLast('/', "")
            val name = path.substringAfterLast('/')
            val output = DocumentsContract.createDocument(resolver, directory(parent), "application/octet-stream", name)
                ?: error("无法创建目标文件")
            try { copyToDocument(source, output, control) }
            catch (error: Exception) {
                try { DocumentsContract.deleteDocument(resolver, output) } catch (_: Exception) { }
                throw error
            }
        }
    }

    private fun skipFully(stream: java.io.InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) remaining -= skipped else if (stream.read() >= 0) remaining-- else error("来源文件短于续传位置")
        }
    }

    private fun safeName(value: String): String {
        require(value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size <= 255 &&
            value !in setOf(".", "..") && !value.endsWith(' ') && !value.endsWith('.') &&
            value.none { it in "/\\:*?\"<>|\u0000" }) { "文件名不安全" }
        return value
    }

    private fun safeRelative(value: String): String {
        require(value.isNotEmpty() && value.split('/').all { it.isNotEmpty() && safeName(it) == it }) { "相对路径不安全" }
        return value
    }

    private fun id(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
}
