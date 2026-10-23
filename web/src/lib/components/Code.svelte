<!-- A code block. Focusable, so keyboard users can scroll long lines sideways (axe: scrollable-region-focusable). -->
<script lang="ts">
	let { code, label }: { code: string; label?: string } = $props();
</script>

<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
<pre tabindex="0" aria-label={label}><code>{code}</code></pre>
