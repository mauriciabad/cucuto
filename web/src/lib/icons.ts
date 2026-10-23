// Material Symbols Rounded, weight 500 (Apache 2.0), inlined at build time and rendered by <Icon>. Kept in a plain
// module so page copy (src/lib/i18n) can name icons with a checked type. Add icons here as pages need them.
import android from '@material-symbols/svg-500/rounded/android.svg?raw';
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
import restart from '@material-symbols/svg-500/rounded/restart_alt.svg?raw';
import shield from '@material-symbols/svg-500/rounded/shield.svg?raw';
import stop from '@material-symbols/svg-500/rounded/stop-fill.svg?raw';
import terminal from '@material-symbols/svg-500/rounded/terminal.svg?raw';
import translate from '@material-symbols/svg-500/rounded/translate.svg?raw';
import visibilityOff from '@material-symbols/svg-500/rounded/visibility_off.svg?raw';

const sources = {
	android,
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
	public: publicIcon,
	restart,
	shield,
	stop,
	terminal,
	translate,
	'visibility-off': visibilityOff
};

export type IconName = keyof typeof sources;

/** The single `d` attribute of each symbol (every Material Symbol is one path in a 0 -960 960 960 viewBox). */
export const ICON_PATHS = Object.fromEntries(
	Object.entries(sources).map(([name, svg]) => [name, /\sd="([^"]+)"/.exec(svg)?.[1] ?? ''])
) as Record<IconName, string>;
