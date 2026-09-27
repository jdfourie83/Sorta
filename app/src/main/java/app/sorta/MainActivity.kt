@file:OptIn(ExperimentalMaterial3Api::class)

package app.sorta

import android.Manifest
import android.app.Application
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.Coil
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.util.concurrent.TimeUnit

enum class Act(val label: String) { NONE("Nothing"), DELETE("Delete"), NAS("Send to NAS"), SHARE("Share") }

data class Media(val uri: Uri, val video: Boolean, val name: String, val mime: String, val added: Long)

val PERMS = if (Build.VERSION.SDK_INT >= 33)
    arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

fun hasPerm(c: Context) = PERMS.all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

class VM(app: Application) : AndroidViewModel(app) {
    private val cr = app.contentResolver
    private val prefs = app.getSharedPreferences("sorta", 0)
    private val client = OkHttpClient.Builder().writeTimeout(10, TimeUnit.MINUTES).readTimeout(2, TimeUnit.MINUTES).build()
    private fun act(k: String, d: Act) = runCatching { Act.valueOf(prefs.getString(k, d.name)!!) }.getOrDefault(d)

    var items by mutableStateOf(emptyList<Media>()); private set
    var left by mutableStateOf(act("left", Act.DELETE)); private set
    var right by mutableStateOf(act("right", Act.NAS)); private set
    var dbl by mutableStateOf(act("double", Act.SHARE)); private set
    var deleteAfterNas by mutableStateOf(prefs.getBoolean("delAfterNas", false)); private set
    var silentDelete by mutableStateOf(prefs.getBoolean("silentDelete", false)); private set
    var columns by mutableStateOf(prefs.getInt("columns", 3)); private set
    val nas = mutableStateMapOf<String, String>().apply { listOf("url", "user", "pass", "dir").forEach { put(it, prefs.getString(it, "") ?: "") } }

    fun setAct(k: String, a: Act) {
        prefs.edit().putString(k, a.name).apply()
        when (k) { "left" -> left = a; "right" -> right = a; else -> dbl = a }
    }
    fun setBool(k: String, v: Boolean) {
        prefs.edit().putBoolean(k, v).apply()
        when (k) { "delAfterNas" -> deleteAfterNas = v; "silentDelete" -> silentDelete = v }
    }
    fun setColumns(n: Int) { columns = n; prefs.edit().putInt("columns", n).apply() }
    fun setNas(k: String, v: String) { nas[k] = v; prefs.edit().putString(k, v).apply() }

    fun load() = viewModelScope.launch(Dispatchers.IO) {
        val out = mutableListOf<Media>()
        for (video in listOf(false, true)) {
            val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val cols = arrayOf("_id", "_display_name", "mime_type", "date_added")
            cr.query(base, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) out += Media(ContentUris.withAppendedId(base, c.getLong(0)), video,
                    c.getString(1) ?: "media", c.getString(2) ?: if (video) "video/*" else "image/*", c.getLong(3))
            }
        }
        items = out.sortedByDescending { it.added }
    }

    /** Uploads via WebDAV (enable WebDAV in UGOS; the target folder must already exist). */
    suspend fun upload(m: Media): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val base = nas["url"].orEmpty().trim().trimEnd('/')
            require(base.isNotEmpty()) { "Set your NAS address in Settings" }
            val dir = nas["dir"].orEmpty().trim('/', ' ')
            val url = base + (if (dir.isEmpty()) "" else "/$dir") + "/" + Uri.encode(m.name)
            val body = object : RequestBody() {
                override fun contentType() = m.mime.toMediaTypeOrNull()
                override fun writeTo(sink: BufferedSink) { cr.openInputStream(m.uri)!!.use { sink.writeAll(it.source()) } }
            }
            val req = Request.Builder().url(url).put(body)
                .header("Authorization", Credentials.basic(nas["user"].orEmpty(), nas["pass"].orEmpty())).build()
            client.newCall(req).execute().use { require(it.isSuccessful) { "NAS replied ${it.code}" } }
        }
    }

    /** Deletes directly with no system dialog. Only succeeds where the OS allows silent delete. */
    suspend fun deleteDirect(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        uris.count { runCatching { cr.delete(it, null, null) > 0 }.getOrDefault(false) }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Coil.setImageLoader(ImageLoader.Builder(this).components { add(VideoFrameDecoder.Factory()) }.crossfade(true).build())
        setContent { SortaTheme { Sorta() } }
    }
}

@Composable
fun SortaTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun Sorta(vm: VM = viewModel()) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var granted by remember { mutableStateOf(hasPerm(ctx)) }
    var settings by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Uri?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Uri>()) }
    val permLauncher = rememberLauncherForActivityResult(RequestMultiplePermissions()) { granted = hasPerm(ctx); if (granted) vm.load() }
    val deleter = rememberLauncherForActivityResult(StartIntentSenderForResult()) { if (it.resultCode == android.app.Activity.RESULT_OK) vm.load() }
    LaunchedEffect(Unit) { if (granted) vm.load() else permLauncher.launch(PERMS) }

    fun requestDelete(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (vm.silentDelete && Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            scope.launch {
                val ok = vm.deleteDirect(uris)
                vm.load()
                snack.showSnackbar(if (ok == uris.size) "Deleted" else "Deleted $ok of ${uris.size}")
            }
        } else {
            runCatching {
                deleter.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(ctx.contentResolver, uris).intentSender).build())
            }
        }
    }

    fun performBatch(a: Act, targets: List<Media>) {
        if (targets.isNotEmpty()) when (a) {
            Act.NONE -> {}
            Act.SHARE -> runCatching {
                val intent = if (targets.size == 1) {
                    Intent(Intent.ACTION_SEND).setType(targets[0].mime).putExtra(Intent.EXTRA_STREAM, targets[0].uri)
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(targets.map { it.uri }))
                }
                ctx.startActivity(Intent.createChooser(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
            }
            Act.DELETE -> requestDelete(targets.map { it.uri })
            Act.NAS -> {
                val snapshot = targets
                scope.launch {
                    val label = if (snapshot.size == 1) snapshot[0].name else "${snapshot.size} items"
                    scope.launch { snack.showSnackbar("Sending $label…", duration = SnackbarDuration.Indefinite) }
                    var ok = 0
                    val sent = mutableListOf<Uri>()
                    for (m in snapshot) {
                        val r = vm.upload(m)
                        if (r.isSuccess) { ok++; sent += m.uri }
                    }
                    snack.currentSnackbarData?.dismiss()
                    snack.showSnackbar(if (ok == snapshot.size) "Sent $ok to NAS ✓" else "Sent $ok of ${snapshot.size} to NAS")
                    if (vm.deleteAfterNas && sent.isNotEmpty()) requestDelete(sent)
                }
            }
        }
        selectionMode = false
        selected = emptySet()
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (selectionMode) "${selected.size} selected" else "Sorta", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        if (selectionMode) IconButton({ selectionMode = false; selected = emptySet() }) {
                            Icon(Icons.Rounded.Close, "Cancel selection")
                        }
                    },
                    actions = {
                        if (!selectionMode) {
                            IconButton({ selectionMode = true }) { Icon(Icons.Rounded.SelectAll, "Select") }
                            IconButton({ settings = true }) { Icon(Icons.Rounded.Settings, "Settings") }
                        }
                    },
                )
