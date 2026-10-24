package id.xydesk.remote.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.freerdp.freerdpcore.application.GlobalApp
import id.xydesk.remote.ui.components.XyIcons
import id.xydesk.remote.ui.components.XyPillButton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

internal data class VoiceStageParticipant(
    val key: String,
    val name: String,
    val githubLogin: String,
    val avatarUrl: String,
    val muted: Boolean,
    val speaking: Boolean,
    val sharingScreen: Boolean,
)

internal data class LiveRdpScreenShareState(
    val active: Boolean = false,
    val streamerKey: String = "",
    val streamerName: String = "",
    val githubLogin: String = "",
    val title: String = "",
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 60,
    val seq: Long = 0L,
    val bitmap: Bitmap? = null,
)

/**
 * Engine Global ala Discord untuk Multi-User Voice Party + Live RDP Screen Share.
 * Tetap hidup saat pengguna berpindah antara Sesi XyRDP, Chat Global, dan Beranda.
 */
internal object XyDiscordVoiceStageManager {
    private const val ENDPOINT = "https://api.xydeskremote.biz.id/api/voice-stage"
    private const val SAMPLE_RATE = 8000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _joined = MutableStateFlow(false)
    val joined = _joined.asStateFlow()

    private val _micMuted = MutableStateFlow(false)
    val micMuted = _micMuted.asStateFlow()

    private val _speakerDeafened = MutableStateFlow(false)
    val speakerDeafened = _speakerDeafened.asStateFlow()

    private val _sharingMyRdp = MutableStateFlow(false)
    val sharingMyRdp = _sharingMyRdp.asStateFlow()

    private val _participants = MutableStateFlow<List<VoiceStageParticipant>>(emptyList())
    val participants = _participants.asStateFlow()

    private val _liveScreen = MutableStateFlow(LiveRdpScreenShareState())
    val liveScreen = _liveScreen.asStateFlow()

    @Volatile
    private var lastAudioSeq: Long = 0L
    private var pollJob: Job? = null
    private var micJob: Job? = null
    private var screenShareJob: Job? = null
    private var audioTrack: AudioTrack? = null

    fun ensureBackgroundPolling(context: Context) {
        if (pollJob?.isActive == true) return
        val appCtx = context.applicationContext
        pollJob = scope.launch {
            while (isActive) {
                runCatching {
                    pollStageOnce(appCtx)
                }
                delay(if (_joined.value || _sharingMyRdp.value) 1_100L else 4_000L)
            }
        }
    }

    fun joinVoiceParty(context: Context, startWithMic: Boolean = true) {
        val appCtx = context.applicationContext
        _joined.value = true
        _micMuted.value = !startWithMic
        ensureBackgroundPolling(appCtx)
        scope.launch {
            sendStageAction(appCtx, "join", JSONObject().put("muted", _micMuted.value))
        }
        if (startWithMic) {
            startMicLoop(appCtx)
        }
    }

    fun leaveVoiceParty(context: Context) {
        val appCtx = context.applicationContext
        _joined.value = false
        _sharingMyRdp.value = false
        micJob?.cancel()
        screenShareJob?.cancel()
        scope.launch {
            sendStageAction(appCtx, "leave", JSONObject())
        }
    }

    fun toggleMic(context: Context) {
        val appCtx = context.applicationContext
        val nextMuted = !_micMuted.value
        _micMuted.value = nextMuted
        if (!nextMuted && _joined.value) {
            startMicLoop(appCtx)
        } else {
            micJob?.cancel()
        }
        scope.launch {
            sendStageAction(appCtx, "heartbeat", JSONObject().put("muted", nextMuted))
        }
    }

    fun toggleDeafen() {
        _speakerDeafened.value = !_speakerDeafened.value
    }

    fun toggleShareActiveRdpSession(context: Context) {
        val appCtx = context.applicationContext
        if (_sharingMyRdp.value) {
            _sharingMyRdp.value = false
            screenShareJob?.cancel()
            scope.launch {
                sendStageAction(appCtx, "stop_screen", JSONObject())
            }
            Toast.makeText(appCtx, "Share Screen Sesi XyRDP dihentikan", Toast.LENGTH_SHORT).show()
            return
        }
        if (!_joined.value) {
            joinVoiceParty(appCtx, startWithMic = false)
        }
        _sharingMyRdp.value = true
        Toast.makeText(appCtx, "LIVE: Share Screen Sesi XyRDP dimulai ke Voice Room!", Toast.LENGTH_SHORT).show()
        screenShareJob?.cancel()
        screenShareJob = scope.launch {
            while (isActive && _sharingMyRdp.value) {
                runCatching {
                    val session = GlobalApp.getSessions()?.firstOrNull { it?.surface?.bitmap != null }
                    val bmp = session?.surface?.bitmap
                    if (bmp != null && !bmp.isRecycled && bmp.width > 0 && bmp.height > 0) {
                        val targetW = 640
                        val targetH = ((bmp.height.toFloat() / bmp.width.toFloat()) * targetW).toInt().coerceIn(320, 400)
                        val scaled = synchronized(bmp) {
                            Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
                        }
                        val bos = ByteArrayOutputStream(32768)
                        scaled.compress(Bitmap.CompressFormat.JPEG, 70, bos)
                        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                        val hostTitle = session.openUri?.host?.takeIf { it.isNotBlank() } ?: "XyRDP Desktop"
                        sendStageAction(
                            appCtx,
                            "screen_frame",
                            JSONObject()
                                .put("title", "Live Sesi XyRDP ($hostTitle)")
                                .put("frame_b64", b64)
                                .put("width", bmp.width)
                                .put("height", bmp.height)
                                .put("fps", 60),
                        )
                    }
                }
                delay(1_150L)
            }
        }
    }

    private fun startMicLoop(context: Context) {
        micJob?.cancel()
        val hasPerm = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return
        micJob = scope.launch {
            var recorder: AudioRecord? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(3200)
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2,
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) return@launch
                recorder.startRecording()
                val chunkSamples = 2400 // 300ms @ 8kHz
                val shortBuf = ShortArray(chunkSamples)
                while (isActive && _joined.value && !_micMuted.value) {
                    val read = recorder.read(shortBuf, 0, chunkSamples)
                    if (read > 0) {
                        var peak = 0
                        for (i in 0 until read) {
                            val a = abs(shortBuf[i].toInt())
                            if (a > peak) peak = a
                        }
                        if (peak > 420) {
                            // Down-quantize ke 8-bit signed PCM agar hemat bandwidth & instan untuk banyak orang
                            val pcm8 = ByteArray(read)
                            for (i in 0 until read) {
                                pcm8[i] = (shortBuf[i].toInt() shr 8).toByte()
                            }
                            val b64 = Base64.encodeToString(pcm8, Base64.NO_WRAP)
                            sendStageAction(
                                context,
                                "voice_chunk",
                                JSONObject()
                                    .put("pcm_b64", b64)
                                    .put("muted", false)
                                    .put("speaking", true),
                            )
                        } else {
                            sendStageAction(
                                context,
                                "heartbeat",
                                JSONObject()
                                    .put("muted", false)
                                    .put("speaking", false),
                            )
                            delay(650L)
                        }
                    } else {
                        delay(200L)
                    }
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
            }
        }
    }

    private fun playIncomingVoiceChunk(pcm8Bytes: ByteArray) {
        if (_speakerDeafened.value || pcm8Bytes.isEmpty()) return
        runCatching {
            var track = audioTrack
            if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
                val minBuf = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(3200)
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.play()
                audioTrack = track
            }
            val pcm16 = ShortArray(pcm8Bytes.size)
            for (i in pcm8Bytes.indices) {
                pcm16[i] = (pcm8Bytes[i].toInt() shl 8).toShort()
            }
            track.write(pcm16, 0, pcm16.size)
        }
    }

    private fun myIdentity(context: Context): Pair<String, String> {
        val gh = getVerifiedGitHubLogin(context)?.trim().orEmpty()
        val prefs = context.getSharedPreferences("xydesk.global.chat", Context.MODE_PRIVATE)
        val nick = prefs.getString("nickname", null)?.trim().orEmpty().ifBlank { gh.ifBlank { "XyUser" } }
        return nick to gh
    }

    private fun sendStageAction(context: Context, action: String, extra: JSONObject) {
        runCatching {
            val (nick, gh) = myIdentity(context)
            val payload = JSONObject(extra.toString())
                .put("action", action)
                .put("name", nick)
                .put("github_login", gh)
            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 5_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            conn.responseCode
            conn.disconnect()
        }
    }

    private fun pollStageOnce(context: Context) {
        val (nick, gh) = myIdentity(context)
        val myKey = (gh.ifBlank { nick }).lowercase()
        if (_joined.value) {
            sendStageAction(
                context,
                "heartbeat",
                JSONObject().put("muted", _micMuted.value),
            )
        }
        val url = "$ENDPOINT?since_audio=$lastAudioSeq&include_frame=1"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 6_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) return
            val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val pArr = root.optJSONArray("participants")
            val parsedParticipants = ArrayList<VoiceStageParticipant>()
            if (pArr != null) {
                for (i in 0 until pArr.length()) {
                    val o = pArr.optJSONObject(i) ?: continue
                    parsedParticipants.add(
                        VoiceStageParticipant(
                            key = o.optString("key"),
                            name = o.optString("name", "User"),
                            githubLogin = o.optString("github_login"),
                            avatarUrl = o.optString("avatar_url"),
                            muted = o.optBoolean("muted", false),
                            speaking = o.optBoolean("speaking", false),
                            sharingScreen = o.optBoolean("sharing_screen", false),
                        ),
                    )
                }
            }
            _participants.value = parsedParticipants

            val serverSeq = root.optLong("audio_seq", lastAudioSeq)
            val cArr = root.optJSONArray("audio_chunks")
            if (cArr != null && _joined.value && !_speakerDeafened.value) {
                for (i in 0 until cArr.length()) {
                    val c = cArr.optJSONObject(i) ?: continue
                    val seq = c.optLong("seq", 0L)
                    val senderKey = c.optString("sender_key")
                    if (seq > lastAudioSeq) lastAudioSeq = seq
                    if (senderKey != myKey) {
                        val b64 = c.optString("pcm_b64")
                        if (b64.isNotBlank()) {
                            val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                            if (bytes != null) playIncomingVoiceChunk(bytes)
                        }
                    }
                }
            }
            if (serverSeq > lastAudioSeq) lastAudioSeq = serverSeq

            val scr = root.optJSONObject("screen_share")
            if (scr != null && scr.optBoolean("active", false)) {
                val seq = scr.optLong("seq", 0L)
                val prev = _liveScreen.value
                val frameB64 = scr.optString("frame_b64")
                val bmp = if (seq != prev.seq && frameB64.isNotBlank()) {
                    runCatching {
                        val bytes = Base64.decode(frameB64, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull() ?: prev.bitmap
                } else {
                    prev.bitmap
                }
                _liveScreen.value = LiveRdpScreenShareState(
                    active = true,
                    streamerKey = scr.optString("streamer_key"),
                    streamerName = scr.optString("streamer_name", "User"),
                    githubLogin = scr.optString("github_login"),
                    title = scr.optString("title", "Live Sesi XyRDP"),
                    width = scr.optInt("width", 1280),
                    height = scr.optInt("height", 720),
                    fps = scr.optInt("fps", 60),
                    seq = seq,
                    bitmap = bmp,
                )
            } else {
                _liveScreen.value = LiveRdpScreenShareState(active = false)
            }
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Bar & Panel Discord-Style Multi-User Voice Party + Live RDP Screen Share
 * untuk dipasang di Global Chat dan di Panel Kontrol Sesi RDP.
 */
@Composable
internal fun XyDiscordVoiceStageBar(
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        XyDiscordVoiceStageManager.ensureBackgroundPolling(context)
    }
    val joined by XyDiscordVoiceStageManager.joined.collectAsState()
    val micMuted by XyDiscordVoiceStageManager.micMuted.collectAsState()
    val deafened by XyDiscordVoiceStageManager.speakerDeafened.collectAsState()
    val sharingMyRdp by XyDiscordVoiceStageManager.sharingMyRdp.collectAsState()
    val participants by XyDiscordVoiceStageManager.participants.collectAsState()
    val liveScreen by XyDiscordVoiceStageManager.liveScreen.collectAsState()

    var expandedStage by remember { mutableStateOf(false) }
    var watchingLiveStream by remember { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        XyDiscordVoiceStageManager.joinVoiceParty(context, startWithMic = granted)
    }

    val hasActiveRdpSession = remember(sharingMyRdp, expandedStage) {
        runCatching { GlobalApp.getSessions()?.isNotEmpty() == true }.getOrDefault(false)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF141418))
            .border(
                width = 1.dp,
                color = when {
                    liveScreen.active -> Color(0xFFF2F2F4)
                    joined -> Color(0xFFE4E4E8)
                    else -> Color(0xFF3C3C42)
                },
                shape = RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        // Baris Utama Voice Lounge & Live RDP Stage
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expandedStage = !expandedStage },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                liveScreen.active -> Color(0xFF2A2A2E)
                                joined -> Color(0xFF232327)
                                else -> Color(0xFF1A1A1E)
                            },
                        )
                        .border(1.dp, Color(0xFF3C3C42), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (liveScreen.active) XyIcons.Monitor else XyIcons.Volume,
                        contentDescription = null,
                        tint = Color(0xFFF3F3F5),
                        modifier = Modifier.size(15.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = xy("XyVoice Party & Live RDP", "XyVoice Party & Live RDP"),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (liveScreen.active) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(0xFFF2F2F4))
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                            ) {
                                Text(
                                    text = "LIVE RDP",
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF111114),
                                )
                            }
                        }
                    }
                    Text(
                        text = when {
                            liveScreen.active -> "${liveScreen.streamerName} sedang Share Screen RDP · ${participants.size} di Voice"
                            participants.isNotEmpty() -> "${participants.size} orang sedang ngobrol di Voice Room"
                            else -> xy("Ngobrol bareng banyak orang & Share Screen Sesi RDP", "Multi-user voice chat & Live RDP Screen Share")
                        },
                        fontSize = 10.sp,
                        color = Color(0xFFB2B2B8),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!joined) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFFF2F2F4))
                            .border(1.dp, Color(0xFFFFFFFF), RoundedCornerShape(50))
                            .clickable {
                                val hasPerm = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                if (hasPerm) {
                                    XyDiscordVoiceStageManager.joinVoiceParty(context, startWithMic = true)
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                                expandedStage = true
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = xy("Gabung Voice", "Join Voice"),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF111114),
                        )
                    }
                } else {
                    // Tombol Mic ON / MUTE
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (micMuted) Color(0xFF232327) else Color(0xFFF2F2F4))
                            .clickable { XyDiscordVoiceStageManager.toggleMic(context) }
                            .padding(horizontal = 9.dp, vertical = 4.5.dp),
                    ) {
                        Text(
                            text = if (micMuted) "MIC OFF" else "MIC ON",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (micMuted) Color.White else Color(0xFF111114),
                        )
                    }
                    // Tombol Keluar Voice
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF2A2A2E))
                            .border(1.dp, Color(0xFF535353), RoundedCornerShape(50))
                            .clickable { XyDiscordVoiceStageManager.leaveVoiceParty(context) }
                            .padding(horizontal = 9.dp, vertical = 4.5.dp),
                    ) {
                        Text(
                            text = xy("Keluar", "Leave"),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                        )
                    }
                }
            }
        }

        // Deretan Peserta Voice Room & Tombol Share Screen Sesi RDP (Ala Discord Stage)
        AnimatedVisibility(visible = expandedStage || joined || liveScreen.active) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (participants.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        participants.forEach { p ->
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(0xFF1A1A1E))
                                    .border(
                                        width = if (p.speaking) 1.4.dp else 0.8.dp,
                                        color = when {
                                            p.sharingScreen -> Color(0xFFFFFFFF)
                                            p.speaking -> Color(0xFFE4E4E8)
                                            else -> Color(0xFF3C3C42)
                                        },
                                        shape = RoundedCornerShape(50),
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                FeedAvatarThumb(
                                    avatarUrl = p.avatarUrl,
                                    name = p.name,
                                    githubLogin = p.githubLogin,
                                    isFounder = isFounderAccount(p.githubLogin) || isFounderAccount(p.name),
                                    size = 20.dp,
                                )
                                Text(
                                    text = p.name,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                )
                                Text(
                                    text = when {
                                        p.sharingScreen -> "LIVE"
                                        p.muted -> "MUTE"
                                        p.speaking -> "ON"
                                        else -> "IDLE"
                                    },
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFB2B2B8),
                                )
                            }
                        }
                    }
                }

                // Kontrol Share Screen Sesi RDP & Tonton Stream
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (hasActiveRdpSession || sharingMyRdp) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (sharingMyRdp) Color(0xFF2A2A2E) else Color(0xFFF2F2F4))
                                .border(1.dp, Color(0xFFFFFFFF), RoundedCornerShape(10.dp))
                                .clickable {
                                    XyDiscordVoiceStageManager.toggleShareActiveRdpSession(context)
                                }
                                .padding(vertical = 7.dp, horizontal = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (sharingMyRdp) {
                                    xy("Hentikan Share Screen RDP", "Stop RDP Screen Share")
                                } else {
                                    xy("Share Screen Sesi RDP (Go Live)", "Share RDP Session Screen (Go Live)")
                                },
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (sharingMyRdp) Color.White else Color(0xFF111114),
                            )
                        }
                    }
                    if (liveScreen.active) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF1A1A1E))
                                .border(1.dp, Color(0xFFE4E4E8), RoundedCornerShape(10.dp))
                                .clickable { watchingLiveStream = !watchingLiveStream }
                                .padding(vertical = 7.dp, horizontal = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (watchingLiveStream) {
                                    xy("Sembunyikan Layar Live RDP", "Hide Live RDP Stream")
                                } else {
                                    xy("Tonton Share Screen ${liveScreen.streamerName}", "Watch ${liveScreen.streamerName}'s RDP")
                                },
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFFF3F3F5),
                            )
                        }
                    }
                    if (joined) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF1A1A1E))
                                .border(1.dp, Color(0xFF3C3C42), RoundedCornerShape(10.dp))
                                .clickable { XyDiscordVoiceStageManager.toggleDeafen() }
                                .padding(vertical = 7.dp, horizontal = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (deafened) "Speaker Off" else "Speaker On",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE4E4E8),
                            )
                        }
                    }
                }

                // Kanvas Penonton Live Screen Share Sesi RDP (Ala Discord Go Live)
                if (liveScreen.active && (watchingLiveStream || sharingMyRdp)) {
                    val bmp = liveScreen.bitmap
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF08080A))
                            .border(1.dp, Color(0xFF3C3C42), RoundedCornerShape(12.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "LIVE · ${liveScreen.title} · oleh ${liveScreen.streamerName}",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFFF3F3F5),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${liveScreen.width}×${liveScreen.height} · LIVE",
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFE4E4E8),
                            )
                        }
                        if (bmp != null && !bmp.isRecycled) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = liveScreen.title,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 140.dp, max = 220.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Fit,
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF101013)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = xy("Memuat stream layar sesi XyRDP…", "Loading live XyRDP screen stream…"),
                                    fontSize = 11.sp,
                                    color = Color(0xFFB2B2B8),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
