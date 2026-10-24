package app.mauri.cucuto.daynight

import android.Manifest
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.cucuto.R
import app.mauri.cucuto.core.conditions.ConditionCompiler
import app.mauri.cucuto.core.conditions.SunTimes
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Day and night (#118, ADR 0037): where sunrise and sunset are computed for, so voices with day or night clips
 * switch at the right time. A city from the built-in list, typed coordinates, or one approximate location fix.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayNightScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DayNightViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locate by viewModel.locate.collectAsStateWithLifecycle()
    // The permission is asked only here, on the user's tap; a denial just shows the alternatives.
    val requestLocation =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            viewModel.useDeviceLocation()
        }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var query by rememberSaveable { mutableStateOf("") }
    val region = remember { Locale.getDefault().country }
    val cities = remember(query, region) { Cities.search(query, region) }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.daynight_title), Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(innerPadding),
            contentPadding =
                PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 24.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.daynight_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state?.let { current -> item { NowCard(current, onForget = viewModel::forgetPlace) } }
            item { SectionTitle(stringResource(R.string.daynight_city_title)) }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.daynight_city_search)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (cities.isEmpty() && query.isNotBlank()) {
                item {
                    Text(
                        stringResource(R.string.daynight_city_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(cities, key = { "${it.name}/${it.country}" }) { city ->
                ListItem(
                    headlineContent = { Text(city.name) },
                    supportingContent = { Text(Cities.countryName(city.country)) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier =
                        Modifier
                            .clip(MaterialTheme.shapes.large)
                            .clickable {
                                viewModel.choose(city)
                                query = ""
                            },
                )
            }
            item { SectionTitle(stringResource(R.string.daynight_coordinates_title)) }
            item { CoordinatesForm(onSave = viewModel::saveCoordinates) }
            item { SectionTitle(stringResource(R.string.daynight_location_title)) }
            item {
                LocationSection(
                    status = locate,
                    onUseLocation = { requestLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
}

@Composable
private fun NowCard(
    state: DayNightUiState,
    onForget: () -> Unit,
) {
    val context = LocalContext.current
    val format =
        remember(context, state.zone) {
            DateFormat.getTimeFormat(context).apply { timeZone = TimeZone.getTimeZone(state.zone) }
        }
    val time = { instant: Instant -> format.format(Date.from(instant)) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(if (state.isDay) R.string.daynight_now_day else R.string.daynight_now_night),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                when (val sun = state.today) {
                    is SunTimes.Regular -> {
                        stringResource(
                            R.string.daynight_sun_times,
                            time(sun.sunrise),
                            time(sun.sunset),
                        )
                    }

                    SunTimes.PolarDay -> {
                        stringResource(R.string.daynight_polar_day)
                    }

                    SunTimes.PolarNight -> {
                        stringResource(R.string.daynight_polar_night)
                    }
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            val place = state.place
            Text(
                when {
                    place == null -> {
                        val today = LocalDate.now(state.zone)
                        stringResource(
                            R.string.daynight_place_none,
                            time(fixed(today, ConditionCompiler.DEFAULT_DAY_START, state)),
                            time(fixed(today, ConditionCompiler.DEFAULT_DAY_END, state)),
                        )
                    }

                    place.label != null -> {
                        stringResource(R.string.daynight_place_named, place.label.orEmpty())
                    }

                    else -> {
                        stringResource(
                            R.string.daynight_place_coordinates,
                            degrees(place.latitude),
                            degrees(place.longitude),
                        )
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (place != null) {
                TextButton(onClick = onForget, contentPadding = ButtonDefaults.TextButtonWithIconContentPadding) {
                    Text(stringResource(R.string.daynight_forget))
                }
            }
        }
    }
}

private fun fixed(
    date: LocalDate,
    time: LocalTime,
    state: DayNightUiState,
): Instant = date.atTime(time).atZone(state.zone).toInstant()

private fun degrees(value: Double): String = String.format(Locale.getDefault(), "%.2f", value)

@Composable
private fun CoordinatesForm(onSave: (String, String) -> CoordinateError?) {
    var latitude by rememberSaveable { mutableStateOf("") }
    var longitude by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<CoordinateError?>(null) }
    var saved by rememberSaveable { mutableStateOf(false) }
    val keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = latitude,
                onValueChange = {
                    latitude = it
                    error = null
                    saved = false
                },
                label = { Text(stringResource(R.string.daynight_latitude)) },
                isError = error == CoordinateError.LATITUDE,
                singleLine = true,
                keyboardOptions = keyboard,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = longitude,
                onValueChange = {
                    longitude = it
                    error = null
                    saved = false
                },
                label = { Text(stringResource(R.string.daynight_longitude)) },
                isError = error == CoordinateError.LONGITUDE,
                singleLine = true,
                keyboardOptions = keyboard,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            stringResource(
                when {
                    error == CoordinateError.LATITUDE -> R.string.daynight_latitude_error
                    error == CoordinateError.LONGITUDE -> R.string.daynight_longitude_error
                    saved -> R.string.daynight_coordinates_saved
                    else -> R.string.daynight_coordinates_hint
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            // "Saved" and errors are announced: the button's tap alone says nothing.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        OutlinedButton(
            onClick = {
                error = onSave(latitude, longitude)
                saved = error == null
            },
            enabled = latitude.isNotBlank() && longitude.isNotBlank(),
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(stringResource(R.string.daynight_coordinates_save))
        }
    }
}

@Composable
private fun LocationSection(
    status: LocateStatus,
    onUseLocation: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.daynight_location_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onUseLocation,
            enabled = status != LocateStatus.Locating,
            shapes = ButtonDefaults.shapes(),
        ) {
            Icon(Icons.Rounded.LocationOn, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(stringResource(R.string.daynight_location_button))
        }
        val message =
            when (status) {
                LocateStatus.Idle -> null
                LocateStatus.Locating -> R.string.daynight_location_locating
                LocateStatus.Found -> R.string.daynight_location_found
                LocateStatus.Denied -> R.string.daynight_location_denied
                LocateStatus.Off -> R.string.daynight_location_off
                LocateStatus.Unavailable -> R.string.daynight_location_unavailable
            }
        if (message != null) {
            Text(
                stringResource(message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
