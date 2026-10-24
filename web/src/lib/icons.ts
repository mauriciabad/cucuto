// Material Symbols Rounded, weight 500 (Apache 2.0), inlined at build time and rendered by <Icon>. Kept in a plain
// module so page copy (src/lib/i18n) can name icons with a checked type. Add icons here as pages need them.
import add from '@material-symbols/svg-500/rounded/add.svg?raw';
import addPhoto from '@material-symbols/svg-500/rounded/add_photo_alternate.svg?raw';
import android from '@material-symbols/svg-500/rounded/android.svg?raw';
import audioFile from '@material-symbols/svg-500/rounded/audio_file.svg?raw';
import close from '@material-symbols/svg-500/rounded/close.svg?raw';
import contentCut from '@material-symbols/svg-500/rounded/content_cut.svg?raw';
import contentCopy from '@material-symbols/svg-500/rounded/content_copy.svg?raw';
import contentPaste from '@material-symbols/svg-500/rounded/content_paste.svg?raw';
import folderOpen from '@material-symbols/svg-500/rounded/folder_open.svg?raw';
import noteAdd from '@material-symbols/svg-500/rounded/note_add.svg?raw';
import undo from '@material-symbols/svg-500/rounded/undo.svg?raw';
import deleteIcon from '@material-symbols/svg-500/rounded/delete.svg?raw';
import graphicEq from '@material-symbols/svg-500/rounded/graphic_eq.svg?raw';
import mic from '@material-symbols/svg-500/rounded/mic.svg?raw';
import musicNote from '@material-symbols/svg-500/rounded/music_note.svg?raw';
import personAdd from '@material-symbols/svg-500/rounded/person_add.svg?raw';
import share from '@material-symbols/svg-500/rounded/share.svg?raw';
import wifiOff from '@material-symbols/svg-500/rounded/wifi_off.svg?raw';
import zoomIn from '@material-symbols/svg-500/rounded/zoom_in.svg?raw';
import arrowForward from '@material-symbols/svg-500/rounded/arrow_forward.svg?raw';
import arrowOutward from '@material-symbols/svg-500/rounded/arrow_outward.svg?raw';
import block from '@material-symbols/svg-500/rounded/block.svg?raw';
import checkCircle from '@material-symbols/svg-500/rounded/check_circle-fill.svg?raw';
import code from '@material-symbols/svg-500/rounded/code.svg?raw';
import cookie from '@material-symbols/svg-500/rounded/cookie.svg?raw';
import database from '@material-symbols/svg-500/rounded/database.svg?raw';
import doNotDisturb from '@material-symbols/svg-500/rounded/do_not_disturb_on.svg?raw';
import download from '@material-symbols/svg-500/rounded/download.svg?raw';
import edit from '@material-symbols/svg-500/rounded/edit.svg?raw';
import errorIcon from '@material-symbols/svg-500/rounded/error-fill.svg?raw';
import extension from '@material-symbols/svg-500/rounded/extension.svg?raw';
import folderZip from '@material-symbols/svg-500/rounded/folder_zip.svg?raw';
import groups from '@material-symbols/svg-500/rounded/groups.svg?raw';
import info from '@material-symbols/svg-500/rounded/info.svg?raw';
import key from '@material-symbols/svg-500/rounded/key.svg?raw';
import libraryAdd from '@material-symbols/svg-500/rounded/library_add.svg?raw';
import libraryMusic from '@material-symbols/svg-500/rounded/library_music.svg?raw';
import lock from '@material-symbols/svg-500/rounded/lock.svg?raw';
import menuBook from '@material-symbols/svg-500/rounded/menu_book.svg?raw';
import mobile from '@material-symbols/svg-500/rounded/mobile.svg?raw';
import mobileVibrate from '@material-symbols/svg-500/rounded/mobile_vibrate.svg?raw';
import noAccounts from '@material-symbols/svg-500/rounded/no_accounts.svg?raw';
import notificationsActive from '@material-symbols/svg-500/rounded/notifications_active.svg?raw';
import personPin from '@material-symbols/svg-500/rounded/person_pin.svg?raw';
import playArrow from '@material-symbols/svg-500/rounded/play_arrow-fill.svg?raw';
import playCircle from '@material-symbols/svg-500/rounded/play_circle.svg?raw';
import powerSettings from '@material-symbols/svg-500/rounded/power_settings_new.svg?raw';
import publicIcon from '@material-symbols/svg-500/rounded/public.svg?raw';
import ringVolume from '@material-symbols/svg-500/rounded/ring_volume.svg?raw';
import schedule from '@material-symbols/svg-500/rounded/schedule.svg?raw';
import restart from '@material-symbols/svg-500/rounded/restart_alt.svg?raw';
import shield from '@material-symbols/svg-500/rounded/shield.svg?raw';
import stop from '@material-symbols/svg-500/rounded/stop-fill.svg?raw';
import terminal from '@material-symbols/svg-500/rounded/terminal.svg?raw';
import translate from '@material-symbols/svg-500/rounded/translate.svg?raw';
import visibilityOff from '@material-symbols/svg-500/rounded/visibility_off.svg?raw';
import warning from '@material-symbols/svg-500/rounded/warning-fill.svg?raw';

const sources = {
	add,
	'add-photo': addPhoto,
	android,
	'audio-file': audioFile,
	close,
	'content-cut': contentCut,
	'content-copy': contentCopy,
	'content-paste': contentPaste,
	'folder-open': folderOpen,
	'note-add': noteAdd,
	undo,
	delete: deleteIcon,
	'graphic-eq': graphicEq,
	mic,
	'music-note': musicNote,
	'person-add': personAdd,
	share,
	'wifi-off': wifiOff,
	'zoom-in': zoomIn,
	'arrow-forward': arrowForward,
	'arrow-outward': arrowOutward,
	block,
	'check-circle': checkCircle,
	code,
	cookie,
	database,
	'do-not-disturb': doNotDisturb,
	download,
	edit,
	error: errorIcon,
	extension,
	'folder-zip': folderZip,
	groups,
	info,
	key,
	'library-add': libraryAdd,
	'library-music': libraryMusic,
	lock,
	'menu-book': menuBook,
	mobile,
	'mobile-vibrate': mobileVibrate,
	'no-accounts': noAccounts,
	'notifications-active': notificationsActive,
	'person-pin': personPin,
	'play-arrow': playArrow,
	'play-circle': playCircle,
	'power-settings': powerSettings,
	'ring-volume': ringVolume,
	public: publicIcon,
	restart,
	schedule,
	shield,
	stop,
	terminal,
	translate,
	'visibility-off': visibilityOff,
	warning
};

export type IconName = keyof typeof sources;

/** The single `d` attribute of each symbol (every Material Symbol is one path in a 0 -960 960 960 viewBox). */
export const ICON_PATHS = Object.fromEntries(
	Object.entries(sources).map(([name, svg]) => [name, /\sd="([^"]+)"/.exec(svg)?.[1] ?? ''])
) as Record<IconName, string>;
