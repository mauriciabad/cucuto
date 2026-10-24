// Encodes a processed clip (mono floats at 48 kHz) for a pack. Ogg Opus with WebCodecs' AudioEncoder where the
// browser has an Opus encoder (the pack format's recommendation, the same settings as the app's creator and
// `packer --opus`); otherwise 16-bit WAV, which every importer accepts. The brief's WebAssembly fallback would need
// 'wasm-unsafe-eval' in the site's Content Security Policy, so WAV it is (ADR 0038).
import { preSkipOf, writeOggOpus } from './ogg.js';

export interface EncodedClip {
	bytes: Uint8Array;
	/** File extension, without the dot. */
	extension: string;
	/** MIME type, for previews. */
	type: string;
}

export interface EncodeTargets {
	sampleRate: number;
	channels: number;
	clipBitrate: number;
}

/** libopus' encoder delay at 48 kHz, used when the encoder does not report its own OpusHead. */
const DEFAULT_PRE_SKIP = 312;
const VENDOR = 'Cucuto pack creator (WebCodecs)';

let opusSupport: Promise<boolean> | undefined;

function opusConfig(targets: EncodeTargets): AudioEncoderConfig {
	return {
		codec: 'opus',
		sampleRate: targets.sampleRate,
		numberOfChannels: targets.channels,
		bitrate: targets.clipBitrate,
		bitrateMode: 'variable',
		opus: { frameDuration: 20_000, complexity: 10 }
	};
}

/** Whether this browser can write Ogg Opus clips (WebCodecs with an Opus encoder). */
export function canEncodeOpus(targets: EncodeTargets): Promise<boolean> {
	opusSupport ??= (async () => {
		if (typeof AudioEncoder === 'undefined' || typeof AudioData === 'undefined') return false;
		try {
			return (await AudioEncoder.isConfigSupported(opusConfig(targets))).supported === true;
		} catch {
			return false;
		}
	})();
	return opusSupport;
}

/** The clip as Ogg Opus, or as WAV when the browser cannot encode Opus (or the encoder fails). */
export async function encodeClip(
	samples: Float32Array,
	targets: EncodeTargets
): Promise<EncodedClip> {
	if (await canEncodeOpus(targets)) {
		try {
			return { bytes: await encodeOpus(samples, targets), extension: 'ogg', type: 'audio/ogg' };
		} catch (error) {
			console.warn('Opus encoding failed, writing WAV instead', error);
		}
	}
	return { bytes: encodeWav(samples, targets.sampleRate), extension: 'wav', type: 'audio/wav' };
}

async function encodeOpus(samples: Float32Array, targets: EncodeTargets): Promise<Uint8Array> {
	const packets: Uint8Array[] = [];
	const packetSamples: number[] = [];
	let description: Uint8Array | undefined;
	let failure: unknown;
	const encoder = new AudioEncoder({
		output(chunk, metadata) {
			const data = new Uint8Array(chunk.byteLength);
			chunk.copyTo(data);
			packets.push(data);
			packetSamples.push(Math.round(((chunk.duration ?? 20_000) * targets.sampleRate) / 1e6));
			const d = metadata?.decoderConfig?.description;
			if (d && !description) {
				description = ArrayBuffer.isView(d)
					? new Uint8Array(d.buffer, d.byteOffset, d.byteLength).slice()
					: new Uint8Array(d).slice();
			}
		},
		error(e) {
			failure = e;
		}
	});
	try {
		encoder.configure(opusConfig(targets));
		// Pad with silence past the encoder's delay, so every real sample comes out before the stream ends; the last
		// page's granule position then trims the padding again.
		const padded = new Float32Array(samples.length + targets.sampleRate / 10);
		padded.set(samples);
		const step = targets.sampleRate; // one second per AudioData
		for (let offset = 0; offset < padded.length; offset += step) {
			const frames = padded.subarray(offset, Math.min(offset + step, padded.length));
			const data = new AudioData({
				format: 'f32',
				sampleRate: targets.sampleRate,
				numberOfFrames: frames.length,
				numberOfChannels: 1,
				timestamp: Math.round((offset * 1e6) / targets.sampleRate),
				data: frames.slice()
			});
			encoder.encode(data);
			data.close();
		}
		await encoder.flush();
	} finally {
		if (encoder.state !== 'closed') encoder.close();
	}
	if (failure) throw failure;
	if (packets.length === 0) throw new Error('The Opus encoder returned nothing');
	const preSkip = preSkipOf(description) ?? DEFAULT_PRE_SKIP;
	const total = packetSamples.reduce((a, b) => a + b, 0);
	if (total < preSkip + samples.length)
		throw new Error('The Opus encoder returned too little audio');
	return writeOggOpus({
		packets,
		packetSamples,
		sampleCount: samples.length,
		preSkip,
		channels: 1,
		inputSampleRate: targets.sampleRate,
		serial: serialFor(samples),
		vendor: VENDOR
	});
}

/** A stream serial number derived from the audio, so the same clip always gives the same file. */
function serialFor(samples: Float32Array): number {
	let h = 0x811c9dc5;
	const bytes = new Uint8Array(
		samples.buffer,
		samples.byteOffset,
		Math.min(samples.byteLength, 65_536)
	);
	for (const b of bytes) h = Math.imul(h ^ b, 0x01000193);
	return h >>> 0;
}

/** 16-bit PCM WAV, mono. */
export function encodeWav(samples: Float32Array, sampleRate: number): Uint8Array {
	const out = new Uint8Array(44 + samples.length * 2);
	const view = new DataView(out.buffer);
	const ascii = (offset: number, text: string) => {
		for (let i = 0; i < text.length; i++) out[offset + i] = text.charCodeAt(i);
	};
	ascii(0, 'RIFF');
	view.setUint32(4, 36 + samples.length * 2, true);
	ascii(8, 'WAVE');
	ascii(12, 'fmt ');
	view.setUint32(16, 16, true);
	view.setUint16(20, 1, true); // PCM
	view.setUint16(22, 1, true); // mono
	view.setUint32(24, sampleRate, true);
	view.setUint32(28, sampleRate * 2, true);
	view.setUint16(32, 2, true);
	view.setUint16(34, 16, true);
	ascii(36, 'data');
	view.setUint32(40, samples.length * 2, true);
	for (let i = 0; i < samples.length; i++) {
		const s = Math.max(-1, Math.min(1, samples[i]));
		view.setInt16(44 + i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true);
	}
	return out;
}
