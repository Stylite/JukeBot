/*
   Copyright 2018 Kromatic

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package jukebot;

import com.sedmelluq.discord.lavaplayer.container.MediaContainerRegistry;
import com.sedmelluq.discord.lavaplayer.jdaudp.NativeAudioSendFactory;
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.beam.BeamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.vimeo.VimeoAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.PlayerLibrary;
import com.sedmelluq.discord.lavaplayer.track.playback.NonAllocatingAudioFrameBuffer;
import jukebot.apis.ksoft.KSoftAPI;
import jukebot.apis.patreon.PatreonAPI;
import jukebot.apis.youtube.YouTubeAPI;
import jukebot.audio.AudioHandler;
import jukebot.audio.sourcemanagers.caching.CachingSourceManager;
import jukebot.audio.sourcemanagers.mixcloud.MixcloudAudioSourceManager;
import jukebot.audio.sourcemanagers.pornhub.PornHubAudioSourceManager;
import jukebot.audio.sourcemanagers.spotify.SpotifyAudioSourceManager;
import jukebot.framework.Command;
import jukebot.listeners.ActionWaiter;
import jukebot.listeners.CommandHandler;
import jukebot.listeners.EventHandler;
import jukebot.utils.Config;
import jukebot.utils.Helpers;
import jukebot.utils.RequestUtil;
import net.dv8tion.jda.api.JDAInfo;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.ApplicationInfo;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder;
import net.dv8tion.jda.api.sharding.ShardManager;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteJDBCLoader;

import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class JukeBot {

    /* Bot-Related*/
    public static final String VERSION = "6.5.7";

    public static final Long startTime = System.currentTimeMillis();
    public static Logger LOG = LoggerFactory.getLogger("JukeBot");
    public static Config config = new Config("config.properties");

    public static Long selfId = 0L;
    public static Long botOwnerId = 0L;
    public static boolean isSelfHosted = false;

    /* Operation-Related */
    public static final RequestUtil httpClient = new RequestUtil();
    public static PatreonAPI patreonApi;
    public static YouTubeAPI youTubeApi;
    public static KSoftAPI kSoftAPI;

    public static final ConcurrentHashMap<Long, AudioHandler> players = new ConcurrentHashMap<>();
    public static final ActionWaiter waiter = new ActionWaiter();
    public static CustomAudioPlayerManager playerManager = new CustomAudioPlayerManager();
    public static ShardManager shardManager;

    public static void main(final String[] args) throws Exception {
        Thread.currentThread().setName("JukeBot-Main");
        printBanner();

        Database.setupDatabase();
        setupAudioSystem();
        loadApis();

        RestAction.setPassContext(false);
        RestAction.setDefaultFailure((e) -> {
        });

        DefaultShardManagerBuilder shardManagerBuilder = new DefaultShardManagerBuilder()
                .setToken(config.getToken())
                .setShardsTotal(-1)
                .addEventListeners(new CommandHandler(), new EventHandler(), waiter)
                .setDisabledCacheFlags(EnumSet.of(
                        CacheFlag.EMOTE,
                        CacheFlag.ACTIVITY,
                        CacheFlag.CLIENT_STATUS
                ))
                .setGuildSubscriptionsEnabled(false)
                .setActivity(Activity.listening(config.getDefaultPrefix() + "help | https://jukebot.serux.pro"));

        final String os = System.getProperty("os.name").toLowerCase();
        final String arch = System.getProperty("os.arch");

        if ((os.contains("windows") || os.contains("linux")) && !arch.equalsIgnoreCase("arm") && !arch.equalsIgnoreCase("arm-linux")) {
            LOG.info("System supports NAS, enabling...");
            shardManagerBuilder.setAudioSendFactory(new NativeAudioSendFactory());
        }

        shardManager = shardManagerBuilder.build();
        setupSelf();
    }

    private static void printBanner() {
        String os = System.getProperty("os.name");
        String arch = System.getProperty("os.arch");
        String banner = Helpers.INSTANCE.readFile("banner.txt", "");

        LOG.info("\n" + banner + "\n" +
                "JukeBot v" + VERSION +
                " | JDA " + JDAInfo.VERSION +
                " | Lavaplayer " + PlayerLibrary.VERSION +
                " | SQLite " + SQLiteJDBCLoader.getVersion() +
                " | " + System.getProperty("sun.arch.data.model") + "-bit JVM" +
                " | " + os + " " + arch + "\n");
    }

    private static void loadApis() {
        if (config.hasKey("patreon")) {
            LOG.debug("Config has patreon key, loading patreon API...");
            patreonApi = new PatreonAPI(Objects.requireNonNull(config.getString("patreon")));
        }

        if (config.hasKey("ksoft")) {
            LOG.debug("Config has ksoft key, loading ksoft API...");
            String key = Objects.requireNonNull(config.getString("ksoft"));
            kSoftAPI = new KSoftAPI(key);
        }

//        if (config.hasKey("youtube")) {
//            LOG.debug("Config has youtube key, loading youtube API...");
//            String key = Objects.requireNonNull(config.getString("youtube"));
//            YoutubeAudioSourceManager sm = playerManager.source(YoutubeAudioSourceManager.class);
//            youTubeApi = new YouTubeAPI(key, sm);
//        }
    }

    /**
     * Audio System
     */

    private static void setupAudioSystem() {
        playerManager.getConfiguration().setFrameBufferFactory(NonAllocatingAudioFrameBuffer::new);
        playerManager.setPlayerCleanupThreshold(30000);
        playerManager.getConfiguration().setFilterHotSwapEnabled(true);

        registerSourceManagers();

        //YoutubeAudioSourceManager sourceManager = playerManager.source(YoutubeAudioSourceManager.class);
        //sourceManager.setPlaylistPageCount(Integer.MAX_VALUE);

        //CachingSourceManager cachingSourceManager = playerManager.source(CachingSourceManager.class);
        //sourceManager.setCacheProvider(cachingSourceManager);
    }

    private static void registerSourceManagers() {
        playerManager.registerSourceManager(new CachingSourceManager());
        playerManager.registerSourceManager(new MixcloudAudioSourceManager());

        if (config.getNsfwEnabled()) {
            playerManager.registerSourceManager(new PornHubAudioSourceManager());
        }

//        if (config.hasKey("spotify_client") && config.hasKey("spotify_secret")) {
//            String client = Objects.requireNonNull(config.getString("spotify_client"));
//            String secret = Objects.requireNonNull(config.getString("spotify_secret"));
//            playerManager.registerSourceManager(new SpotifyAudioSourceManager(client, secret));
//        }

//        AudioSourceManagers.registerRemoteSources(playerManager);

        playerManager.registerSourceManager(SoundCloudAudioSourceManager.createDefault());
        playerManager.registerSourceManager(new BandcampAudioSourceManager());
        playerManager.registerSourceManager(new VimeoAudioSourceManager());
        playerManager.registerSourceManager(new TwitchStreamAudioSourceManager());
        playerManager.registerSourceManager(new BeamAudioSourceManager());
        playerManager.registerSourceManager(new HttpAudioSourceManager());
    }

    private static void setupSelf() {
        ApplicationInfo appInfo = shardManager.retrieveApplicationInfo().complete();
        selfId = appInfo.getIdLong();
        botOwnerId = appInfo.getOwner().getIdLong();
        isSelfHosted = appInfo.getIdLong() != 249303797371895820L
                && appInfo.getIdLong() != 314145804807962634L;

        if (isSelfHosted || selfId == 314145804807962634L) {
            playerManager.getConfiguration().setResamplingQuality(AudioConfiguration.ResamplingQuality.HIGH);
        }

        if (isSelfHosted) {
            Map<String, Command> commandRegistry = CommandHandler.Companion.getCommands();
            commandRegistry.remove("patreon");
            commandRegistry.remove("verify");
            Command feedback = commandRegistry.remove("feedback");

            if (feedback != null) {
                feedback.destroy();
            }
        } else {
            Helpers.INSTANCE.getMonitor().scheduleAtFixedRate(Helpers.INSTANCE::monitorPledges, 0, 1, TimeUnit.DAYS);
        }
    }

    public static boolean hasPlayer(final long guildId) {
        return players.containsKey(guildId);
    }

    public static AudioHandler getPlayer(final long guildId) {
        Guild g = shardManager.getGuildById(guildId);
        Objects.requireNonNull(g, "getPlayer was given an invalid guildId!");

        AudioHandler handler = players.computeIfAbsent(guildId,
                v -> new AudioHandler(guildId, playerManager.createPlayer()));

        AudioManager audioManager = g.getAudioManager();

        if (audioManager.getSendingHandler() == null)
            audioManager.setSendingHandler(handler);

        return handler;
    }

    public static void removePlayer(final long guildId) {
        if (JukeBot.hasPlayer(guildId)) {
            players.remove(guildId).cleanup();
        }
    }

}
