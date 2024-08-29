package com.futo.platformplayer.api.media.platforms.js.models.sources

import com.caoccao.javet.values.V8Value
import com.caoccao.javet.values.primitive.V8ValueString
import com.caoccao.javet.values.reference.V8ValueObject
import com.futo.platformplayer.api.media.models.streams.sources.IDashManifestSource
import com.futo.platformplayer.api.media.models.streams.sources.IVideoSource
import com.futo.platformplayer.api.media.models.streams.sources.IVideoUrlSource
import com.futo.platformplayer.api.media.platforms.js.DevJSClient
import com.futo.platformplayer.api.media.platforms.js.JSClient
import com.futo.platformplayer.engine.IV8PluginConfig
import com.futo.platformplayer.engine.V8Plugin
import com.futo.platformplayer.getOrDefault
import com.futo.platformplayer.getOrNull
import com.futo.platformplayer.getOrThrow
import com.futo.platformplayer.states.StateDeveloper

interface IJSDashManifestRawSource {
    val hasGenerate: Boolean;
    var manifest: String?;
    fun generate(): String?;
}
open class JSDashManifestRawSource: JSSource, IVideoSource, IJSDashManifestRawSource {
    override val container : String = "application/dash+xml";
    override val name : String;
    override val width: Int;
    override val height: Int;
    override val codec: String;
    override val bitrate: Int?;
    override val duration: Long;
    override val priority: Boolean;

    var url: String?;
    override var manifest: String?;

    override val hasGenerate: Boolean;
    val canMerge: Boolean;

    constructor(plugin: JSClient, obj: V8ValueObject) : super(TYPE_DASH_RAW, plugin, obj) {
        val contextName = "DashRawSource";
        val config = plugin.config;
        name = _obj.getOrThrow(config, "name", contextName);
        url = _obj.getOrThrow(config, "url", contextName);
        manifest = _obj.getOrDefault<String>(config, "manifest", contextName, null);
        width = _obj.getOrDefault(config, "width", contextName, 0) ?: 0;
        height = _obj.getOrDefault(config, "height", contextName, 0) ?: 0;
        codec = _obj.getOrDefault(config, "codec", contextName, "") ?: "";
        bitrate = _obj.getOrDefault(config, "bitrate", contextName, 0) ?: 0;
        duration = _obj.getOrDefault(config, "duration", contextName, 0) ?: 0;
        priority = _obj.getOrDefault(config, "priority", contextName, false) ?: false;
        canMerge = _obj.getOrDefault(config, "canMerge", contextName, false) ?: false;
        hasGenerate = _obj.has("generate");
    }

    override open fun generate(): String? {
        if(!hasGenerate)
            return manifest;
        if(_obj.isClosed)
            throw IllegalStateException("Source object already closed");
        if(_plugin is DevJSClient) {
            return StateDeveloper.instance.handleDevCall(_plugin.devID, "DashManifestRawSource.generate()") {
                _plugin.getUnderlyingPlugin().catchScriptErrors("DashManifestRaw.generate", "generate()", {
                    _obj.invokeString("generate");
                });
            }
        }
        else
            return _plugin.getUnderlyingPlugin().catchScriptErrors("DashManifestRaw.generate", "generate()", {
                _obj.invokeString("generate");
            });
    }
}

class JSDashManifestMergingRawSource(
    val video: JSDashManifestRawSource,
    val audio: JSDashManifestRawAudioSource): JSDashManifestRawSource(video.getUnderlyingPlugin()!!, video.getUnderlyingObject()!!), IVideoSource {

    override val name: String
        get() = video.name;
    override val bitrate: Int
        get() = (video.bitrate ?: 0) + audio.bitrate;
    override val codec: String
        get() = video.codec
    override val container: String
        get() = video.container
    override val duration: Long
        get() = video.duration;
    override val height: Int
        get() = video.height;
    override val width: Int
        get() = video.width;
    override val priority: Boolean
        get() = video.priority;

    override fun generate(): String? {
        val videoDash = video.generate();
        val audioDash = audio.generate();
        if(videoDash != null && audioDash == null) return videoDash;
        if(audioDash != null && videoDash == null) return audioDash;
        if(videoDash == null) return null;

        //TODO: Temporary simple solution..make more reliable version
        val audioAdaptationSet = adaptationSetRegex.find(audioDash!!);
        if(audioAdaptationSet != null) {
            return videoDash.replace("</AdaptationSet>", "</AdaptationSet>\n" + audioAdaptationSet.value)
        }
        else
            return videoDash;
    }

    companion object {
        private val adaptationSetRegex = Regex("<AdaptationSet.*?>.*?<\\/AdaptationSet>", RegexOption.DOT_MATCHES_ALL);
    }
}