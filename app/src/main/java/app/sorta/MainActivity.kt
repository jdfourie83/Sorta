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
    fun changeColumns(n: Int) { columns = n; prefs.edit().putInt("columns", n).apply() }
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

    val titleText = if (selectionMode) "${selected.size} selected" else "Sorta"

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(titleText, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        if (selectionMode) {
                            IconButton(onClick = { selectionMode = false; selected = emptySet() }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Cancel selection")
                            }
                        }
                    },
                    actions = {
                        if (!selectionMode) {
                            IconButton(onClick = { selectionMode = true }) {
                                Icon(Icons.Rounded.SelectAll, contentDescription = "Select")
                            }
                            IconButton(onClick = { settings = true }) {
                                Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                            }
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snack) },
        ) { pad ->
            if (!granted) Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
                Button({ permLauncher.launch(PERMS) }) { Text("Allow access to photos & videos") }
            } else LazyVerticalGrid(
                GridCells.Fixed(vm.columns), Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(vm.items, key = { it.uri.toString() }) { m ->
                    Tile(
                        m = m,
                        playing = preview == m.uri,
                        selectionMode = selectionMode,
                        isSelected = m.uri in selected,
                        vm = vm,
                        onPreview = { on -> preview = if (on) m.uri else if (preview == m.uri) null else preview },
                        onToggleSelect = { selected = if (m.uri in selected) selected - m.uri else selected + m.uri },
                        onAct = { act ->
                            val targets = if (selectionMode && selected.isNotEmpty()) vm.items.filter { it.uri in selected } else listOf(m)
                            performBatch(act, targets)
                        },
                    )
                }
            }
        }
        AnimatedVisibility(settings, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            BackHandler { settings = false }
            SettingsScreen(vm) { settings = false }
        }
    }
}

@Composable
fun Tile(
    m: Media,
    playing: Boolean,
    selectionMode: Boolean,
    isSelected: Boolean,
    vm: VM,
    onPreview: (Boolean) -> Unit,
    onToggleSelect: () -> Unit,
    onAct: (Act) -> Unit,
) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val off = remember { Animatable(0f) }
    val thresh = with(LocalDensity.current) { 100.dp.toPx() }
    val pending = if (off.value > thresh) vm.right else if (off.value < -thresh) vm.left else Act.NONE

    Box(
        Modifier.aspectRatio(1f).zIndex(if (off.value != 0f) 1f else 0f)
            .graphicsLayer { translationX = off.value * .6f }
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val a = if (off.value > thresh) vm.right else if (off.value < -thresh) vm.left else Act.NONE
                        if (a != Act.NONE) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onAct(a) }
                        scope.launch { off.animateTo(0f, spring(dampingRatio = .7f)) }
                    },
                    onDragCancel = { scope.launch { off.animateTo(0f) } },
                ) { change, d -> change.consume(); scope.launch { off.snapTo(off.value + d) } }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        if (selectionMode) onToggleSelect()
                        else runCatching {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(m.uri, m.mime)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }
                    },
                    onDoubleTap = { onAct(vm.dbl) },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (selectionMode) onToggleSelect()
                        else if (m.video) onPreview(true)
                    },
                )
            }
    ) {
        AsyncImage(m.uri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (playing) Preview(m.uri) { onPreview(false) }
        if (m.video && !playing) Icon(Icons.Rounded.PlayArrow, null, tint = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                .background(Color.Black.copy(.45f), CircleShape).size(20.dp))
        if (pending != Act.NONE) Text(pending.label, color = Color.White, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(.65f), CircleShape)
                .padding(horizontal = 10.dp, vertical = 4.dp))
        if (selectionMode) Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(.35f)),
            contentAlignment = Alignment.Center,
        ) { if (isSelected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
    }
}

/** Muted, 5-second in-tile preview; calls done() when finished. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun Preview(uri: Uri, done: () -> Unit) {
    val ctx = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            volume = 0f
            setMediaItem(MediaItem.Builder().setUri(uri)
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(5000).build()).build())
            prepare(); playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) done() }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release(); done() }
    }
    AndroidView(factory = {
        PlayerView(it).apply {
            this.player = player; useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            setShutterBackgroundColor(0)
        }
    }, modifier = Modifier.fillMaxSize())
}

@Composable
fun SettingsScreen(vm: VM, close: () -> Unit) {
    val ctx = LocalContext.current
    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") },
            navigationIcon = { IconButton(close) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Gestures", style = MaterialTheme.typography.titleMedium)
            Picker("Swipe left", vm.left) { vm.setAct("left", it) }
            Picker("Swipe right", vm.right) { vm.setAct("right", it) }
            Picker("Double tap", vm.dbl) { vm.setAct("double", it) }
            Text("Single tap opens the photo or video. Long-press a video for a 5-second preview. Tap the select icon in the top bar to choose several tiles, then swipe or double-tap any of the whole selection.",
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Layout", style = MaterialTheme.typography.titleMedium)
            ColumnsPicker(vm.columns) { vm.changeColumns(it) }
            Text("Fewer columns give each tile more room to swipe before your finger reaches the edge of the screen.",
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Deleting", style = MaterialTheme.typography.titleMedium)
            SwitchRow("Delete from phone after sending to NAS", vm.deleteAfterNas) { vm.setBool("delAfterNas", it) }
            SwitchRow("Delete without confirmation", vm.silentDelete) { checked ->
                vm.setBool("silentDelete", checked)
                if (checked && Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                    runCatching {
                        ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${ctx.packageName}")))
                    }
                }
            }
            if (vm.silentDelete) Text(
                "Grant \"All files access\" when prompted, or in system Settings, or deletes will fall back to asking each time. A quick status still shows at the bottom after every delete.",
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider()
            Text("Ugreen NAS (WebDAV)", style = MaterialTheme.typography.titleMedium)
            Field(vm, "url", "Address (e.g. http://192.168.1.20:5005)")
            Field(vm, "dir", "Folder (e.g. Photos/Sorta)")
            Field(vm, "user", "Username")
            Field(vm, "pass", "Password", password = true)
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked, onChange)
    }
}

@Composable
fun Field(vm: VM, key: String, label: String, password: Boolean = false) {
    OutlinedTextField(vm.nas[key].orEmpty(), { vm.setNas(key, it) }, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(label) },
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
}

@Composable
fun Picker(label: String, value: Act, set: (Act) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }) {
        OutlinedTextField(value.label, {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true,
            label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) })
        ExposedDropdownMenu(open, { open = false }) {
            Act.entries.forEach { a -> DropdownMenuItem({ Text(a.label) }, { set(a); open = false }) }
        }
    }
}

@Composable
fun ColumnsPicker(value: Int, set: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = if (value == 1) "1 column" else "$value columns"
    ExposedDropdownMenuBox(open, { open = it }) {
        OutlinedTextField(label, {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true,
            label = { Text("Grid columns") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) })
        ExposedDropdownMenu(open, { open = false }) {
            listOf(1, 2, 3).forEach { n ->
                DropdownMenuItem({ Text(if (n == 1) "1 column" else "$n columns") }, { set(n); open = false })
            }
        }
    }
}
