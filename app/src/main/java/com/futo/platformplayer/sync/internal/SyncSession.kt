package com.futo.platformplayer.sync.internal

import com.futo.platformplayer.UIDialogs
import com.futo.platformplayer.activities.MainActivity
import com.futo.platformplayer.api.media.Serializer
import com.futo.platformplayer.logging.Logger
import com.futo.platformplayer.models.HistoryVideo
import com.futo.platformplayer.models.Subscription
import com.futo.platformplayer.models.SubscriptionGroup
import com.futo.platformplayer.states.StateApp
import com.futo.platformplayer.states.StateBackup
import com.futo.platformplayer.states.StateHistory
import com.futo.platformplayer.states.StatePlayer
import com.futo.platformplayer.states.StatePlaylists
import com.futo.platformplayer.states.StateSubscriptionGroups
import com.futo.platformplayer.states.StateSubscriptions
import com.futo.platformplayer.states.StateSync
import com.futo.platformplayer.sync.SyncSessionData
import com.futo.platformplayer.sync.internal.SyncSocketSession.Opcode
import com.futo.platformplayer.sync.models.SendToDevicePackage
import com.futo.platformplayer.sync.models.SyncPlaylistsPackage
import com.futo.platformplayer.sync.models.SyncSubscriptionGroupsPackage
import com.futo.platformplayer.sync.models.SyncSubscriptionsPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

interface IAuthorizable {
    val isAuthorized: Boolean
}

class SyncSession : IAuthorizable {
    private val _socketSessions: MutableList<SyncSocketSession> = mutableListOf()
    private var _authorized: Boolean = false
    private var _remoteAuthorized: Boolean = false
    private val _onAuthorized: (session: SyncSession) -> Unit
    private val _onUnauthorized: (session: SyncSession) -> Unit
    private val _onClose: (session: SyncSession) -> Unit
    private val _onConnectedChanged: (session: SyncSession, connected: Boolean) -> Unit
    val remotePublicKey: String
    override val isAuthorized get() = _authorized && _remoteAuthorized
    private var _wasAuthorized = false

    var connected: Boolean = false
        private set(v) {
        if (field != v) {
            field = v
            this._onConnectedChanged(this, v)
        }
    }

    constructor(remotePublicKey: String, onAuthorized: (session: SyncSession) -> Unit, onUnauthorized: (session: SyncSession) -> Unit, onConnectedChanged: (session: SyncSession, connected: Boolean) -> Unit, onClose: (session: SyncSession) -> Unit) {
        this.remotePublicKey = remotePublicKey
        _onAuthorized = onAuthorized
        _onUnauthorized = onUnauthorized
        _onConnectedChanged = onConnectedChanged
        _onClose = onClose
    }

    fun addSocketSession(socketSession: SyncSocketSession) {
        if (socketSession.remotePublicKey != remotePublicKey) {
            throw Exception("Public key of session must match public key of socket session")
        }

        synchronized(_socketSessions) {
            _socketSessions.add(socketSession)
            connected = _socketSessions.isNotEmpty()
        }

        socketSession.authorizable = this
    }

    fun authorize(socketSession: SyncSocketSession) {
        socketSession.send(Opcode.NOTIFY_AUTHORIZED.value)
        _authorized = true
        checkAuthorized()
    }

    fun unauthorize(socketSession: SyncSocketSession? = null) {
        if (socketSession != null)
            socketSession.send(Opcode.NOTIFY_UNAUTHORIZED.value)
        else {
            val ss = synchronized(_socketSessions) {
                _socketSessions.first()
            }

            ss.send(Opcode.NOTIFY_UNAUTHORIZED.value)
        }
    }

    private fun checkAuthorized() {
        if (!_wasAuthorized && isAuthorized) {
            _wasAuthorized = true
            _onAuthorized.invoke(this)
        }
    }

    fun removeSocketSession(socketSession: SyncSocketSession) {
        synchronized(_socketSessions) {
            _socketSessions.remove(socketSession)
            connected = _socketSessions.isNotEmpty()
        }
    }

    fun close() {
        synchronized(_socketSessions) {
            for (socketSession in _socketSessions) {
                socketSession.stop()
            }

            _socketSessions.clear()
        }

        _onClose.invoke(this)
    }

    fun handlePacket(socketSession: SyncSocketSession, opcode: UByte, subOpcode: UByte, data: ByteBuffer) {
        try {
            Logger.i(TAG, "Handle packet (opcode: ${opcode}, subOpcode: ${subOpcode}, data.length: ${data.remaining()})")

            when (opcode) {
                Opcode.NOTIFY_AUTHORIZED.value -> {
                    _remoteAuthorized = true
                    checkAuthorized()
                }
                Opcode.NOTIFY_UNAUTHORIZED.value -> {
                    _remoteAuthorized = false
                    _onUnauthorized(this)
                }
                //TODO: Handle any kind of packet (that is not necessarily authorized)
            }

            if (!isAuthorized) {
                return
            }

            if (opcode != Opcode.DATA.value) {
                Logger.w(TAG, "Unknown opcode received: (opcode = ${opcode}, subOpcode = ${subOpcode})}")
                return
            }

            Logger.i(TAG, "Received (opcode = ${opcode}, subOpcode = ${subOpcode}) (${data.remaining()} bytes)")
            //TODO: Abstract this out
            when (subOpcode) {
                GJSyncOpcodes.sendToDevices -> {
                    StateApp.instance.scopeOrNull?.launch(Dispatchers.Main) {
                        val context = StateApp.instance.contextOrNull;
                        if (context != null && context is MainActivity) {
                            val dataBody = ByteArray(data.remaining());
                            val remainder = data.remaining();
                            data.get(dataBody, 0, remainder);
                            val json = String(dataBody, Charsets.UTF_8);
                            val obj = Json.decodeFromString<SendToDevicePackage>(json);
                            UIDialogs.appToast("Received url from device [${socketSession.remotePublicKey}]:\n{${obj.url}");
                            context.handleUrl(obj.url, obj.position);
                        }
                    };
                }

                GJSyncOpcodes.syncStateExchange -> {
                    val dataBody = ByteArray(data.remaining());
                    data.get(dataBody);
                    val json = String(dataBody, Charsets.UTF_8);
                    val syncSessionData = Serializer.json.decodeFromString<SyncSessionData>(json);

                    Logger.i(TAG, "Received SyncSessionData from " + remotePublicKey);


                    sendData(GJSyncOpcodes.syncSubscriptions, StateSubscriptions.instance.getSyncSubscriptionsPackageString());
                    sendData(GJSyncOpcodes.syncSubscriptionGroups, StateSubscriptionGroups.instance.getSyncSubscriptionGroupsPackageString());
                    sendData(GJSyncOpcodes.syncPlaylists, StatePlaylists.instance.getSyncPlaylistsPackageString())

                    val recentHistory = StateHistory.instance.getRecentHistory(syncSessionData.lastHistory);
                    if(recentHistory.size > 0)
                        sendJsonData(GJSyncOpcodes.syncHistory, recentHistory);
                }

                GJSyncOpcodes.syncExport -> {
                    val dataBody = ByteArray(data.remaining());
                    val bytesStr = ByteArrayInputStream(data.array(), data.position(), data.remaining());
                    try {
                        val exportStruct = StateBackup.ExportStructure.fromZipBytes(bytesStr);
                        for (store in exportStruct.stores) {
                            if (store.key.equals("subscriptions", true)) {
                                val subStore =
                                    StateSubscriptions.instance.getUnderlyingSubscriptionsStore();
                                StateApp.instance.scopeOrNull?.launch(Dispatchers.IO) {
                                    val pack = SyncSubscriptionsPackage(
                                        store.value.map {
                                            subStore.fromReconstruction(it, exportStruct.cache)
                                        },
                                        StateSubscriptions.instance.getSubscriptionRemovals()
                                    );
                                    handleSyncSubscriptionPackage(this@SyncSession, pack);
                                }
                            }
                        }
                    } finally {
                        bytesStr.close();
                    }
                }

                GJSyncOpcodes.syncSubscriptions -> {
                    val dataBody = ByteArray(data.remaining());
                    data.get(dataBody);
                    val json = String(dataBody, Charsets.UTF_8);
                    val subPackage = Serializer.json.decodeFromString<SyncSubscriptionsPackage>(json);
                    handleSyncSubscriptionPackage(this, subPackage);

                    val newestSub = subPackage.subscriptions.maxOf { it.creationTime };

                    val sesData = StateSync.instance.getSyncSessionData(remotePublicKey);
                    if(newestSub > sesData.lastSubscription) {
                        sesData.lastSubscription = newestSub;
                        StateSync.instance.saveSyncSessionData(sesData);
                    }
                }

                GJSyncOpcodes.syncSubscriptionGroups -> {
                    val dataBody = ByteArray(data.remaining());
                    data.get(dataBody);
                    val json = String(dataBody, Charsets.UTF_8);
                    val pack = Serializer.json.decodeFromString<SyncSubscriptionGroupsPackage>(json);

                    var lastSubgroupChange = OffsetDateTime.MIN;
                    for(group in pack.groups){
                        if(group.lastChange > lastSubgroupChange)
                            lastSubgroupChange = group.lastChange;

                        val existing = StateSubscriptionGroups.instance.getSubscriptionGroup(group.id);

                        if(existing == null)
                            StateSubscriptionGroups.instance.updateSubscriptionGroup(group, false, true);
                        else if(existing.lastChange < group.lastChange) {
                            existing.name = group.name;
                            existing.urls = group.urls;
                            existing.image = group.image;
                            existing.priority = group.priority;
                            existing.lastChange = group.lastChange;
                            StateSubscriptionGroups.instance.updateSubscriptionGroup(existing, false, true);
                        }
                    }
                    for(removal in pack.groupRemovals) {
                        val creation = StateSubscriptionGroups.instance.getSubscriptionGroup(removal.key);
                        val removalTime = OffsetDateTime.ofInstant(Instant.ofEpochSecond(removal.value, 0), ZoneOffset.UTC);
                        if(creation != null && creation.creationTime < removalTime)
                            StateSubscriptionGroups.instance.deleteSubscriptionGroup(removal.key, false);
                    }
                }

                GJSyncOpcodes.syncPlaylists -> {
                    val dataBody = ByteArray(data.remaining());
                    data.get(dataBody);
                    val json = String(dataBody, Charsets.UTF_8);
                    val pack = Serializer.json.decodeFromString<SyncPlaylistsPackage>(json);

                    for(playlist in pack.playlists) {
                        val existing = StatePlaylists.instance.getPlaylist(playlist.id);

                        if(existing == null)
                            StatePlaylists.instance.createOrUpdatePlaylist(playlist, false);
                        else if(existing.dateUpdate.toLocalDateTime() < playlist.dateUpdate.toLocalDateTime()) {
                            existing.dateUpdate = playlist.dateUpdate;
                            existing.name = playlist.name;
                            existing.videos = playlist.videos;
                            existing.dateCreation = playlist.dateCreation;
                            existing.datePlayed = playlist.datePlayed;
                            StatePlaylists.instance.createOrUpdatePlaylist(existing, false);
                        }
                    }
                    for(removal in pack.playlistRemovals) {
                        val creation = StatePlaylists.instance.getPlaylist(removal.key);
                        val removalTime = OffsetDateTime.ofInstant(Instant.ofEpochSecond(removal.value, 0), ZoneOffset.UTC);
                        if(creation != null && creation.dateCreation < removalTime)
                            StatePlaylists.instance.removePlaylist(creation, false);

                    }
                }

                GJSyncOpcodes.syncHistory -> {
                    val dataBody = ByteArray(data.remaining());
                    data.get(dataBody);
                    val json = String(dataBody, Charsets.UTF_8);
                    val history = Serializer.json.decodeFromString<List<HistoryVideo>>(json);
                    Logger.i(TAG, "SyncHistory received ${history.size} videos from ${remotePublicKey}");

                    var lastHistory = OffsetDateTime.MIN;
                    for(video in history){
                        val hist = StateHistory.instance.getHistoryByVideo(video.video, true, video.date);
                        if(hist != null)
                            StateHistory.instance.updateHistoryPosition(video.video, hist, true, video.position, video.date)
                        if(lastHistory < video.date)
                            lastHistory = video.date;
                    }

                    if(lastHistory != OffsetDateTime.MIN && history.size > 1) {
                        val sesData = StateSync.instance.getSyncSessionData(remotePublicKey);
                        if (lastHistory > sesData.lastHistory) {
                            sesData.lastHistory = lastHistory;
                            StateSync.instance.saveSyncSessionData(sesData);
                        }
                    }
                }
            }
        }
        catch(ex: Exception) {
            Logger.w(TAG, "Failed to handle sync package ${opcode}: ${ex.message}", ex);
        }
    }

    private fun handleSyncSubscriptionPackage(origin: SyncSession, pack: SyncSubscriptionsPackage) {
        val added = mutableListOf<Subscription>()
        for(sub in pack.subscriptions) {
            if(!StateSubscriptions.instance.isSubscribed(sub.channel)) {
                val removalTime = StateSubscriptions.instance.getSubscriptionRemovalTime(sub.channel.url);
                if(sub.creationTime > removalTime) {
                    val newSub = StateSubscriptions.instance.addSubscription(sub.channel, sub.creationTime);
                    added.add(newSub);
                }
            }
        }
        if(added.size > 3)
            UIDialogs.appToast("${added.size} Subscriptions from ${origin.remotePublicKey.substring(0, Math.min(8, origin.remotePublicKey.length))}");
        else if(added.size > 0)
            UIDialogs.appToast("Subscriptions from ${origin.remotePublicKey.substring(0, Math.min(8, origin.remotePublicKey.length))}:\n" +
                added.map { it.channel.name }.joinToString("\n"));


        if(pack.subscriptions != null && pack.subscriptions.size > 0) {
            for (subRemoved in pack.subscriptionRemovals) {
                val removed = StateSubscriptions.instance.applySubscriptionRemovals(pack.subscriptionRemovals);
                if(removed.size > 3)
                    UIDialogs.appToast("Removed ${removed.size} Subscriptions from ${origin.remotePublicKey.substring(0, Math.min(8, origin.remotePublicKey.length))}");
                else if(removed.size > 0)
                    UIDialogs.appToast("Subscriptions removed from ${origin.remotePublicKey.substring(0, Math.min(8, origin.remotePublicKey.length))}:\n" +
                            removed.map { it.channel.name }.joinToString("\n"));

            }
        }
    }


    inline fun <reified T> sendJsonData(subOpcode: UByte, data: T) {
        send(Opcode.DATA.value, subOpcode, Json.encodeToString<T>(data));
    }
    fun sendData(subOpcode: UByte, data: String) {
        send(Opcode.DATA.value, subOpcode, data.toByteArray(Charsets.UTF_8));
    }
    fun send(opcode: UByte, subOpcode: UByte, data: String) {
        send(opcode, subOpcode, data.toByteArray(Charsets.UTF_8));
    }
    fun send(opcode: UByte, subOpcode: UByte, data: ByteArray) {
        val sock = _socketSessions.firstOrNull();
        if(sock != null){
            sock.send(opcode, subOpcode, ByteBuffer.wrap(data));
        }
        else
            throw IllegalStateException("Session has no active sockets");
    }

    private companion object {
        const val TAG = "SyncSession"
    }
}