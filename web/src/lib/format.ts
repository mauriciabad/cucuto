/** File size in megabytes or kilobytes, the way people read download sizes ("4.8 MB", "320 kB"). */
export function formatSize(bytes: number, locale = 'en'): string {
	const megabytes = bytes >= 1_000_000;
	return new Intl.NumberFormat(locale, {
		style: 'unit',
		unit: megabytes ? 'megabyte' : 'kilobyte',
		maximumFractionDigits: megabytes ? 1 : 0
	}).format(megabytes ? bytes / 1_000_000 : Math.max(1, bytes / 1_000));
}

/** A calendar date ("October 24, 2026", "24 de octubre de 2026") from an ISO `YYYY-MM-DD` string. */
export function formatDate(isoDate: string, locale = 'en'): string {
	return new Intl.DateTimeFormat(locale, { dateStyle: 'long', timeZone: 'UTC' }).format(
		new Date(`${isoDate}T00:00:00Z`)
	);
}
