// Synthesizes the Cucuto UI sounds with ffmpeg (sine/triangle tones, short envelopes) as Ogg Opus.
// Pure math, no samples: the results are original works dedicated to the public domain (CC0-1.0).
import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { out } from './svg.mjs';

const PI2 = '2*PI';
// Envelope helpers (ffmpeg expression language). a = attack seconds, k = exponential decay rate.
const env = (start, a, k) => `between(t,${start},10)*min(1,(t-${start})/${a})*exp(-(t-${start})*${k})`;
const sine = (f, start = 0) => `sin(${PI2}*${f}*(t-${start}))`;
// Soft triangle-ish timbre: fundamental plus a little odd harmonic.
const tri = (f, start = 0) => `(${sine(f, start)}-0.111*${sine(3 * f, start)})`;
// Gentle vibrato for the cuckoo voice.
const flute = (f, start) => `sin(${PI2}*${f}*(t-${start})+0.35*sin(${PI2}*7*(t-${start})))`;
// Note that fades out by `end` (seconds) so nothing clicks.
const gate = (start, end) => `between(t,${start},${end})*min(1,(${end}-t)/0.012)`;

export const SOUNDS = {
  // A soft wooden tick: high, very short. Every tap on a card or chip.
  tap: { dur: 0.05, expr: `0.55*${sine(1760)}*${env(0, 0.002, 95)}+0.18*${sine(3520)}*${env(0, 0.001, 160)}` },
  // Two rising blips a fifth apart: switch on.
  'toggle-on': { dur: 0.11, expr: `0.45*${tri(880)}*${env(0, 0.003, 60)}*${gate(0, 0.05)}+0.45*${tri(1320, 0.045)}*${env(0.045, 0.003, 45)}` },
  // The same blips falling: switch off.
  'toggle-off': { dur: 0.11, expr: `0.45*${tri(1320)}*${env(0, 0.003, 60)}*${gate(0, 0.05)}+0.45*${tri(880, 0.045)}*${env(0.045, 0.003, 45)}` },
  // Major arpeggio C6-E6-G6: done, imported, saved.
  success: { dur: 0.28, expr: [[1046.5, 0], [1318.5, 0.06], [1568, 0.12]].map(([f, s]) => `0.32*${tri(f, s)}*${env(s, 0.004, 22)}`).join('+') },
  // Two low, round notes falling a major third (A4 to F4): soft "nope", never a buzzer.
  error: { dur: 0.24, expr: `0.5*${tri(440)}*${env(0, 0.006, 30)}*${gate(0, 0.1)}+0.5*${tri(349.23, 0.09)}*${env(0.09, 0.006, 22)}` },
  // The signature call, "cu-cu": a flute-like falling major third (G5 to E-flat 5), like a cuckoo clock.
  assign: { dur: 0.29, expr: `0.5*${flute(783.99, 0)}*min(1,t/0.012)*${gate(0, 0.11)}+0.5*${flute(622.25, 0.13)}*${env(0.13, 0.012, 9)}*${gate(0.13, 0.29)}` },
};

mkdirSync(out('sounds'), { recursive: true });
for (const [name, { dur, expr }] of Object.entries(SOUNDS)) {
  execFileSync('ffmpeg', [
    '-loglevel', 'error', '-y',
    '-f', 'lavfi', '-i', `aevalsrc=exprs='${expr}':s=48000:d=${dur}`,
    '-af', 'afade=t=out:st=' + (dur - 0.008).toFixed(3) + ':d=0.008,alimiter=limit=0.7',
    '-c:a', 'libopus', '-b:a', '64k', '-ac', '1',
    '-metadata', `title=cucuto ${name}`, '-metadata', 'artist=cucuto contributors', '-metadata', 'copyright=CC0-1.0',
    out(`sounds/${name}.ogg`),
  ]);
}
console.log('sounds built');
