#!/usr/bin/env kotlin
/*
 * Synthesizes every sound of the CC0 demo packs with ffmpeg, so the audio is reproducible and contains no third-party
 * material.
 *
 *   kotlin packs/generate.main.kts        # from the repository root; needs ffmpeg with libopus and packer
 *                                          # (cd tools/packer && ./gradlew installDist)
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
fun sine(phase: String) = "sin(2*PI*($phase))"

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
    // Special clips (#120): a dawn chorus for the chat's first message of the day, three rising chirps and a trill;
    // an impatient squawk for the third message in a row; and a rare cuckoo call, the bird's easter egg.
    Sound(
        "$critters/bird/clips/dawn-chorus.ogg", 1.3,
        "0.35*(" + blip(0.0, 0.25, "1500*T+3000*T*T", 8.0, ::sine) + "+" + blip(0.3, 0.25, "1800*T+3500*T*T", 8.0, ::sine) + "+" +
            blip(0.6, 0.25, "2200*T+4000*T*T", 8.0, ::sine) + "+between(t,0.9,1.3)*sin(2*PI*if(lt(mod(t,0.06),0.03),2600,3000)*t)*(1.3-t)*2)",
    ),
    Sound(
        "$critters/bird/clips/squawk.ogg", 0.5,
        "0.4*(" + blip(0.0, 0.12, "1100*T-2000*T*T", 10.0, ::saw) + "+" + blip(0.16, 0.12, "1200*T-2000*T*T", 10.0, ::saw) + "+" +
            blip(0.32, 0.16, "1300*T-2000*T*T", 8.0, ::saw) + ")",
    ),
    Sound("$critters/bird/clips/cuckoo.ogg", 0.95, "0.4*(" + blip(0.0, 0.32, "784*T", 5.0, ::sine) + "+" + blip(0.42, 0.5, "622*T", 4.0, ::sine) + ")"),
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
    // Kettle reel, a ringtone with an intro, a loop and an outro (pack.yaml: loop 4000..12000 ms, fade-in 500 ms).
    // 0-4 s: the water heats up (bubbles and a rising whistle, both fading to zero at 4 s).
    // 4-12 s: the loop, a jig whistled over bubbles and a bass line. Every part is zero at u = 0 and u = 8 (notes are
    //   sine-shaped, the bass has whole cycles per 2 s), so the seam from 12 s back to 4 s is seamless.
    // 12-14 s: the outro, a ding that never plays in a looping ring file.
    Sound(
        "$kitchen/kettle/ringtones/kettle-reel.ogg", 14.0,
        run {
            val u = "(t-4)"
            val beat = "mod($u,0.25)"
            val note = "mod($u,0.5)"
            val melody = noteTable("mod(floor($u*2),16)", listOf(880, 988, 1109, 1319, 1109, 988, 880, 740, 784, 880, 988, 1175, 988, 880, 784, 880))
            val bass = noteTable("mod(floor($u/2),4)", listOf(110, 147, 131, 98))
            val intro = "(0.3*sin(2*PI*(400+200*sin(t*37))*mod(t,0.12))*exp(-mod(t,0.12)*40)*t/4" +
                "+0.2*sin(2*PI*(600*t+100*t*t))*t/4)*(1-exp(-(4-t)*30))"
            val loop = "0.3*sin(2*PI*$melody*$u)*sin(2*PI*$note)" +
                "+0.2*sin(2*PI*(300+150*sin(8*PI*$beat))*$beat)*exp(-$beat*30)" +
                "+0.12*sin(2*PI*$bass*$u)"
            val outro = "0.35*(sin(2*PI*2093*(t-12))+0.3*sin(2*PI*4186*(t-12)))*exp(-2.5*(t-12))*min((t-12)*200,1)"
            "if(lt(t,4),$intro,if(lt(t,12),$loop,$outro))"
        },
        bitrate = "48k",
    ),
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

// Auto-cut clips: notification clips cut from a ringtone with `packer cut` (the hook finder of pack-spec, shared with
// the app and the pack creators). They are committed like the other sounds, so building the packs needs no ffmpeg.
val packer = File("tools/packer/cli/build/install/packer/bin/packer")
check(packer.canExecute()) { "Build packer first: (cd tools/packer && ./gradlew installDist)" }
data class Cut(val song: String, val clip: String, val args: List<String> = emptyList())
for (c in listOf(Cut("$kitchen/kettle/ringtones/kettle-reel.ogg", "$kitchen/kettle/clips/reel-hook.ogg", listOf("--from-ms", "4000", "--to-ms", "12000")))) {
    val cmd = listOf(packer.path, "cut", File(packsDir, c.song).path, "-o", File(packsDir, c.clip).path) + c.args
    val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    check(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0) { "${cmd.joinToString(" ")} failed:\n$out" }
    print(out)
}
