// A minimal Ogg Opus writer (RFC 7845 on RFC 3533): WebCodecs' AudioEncoder returns raw Opus packets, and a pack
// needs a file. One logical stream, mono, an OpusHead and an OpusTags page, then audio pages of up to about a second.

const OPUS_RATE = 48_000;
const MAX_SEGMENTS = 255;
const PACKETS_PER_PAGE = 50;

const CRC_TABLE = (() => {
	const table = new Uint32Array(256);
	for (let i = 0; i < 256; i++) {
		let r = i << 24;
		for (let j = 0; j < 8; j++) r = r & 0x80000000 ? (r << 1) ^ 0x04c11db7 : r << 1;
		table[i] = r >>> 0;
	}
	return table;
})();

/** Ogg's CRC-32: polynomial 0x04C11DB7, not reflected, initial value 0, no final XOR. */
export function oggCrc(bytes: Uint8Array): number {
	let crc = 0;
	for (const b of bytes) crc = ((crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ b) & 0xff]) >>> 0;
	return crc >>> 0;
}

const ascii = (text: string) => new TextEncoder().encode(text);

/** The identification header. `preSkip` is in 48 kHz samples. */
export function opusHead(channels: number, preSkip: number, inputSampleRate: number): Uint8Array {
	const head = new Uint8Array(19);
	const view = new DataView(head.buffer);
	head.set(ascii('OpusHead'));
	head[8] = 1; // version
	head[9] = channels;
	view.setUint16(10, preSkip, true);
	view.setUint32(12, inputSampleRate, true);
	view.setInt16(16, 0, true); // output gain
	head[18] = 0; // channel mapping family 0: mono or stereo
	return head;
}

/** The comment header: a vendor string and no comments (no clock time or user data leaks into the file). */
export function opusTags(vendor: string): Uint8Array {
	const v = ascii(vendor);
	const tags = new Uint8Array(8 + 4 + v.length + 4);
	const view = new DataView(tags.buffer);
	tags.set(ascii('OpusTags'));
	view.setUint32(8, v.length, true);
	tags.set(v, 12);
	view.setUint32(12 + v.length, 0, true);
	return tags;
}

/** The pre-skip of an OpusHead (for example the `description` WebCodecs gives), or null when it is not one. */
export function preSkipOf(description: Uint8Array | undefined): number | null {
	if (!description || description.length < 19) return null;
	if (new TextDecoder().decode(description.subarray(0, 8)) !== 'OpusHead') return null;
	return new DataView(description.buffer, description.byteOffset, description.byteLength).getUint16(
		10,
		true
	);
}

interface Page {
	packets: Uint8Array[];
	granule: bigint;
	flags: number;
}

function writePage(page: Page, serial: number, sequence: number): Uint8Array {
	const lacing: number[] = [];
	for (const packet of page.packets) {
		let left = packet.length;
		while (left >= 255) {
			lacing.push(255);
			left -= 255;
		}
		lacing.push(left);
	}
	const bodyLength = page.packets.reduce((sum, p) => sum + p.length, 0);
	const out = new Uint8Array(27 + lacing.length + bodyLength);
	const view = new DataView(out.buffer);
	out.set(ascii('OggS'));
	out[4] = 0; // stream structure version
	out[5] = page.flags;
	view.setBigInt64(6, page.granule, true);
	view.setUint32(14, serial, true);
	view.setUint32(18, sequence, true);
	view.setUint32(22, 0, true); // CRC, filled in below
	out[26] = lacing.length;
	out.set(lacing, 27);
	let offset = 27 + lacing.length;
	for (const packet of page.packets) {
		out.set(packet, offset);
		offset += packet.length;
	}
	view.setUint32(22, oggCrc(out), true);
	return out;
}

const segmentsOf = (packet: Uint8Array) => Math.floor(packet.length / 255) + 1;

/**
 * An Ogg Opus file from encoded packets of `packetSamples` 48 kHz samples each (20 ms = 960). `sampleCount` is the
 * real length of the audio: the last page's granule position trims the encoder's padding at the end, so the file
 * lasts exactly as long as the clip (RFC 7845 §4.4).
 */
export function writeOggOpus(options: {
	packets: Uint8Array[];
	packetSamples: number[];
	sampleCount: number;
	preSkip: number;
	channels: number;
	inputSampleRate: number;
	serial: number;
	vendor: string;
}): Uint8Array {
	const { packets, packetSamples, preSkip, serial } = options;
	const pages: Page[] = [
		{
			packets: [opusHead(options.channels, preSkip, options.inputSampleRate)],
			granule: 0n,
			flags: 0x02
		},
		{ packets: [opusTags(options.vendor)], granule: 0n, flags: 0 }
	];
	const end = BigInt(preSkip + options.sampleCount);
	let granule = 0n;
	let current: Uint8Array[] = [];
	let segments = 0;
	const flush = () => {
		if (current.length === 0) return;
		pages.push({ packets: current, granule: granule < end ? granule : end, flags: 0 });
		current = [];
		segments = 0;
	};
	packets.forEach((packet, i) => {
		if (segments + segmentsOf(packet) > MAX_SEGMENTS || current.length >= PACKETS_PER_PAGE) flush();
		current.push(packet);
		segments += segmentsOf(packet);
		granule += BigInt(packetSamples[i] ?? 960);
	});
	flush();
	const last = pages[pages.length - 1];
	last.flags |= 0x04; // end of stream
	if (pages.length > 2) last.granule = end;
	const written = pages.map((page, sequence) => writePage(page, serial, sequence));
	const out = new Uint8Array(written.reduce((sum, p) => sum + p.length, 0));
	let offset = 0;
	for (const page of written) {
		out.set(page, offset);
		offset += page.length;
	}
	return out;
}

export { OPUS_RATE };
