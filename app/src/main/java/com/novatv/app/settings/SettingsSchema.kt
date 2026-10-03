package com.novatv.app.settings

/**
 * The complete settings tree, laid out exactly like TiviMate's Settings menu:
 * General, Playlists, EPG, Appearance, Playback, Remote control, Parental controls, Other, About.
 *
 * Everything here is data: the Settings panel renders it generically (submenus, blue headers,
 * switches, radio lists, notes) and [SettingsRepository] stores every value by its [SettingItem.key].
 * To add a setting, add one line in the right place. Nothing else is needed for it to show up and be saved.
 *
 * Items marked premium = true are locked until Premium is unlocked (About › Account).
 */

sealed interface SettingItem {
    val key: String
    val title: String
    val summary: String?
    val premium: Boolean
    /** Only show this item when another toggle is on (key of that toggle). */
    val visibleWhen: String?
}

data class ToggleItem(
    override val key: String,
    override val title: String,
    val default: Boolean,
    override val summary: String? = null,
    override val premium: Boolean = false,
    override val visibleWhen: String? = null,
    /** TiviMate shows some master switches as just "On" / "Off". */
    val stateTitle: Boolean = false,
) : SettingItem

data class ChoiceItem(
    override val key: String,
    override val title: String,
    /** value -> label */
    val options: List<Pair<String, String>>,
    val default: String,
    override val summary: String? = null,
    override val premium: Boolean = false,
    override val visibleWhen: String? = null,
    /** Explanation shown under the options (TiviMate's grey help text). */
    val note: String? = null,
    /** Show the options as radio rows right inside the page instead of opening a list. */
    val inline: Boolean = false,
) : SettingItem

/** A number picked from a radio list (min..max). */
data class NumberItem(
    override val key: String,
    override val title: String,
    val min: Int,
    val max: Int,
    val step: Int = 1,
    val default: Int,
    val unit: String = "",
    override val summary: String? = null,
    override val premium: Boolean = false,
    override val visibleWhen: String? = null,
) : SettingItem

data class TextItem(
    override val key: String,
    override val title: String,
    val default: String = "",
    val secret: Boolean = false,
    val numeric: Boolean = false,
    override val summary: String? = null,
    override val premium: Boolean = false,
    override val visibleWhen: String? = null,
    val note: String? = null,
) : SettingItem

data class ActionItem(
    override val key: String,
    override val title: String,
    val action: SettingAction,
    override val summary: String? = null,
    override val premium: Boolean = false,
    override val visibleWhen: String? = null,
) : SettingItem

/** Opens a page with more settings. [valueKey] shows that setting's value under the title. */
data class SubmenuItem(
    override val key: String,
    override val title: String,
    val items: List<SettingItem>,
    override val summary: String? = null,
    override val premium: Boolean = false,
    override val visibleWhen: String? = null,
    val valueKey: String? = null,
) : SettingItem

/** Blue section header inside a page (e.g. "Update options"). */
data class HeaderItem(override val key: String, override val title: String) : SettingItem {
    override val summary: String? get() = null
    override val premium: Boolean get() = false
    override val visibleWhen: String? get() = null
}

/** Grey help text at the end of a page. */
data class NoteItem(override val key: String, val text: String) : SettingItem {
    override val title: String get() = ""
    override val summary: String? get() = text
    override val premium: Boolean get() = false
    override val visibleWhen: String? get() = null
}

/** Rows whose content comes from app data (the playlist list, account, version…). */
data class DynamicItem(
    override val key: String,
    override val title: String = "",
    override val visibleWhen: String? = null,
) : SettingItem {
    override val summary: String? get() = null
    override val premium: Boolean get() = false
}

enum class SettingAction {
    ADD_PLAYLIST,
    REFRESH_ALL_PLAYLISTS,
    MANAGE_EPG_SOURCES,
    CLEAR_EPG_CACHE,
    UPDATE_EPG,
    EPG_STATUS,
    RESET_WATCH_TIME,
    CLEAR_LOGO_CACHE,
    RESTORE_GUIDE_KEYS,
    RESTORE_PLAYER_KEYS,
    REORDER_MENU_BUTTONS,
    BACKUP,
    RESTORE,
    PRIVACY_POLICY,
    PREMIUM_ACCOUNT,
    GET_PREMIUM,
    DEVICE_INFO,
    VERSION_INFO,
    CHECK_UPDATES,
}

private fun opts(vararg pairs: Pair<String, String>) = pairs.toList()
private fun nums(vararg n: Int) = n.map { it.toString() to it.toString() }
private fun range(from: Int, to: Int, step: Int = 1) = (from..to step step).map { it.toString() to it.toString() }

/** Remote control key mappings (Settings › Remote control › TV guide / Player). */
object RemoteKeys {
    data class KeyDef(val id: String, val label: String, val default: String)

    val PLAYER_ACTIONS = opts(
        "ignore" to "Ignore",
        "guide_overlay" to "Show TV guide in overlay mode",
        "guide_groups_overlay" to "Show TV guide groups in overlay mode",
        "guide_preview" to "Show TV guide in preview mode",
        "guide_groups_preview" to "Show TV guide groups in preview mode",
        "channels_overlay" to "Show channels list in overlay mode",
        "channels_groups_overlay" to "Show channels list groups in overlay mode",
        "channels_preview" to "Show channels list in preview mode",
        "channels_groups_preview" to "Show channels list groups in preview mode",
        "program_description" to "Show program description",
        "info_control" to "Show info panel and control panel",
        "info" to "Show info panel",
        "control" to "Show control panel",
        "menu" to "Show menu",
        "next_channel" to "Turn on next channel",
        "prev_channel" to "Turn on previous channel",
        "recent_channel" to "Turn on most recent channel",
        "info_next" to "Show info panel for next channel (Press OK to turn on channel)",
        "info_prev" to "Show info panel for previous channel (Press OK to turn on channel)",
        "volume_up" to "Volume up",
        "volume_down" to "Volume down",
        "volume_mute" to "Volume mute",
        "play_pause" to "Play/pause",
        "stop" to "Stop playback",
        "restart" to "Restart current program",
        "go_live" to "Go to live stream",
        "search" to "Search",
        "history" to "History",
        "movies" to "Movies",
        "shows" to "Shows",
        "recordings" to "Recordings",
        "my_list" to "My list",
        "record" to "Start/stop recording",
        "multiview" to "Multiview",
        "pip" to "Picture-in-picture",
        "video_tracks" to "Video tracks",
        "audio_tracks" to "Audio tracks",
        "captions" to "Closed captions",
        "display_mode" to "Display mode",
        "sleep_timer" to "Sleep timer",
        "favorite" to "Change Favorite status",
        "settings" to "Settings",
        "go_back" to "Go back",
        "exit" to "Exit",
    )

    val PLAYER_KEYS = listOf(
        KeyDef("ok", "OK", "info_control"),
        KeyDef("ok_long", "Long OK", "menu"),
        KeyDef("back", "Back", "go_back"),
        KeyDef("back_long", "Long Back", "stop"),
        KeyDef("left", "Left", "channels_overlay"),
        KeyDef("left_long", "Long Left", "channels_preview"),
        KeyDef("right", "Right", "recent_channel"),
        KeyDef("right_long", "Long Right", "program_description"),
        KeyDef("up", "Up", "next_channel"),
        KeyDef("up_long", "Long Up", "history"),
        KeyDef("down", "Down", "prev_channel"),
        KeyDef("down_long", "Long Down", "search"),
        KeyDef("ch_up", "Pg+ (Ch+)", "info_next"),
        KeyDef("ch_down", "Pg- (Ch-)", "info_prev"),
        KeyDef("vol_up", "Volume up", "volume_up"),
        KeyDef("vol_down", "Volume down", "volume_down"),
        KeyDef("mute", "Mute", "volume_mute"),
        KeyDef("menu", "Menu", "menu"),
        KeyDef("menu_long", "Long Menu", "guide_overlay"),
        KeyDef("play_pause", "Play/Pause", "play_pause"),
        KeyDef("play_pause_long", "Long Play/Pause", "go_live"),
        KeyDef("stop", "Stop", "stop"),
        KeyDef("rewind", "Rewind (RW)", "info_prev"),
        KeyDef("ffwd", "Fast forward (FF)", "info_next"),
        KeyDef("record", "Record", "record"),
        KeyDef("backspace", "Backspace", "guide_groups_overlay"),
        KeyDef("info", "Info", "info"),
        KeyDef("guide", "Guide", "guide_overlay"),
        KeyDef("red", "Red", "guide_overlay"),
        KeyDef("green", "Green", "guide_groups_overlay"),
        KeyDef("yellow", "Yellow", "control"),
        KeyDef("blue", "Blue", "settings"),
    )

    val GUIDE_ACTIONS = opts(
        "ignore" to "Ignore",
        "watch" to "Watch channel",
        "menu" to "Show menu",
        "side_menu" to "Show side menu",
        "go_back" to "Go back",
        "return_player" to "Return to player",
        "groups" to "Show groups",
        "next_programs" to "Scroll to next programs",
        "page_up" to "Page scroll channels up",
        "page_down" to "Page scroll channels down",
        "search" to "Search",
        "description" to "Show program description",
        "external_player" to "Open in external player",
        "favorite" to "Change Favorite status",
        "channel_options" to "Channel options",
        "group_options" to "Group options",
        "history" to "History",
        "movies" to "Movies",
        "shows" to "Shows",
        "recordings" to "Recordings",
        "my_list" to "My list",
        "record" to "Start/stop recording",
        "settings" to "Settings",
        "exit" to "Exit",
    )

    val GUIDE_KEYS = listOf(
        KeyDef("ok", "OK", "watch"),
        KeyDef("ok_long", "Long OK", "menu"),
        KeyDef("back", "Back", "go_back"),
        KeyDef("back_long", "Long Back", "return_player"),
        KeyDef("left", "Left", "groups"),
        KeyDef("right", "Right", "next_programs"),
        KeyDef("ch_up", "Pg+ (Ch+)", "page_up"),
        KeyDef("ch_down", "Pg- (Ch-)", "page_down"),
        KeyDef("menu", "Menu", "side_menu"),
        KeyDef("menu_long", "Long Menu", "search"),
        KeyDef("play_pause", "Play/Pause", "description"),
        KeyDef("play_pause_long", "Long Play/Pause", "external_player"),
        KeyDef("stop", "Stop", "favorite"),
        KeyDef("rewind", "Rewind (RW)", "page_up"),
        KeyDef("ffwd", "Fast forward (FF)", "page_down"),
        KeyDef("record", "Record", "record"),
        KeyDef("backspace", "Backspace", "return_player"),
        KeyDef("info", "Info", "description"),
        KeyDef("guide", "Guide", "groups"),
        KeyDef("red", "Red", "favorite"),
        KeyDef("green", "Green", "channel_options"),
        KeyDef("yellow", "Yellow", "group_options"),
        KeyDef("blue", "Blue", "settings"),
    )

    fun playerKey(id: String) = "remote.player.$id"
    fun guideKey(id: String) = "remote.guide.$id"
}

/** Buttons of the player menu (Appearance › Player › Menu). id, label, visible by default. */
val PLAYER_MENU_BUTTONS = listOf(
    Triple("search", "Search", true),
    Triple("channels", "Channels list", true),
    Triple("recordings", "Recordings", true),
    Triple("multiview", "Multiview", true),
    Triple("pip", "Picture-in-picture", true),
    Triple("video_tracks", "Video tracks", true),
    Triple("audio_tracks", "Audio tracks", true),
    Triple("captions", "Closed captions", true),
    Triple("display_mode", "Display mode", true),
    Triple("sleep_timer", "Sleep timer", true),
    Triple("favorite", "Change Favorite status", true),
    Triple("channel_options", "Channel options", true),
    Triple("settings", "Settings", true),
    Triple("history", "History", false),
    Triple("movies", "Movies", false),
    Triple("shows", "Shows", false),
    Triple("my_list", "My list", false),
    Triple("exit", "Exit", false),
)

val COLOR_PALETTE = opts(
    "red" to "Red", "pink" to "Pink", "purple" to "Purple", "indigo" to "Indigo", "blue" to "Blue",
    "cyan" to "Cyan", "teal" to "Teal", "green" to "Green", "lime" to "Lime", "yellow" to "Yellow",
    "amber" to "Amber", "orange" to "Orange", "brown" to "Brown", "grey" to "Grey", "blue_grey" to "Blue grey",
    "black" to "Black",
)

object SettingsSchema {

    // ------------------------------------------------------------------ General

    private val general = SubmenuItem(
        "general", "General", listOf(
            ToggleItem("general.boot_start", "Auto start app on boot", false),
            ToggleItem("general.wake_start", "Auto start app on wake up from sleep mode", false,
                "May not work on all devices"),
            ToggleItem("general.start_last_channel", "Turn on last channel on app start", true),
            ToggleItem("general.pip_home", "Switch to picture-in-picture mode on press Home", false, premium = true),
            ToggleItem("general.exit_confirm", "Confirm exit by second press Back", false),
            TextItem("playback.user_agent", "User-Agent",
                note = "Used for all playlists that don't have their own User-Agent."),
            TextItem("general.udp_proxy", "UDP proxy (address:port)",
                note = "For udp:// and rtp:// multicast streams, e.g. 192.168.1.1:4022 (udpxy)."),
            ActionItem("backup.create", "Back up data", SettingAction.BACKUP, premium = true),
            ActionItem("backup.restore", "Restore data", SettingAction.RESTORE, premium = true),
        )
    )

    // ------------------------------------------------------------------ Playlists

    private val playlistsPage = SubmenuItem(
        "playlists", "Playlists", listOf(
            DynamicItem("playlists.list"),
            ChoiceItem("playlists.sort", "Playlists sorting",
                opts("name" to "By name", "manual" to "Manual"), "name"),
            ActionItem("playlists.add", "Add playlist", SettingAction.ADD_PLAYLIST),
            ActionItem("playlists.refresh_all", "Update all playlists", SettingAction.REFRESH_ALL_PLAYLISTS),
        )
    )

    // ------------------------------------------------------------------ EPG

    private val epg = SubmenuItem(
        "epg", "EPG", listOf(
            ActionItem("epg.sources", "EPG sources", SettingAction.MANAGE_EPG_SOURCES),
            ChoiceItem("epg.past_days", "Past days to keep EPG", range(1, 30), "2"),
            ToggleItem("epg.store_descriptions", "Store program descriptions", true),
            HeaderItem("epg.h_update", "Update options"),
            ChoiceItem("epg.update_interval", "Update interval, hours",
                opts("0" to "None") + nums(2, 4, 8, 16, 24, 48, 72, 96, 120), "24", premium = true),
            ToggleItem("epg.update_on_start", "Update on app start", true),
            ToggleItem("epg.update_on_playlist_change", "Update on playlists change", true),
            ActionItem("epg.update_now", "Update EPG", SettingAction.UPDATE_EPG),
            ActionItem("epg.clear_cache", "Clear EPG", SettingAction.CLEAR_EPG_CACHE),
            ActionItem("epg.status", "Latest update status", SettingAction.EPG_STATUS),
        )
    )

    // ------------------------------------------------------------------ Appearance

    private val tvGuide = SubmenuItem(
        "appearance.tv_guide", "TV guide", listOf(
            SubmenuItem("appearance.channels_sorting", "Channels sorting", listOf(
                ChoiceItem("channels.sort", "Channels sorting",
                    opts("playlist" to "By order in playlist", "name" to "By name",
                        "date_added" to "By date added", "watch_time" to "By watch time"),
                    "playlist", premium = true, inline = true),
                ToggleItem("channels.fav_first", "Show favorite channels first", false, premium = true),
                ToggleItem("channels.group_by_playlist", "Group channels by playlists in \"All playlists\" category", true),
                ActionItem("channels.reset_watch_time", "Reset watch time", SettingAction.RESET_WATCH_TIME),
                NoteItem("channels.sort_note", "This setting defines the global sorting for all groups. " +
                    "You can override the sorting method for individual groups or manually sort channels in the group options."),
            ), valueKey = "channels.sort"),
            SubmenuItem("appearance.preview", "Preview", listOf(
                ToggleItem("epg.show_preview", "On", true, stateTitle = true),
                ToggleItem("guide.preview_animated", "Animated transition", true, visibleWhen = "epg.show_preview"),
                ToggleItem("guide.preview_autoplay", "Autoplay channels", false, visibleWhen = "epg.show_preview"),
                ToggleItem("guide.preview_stay", "Stay on TV guide when switching channels", true, visibleWhen = "epg.show_preview"),
                ChoiceItem("guide.preview_timeout", "Full screen switching timeout, sec",
                    opts("0" to "Never") + nums(10, 30, 60, 120, 300, 600), "120", visibleWhen = "epg.show_preview"),
            ), valueKey = "epg.show_preview"),
            SubmenuItem("appearance.names_editor", "Channel names editor", listOf(
                ToggleItem("channels.names_editor", "Off", false, stateTitle = true, premium = true),
                TextItem("channels.strip_prefix", "Prefixes to remove", premium = true, visibleWhen = "channels.names_editor"),
                TextItem("channels.strip_suffix", "Suffixes to remove", premium = true, visibleWhen = "channels.names_editor"),
                NoteItem("channels.names_note", "Enter the comma separated lists of prefixes and suffixes to remove " +
                    "from channel names, for example, \"US:, UK:\" and \"| HD, | FHD\""),
            ), valueKey = "channels.names_editor"),
            ToggleItem("channels.show_numbers", "Show channel numbers", true),
            ToggleItem("guide.show_names", "Show channel names", true),
            ToggleItem("guide.two_line_names", "Two-line channel names", false),
            ToggleItem("channels.catchup_icon", "Show catch-up icon in channels list", true),
            ToggleItem("guide.highlight_current_channel", "Highlight current channel in color", true),
            ToggleItem("guide.two_line_titles", "Two-line program titles", false),
            ToggleItem("guide.highlight_current_programs", "Highlight current programs", true),
            ToggleItem("guide.highlight_progress_only", "Highlight progress only", true,
                visibleWhen = "guide.highlight_current_programs"),
            ToggleItem("guide.time_indicator_full", "Show current time indicator at full height", true),
            ToggleItem("guide.animated_scroll", "Animated channels scrolling", true),
            ToggleItem("guide.stay_overlay", "Stay on TV guide when switching channels in overlay mode", false),
            ToggleItem("guide.back_to_current", "Use Back to return to current programs", true),
        )
    )

    private val player = SubmenuItem(
        "appearance.player", "Player", listOf(
            SubmenuItem("appearance.player_list", "Channels list", listOf(
                ToggleItem("player.list_highlight_current", "Highlight current programs in color", true),
                ToggleItem("player.list_dim_past", "Dim past programs", false),
                HeaderItem("player.h_overlay", "Overlay mode"),
                ToggleItem("player.overlay_show_programs", "Show programs", true),
                ToggleItem("player.overlay_show_desc", "Show program description", true),
                ToggleItem("player.overlay_autoplay", "Autoplay channels", false),
                ToggleItem("player.overlay_stay", "Stay on list when switching channels", false),
                HeaderItem("player.h_preview", "Preview mode"),
                ToggleItem("player.preview_animated", "Animated transition", true),
                ToggleItem("player.preview_autoplay", "Autoplay channels", false),
                ToggleItem("player.preview_stay", "Stay on list when switching channels", true),
            )),
            SubmenuItem("appearance.player_info", "Info panel", listOf(
                ToggleItem("player.info_card", "Card style", false),
                ToggleItem("player.info_bottom", "Show info panel at the bottom when switching channels", true),
                ToggleItem("appearance.show_clock_info", "Show clock", true),
                ToggleItem("player.info_date", "Show date on clock", true, visibleWhen = "appearance.show_clock_info"),
                ToggleItem("player.info_playlist_group", "Show playlist and group name", true),
                ToggleItem("player.info_desc", "Show program description", true),
                ToggleItem("player.info_desc_switch_only", "Show description when switching channels only", true,
                    visibleWhen = "player.info_desc"),
                ToggleItem("player.info_media", "Show media properties", true),
                ToggleItem("player.info_resolution", "Show video resolution instead of labels", false,
                    "For example, \"1920x1080\" instead of \"FHD\"", visibleWhen = "player.info_media"),
            )),
            SubmenuItem("appearance.player_history", "History / Recent channels", listOf(
                ToggleItem("player.btn_guide", "Show \"TV guide\" button", true),
                ToggleItem("player.btn_history", "Show \"History\" button", true),
                HeaderItem("player.h_history", "History"),
                ChoiceItem("history.days", "History day count", range(1, 14), "7"),
                ChoiceItem("history.delay", "Delay before adding to history, sec", nums(0, 5, 10, 15, 20, 30, 60), "10"),
                ToggleItem("history.show_current", "Show current programs", true),
                ToggleItem("history.show_past_no_catchup", "Show past programs without catch-up", false),
                HeaderItem("player.h_recent", "Recent channels"),
                ChoiceItem("general.recent_count", "Recent channel count", nums(5, 10, 15, 20, 30), "10"),
                ChoiceItem("recent.delay", "Delay before adding to recent channels, sec", nums(0, 5, 10, 15, 20, 30, 60), "0"),
                ToggleItem("recent.show_names", "Show channel names", false),
            )),
            SubmenuItem("appearance.player_menu", "Menu",
                listOf<SettingItem>(ActionItem("player.menu_reorder", "Reorder buttons", SettingAction.REORDER_MENU_BUTTONS)) +
                    HeaderItem("player.h_buttons", "Buttons") +
                    PLAYER_MENU_BUTTONS.map<Triple<String, String, Boolean>, SettingItem> { (id, label, on) -> ToggleItem("player.btn.$id", label, on) },
            ),
            SubmenuItem("appearance.player_clock", "Clock", listOf(
                ToggleItem("appearance.show_clock", "On", true, stateTitle = true),
                ChoiceItem("player.clock_position", "Position",
                    opts("top_left" to "Top left", "top_right" to "Top right",
                        "bottom_left" to "Bottom left", "bottom_right" to "Bottom right"), "top_right",
                    visibleWhen = "appearance.show_clock"),
                ChoiceItem("player.clock_size", "Size",
                    opts("small" to "Small", "medium" to "Medium", "large" to "Large"), "medium",
                    visibleWhen = "appearance.show_clock"),
                ChoiceItem("player.clock_transparency", "Transparency",
                    opts("no" to "No", "low" to "Low", "medium" to "Medium", "high" to "High"), "no",
                    visibleWhen = "appearance.show_clock"),
            ), valueKey = "appearance.show_clock"),
            ChoiceItem("player.panels_timeout", "Panels timeout, sec", range(1, 10) + nums(15, 20, 30, 60), "5"),
            ToggleItem("player.black_screen", "Show black screen when switching channels", false),
        )
    )

    private val groups = SubmenuItem(
        "appearance.groups", "Groups", listOf(
            ChoiceItem("groups.sort", "Groups sorting",
                opts("playlist" to "By order in playlist", "name" to "By name"), "playlist",
                note = "This setting defines the global sorting for all playlists. You can override the sorting method " +
                    "for individual playlists or manually sort groups in the playlist settings."),
            ToggleItem("groups.show_all_playlists", "Show \"All playlists\" category", true),
            ToggleItem("groups.show_favorites", "Show \"Favorites\" category", true),
            ToggleItem("channels.show_all_group", "Show \"All channels\" category", true),
        )
    )

    private val logos = SubmenuItem(
        "appearance.logos", "Logos", listOf(
            ChoiceItem("channels.logo_source", "Logos priority",
                opts("playlist" to "Prefer logos from playlist", "epg" to "Prefer logos from EPG",
                    "folder" to "Prefer logos from folder"), "playlist",
                note = "Select whether you want to prefer logos specified in the playlists, logos from the local folder " +
                    "or logos specified in EPG. You can override logos priority for individual playlists in the playlist settings."),
            TextItem("logos.folder", "Logos folder", note = "Enter path of a folder with logos"),
            ToggleItem("logos.inexact", "Inexact matching for logos files", false),
            ActionItem("logos.clear_cache", "Clear logos cache", SettingAction.CLEAR_LOGO_CACHE),
        )
    )

    private val colorTheme = SubmenuItem(
        "appearance.color_theme", "Color theme", listOf(
            ChoiceItem("appearance.theme", "Background color",
                opts("dark" to "Dark", "black" to "Black", "dark_blue" to "Dark blue", "dark_grey" to "Dark grey"),
                "dark", premium = true),
            ChoiceItem("appearance.accent", "Accent color", COLOR_PALETTE, "blue", premium = true),
            ChoiceItem("appearance.selection", "Selection color",
                opts("white" to "White", "accent" to "Accent color"), "white", premium = true),
        )
    )

    private val appearance = SubmenuItem(
        "appearance", "Appearance", listOf(
            tvGuide, player, groups, logos,
            ChoiceItem("general.language", "Language",
                opts("system" to "System", "cs" to "Čeština", "da" to "Dansk", "de" to "Deutsch", "en" to "English",
                    "es" to "Español", "fr" to "Français", "hr" to "Hrvatski", "it" to "Italiano", "lv" to "Latviešu",
                    "hu" to "Magyar", "nl" to "Nederlands", "no" to "Norsk", "pl" to "Polski", "pt" to "Português",
                    "pt_br" to "Português (Brasil)", "ro" to "Română", "sk" to "Slovenský", "sl" to "Slovenščina",
                    "sr" to "Srpski", "fi" to "Suomi", "sv" to "Svenska", "vi" to "Tiếng Việt", "tr" to "Türkçe",
                    "el" to "Ελληνικά", "bg" to "Български", "ru" to "Русский", "uk" to "Українська",
                    "ar" to "العربية", "he" to "עברית"), "system"),
            ChoiceItem("appearance.font", "Font size",
                opts("small" to "Small", "medium" to "Medium", "large" to "Large", "xlarge" to "Very large"), "medium"),
            colorTheme,
            ChoiceItem("appearance.overlay_opacity", "User interface transparency, %", range(0, 100, 10), "50"),
        )
    )

    // ------------------------------------------------------------------ Playback

    private val playback = SubmenuItem(
        "playback", "Playback", listOf(
            ChoiceItem("playback.buffer", "Buffer size",
                opts("none" to "None", "small" to "Small", "medium" to "Medium", "large" to "Large", "xlarge" to "Very large"),
                "small",
                note = "The buffer size determines duration of video that must be downloaded to start playback. " +
                    "Try increasing this size if you frequently see buffering while watching. " +
                    "This can increase the channel switching time."),
            ChoiceItem("playback.audio_decoder", "Audio decoder",
                opts("hw" to "Hardware", "sw" to "Software"), "hw",
                note = "Try the software audio decoder if your device has issues when decoding audio using the hardware decoder"),
            ChoiceItem("playback.decoder", "Video decoder",
                opts("hw" to "Hardware", "sw" to "Software"), "hw",
                note = "Try the software video decoder if your device has issues when decoding video using the hardware decoder"),
            SubmenuItem("playback.afr_page", "Auto frame rate (AFR)", listOf(
                ToggleItem("playback.afr_tv", "Enable for TV", false, premium = true),
                ToggleItem("playback.afr_vod", "Enable for VOD", false, premium = true),
                ToggleItem("playback.afr_rate", "Switch screen refresh rate", true, premium = true),
                ToggleItem("playback.afr_50_60", "Switch rate for 50/60 FPS only", false, premium = true),
                ToggleItem("playback.afr_resolution", "Switch screen resolution", false, premium = true),
                ChoiceItem("playback.afr_delay", "Delay before switching, sec", range(0, 10), "0", premium = true),
                NoteItem("playback.afr_note", "Turn on this setting to switch TV refresh rate to match the video frame rate. " +
                    "This can make playback more smooth. Note that not all devices support auto frame rate."),
            ), valueKey = "playback.afr_tv"),
            ToggleItem("playback.surround", "Select surround audio track by default", false),
            ToggleItem("playback.passthrough", "Audio passthrough", false),
            ToggleItem("playback.tunneling", "Tunneled playback", false),
            ChoiceItem("playback.player", "Use external player",
                opts("no" to "No", "tv" to "For TV", "vod" to "For VOD", "all" to "For TV and VOD"), "no"),
            SubmenuItem("playback.skip_steps", "Skip steps", listOf(
                ChoiceItem("playback.skip_short", "Short step, sec", nums(5, 10, 15, 20, 30), "10"),
                ChoiceItem("playback.skip_long", "Long step (hold the button), sec", nums(30, 60, 120, 300, 600), "60"),
                NoteItem("playback.skip_note", "Used by Rewind / Fast forward while watching catch-up, recordings and movies."),
            )),
        )
    )

    // ------------------------------------------------------------------ Remote control

    private val remote = SubmenuItem(
        "remote", "Remote control", listOf(
            SubmenuItem("remote.tv_guide", "TV guide",
                RemoteKeys.GUIDE_KEYS.map<RemoteKeys.KeyDef, SettingItem> { k ->
                    ChoiceItem(RemoteKeys.guideKey(k.id), k.label, RemoteKeys.GUIDE_ACTIONS, k.default, premium = true)
                } + ActionItem("remote.guide_restore", "Restore to defaults", SettingAction.RESTORE_GUIDE_KEYS),
            ),
            SubmenuItem("remote.player", "Player",
                RemoteKeys.PLAYER_KEYS.map<RemoteKeys.KeyDef, SettingItem> { k ->
                    ChoiceItem(RemoteKeys.playerKey(k.id), k.label, RemoteKeys.PLAYER_ACTIONS, k.default, premium = true)
                } + ActionItem("remote.player_restore", "Restore to defaults", SettingAction.RESTORE_PLAYER_KEYS),
            ),
            HeaderItem("remote.h_seeking", "Seeking options"),
            ToggleItem("remote.seek_rw_ff", "Use RW/FF/Pause for seeking/pause while watching catch-up", true),
            ToggleItem("remote.seek_rw_live", "Use RW to rewind live stream with catch-up", false),
            ToggleItem("remote.seek_left_right", "Use Left/Right for seeking while watching catch-up", false),
            ToggleItem("remote.seek_left_live", "Use Left to rewind live stream with catch-up", false),
            ToggleItem("remote.seek_up_down", "Use Down/Up for seeking while watching catch-up", false),
            ToggleItem("remote.seek_down_live", "Use Down to rewind live stream with catch-up", false),
        )
    )

    // ------------------------------------------------------------------ Parental controls

    private val parental = SubmenuItem(
        "parental", "Parental controls", listOf(
            ToggleItem("parental.enabled", "Off", false, stateTitle = true, premium = true),
            TextItem("parental.pin", "Change PIN", "0000", secret = true, numeric = true, premium = true,
                visibleWhen = "parental.enabled"),
            ChoiceItem("parental.pin_method", "PIN input method",
                opts("picker" to "Picker", "keyboard" to "Keyboard"), "picker", premium = true,
                visibleWhen = "parental.enabled"),
            ChoiceItem("parental.unlock_duration", "Don't require PIN after unlocking",
                opts("always" to "Always require", "5" to "For 5 minutes", "15" to "For 15 minutes",
                    "60" to "For 1 hour", "session" to "Until the app is closed"), "always", premium = true,
                visibleWhen = "parental.enabled"),
            ToggleItem("parental.channels_only", "Don't require for channels only", false, premium = true,
                visibleWhen = "parental.enabled"),
            HeaderItem("parental.h_require", "Require PIN for"),
            ToggleItem("parental.lock_settings", "Settings", false, premium = true),
            ToggleItem("parental.lock_playlists", "Settings | Playlists", false, premium = true),
            ToggleItem("parental.lock_epg", "Settings | EPG", false, premium = true),
            ToggleItem("parental.lock_channel_options", "Channel options", false, premium = true),
            ToggleItem("parental.lock_group_options", "Group options", false, premium = true),
        )
    )

    // ------------------------------------------------------------------ Other

    private val other = SubmenuItem(
        "other", "Other", listOf(
            SubmenuItem("other.search", "Search", listOf(
                ToggleItem("search.voice", "Prefer voice search", true),
                ToggleItem("search.history", "Show search history", true),
                ToggleItem("search.fav_first", "Show favorite channels first", true),
                ToggleItem("search.past_no_catchup", "Show past programs without catch-up", false),
                ToggleItem("search.stay", "Stay on search screen when switching channels", false),
            )),
            SubmenuItem("other.reminders", "Reminders", listOf(
                ChoiceItem("epg.reminder_before", "Remind before program start, min", nums(0, 1, 2, 3, 5, 10, 15), "0",
                    premium = true),
                ChoiceItem("reminders.popup_timeout", "Popup timeout, sec", nums(5, 10, 15, 20, 30, 60), "10", premium = true),
                ChoiceItem("reminders.default_action", "Default action",
                    opts("watch" to "Watch", "ignore" to "Ignore"), "watch", premium = true),
                ToggleItem("reminders.wake", "Wake up from sleep mode", false, "May not work on all devices", premium = true),
            )),
            SubmenuItem("other.recording", "Recording", listOf(
                TextItem("recording.folder", "Recordings folder", "/storage/emulated/0/Download/KingVegasTV/Recordings",
                    premium = true),
                ChoiceItem("recording.pad_before", "Start recording before program start, min", nums(0, 1, 2, 3, 5, 10, 15), "0",
                    premium = true),
                ChoiceItem("recording.pad_after", "Stop recording after program end, min", nums(0, 1, 2, 3, 5, 10, 15, 30), "0",
                    premium = true),
            )),
            SubmenuItem("other.vod", "VOD", listOf(
                ToggleItem("vod.autoplay_next", "Autoplay next episode", true),
            )),
        )
    )

    // ------------------------------------------------------------------ About

    private val about = SubmenuItem(
        "about", "About", listOf(
            ActionItem("about.check_updates", "Check for updates", SettingAction.CHECK_UPDATES),
            ActionItem("about.privacy", "Privacy policy", SettingAction.PRIVACY_POLICY),
            ActionItem("about.account", "Account", SettingAction.PREMIUM_ACCOUNT),
            ActionItem("about.get_premium", "Unlock premium", SettingAction.GET_PREMIUM),
            ActionItem("about.device", "Device name", SettingAction.DEVICE_INFO),
            ActionItem("about.version", "Version", SettingAction.VERSION_INFO),
            TextItem("premium.server_url", "Account server", "",
                summary = "Leave blank to use the built-in address"),
            ToggleItem("premium.dev_unlock", "Developer: unlock Premium", false,
                "Only shown in test builds, for trying Premium features without signing in"),
            NoteItem("about.note", "Manage or cancel your Premium subscription on the account website " +
                "(About › Account shows the link)."),
        )
    )

    /** Settings main list, in TiviMate's order. */
    val sections: List<SubmenuItem> = listOf(
        general, playlistsPage, epg, appearance, playback, remote, parental, other, about,
    )

    /**
     * Values the app uses that TiviMate doesn't show in Settings (kept so code and backups still work).
     * They're never rendered.
     */
    val internal: List<SettingItem> = listOf(
        ToggleItem("backup.include_passwords", "Include passwords in backups", true),
        NumberItem("epg.future_days", "Load future programs", 1, 14, 1, 3),
        NumberItem("epg.offset_minutes", "Time offset", -720, 720, 30, 0),
        NumberItem("epg.rows", "Channels visible in guide", 5, 12, 1, 8),
        NumberItem("epg.timeline_hours", "Guide timeline width", 1, 4, 1, 2),
        ToggleItem("epg.reminders", "Program reminders", true),
        ChoiceItem("channels.numbering", "Channel numbers",
            opts("playlist" to "From playlist", "sequential" to "Sequential", "per_group" to "Per group"), "playlist"),
        ToggleItem("channels.show_hidden", "Show hidden channels", false),
        ToggleItem("general.recent_group", "Show recently watched", false),
        ChoiceItem("general.clock", "Time format", opts("system" to "System", "12" to "12-hour", "24" to "24-hour"), "system"),
        ChoiceItem("general.search_scope", "Search in", opts("all" to "All", "channels" to "Channels"), "all"),
        NumberItem("playback.timeout", "Connection timeout", 5, 60, 5, 15, " s"),
        ToggleItem("playback.subtitles", "Subtitles on by default", false),
        TextItem("playback.audio_lang", "Preferred audio language"),
        TextItem("playback.subtitle_lang", "Preferred subtitle language"),
        ChoiceItem("playback.aspect", "Display mode",
            opts("fit" to "Fit", "fill" to "Stretch", "zoom" to "Zoom", "16_9" to "16:9", "4_3" to "4:3"), "fit"),
        ChoiceItem("playback.sleep_default", "Sleep timer", opts("0" to "Off"), "0"),
        ToggleItem("playback.reconnect", "Reconnect automatically", true),
        NumberItem("playback.reconnect_tries", "Reconnect attempts", 1, 20, 1, 5),
        ToggleItem("playback.resume_vod", "Resume movies and episodes", true),
        NumberItem("playback.audio_offset", "Audio offset", -2000, 2000, 50, 0, " ms"),
        ToggleItem("remote.number_keys", "Number keys change channel", true),
        NumberItem("remote.number_delay", "Wait after number entry", 1, 5, 1, 2, " s"),
        ToggleItem("parental.lock_adult", "Lock adult groups automatically", true),
        ToggleItem("parental.hide_locked", "Hide locked groups completely", false),
        ChoiceItem("multiview.layout", "Multiview layout",
            opts("2_side" to "2 side by side", "3_main" to "1 + 2", "4_grid" to "2 × 2"), "4_grid"),
    )

    private fun flatten(items: List<SettingItem>): List<SettingItem> = items.flatMap {
        if (it is SubmenuItem) listOf(it) + flatten(it.items) else listOf(it)
    }

    val allItems: Map<String, SettingItem> = (flatten(sections) + internal).associateBy { it.key }

    fun defaultValue(key: String): String = when (val item = allItems[key]) {
        is ToggleItem -> item.default.toString()
        is ChoiceItem -> item.default
        is NumberItem -> item.default.toString()
        is TextItem -> item.default
        else -> ""
    }

    /** Find a page by key (used to open Settings straight at Playlists, EPG…). */
    fun page(key: String): SubmenuItem? = allItems[key] as? SubmenuItem
}
