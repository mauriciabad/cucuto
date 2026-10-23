<!-- A Material Symbols Rounded icon (src/lib/icons.ts), decorative: label the surrounding control instead. -->
<script lang="ts">
	import { ICON_PATHS, type IconName } from '#lib/icons.js';

	interface Props {
		name: IconName;
		class?: string;
	}

	let { name, class: className = '' }: Props = $props();
</script>

<svg class="icon {className}" viewBox="0 -960 960 960" aria-hidden="true" focusable="false">
	<path d={ICON_PATHS[name]} />
</svg>
