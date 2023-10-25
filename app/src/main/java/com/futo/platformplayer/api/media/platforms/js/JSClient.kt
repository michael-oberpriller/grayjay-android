package com.futo.platformplayer.api.media.platforms.js

import android.content.Context
import com.caoccao.javet.values.V8Value
import com.caoccao.javet.values.primitive.V8ValueBoolean
import com.caoccao.javet.values.primitive.V8ValueInteger
import com.caoccao.javet.values.primitive.V8ValueNull
import com.caoccao.javet.values.primitive.V8ValueString
import com.caoccao.javet.values.reference.V8ValueArray
import com.caoccao.javet.values.reference.V8ValueObject
import com.futo.platformplayer.states.StatePlatform
import com.futo.platformplayer.states.StatePlugins
import com.futo.platformplayer.api.media.IPlatformClient
import com.futo.platformplayer.api.media.PlatformClientCapabilities
import com.futo.platformplayer.api.media.models.PlatformAuthorLink
import com.futo.platformplayer.api.media.models.ResultCapabilities
import com.futo.platformplayer.api.media.models.channels.IPlatformChannel
import com.futo.platformplayer.api.media.models.chapters.IChapter
import com.futo.platformplayer.api.media.models.comments.IPlatformComment
import com.futo.platformplayer.api.media.models.contents.IPlatformContent
import com.futo.platformplayer.api.media.models.contents.IPlatformContentDetails
import com.futo.platformplayer.api.media.models.live.ILiveChatWindowDescriptor
import com.futo.platformplayer.api.media.models.live.IPlatformLiveEvent
import com.futo.platformplayer.api.media.models.playback.IPlaybackTracker
import com.futo.platformplayer.api.media.models.playlists.IPlatformPlaylistDetails
import com.futo.platformplayer.api.media.platforms.js.internal.*
import com.futo.platformplayer.api.media.platforms.js.models.*
import com.futo.platformplayer.api.media.structures.EmptyPager
import com.futo.platformplayer.api.media.structures.IPager
import com.futo.platformplayer.constructs.Event1
import com.futo.platformplayer.constructs.Event2
import com.futo.platformplayer.engine.V8Plugin
import com.futo.platformplayer.engine.exceptions.PluginEngineException
import com.futo.platformplayer.engine.exceptions.PluginEngineStoppedException
import com.futo.platformplayer.engine.exceptions.ScriptCaptchaRequiredException
import com.futo.platformplayer.engine.exceptions.ScriptImplementationException
import com.futo.platformplayer.engine.exceptions.ScriptValidationException
import com.futo.platformplayer.logging.Logger
import com.futo.platformplayer.models.ImageVariable
import com.futo.platformplayer.states.AnnouncementType
import com.futo.platformplayer.states.StateAnnouncement
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime
import kotlin.reflect.full.findAnnotations
import kotlin.reflect.jvm.kotlinFunction

open class JSClient : IPlatformClient {
    val config: SourcePluginConfig;
    protected val _context: Context;
    private val _plugin: V8Plugin;
    private val plugin: V8Plugin get() = _plugin ?: throw IllegalStateException("Client not enabled");

    var descriptor: SourcePluginDescriptor
        private set;

    private val _client: JSHttpClient;
    private val _clientAuth: JSHttpClient?;
    private var _searchCapabilities: ResultCapabilities? = null;
    private var _searchChannelContentsCapabilities: ResultCapabilities? = null;
    private var _channelCapabilities: ResultCapabilities? = null;

    protected val _script: String;

    private var _initialized: Boolean = false;
    private var _enabled: Boolean = false;

    private val _auth: SourceAuth?;
    private val _captcha: SourceCaptchaData?;

    private val _injectedSaveState: String?;

    override val id: String get() = config.id;
    override val name: String get() = config.name;
    override val icon: ImageVariable;
    override var capabilities: PlatformClientCapabilities = PlatformClientCapabilities();

    private val _busyLock = Object();
    private var _busyCounter = 0;
    val isBusy: Boolean get() = _busyCounter > 0;

    val settings: HashMap<String, String?> get() = descriptor.settings;

    val flags: Array<String>;

    var channelClaimTemplates: Map<Int, Map<Int, String>>? = null
        private set;

    val isLoggedIn: Boolean get() = _auth != null;
    val isEnabled: Boolean get() = _enabled;

    val enableInSearch get() = descriptor.appSettings.tabEnabled.enableSearch ?: true
    val enableInHome get() = descriptor.appSettings.tabEnabled.enableHome ?: true

    val onDisabled = Event1<JSClient>();
    val onCaptchaException = Event2<JSClient, ScriptCaptchaRequiredException>();

    constructor(context: Context, descriptor: SourcePluginDescriptor, saveState: String? = null) {
        this._context = context;
        this.config = descriptor.config;
        icon = StatePlatform.instance.getPlatformIcon(config.id) ?: ImageVariable(config.absoluteIconUrl, null, null);
        this.descriptor = descriptor;
        _injectedSaveState = saveState;
        _auth = descriptor.getAuth();
        _captcha = descriptor.getCaptchaData();
        flags = descriptor.flags.toTypedArray();

        _client = JSHttpClient(this, null, _captcha);
        _clientAuth = JSHttpClient(this, _auth, _captcha);
        _plugin = V8Plugin(context, descriptor.config, null, _client, _clientAuth);
        _plugin.withDependency(context, "scripts/polyfil.js");
        _plugin.withDependency(context, "scripts/source.js");

        val script = StatePlugins.instance.getScript(descriptor.config.id);
        if(script != null) {
            _script = script;
            _plugin.withScript(script);
        }
        else
            throw IllegalStateException("Script for plugin [${descriptor.config.name}] was not available");

        _plugin.onScriptException.subscribe {
            if(it is ScriptCaptchaRequiredException)
                onCaptchaException.emit(this, it);
        };
    }
    constructor(context: Context, descriptor: SourcePluginDescriptor, saveState: String?, script: String) {
        this._context = context;
        this.config = descriptor.config;
        icon = StatePlatform.instance.getPlatformIcon(config.id) ?: ImageVariable(config.absoluteIconUrl, null, null);
        this.descriptor = descriptor;
        _injectedSaveState = saveState;
        _auth = descriptor.getAuth();
        _captcha = descriptor.getCaptchaData();
        flags = descriptor.flags.toTypedArray();

        _client = JSHttpClient(this, null, _captcha);
        _clientAuth = JSHttpClient(this, _auth, _captcha);
        _plugin = V8Plugin(context, descriptor.config, script, _client, _clientAuth);
        _plugin.withDependency(context, "scripts/polyfil.js");
        _plugin.withDependency(context, "scripts/source.js");
        _plugin.withScript(script);
        _script = script;

        _plugin.onScriptException.subscribe {
            if(it is ScriptCaptchaRequiredException)
                onCaptchaException.emit(this, it);
        };
    }

    open fun getCopy(): JSClient {
        return JSClient(_context, descriptor, saveState(), _script);
    }

    fun getUnderlyingPlugin(): V8Plugin {
        return _plugin;
    }

    override fun initialize() {
        Logger.i(TAG, "Plugin [${config.name}] initializing");
        plugin.start();
        plugin.execute("plugin.config = ${Json.encodeToString(config)}");
        plugin.execute("plugin.settings = parseSettings(${Json.encodeToString(descriptor.getSettingsWithDefaults())})");

        descriptor.appSettings.loadDefaults(descriptor.config);

        _initialized = true;

        capabilities = PlatformClientCapabilities(
            hasChannelSearch = plugin.executeBoolean("!!source.searchChannels") ?: false,
            hasGetUserSubscriptions = plugin.executeBoolean("!!source.getUserSubscriptions") ?: false,
            hasGetComments = plugin.executeBoolean("!!source.getComments") ?: false,
            hasSearchPlaylists = (plugin.executeBoolean("!!source.searchPlaylists") ?: false),
            hasGetPlaylist = (plugin.executeBoolean("!!source.getPlaylist") ?: false) && (plugin.executeBoolean("!!source.isPlaylistUrl") ?: false),
            hasGetUserPlaylists = plugin.executeBoolean("!!source.getUserPlaylists") ?: false,
            hasSearchChannelContents = plugin.executeBoolean("!!source.searchChannelContents") ?: false,
            hasSaveState = plugin.executeBoolean("!!source.saveState") ?: false,
            hasGetPlaybackTracker = plugin.executeBoolean("!!source.getPlaybackTracker") ?: false,
            hasGetChannelUrlByClaim = plugin.executeBoolean("!!source.getChannelUrlByClaim") ?: false,
            hasGetChannelTemplateByClaimMap = plugin.executeBoolean("!!source.getChannelTemplateByClaimMap") ?: false,
            hasGetSearchCapabilities = plugin.executeBoolean("!!source.getSearchCapabilities") ?: false,
            hasGetChannelCapabilities = plugin.executeBoolean("!!source.getChannelCapabilities") ?: false,
            hasGetLiveEvents = plugin.executeBoolean("!!source.getLiveEvents") ?: false,
            hasGetLiveChatWindow = plugin.executeBoolean("!!source.getLiveChatWindow") ?: false,
            hasGetContentChapters = plugin.executeBoolean("!!source.getContentChapters") ?: false,
        );

        try {
            if (capabilities.hasGetChannelTemplateByClaimMap)
                getChannelTemplateByClaimMap();
        }
        catch(ex: Throwable) { }
    }
    fun ensureEnabled() {
        if(!_enabled)
            enable();
    }

    @JSDocs(0, "source.enable()", "Called when the plugin is enabled/started")
    fun enable() {
        if(!_initialized)
            initialize();
        plugin.execute("source.enable(${Json.encodeToString(config)}, parseSettings(${Json.encodeToString(descriptor.getSettingsWithDefaults())}), ${Json.encodeToString(_injectedSaveState)})");
        _enabled = true;
    }
    @JSDocs(1, "source.saveState()", "Provide a string that is passed to enable for quicker startup of multiple instances")
    fun saveState(): String? {
        ensureEnabled();
        if(!capabilities.hasSaveState)
            return null;
        val resp = plugin.executeTyped<V8ValueString>("source.saveState()").value;
        return resp;
    }

    @JSDocs(1, "source.disable()", "Called before the plugin is disabled/stopped")
    override fun disable() {
        Logger.i(TAG, "Disabling plugin [${name}] (Enabled: ${_enabled}, Initialized: ${_initialized})");
        if(_enabled)
            ;//TODO: Disable?
        _enabled = false;
        if(_initialized)
            _plugin.stop();
        _initialized = false;

        onDisabled.emit(this);
    }

    @JSDocs(2, "source.getHome()", "Gets the HomeFeed of the platform")
    override fun getHome(): IPager<IPlatformContent> = isBusyWith {
        ensureEnabled();
        return@isBusyWith JSContentPager(config, plugin,
            plugin.executeTyped("source.getHome()"));
    }

    @JSDocs(3, "source.searchSuggestions(query)", "Gets search suggestions for a given query")
    @JSDocsParameter("query", "Query to complete suggestions for")
    override fun searchSuggestions(query: String): Array<String> = isBusyWith {
        ensureEnabled();
        return@isBusyWith plugin.executeTyped<V8ValueArray>("source.searchSuggestions(${Json.encodeToString(query)})")
            .toArray()
            .map { (it as V8ValueString).value }
            .toTypedArray();
    }
    @JSDocs(4, "source.getSearchCapabilities()", "Gets capabilities this plugin has for search contents")
    override fun getSearchCapabilities(): ResultCapabilities {
        if(!capabilities.hasGetSearchCapabilities)
            return ResultCapabilities(listOf(ResultCapabilities.TYPE_MIXED));
        try {
            if (_searchCapabilities != null) {
                return _searchCapabilities!!;
            }

            _searchCapabilities = ResultCapabilities.fromV8(config, plugin.executeTyped("source.getSearchCapabilities()"));
            return _searchCapabilities!!;
        }
        catch(ex: Throwable) {
            announcePluginUnhandledException("getSearchCapabilities", ex);
            return ResultCapabilities(listOf(ResultCapabilities.TYPE_MIXED));
        }
    }
    @JSDocs(5, "source.search(query)", "Searches for contents on the platform")
    @JSDocsParameter("query", "Query that search results should match")
    @JSDocsParameter("type", "(optional) Type of contents to get from search ")
    @JSDocsParameter("order", "(optional) Order in which contents should be returned")
    @JSDocsParameter("filters", "(optional) Filters to apply on contents")
    @JSDocsParameter("channelId", "(optional) Channel id to search in")
    override fun search(query: String, type: String?, order: String?, filters: Map<String, List<String>>?): IPager<IPlatformContent> = isBusyWith {
        ensureEnabled();
        return@isBusyWith JSContentPager(config, plugin,
            plugin.executeTyped("source.search(${Json.encodeToString(query)}, ${Json.encodeToString(type)}, ${Json.encodeToString(order)}, ${Json.encodeToString(filters)})"));
    }

    @JSDocs(4, "source.getSearchChannelContentsCapabilities()", "Gets capabilities this plugin has for search videos")
    override fun getSearchChannelContentsCapabilities(): ResultCapabilities {
        ensureEnabled();
        if (_searchChannelContentsCapabilities != null)
            return _searchChannelContentsCapabilities!!;

        _searchChannelContentsCapabilities = ResultCapabilities.fromV8(config, plugin.executeTyped("source.getSearchChannelContentsCapabilities()"));
        return _searchChannelContentsCapabilities!!;
    }
    @JSDocs(5, "source.searchChannelContents(query)", "Searches for videos on the platform")
    @JSDocsParameter("channelUrl", "Channel url to search")
    @JSDocsParameter("query", "Query that search results should match")
    @JSDocsParameter("type", "(optional) Type of contents to get from search ")
    @JSDocsParameter("order", "(optional) Order in which contents should be returned")
    @JSDocsParameter("filters", "(optional) Filters to apply on contents")
    override fun searchChannelContents(channelUrl: String, query: String, type: String?, order: String?, filters: Map<String, List<String>>?): IPager<IPlatformContent> = isBusyWith {
        ensureEnabled();
        if(!capabilities.hasSearchChannelContents)
            throw IllegalStateException("This plugin does not support channel search");

        return@isBusyWith JSContentPager(config, plugin,
            plugin.executeTyped("source.searchChannelContents(${Json.encodeToString(channelUrl)}, ${Json.encodeToString(query)}, ${Json.encodeToString(type)}, ${Json.encodeToString(order)}, ${Json.encodeToString(filters)})"));
    }

    @JSOptional
    @JSDocs(5, "source.searchChannels(query)",  "Searches for channels on the platform")
    @JSDocsParameter("query", "Query that channels should match")
    override fun searchChannels(query: String): IPager<PlatformAuthorLink> = isBusyWith {
        ensureEnabled();
        return@isBusyWith JSChannelPager(config, plugin,
            plugin.executeTyped("source.searchChannels(${Json.encodeToString(query)})"));
    }

    @JSDocs(6, "source.isChannelUrl(url)", "Validates if an channel url is for this platform")
    @JSDocsParameter("url", "A channel url (May not be your platform)")
    override fun isChannelUrl(url: String): Boolean {
        try {
            return plugin.executeTyped<V8ValueBoolean>("source.isChannelUrl(${Json.encodeToString(url)})")
                .value;
        }
        catch(ex: Throwable) {
            announcePluginUnhandledException("isChannelUrl", ex);
            return false;
        }
    }
    @JSDocs(7, "source.getChannel(channelUrl)", "Gets a channel by its url")
    @JSDocsParameter("channelUrl", "A channel url (this platform)")
    override fun getChannel(channelUrl: String): IPlatformChannel = isBusyWith {
        ensureEnabled();
        return@isBusyWith JSChannel(config,
            plugin.executeTyped("source.getChannel(${Json.encodeToString(channelUrl)})"));
    }
    @JSDocs(8, "source.getChannelCapabilities()", "Gets capabilities this plugin has for channel contents")
    override fun getChannelCapabilities(): ResultCapabilities {
        if(!capabilities.hasGetChannelCapabilities)
            return ResultCapabilities(listOf(ResultCapabilities.TYPE_MIXED));
        try {
            if (_channelCapabilities != null) {
                return _channelCapabilities!!;
            }

            _channelCapabilities = ResultCapabilities.fromV8(config, plugin.executeTyped("source.getChannelCapabilities()"));
            return _channelCapabilities!!;
        }
        catch(ex: Throwable) {
            announcePluginUnhandledException("getChannelCapabilities", ex);
            return ResultCapabilities(listOf(ResultCapabilities.TYPE_MIXED));
        }
    }
    @JSDocs(10, "source.getChannelContents(url, type, order, filters)", "Gets contents of a channel (reverse chronological order)")
    @JSDocsParameter("channelUrl", "A channel url (this platform)")
    @JSDocsParameter("type", "(optional) Type of contents to get from channel")
    @JSDocsParameter("order", "(optional) Order in which contents should be returned")
    @JSDocsParameter("filters", "(optional) Filters to apply on contents")
    override fun getChannelContents(channelUrl: String, type: String?, order: String?, filters: Map<String, List<String>>?): IPager<IPlatformContent> = isBusyWith {
        ensureEnabled();
        return@isBusyWith JSContentPager(config, plugin,
            plugin.executeTyped("source.getChannelContents(${Json.encodeToString(channelUrl)}, ${Json.encodeToString(type)}, ${Json.encodeToString(order)}, ${Json.encodeToString(filters)})"));
    }

    @JSOptional
    @JSDocs(11, "source.getChannelUrlByClaim(claimType, claimValues)", "Gets the channel url that should be used to fetch a given polycentric claim")
    @JSDocsParameter("claimType", "Polycentric claimtype id")
    @JSDocsParameter("claimValues", "A map of values associated with the claim")
    override fun getChannelUrlByClaim(claimType: Int, claimValues: Map<Int, String>): String? {
        if(!capabilities.hasGetChannelUrlByClaim)
            throw IllegalStateException("This plugin does not support channel url by claim");

        val value = plugin.executeTyped<V8Value>("source.getChannelUrlByClaim(${claimType}, ${Json.encodeToString(claimValues)})");
        if(value !is V8ValueString)
            return null;
        return value.value;
    }
    @JSOptional
    @JSDocs(12, "source.getChannelTemplateByClaimMap()", "Get a map for every supported claimtype mapping field to urls")
    @JSDocsParameter("claimType", "Polycentric claimtype id")
    @JSDocsParameter("claimValues", "A map of values associated with the claim")
    fun getChannelTemplateByClaimMap(): Map<Int,Map<Int, String>>{
        if(!capabilities.hasGetChannelTemplateByClaimMap)
            throw IllegalStateException("This plugin does not support channel template by claim map");

        val value = plugin.executeTyped<V8Value>("source.getChannelTemplateByClaimMap()");
        if(value !is V8ValueObject)
            return mapOf();

        val claimTypes = mutableMapOf<Int, Map<Int, String>>();

        val keys = value.ownPropertyNames;
        for(key in keys.toArray()) {
            if(key is V8ValueInteger) {
                val map = value.get<V8ValueObject>(key);
                val mapKeys = map.ownPropertyNames;

                claimTypes[key.value] = mapKeys.toArray().filter {
                    it is V8ValueInteger
                }.associate {
                    val mapKey = (it as V8ValueInteger).value;
                    return@associate Pair(mapKey, map.getString(mapKey));
                };
            }
        }
        channelClaimTemplates = claimTypes.toMap();
        return claimTypes;
    }


    @JSDocs(13, "source.isContentDetailsUrl(url)", "Validates if an content url is for this platform")
    @JSDocsParameter("url", "A content url (May not be your platform)")
    override fun isContentDetailsUrl(url: String): Boolean {
        try {
            return plugin.executeTyped<V8ValueBoolean>("source.isContentDetailsUrl(${Json.encodeToString(url)})")
                .value;
        }
        catch(ex: Throwable) {
            announcePluginUnhandledException("isContentDetailsUrl", ex);
            return false;
        }
    }
    @JSDocs(14, "source.getContentDetails(url)", "Gets content details by its url")
    @JSDocsParameter("url", "A content url (this platform)")
    override fun getContentDetails(url: String): IPlatformContentDetails = isBusyWith {
        ensureEnabled();
        return@isBusyWith IJSContentDetails.fromV8(config,
            plugin.executeTyped("source.getContentDetails(${Json.encodeToString(url)})"));
    }

    @JSOptional //getContentChapters = function(url, initialData)
    @JSDocs(15, "source.getContentChapters(url)", "Gets chapters for content details")
    @JSDocsParameter("url", "A content url (this platform)")
    override fun getContentChapters(url: String): List<IChapter> = isBusyWith {
        if(!capabilities.hasGetContentChapters)
            return@isBusyWith listOf();
        ensureEnabled();
        return@isBusyWith JSChapter.fromV8(config,
            plugin.executeTyped("source.getContentChapters(${Json.encodeToString(url)})"));
    }

    @JSOptional
    @JSDocs(15, "source.getPlaybackTracker(url)", "Gets a playback tracker for given content url")
    @JSDocsParameter("url", "A content url (this platform)")
    override fun getPlaybackTracker(url: String): IPlaybackTracker? = isBusyWith {
        if(!capabilities.hasGetPlaybackTracker)
            return@isBusyWith null;
        ensureEnabled();
        Logger.i(TAG, "JSClient.getPlaybackTracker(${url})");
        val tracker = plugin.executeTyped<V8Value>("source.getPlaybackTracker(${Json.encodeToString(url)})");
        if(tracker is V8ValueObject)
            return@isBusyWith JSPlaybackTracker(config, tracker);
        else
            return@isBusyWith null;
    }

    @JSDocs(16, "source.getComments(url)", "Gets comments for a content by its url")
    @JSDocsParameter("url", "A content url (this platform)")
    override fun getComments(url: String): IPager<IPlatformComment> = isBusyWith {
        ensureEnabled();
        val pager = plugin.executeTyped<V8Value>("source.getComments(${Json.encodeToString(url)})");
        if (pager !is V8ValueObject) { //TODO: Maybe solve this better
            return@isBusyWith EmptyPager<IPlatformComment>();
        }
        return@isBusyWith JSCommentPager(config, plugin, pager);
    }
    @JSDocs(17, "source.getSubComments(comment)", "Gets replies for a given comment")
    @JSDocsParameter("comment", "Comment object that was returned by getComments")
    override fun getSubComments(comment: IPlatformComment): IPager<IPlatformComment> {
        ensureEnabled();
        return comment.getReplies(this) ?: JSCommentPager(config, plugin,
                plugin.executeTyped("source.getSubComments(${Json.encodeToString(comment as JSComment)})"));
    }

    @JSDocs(16, "source.getLiveChatWindow(url)", "Gets live events for a livestream")
    @JSDocsParameter("url", "Url of live stream")
    override fun getLiveChatWindow(url: String): ILiveChatWindowDescriptor? = isBusyWith {
        if(!capabilities.hasGetLiveChatWindow)
            return@isBusyWith null;
        ensureEnabled();
        return@isBusyWith JSLiveChatWindowDescriptor(config,
            plugin.executeTyped("source.getLiveChatWindow(${Json.encodeToString(url)})"));
    }
    @JSDocs(16, "source.getLiveEvents(url)", "Gets live events for a livestream")
    @JSDocsParameter("url", "Url of live stream")
    override fun getLiveEvents(url: String): IPager<IPlatformLiveEvent>? = isBusyWith {
        if(!capabilities.hasGetLiveEvents)
            return@isBusyWith null;
        ensureEnabled();
        return@isBusyWith JSLiveEventPager(config, plugin,
            plugin.executeTyped("source.getLiveEvents(${Json.encodeToString(url)})"));
    }
    @JSDocs(19, "source.searchPlaylists(query)", "Searches for playlists on the platform")
    @JSDocsParameter("query", "Query that search results should match")
    @JSDocsParameter("type", "(optional) Type of contents to get from search ")
    @JSDocsParameter("order", "(optional) Order in which contents should be returned")
    @JSDocsParameter("filters", "(optional) Filters to apply on contents")
    @JSDocsParameter("channelId", "(optional) Channel id to search in")
    override fun searchPlaylists(query: String, type: String?, order: String?, filters: Map<String, List<String>>?): IPager<IPlatformContent> = isBusyWith {
        ensureEnabled();
        if(!capabilities.hasSearchPlaylists)
            throw IllegalStateException("This plugin does not support playlist search");
        return@isBusyWith JSContentPager(config, plugin, plugin.executeTyped("source.searchPlaylists(${Json.encodeToString(query)}, ${Json.encodeToString(type)}, ${Json.encodeToString(order)}, ${Json.encodeToString(filters)})"));
    }
    @JSOptional
    @JSDocs(20, "source.isPlaylistUrl(url)", "Validates if a playlist url is for this platform")
    @JSDocsParameter("url", "Url of playlist")
    override fun isPlaylistUrl(url: String): Boolean {
        ensureEnabled();
        if (!capabilities.hasGetPlaylist)
            return false;
        return plugin.executeBoolean("source.isPlaylistUrl(${Json.encodeToString(url)})") ?: false;
    }
    @JSOptional
    @JSDocs(21, "source.getPlaylist(url)", "Gets the playlist of the current user")
    @JSDocsParameter("url", "Url of playlist")
    override fun getPlaylist(url: String): IPlatformPlaylistDetails = isBusyWith {
        ensureEnabled();
        return@isBusyWith JSPlaylistDetails(plugin, plugin.config as SourcePluginConfig, plugin.executeTyped("source.getPlaylist(${Json.encodeToString(url)})"));
    }

    @JSOptional
    @JSDocs(22, "source.getUserPlaylists()", "Gets the playlist of the current user")
    override fun getUserPlaylists(): Array<String> {
        ensureEnabled();
        return plugin.executeTyped<V8ValueArray>("source.getUserPlaylists()")
            .toArray()
            .map { (it as V8ValueString).value }
            .toTypedArray();
    }

    @JSOptional
    @JSDocs(23, "source.getUserSubscriptions()", "Gets the subscriptions of the current user")
    override fun getUserSubscriptions(): Array<String> {
        ensureEnabled();
        return plugin.executeTyped<V8ValueArray>("source.getUserSubscriptions()")
            .toArray()
            .map { (it as V8ValueString).value }
            .toTypedArray();
    }

    fun validate() {
        try {
            plugin.start();

            validateFunction("source.getHome");
            //validateFunction("source.getSearchCapabilities");
            validateFunction("source.search");
            validateFunction("source.isChannelUrl");
            validateFunction("source.getChannel");
            //validateFunction("source.getChannelCapabilities");
            validateFunction("source.getChannelContents");
            validateFunction("source.isContentDetailsUrl");
            validateFunction("source.getContentDetails");
        }
        finally {
            plugin.stop()
        }
    }
    private fun validateFunction(funcName: String) {
        if(plugin.executeBoolean("typeof ${funcName} == 'function'") != true)
            throw ScriptValidationException("Validation\n[function ${funcName} not available]");
    }


    fun validateUrlOrThrow(url: String) {
        val allowed = config.isUrlAllowed(url);
        if(!allowed)
            throw ScriptImplementationException(config, "Attempted to access non-whitelisted url: ${url}");
    }

    override fun isClaimTypeSupported(claimType: Int): Boolean {
        return capabilities.hasGetChannelTemplateByClaimMap && config.supportedClaimTypes.contains(claimType)
    }
    fun isClaimTemplateMapSupported(claimType: Int, map: Map<Int,String>): Boolean {
        return capabilities.hasGetChannelTemplateByClaimMap && channelClaimTemplates?.let {
            it.containsKey(claimType) && it[claimType]!!.any { map.containsKey(it.key) };
        } ?: false;
    }

    fun resolveChannelUrlByClaimTemplates(claimType: Int, values: Map<Int, String>): String? {
        return channelClaimTemplates?.let {
            if(it.containsKey(claimType)) {
                val templates = it[claimType];
                if(templates != null)
                    for(value in values.keys.sortedBy { it }) {
                        if(templates.containsKey(value)) {
                            return templates[value]!!.replace("{{CLAIMVALUE}}", values[value]!!);
                        }
                    }
            }
            return null;
        };
    }

    fun resolveChannelUrlsByClaimTemplates(claimType: Int, values: Map<Int, String>): List<String> {
        val urls = arrayListOf<String>();
        channelClaimTemplates?.let {
            if(it.containsKey(claimType)) {
                val templates = it[claimType];
                if(templates != null)
                    for(value in values.keys.sortedBy { it }) {
                        if(templates.containsKey(value)) {
                            urls.add(templates[value]!!.replace("{{CLAIMVALUE}}", values[value]!!));
                        }
                    }
            }
        };

        return urls;
    }


    private fun <T> isBusyWith(handle: ()->T): T {
        try {
            synchronized(_busyLock) {
                _busyCounter++;
            }
            return handle();
        }
        finally {
            synchronized(_busyLock) {
                _busyCounter--;
            }
        }
    }

    private fun announcePluginUnhandledException(method: String, ex: Throwable) {
        if(ex is PluginEngineException)
            return;
        try {
            StateAnnouncement.instance.registerAnnouncement("PluginUnhandled_${config.id}_${method}",
                "Plugin ${config.name} encountered an error in [${method}]",
                "${ex.message}\nPlease contact the plugin developer",
                AnnouncementType.SESSION_RECURRING,
                OffsetDateTime.now());
        }
        catch(_: Throwable) {}
    }

    companion object {
        val TAG = "JSClient";

        fun getJSDocs(): List<JSCallDocs> {
            val docs = mutableListOf<JSCallDocs>();
            val methods = JSClient::class.java.declaredMethods.filter { it.getAnnotation(JSDocs::class.java) != null }
            for(method in methods.sortedBy { it.getAnnotation(JSDocs::class.java)?.order }) {
                val doc = method.getAnnotation(JSDocs::class.java);
                val parameters = method.kotlinFunction!!.findAnnotations<JSDocsParameter>();
                val isOptional = method.kotlinFunction!!.findAnnotations<JSOptional>().isNotEmpty();

                docs.add(JSCallDocs(method.name, doc.code, doc.description, parameters
                    .sortedBy { it.order }
                    .map{ JSParameterDocs(it.name, it.description) }
                    .toList(), isOptional));
            }
            return docs;
        }
    }
}