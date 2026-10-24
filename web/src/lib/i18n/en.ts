// English copy for the whole site. `es.ts` must have exactly the same shape (it is typed as `Messages`).
//
// Conventions:
// - Keys ending in `Html` may contain inline markup (<code>, <strong>, <a>) and `{placeholder}` links from
//   src/lib/i18n/links.ts; render them with <Html>. Everything else is plain text.
// - Voice and tone follow brand/README.md: "Cucuto" with a capital C, friendly and concrete, plain in instructions,
//   sentence case, at most one exclamation mark per page.
// - Say "planned" for anything the app does not do yet.
import type { IconName } from '#lib/icons.js';

export interface IconItem {
	icon: IconName;
	title: string;
	text: string;
}

/**
 * One screenshot on the home page. `image` names the files written by the app's screenshot tests
 * (`./gradlew :app:exportWebScreenshots`, ADR 0032) to src/lib/assets/images/screenshots/.
 */
export interface Shot {
	image: 'onboarding' | 'home' | 'picker' | 'packs';
	title: string;
	text: string;
	alt: string;
}

export interface QA {
	q: string;
	aHtml: string;
}

const iconItems = (items: IconItem[]) => items;

export const en = {
	meta: {
		siteDescription:
			'Cucuto plays a different character’s sound and vibration for each WhatsApp and Signal contact, so you know who wrote before you look. For rooted Android 17 with LSPosed.',
		homeTitle: 'Cucuto: give each contact a voice',
		ogImageAlt:
			'The Cucuto logo: a tangerine cuckoo clock house with its round door open and a bird singing.'
	},
	layout: {
		skipLink: 'Skip to content',
		homeLabel: 'Cucuto home',
		navLabel: 'Main',
		nav: {
			packs: 'Packs',
			create: 'Create',
			install: 'Install',
			authors: 'Pack authors',
			privacy: 'Privacy'
		},
		github: 'GitHub',
		languageLabel: 'Language',
		switchTo: 'Leer en español',
		footerLicenseHtml:
			'<strong>Cucuto</strong> is free software under the <a href="{license}" rel="external">GPL-3.0-or-later</a> license.',
		footerContent:
			'No third-party content ships with the app; you are responsible for the packs you import. Not affiliated with WhatsApp or Meta.',
		footerLinks: { source: 'Source on GitHub', install: 'Install guide', privacy: 'Privacy' }
	},
	download: {
		button: 'Download APK',
		comingSoon: 'APK coming soon',
		metaHtml: 'Version {version} · {size} · <a href="{notes}" rel="external">release notes</a>',
		soonHtml:
			'Cucuto is in development and the first release is on its way. Follow the <a href="{releases}" rel="external">releases page</a>.'
	},
	clock: {
		replay: 'Play the cuckoo clock animation again',
		still: 'A cuckoo clock with its round door open and a bird singing'
	},
	home: {
		eyebrow: 'An LSPosed module for Android 17',
		pitch: 'Give each contact a voice.',
		lead: 'Cucuto plays a different character’s sound and vibration for each WhatsApp and Signal contact, like a cuckoo clock announcing who’s there. A cat for your sister, a trumpet for your boss.',
		howToInstall: 'How to install',
		finePrintHtml:
			'Free and <a href="{repo}" rel="external">open source</a> · Needs root · No tracking',
		videoTitle: 'See it in action',
		videoSoonTitle: 'The explainer video is on its way',
		videoSoonText:
			'A short tour: a message arrives, the phone plays the sender’s character, and you know who wrote without looking.',
		video: {
			soonBadge: 'Coming soon',
			posterAlt:
				'Illustration: a phone shows Cucuto’s character picker, “Voice for Ada”, where Bleep the frog is being assigned in a burst of confetti, above Tweet, Mew, Bzz, Bong, Ting, Swish and Tootle.',
			label: 'Cucuto explainer video',
			play: 'Play the video',
			unsupported:
				'Your browser can’t play this video. The transcript below has everything it says.',
			transcript: 'Read the transcript'
		},
		howTitle: 'How it works',
		steps: iconItems([
			{
				icon: 'library-music',
				title: 'Import a sound pack',
				text: 'Open a .soundpack.zip file, download one from a pack repository or make your own. A pack is a cast of characters: creatures with short clips and their own vibration, or songs to ring with.'
			},
			{
				icon: 'person-pin',
				title: 'Give a contact a character',
				text: 'Pick a chat, tap a character to hear it, tap again to assign. Then, if you like, choose what rings when they call: a song from a pack, the phone’s default ringtone, or theirs as it is.'
			},
			{
				icon: 'notifications-active',
				title: 'Hear who it is',
				text: 'When they message you on WhatsApp or Signal, the notification plays their character’s voice and vibration. Phone in your pocket, you already know who wrote.'
			}
		]),
		extrasTitle: 'More than a new tone',
		extrasLead:
			'Small touches that make each contact sound like themselves. All of it is decided on your phone, with no battery cost.',
		extras: iconItems([
			{
				icon: 'ring-volume',
				title: 'Phone ringtones too',
				text: 'Give a contact a song from a pack as their ringtone, switch them to the phone’s default, or leave theirs as it is. Cucuto sets it on the contact, like the Contacts app does, and can always give the old one back.'
			},
			{
				icon: 'tune',
				title: 'Ringtone rules in plain words',
				text: 'A song can ring live by day and as a radio cut at night. Cucuto shows the pack’s rules as sentences you can read, and you can write your own for one contact.'
			},
			{
				icon: 'wb-twilight',
				title: 'Clips with good timing',
				text: 'Packs can greet the first message of the day, answer a burst of messages, hide a rare easter egg, or play something else at night or on holidays. No sensors and no tracking.'
			},
			{
				icon: 'casino',
				title: 'The right character, fast',
				text: 'Big packs fold into sections, search finds a character by name, and Surprise me picks one at random for you to hear before you keep it.'
			},
			{
				icon: 'contacts',
				title: 'Chats before they write',
				text: 'Add people from your contacts and give them a voice right away. Their first message links the chat, and their voice plays from the next one.'
			},
			{
				icon: 'edit',
				title: 'Make your own packs',
				text: 'Record or pick sounds and pictures in the app, or on this site’s Create page. Nothing is uploaded.'
			}
		]),
		featuresTitle: 'Built to stay out of the way',
		featuresLead:
			'Cucuto changes one thing, the sound and vibration of WhatsApp’s or Signal’s own notification, and leaves everything else to Android.',
		features: iconItems([
			{
				icon: 'notifications-active',
				title: 'One sound, no duplicates',
				text: 'No second notification and no permanent icon in the status bar. The alert you get is the app’s own, with a new voice.'
			},
			{
				icon: 'do-not-disturb',
				title: 'Your settings still rule',
				text: 'Muted chats, Do Not Disturb and silent mode work exactly as before, because Android still decides whether a message alerts.'
			},
			{
				icon: 'mobile-vibrate',
				title: 'A vibration per character',
				text: 'Each character can bring its own vibration pattern, so you can tell who wrote even with the phone on vibrate.'
			},
			{
				icon: 'shield',
				title: 'Safe fallback',
				text: 'If the module is off or anything fails, WhatsApp’s or Signal’s normal tone plays. You never miss a message because of Cucuto.'
			},
			{
				icon: 'groups',
				title: 'Groups too',
				text: 'Give a group chat one character, or let each person in it speak with their own.'
			},
			{
				icon: 'power-settings',
				title: 'Nothing running in the background',
				text: 'No background service, no persistent notification and no battery exemption to grant.'
			}
		]),
		screenshotsTitle: 'A look inside',
		screenshotsLead:
			'The app with the two free demo packs and made-up chats, in your light or dark theme.',
		screenshots: [
			{
				image: 'onboarding',
				title: 'Setup',
				text: 'A short guided setup checks the module and its scope for you.',
				alt: 'Cucuto’s welcome screen: the cuckoo clock with its door open and what the app does.'
			},
			{
				image: 'home',
				title: 'Chats',
				text: 'People who wrote recently, waiting for a voice.',
				alt: 'The home screen: recent senders without a voice and chats with their characters.'
			},
			{
				image: 'picker',
				title: 'Characters',
				text: 'Tap to hear a character, tap again to assign it.',
				alt: 'The character picker for a chat, with the demo packs’ characters grouped by pack.'
			},
			{
				image: 'packs',
				title: 'Packs',
				text: 'Your packs and their characters. Tap one to hear it.',
				alt: 'The Packs screen with the Chiptune critters and Kitchen orchestra demo packs.'
			}
		] satisfies Shot[] as Shot[],
		requirementsTitle: 'Requirements',
		requirementsLead:
			'Cucuto is a power-user tool. Check that your phone has all of these before you install it.',
		requirements: iconItems([
			{
				icon: 'android',
				title: 'Android 17',
				text: 'Cucuto is built for Android 17 (API 37) only. Older versions are not supported.'
			},
			{
				icon: 'lock',
				title: 'Root access',
				text: 'Root lets the module change WhatsApp’s and Signal’s notifications from inside Android itself.'
			},
			{
				icon: 'extension',
				title: 'LSPosed with libxposed API 102',
				text: 'The module runs in the System Framework scope. It never hooks WhatsApp or Signal.'
			},
			{
				icon: 'notifications-active',
				title: 'WhatsApp or Signal',
				text: 'WhatsApp, WhatsApp Business, Signal or Molly. Leave the app’s own tone and vibration on: Cucuto swaps them per contact.'
			}
		]),
		faqTitle: 'Questions',
		faq: [
			{
				q: 'Does Cucuto come with sounds from shows or games?',
				aHtml:
					'No. The app ships empty and no copyrighted content ships with it. You import sound packs yourself; the <a href="{packs}">official repository</a> only hosts free demo packs (CC0). You are responsible for the packs you import.'
			},
			{
				q: 'Why does it need root?',
				aHtml:
					'On Android 17 a normal app can’t reliably play sounds from the background, and copying WhatsApp’s or Signal’s notifications would mean duplicates and clutter. With root, Cucuto swaps the sound and vibration on the app’s own notification, so mute, Do Not Disturb and ringer mode keep working exactly as usual.'
			},
			{
				q: 'Does it modify WhatsApp or Signal?',
				aHtml:
					'No. The module only runs inside Android’s system process (the System Framework scope) and never touches the WhatsApp or Signal apps, so it gets along with other modules that do hook WhatsApp.'
			},
			{
				q: 'Does it work with Signal?',
				aHtml:
					'Yes, with Signal and Molly, from the same module in Android’s system process: nothing is added to Signal. Signal’s notification names the person’s Android contact, so a voice you give someone plays on WhatsApp and Signal alike once their chat is linked to their contact (chats added from contacts, or linked for phone ringtones, already are). Someone not in your contacts gets a voice of their own on Signal. If Signal’s notifications hide names, Signal’s own tone plays. Signal calls keep Signal’s ringtone.'
			},
			{
				q: 'What happens if something goes wrong?',
				aHtml:
					'You hear WhatsApp’s or Signal’s normal tone. If the module is off or anything fails, Android plays the default sound instead of staying silent.'
			},
			{
				q: 'Does it work in group chats?',
				aHtml:
					'Yes. By default a group gets one character of its own. You can also set a group to play the character of whoever wrote.'
			},
			{
				q: 'What about calls?',
				aHtml:
					'Phone calls, yes. Give a contact a ringtone from a pack, switch them to the phone’s default ringtone, or leave theirs as it is. A song with several versions follows the pack’s rules (day and night, seasons, holidays) or rules you set for that contact. Cucuto sets the ringtone on the contact in Android’s contacts (you link the chat to its contact once), and turning phone ringtones off gives every contact its old ringtone back. WhatsApp calls keep WhatsApp’s own ringtone for now, and Signal calls keep Signal’s.'
			},
			{
				q: 'Can someone get a voice before they write to me?',
				aHtml:
					'Yes, if they are in your phone’s contacts: tap <strong>Add a chat from contacts</strong> on Home. Cucuto reads only the WhatsApp and Signal entries your contacts already hold and saves only the people you pick. On Signal their voice plays from their first message. On WhatsApp their first message plays WhatsApp’s tone and links the chat, and their voice plays from the next one. Groups appear after their first message.'
			},
			{
				q: 'Can I try it without root?',
				aHtml:
					'No. Cucuto changes WhatsApp’s and Signal’s sounds through an LSPosed module, and LSPosed needs root. Without it you can still import packs and preview characters, but WhatsApp and Signal keep their own sounds.'
			},
			{
				q: 'Can I make my own packs?',
				aHtml:
					'Yes. Make one in the app (<strong>Packs › Create a pack</strong>) or in your browser on the <a href="{create}">Create page</a>. The pack format is open and documented, packs can carry ringtones and clips for special moments, and anyone can host a pack repository on a static website. See the <a href="{authors}">guide for pack authors</a>.'
			},
			{
				q: 'Is it on Google Play?',
				aHtml:
					'Not yet. A Google Play listing is being prepared. Until it is there, get the APK from <a href="{releases}" rel="external">GitHub</a>, or let Obtainium keep it updated (see the <a href="{install}">install guide</a>). Every copy is signed with the same certificate, so a later install from Play and an install from GitHub update each other. Either way, Cucuto needs root and LSPosed to change WhatsApp’s and Signal’s sounds.'
			},
			{
				q: 'Is it free? Does it collect data?',
				aHtml:
					'Cucuto is free and open source under GPL-3.0-or-later. It has no accounts, no ads and no analytics, and neither does this website. Details on the <a href="{privacy}">privacy page</a>.'
			}
		] satisfies QA[] as QA[]
	},
	packs: {
		title: 'Packs',
		description:
			'Free sound packs for Cucuto: characters with clips and vibrations, ready to import.',
		lead: 'A pack is a cast of characters, each with a picture, short clips and a vibration. Free packs listed here are CC0, so you can use, change and share them.',
		repoNoteHtml:
			'This list is also the app’s default pack repository, <code>{path}</code>: browse and download the same packs from the app, or tap <strong>Add to app</strong> to add it again.',
		bundle: 'Bundle',
		unknownAuthor: 'Unknown author',
		noLicense: 'No license',
		characters: 'Characters',
		play: 'Play {name}’s sample',
		stop: 'Stop {name}’s sample',
		hapticCustom: 'Vibration: own pattern, {seconds} seconds',
		hapticAuto: 'Follows the voice',
		hapticAutoSpoken: 'Vibration follows the voice',
		hapticNone: 'No vibration',
		contains: 'Contains {names}.',
		download: 'Download',
		downloadHidden: '{name} .soundpack.zip',
		addToApp: 'Add to app',
		placeholders: ['Animals', 'Chiptune', 'Little bells'],
		soonTitle: 'The first free packs are on their way',
		soonTextHtml:
			'Once the demo packs are published, this gallery lists every pack with its characters, sample clips and a download button. The same list is available to the app at <code>{path}</code>, the default pack repository.',
		soonAuthorsHtml: 'Want to make one? Read the <a href="{authors}">guide for pack authors</a>.'
	},
	install: {
		title: 'Install guide',
		description:
			'Install Cucuto on a rooted Android 17 phone: APK, LSPosed module with System Framework scope, reboot, status checks.',
		lead: 'Five steps, about five minutes. You need Android 17, root and LSPosed with libxposed API 102 already working on your phone.',
		preReleaseHtml:
			'<strong>Cucuto hasn’t had its first release yet.</strong> This guide describes setting up version 1. The download button appears here as soon as it’s out; follow the <a href="{releases}" rel="external">releases page</a>.',
		beforeTitle: 'Before you start',
		before: iconItems([
			{
				icon: 'android',
				title: 'Android 17',
				text: 'Settings › About phone shows the Android version. Cucuto does not run on Android 16 or older.'
			},
			{
				icon: 'lock',
				title: 'Root',
				text: 'Your phone is rooted (for example with Magisk, KernelSU or APatch). Cucuto does not root your phone for you.'
			},
			{
				icon: 'extension',
				title: 'LSPosed',
				text: 'An LSPosed build that loads modern modules (libxposed API 102) is installed and working.'
			},
			{
				icon: 'notifications-active',
				title: 'WhatsApp or Signal',
				text: 'WhatsApp, WhatsApp Business, Signal or Molly, with notifications on and the app’s own tone and vibration left on.'
			}
		]),
		stepsTitle: 'Steps',
		appIconAlt: 'The Cucuto app icon',
		steps: [
			{
				title: 'Download and install the APK',
				textHtml:
					'Get the latest <code>.apk</code> from the <a href="{releaseUrl}" rel="external">GitHub release page</a> and open it on your phone. Android asks you to allow installs from that app (your browser or file manager) the first time. Once installed, look for this icon.'
			},
			{
				title: 'Enable the module in LSPosed',
				textHtml:
					'Open the LSPosed manager, go to <strong>Modules</strong>, turn on <strong>Cucuto</strong> and check that its scope is <strong>System Framework</strong> only. Cucuto asks for exactly that scope, so it is usually ticked already. Don’t add WhatsApp or Signal to the scope: Cucuto never hooks them.'
			},
			{
				title: 'Reboot',
				textHtml:
					'The module loads into Android’s system process at boot, so a full reboot is needed after enabling it, changing its scope or updating Cucuto.'
			},
			{
				title: 'Check the status card',
				textHtml:
					'Open Cucuto. On first launch a setup guide walks you through these steps and ends with a test notification; you can reopen it from Settings. The status card at the top checks that the module is active, the LSPosed scope is right, WhatsApp or Signal is installed and at least one pack is imported. Each failing check tells you how to fix it.'
			},
			{
				title: 'Import a pack and assign characters',
				textHtml:
					'Import a <code>.soundpack.zip</code> file or pick one from the <a href="{packs}">free packs</a>, then choose a chat, tap a character and, if you like, a ringtone. Someone who hasn’t written yet? Add them with <strong>Add a chat from contacts</strong>. Keep WhatsApp’s or Signal’s own notification tone and vibration turned on: Cucuto replaces them per contact.'
			}
		],
		testTitle: 'Test it',
		testHtml:
			'In Cucuto, open <strong>Diagnostics</strong> and tap <strong>Post test notification</strong>. With the module active you hear a short two-tone chirp and feel a click instead of the default sound, and the decision shows up in the list below within a few seconds.',
		updatesTitle: 'Updates',
		updatesHtml:
			'Cucuto never checks for updates and never goes online to look for them. New versions appear on the <a href="{releases}" rel="external">releases page</a>, and Cucuto keeps working when you skip one. To get them on your phone without checking by hand, use <a href="{obtainium}" rel="external">Obtainium</a>, a free app that installs apps straight from their GitHub releases. After every update, reboot: the module loads the new version at boot.',
		certificateHtml:
			'Every Cucuto APK is signed with the same certificate, whose SHA-256 starts with <code>{cert}</code> (the full value is in the Obtainium settings below and on every release). Once Cucuto is listed on Google Play, Play will install it with that same certificate, so a copy from Play and a copy from GitHub or Obtainium update each other, without uninstalling.',
		obtainiumButton: 'Get it with Obtainium',
		obtainiumHint:
			'Opens Obtainium with Cucuto’s settings filled in. It works on a phone with Obtainium installed.',
		obtainiumManualTitle: 'Set up Obtainium by hand',
		obtainiumManualHtml:
			'In Obtainium, tap <strong>Add app</strong>, paste the source URL, then open the additional options and fill in the rest. The release title filter skips the demo pack releases, and the certificate makes Obtainium refuse an APK signed by anyone else.',
		// Obtainium's own labels, verbatim, so readers find them in its UI.
		obtainiumFields: {
			source: 'App source URL',
			apkFilter: 'Filter links by regex',
			titleFilter: 'Filter release titles by regex',
			cert: 'Expected signing certificate hashes'
		},
		obtainiumJson: 'The same settings as an Obtainium app config (JSON):',
		troubleTitle: 'Troubleshooting',
		trouble: [
			{
				q: 'The module shows as inactive.',
				aHtml:
					'Check in LSPosed that Cucuto is enabled with the System Framework scope, then reboot. LSPosed’s own logs show whether the module loaded.'
			},
			{
				q: 'A contact plays the normal WhatsApp or Signal tone.',
				aHtml:
					'That is the safe fallback: the contact has no character yet, the voices switch is off, or the module isn’t active. The decision log in <strong>Diagnostics</strong> shows what happened for each recent notification. On Signal, also check that Signal’s notifications show at least the name: when they hide it, Signal’s own tone plays.'
			},
			{
				q: 'No sound at all.',
				aHtml:
					'Cucuto only replaces a sound that was going to play. Check that the chat isn’t muted, that Do Not Disturb is off and that WhatsApp’s or Signal’s notification tone isn’t set to silent.'
			},
			{
				q: 'The phone doesn’t boot after enabling the module.',
				aHtml:
					'Boot into your root manager’s safe mode (usually by pressing Volume Down during boot; check its documentation). It turns off every module, LSPosed included. Then uninstall Cucuto and reboot normally: LSPosed drops a module whose app is gone. More recovery options are in the <a href="{repo}/blob/main/docs/install.md#if-the-phone-does-not-boot" rel="external">install guide on GitHub</a>.'
			},
			{
				q: 'Still stuck?',
				aHtml:
					'<a href="{issues}" rel="external">Open an issue on GitHub</a> with your Android build, your LSPosed version and what Diagnostics shows.'
			}
		] satisfies QA[] as QA[],
		removeTitle: 'Turn it off or uninstall',
		removeHtml:
			'To pause Cucuto, turn off the voices switch in the app: every chat goes back to WhatsApp’s or Signal’s own tone right away. To remove it completely, disable the module in LSPosed, reboot and uninstall the app. Uninstalling deletes your imported packs and assignments.'
	},
	authors: {
		title: 'For pack authors',
		description:
			'Make sound packs for Cucuto: the open .soundpack.zip format (a plain zip), the packer tool and hosting your own pack repository.',
		lead: 'Anyone can make a pack: animals, game sounds, a friend’s voice, a song. The format is open, so a pack made anywhere imports everywhere.',
		toc: 'On this page',
		whatTitle: 'What’s in a pack',
		whatHtml:
			'A pack is a plain zip file named <code>&lt;name&gt;.soundpack.zip</code>, with a <code>pack.json</code> manifest at its root and one folder per character. Each character has a picture and short notification clips, call ringtones or both, plus an optional vibration pattern. One file can also hold a <strong>bundle</strong> of several packs.',
		mediaTitle: 'Media',
		media: iconItems([
			{
				icon: 'library-music',
				title: 'Clips',
				text: 'Sounds of 0.2 to 30 seconds each; short ones work best. Ogg Opus is recommended; Ogg Vorbis, MP3, AAC, FLAC and WAV work too.'
			},
			{
				icon: 'person-pin',
				title: 'Pictures',
				text: 'JPEG XL is recommended; PNG, JPEG, WebP, AVIF, GIF and SVG work too, at most 512 px per side. An animated sprite (JPEG XL, WebP or GIF) is optional.'
			},
			{
				icon: 'mobile-vibrate',
				title: 'Vibration',
				text: 'Automatic (it follows the loudness of the clip), none, or your own timeline of taps and swells.'
			}
		]),
		mediaNoteHtml:
			'The <a href="{packFormat}">pack format</a> has the exact limits for durations, sizes and file types.',
		makeTitle: 'Three ways to make one',
		make: [
			{
				title: 'With packer',
				textHtml:
					'Describe your pack in a <code>pack.yaml</code> file and let the command-line tool build, check and bundle it. Available now.'
			},
			{
				title: 'With the pack creator',
				textHtml:
					'Pick pictures, record or import clips, trim them and download the pack on the <a href="{create}">Create page</a>, right in your browser, no tools needed. On the phone, the app’s <strong>Packs › Create a pack</strong> does the same, and <strong>Duplicate and edit</strong> starts from a pack you already have.'
			},
			{
				title: 'By hand',
				textHtml:
					'Write <code>pack.json</code> yourself and zip the folder. Follow the <a href="{packFormat}">pack format</a> to the letter.'
			}
		],
		packerTitle: 'Build it with packer',
		packerIntroHtml:
			'<code>packer</code> turns a folder of clips and pictures into a <code>.soundpack.zip</code> and checks it with the same rules as the app, so a pack that passes imports everywhere. It needs Java 21; build it from the repository:',
		packerYamlHtml:
			'Put your files in a folder with a <code>pack.yaml</code> next to them. Folders and globs pick up every clip:',
		packerCommandsHtml:
			'Then build, validate and, if you like, bundle several packs into one file. Optional flags normalize loudness, trim silence and convert to Opus and JPEG XL (they need <code>ffmpeg</code> and <code>cjxl</code>):',
		packerMoreHtml:
			'All commands and flags are in the <a href="{packerReadme}" rel="external">packer README</a>.',
		momentsTitle: 'Clips and ringtones for the right moment',
		momentsIntroHtml:
			'Any clip or ringtone can have a <code>when</code> condition: <code>day</code> or <code>night</code> (sunrise and sunset where the listener lives), an hour range, seasons, weekdays, and dates such as <code>12-25</code>. Clips also have three special moments, which play instead of the usual random pick:',
		moments: [
			'<code>firstOfDay: true</code>: the chat’s first message of the day (days start at 04:00). A good-morning clip.',
			'<code>burst: 3</code>: the third message in a row, each within a minute of the last. For an impatient friend; give other clips <code>6</code> or <code>10</code> to escalate.',
			'<code>oneIn: 25</code>: a rare clip that plays on about one message in 25, never twice in a row. An easter egg.'
		],
		momentsYamlHtml:
			'In <code>pack.yaml</code> (the same keys go in <code>pack.json</code>). Keys combine, so a clip can be a rare Halloween scream or a greeting only before noon:',
		momentsNoteHtml:
			'Everything is decided on the phone from the clock and a few counters per chat, so it costs no battery. Write conditions with <code>packer</code> or by hand; the pack creator keeps them when you open and edit a pack. The rules are in the <a href="{packFormat}#special-clips">pack format</a>.',
		showTitle: 'How the app shows your pack',
		show: [
			'<strong>Two pickers.</strong> Characters with clips appear when someone picks a message sound; characters with ringtones appear, as record covers, when they pick a ringtone. A pack of songs only is a ringtone pack: it never shows up among message sounds, and that is fine. Most packs are one kind or the other.',
			'<strong>Ringtone rules in plain words.</strong> A character with several ringtones has a <strong>Ringtone rules</strong> screen that reads your conditions back as sentences: “Live by day, Radio at night”, “Rings on 25 December”, “Rings the rest of the time”. Give every ringtone a <code>label</code>; without one, people see the file name.',
			'<strong>Order matters.</strong> When several ringtones match, the one with the most conditions wins, then the first in the list; when none matches, the first one rings. Put the everyday version first. The screen says so when a version can never ring because an earlier one has the same condition.',
			'<strong>People can change them.</strong> Per contact, they can keep your rules, ring a random version per call, stick to one version, or edit their own copy of your rules. Tag versions <code>day</code> and <code>night</code>, or with season names, to also offer the “Day and night” and “By season” choices.',
			'<strong>Special clips are labeled</strong> in the contact’s clip list (“First message of the day”, “Message 3 in a row”, “Rare: about 1 in 25 messages”), so people know what to wait for.'
		],
		checkTitle: 'Check a pack in your browser',
		checkHtml:
			'No Java at hand? Choose a pack file here. This page runs the same rules as the app and <code>packer</code> (the same code, compiled for the web) and reports the same codes. The file stays on your device: nothing is uploaded.',
		hostTitle: 'Host your own repository',
		hostIntroHtml:
			'A pack repository is any HTTPS location that serves a <code>repository.json</code> index and the files it lists: GitHub Pages, Cloudflare Pages, a bucket or your own server. No server code needed. <code>packer index</code> writes a ready-to-upload folder with the index, small previews and the packs (no Java? <a href="{createRepository}">make the folder in your browser</a>):',
		hostShareHtml:
			'Upload the folder and share its URL. People paste it in the app (or scan it as a QR code), browse your packs, preview the clips and download what they like. On a website, an <strong>Add to app</strong> link does it in one tap:',
		hostPrivateHtml:
			'The app checks the size and SHA-256 of every download. This site is the default repository, at <code>{path}</code>.',
		rightsTitle: 'Only share what you may share',
		rightsHtml:
			'Publish only clips and pictures you made or have the rights to, and set the <code>license</code> field. The official repository hosts CC0 packs only. Personal packs with copyrighted content can stay private: import them straight from a file on your phone.',
		specTitle: 'The full specification',
		specHtml:
			'Every field, limit, validation rule and error code is in the pack format, version 1. JSON Schemas and golden test packs in the repository are the machine-readable contract that the app, packer and this website all test against.',
		specButton: 'Read the pack format'
	},
	packFormat: {
		title: 'Pack format',
		description:
			'The Cucuto pack format, version 1: pack.json, bundles, media rules, archive rules, validation and pack repositories.',
		back: 'For pack authors',
		sourceHtml:
			'This page is generated from <a href="{packFormatSource}" rel="external"><code>docs/pack-format.md</code></a> in the repository, where you can also suggest changes.',
		englishOnly: '',
		toc: 'Contents'
	},
	privacy: {
		title: 'Privacy',
		description:
			'The Cucuto website has no analytics, no cookies and no tracking. The app has no accounts and no analytics, and your data stays on your phone.',
		lead: 'Short version: we don’t collect anything. Not on this website, not in the app.',
		updated: 'Last updated: {date}',
		promises: iconItems([
			{ icon: 'block', title: 'No analytics', text: 'No counters, pixels or session recordings.' },
			{ icon: 'cookie', title: 'No cookies', text: 'Nothing is stored to recognize you.' },
			{
				icon: 'no-accounts',
				title: 'No accounts',
				text: 'Nothing to sign up for, no profiles, no ads.'
			},
			{
				icon: 'visibility-off',
				title: 'No message content',
				text: 'Cucuto never reads what your messages say.'
			}
		]),
		siteTitle: 'This website',
		site: [
			'Every page, font, image and sample clip is served from this site; it makes no requests to other services. To work offline and load faster, your browser keeps a copy of the site in its cache (a service worker). That copy stays on your device, and you can clear it in your browser settings.',
			'The <a href="{create}">Create page</a> uses your microphone only while you record, and only if you allow it. Pictures and sounds you add there are processed in your browser and never leave your device. Drafts are kept in your browser’s storage on this device until you delete them or clear the site’s data.',
			'The site is hosted on GitHub Pages, and the APK and pack downloads are GitHub release files. Like any web host, GitHub receives technical data such as your IP address when it serves a file; see the <a href="https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement" rel="external">GitHub General Privacy Statement</a>.'
		],
		appTitle: 'The app',
		appLeadHtml:
			'Cucuto has no accounts, no ads, no analytics and no crash reporting. Everything it knows stays in its private storage on your phone.',
		storedTitle: 'What stays on your phone',
		stored: [
			'<strong>Your packs</strong>: the pack files you import or download.',
			'<strong>Your assignments</strong>: which character speaks for which chat, and your settings.',
			'<strong>Recent chats</strong>: when a WhatsApp or Signal notification arrives, the module notes the chat’s name, the sender’s name and the app’s internal ID for that chat (on Signal, for a saved contact, which of your contacts it is), so new people appear in the app, ready for a voice. It keeps the 1,000 most recent chats.',
			'<strong>The decision log</strong>: what Cucuto did for each recent notification (which voice played, or why the app’s own tone did). It keeps the newest 500 entries, so you can check why a sound played.'
		],
		storedNoteHtml:
			'The module never reads message text, and it never writes names or chat IDs to system logs. Cucuto never opens WhatsApp’s or Signal’s databases, chats or media: it learns about a chat only from its notifications, or from a contact you link or add yourself. Uninstalling the app deletes all of it.',
		moduleTitle: 'How the module works',
		moduleHtml:
			'The module runs inside Android’s system process, where notifications are handled, and only looks at notifications from WhatsApp, WhatsApp Business, Signal and Molly. It hands what it notes to the app over a private channel that refuses every other app. It never goes online and never touches the WhatsApp or Signal apps.',
		networkTitle: 'When the app goes online',
		networkHtml:
			'Only to reach the pack repositories in your list (this site’s is there by default): to show their packs, check for updates and download the packs you choose. Downloads may come from the repository’s own host, such as GitHub for this site’s packs. If a private repository needs a password or token, it is stored encrypted with the Android Keystore and only ever sent to that repository’s own server.',
		backupTitle: 'Backups',
		backup: [
			'Cucuto can save its own backup file, so a new phone gets everything back. It holds the chats Cucuto knows (names, phone numbers, WhatsApp’s and Signal’s chat IDs and the contact each one is linked to), their voices and phone ringtones, the voices switch, your repository list and which packs you use, and the pack files themselves unless you leave them out.',
			'Never in a backup: your day-and-night location, and repository passwords or tokens, unless you add them to a backup you lock with a password of your own. Then the whole file is encrypted. Automatic backups are never encrypted, so they never hold passwords.',
			'Backups go only where you choose: a file you save by hand, or a folder or file you pick for automatic backups. If you pick Google Drive, the Drive app uploads the file; Cucuto itself never uploads anything.',
			'Android’s own cloud backup and device-to-device transfer are turned off for Cucuto, so nothing is copied to Google that way.'
		],
		permissionsTitle: 'Permissions',
		permissions: [
			'<strong>Internet</strong>: for the pack repositories above.',
			'<strong>Notifications</strong>: only for the test notification in Diagnostics.',
			'<strong>Contacts</strong>: only if you turn on phone ringtones, to link a chat to one of your contacts and set that contact’s ringtone (or clear it, when you choose “Android default”), the same setting the Contacts app changes. And when you tap “Add a chat from contacts”: Cucuto reads the WhatsApp and Signal entries your contacts already hold (each a name and a number) and saves only the people you pick. When one of them first messages you, it checks that name against those entries again, so it never links the wrong person with the same name. Nothing else about your contacts is read.',
			'<strong>Phone</strong>: only for “Random per call” ringtones, to notice that a call ended. The number is never read.',
			'<strong>Approximate location</strong>: one reading, only when you tap “Use my location once” on the “Day and night” screen. Never precise location, never in the background.',
			'<strong>Microphone</strong>: only while you record a clip in the pack creator.'
		],
		permissionsNoteHtml:
			'Contacts, phone, location and microphone are asked for only when you use the feature that needs them, and everything else keeps working if you say no. No access to your camera, your files (you pick each file you open or save) or WhatsApp’s or Signal’s data. If a future feature needs more, the app will ask first and this page will say why.',
		checkTitle: 'Check for yourself',
		checkHtml:
			'The website and the app are open source: read the code on <a href="{repo}" rel="external">GitHub</a>. Questions about privacy? <a href="{issues}" rel="external">Open an issue</a>.'
	},
	open: {
		title: 'Open a pack',
		description: 'Open .soundpack.zip files with the Cucuto web app.',
		leadHtml:
			'Cucuto checks the pack files you open here (<code>.soundpack.zip</code>, or any pack <code>.zip</code>) with the same rules as the app, on your device. To make your own, use the <a href="{create}">Create page</a>; to install a pack, import it in the Cucuto app on your phone.',
		opened: 'You opened:',
		browse: 'Browse free packs',
		edit: 'Edit in the pack creator',
		editFailed: 'The pack could not be handed to the creator ({error}).'
	},
	packCheck: {
		choose: 'Choose pack files',
		checking: 'Checking…',
		valid: 'Ready to import',
		validWithWarnings: 'Ready to import, with warnings',
		invalid: 'The app would not import this file',
		bundle: 'A bundle of {count} packs:',
		pack: '{version} by {author}, characters: {count}',
		error: 'error',
		warning: 'warning',
		codesHtml:
			'Every code is explained in the <a href="{packFormat}#error-codes">pack format</a>. Details are in English.',
		failed: 'The file could not be checked ({error}).',
		unavailable: 'The pack checker is not part of this build of the site.'
	},
	create: {
		title: 'Create a pack',
		description:
			'Make a Cucuto sound pack in your browser: pictures, recorded or imported clips, trimmed and evened out, checked and downloaded as a .soundpack.zip. Nothing is uploaded.',
		leadHtml:
			'Pick pictures, record or import sounds, and download a <code>.soundpack.zip</code> that the app imports. It runs the app’s own rules, so what you make here imports everywhere.',
		privacy: 'Everything happens on this device: pictures and sounds are never uploaded.',
		offline:
			'Works offline: install this site as an app from your browser’s menu and create packs without a connection.',
		loading: 'Loading the pack creator…',
		unavailable: 'The pack creator is not part of this build of the site.',
		loadFailed: 'The pack creator could not load ({error}). Reload the page to try again.',
		wavNotice:
			'This browser cannot encode Opus, so clips are saved as WAV. They are bigger but work the same. Chrome, Edge and Firefox on a computer save Opus.',
		drafts: {
			title: 'Your drafts',
			intro:
				'The pack you are making is saved in this browser after every change, also offline. Nothing is uploaded; clearing the site’s data deletes the drafts.',
			saved: 'Draft saved on this device',
			saving: 'Saving the draft…',
			unavailable:
				'This browser cannot keep drafts (a private window, or site data is blocked). Download the pack before you leave the page.',
			saveFailed: 'The draft could not be saved ({error}).',
			loadFailed: 'This draft could not be opened ({error}).',
			untitled: 'Untitled pack',
			summary: 'Characters: {characters} · clips: {clips} · {date}',
			current: 'Open now',
			resume: 'Resume',
			delete: 'Delete',
			confirmDelete: 'Delete “{name}”? This cannot be undone.',
			confirm: 'Delete',
			cancel: 'Keep it',
			new: 'Start a new pack',
			open: 'Open a pack file',
			openHint: 'Edit a .soundpack.zip made here, in the app or with packer.'
		},
		opening: {
			busy: 'Opening {name}…',
			invalid: '{name} cannot be opened: it does not pass the pack check.',
			bundle: '{name} is a bundle of several packs. Pick the one to edit:',
			failed: '{name} could not be opened ({error}).',
			editing: 'Editing {name} {version}',
			update:
				'Saves as an update: the same pack ID, version {version}. The app replaces the installed pack and keeps every contact’s voice.',
			copy: 'Save as a new pack instead, with its own ID and version 1.0.0, to install it next to the original',
			kept: 'Animated pictures are kept from the original as they are, though this page does not edit them yet: {animations}.',
			undecodable:
				'Clips this browser cannot play: {n}. They stay in the pack as they are, without preview or editing.'
		},
		details: {
			title: 'Pack details',
			name: 'Pack name',
			author: 'Author',
			authorHint: 'Your name or nickname, shown in the app.',
			license: 'License',
			licenses: {
				'Personal use only':
					'Personal use only: right for clips and pictures you did not make yourself',
				'CC0-1.0': 'CC0 1.0: anyone may use it, no conditions',
				'CC-BY-4.0': 'CC BY 4.0: anyone may use it, with credit',
				other: 'Another license'
			} as Record<string, string>,
			licenseOther: 'Your license',
			licenseOtherHintHtml:
				'An <a href="https://spdx.org/licenses/" rel="external">SPDX identifier</a> such as <code>MIT</code>, or your own words such as “Personal use only”.',
			descriptionLabel: 'Description (optional)',
			packId: 'Pack ID',
			packIdHint: 'Made from the author and name; it stays the same for this draft.',
			packIdKept: 'The ID of the pack you opened, so the app treats this as an update.',
			version: 'Version',
			versionHint: 'Raise it for every update you share, for example 1.0.1 or 1.1.0.'
		},
		cover: {
			title: 'Cover (optional)',
			hint: 'Shown on the pack’s card in the app. Without one, the app shows the first character.',
			choose: 'Choose a cover',
			change: 'Change cover',
			remove: 'Remove cover',
			alt: 'Cover of {name}',
			none: 'No cover'
		},
		characters: {
			title: 'Characters',
			intro:
				'Each character is a picture with clips for messages, ringtones for calls, or both. The app picks one of a character’s clips at random for every message.',
			add: 'Add a character',
			empty: 'No characters yet.',
			heading: 'Character {n}',
			name: 'Name',
			remove: 'Remove character',
			color: 'Color',
			colorHint: 'Suggested from the picture. The app themes this character with it.',
			description: 'Description (optional)'
		},
		picture: {
			choose: 'Choose a picture',
			change: 'Change picture',
			alt: 'Picture of {name}',
			none: 'No picture yet',
			failed: 'This picture could not be opened ({error}).'
		},
		crop: {
			title: 'Frame the picture',
			help: 'Drag the picture or use the arrow keys to move it. Zoom with the slider, the mouse wheel, or the + and − keys.',
			area: 'Picture framing area',
			zoom: 'Zoom',
			use: 'Use picture',
			cancel: 'Cancel'
		},
		clips: {
			title: 'Clips',
			record: 'Record',
			stop: 'Stop recording',
			recording: 'Recording: {time} s of {max} s',
			upload: 'Add audio files',
			play: 'Play',
			stopPlaying: 'Stop',
			edit: 'Edit',
			remove: 'Remove',
			label: 'Label',
			weight: 'Weight',
			weightHint: 'How often it plays compared with the other clips (1 is normal).',
			seconds: '{s} s',
			longHint:
				'Longer than 5 s. Android plays notification sounds to the end and lowers other audio meanwhile: fine for a jingle, but short clips feel snappier.',
			micFailed:
				'The microphone is not available ({error}). Check the site’s microphone permission.',
			noMic: 'This browser cannot record. Add audio files instead.',
			decodeFailed: '{name} could not be opened: this browser cannot play it.',
			tooLong: '{name} is longer than 6 minutes, the longest audio the creator opens.',
			recordingLabel: 'Recording {n}'
		},
		conditions: {
			clipTitle: 'When it plays',
			ringtoneTitle: 'When it rings',
			intro: 'Leave everything as it is for a clip that can play on any message.',
			ringtoneIntro:
				'The app rings the most specific version whose condition holds, and the first version when none does.',
			moment: 'Which message',
			momentAny: 'Any message, in the rotation',
			momentFirst: 'The chat’s first message of the day (days start at 4:00)',
			momentBurst: 'A message in a row, each within a minute of the one before',
			burst: 'Message number',
			rare: 'Rare clip: left out of the rotation, it plays now and then',
			oneIn: 'About one message in',
			time: 'Time of day',
			timeAny: 'Any time',
			timeDay: 'By day',
			timeNight: 'At night',
			timeHours: 'Between hours',
			dayHint:
				'Day runs from sunrise to sunset for the place set in the app (7:00 to 19:00 until one is set).',
			from: 'From',
			to: 'To',
			hoursHint: 'Includes “from”, excludes “to”, and may cross midnight (22:00 to 06:00).',
			seasons: 'Seasons',
			seasonsHint: 'None chosen: all year. Flipped south of the equator.',
			weekdays: 'Days of the week',
			weekdaysHint: 'None chosen: every day.',
			dates: 'Dates',
			datesHint:
				'Days of the year, every year, as month-day (12-25). Add an end day for a range; ranges may cross the new year.',
			dateFrom: 'Day',
			dateTo: 'Until (optional)',
			date: 'Date {n}',
			addDate: 'Add a date',
			removeDate: 'Remove date {n}',
			save: 'Save',
			cancel: 'Cancel',
			problems: {
				TIME_INVALID: 'Write the hours as HH:MM, for example 22:00.',
				TIME_RANGE_EMPTY: 'The hours start and end at the same time. Pick another end.',
				DATE_INVALID:
					'Date {n} is not a day of the year. Write it as month-day, for example 12-25.',
				TOO_MANY_DATES: 'A condition can have up to 50 dates.',
				BURST_OUT_OF_RANGE: 'The message number goes from 2 to 50.',
				ONE_IN_OUT_OF_RANGE: 'A rare clip plays on about one message in 2 to 10,000.',
				RINGTONE_DURATION_OUT_OF_RANGE: 'Ringtones last from 5 seconds to 6 minutes.',
				LOOP_INVALID:
					'The loop must last at least 1 second and end before the song does. Move its start or end.',
				FADE_IN_OUT_OF_RANGE: 'The fade-in lasts from 0 to 10 seconds.',
				WEIGHT_OUT_OF_RANGE: 'How often goes from 0.1 to 100.'
			} as Record<string, string>
		},
		summary: {
			day: 'By day',
			night: 'At night',
			hours: '{from} to {to}',
			firstOfDay: 'First message of the day',
			burst: 'Message {n} in a row',
			rare: 'Rare: about 1 in {n}',
			dateRange: '{from} to {to}',
			always: 'Always',
			seasons: {
				spring: 'Spring',
				summer: 'Summer',
				autumn: 'Autumn',
				winter: 'Winter'
			} as Record<string, string>
		},
		ringtones: {
			title: 'Ringtones',
			intro:
				'Songs for calls, for contacts who pick this character as their ringtone. Several versions of a song can ring at different times, such as day and night. A character can have only ringtones.',
			add: 'Add a ringtone',
			hint: 'Audio files of 5 seconds to 6 minutes, kept in their own format.',
			play: 'Play',
			stopPlaying: 'Stop',
			label: 'Label',
			edit: 'Edit',
			addVersion: 'Add a version',
			remove: 'Remove',
			versionLabel: '{label} (version {n})',
			untitled: 'Ringtone {n}',
			tooShort: '{name} lasts {s} s. Ringtones need at least 5 seconds.',
			tooLong: '{name} is longer than 6 minutes, the longest ringtone.',
			unsupported:
				'{name} is not a format packs accept. Use Ogg, Opus, MP3, M4A, AAC, FLAC or WAV.',
			decodeFailed: '{name} could not be opened: this browser cannot play it.',
			noPreview: 'This browser cannot play this file; it stays in the pack as it is.'
		},
		ringtoneEditor: {
			title: 'Edit ringtone',
			source: '{name}, {s} s',
			label: 'Label',
			loop: 'Looping',
			loopWhole: 'Loop the whole song',
			loopOnce: 'Play once, then silence',
			loopSection: 'Play an intro once, then loop a section',
			loopStart: 'Loop starts',
			loopEnd: 'Loop ends',
			playLoop: 'Play the loop',
			fadeIn: 'Fade in',
			fadeInValue: '{s} s',
			weight: 'How often for “random per call”',
			weightHint:
				'Compared with the other versions, when a contact picks a random version for every call (1 is normal).',
			save: 'Save',
			cancel: 'Cancel'
		},
		more: {
			title: 'More details (optional)',
			homepage: 'Homepage',
			homepageHint: 'A web page about the pack, starting with https://.',
			homepageInvalid: 'Write a full address that starts with https://.',
			tags: 'Tags',
			tagsHint: 'Words separated by commas, for example: animals, 8-bit.',
			tagsResult: 'Saved as: {tags}'
		},
		clipEditor: {
			title: 'Edit clip',
			source: 'From {name}, {s} s',
			start: 'Start',
			end: 'End',
			selection: 'Selected: {s} s',
			hook: 'Find the hook',
			hookHint: 'Selects the loudest {s} s, usually the chorus.',
			trimSilence: 'Trim silence at both ends',
			normalize: 'Even out the loudness, like packer does',
			play: 'Play selection',
			save: 'Save clip',
			cancel: 'Cancel',
			processing: 'Processing…',
			tooShort: 'After trimming, this clip lasts {s} s. Clips need at least 0.2 s.',
			tooLong: 'Clips can last up to 30 s. Make the selection shorter.',
			silent: 'This part is silent. Pick another part or record again.',
			failed: 'The clip could not be saved ({error}).'
		},
		haptics: {
			title: 'Vibration',
			text: 'Each character vibrates along with its voice: the app works the pattern out from every clip when it imports the pack. To turn it off or design your own pattern, use Vibration on the character’s card. Browsers cannot play these vibrations: import the pack in the app to feel them.'
		},
		hapticEditor: {
			title: 'Vibration',
			auto: 'Follow the voice (automatic)',
			none: 'No vibration',
			custom: 'Custom pattern',
			intro:
				'Taps and swells on a timeline, in milliseconds from the start of the sound. Browsers cannot play them: import the pack in the app and preview the pattern there.',
			timeline: 'Pattern timeline: {n} events over {ms} ms',
			empty: 'No events yet. Add a tap or a swell, or paste a pattern.',
			emptyNote: 'Until the pattern has an event, the pack uses the automatic vibration.',
			tap: 'Tap',
			swell: 'Swell',
			addTap: 'Add a tap',
			addSwell: 'Add a swell',
			event: 'Event {n}: {kind}',
			start: 'Start (ms)',
			preset: 'Kind of tap',
			presets: {
				CLICK: 'Click (crisp)',
				TICK: 'Tick (lighter)',
				LOW_TICK: 'Low tick (lightest)'
			} as Record<string, string>,
			strength: 'Strength',
			point: 'Point {n}',
			intensity: 'Intensity',
			sharpness: 'Sharpness',
			duration: 'Time to reach it (ms)',
			addPoint: 'Add a point',
			removePoint: 'Remove point {n}',
			removeEvent: 'Remove event {n}',
			valid: 'Valid pattern: {n} events, {ms} ms.',
			copyAi: 'Copy for AI',
			copyAiHint:
				'Copies a prompt with the clips’ lengths and loudness, the current pattern and the format. Paste it into any AI chat (attach the sound if the chat takes audio), then paste the answer below. Cucuto never sends anything to an AI itself.',
			copied: 'Copied. Paste it into an AI chat.',
			copyFailed: 'The browser did not allow copying. Select the text below and copy it.',
			paste: 'Paste a pattern',
			pasteHint: 'JSON from an AI chat or from another pack: the whole haptic or just its events.',
			usePattern: 'Use pattern',
			pasted: 'Pattern replaced. Undo brings the old one back.',
			undo: 'Undo',
			where: {
				pattern: 'The pattern',
				event: 'Event {event}',
				point: 'Event {event}, point {point}'
			},
			problems: {
				NotJson: 'There is no JSON pattern in the text.',
				NotCustom: 'The mode is “{value}”; a pasted pattern must be “custom”.',
				NoEvents: 'The pattern has no events.',
				TooManyEvents: 'A pattern can have up to 64 events.',
				NotAnEvent: 'Event {event} needs either “preset” or “envelope” (exactly one of them).',
				MissingField: '{where}: “{field}” is missing.',
				UnknownField: '{where}: “{field}” is not part of the format.',
				NotWholeNumber: '{where}: “{field}” must be a whole number of milliseconds, not {value}.',
				OutOfRange: '{where}: “{field}” must be between {min} and {max}, not {value}.',
				UnknownPreset: 'Event {event}: “{value}” is not a kind of tap (CLICK, TICK or LOW_TICK).',
				PointCount: 'Event {event}: a swell needs 1 to 16 points, not {count}.',
				NotClosed: 'Event {event}: the last point of a swell must have intensity 0.',
				Unsorted: 'Event {event} starts before the event above it. Sort the events by start.',
				Overlap: 'Event {event} starts at {ms} ms, before the swell above it ends at {until} ms.',
				TooLong: 'The pattern lasts {ms} ms; it must end by 5000 ms.'
			} as Record<string, string>
		},
		build: {
			title: 'Check and download',
			intro: 'Cucuto checks the pack with the same rules as the app before you download it.',
			check: 'Check pack',
			checking: 'Building and checking…',
			incomplete: 'Almost there:',
			download: 'Download pack',
			share: 'Send to an app',
			shareHint: 'Opens your phone’s share sheet, where you can pick Cucuto.',
			installHtml:
				'To install it, open the file on your phone with the Cucuto app, or import it from the Packs screen. You can also <a href="{authors}#host">host it in a pack repository</a>.',
			stale: 'You changed the pack since this check. Check it again before downloading.',
			failed: 'The pack could not be built ({error}).',
			problems: {
				PACK_NAME_MISSING: 'Give the pack a name.',
				AUTHOR_MISSING: 'Add an author.',
				LICENSE_MISSING: 'Pick a license.',
				NO_CHARACTERS: 'Add at least one character.',
				TOO_MANY_CHARACTERS: 'A pack can have up to 500 characters.',
				CHARACTER_NAME_MISSING: 'Name character {n}.',
				SPRITE_MISSING: 'Choose a picture for {name}.',
				NO_CLIPS: 'Add a clip or a ringtone to {name}.',
				TOO_MANY_CLIPS: '{name} has more than 200 clips.',
				UNSUPPORTED_FILE: '{name} has a file the pack format does not accept.'
			} as Record<string, string>
		},
		tools: {
			title: 'More tools',
			bundle: 'Bundle several packs',
			bundleText: 'Combine finished packs into one file that installs them all at once.',
			repository: 'Host your own repository',
			repositoryText:
				'Make a folder with repository.json, previews and your packs, ready to upload to GitHub Pages or any web host.'
		}
	},
	bundle: {
		title: 'Bundle packs',
		description:
			'Combine several Cucuto sound packs into one .soundpack.zip bundle in your browser, like packer bundle. Nothing is uploaded.',
		leadHtml:
			'A bundle is one file with several packs inside, each keeping its own license. The app installs every pack in it at once. This page writes the same layout as <code>packer bundle</code>, on your device.',
		back: 'Back to the pack creator',
		packs: {
			title: 'Packs',
			add: 'Add pack files',
			hint: '.soundpack.zip files made here, in the app or with packer. A bundle cannot hold another bundle.',
			empty: 'No packs yet.',
			item: '{id} · version {version} · {characters} characters',
			remove: 'Remove {name}',
			invalid: '{name} does not pass the pack check, so it cannot go in a bundle.',
			isBundle: '{name} is already a bundle. Add the packs inside it instead.',
			failed: '{name} could not be read ({error}).'
		},
		details: {
			title: 'Bundle details',
			name: 'Bundle name',
			author: 'Author (optional)',
			id: 'Bundle ID',
			idHint:
				'Made from the author and name. Keep it different from the packs’ IDs, and the same in every update.',
			version: 'Version',
			versionHint: 'Raise it for every update you share, for example 1.0.1.',
			license: 'License (optional)',
			licenseHint: 'Each pack keeps its own license.',
			description: 'Description (optional)',
			homepage: 'Homepage (optional)',
			homepageHint: 'A web page about the bundle, starting with https://.',
			homepageInvalid: 'Write a full address that starts with https://.',
			tags: 'Tags (optional)',
			tagsHint: 'Words separated by commas, for example: animals, 8-bit.',
			tagsResult: 'Saved as: {tags}'
		},
		build: {
			title: 'Check and download',
			check: 'Check bundle',
			checking: 'Building and checking…',
			download: 'Download bundle',
			incomplete: 'Almost there:',
			stale: 'You changed the bundle since this check. Check it again before downloading.',
			failed: 'The bundle could not be built ({error}).',
			problems: {
				BUNDLE_ID_INVALID:
					'The bundle ID must be lowercase words separated by dots, for example local.me.all-my-packs.',
				BUNDLE_NAME_MISSING: 'Give the bundle a name.',
				VERSION_INVALID: 'The version must look like 1.0.0.',
				NO_PACKS: 'Add at least one pack.',
				TOO_MANY_PACKS: 'A bundle can hold up to 100 packs.',
				PACK_TWICE: 'Two packs have the same ID. Keep only one of them.',
				ID_CLASH: 'The bundle ID is the same as one of its packs’ IDs. Pick another one.',
				UNSUPPORTED_FILE: 'The cover is not an image the pack format accepts.'
			} as Record<string, string>
		}
	},
	repository: {
		title: 'Host your own repository',
		description:
			'Make a ready-to-upload Cucuto pack repository (repository.json, previews and packs) in your browser, like packer index. Nothing is uploaded.',
		leadHtml:
			'A pack repository is a folder on any HTTPS host: a <code>repository.json</code> index, small previews and the pack files. The app lists it in Discover and offers updates. This page writes the same folder as <code>packer index</code>, on your device.',
		back: 'Back to the pack creator',
		files: {
			title: 'Packs and bundles',
			add: 'Add pack files',
			hint: '.soundpack.zip packs or bundles. If a pack is there twice, the index lists the highest version.',
			empty: 'No packs yet.',
			item: '{kind} · {id} · version {version} · {size}',
			pack: 'Pack',
			bundle: 'Bundle',
			remove: 'Remove {name}',
			invalid: '{name} does not pass the pack check, so it cannot be listed.',
			failed: '{name} could not be read ({error}).'
		},
		details: {
			title: 'Repository details',
			name: 'Repository name',
			description: 'Description (optional)',
			homepage: 'Homepage (optional)',
			homepageHint: 'An https:// address about you or your packs.',
			where: 'Where the pack files live',
			here: 'In the same folder, under packs/ (the simplest)',
			elsewhere: 'Somewhere else, for example GitHub Releases',
			baseUrl: 'Address of the folder with the pack files',
			baseUrlHint: 'https:// only. Each pack is listed as this address followed by its file name.',
			httpsOnly: 'Use an https:// address.'
		},
		build: {
			title: 'Make the folder',
			make: 'Make repository',
			making: 'Indexing…',
			incomplete: 'Almost there:',
			nameMissing: 'Give the repository a name.',
			noFiles: 'Add at least one pack file.',
			failed: 'The repository could not be made ({error}).',
			invalid: 'The index does not pass the repository check:',
			ready: 'Ready. Entries in the index: {n}. Unzip the folder and upload everything in it.',
			download: 'Download the folder (.zip)',
			copy: 'Copy repository.json',
			copied: 'Copied.',
			stale: 'You changed something since the folder was made. Make it again before downloading.'
		},
		host: {
			title: 'Put it online',
			githubTitle: 'GitHub Pages (free)',
			github: [
				'Create a public repository on GitHub, for example <code>my-packs</code>.',
				'Unzip the folder. On the repository’s page, choose <strong>Add file → Upload files</strong>, drop everything that was inside the folder (<code>repository.json</code>, <code>previews</code> and <code>packs</code>) and commit.',
				'Open <strong>Settings → Pages</strong>. Under <strong>Build and deployment</strong>, set the source to <strong>Deploy from a branch</strong>, pick <code>main</code> and <code>/ (root)</code>, and save.',
				'After a minute or two the repository is online at <code>https://&lt;your user&gt;.github.io/my-packs/</code>. In the app, open <strong>Discover → Add a repository</strong> and paste that address.'
			],
			githubNote:
				'GitHub’s upload page takes files up to 25 MB, and Git refuses files over 100 MB. Put bigger packs on GitHub Releases and choose “Somewhere else” with the release’s download address.',
			driveTitle: 'Google Drive',
			driveHtml:
				'Drive cannot serve a repository: its links point to file IDs, not to paths next to <code>repository.json</code>. Share packs one by one instead: upload the <code>.soundpack.zip</code>, share it with <strong>Anyone with the link</strong>, and paste the link in the app’s <strong>Discover → Add a repository</strong>, which downloads it directly. For a list of packs with updates, use GitHub Pages or another web host.',
			otherTitle: 'Other web hosts',
			otherHtml:
				'Cloudflare Pages, Netlify, a storage bucket or your own server work the same way: upload what is inside the folder and share its <code>https://</code> address. No server code is needed.',
			address: 'Your repository’s address (optional)',
			addressHint:
				'Where the folder will be online, to get a link that adds it to the app in one tap.',
			addLink: 'Add-to-app link:',
			docsHtml:
				'More in <a href="{authors}#host">Host your own repository</a> and in the <a href="{packFormat}#pack-repositories">pack format</a>.'
		}
	},
	error: {
		notFoundTitle: 'Page not found',
		otherTitle: 'Something went wrong',
		notFoundHeading: 'Nobody’s home at this address',
		notFoundText: 'This page doesn’t exist. Check the address or start from the home page.',
		otherText: 'The page couldn’t load ({status}). Try again in a moment.',
		home: 'Go to the home page'
	}
};

export type Messages = typeof en;
