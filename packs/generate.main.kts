#!/usr/bin/env kotlin
/*
 * Synthesizes every sound of the CC0 demo packs with ffmpeg, so the audio is reproducible and contains no third-party
 * material.
 *
 *   kotlin packs/generate.main.kts        # from the repository root; needs ffmpeg with libopus
 *
 * Each sound is an ffmpeg `aevalsrc` expression (t = time in seconds), faded in and out to avoid clicks and encoded as
 * mono Ogg Opus at 48 kHz. Sprites and covers are hand-written SVG files next to each pack.yaml.
 */

import java.io.File
import java.util.concurrent.TimeUnit

val packsDir = File("packs")
require(File(packsDir, "generate.main.kts").isFile) { "Run from the repository root" }

data class Sound(val path: String, val seconds: Double, val expr: String, val bitrate: String = "32k", val filter: String? = null)

/** Square, saw and triangle waves of a phase given in cycles. */
fun sq(phase: String) = "sgn(sin(2*PI*($phase)))"
fun saw(phase: String) = "(2*(($phase)-floor(($phase)+0.5)))"
fun tri(phase: String) = "(2*abs(${saw(phase)})-1)"

/** A note of [seconds] starting at [start] with frequency [f], shaped by an exponential decay. */
fun blip(start: Double, seconds: Double, phase: String, decay: Double, wave: (String) -> String = ::sq): String {
    val tt = "(t-$start)"
    return "between(t,$start,${start + seconds})*${wave(phase.replace("T", tt))}*exp(-$tt*$decay)"
}

/** Frequency table lookup: nested if() over note index n. */
fun noteTable(n: String, freqs: List<Int>): String =
    freqs.dropLast(1).withIndex().reversed().fold(freqs.last().toString()) { acc, (i, f) -> "if(eq($n,$i),$f,$acc)" }

val critters = "chiptune-critters"
val kitchen = "kitchen-orchestra"

val sounds = listOf(
    // ---------------------------------------------------------------- Chiptune critters
    Sound("$critters/frog/clips/ribbit.ogg", 0.45, "0.35*(" + blip(0.0, 0.14, "220*T-250*T*T", 10.0) + "+" + blip(0.2, 0.18, "200*T-250*T*T", 9.0) + ")"),
    Sound("$critters/frog/clips/croak.ogg", 0.6, "0.3*${sq("300*t-175*t*t")}*(1-t/0.6)"),
    Sound("$critters/frog/clips/hop.ogg", 0.3, "0.3*${sq("200*t+1000*t*t")}*exp(-t*6)"),
    Sound("$critters/bird/clips/chirp.ogg", 0.36, "0.4*sin(2*PI*(2000*mod(t,0.12)+10000*mod(t,0.12)^2))*exp(-mod(t,0.12)*25)"),
    Sound("$critters/bird/clips/trill.ogg", 0.5, "0.22*${sq("if(lt(mod(t,0.08),0.04),1200,1500)*t")}*(1-t/0.5)"),
    Sound("$critters/bird/clips/whistle.ogg", 0.6, "0.4*sin(2*PI*1500*t+3*sin(2*PI*6*t))*sin(PI*t/0.6)"),
    Sound("$critters/cat/clips/mew.ogg", 0.5, "0.35*(sin(2*PI*(600*t-63.66*cos(2*PI*t)))+0.3*sin(4*PI*(600*t-63.66*cos(2*PI*t))))*sin(PI*t/0.5)"),
    Sound("$critters/cat/clips/purr.ogg", 1.2, "0.5*sin(2*PI*80*t)*(0.5+0.5*sin(2*PI*25*t))*sin(PI*t/1.2)"),
    Sound("$critters/cat/clips/mrrp.ogg", 0.4, "0.25*${sq("400*t+300*t*t")}*(0.6+0.4*sin(2*PI*30*t))*sin(PI*t/0.4)"),
    Sound("$critters/bee/clips/buzz.ogg", 0.8, "0.22*${saw("220*t+0.5*sin(2*PI*8*t)")}*sin(PI*t/0.8)"),
    Sound("$critters/bee/clips/zip.ogg", 0.3, "0.22*${saw("300*t+1500*t*t")}*sin(PI*t/0.3)"),
    // Pond song: 8 s loops. Day is a bright square-wave arpeggio over a bass line; night is slower, lower and softer.
    Sound(
        "$critters/frog/ringtones/pond-song-day.ogg", 8.0,
        "0.22*${sq("${noteTable("mod(floor(t*4),8)", listOf(523, 659, 784, 1047, 784, 659, 587, 784))}*t")}*exp(-mod(t,0.25)*8)" +
            "+0.15*${tri("if(lt(mod(t,4),2),131,98)*t")}",
        bitrate = "48k",
    ),
    Sound(
        "$critters/frog/ringtones/pond-song-night.ogg", 8.0,
        "0.35*sin(2*PI*${noteTable("mod(floor(t*2),8)", listOf(262, 330, 392, 330, 294, 392, 330, 262))}*t)*exp(-mod(t,0.5)*3)" +
            "+0.12*sin(2*PI*if(lt(mod(t,4),2),65,49)*t)",
        bitrate = "48k",
    ),
    // ---------------------------------------------------------------- Kitchen orchestra
    Sound("$kitchen/pot/clips/bong.ogg", 1.5, "0.35*(sin(2*PI*110*t)+0.5*sin(2*PI*276*t)+0.3*sin(2*PI*523*t))*exp(-3*t)"),
    Sound("$kitchen/pot/clips/clang.ogg", 0.8, "0.3*(sin(2*PI*340*t)+0.6*sin(2*PI*860*t)+0.4*sin(2*PI*1420*t))*exp(-6*t)"),
    Sound("$kitchen/glass/clips/ting.ogg", 1.0, "0.35*(sin(2*PI*2093*t)+0.3*sin(2*PI*5250*t))*exp(-5*t)"),
    Sound("$kitchen/glass/clips/clink.ogg", 0.6, "0.3*(between(t,0,0.6)*sin(2*PI*1760*t)*exp(-12*t)+between(t,0.15,0.6)*sin(2*PI*2637*(t-0.15))*exp(-12*(t-0.15)))"),
    Sound("$kitchen/whisk/clips/shake.ogg", 0.5, "0.4*(2*random(0)-1)*exp(-mod(t,0.16)*30)", filter = "highpass=f=3000"),
    Sound("$kitchen/whisk/clips/swish.ogg", 0.6, "0.5*(2*random(0)-1)*sin(PI*t/0.6)^2", filter = "bandpass=f=4000:width_type=o:w=2"),
    Sound("$kitchen/kettle/clips/whistle.ogg", 1.5, "0.3*sin(2*PI*(1800*t+200*t*t))*min(t*2,1)+0.05*(2*random(0)-1)"),
    Sound("$kitchen/kettle/clips/bubble.ogg", 1.0, "0.4*sin(2*PI*(400+200*sin(t*37))*mod(t,0.12))*exp(-mod(t,0.12)*40)"),
)

fun ffmpeg(vararg args: String) {
    val cmd = listOf("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *args)
    val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    check(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0) { "${cmd.joinToString(" ")} failed:\n$out" }
}

for (s in sounds) {
    val out = File(packsDir, s.path).apply { parentFile.mkdirs() }
    // Commas inside the expression must be escaped for the filtergraph parser.
    val source = "aevalsrc=exprs='${s.expr.replace(",", "\\,")}':s=48000:d=${s.seconds}"
    val fades = "afade=t=in:d=0.005,afade=t=out:st=${s.seconds - 0.03}:d=0.03"
    val filters = listOfNotNull(s.filter, "alimiter=limit=0.9:level=false", fades).joinToString(",")
    ffmpeg(
        "-f", "lavfi", "-i", source, "-af", filters,
        "-ac", "1", "-ar", "48000", "-c:a", "libopus", "-b:a", s.bitrate,
        "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact",
        out.path,
    )
    println("${out.path}  ${out.length()} bytes")
}
