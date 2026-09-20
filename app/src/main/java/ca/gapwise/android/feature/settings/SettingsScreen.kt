package ca.gapwise.android.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ca.gapwise.android.core.model.Meeting
import ca.gapwise.android.core.persistence.AppPreferences
import ca.gapwise.android.core.persistence.AppThemeMode
import ca.gapwise.android.core.persistence.CommuteMode
import ca.gapwise.android.core.persistence.DayOrigin
import ca.gapwise.android.core.persistence.RiskTolerance
import ca.gapwise.android.core.persistence.RouteMode
import ca.gapwise.android.data.account.AccountIdentity
import ca.gapwise.android.data.account.AuthProvider
import kotlin.math.roundToInt

private const val GAPWISE_WEB = "https://gapwise.ca"
private const val GAPWISE_AI = "https://ai.gapwise.ca"
private const val GAPWISE_MCP = "https://ai.gapwise.ca/api/mcp"

private data class Choice(val id: String, val label: String)

private val Residences = listOf(
    Choice("EH", "Erindale Hall"),
    Choice("LL", "Leacock Lane"),
    Choice("MV", "MaGrath Valley"),
    Choice("MC", "McLuhan Court"),
    Choice("OPH", "Oscar Peterson Hall"),
    Choice("PP", "Putnam Place"),
    Choice("RIH", "Roy Ivor Hall"),
    Choice("SW", "Schreiberwood"),
    Choice("NRB", "New Residence Building"),
)

private val TransitPoints = listOf(
    Choice("miway-utm-bus-station", "UTM Bus Station (MiWay)"),
    Choice("utm-shuttle-instructional-centre", "UTM Shuttle — Instructional Centre"),
)

private val ParkingPoints = listOf(
    Choice("parking-p8", "Parking Lot P8"),
    Choice("parking-p9", "Parking Lot P9"),
)

@Composable
fun SettingsScreen(
    meetings: List<Meeting>,
    importStatus: String?,
    themeMode: AppThemeMode,
    account: AccountIdentity?,
    syncEnabled: Boolean,
    syncBusy: Boolean,
    syncStatus: String?,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onImport: () -> Unit,
    onClearTimetable: () -> Unit,
    onSignIn: (AuthProvider) -> Unit,
    onSignOut: () -> Unit,
    onSetSyncEnabled: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onLoadSync: () -> Unit,
    onDeleteSync: () -> Unit,
    onDeleteAccount: (clearLocal: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember { AppPreferences(context.applicationContext) }

    var routeMode by remember { mutableStateOf(preferences.routeMode()) }
    var walkingSpeed by remember { mutableStateOf(preferences.walkingSpeedMps()) }
    var transitionBuffer by remember { mutableStateOf(preferences.transitionBufferMinutes()) }
    var dayOrigin by remember { mutableStateOf(preferences.dayOrigin()) }
    var commuteMode by remember { mutableStateOf(preferences.commuteMode()) }
    var residenceCode by remember { mutableStateOf(preferences.residenceBuildingCode() ?: "OPH") }
    var accessPointId by remember { mutableStateOf(preferences.campusAccessPointId()) }

    var setupMinutes by remember { mutableStateOf(preferences.setupMinutes()) }
    var packUpMinutes by remember { mutableStateOf(preferences.packUpMinutes()) }
    var lunchStart by remember { mutableStateOf(preferences.lunchWindowStart()) }
    var lunchEnd by remember { mutableStateOf(preferences.lunchWindowEnd()) }
    var mealDuration by remember { mutableStateOf(preferences.mealDurationMinutes()) }
    var willingToLeaveCampus by remember { mutableStateOf(preferences.willingToLeaveCampus()) }
    var homeCommute by remember { mutableStateOf(preferences.oneWayHomeCommuteMinutes()) }
    var minimumHomeStay by remember { mutableStateOf(preferences.minimumHomeStayMinutes()) }
    var homeTurnaround by remember { mutableStateOf(preferences.homeTurnaroundMinutes()) }
    var riskTolerance by remember { mutableStateOf(preferences.riskTolerance()) }

    var deleteSyncOpen by remember { mutableStateOf(false) }
    var deleteAccountOpen by remember { mutableStateOf(false) }
    var clearLocalOnDelete by remember { mutableStateOf(false) }

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        PreferenceSection("Appearance") {
            Text("Theme", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChoice(
                    label = "Light",
                    selected = themeMode == AppThemeMode.LIGHT,
                    onClick = { onThemeModeChange(AppThemeMode.LIGHT) },
                )
                ThemeChoice(
                    label = "Dark",
                    selected = themeMode == AppThemeMode.DARK,
                    onClick = { onThemeModeChange(AppThemeMode.DARK) },
                )
            }
        }

        SectionDivider()

        PreferenceSection("Route options") {
            Text(
                "The same walking assumptions used by the web route controls.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                RouteMode.entries.forEach { mode ->
                    FilterChip(
                        selected = routeMode == mode,
                        onClick = {
                            routeMode = mode
                            preferences.setRouteMode(mode)
                        },
                        label = { Text(mode.label) },
                    )
                }
            }
            SettingSlider(
                label = "Walking speed",
                valueLabel = "${"%.2f".format(walkingSpeed)} m/s",
                value = walkingSpeed,
                range = 0.5f..2.5f,
                steps = 39,
                onValueChange = { walkingSpeed = it },
                onValueChangeFinished = { preferences.setWalkingSpeedMps(walkingSpeed) },
            )
            SettingSlider(
                label = "Transition buffer",
                valueLabel = "$transitionBuffer min",
                value = transitionBuffer.toFloat(),
                range = 0f..30f,
                steps = 29,
                onValueChange = { transitionBuffer = it.roundToInt() },
                onValueChangeFinished = { preferences.setTransitionBufferMinutes(transitionBuffer) },
            )
        }

        SectionDivider()

        PreferenceSection("Campus arrival") {
            Text(
                "Gapwise stores where your campus walk begins — never your home address.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = dayOrigin == DayOrigin.RESIDENCE,
                    onClick = {
                        dayOrigin = DayOrigin.RESIDENCE
                        commuteMode = null
                        accessPointId = null
                        preferences.setDayOrigin(dayOrigin)
                        preferences.setCommuteMode(null)
                        preferences.setCampusAccessPointId(null)
                        preferences.setResidenceBuildingCode(residenceCode)
                    },
                    label = { Text("Live on campus") },
                )
                FilterChip(
                    selected = dayOrigin == DayOrigin.COMMUTE,
                    onClick = {
                        dayOrigin = DayOrigin.COMMUTE
                        preferences.setDayOrigin(dayOrigin)
                        preferences.setResidenceBuildingCode(null)
                    },
                    label = { Text("Commute") },
                )
            }

            if (dayOrigin == DayOrigin.RESIDENCE) {
                Text("Residence building", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Residences, key = { it.id }) { residence ->
                        FilterChip(
                            selected = residenceCode == residence.id,
                            onClick = {
                                residenceCode = residence.id
                                preferences.setResidenceBuildingCode(residence.id)
                            },
                            label = { Text("${residence.label} (${residence.id})") },
                        )
                    }
                }
            } else {
                Text("How you arrive", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(CommuteMode.entries) { mode ->
                        FilterChip(
                            selected = commuteMode == mode,
                            onClick = {
                                commuteMode = mode
                                accessPointId = null
                                preferences.setCommuteMode(mode)
                                preferences.setCampusAccessPointId(null)
                            },
                            label = { Text(mode.label) },
                        )
                    }
                }
                val points = when (commuteMode) {
                    CommuteMode.TRANSIT -> TransitPoints
                    CommuteMode.PARKING -> ParkingPoints
                    CommuteMode.PICKUP -> emptyList()
                    null -> emptyList()
                }
                when {
                    commuteMode == CommuteMode.PICKUP -> Text(
                        "Verified pickup/drop-off handoff points are not mapped yet, matching the web app's fail-closed behavior.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    points.isNotEmpty() -> {
                        Text("Campus arrival point", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(points, key = { it.id }) { point ->
                                FilterChip(
                                    selected = accessPointId == point.id,
                                    onClick = {
                                        accessPointId = point.id
                                        preferences.setCampusAccessPointId(point.id)
                                    },
                                    label = { Text(point.label) },
                                )
                            }
                        }
                    }
                }
            }
        }

        SectionDivider()

        PreferenceSection("Gap planning") {
            Text(
                "All planning knobs from the web gap preferences are available here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingSlider(
                label = "Setup time",
                valueLabel = "$setupMinutes min",
                value = setupMinutes.toFloat(),
                range = 0f..20f,
                steps = 19,
                onValueChange = { setupMinutes = it.roundToInt() },
                onValueChangeFinished = { preferences.setSetupMinutes(setupMinutes) },
            )
            SettingSlider(
                label = "Pack-up time",
                valueLabel = "$packUpMinutes min",
                value = packUpMinutes.toFloat(),
                range = 0f..20f,
                steps = 19,
                onValueChange = { packUpMinutes = it.roundToInt() },
                onValueChangeFinished = { preferences.setPackUpMinutes(packUpMinutes) },
            )
            SettingSlider(
                label = "Lunch window starts",
                valueLabel = formatClock(lunchStart),
                value = lunchStart.toFloat(),
                range = 0f..1425f,
                steps = 94,
                onValueChange = {
                    lunchStart = roundQuarterHour(it.roundToInt()).coerceAtMost(lunchEnd - 15)
                },
                onValueChangeFinished = { preferences.setLunchWindowStart(lunchStart) },
            )
            SettingSlider(
                label = "Lunch window ends",
                valueLabel = formatClock(lunchEnd),
                value = lunchEnd.toFloat(),
                range = 15f..1440f,
                steps = 94,
                onValueChange = {
                    lunchEnd = roundQuarterHour(it.roundToInt()).coerceAtLeast(lunchStart + 15)
                },
                onValueChangeFinished = { preferences.setLunchWindowEnd(lunchEnd) },
            )
            SettingSlider(
                label = "Meal duration",
                valueLabel = "$mealDuration min",
                value = mealDuration.toFloat(),
                range = 15f..90f,
                steps = 74,
                onValueChange = { mealDuration = it.roundToInt() },
                onValueChangeFinished = { preferences.setMealDurationMinutes(mealDuration) },
            )
            ToggleSetting(
                title = "Willing to leave campus",
                detail = "Allow off-campus/home recommendations when the gap is long enough.",
                checked = willingToLeaveCampus,
                onCheckedChange = {
                    willingToLeaveCampus = it
                    preferences.setWillingToLeaveCampus(it)
                },
            )
            ToggleSetting(
                title = "Use a one-way home commute estimate",
                detail = "Optional manual estimate used by home-gap planning.",
                checked = homeCommute != null,
                onCheckedChange = { enabled ->
                    homeCommute = if (enabled) (homeCommute ?: 30) else null
                    preferences.setOneWayHomeCommuteMinutes(homeCommute)
                },
            )
            if (homeCommute != null) {
                SettingSlider(
                    label = "One-way home commute",
                    valueLabel = "$homeCommute min",
                    value = homeCommute!!.toFloat(),
                    range = 5f..180f,
                    steps = 174,
                    onValueChange = { homeCommute = it.roundToInt() },
                    onValueChangeFinished = { preferences.setOneWayHomeCommuteMinutes(homeCommute) },
                )
            }
            SettingSlider(
                label = "Minimum time at home",
                valueLabel = "$minimumHomeStay min",
                value = minimumHomeStay.toFloat(),
                range = 30f..360f,
                steps = 329,
                onValueChange = { minimumHomeStay = it.roundToInt() },
                onValueChangeFinished = { preferences.setMinimumHomeStayMinutes(minimumHomeStay) },
            )
            SettingSlider(
                label = "Home turnaround",
                valueLabel = "$homeTurnaround min",
                value = homeTurnaround.toFloat(),
                range = 0f..30f,
                steps = 29,
                onValueChange = { homeTurnaround = it.roundToInt() },
                onValueChangeFinished = { preferences.setHomeTurnaroundMinutes(homeTurnaround) },
            )
            Text("Risk tolerance", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RiskTolerance.entries.forEach { tolerance ->
                    FilterChip(
                        selected = riskTolerance == tolerance,
                        onClick = {
                            riskTolerance = tolerance
                            preferences.setRiskTolerance(tolerance)
                        },
                        label = { Text(tolerance.label) },
                    )
                }
            }
        }

        SectionDivider()

        PreferenceSection("Account") {
            if (account == null) {
                Text(
                    "An account is optional. Sign in only if you want encrypted cross-device sync and private account features.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AuthProvider.entries.forEach { provider ->
                        OutlinedButton(
                            onClick = { onSignIn(provider) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(provider.label, maxLines = 1)
                        }
                    }
                }
            } else {
                Text(
                    "Signed in as",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    account.email ?: "Gapwise account",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedButton(onClick = onSignOut, enabled = !syncBusy) {
                    Text("Sign out")
                }
            }
        }

        PreferenceSection("Device storage") {
            Text(
                if (meetings.isEmpty()) "No timetable is stored yet." else "On · ${meetings.size} normalized class meetings are encrypted on this device.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Android keeps the normalized timetable in app-private AES-GCM storage backed by Android Keystore. The original .ics file is not retained.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (account != null) {
            PreferenceSection("Sync across devices") {
                ToggleSetting(
                    title = "Encrypted account sync",
                    detail = "Use the same encrypted private cloud as gapwise.ca.",
                    checked = syncEnabled,
                    enabled = !syncBusy,
                    onCheckedChange = onSetSyncEnabled,
                )
                if (syncStatus != null) {
                    Text(
                        syncStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSyncNow, enabled = syncEnabled && !syncBusy) {
                        if (syncBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        Text(if (syncBusy) "Syncing…" else "Sync now")
                    }
                    OutlinedButton(onClick = onLoadSync, enabled = !syncBusy) {
                        Text("Load sync")
                    }
                }
                OutlinedButton(
                    onClick = { deleteSyncOpen = true },
                    enabled = !syncBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Delete synced account data")
                }
            }
        }

        SectionDivider()

        PreferenceSection("Exports") {
            Text(
                "The web settings include timetable image/vector export and a UTM map heatmap exporter. Until those renderers are ported to Compose, these open the same Gapwise web tools without uploading your raw .ics file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { openUrl("$GAPWISE_WEB/timetable") }, modifier = Modifier.fillMaxWidth()) {
                Text("Open timetable exporter")
            }
            OutlinedButton(onClick = { openUrl("$GAPWISE_WEB/route") }, modifier = Modifier.fillMaxWidth()) {
                Text("Open UTM map heatmap exporter")
            }
        }

        PreferenceSection("Academic work") {
            Text(
                "Academic work planning is part of the web product settings and planning flow.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { openUrl("$GAPWISE_WEB/today") }, modifier = Modifier.fillMaxWidth()) {
                Text("Open Academic work on web")
            }
        }

        PreferenceSection("AI integrations") {
            Text(
                "Public Gapwise AI and MCP remain available without an account. Private AI delegation is permissioned separately on the web app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { openUrl(GAPWISE_AI) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open Gapwise AI")
            }
            Text(
                GAPWISE_MCP,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { openUrl(GAPWISE_WEB) }, modifier = Modifier.fillMaxWidth()) {
                Text("Manage private AI permissions on web")
            }
            Text(
                "Web permissions cover: read academic timetable, read gap plans, read/edit gap preferences, and read routing preferences. Academic classes remain read-only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionDivider()

        PreferenceSection("Timetable") {
            Text(
                if (meetings.isEmpty()) "No timetable imported" else "${meetings.size} class meetings saved on this device",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (importStatus != null) {
                Text(
                    importStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                Text(if (meetings.isEmpty()) "Import ACORN calendar" else "Update timetable")
            }
            if (meetings.isNotEmpty()) {
                OutlinedButton(onClick = onClearTimetable, modifier = Modifier.fillMaxWidth()) {
                    Text("Remove timetable")
                }
            }
        }

        SectionDivider()

        PreferenceSection("Privacy") {
            Text(
                "Calendar parsing happens on-device. Raw .ics bytes are never uploaded by the Android app. Account sync encrypts normalized private state before cloud storage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Timetables support UTM, St. George, Scarborough, and mixed-campus schedules. This Android version currently maps UTM buildings and routes only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (account != null) {
            PreferenceSection("Danger zone") {
                OutlinedButton(
                    onClick = { deleteAccountOpen = true },
                    enabled = !syncBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Delete account and cloud data")
                }
            }
        }

        Text(
            "Planning preferences above are persisted locally in the native app. Timetable/account synchronization continues to preserve the full encrypted web private-data payload rather than overwriting fields Android does not yet edit.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 18.dp),
        )
    }

    if (deleteSyncOpen) {
        AlertDialog(
            onDismissRequest = { if (!syncBusy) deleteSyncOpen = false },
            title = { Text("Delete synced Gapwise data?") },
            text = { Text("This removes the encrypted private-data and availability records from your account. Your local timetable stays on this device.") },
            confirmButton = {
                TextButton(
                    enabled = !syncBusy,
                    onClick = {
                        deleteSyncOpen = false
                        onDeleteSync()
                    },
                ) { Text("Delete sync") }
            },
            dismissButton = {
                TextButton(onClick = { deleteSyncOpen = false }, enabled = !syncBusy) { Text("Cancel") }
            },
        )
    }

    if (deleteAccountOpen) {
        AlertDialog(
            onDismissRequest = { if (!syncBusy) deleteAccountOpen = false },
            title = { Text("Delete account and cloud data?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("This permanently removes your Gapwise account and account-owned cloud data. The original .ics file was never uploaded.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = clearLocalOnDelete,
                            onCheckedChange = { clearLocalOnDelete = it },
                            enabled = !syncBusy,
                        )
                        Text("Also remove the timetable saved on this Android device")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !syncBusy,
                    onClick = {
                        deleteAccountOpen = false
                        onDeleteAccount(clearLocalOnDelete)
                    },
                ) { Text("Permanently delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteAccountOpen = false }, enabled = !syncBusy) { Text("Keep account") }
            },
        )
    }
}

@Composable
private fun ThemeChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) }
    else OutlinedButton(onClick = onClick) { Text(label) }
}

@Composable
private fun ToggleSetting(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun SettingSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(valueLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun PreferenceSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
        )
        content()
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private fun roundQuarterHour(minutes: Int): Int = ((minutes + 7) / 15) * 15

private fun formatClock(minutes: Int): String {
    val normalized = minutes.coerceIn(0, 1440)
    if (normalized == 1440) return "12:00 AM"
    val hour24 = normalized / 60
    val minute = normalized % 60
    val hour12 = when (val value = hour24 % 12) {
        0 -> 12
        else -> value
    }
    val suffix = if (hour24 >= 12) "PM" else "AM"
    return "%d:%02d %s".format(hour12, minute, suffix)
}
