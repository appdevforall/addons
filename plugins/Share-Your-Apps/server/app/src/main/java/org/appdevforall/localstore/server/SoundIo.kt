package org.appdevforall.localstore.server

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread
import org.appdevforall.localstore.server.SoundModem

/**
 * Moves the sound handshake through the phone's speaker and microphone. [SoundModem] and [SoundDecoder] do the
 * signal work; this only plays and records, on background threads. Callbacks run on the main thread. The screens
 * own a [Player] or [Listener] and stop it when they close, so nothing plays or listens off the screen.
 */
object SoundIo {
    /** About how long a player plays, and the longest a listener listens, from the moment each starts. */
    const val WINDOW_MS = SoundModem.WINDOW_MS
    /** How long a player waits for the speaker to finish what it was given. */
    private const val TAIL_MS = 2_000L
    private val main = Handler(Looper.getMainLooper())

    /** Whether the media volume is muted, or under a third of full: likely too quiet for the other phone. */
    fun volumeLow(context: Context): Boolean {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return max > 0 && am.getStreamVolume(AudioManager.STREAM_MUSIC) * 3 < max
    }

    fun canListen(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun pcm(x: FloatArray) = ShortArray(x.size) { i -> (x[i].coerceIn(-1f, 1f) * 32767).toInt().toShort() }

    /**
     * Plays [data] as media sound: [bursts] bursts in a row, as many as fit in [WINDOW_MS] (see
     * [SoundModem.burstsFor]), until they are all played or [stop]. [progress] gets the number of bursts played so
     * far, counted from what the speaker has played, not from what is queued. [ended] gets null, or why it failed.
     */
    class Player(private val data: ByteArray, private val ended: (String?) -> Unit, private val progress: ((Int) -> Unit)?) {
        @Volatile private var stopped = false
        /** How many bursts it plays in all, if it isn't stopped. */
        val bursts: Int = SoundModem().burstsFor(data.size)
        /** Bursts played so far, for the log; read it in [ended]. */
        @Volatile var burstsPlayed = 0; private set
        /** Whether [stop] ended it, rather than it playing all its bursts; read it in [ended]. */
        val stoppedEarly: Boolean get() = stopped

        fun start(): Player { thread(name = "sound-play") { run() }; return this }
        fun stop() { stopped = true }

        private fun run() {
            val modem = SoundModem()
            var error: String? = null
            var track: AudioTrack? = null
            try {
                val period = modem.burstPeriod(data.size)
                // The three repeats, played over until all the bursts are out: the same sound as [bursts] bursts, made at once.
                val round = pcm(modem.play(data, SoundModem.REPEATS))
                val total = bursts * period
                val min = AudioTrack.getMinBufferSize(modem.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(modem.sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(min, modem.sampleRate / 5 * 2)) // about 200 ms
                    .build()
                track = t
                t.play()
                // The speaker's own count of what it has played, so the countdown follows the sound.
                fun report() {
                    val done = minOf(bursts, t.playbackHeadPosition / period)
                    if (done > burstsPlayed) { burstsPlayed = done; main.post { progress?.invoke(done) } }
                }
                var at = 0
                while (at < total && !stopped) {
                    val from = at % round.size
                    val n = t.write(round, from, minOf(4096, round.size - from, total - at))
                    if (n < 0) { error = "The speaker refused the sound (error $n)."; break }
                    at += n
                    report()
                }
                // Let the buffer finish playing, reporting the last bursts as they sound.
                val tail = System.currentTimeMillis() + TAIL_MS
                while (error == null && !stopped && t.playbackHeadPosition < total && System.currentTimeMillis() < tail) {
                    report(); Thread.sleep(50)
                }
                if (error == null && !stopped && t.playbackHeadPosition >= total && burstsPlayed < bursts) {
                    burstsPlayed = bursts; main.post { progress?.invoke(bursts) }
                } else report()
            } catch (e: Exception) {
                error = "Couldn't play the sound: ${e.message ?: e.toString()}"
            } finally {
                try { track?.pause(); track?.flush() } catch (e: Exception) { }
                track?.release()
            }
            main.post { ended(error) }
        }
    }

    enum class End { STOPPED, NOTHING_HEARD, NO_MICROPHONE, FAILED }

    /** How often a listener reports its [Level]: about ten times a second, smooth enough for a meter. */
    const val LEVEL_MS = 100L

    /**
     * What a listener reports while it listens: [signal], the strongest start signal so far (0 to 1); [db], the
     * microphone's loudest moment since the last report, in dB of full scale (SoundDsp.METER_FLOOR_DB to 0);
     * and [msLeft], the time left before it gives up.
     */
    class Level(val signal: Double, val db: Double, val msLeft: Long)

    /**
     * Records at [SoundModem.SAMPLE_RATE] until it hears a valid [SoundPayload], [stop], or [WINDOW_MS]. [level] gets a
     * [Level] about ten times a second: the strongest start signal so far, the microphone's loudness, and the time
     * left; [heard] gets the payload and how it was heard (for the log); otherwise [ended] says why it stopped.
     * The caller asks for RECORD_AUDIO first.
     */
    class Listener(
        private val context: Context,
        private val level: (Level) -> Unit,
        private val heard: (SoundPayload, String) -> Unit,
        private val ended: (End, String) -> Unit,
    ) {
        @Volatile private var stopped = false

        fun start(): Listener { thread(name = "sound-listen") { run() }; return this }
        fun stop() { stopped = true }

        /** UNPROCESSED where the phone supports it: noise suppression can remove tones. Else VOICE_RECOGNITION, also lightly processed. */
        private fun source(): Int {
            val am = context.getSystemService(AudioManager::class.java)
            return if (am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
                MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        }

        @SuppressLint("MissingPermission") // checked by the screen before starting; a SecurityException is caught
        private fun run() {
            val modem = SoundModem()
            var payload: SoundPayload? = null
            val decoder = SoundDecoder(modem) { b -> SoundPayload.decode(b)?.also { payload = it } != null }
            var record: AudioRecord? = null
            var end = End.STOPPED
            var why = "Stopped."
            var found: SoundPayload? = null
            try {
                val rate = modem.sampleRate
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) { end = End.FAILED; why = "This phone can't record at $rate Hz."; return }
                val r = AudioRecord(source(), rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
                record = r
                if (r.state != AudioRecord.STATE_INITIALIZED) { end = End.NO_MICROPHONE; why = "The microphone couldn't be opened."; return }
                r.startRecording()
                val chunk = ShortArray(2048)
                val f = FloatArray(chunk.size)
                val deadline = System.currentTimeMillis() + WINDOW_MS
                var lastLevel = 0L
                // The loudest chunk since the last report, so a short tone isn't missed between reports.
                var loudest = SoundDsp.METER_FLOOR_DB
                while (!stopped) {
                    val now = System.currentTimeMillis()
                    if (now > deadline) { end = End.NOTHING_HEARD; why = "Didn't hear the code."; break }
                    val n = r.read(chunk, 0, chunk.size)
                    if (n < 0) { end = End.FAILED; why = "The microphone stopped (error $n)."; break }
                    for (i in 0 until n) f[i] = chunk[i] / 32768f
                    if (n > 0) loudest = maxOf(loudest, SoundDsp.rmsDb(f, 0, n))
                    if (decoder.feed(f, 0, n) != null) { found = payload; break }
                    if (now - lastLevel >= LEVEL_MS) {
                        lastLevel = now
                        val v = Level(decoder.bestSignal, loudest, maxOf(0L, deadline - now))
                        loudest = SoundDsp.METER_FLOOR_DB
                        main.post { level(v) }
                    }
                }
            } catch (e: SecurityException) {
                end = End.NO_MICROPHONE; why = "The app isn't allowed to use the microphone."
            } catch (e: Exception) {
                end = End.FAILED; why = "Couldn't listen: ${e.message ?: e.toString()}"
            } finally {
                try { record?.stop() } catch (e: Exception) { }
                record?.release()
                val p = found
                val how = decoder.how
                if (p != null) main.post { heard(p, how) } else main.post { ended(end, why) }
            }
        }
    }
}
