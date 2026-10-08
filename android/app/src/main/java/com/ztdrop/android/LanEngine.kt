package com.ztdrop.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val SIGNAL_PORT = 52110
private const val FILE_PORT = 52111

private data class Peer(val id: String, val name: String, val ip: String, var seenAt: Long)
private data class WifiAddress(val ip: String, val broadcast: String)
internal data class ReceiveOffer(val ip: String, val token: String, val name: String, val size: Long,
                                 val folder: Boolean, val mode: String, val messageId: String = "")
private data class CodeLease(val deviceId: String, val shareId: String, val claimId: String, val seenAt: Long)

/** ZTDrop v2 discovery and one-to-one chat. No IPMsg packets are put on the wire. */
class LanEngine(private val context: Context, private val changed: () -> Unit) {
    private val io = Executors.newCachedThreadPool()
    private val store = MessageStore(context)
    private val transfers = TransferEngine(context, changed)
    private val codeQueries = ConcurrentHashMap<String, (ReceiveOffer?) -> Unit>()
    private val pendingClaims = ConcurrentHashMap<String, String>()
    private val lostClaims = ConcurrentHashMap.newKeySet<String>()
    private val codeLeases = ConcurrentHashMap<String, CodeLease>()
    private val peers = LinkedHashMap<String, Peer>()
    private val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
    val deviceId: String = prefs.getString("device_id", null)?.takeIf(::validId) ?: randomId().also {
        prefs.edit().putString("device_id", it).apply()
    }
    val deviceName: String
        get() = prefs.getString("device_name", null)?.takeIf { it.isNotBlank() }
            ?: "ZTDrop · ${Build.MODEL ?: "Android"}"
    @Volatile private var running = false
    @Volatile private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (running) return
        val udp = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(SIGNAL_PORT))
            soTimeout = 1000
        }
        socket = udp
        running = true
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("ztdrop-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        io.execute { receiveLoop(udp) }
        io.execute { heartbeatLoop() }
        transfers.start()
    }

    fun stop() {
        running = false
        socket?.close()
        socket = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        transfers.stop()
        io.shutdownNow()
        store.close()
    }

    fun state(peerId: String): JSONObject {
        val wifi = wifiAddress()
        val online = synchronized(peers) { peers.values.filter { System.currentTimeMillis() - it.seenAt < 9000 }.toList() }
        val peerArray = JSONArray()
        online.forEach { peer -> peerArray.put(JSONObject()
            .put("node_id", peer.id).put("device_name", peer.name).put("ip", peer.ip).put("online", true)) }
        return JSONObject().put("device_id", deviceId).put("device_name", deviceName)
            .put("ip", wifi?.ip ?: "")
            .put("peers", peerArray).put("friends", store.friends(online.map { it.id }.toSet()))
            .put("messages", store.messages(peerId)).put("transfers", transfers.snapshot())
            .put("shares", transfers.shares())
    }

    fun startCodeShare(uri: Uri, folder: Boolean, result: (String?, String?) -> Unit) {
        io.execute {
            try {
                val share = transfers.prepare(uri, folder, "code")
                var selected: String? = null
                repeat(40) {
                    if (selected != null) return@repeat
                    val code = (0..9999).random().toString().padStart(4, '0')
                    if (codeLeases[code]?.let { System.currentTimeMillis() - it.seenAt < 20_000 } == true) return@repeat
                    val claimId = randomId()
                    pendingClaims[code] = claimId
                    val claim = codePacket("CODE_CLAIM", code, share.token, claimId)
                    sendToLan(claim)
                    Thread.sleep(150)
                    sendToLan(claim)
                    Thread.sleep(200)
                    pendingClaims.remove(code)
                    if (lostClaims.remove(claimId) || codeLeases[code]?.let { System.currentTimeMillis() - it.seenAt < 20_000 } == true) return@repeat
                    share.code = code; share.shareId = share.token; share.claimId = claimId
                    transfers.register(share)
                    codeLeases[code] = CodeLease(deviceId, share.shareId, claimId, System.currentTimeMillis())
                    sendToLan(codePacket("CODE_ACTIVE", code, share.shareId, claimId))
                    selected = code
                }
                result(selected, if (selected == null) "分享码冲突过多" else null)
            } catch (error: Exception) { result(null, error.message ?: "创建分享失败") }
        }
    }

    fun startDirectShare(uri: Uri, result: (String?, String?) -> Unit) {
        io.execute {
            try {
                val ip = wifiAddress()?.ip ?: error("未连接 Wi-Fi 局域网")
                val share = transfers.prepare(uri, false, "direct")
                transfers.register(share)
                result("http://$ip:52112/s/${share.token}", null)
            } catch (error: Exception) { result(null, error.message ?: "创建直链失败") }
        }
    }

    fun revokeShare(token: String) {
        val share = transfers.share(token) ?: return
        transfers.revoke(token)
        if (share.code.isNotEmpty()) {
            codeLeases.remove(share.code)
            sendToLan(codePacket("CODE_RELEASE", share.code, share.shareId, share.claimId))
        }
    }

    fun sendAttachment(peerId: String, uri: Uri, folder: Boolean, result: (String?) -> Unit) {
        io.execute {
            try {
                require(store.friendStatus(peerId) == "accepted") { "请先添加设备好友" }
                require(peer(peerId) != null) { "设备已离线" }
                val share = transfers.prepare(uri, folder, "chat")
                transfers.register(share)
                val message = JSONObject().put("message_id", randomId()).put("peer_id", peerId)
                    .put("sender_id", deviceId).put("kind", if (folder) "folder" else "file")
                    .put("content", "").put("file_name", share.name).put("file_size", share.size)
                    .put("is_folder", folder).put("token", share.token)
                    .put("created_at", System.currentTimeMillis() / 1000)
                    .put("download_status", "pending").put("delivery_status", "pending")
                store.insertMessage(message, peerId)
                if (!folder) ChatImages.savePreview(context, message.getString("message_id"), uri)?.let { store.setLocalMedia(message.getString("message_id"), it) }
                deliver(message)
                changed()
                result(null)
            } catch (error: Exception) { result(error.message ?: "发送附件失败") }
        }
    }

    internal fun queryCode(code: String, result: (ReceiveOffer?) -> Unit) {
        require(code.matches(Regex("[0-9]{4}"))) { "请输入 4 位分享码" }
        val requestId = randomId()
        codeQueries[requestId] = result
        val query = packet("CODE_QUERY", requestId).put("code", code)
        io.execute {
            repeat(4) {
                if (!codeQueries.containsKey(requestId)) return@execute
                sendToLan(query)
                Thread.sleep(800)
            }
            codeQueries.remove(requestId)?.invoke(null)
        }
    }

    internal fun isReceiving(taskId: String): Boolean = transfers.isReceiving(taskId)

    internal fun pauseReceive(taskId: String) { transfers.pauseReceive(taskId) }

    internal fun stopReceive(taskId: String) { transfers.stopReceive(taskId) }

    internal fun receive(offer: ReceiveOffer, destination: Uri, destinationIsTree: Boolean = false): Boolean {
        return transfers.receive(offer.ip, offer.token, offer.name, offer.size, offer.folder,
            destination, offer.mode, offer.messageId.ifEmpty { offer.token }, destinationIsTree,
            context.getSharedPreferences("ui", Context.MODE_PRIVATE).getInt("download_threads", 8).coerceIn(1, 8)) { complete, savedUri ->
            if (running) {
                if (offer.messageId.isNotEmpty()) {
                    store.setDownloadStatus(offer.messageId, if (complete) "completed" else "failed")
                    if (complete && !offer.folder && savedUri != null) ChatImages.savePreview(context, offer.messageId, savedUri)?.let { store.setLocalMedia(offer.messageId, it) }
                }
                changed()
            }
        }
    }

    internal fun offerForMessage(message: JSONObject): ReceiveOffer {
        val id = message.optString("sender_id")
        val sender = peer(id) ?: error("发送设备已离线")
        require(message.optString("kind") in setOf("file", "folder") && validId(message.optString("token"))) { "文件消息无效" }
        return ReceiveOffer(sender.ip, message.getString("token"), message.getString("file_name"),
            message.getLong("file_size"), message.optBoolean("is_folder"), "chat", message.getString("message_id"))
    }

    fun setDeviceName(name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && trimmed.toByteArray(Charsets.UTF_8).size <= 120) {
            "昵称长度需在 1 到 120 字节之间"
        }
        prefs.edit().putString("device_name", trimmed).apply()
        changed()
    }

    fun requestFriend(peerId: String) {
        require(validId(peerId) && peerId != deviceId) { "设备 ID 无效" }
        val peer = peer(peerId) ?: error("设备已离线")
        when (store.friendStatus(peerId)) {
            "accepted" -> error("已经是好友")
            "pending_out" -> error("好友申请已发送")
            "pending_in" -> error("请先处理对方的好友申请")
        }
        store.clearCancelledRequest(peerId)
        store.upsertFriend(peerId, peer.name, "pending_out")
        sendFriendRequest(peerId)
        changed()
    }

    fun cancelFriendRequest(peerId: String) {
        require(store.friendStatus(peerId) == "pending_out") { "没有待取消的好友申请" }
        store.cancelFriendRequest(peerId)
        if (peer(peerId) != null) {
            sendFriendCancellation(peerId)
            store.markCancellationSent(peerId)
        }
        changed()
    }

    fun answerFriend(peerId: String, accept: Boolean) {
        require(store.friendStatus(peerId) == "pending_in") { "没有待处理的好友请求" }
        if (accept) store.upsertFriend(peerId, peer(peerId)?.name ?: "局域网设备", "accepted")
        else store.removeFriend(peerId)
        send(peerId, packet(if (accept) "FRIEND_ACCEPT" else "FRIEND_REJECT")
            .put("from", deviceId).put("to", peerId).put("device_name", deviceName))
        changed()
    }

    fun setFriendRemark(peerId: String, remark: String) {
        require(store.friendStatus(peerId) == "accepted") { "设备不是好友" }
        val value = remark.trim()
        require(value.toByteArray(Charsets.UTF_8).size <= 120) { "备注不能超过 120 字节" }
        store.setRemark(peerId, value)
        changed()
    }

    fun setConversationPinned(peerId: String, pinned: Boolean) {
        require(store.friendStatus(peerId) == "accepted") { "设备不是好友" }
        store.setPinned(peerId, pinned)
        changed()
    }

    fun clearHistory(peerId: String) {
        require(store.friendStatus(peerId) == "accepted") { "设备不是好友" }
        store.clearMessages(peerId)
        changed()
    }

    // 仅移除本机记录，不发送撤回信令，也不删除已保存的附件。
    fun deleteMessage(peerId: String, messageId: String) {
        require(validId(peerId) && validId(messageId)) { "消息 ID 无效" }
        store.deleteMessage(peerId, messageId)
        changed()
    }

    fun removeFriend(peerId: String) {
        require(store.friendStatus(peerId) == "accepted") { "设备不是好友" }
        send(peerId, packet("FRIEND_REMOVE").put("from", deviceId).put("to", peerId))
        store.removeFriend(peerId)
        changed()
    }

    fun sendText(peerId: String, content: String) {
        require(store.friendStatus(peerId) == "accepted") { "请先添加设备好友" }
        val text = content.trim()
        require(text.isNotEmpty() && text.toByteArray(Charsets.UTF_8).size <= 2000) { "消息长度需在 1 到 2000 字节之间" }
        val message = JSONObject()
            .put("message_id", randomId()).put("peer_id", peerId).put("sender_id", deviceId)
            .put("kind", "text").put("content", text)
            .put("file_name", JSONObject.NULL).put("file_size", JSONObject.NULL)
            .put("is_folder", false).put("token", JSONObject.NULL)
            .put("created_at", System.currentTimeMillis() / 1000)
            .put("download_status", "pending").put("delivery_status", "pending")
        store.insertMessage(message, peerId)
        deliver(message)
        changed()
    }

    private fun receiveLoop(udp: DatagramSocket) {
        val bytes = ByteArray(4096)
        while (running) {
            try {
                val datagram = DatagramPacket(bytes, bytes.size)
                udp.receive(datagram)
                val value = JSONObject(String(datagram.data, datagram.offset, datagram.length, Charsets.UTF_8))
                if (value.optInt("protocol_version") != 2) continue
                handle(value, datagram.address.hostAddress ?: continue, datagram.port)
            } catch (_: java.net.SocketTimeoutException) {
                // Check stop flag once a second.
            } catch (_: Exception) {
                if (!running) break
            }
        }
    }

    private fun heartbeatLoop() {
        var lastRetry = 0L
        while (running) {
            try {
                val heartbeat = packet("HEARTBEAT")
                    .put("node_id", deviceId).put("device_name", deviceName).put("port", FILE_PORT)
                wifiAddress()?.let { wifi ->
                    sendRaw(heartbeat, wifi.broadcast)
                    sendRaw(heartbeat, "255.255.255.255")
                }
                val known = synchronized(peers) { peers.values.toList() }
                known.forEach { if (System.currentTimeMillis() - it.seenAt < 9000) sendRaw(heartbeat, it.ip) }
                synchronized(peers) { peers.entries.removeAll { System.currentTimeMillis() - it.value.seenAt >= 9000 } }
                val shares = transfers.shares()
                val activeCodes = (0 until shares.length()).mapNotNull { shares.optJSONObject(it)?.optString("code") }
                    .filter { it.isNotEmpty() }.toSet()
                codeLeases.entries.removeAll { (code, lease) ->
                    if (lease.deviceId == deviceId && code !in activeCodes) {
                        sendToLan(codePacket("CODE_RELEASE", code, lease.shareId, lease.claimId))
                        true
                    } else lease.deviceId != deviceId && System.currentTimeMillis() - lease.seenAt >= 20_000
                }
                for (i in 0 until shares.length()) {
                    val current = transfers.share(shares.getJSONObject(i).getString("token")) ?: continue
                    if (current.code.isNotEmpty()) sendToLan(codePacket("CODE_HEARTBEAT", current.code, current.shareId, current.claimId))
                }
                if (System.currentTimeMillis() - lastRetry >= 6000) {
                    store.pendingFriends().forEach(::sendFriendRequest)
                    store.pendingCancellations().forEach { id ->
                        if (peer(id) != null) {
                            sendFriendCancellation(id)
                            store.markCancellationSent(id)
                        }
                    }
                    store.pendingMessages(deviceId).forEach(::deliver)
                    lastRetry = System.currentTimeMillis()
                }
                changed()
                Thread.sleep(3000)
            } catch (_: InterruptedException) { break }
            catch (_: Exception) { Thread.sleep(1000) }
        }
    }

    private fun handle(value: JSONObject, ip: String, port: Int) {
        val kind = value.optString("type")
        if (kind.startsWith("CODE_")) {
            handleCode(value, ip, port)
            return
        }
        if (kind == "HEARTBEAT") {
            val id = value.optString("node_id")
            if (!validId(id) || id == deviceId || value.optInt("port") != FILE_PORT) return
            val name = value.optString("device_name", "局域网设备").take(200)
            synchronized(peers) { peers[id] = Peer(id, name, ip, System.currentTimeMillis()) }
            changed()
            return
        }
        if (kind !in setOf("FRIEND_REQUEST", "FRIEND_ACCEPT", "FRIEND_REJECT", "FRIEND_REMOVE", "CHAT_MESSAGE", "CHAT_ACK")) return
        val from = value.optString("from")
        if (!validId(from) || value.optString("to") != deviceId || peer(from)?.ip != ip) return
        if (value.optString("sender_device_id") != from) return
        when (kind) {
            "FRIEND_REQUEST" -> {
                val current = store.friendStatus(from)
                val name = value.optString("device_name", "局域网设备").take(200)
                store.clearCancelledRequest(from)
                store.upsertFriend(from, name, if (current == "accepted") "accepted" else "pending_in")
                if (current == "accepted") send(from, packet("FRIEND_ACCEPT").put("from", deviceId).put("to", from).put("device_name", deviceName))
            }
            "FRIEND_ACCEPT" -> if (store.friendStatus(from) == "pending_out") store.upsertFriend(from, value.optString("device_name", "局域网设备").take(200), "accepted")
            "FRIEND_REJECT", "FRIEND_REMOVE" -> store.removeFriend(from)
            "CHAT_MESSAGE" -> {
                if (store.friendStatus(from) != "accepted") return
                val message = value.optJSONObject("message") ?: return
                if (!validId(message.optString("message_id")) || message.optString("sender_id") != from || message.optString("peer_id") != deviceId) return
                if (message.optString("content").toByteArray(Charsets.UTF_8).size > 2000) return
                if (message.optString("kind") !in setOf("text", "file", "folder")) return
                if (message.optString("kind") != "text") {
                    val name = message.optString("file_name")
                    if (!validId(message.optString("token")) || name.isBlank() ||
                        name.toByteArray(Charsets.UTF_8).size > 255 || name in setOf(".", "..") ||
                        name.endsWith(' ') || name.endsWith('.') || name.any { it in "/\\:*?\"<>|\u0000" } ||
                        message.optLong("file_size", -1) < 0) return
                }
                message.put("peer_id", from).put("delivery_status", "delivered").put("download_status", "pending")
                store.insertMessage(message, from)
                send(from, packet("CHAT_ACK", message.getString("message_id")).put("from", deviceId).put("to", from))
            }
            "CHAT_ACK" -> if (validId(value.optString("message_id"))) store.markDelivered(value.getString("message_id"), from, deviceId)
        }
        changed()
    }

    private fun handleCode(value: JSONObject, ip: String, port: Int) {
        val kind = value.optString("type")
        if (kind == "CODE_RESPONSE") {
            val requestId = value.optString("request_id")
            val callback = codeQueries[requestId] ?: return
            val token = value.optString("token")
            val id = value.optString("device_id")
            val name = value.optString("file_name")
            val size = value.optLong("file_size", -1)
            if (!validId(token) || !validId(id) || id == deviceId || name.isBlank() || size < 0 ||
                value.optString("sender_device_id") != id) return
            codeQueries.remove(requestId)
            callback(ReceiveOffer(ip, token, name, size, value.optBoolean("is_folder"), "code"))
            return
        }
        val code = value.optString("code")
        if (!code.matches(Regex("[0-9]{4}"))) return
        if (kind == "CODE_QUERY") {
            val request = value.optString("message_id")
            if (!validId(request)) return
            val share = transfers.shares().let { list -> (0 until list.length())
                .mapNotNull { list.optJSONObject(it) }.firstOrNull { it.optString("code") == code } }
                ?.let { transfers.share(it.optString("token")) } ?: return
            val response = packet("CODE_RESPONSE")
                .put("request_id", request).put("code", code).put("device_id", deviceId)
                .put("share_id", share.shareId).put("claim_id", share.claimId)
                .put("file_name", share.name).put("file_size", share.size)
                .put("is_folder", share.isFolder).put("token", share.token)
            sendRaw(response, ip, port)
            return
        }
        val remoteId = value.optString("device_id")
        val shareId = value.optString("share_id")
        val claimId = value.optString("claim_id")
        if (!validId(remoteId) || !validId(shareId) || !validId(claimId) || remoteId == deviceId) return
        when (kind) {
            "CODE_CLAIM" -> {
                val active = codeLeases[code]
                val pending = pendingClaims[code]
                if (active != null || pending != null && wins(pending, deviceId, claimId, remoteId)) {
                    val ours = active ?: CodeLease(deviceId, shareId, pending!!, System.currentTimeMillis())
                    sendRaw(codePacket("CODE_CONFLICT", code, ours.shareId, ours.claimId)
                        .put("target_claim_id", claimId), ip, port)
                } else if (pending != null) lostClaims.add(pending)
            }
            "CODE_CONFLICT" -> pendingClaims[code]?.let { if (value.optString("target_claim_id") == it) lostClaims.add(it) }
            "CODE_ACTIVE", "CODE_HEARTBEAT" -> {
                pendingClaims[code]?.let(lostClaims::add)
                val existing = codeLeases[code]
                if (existing == null || wins(claimId, remoteId, existing.claimId, existing.deviceId)) {
                    if (existing?.deviceId == deviceId) transfers.shares().let { list ->
                        (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                            .firstOrNull { it.optString("code") == code }?.let { transfers.revoke(it.optString("token")) }
                    }
                    codeLeases[code] = CodeLease(remoteId, shareId, claimId, System.currentTimeMillis())
                } else if (existing.claimId == claimId && existing.deviceId == remoteId)
                    codeLeases[code] = existing.copy(seenAt = System.currentTimeMillis())
            }
            "CODE_RELEASE" -> codeLeases[code]?.let {
                if (it.deviceId == remoteId && it.shareId == shareId && it.claimId == claimId) codeLeases.remove(code)
            }
        }
    }

    private fun codePacket(kind: String, code: String, shareId: String, claimId: String): JSONObject =
        packet(kind).put("code", code).put("device_id", deviceId).put("share_id", shareId).put("claim_id", claimId)

    private fun wins(claimA: String, deviceA: String, claimB: String, deviceB: String): Boolean =
        claimA > claimB || claimA == claimB && deviceA > deviceB

    private fun sendToLan(value: JSONObject) {
        wifiAddress()?.let { sendRaw(value, it.broadcast); sendRaw(value, "255.255.255.255") }
        synchronized(peers) { peers.values.map { it.ip } }.forEach { sendRaw(value, it) }
    }

    private fun peer(id: String): Peer? = synchronized(peers) {
        peers[id]?.takeIf { System.currentTimeMillis() - it.seenAt < 9000 }
    }

    private fun sendFriendRequest(id: String) {
        if (store.friendStatus(id) != "pending_out") return
        send(id, packet("FRIEND_REQUEST").put("from", deviceId).put("to", id).put("device_name", deviceName))
    }

    private fun sendFriendCancellation(id: String) {
        send(id, packet("FRIEND_REMOVE").put("from", deviceId).put("to", id))
    }

    private fun deliver(message: JSONObject) {
        val id = message.optString("peer_id")
        send(id, packet("CHAT_MESSAGE").put("from", deviceId).put("to", id).put("message", message))
    }

    private fun packet(kind: String, id: String = randomId()): JSONObject = JSONObject()
        .put("protocol_version", 2).put("message_id", id).put("type", kind)
        .put("sender_device_id", deviceId).put("timestamp_ms", System.currentTimeMillis())

    private fun send(id: String, value: JSONObject) {
        val address = peer(id)?.ip ?: return
        io.execute { repeat(3) { sendRaw(value, address); Thread.sleep(120) } }
    }

    private fun sendRaw(value: JSONObject, address: String, port: Int = SIGNAL_PORT) {
        val udp = socket ?: return
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > 3900) return
        try { udp.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(address), port)) }
        catch (_: Exception) { /* Peer may disappear during a network change. */ }
    }

    private fun wifiAddress(): WifiAddress? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (network in manager.allNetworks) {
            val caps = manager.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val link = manager.getLinkProperties(network) ?: continue
            for (address in link.linkAddresses) {
                val ip = address.address as? Inet4Address ?: continue
                val prefix = address.prefixLength
                if (prefix !in 1..32) continue
                val octets = ip.address
                val number = ((octets[0].toInt() and 255) shl 24) or ((octets[1].toInt() and 255) shl 16) or
                    ((octets[2].toInt() and 255) shl 8) or (octets[3].toInt() and 255)
                val mask = (-1 shl (32 - prefix))
                val broadcast = number or mask.inv()
                return WifiAddress(ip.hostAddress ?: "", listOf(24, 16, 8, 0).joinToString(".") { ((broadcast ushr it) and 255).toString() })
            }
        }
        return null
    }

    companion object {
        private val random = SecureRandom()
        private fun randomId(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    }
}
