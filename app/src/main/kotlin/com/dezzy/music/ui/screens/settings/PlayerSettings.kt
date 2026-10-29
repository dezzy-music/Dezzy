/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.dezzy.music.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dezzy.music.LocalPlayerAwareWindowInsets
import com.dezzy.music.R
import com.dezzy.music.constants.ArtistSeparatorsKey
import com.dezzy.music.constants.ExternalDownloaderEnabledKey
import com.dezzy.music.constants.ExternalDownloaderPackageKey
import com.dezzy.music.constants.AudioNormalizationKey
import com.dezzy.music.constants.AudioOffload
import com.dezzy.music.constants.AudioQuality
import com.dezzy.music.constants.AudioQualityKey
import com.dezzy.music.constants.NetworkMeteredKey
import com.dezzy.music.constants.AutoDownloadOnLikeKey
import com.dezzy.music.constants.AutoStartOnBluetoothKey
import com.dezzy.music.constants.AutoSkipNextOnErrorKey
import com.dezzy.music.constants.PauseOnDeviceMuteKey
import com.dezzy.music.constants.PermanentShuffleKey
import com.dezzy.music.constants.PersistentQueueKey

import com.dezzy.music.constants.SkipSilenceKey
import com.dezzy.music.constants.StopMusicOnTaskClearKey
import com.dezzy.music.constants.WakelockKey
import com.dezzy.music.constants.HistoryDuration
import com.dezzy.music.constants.AudioCrossfadeDurationKey
import com.dezzy.music.constants.PlayerStreamClient
import com.dezzy.music.constants.PlayerStreamClientKey
import com.dezzy.music.constants.SeekExtraSeconds
import com.dezzy.music.constants.CastVideoKey
import com.dezzy.music.constants.VideoKeepModeOnSongChangeKey
import com.dezzy.music.constants.VideoMaxQualityKey
import com.dezzy.music.constants.VideoQuality
import com.dezzy.music.constants.VideoResizeMode
import com.dezzy.music.constants.VideoResizeModeKey
import com.dezzy.music.ui.component.ArtistSeparatorsDialog
import com.dezzy.music.ui.component.TagsManagementDialog
import com.dezzy.music.ui.component.TextFieldDialog
import com.dezzy.music.ui.component.EnumListPreference
import com.dezzy.music.ui.component.IconButton
import com.dezzy.music.ui.component.ListDialog
import com.dezzy.music.ui.component.PreferenceEntry
import com.dezzy.music.ui.component.PreferenceGroupTitle
import com.dezzy.music.ui.component.SliderPreference
import com.dezzy.music.ui.component.CrossfadeSliderPreference
import com.dezzy.music.ui.component.SwitchPreference
import com.dezzy.music.ui.utils.backToMain
import com.dezzy.music.ui.utils.rememberAdaptiveSidePadding
import com.dezzy.music.utils.rememberEnumPreference
import com.dezzy.music.utils.rememberPreference
import com.dezzy.music.LocalDatabase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (audioQuality, onAudioQualityChange) = rememberEnumPreference(
        AudioQualityKey,
        defaultValue = AudioQuality.AUTO
    )
    val (playerStreamClient, onPlayerStreamClientChange) = rememberEnumPreference(
        PlayerStreamClientKey,
        defaultValue = PlayerStreamClient.ANDROID_VR
    )
    val (highResEngine, onHighResEngineChange) = com.dezzy.music.utils.rememberPreference(com.dezzy.music.constants.HighResEngineEnabledKey, false)
    val (networkMetered, onNetworkMeteredChange) = rememberPreference(
        NetworkMeteredKey,
        defaultValue = true
    )
    val (persistentQueue, onPersistentQueueChange) = rememberPreference(
        PersistentQueueKey,
        defaultValue = true
    )
    val (skipSilence, onSkipSilenceChange) = rememberPreference(
        SkipSilenceKey,
        defaultValue = false
    )
    val (audioNormalization, onAudioNormalizationChange) = rememberPreference(
        AudioNormalizationKey,
        defaultValue = true
    )
    val (audioOffload, onAudioOffloadChange) = rememberPreference(
        AudioOffload,
        defaultValue = false
    )

    val (seekExtraSeconds, onSeekExtraSeconds) = rememberPreference(
        SeekExtraSeconds,
        defaultValue = false
    )

    val (autoDownloadOnLike, onAutoDownloadOnLikeChange) = rememberPreference(
        AutoDownloadOnLikeKey,
        defaultValue = true
    )
    val (autoSkipNextOnError, onAutoSkipNextOnErrorChange) = rememberPreference(
        AutoSkipNextOnErrorKey,
        defaultValue = false
    )
    val (pauseOnDeviceMute, onPauseOnDeviceMuteChange) = rememberPreference(
        PauseOnDeviceMuteKey,
        defaultValue = false
    )
    val (autoStartOnBluetooth, onAutoStartOnBluetoothChange) = rememberPreference(
        AutoStartOnBluetoothKey,
        defaultValue = false
    )
    val (stopMusicOnTaskClear, onStopMusicOnTaskClearChange) = rememberPreference(
        StopMusicOnTaskClearKey,
        defaultValue = false
    )
    val (historyDuration, onHistoryDurationChange) = rememberPreference(
        HistoryDuration,
        defaultValue = 30f
    )

    val (audioCrossfadeSeconds, onAudioCrossfadeSecondsChange) = rememberPreference(
        AudioCrossfadeDurationKey,
        defaultValue = 0
    )

    val (artistSeparators, onArtistSeparatorsChange) = rememberPreference(
        ArtistSeparatorsKey,
        defaultValue = ",;/&"
    )
    val (videoMaxQuality, onVideoMaxQualityChange) = rememberEnumPreference(
        VideoMaxQualityKey,
        defaultValue = VideoQuality.P720
    )
    val (videoResizeMode, onVideoResizeModeChange) = rememberEnumPreference(
        VideoResizeModeKey,
        defaultValue = VideoResizeMode.FIT
    )
    val (videoKeepMode, onVideoKeepModeChange) = rememberPreference(
        VideoKeepModeOnSongChangeKey,
        defaultValue = false
    )
    val (castVideo, onCastVideoChange) = rememberPreference(
        CastVideoKey,
        defaultValue = false
    )
    val (externalDownloaderEnabled, onExternalDownloaderEnabledChange) = rememberPreference(
        ExternalDownloaderEnabledKey,
        defaultValue = false
    )
    val (externalDownloaderPackage, onExternalDownloaderPackageChange) = rememberPreference(
        ExternalDownloaderPackageKey,
        defaultValue = ""
    )

    val (wakelockEnabled, onWakelockChange) = rememberPreference(
        WakelockKey,
        defaultValue = true
    )

    var showArtistSeparatorsDialog by remember { mutableStateOf(false) }
    var showTagsManagementDialog by remember { mutableStateOf(false) }
    var showPlayerStreamClientDialog by remember { mutableStateOf(false) }
    var showExternalDownloaderPackageDialog by remember { mutableStateOf(false) }
    val database = LocalDatabase.current

    if (showArtistSeparatorsDialog) {
        ArtistSeparatorsDialog(
            currentSeparators = artistSeparators,
            onDismiss = { showArtistSeparatorsDialog = false },
            onSave = { newSeparators ->
                onArtistSeparatorsChange(newSeparators)
                showArtistSeparatorsDialog = false
            }
        )
    }

    if (showTagsManagementDialog) {
        TagsManagementDialog(
            database = database,
            onDismiss = { showTagsManagementDialog = false }
        )
    }

    if (showExternalDownloaderPackageDialog) {
        TextFieldDialog(
            initialTextFieldValue = androidx.compose.ui.text.input.TextFieldValue(externalDownloaderPackage),
            onDone = { pkg ->
                onExternalDownloaderPackageChange(pkg)
                showExternalDownloaderPackageDialog = false
            },
            onDismiss = { showExternalDownloaderPackageDialog = false },
            singleLine = true,
            maxLines = 1,
        )
    }

    if (showPlayerStreamClientDialog) {
        ListDialog(
            onDismiss = { showPlayerStreamClientDialog = false },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            items(
                listOf(
                    PlayerStreamClient.ANDROID_VR,
                    PlayerStreamClient.IOS,
                    PlayerStreamClient.ANDROID_MUSIC,
                    PlayerStreamClient.WEB_REMIX,
                )
            ) { value ->
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            onPlayerStreamClientChange(value)
                            showPlayerStreamClientDialog = false
                        }.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    RadioButton(
                        selected = value == playerStreamClient,
                        onClick = null,
                    )

                    Column(modifier = Modifier.padding(start = 16.dp)) {
                        Text(
                            text =
                            when (value) {
                                PlayerStreamClient.ANDROID_VR -> stringResource(R.string.player_stream_client_android_vr)
                                PlayerStreamClient.IOS -> "iOS"
                                PlayerStreamClient.ANDROID_MUSIC -> "Android Music"
                                PlayerStreamClient.WEB_REMIX -> stringResource(R.string.player_stream_client_web_remix)
                                else -> value.name
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text =
                            when (value) {
                                PlayerStreamClient.ANDROID_VR -> stringResource(R.string.player_stream_client_android_vr_desc)
                                PlayerStreamClient.IOS -> "Streams sin cifrar: evita el descifrado de firma (roto por cambios de YouTube) y suele pasar la verificación de bot. Recomendado si no reproduce."
                                PlayerStreamClient.ANDROID_MUSIC -> "Cliente de Android Music. Alternativa sin cifrado si iOS falla."
                                PlayerStreamClient.WEB_REMIX -> stringResource(R.string.player_stream_client_web_remix_desc)
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
        }
    }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = rememberAdaptiveSidePadding())
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Top
                )
            )
        )

        PreferenceGroupTitle(
            title = stringResource(R.string.player)
        )

        EnumListPreference(
            title = { Text(stringResource(R.string.audio_quality)) },
            icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
            selectedValue = audioQuality,
            onValueSelected = onAudioQualityChange,
            valueText = {
                when (it) {
                    AudioQuality.HIGHEST -> stringResource(R.string.audio_quality_max)
                    AudioQuality.HIGH -> stringResource(R.string.audio_quality_high)
                    AudioQuality.AUTO -> stringResource(R.string.audio_quality_auto)
                    AudioQuality.LOW -> stringResource(R.string.audio_quality_low)
                }
            }
        )

        SwitchPreference(
            title = { Text("Motor de Audio Nativo (64-bits)") },
            description = "Procesamiento DSP en C++ (Oboe) con latencia ultrabaja y ecualización grado audiófilo. Evita el mezclador de Android.",
            icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
            checked = highResEngine,
            onCheckedChange = onHighResEngineChange
        )

        PreferenceEntry(
            title = { Text(stringResource(R.string.player_stream_client)) },
            description =
            when (playerStreamClient) {
                PlayerStreamClient.ANDROID_VR -> stringResource(R.string.player_stream_client_android_vr)
                else -> stringResource(R.string.player_stream_client_web_remix)
            },
            icon = { Icon(painterResource(R.drawable.integration), null) },
            onClick = { showPlayerStreamClientDialog = true }
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.network_metered_title)) },
            description = stringResource(R.string.network_metered_description),
            icon = { Icon(painterResource(R.drawable.android_cell), null) },
            checked = networkMetered,
            onCheckedChange = onNetworkMeteredChange
        )

        SliderPreference(
            title = { Text(stringResource(R.string.history_duration)) },
            icon = { Icon(painterResource(R.drawable.history), null) },
            value = historyDuration,
            onValueChange = onHistoryDurationChange,
        )

        CrossfadeSliderPreference(
            value = audioCrossfadeSeconds,
            onValueChange = onAudioCrossfadeSecondsChange,
            isEnabled = !audioOffload,
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.skip_silence)) },
            icon = { Icon(painterResource(R.drawable.fast_forward), null) },
            checked = skipSilence,
            onCheckedChange = onSkipSilenceChange,
            isEnabled = !audioOffload,
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.audio_normalization)) },
            icon = { Icon(painterResource(R.drawable.volume_up), null) },
            checked = audioNormalization,
            onCheckedChange = onAudioNormalizationChange
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.audio_offload)) },
            description = stringResource(R.string.audio_offload_desc),
            icon = { Icon(painterResource(R.drawable.speed), null) },
            checked = audioOffload,
            onCheckedChange = { enabled ->
                onAudioOffloadChange(enabled)
                if (enabled) {
                    onSkipSilenceChange(false)
                }
            }
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.seek_seconds_addup)) },
            description = stringResource(R.string.seek_seconds_addup_description),
            icon = { Icon(painterResource(R.drawable.arrow_forward), null) },
            checked = seekExtraSeconds,
            onCheckedChange = onSeekExtraSeconds
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.pause_on_device_mute)) },
            description = stringResource(R.string.pause_on_device_mute_desc),
            icon = { Icon(painterResource(R.drawable.volume_off), null) },
            checked = pauseOnDeviceMute,
            onCheckedChange = onPauseOnDeviceMuteChange
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.auto_start_on_bluetooth)) },
            description = stringResource(R.string.auto_start_on_bluetooth_desc),
            icon = { Icon(painterResource(R.drawable.bluetooth), null) },
            checked = autoStartOnBluetooth,
            onCheckedChange = onAutoStartOnBluetoothChange
        )

        PreferenceGroupTitle(
            title = stringResource(R.string.video_playback)
        )

        EnumListPreference(
            title = { Text(stringResource(R.string.video_quality)) },
            icon = { Icon(painterResource(R.drawable.slow_motion_video), null) },
            selectedValue = videoMaxQuality,
            onValueSelected = onVideoMaxQualityChange,
            valueText = {
                when (it) {
                    VideoQuality.P360 -> "360p"
                    VideoQuality.P480 -> "480p"
                    VideoQuality.P720 -> "720p"
                    VideoQuality.P1080 -> "1080p"
                }
            }
        )

        EnumListPreference(
            title = { Text(stringResource(R.string.video_resize_mode)) },
            icon = { Icon(painterResource(R.drawable.fullscreen), null) },
            selectedValue = videoResizeMode,
            onValueSelected = onVideoResizeModeChange,
            valueText = {
                when (it) {
                    VideoResizeMode.FIT -> stringResource(R.string.video_resize_fit)
                    VideoResizeMode.CROP -> stringResource(R.string.video_resize_crop)
                    VideoResizeMode.FILL -> stringResource(R.string.video_resize_fill)
                }
            }
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.video_keep_mode)) },
            description = stringResource(R.string.video_keep_mode_desc),
            icon = { Icon(painterResource(R.drawable.skip_next), null) },
            checked = videoKeepMode,
            onCheckedChange = onVideoKeepModeChange
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.cast_video)) },
            description = stringResource(R.string.cast_video_desc),
            icon = { Icon(painterResource(R.drawable.cast), null) },
            checked = castVideo,
            onCheckedChange = onCastVideoChange
        )

        PreferenceGroupTitle(
            title = stringResource(R.string.queue)
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.persistent_queue)) },
            description = stringResource(R.string.persistent_queue_desc),
            icon = { Icon(painterResource(R.drawable.queue_music), null) },
            checked = persistentQueue,
            onCheckedChange = onPersistentQueueChange
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.auto_download_on_like)) },
            description = stringResource(R.string.auto_download_on_like_desc),
            icon = { Icon(painterResource(R.drawable.download), null) },
            checked = autoDownloadOnLike,
            onCheckedChange = onAutoDownloadOnLikeChange
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.auto_skip_next_on_error)) },
            description = stringResource(R.string.auto_skip_next_on_error_desc),
            icon = { Icon(painterResource(R.drawable.skip_next), null) },
            checked = autoSkipNextOnError,
            onCheckedChange = onAutoSkipNextOnErrorChange
        )

        PreferenceGroupTitle(
            title = stringResource(R.string.misc)
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.stop_music_on_task_clear)) },
            icon = { Icon(painterResource(R.drawable.clear_all), null) },
            checked = stopMusicOnTaskClear,
            onCheckedChange = onStopMusicOnTaskClearChange
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.wakelock)) },
            description = stringResource(R.string.wakelock_desc),
            icon = { Icon(painterResource(R.drawable.bolt), null) },
            checked = wakelockEnabled,
            onCheckedChange = onWakelockChange
        )

        PreferenceEntry(
            title = { Text(stringResource(R.string.artist_separators)) },
            description = artistSeparators.map { "\"$it\"" }.joinToString("  "),
            icon = { Icon(painterResource(R.drawable.artist), null) },
            onClick = { showArtistSeparatorsDialog = true }
        )

        PreferenceEntry(
            title = { Text(stringResource(R.string.manage_playlist_tags)) },
            description = stringResource(R.string.manage_playlist_tags_desc),
            icon = { Icon(painterResource(R.drawable.style), null) },
            onClick = { showTagsManagementDialog = true }
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.external_downloader)) },
            description = stringResource(R.string.external_downloader_desc),
            icon = { Icon(painterResource(R.drawable.download), null) },
            checked = externalDownloaderEnabled,
            onCheckedChange = onExternalDownloaderEnabledChange
        )

        PreferenceEntry(
            title = { Text(stringResource(R.string.external_downloader_package)) },
            description = externalDownloaderPackage.ifEmpty { stringResource(R.string.external_downloader_package_desc) },
            icon = { Icon(painterResource(R.drawable.integration), null) },
            onClick = { showExternalDownloaderPackageDialog = true },
            isEnabled = externalDownloaderEnabled
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.player_and_audio)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null
                )
            }
        }
    )
}
