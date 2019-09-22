package jukebot.commands

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import jukebot.audio.AudioHandler
import jukebot.framework.Command
import jukebot.framework.CommandCategory
import jukebot.framework.CommandProperties
import jukebot.framework.Context
import jukebot.utils.toTimeString

@CommandProperties(description = "Jump to a certain time, or by a specific amount of seconds", category = CommandCategory.CONTROLS, aliases = ["jump"])
class Seek : Command(ExecutionType.REQUIRE_MUTUAL) {

    override fun execute(context: Context) {
        val player = context.getAudioPlayer()
        val currentTrack = player.player.playingTrack

        if (!player.isPlaying) {
            return context.embed("Not Playing", "Nothing is currently playing.")
        }

        if (!context.isDJ(true)) {
            return context.embed("Not a DJ", "You need to be a DJ to use this command.\n[See here on how to become a DJ](https://jukebot.serux.pro/faq)")
        }

        if (!currentTrack.isSeekable) {
            return context.embed("Seek Unavailable", "The current track doesn't support seeking.")
        }

        val arg = context.args.firstOrNull()
            ?: return context.embed("Track Seeking", "You need to specify a time, or amount of seconds to jump by.")

        if (arg.contains(":")) {
            parseTime(context, player, currentTrack, arg)
        } else {
            parseSeconds(context, player, currentTrack, arg)
        }
    }

    fun parseSeconds(ctx: Context, player: AudioHandler, track: AudioTrack, seconds: String) {
        val jumpTime = seconds.toIntOrNull()
            ?: return ctx.embed("Track Seeking", "You need to specify a valid amount of seconds to jump.")

        val jumpTimeMs = jumpTime * 1000

        if (track.position + jumpTimeMs >= track.duration) {
            return player.playNext()
        }

        track.position += jumpTimeMs
        ctx.embed("Track Seeking", "Seeked to **${track.position.toTimeString()}**/${track.info.length.toTimeString()}")
    }

    fun parseTime(ctx: Context, player: AudioHandler, track: AudioTrack, time: String) {
        val parts = time.split(":").map { it.toIntOrNull() }

        if (parts.any { it == null }) {
            return ctx.embed("Track Seeking", "Invalid time specified.\nExamples: `02:33`, `01:18:29`, `01:08:29:56`")
        }

        val partsInt = parts.filterNotNull() // None should be null, but IntelliJ is complaining.

        val jumpTimeMs = when (parts.size) {
            4 -> { // DD:HH:MM:SS
                val (days, hours, minutes, seconds) = partsInt
                val total = (days * 86400) + (hours * 3600) + (minutes * 60) + seconds

                total * 1000 // millis
            }
            3 -> { // HH:MM:SS
                val (hours, minutes, seconds) = partsInt
                val total = (hours * 3600) + (minutes * 60) + seconds

                total * 1000
            }
            2 -> { // MM:SS
                val (minutes, seconds) = partsInt
                val total = (minutes * 60) + seconds

                total * 1000
            }
            else -> return ctx.embed("Track Seeking", "Invalid time.\nAcceptable formats: `mm:ss`, `hh:mm:ss`, `dd:hh:mm:ss`")
        }

        if (track.position + jumpTimeMs >= track.duration) {
            return player.playNext()
        }

        track.position = jumpTimeMs.toLong()
        ctx.embed("Track Seeking", "Seeked to **${track.position.toTimeString()}**/${track.info.length.toTimeString()}")
    }
}
