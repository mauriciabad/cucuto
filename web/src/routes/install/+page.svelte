<script lang="ts">
	import Icon from '#lib/components/Icon.svelte';
	import Seo from '#lib/components/Seo.svelte';
	import { ISSUES_URL, LATEST_RELEASE_URL, REQUIREMENTS } from '#lib/site.js';
</script>

<Seo
	title="Install guide"
	description="Install Cucuto on a rooted Android 17 phone: APK, LSPosed module with System Framework scope, reboot, status checks."
/>

<div class="prose">
	<header class="page-header">
		<h1 class="display-small emphasized">Install guide</h1>
		<p class="body-large">
			Five steps, about five minutes. You need {REQUIREMENTS.android}, root and LSPosed with
			libxposed API {REQUIREMENTS.libxposedApi} already working on your phone.
		</p>
	</header>

	<ol class="install-steps">
		<li>
			<h2 class="title-large">Download and install the APK</h2>
			<p>
				Get the latest <code>.apk</code> from the
				<a href={LATEST_RELEASE_URL} rel="external">GitHub release page</a> and open it on your phone.
				Android asks you to allow installs from that app (your browser or file manager) the first time.
			</p>
		</li>
		<li>
			<h2 class="title-large">Enable the module in LSPosed</h2>
			<p>
				Open the LSPosed manager, go to <strong>Modules</strong>, turn on <strong>Cucuto</strong>
				and set its scope to <strong>System Framework</strong> only. Do not add WhatsApp to the scope:
				Cucuto never hooks the WhatsApp app.
			</p>
		</li>
		<li>
			<h2 class="title-large">Reboot</h2>
			<p>
				The module loads into the Android system at boot, so a full reboot is needed after enabling
				it or changing its scope.
			</p>
		</li>
		<li>
			<h2 class="title-large">Check the status card</h2>
			<p>
				Open cucuto. The status card at the top checks that the module is active, the LSPosed scope
				is right, WhatsApp is installed and at least one pack is imported. Each failing check tells
				you how to fix it.
			</p>
		</li>
		<li>
			<h2 class="title-large">Import a pack and assign characters</h2>
			<p>
				Import a <code>.soundpack</code> file, then pick a chat and tap a character. Keep WhatsApp’s own
				notification tone and vibration turned on: Cucuto replaces them per contact.
			</p>
		</li>
	</ol>

	<h2 class="headline-small">Troubleshooting</h2>
	<ul>
		<li>
			<strong>The module shows as inactive.</strong> Check that the scope is System Framework and that
			you rebooted after enabling it.
		</li>
		<li>
			<strong>A contact plays the normal WhatsApp tone.</strong> That is the safe fallback: the contact
			has no character yet, the chat is muted, or the module is off. The decision log in the app’s settings
			shows why for each recent notification.
		</li>
		<li>
			<strong>Still stuck?</strong>
			<a href={ISSUES_URL} rel="external">Open an issue on GitHub</a> with your Android build and the
			decision log.
		</li>
	</ul>

	<p class="back">
		<a class="button tonal" href={LATEST_RELEASE_URL} rel="external">
			<Icon name="download" />
			Download APK
		</a>
	</p>
</div>

<style>
	.install-steps {
		counter-reset: step;
		list-style: none;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 1rem;
	}

	.install-steps li {
		counter-increment: step;
		position: relative;
		padding: 1.5rem 1.5rem 1.5rem 5rem;
		border-radius: 1.75rem;
		background: var(--md-sys-color-surface-container-low);
	}

	.install-steps li::before {
		content: counter(step);
		position: absolute;
		left: 1.25rem;
		top: 1.25rem;
		display: grid;
		place-items: center;
		width: 2.5rem;
		height: 2.5rem;
		border-radius: 50%;
		background: var(--md-sys-color-primary);
		color: var(--md-sys-color-on-primary);
		font: 800 1.125rem/1 var(--cucuto-font);
		font-variation-settings: 'ROND' 100;
	}

	.install-steps p {
		margin-top: 0.5rem;
		color: var(--md-sys-color-on-surface-variant);
	}

	.back {
		margin-top: 2.5rem;
	}
</style>
