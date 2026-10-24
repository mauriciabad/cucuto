// Audio in the browser with the Web Audio API: decode any file the browser can play to mono floats at 48 kHz (the
// rate Opus and the shared clip pipeline use), draw peaks, and play previews. Nothing leaves the device.

export const SAMPLE_RATE = 48_000;

/** Decodes an audio file and mixes it down to mono at [SAMPLE_RATE]; decodeAudioData resamples to the context rate. */
export async function decodeToMono(bytes: ArrayBuffer): Promise<Float32Array> {
	const context = new OfflineAudioContext(1, 1, SAMPLE_RATE);
	const buffer = await context.decodeAudioData(bytes);
	if (buffer.numberOfChannels === 1) return buffer.getChannelData(0).slice();
	const mono = new Float32Array(buffer.length);
	for (let c = 0; c < buffer.numberOfChannels; c++) {
		const channel = buffer.getChannelData(c);
		for (let i = 0; i < mono.length; i++) mono[i] += channel[i];
	}
	for (let i = 0; i < mono.length; i++) mono[i] /= buffer.numberOfChannels;
	return mono;
}

export const durationMs = (samples: Float32Array) => (samples.length * 1000) / SAMPLE_RATE;

/** The highest absolute sample (0..1) in each of `buckets` equal slices. */
export function peaks(samples: Float32Array, buckets: number): Float32Array {
	const out = new Float32Array(buckets);
	if (samples.length === 0) return out;
	for (let b = 0; b < buckets; b++) {
		const from = Math.floor((samples.length * b) / buckets);
		const to = Math.max(from + 1, Math.floor((samples.length * (b + 1)) / buckets));
		let peak = 0;
		for (let i = from; i < to && i < samples.length; i++) {
			const v = Math.abs(samples[i]);
			if (v > peak) peak = v;
		}
		out[b] = Math.min(1, peak);
	}
	return out;
}

let context: AudioContext | undefined;
let playing: AudioBufferSourceNode | undefined;

/**
 * Plays `[startMs, endMs)` of `samples` and resolves when it ends or is stopped. Web Audio rather than an <audio>
 * element: no blob: URL, so the site's Content Security Policy stays as strict as it is.
 */
export function play(samples: Float32Array, startMs = 0, endMs?: number): Promise<void> {
	stop();
	context ??= new AudioContext({ sampleRate: SAMPLE_RATE });
	const ctx = context;
	const from = Math.floor((startMs * SAMPLE_RATE) / 1000);
	const to = endMs === undefined ? samples.length : Math.floor((endMs * SAMPLE_RATE) / 1000);
	const part = samples.subarray(from, Math.max(from + 1, Math.min(to, samples.length)));
	const buffer = ctx.createBuffer(1, part.length, SAMPLE_RATE);
	buffer.copyToChannel(part.slice(), 0);
	const source = ctx.createBufferSource();
	source.buffer = buffer;
	source.connect(ctx.destination);
	playing = source;
	return new Promise((resolve) => {
		source.onended = () => {
			if (playing === source) playing = undefined;
			resolve();
		};
		void ctx.resume().then(() => source.start());
	});
}

export function stop() {
	try {
		playing?.stop();
	} catch {
		// already stopped
	}
	playing = undefined;
}

/** Records from the microphone with MediaRecorder; `stop()` resolves with the decoded audio. */
export interface Recording {
	stop(): Promise<Float32Array>;
	cancel(): void;
	/** Input level 0..1 for a meter. */
	level(): number;
}

export const canRecord = () =>
	typeof navigator !== 'undefined' &&
	!!navigator.mediaDevices?.getUserMedia &&
	typeof MediaRecorder !== 'undefined';

export async function record(): Promise<Recording> {
	const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
	const recorder = new MediaRecorder(stream);
	const chunks: Blob[] = [];
	recorder.ondataavailable = (event) => {
		if (event.data.size > 0) chunks.push(event.data);
	};
	const meterContext = new AudioContext();
	const analyser = meterContext.createAnalyser();
	analyser.fftSize = 1024;
	meterContext.createMediaStreamSource(stream).connect(analyser);
	const frame = new Float32Array(analyser.fftSize);
	const release = () => {
		stream.getTracks().forEach((track) => track.stop());
		void meterContext.close();
	};
	recorder.start();
	return {
		stop: () =>
			new Promise((resolve, reject) => {
				recorder.onstop = async () => {
					release();
					try {
						const blob = new Blob(chunks, { type: recorder.mimeType });
						resolve(await decodeToMono(await blob.arrayBuffer()));
					} catch (error) {
						reject(error instanceof Error ? error : new Error(String(error)));
					}
				};
				recorder.stop();
			}),
		cancel() {
			recorder.onstop = null;
			if (recorder.state !== 'inactive') recorder.stop();
			release();
		},
		level() {
			analyser.getFloatTimeDomainData(frame);
			let peak = 0;
			for (const v of frame) peak = Math.max(peak, Math.abs(v));
			return Math.min(1, peak);
		}
	};
}
