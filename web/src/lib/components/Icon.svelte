<script lang="ts" module>
	// Material Symbols Rounded, weight 500 (Apache 2.0), inlined at build time. Add icons here as pages need them.
	import android from '@material-symbols/svg-500/rounded/android.svg?raw';
	import arrowForward from '@material-symbols/svg-500/rounded/arrow_forward.svg?raw';
	import arrowOutward from '@material-symbols/svg-500/rounded/arrow_outward.svg?raw';
	import block from '@material-symbols/svg-500/rounded/block.svg?raw';
	import checkCircle from '@material-symbols/svg-500/rounded/check_circle-fill.svg?raw';
	import code from '@material-symbols/svg-500/rounded/code.svg?raw';
	import cookie from '@material-symbols/svg-500/rounded/cookie.svg?raw';
	import download from '@material-symbols/svg-500/rounded/download.svg?raw';
	import extension from '@material-symbols/svg-500/rounded/extension.svg?raw';
	import libraryAdd from '@material-symbols/svg-500/rounded/library_add.svg?raw';
	import libraryMusic from '@material-symbols/svg-500/rounded/library_music.svg?raw';
	import lock from '@material-symbols/svg-500/rounded/lock.svg?raw';
	import noAccounts from '@material-symbols/svg-500/rounded/no_accounts.svg?raw';
	import notificationsActive from '@material-symbols/svg-500/rounded/notifications_active.svg?raw';
	import personPin from '@material-symbols/svg-500/rounded/person_pin.svg?raw';
	import playArrow from '@material-symbols/svg-500/rounded/play_arrow-fill.svg?raw';
	import stop from '@material-symbols/svg-500/rounded/stop-fill.svg?raw';

	const sources = {
		android,
		'arrow-forward': arrowForward,
		'arrow-outward': arrowOutward,
		block,
		'check-circle': checkCircle,
		code,
		cookie,
		download,
		extension,
		'library-add': libraryAdd,
		'library-music': libraryMusic,
		lock,
		'no-accounts': noAccounts,
		'notifications-active': notificationsActive,
		'person-pin': personPin,
		'play-arrow': playArrow,
		stop
	};

	export type IconName = keyof typeof sources;

	const paths = Object.fromEntries(
		Object.entries(sources).map(([name, svg]) => [name, /\sd="([^"]+)"/.exec(svg)?.[1] ?? ''])
	) as Record<IconName, string>;
</script>

<script lang="ts">
	interface Props {
		name: IconName;
		class?: string;
	}

	let { name, class: className = '' }: Props = $props();
</script>

<svg class="icon {className}" viewBox="0 -960 960 960" aria-hidden="true" focusable="false">
	<path d={paths[name]} />
</svg>
