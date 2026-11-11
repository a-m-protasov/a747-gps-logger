package io.github.a_m_protasov.a747.ui

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.a_m_protasov.a747.A747App
import io.github.a_m_protasov.a747.DeviceState
import io.github.a_m_protasov.a747.Item
import io.github.a_m_protasov.a747.Link
import io.github.a_m_protasov.a747.R
import io.github.a_m_protasov.a747.Track
import io.github.a_m_protasov.a747.Waypoint
import io.github.a_m_protasov.a747.core.DeviceInfo
import io.github.a_m_protasov.a747.device.DownloadService
import java.io.File

private enum class Tab(val title: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    DEVICE(R.string.device, Icons.Filled.SatelliteAlt),
    TRACKS(R.string.tracks, Icons.Filled.Route),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun A747Root(
    onConnectUsb: () -> Unit,
    pairedDevices: () -> List<Pair<String, BluetoothDevice>>,
    onConnectBluetooth: (BluetoothDevice) -> Unit,
) {
    val repo = A747App.repo
    UI.ctx = LocalContext.current.applicationContext
    var tab by rememberSaveable { mutableStateOf(Tab.DEVICE) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var openItem by rememberSaveable { mutableStateOf<String?>(null) }
    val items by repo.items.collectAsStateWithLifecycle()
    val waypoints by repo.waypoints.collectAsStateWithLifecycle()
    val message by repo.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); repo.message.value = null }
    }

    val item = items.firstOrNull { it.key == openItem }
    if (settings) {
        BackHandler { settings = false }
        SettingsScreen(onBack = { settings = false })
        return
    }
    if (item != null) {
        BackHandler { openItem = null }
        when (item) {
            is Track -> TrackDetail(item, waypoints, onBack = { openItem = null })
            is Waypoint -> WaypointDetail(item, onBack = { openItem = null })
        }
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(tab.title)) },
                actions = {
                    when (tab) {
                        Tab.TRACKS -> if (items.isNotEmpty()) ExportMenu(items)
                        Tab.DEVICE -> {}
                    }
                    IconButton(onClick = { settings = true }) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                for (t in Tab.entries) {
                    val count = if (t == Tab.TRACKS) items.size else 0
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            BadgedBox(badge = { if (count > 0) Badge { Text("$count") } }) { Icon(t.icon, null) }
                        },
                        label = { Text(stringResource(t.title)) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                Tab.DEVICE -> DeviceScreen(onConnectUsb, pairedDevices, onConnectBluetooth)
                Tab.TRACKS -> LibraryScreen(items) { openItem = it.key }
            }
        }
    }
}

// MARK: Device

@Composable
private fun DeviceScreen(
    onConnectUsb: () -> Unit,
    pairedDevices: () -> List<Pair<String, BluetoothDevice>>,
    onConnectBluetooth: (BluetoothDevice) -> Unit,
) {
    val repo = A747App.repo
    val state by repo.device.collectAsStateWithLifecycle()
    val live by repo.live.collectAsStateWithLifecycle()
    var confirm by remember { mutableIntStateOf(0) }
    var btList by remember { mutableStateOf<List<Pair<String, BluetoothDevice>>?>(null) }
    val ctx = LocalContext.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.logger), null, tint = Color.Unspecified, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text("i-Blue 747A+", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(
                        when (val s = state) {
                            DeviceState.Disconnected -> R.string.not_connected
                            DeviceState.Connecting -> R.string.connecting
                            is DeviceState.Failed -> R.string.connection_problem
                            is DeviceState.Ready -> if (s.link == Link.USB) R.string.connected_by_usb else R.string.connected_by_bluetooth
                            is DeviceState.Busy -> if (s.link == Link.USB) R.string.connected_by_usb else R.string.connected_by_bluetooth
                        },
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when (val s = state) {
            DeviceState.Disconnected, is DeviceState.Failed -> {
                if (s is DeviceState.Failed) Banner(Icons.Outlined.Warning, MaterialTheme.colorScheme.error, s.message)
                InfoCard(stringResource(R.string.connect_the_logger), Icons.Filled.Usb) {
                    Text(stringResource(R.string.turn_the_logger_on_and_connect_it_with_a_usb_otg), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onConnectUsb) {
                            Icon(Icons.Filled.Usb, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.connect_by_usb))
                        }
                        OutlinedButton(onClick = { btList = pairedDevices() }) {
                            Icon(Icons.Filled.Bluetooth, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.connect_by_bluetooth))
                        }
                    }
                    Text(stringResource(R.string.if_nothing_happens_try_another_cable_many_cables), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                }
            }
            DeviceState.Connecting -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is DeviceState.Ready -> Details(s.info, live, busy = null, onDownload = { DownloadService.start(ctx, false) }, onErase = { confirm = 1 })
            is DeviceState.Busy -> Details(s.info, live, busy = stringResource(s.what) to s.progress, onDownload = {}, onErase = {})
        }
    }

    btList?.let { list ->
        AlertDialog(
            onDismissRequest = { btList = null },
            title = { Text(stringResource(R.string.paired_devices)) },
            text = {
                if (list.isEmpty()) Text(stringResource(R.string.no_paired_bluetooth_devices_pair_the_logger_in_t))
                else Column { list.forEach { (name, dev) ->
                    ListItem(headlineContent = { Text(name) }, leadingContent = { Icon(Icons.Filled.Bluetooth, null) },
                        modifier = Modifier.clickable { btList = null; onConnectBluetooth(dev) })
                } }
            },
            confirmButton = {
                TextButton(onClick = { btList = null; ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
                    Text(stringResource(R.string.open_bluetooth_settings))
                }
            },
            dismissButton = { TextButton(onClick = { btList = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirm == 1) AlertDialog(
        onDismissRequest = { confirm = 0 },
        title = { Text(stringResource(R.string.erase_the_logger_s_memory)) },
        text = { Text(stringResource(R.string.the_log_is_downloaded_and_saved_first_erasing_ha)) },
        confirmButton = { TextButton(onClick = { confirm = 2 }) { Text(stringResource(R.string.continue_action)) } },
        dismissButton = { TextButton(onClick = { confirm = 0 }) { Text(stringResource(R.string.cancel)) } },
    )
    if (confirm == 2) AlertDialog(
        onDismissRequest = { confirm = 0 },
        icon = { Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.this_cannot_be_undone)) },
        text = { Text(stringResource(R.string.all_tracks_on_the_logger_will_be_erased_after_th)) },
        confirmButton = {
            TextButton(onClick = { confirm = 0; DownloadService.start(ctx, true) }) {
                Text(stringResource(R.string.download_and_erase), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirm = 0 }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Banner(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, text: String) {
    Row(
        Modifier.fillMaxWidth().background(tint.copy(alpha = 0.12f), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(10.dp))
        Text(text)
    }
}

@Composable
private fun Details(info: DeviceInfo, live: io.github.a_m_protasov.a747.core.LiveStatus?, busy: Pair<String, Float?>?,
                    onDownload: () -> Unit, onErase: () -> Unit) {
    if (info.wrapped) Banner(Icons.Outlined.Warning, MaterialTheme.colorScheme.error,
        stringResource(R.string.the_log_has_passed_the_real_memory_size_the_olde))
    else if (!info.loggingOn) Banner(Icons.Outlined.PauseCircle, MaterialTheme.colorScheme.outline,
        stringResource(R.string.logging_is_off_move_the_logger_s_switch_to_log_t))
    if (!info.wrapped && info.usedFraction > 0.75) Banner(Icons.Outlined.Warning, WaypointColor,
        stringResource(R.string.memory_is_n_percent_full_this_logger_has_4_mb_no, (info.usedFraction * 100).toInt()))

    if (busy != null) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(busy.first)
            if (busy.second != null) LinearProgressIndicator({ busy.second!! }, Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDownload) { Icon(Icons.Filled.Download, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.download)) }
            FilledTonalButton(onClick = onErase) {
                Icon(Icons.Outlined.DeleteForever, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.download_and_erase_2))
            }
        }
    }

    InfoCard(stringResource(R.string.memory), Icons.Filled.Memory) {
        Text(stringResource(R.string.used_x_of_x, UI.bytes(info.usedBytes), UI.bytes(info.flashSize)))
        LinearProgressIndicator({ info.usedFraction.toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(8.dp),
            color = if (info.usedFraction > 0.75) WaypointColor else MaterialTheme.colorScheme.primary)
        InfoRow(stringResource(R.string.records), "${info.recordCount}")
        info.remainingHours?.let { InfoRow(stringResource(R.string.room_left), stringResource(R.string.about_n_h_of_logging, it.toInt())) }
        InfoRow(stringResource(R.string.when_full), stringResource(if (info.stopWhenFull) R.string.stop else R.string.overwrite))
        InfoRow(stringResource(R.string.logging), stringResource(if (info.loggingOn) R.string.on else R.string.off))
    }
    InfoCard(stringResource(R.string.satellites), Icons.Filled.SatelliteAlt) {
        if (live != null) {
            InfoRow(stringResource(R.string.position_fix), stringResource(if (live.hasFix) R.string.yes else R.string.searching))
            InfoRow(stringResource(R.string.satellites_in_use), "${live.satellites}")
            live.hdop?.let { InfoRow("HDOP", "%.1f".format(it)) }
        } else Text(stringResource(R.string.waiting_for_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.battery_level_is_not_reported_by_this_logger), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline)
    }
    InfoCard(stringResource(R.string.log_settings), Icons.Filled.Tune) {
        InfoRow(stringResource(R.string.every), if (info.periodTenthsS > 0) "%.1f s".format(info.periodTenthsS / 10.0).replace(".0 s", " s") else "—")
        InfoRow(stringResource(R.string.or_every), if (info.distanceTenthsM > 0) UI.distance(info.distanceTenthsM / 10.0) else "—")
        InfoRow(stringResource(R.string.above_speed), if (info.speedTenthsKmh > 0) UI.speed(info.speedTenthsKmh / 10.0) else "—")
        InfoRow(stringResource(R.string.fields), fieldNames(info.format))
        Text(stringResource(R.string.settings_are_read_only_in_this_version), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline)
    }
    InfoCard(stringResource(R.string.about_the_device), Icons.Filled.Info) {
        InfoRow(stringResource(R.string.model), info.model)
        InfoRow(stringResource(R.string.firmware), info.firmware)
        InfoRow(stringResource(R.string.flash_chip), "%08X".format(info.flashId))
        InfoRow(stringResource(R.string.clock), stringResource(R.string.s_1024_weeks_behind_corrected_automatically))
    }
}

private fun fieldNames(mask: Long): String {
    val names = mapOf(0 to "UTC", 1 to "VALID", 2 to "LAT", 3 to "LON", 4 to "HEIGHT", 5 to "SPEED", 6 to "HEADING",
        7 to "DSTA", 8 to "DAGE", 9 to "PDOP", 10 to "HDOP", 11 to "VDOP", 12 to "NSAT", 13 to "SID", 14 to "ELEV",
        15 to "AZIM", 16 to "SNR", 17 to "RCR", 18 to "MS", 19 to "DIST")
    return names.keys.sorted().filter { mask and (1L shl it) != 0L }.joinToString(" · ") { names[it]!! }
}

// MARK: Tracks

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryScreen(items: List<Item>, onOpen: (Item) -> Unit) {
    if (items.isEmpty()) return Empty(Icons.Filled.Route, R.string.no_tracks_yet, R.string.connect_the_logger_and_download_its_memory)
    val days = items.groupBy { it.day }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<Item?>(null) }
    var deleting by remember { mutableStateOf<Item?>(null) }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        for ((day, list) in days) {
            item(key = "d$day") {
                Text(UI.day(day), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            items(list, key = { it.key }) { i ->
                Box {
                    ItemRow(i, Modifier.combinedClickable(onClick = { onOpen(i) }, onLongClick = { menuFor = i.key }))
                    DropdownMenu(expanded = menuFor == i.key, onDismissRequest = { menuFor = null }, offset = DpOffset(72.dp, 0.dp)) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = { menuFor = null; renaming = i })
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = { menuFor = null; deleting = i })
                    }
                }
            }
        }
    }
    renaming?.let { RenameDialog(it) { renaming = null } }
    deleting?.let { DeleteDialog(it, onDone = { deleting = null }) }
}

@Composable
private fun ItemRow(i: Item, modifier: Modifier) {
    val (title, sub) = when (i) {
        is Track -> {
            val span = "${UI.time(i.stats.startUtc, i.zone)} – ${UI.time(i.stats.endUtc, i.zone)}"
            val size = "${UI.distance(i.stats.distanceM)} · ${UI.duration(i.stats.durationS)}"
            if (i.customName != null) i.customName to "$span · $size" else span to size
        }
        is Waypoint -> {
            val time = UI.time(i.point.utc, i.zone)
            val coords = UI.coordinates(i.point)
            if (i.customName != null) i.customName to "$time · $coords" else time to coords
        }
    }
    ListItem(
        modifier = modifier,
        leadingContent = {
            if (i is Track) Dot(Icons.Filled.Route, MaterialTheme.colorScheme.primary) else Dot(Icons.Filled.Place, WaypointColor)
        },
        headlineContent = { Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

/** Name field; the default name (date and time) shows as the hint, and an empty field restores it. */
@Composable
private fun RenameDialog(item: Item, onDone: () -> Unit) {
    var text by remember { mutableStateOf(item.customName ?: "") }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.rename)) },
        text = {
            OutlinedTextField(text, { text = it }, placeholder = { Text(item.name) }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { A747App.repo.rename(item, text); onDone() }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun DeleteDialog(item: Item, onDone: () -> Unit, onDeleted: () -> Unit = {}) {
    AlertDialog(
        onDismissRequest = onDone,
        icon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.delete_the_selected_items)) },
        text = { Text("${item.title}\n\n" + stringResource(R.string.deleted_tracks_and_waypoints_won_t_come_back_aft)) },
        confirmButton = {
            TextButton(onClick = { A747App.repo.delete(listOf(item)); onDone(); onDeleted() }) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Rename and delete buttons for a detail screen's top bar. */
@Composable
private fun EditActions(item: Item, onDeleted: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, stringResource(R.string.rename)) }
    IconButton(onClick = { deleting = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
    if (renaming) RenameDialog(item) { renaming = false }
    if (deleting) DeleteDialog(item, onDone = { deleting = false }, onDeleted = onDeleted)
}

@Composable
private fun Dot(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Box(Modifier.size(40.dp).background(color.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = color)
    }
}

@Composable
private fun Empty(icon: androidx.compose.ui.graphics.vector.ImageVector, title: Int, text: Int) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(text), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackDetail(track: Track, allWaypoints: List<Waypoint>, onBack: () -> Unit) {
    val repo = A747App.repo
    var showWpts by remember { mutableStateOf(repo.showWaypointsOnTrack) }
    val wpts = if (showWpts) allWaypoints.filter { it.point.utc in track.stats.startUtc..track.stats.endUtc } else emptyList()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(track.customName ?: UI.time(track.stats.startUtc, track.zone, withDate = true), maxLines = 1,
                overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            actions = {
                IconButton(onClick = { showWpts = !showWpts }) {
                    Icon(Icons.Filled.Place, stringResource(R.string.show_waypoints_made_during_this_track),
                        tint = if (showWpts) WaypointColor else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ExportMenu(listOf(track))
                EditActions(track, onDeleted = onBack)
            },
        )
    }) { pad ->
        // the map gets 2/3 of the screen and is not inside a scrolling container, so its pan and
        // zoom gestures are not fought over; the stats scroll in the remaining third
        Column(Modifier.padding(pad).fillMaxSize()) {
            MapWeb(listOf(track.points), wpts, Modifier.fillMaxWidth().weight(2f))
            val st = track.stats
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat(R.string.distance, UI.distance(st.distanceM))
                    Stat(R.string.duration, UI.duration(st.durationS))
                    Stat(R.string.moving, UI.duration(st.movingS))
                    Stat(R.string.average_speed, UI.speed(st.avgSpeedKmh))
                    Stat(R.string.max_speed, UI.speed(st.maxSpeedKmh))
                    Stat(R.string.ascent, UI.height(st.ascentM))
                    Stat(R.string.descent, UI.height(st.descentM))
                    Stat(R.string.points, "${st.points}")
                }
                val step = maxOf(1, track.points.size / 400)
                val sampled = track.points.filterIndexed { i, _ -> i % step == 0 }
                val speeds = sampled.mapNotNull { it.speed?.let(UI::speedValue) }
                val heights = sampled.mapNotNull { it.height?.let(UI::heightValue) }
                if (speeds.size > 1) {
                    Text(stringResource(R.string.speed), style = MaterialTheme.typography.titleMedium)
                    MiniChart(speeds, MaterialTheme.colorScheme.primary, fill = true)
                }
                if (heights.size > 1) {
                    Text(stringResource(R.string.elevation), style = MaterialTheme.typography.titleMedium)
                    MiniChart(heights, WaypointColor, fill = false)
                }
            }
        }
    }
}

@Composable
private fun Stat(title: Int, value: String) {
    Column {
        Text(stringResource(title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

// MARK: Waypoints

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WaypointDetail(w: Waypoint, onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(w.customName ?: UI.time(w.point.utc, w.zone, withDate = true), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            actions = {
                ExportMenu(listOf(w))
                EditActions(w, onDeleted = onBack)
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // one waypoint: the map zooms in on it
            MapWeb(emptyList(), listOf(w), Modifier.fillMaxWidth().weight(2f))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat(R.string.time, UI.time(w.point.utc, w.zone, withDate = true))
                    Stat(R.string.coordinates, UI.coordinates(w.point))
                    w.point.height?.let { Stat(R.string.elevation, UI.height(it)) }
                }
            }
        }
    }
}

// MARK: Export

private enum class Format(val ext: String, val mime: String) {
    GPX("gpx", "application/gpx+xml"), KML("kml", "application/vnd.google-earth.kml+xml"),
    CSV("csv", "text/csv"), BIN("bin", "application/octet-stream"),
}

/**
 * "Share" and "Save to file" for tracks and waypoints together. CSV has no place for waypoints
 * next to tracks, so there they become one-point tracks named after the waypoint.
 */
@Composable
private fun ExportMenu(items: List<Item>) {
    val repo = A747App.repo
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pair<ByteArray, String>?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val p = pending ?: return@rememberLauncherForActivityResult
        if (uri != null) ctx.contentResolver.openOutputStream(uri)?.use { it.write(p.first) }
        pending = null
    }
    fun content(f: Format): Pair<ByteArray, String> {
        val ex = repo.exporter
        val tracks = items.filterIsInstance<Track>().map { it.points }.sortedBy { it[0].utc }
        val wpts = items.filterIsInstance<Waypoint>().map { it.point }.sortedBy { it.utc }
        val a = ex.name(items.minBy { it.utc }.let { if (it is Track) it.points[0] else (it as Waypoint).point }).take(10)
        val b = ex.name(items.maxBy { it.utc }.let { if (it is Track) it.points[0] else (it as Waypoint).point }).take(10)
        val base = if (a == b) "a747-$a" else "a747-${a}_$b"
        val text = when (f) {
            Format.GPX -> ex.gpx(tracks, wpts)
            Format.KML -> ex.kml(tracks, wpts)
            Format.CSV -> if (tracks.isEmpty()) ex.csvWaypoints(wpts) else ex.csv(tracks + wpts.map { listOf(it) })
            Format.BIN -> null
        }
        val bytes = text?.toByteArray() ?: repo.library.lastImage?.readBytes() ?: ByteArray(0)
        return bytes to "$base.${f.ext}"
    }
    val formats = if (items.any { it is Track }) Format.entries else listOf(Format.GPX, Format.KML, Format.CSV)
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.Share, stringResource(R.string.share)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(stringResource(R.string.share), style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.primary)
            for (f in formats) DropdownMenuItem(text = { Text(f.name) }, onClick = {
                open = false
                val (bytes, name) = content(f)
                val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
                val file = File(dir, name).apply { writeBytes(bytes) }
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
                val send = Intent(Intent.ACTION_SEND).setType(f.mime).putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(Intent.createChooser(send, name))
            })
            HorizontalDivider()
            Text(stringResource(R.string.save_to_file), style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.primary)
            for (f in formats) DropdownMenuItem(text = { Text(f.name) }, onClick = {
                open = false
                pending = content(f)
                save.launch(pending!!.second)
            })
        }
    }
}

// MARK: Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val repo = A747App.repo
    var units by remember { mutableStateOf(repo.units) }
    var gap by remember { mutableIntStateOf(repo.splitGapMinutes) }
    var showWpts by remember { mutableStateOf(repo.showWaypointsOnTrack) }
    var bySpeed by remember { mutableStateOf(repo.trackColorBySpeed) }
    var color by remember { mutableStateOf(repo.trackColor) }
    val ctx = LocalContext.current
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.settings)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } })
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.units), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp))
            for ((key, title) in listOf("system" to R.string.as_in_system_settings, "metric" to R.string.metric_km_m, "imperial" to R.string.imperial_mi_ft)) {
                ListItem(headlineContent = { Text(stringResource(title)) },
                    leadingContent = { RadioButton(selected = units == key, onClick = null) },
                    modifier = Modifier.clickable { units = key; repo.units = key })
            }
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.new_track_after_a_pause_of_x, UI.duration(gap * 60L))) },
                supportingContent = {
                    androidx.compose.material3.Slider(value = gap.toFloat(), onValueChange = { gap = (it / 5).toInt() * 5 },
                        onValueChangeFinished = { repo.splitGapMinutes = gap }, valueRange = 5f..240f)
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.color_by_speed)) },
                trailingContent = { Switch(bySpeed, onCheckedChange = { bySpeed = it; repo.trackColorBySpeed = it }) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.track_color)) },
                supportingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                        for (hex in listOf("#1F8A70", "#2F6BFF", "#F2994A", "#E5484D", "#8E4EC6", "#222222")) {
                            val c = Color(android.graphics.Color.parseColor(hex))
                            Box(
                                Modifier.size(32.dp).background(c.copy(alpha = if (bySpeed) 0.35f else 1f), CircleShape)
                                    .then(if (hex == color && !bySpeed) Modifier.padding(3.dp).background(Color.White, CircleShape)
                                        .padding(3.dp).background(c, CircleShape) else Modifier)
                                    .clickable(enabled = !bySpeed) { color = hex; repo.trackColor = hex },
                            )
                        }
                    }
                },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.show_waypoints_on_track_maps)) },
                trailingContent = { Switch(showWpts, onCheckedChange = { showWpts = it; repo.showWaypointsOnTrack = it }) },
            )
            HorizontalDivider()
            ListItem(headlineContent = { Text(stringResource(R.string.project_page)) },
                supportingContent = { Text("github.com/a-m-protasov/a747-gps-logger") },
                modifier = Modifier.clickable { openUrl(ctx, "https://github.com/a-m-protasov/a747-gps-logger") })
        }
    }
}
