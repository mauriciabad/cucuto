/** File size in megabytes or kilobytes, the way people read download sizes ("4.8 MB", "320 kB"). */
export function formatSize(bytes: number): string {
	const megabytes = bytes >= 1_000_000;
	return new Intl.NumberFormat('en', {
		style: 'unit',
		unit: megabytes ? 'megabyte' : 'kilobyte',
		maximumFractionDigits: megabytes ? 1 : 0
	}).format(megabytes ? bytes / 1_000_000 : Math.max(1, bytes / 1_000));
}
